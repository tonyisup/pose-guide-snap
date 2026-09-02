package com.tonyisup.poseguidesnap.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tonyisup.poseguidesnap.data.ShootPreparationLifecycle
import com.tonyisup.poseguidesnap.data.ShootReorderResult
import com.tonyisup.poseguidesnap.importer.ReferenceImportAllocationBlockReason
import com.tonyisup.poseguidesnap.importer.ReferenceImportOutcome
import com.tonyisup.poseguidesnap.importer.ReferenceImportOutcomeStatus
import com.tonyisup.poseguidesnap.importer.ReferenceImportRetryAction
import com.tonyisup.poseguidesnap.importer.ReferencePickerResult
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShootEditorProductionFactoryTest {
    @Test
    fun lazyResourceDefersCreationUntilLeasedUseAndClosesExactlyOnce() {
        val createCount = AtomicInteger()
        val closeCount = AtomicInteger()
        val resource = Any()
        val owner = LazyCloseableResource(
            createResource = {
                createCount.incrementAndGet()
                resource
            },
            closeResource = {
                assertSame(resource, it)
                closeCount.incrementAndGet()
            },
        )

        assertEquals(0, createCount.get())
        assertSame(resource, owner.useResource { it })
        assertSame(resource, owner.useResource { it })
        assertEquals(1, createCount.get())

        owner.close()
        owner.close()
        assertEquals(1, closeCount.get())
        assertThrows(IllegalStateException::class.java) { owner.useResource { it } }
    }

    @Test
    fun productionOwnerFactoryDefersMoveNetCreationOutOfViewModelConstruction() {
        val source = productionFactorySource()
        val ownerFactory = bounded(
            source,
            "internal fun createShootEditorProductionOwner(",
            "private fun createProductionRuntime(",
        )
        val lazyDetector = bounded(
            source,
            "internal class LazyMoveNetReferenceDetectorOwner(",
            "internal class ShootEditorProductionOwner(",
        )

        assertTrue("factory must allocate only the lazy detector owner", "LazyMoveNetReferenceDetectorOwner(applicationContext)" in ownerFactory)
        assertFalse("ViewModel factory must not synchronously create MoveNet", "MoveNetPoseDetector.create" in ownerFactory)
        assertTrue("MoveNet construction must live in the lazy resource factory", "MoveNetPoseDetector.create(applicationContext.applicationContext)" in lazyDetector)
        assertTrue("inference must be the first resource-use boundary", "detector.useResource" in lazyDetector)
    }

    @Test
    fun viewModelStoreRetainsProductionOwnerAndCoordinatorAcrossOwnerRecreation() {
        val closeCount = AtomicInteger()
        val creationCount = AtomicInteger()
        val workflow = FakeWorkflow()
        val pickerCoordinator = pickerCoordinator()
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                creationCount.incrementAndGet()
                return retainedOwner(workflow, pickerCoordinator, closeCount::incrementAndGet) as T
            }
        }

        val first = ViewModelProvider(store, factory)[ShootEditorProductionOwner::class.java]
        val recreated = ViewModelProvider(store, factory)[ShootEditorProductionOwner::class.java]

        assertSame(first, recreated)
        assertSame(pickerCoordinator, first.pickerCoordinator)
        assertEquals(
            setOf("pickerCoordinator", "callbackDispatcher", "pickerLock", "pendingPicker"),
            ShootEditorProductionOwner::class.java.declaredFields
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet(),
        )
        assertEquals(1, creationCount.get())
        assertTrue(
            ShootEditorProductionOwner::class.java.declaredFields.none { field ->
                android.net.Uri::class.java.isAssignableFrom(field.type)
            },
        )
        assertEquals(0, closeCount.get())

        store.clear()
        store.clear()
        assertEquals(1, closeCount.get())
    }

    @Test
    fun retainedOwnerSettlesPickerCallbackAfterDestinationRecreation() = runTest {
        val workflow = RetainedPickerWorkflow()
        val pickerCoordinator = pickerCoordinator()
        val owner = retainedOwner(
            workflow,
            pickerCoordinator,
            close = {},
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        owner.requestImport("Side")
        val effect = owner.effects.first() as ShootEditorEffect.LaunchPhotoPicker
        assertTrue(owner.state.value is ShootEditorUiState.Importing)
        assertTrue(owner.retainPickerRequest(effect.operationId, effect.launch))

        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = owner as T
        }
        val first = ViewModelProvider(store, factory)[ShootEditorProductionOwner::class.java]
        val recreated = ViewModelProvider(store, factory)[ShootEditorProductionOwner::class.java]
        assertSame(first, recreated)

        recreated.onPhotoPickerCallback(null)
        runCurrent()

        val settled = recreated.state.value as ShootEditorUiState.Content
        assertEquals(ShootEditorFeedbackCode.IMPORT_CANCELLED, settled.data.feedback?.code)
        assertFalse(settled.startEligibility == ShootEditorStartEligibility.OPERATION_IN_PROGRESS)
        store.clear()
    }

    @Test
    fun everyFailureStageClosesEveryAcquiredResourceExactlyOnceAndSuppressesCloseFailure() {
        FailureStage.entries.forEach { stage ->
            val database = FakeCloseable("database", failClose = stage == FailureStage.VIEW_MODEL)
            val detector = FakeCloseable("detector")
            val failure = assertThrows(IllegalStateException::class.java) {
                initializeOwnedShootEditor(
                    ShootEditorInitializationSeams(
                        createDatabase = {
                            if (stage == FailureStage.DATABASE) error("database construction")
                            database
                        },
                        closeDatabase = FakeCloseable::close,
                        createDetector = {
                            if (stage == FailureStage.DETECTOR) error("detector construction")
                            detector
                        },
                        closeDetector = FakeCloseable::close,
                        createRuntime = { _, _, _ ->
                            if (stage == FailureStage.RUNTIME) error("runtime construction")
                            ShootEditorRuntimeParts(FakeWorkflow(), Any(), {})
                        },
                        createViewModel = { workflow, picker, close ->
                            if (stage == FailureStage.VIEW_MODEL) error("view model construction")
                            FakeOwner(workflow, picker, close)
                        },
                    ),
                )
            }

            assertTrue(failure.message.orEmpty().contains("construction"))
            assertEquals(if (stage == FailureStage.DATABASE) 0 else 1, database.closeCount)
            assertEquals(if (stage.ordinal >= FailureStage.RUNTIME.ordinal) 1 else 0, detector.closeCount)
            if (stage == FailureStage.VIEW_MODEL) assertEquals(1, failure.suppressed.size)
        }
    }

    @Test
    fun successTransfersOwnershipWithoutEarlyCloseAndOwnerClearClosesOnce() {
        val database = FakeCloseable("database")
        val detector = FakeCloseable("detector")
        val picker = Any()
        val initialized = initializeOwnedShootEditor(
            ShootEditorInitializationSeams(
                createDatabase = { database },
                closeDatabase = FakeCloseable::close,
                createDetector = { detector },
                closeDetector = FakeCloseable::close,
                createRuntime = { _, _, _ -> ShootEditorRuntimeParts(FakeWorkflow(), picker, {}) },
                createViewModel = { workflow, receivedPicker, close ->
                    FakeOwner(workflow, receivedPicker, close)
                },
            ),
        )

        assertSame(picker, initialized.pickerCoordinator)
        assertSame(picker, initialized.viewModel.pickerCoordinator)
        assertEquals(0, database.closeCount)
        assertEquals(0, detector.closeCount)
        initialized.viewModel.clear()
        initialized.viewModel.clear()
        assertEquals(1, database.closeCount)
        assertEquals(1, detector.closeCount)
    }

    @Test
    fun viewModelClearCancelsWorkBeforeCloseAndInvokesCloseOnce() {
        val events = mutableListOf<String>()
        val viewModel = ShootEditorViewModel(
            shootId = "shoot-safe",
            workflow = FakeWorkflow(),
            dispatcher = UnconfinedTestDispatcher(),
            closeAuthority = { events += "close" },
        )

        viewModel.javaClass.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(viewModel)
        viewModel.javaClass.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(viewModel)

        assertEquals(listOf("close"), events)
    }

    private fun productionFactorySource(): String = projectRoot().resolve(
        "app/src/main/java/com/tonyisup/poseguidesnap/ui/editor/ShootEditorProductionFactory.kt",
    ).readText()

    private fun bounded(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end)
        assertTrue("Missing bounded source start: $start", startIndex >= 0)
        assertTrue("Missing bounded source end: $end", endIndex >= 0)
        assertTrue("Invalid bounded source order", startIndex < endIndex)
        return source.substring(startIndex, endIndex)
    }

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root")
    }

    private enum class FailureStage { DATABASE, DETECTOR, RUNTIME, VIEW_MODEL }

    private class FakeCloseable(private val name: String, private val failClose: Boolean = false) {
        var closeCount = 0
        fun close() {
            closeCount += 1
            if (failClose) error("$name close")
        }
    }

    private class FakeOwner(
        val workflow: FakeWorkflow,
        val pickerCoordinator: Any,
        close: () -> Unit,
    ) {
        private var closeAction: (() -> Unit)? = close
        fun clear() { closeAction?.invoke(); closeAction = null }
    }

    private class TestPickerLaunch : ShootEditorPickerLaunch()

    private class RetainedPickerWorkflow : ShootEditorWorkflowPort {
        private val launch = TestPickerLaunch()

        override fun observeEditorSnapshot(shootId: String): Flow<ShootEditorDisplaySnapshot?> = flowOf(
            ShootEditorDisplaySnapshot(
                name = "Fixture",
                lifecycle = ShootPreparationLifecycle.ACTIVE,
                references = listOf(
                    ShootEditorReferenceItem("pose-a", 0, "Front", true),
                    ShootEditorReferenceItem("pose-b", 1, "Side", false),
                    ShootEditorReferenceItem("pose-c", 2, "Back", true),
                ),
                importWorkStatuses = emptyList(),
                hasResumableSession = false,
            ),
        )

        override suspend fun allocateImport(
            shootId: String,
            label: String,
        ): ShootEditorImportAllocationOutcome = ShootEditorImportAllocationOutcome.Ready(launch)

        override fun classifyPickerResult(result: ReferencePickerResult): ReferenceImportOutcome =
            ReferenceImportOutcome(
                ReferenceImportOutcomeStatus.CANCELLED,
                ReferenceImportRetryAction.NONE,
            )

        override suspend fun reorder(
            shootId: String,
            orderedPoseIds: List<String>,
        ): ShootReorderResult = ShootReorderResult.AlreadyOrdered

        override suspend fun start(shootId: String): ShootEditorStartOutcome =
            ShootEditorStartOutcome.Rejected(ShootEditorStartRejectionReason.AUTHORITY_UNAVAILABLE)

        override suspend fun resume(shootId: String): ShootEditorResumeOutcome =
            ShootEditorResumeOutcome.Rejected(ShootEditorResumeRejectionReason.AUTHORITY_UNAVAILABLE)
    }

    private class FakeWorkflow : ShootEditorWorkflowPort {
        override fun observeEditorSnapshot(shootId: String): Flow<ShootEditorDisplaySnapshot?> = flowOf(null)
        override suspend fun allocateImport(shootId: String, label: String): ShootEditorImportAllocationOutcome =
            ShootEditorImportAllocationOutcome.Blocked(
                ReferenceImportAllocationBlockReason.AUTHORITY_UNAVAILABLE,
                ReferenceImportRetryAction.RETRY_ALLOCATION,
            )
        override fun classifyPickerResult(result: ReferencePickerResult): ReferenceImportOutcome =
            ReferenceImportOutcome(ReferenceImportOutcomeStatus.CANCELLED, ReferenceImportRetryAction.NONE)
        override suspend fun reorder(shootId: String, orderedPoseIds: List<String>): ShootReorderResult =
            ShootReorderResult.AuthorityInconsistent
        override suspend fun start(shootId: String): ShootEditorStartOutcome =
            ShootEditorStartOutcome.Rejected(ShootEditorStartRejectionReason.AUTHORITY_UNAVAILABLE)
        override suspend fun resume(shootId: String): ShootEditorResumeOutcome =
            ShootEditorResumeOutcome.Rejected(ShootEditorResumeRejectionReason.AUTHORITY_UNAVAILABLE)
    }

    private fun retainedOwner(
        workflow: ShootEditorWorkflowPort,
        pickerCoordinator: ShootEditorPickerCoordinator,
        close: () -> Unit,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = UnconfinedTestDispatcher(),
    ): ShootEditorProductionOwner = ShootEditorProductionOwner(
        shootId = "shoot-safe",
        workflow = workflow,
        pickerCoordinator = pickerCoordinator,
        dispatcher = dispatcher,
        closeAuthority = close,
    )

    private fun pickerCoordinator(): ShootEditorPickerCoordinator {
        val registry = ShootEditorPickerRegistry()
        val authority = DeferredShootEditorResourceAuthority({}, registry::invalidate)
        return ShootEditorPickerCoordinator(
            registry = registry,
            handler = ShootEditorPickerHandlerPort { _, _ -> ReferencePickerResult.Cancelled },
            authority = authority,
        )
    }
}
