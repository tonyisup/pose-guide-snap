package com.tonyisup.poseguidesnap.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.tonyisup.poseguidesnap.data.JournaledReferenceAssetStore
import com.tonyisup.poseguidesnap.data.RoomReferenceImportFileJournal
import com.tonyisup.poseguidesnap.data.RoomReferenceImportRepository
import com.tonyisup.poseguidesnap.data.RoomShootPreparationRepository
import com.tonyisup.poseguidesnap.data.RoomShootRepository
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.importer.AndroidMoveNetReferenceAnalyzer
import com.tonyisup.poseguidesnap.importer.BlockingMoveNetReferenceDetectorMapperAdapter
import com.tonyisup.poseguidesnap.importer.ContentResolverReferencePickerByteSourceFactory
import com.tonyisup.poseguidesnap.importer.JournaledReferenceAssetStoreAdapter
import com.tonyisup.poseguidesnap.importer.JournaledReferencePickerImporterPort
import com.tonyisup.poseguidesnap.importer.JournaledReferencePoseImporter
import com.tonyisup.poseguidesnap.importer.MoveNetReferenceDetectorMapperAdapter
import com.tonyisup.poseguidesnap.importer.ReferenceImportApplicationComposition
import com.tonyisup.poseguidesnap.importer.ReferencePickerResultHandler
import com.tonyisup.poseguidesnap.importer.RoomReferenceImportAuthorityAdapter
import com.tonyisup.poseguidesnap.importer.RoomReferenceImportFileJournalAdapter
import com.tonyisup.poseguidesnap.pose.movenet.MoveNetPoseDetector
import com.tonyisup.poseguidesnap.pose.movenet.MoveNetResultMapper
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class ShootEditorRuntimeParts<Workflow, PickerCoordinator>(
    val workflow: Workflow,
    val pickerCoordinator: PickerCoordinator,
    val invalidatePicker: () -> Unit,
)

internal class ShootEditorInitializationSeams<Database, Detector, Workflow, PickerCoordinator, ViewModel>(
    val createDatabase: () -> Database,
    val closeDatabase: (Database) -> Unit,
    val createDetector: () -> Detector,
    val closeDetector: (Detector) -> Unit,
    val createRuntime: (
        Database,
        Detector,
        ShootEditorResourceAuthority,
    ) -> ShootEditorRuntimeParts<Workflow, PickerCoordinator>,
    val createViewModel: (Workflow, PickerCoordinator, () -> Unit) -> ViewModel,
)

internal class InitializedShootEditor<ViewModel, PickerCoordinator>(
    val viewModel: ViewModel,
    val pickerCoordinator: PickerCoordinator,
) {
    override fun toString(): String = "InitializedShootEditor(redacted)"
}

/** Generic failure-injection seam proving explicit, failure-safe resource ownership transfer. */
internal fun <Database, Detector, Workflow, PickerCoordinator, ViewModel> initializeOwnedShootEditor(
    seams: ShootEditorInitializationSeams<Database, Detector, Workflow, PickerCoordinator, ViewModel>,
): InitializedShootEditor<ViewModel, PickerCoordinator> {
    val database = seams.createDatabase()
    val detector = try {
        seams.createDetector()
    } catch (constructionFailure: Throwable) {
        closeSuppressing(constructionFailure) { seams.closeDatabase(database) }
        throw constructionFailure
    }

    val invalidation = AtomicReference<() -> Unit>({})
    val authority = DeferredShootEditorResourceAuthority(
        closeResources = {
            closeBoth(
                closeFirst = { seams.closeDetector(detector) },
                closeSecond = { seams.closeDatabase(database) },
            )
        },
        invalidatePicker = { invalidation.getAndSet({}).invoke() },
    )
    var ownershipTransferred = false
    try {
        val runtime = seams.createRuntime(database, detector, authority)
        invalidation.set(runtime.invalidatePicker)
        val viewModel = seams.createViewModel(
            runtime.workflow,
            runtime.pickerCoordinator,
            authority::close,
        )
        ownershipTransferred = true
        return InitializedShootEditor(viewModel, runtime.pickerCoordinator)
    } catch (constructionFailure: Throwable) {
        if (!ownershipTransferred) closeSuppressing(constructionFailure, authority::close)
        throw constructionFailure
    }
}

internal class LazyCloseableResource<Resource : Any>(
    private val createResource: () -> Resource,
    private val closeResource: (Resource) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var resource: Resource? = null
    private var closed = false

    fun <Result> useResource(block: (Resource) -> Result): Result = synchronized(lock) {
        check(!closed) { "lazy resource is closed" }
        val active = resource ?: createResource().also { created -> resource = created }
        block(active)
    }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return
            closed = true
            resource.also { resource = null }
        }
        if (active != null) closeResource(active)
    }

    override fun toString(): String = "LazyCloseableResource(redacted)"
}

/** Defers model asset reads and LiteRT allocation until leased importer work reaches inference. */
internal class LazyMoveNetReferenceDetectorOwner(
    applicationContext: Context,
    detectorFactory: () -> MoveNetPoseDetector = {
        MoveNetPoseDetector.create(applicationContext.applicationContext)
    },
    private val mapper: MoveNetResultMapper = MoveNetResultMapper(),
) : MoveNetReferenceDetectorMapperAdapter, AutoCloseable {
    private val detector = LazyCloseableResource(detectorFactory, MoveNetPoseDetector::close)

    override fun detectAndMapUpright(
        bitmap: Bitmap,
        monotonicTimestampNanos: Long,
    ): PoseObservation = detector.useResource { active ->
        BlockingMoveNetReferenceDetectorMapperAdapter(active, mapper)
            .detectAndMapUpright(bitmap, monotonicTimestampNanos)
    }

    override fun close() {
        detector.close()
    }

    override fun toString(): String = "LazyMoveNetReferenceDetectorOwner(redacted)"
}

private class RetainedPickerRequest(
    val operationId: ShootEditorOperationId,
    val launch: ShootEditorPickerLaunch,
) {
    override fun toString(): String = "RetainedPickerRequest(redacted)"
}

internal class ShootEditorProductionOwner(
    shootId: String,
    workflow: ShootEditorWorkflowPort,
    internal val pickerCoordinator: ShootEditorPickerCoordinator,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val callbackDispatcher: CoroutineDispatcher = dispatcher,
    closeAuthority: () -> Unit,
) : ShootEditorViewModel(
    shootId = shootId,
    workflow = workflow,
    dispatcher = dispatcher,
    closeAuthority = closeAuthority,
) {
    private val pickerLock = Any()
    private var pendingPicker: RetainedPickerRequest? = null

    internal fun retainPickerRequest(
        operationId: ShootEditorOperationId,
        launch: ShootEditorPickerLaunch,
    ): Boolean = synchronized(pickerLock) {
        val current = state.value
        if (
            pendingPicker != null ||
            current !is ShootEditorUiState.Importing ||
            current.operationId != operationId
        ) {
            false
        } else {
            pendingPicker = RetainedPickerRequest(operationId, launch)
            true
        }
    }

    internal fun onPhotoPickerCallback(uri: Uri?) {
        val pending = consumePickerRequest() ?: return
        viewModelScope.launch(callbackDispatcher) {
            val result = pickerCoordinator.handle(uri, pending.launch)
            onReferencePickerResult(pending.operationId, result)
        }
    }

    internal fun onPhotoPickerLaunchFailed(operationId: ShootEditorOperationId) {
        val pending = consumePickerRequest(operationId) ?: return
        onReferencePickerResult(pending.operationId, com.tonyisup.poseguidesnap.importer.ReferencePickerResult.Cancelled)
    }

    override fun onCleared() {
        synchronized(pickerLock) { pendingPicker = null }
        super.onCleared()
    }

    override fun toString(): String = "ShootEditorProductionOwner(redacted)"

    private fun consumePickerRequest(
        expectedOperationId: ShootEditorOperationId? = null,
    ): RetainedPickerRequest? = synchronized(pickerLock) {
        val current = pendingPicker ?: return@synchronized null
        if (expectedOperationId != null && current.operationId != expectedOperationId) {
            return@synchronized null
        }
        pendingPicker = null
        current
    }
}

/** Creates resources only when a retained editor ViewModel owner is first instantiated. */
internal fun createShootEditorProductionOwner(
    applicationContext: Context,
    shootId: String,
    blockingDispatcher: CoroutineDispatcher = Dispatchers.IO,
): ShootEditorProductionOwner {
    val initialized = initializeOwnedShootEditor(
        ShootEditorInitializationSeams(
            createDatabase = { AppDatabase.create(applicationContext) },
            closeDatabase = AppDatabase::close,
            createDetector = { LazyMoveNetReferenceDetectorOwner(applicationContext) },
            closeDetector = LazyMoveNetReferenceDetectorOwner::close,
            createRuntime = { database, detector, authority ->
                createProductionRuntime(
                    applicationContext,
                    database,
                    detector,
                    authority,
                    blockingDispatcher,
                )
            },
            createViewModel = { workflow, pickerCoordinator, close ->
                ShootEditorProductionOwner(
                    shootId = shootId,
                    workflow = workflow,
                    pickerCoordinator = pickerCoordinator,
                    closeAuthority = close,
                )
            },
        ),
    )
    return initialized.viewModel
}

private fun createProductionRuntime(
    context: Context,
    database: AppDatabase,
    detector: LazyMoveNetReferenceDetectorOwner,
    authority: ShootEditorResourceAuthority,
    blockingDispatcher: CoroutineDispatcher,
): ShootEditorRuntimeParts<RoomShootEditorWorkflow, ShootEditorPickerCoordinator> {
    val registry = ShootEditorPickerRegistry()
    val applicationService = ReferenceImportApplicationComposition.create(database)
    val importRepository = RoomReferenceImportRepository(database)
    val fileJournal = RoomReferenceImportFileJournal(database)
    val assetStore = JournaledReferenceAssetStore(context.noBackupFilesDir)
    val analyzer = AndroidMoveNetReferenceAnalyzer(
        noBackupFilesDirectory = context.noBackupFilesDir,
        detectorMapperAdapter = detector,
    )
    val importer = JournaledReferencePoseImporter(
        authority = RoomReferenceImportAuthorityAdapter(importRepository),
        journal = RoomReferenceImportFileJournalAdapter(fileJournal),
        assets = JournaledReferenceAssetStoreAdapter(assetStore),
        analyzer = analyzer,
    )
    val pickerHandler = ReferencePickerResultHandler(
        importer = JournaledReferencePickerImporterPort(importer::importReference),
        sourceFactory = ContentResolverReferencePickerByteSourceFactory(context.contentResolver),
        dispatcher = blockingDispatcher,
    )
    val workflow = RoomShootEditorWorkflow(
        repository = RoomShootEditorAdapter(RoomShootPreparationRepository(database)),
        activeSessions = RoomShootEditorActiveSessionAdapter(RoomShootRepository(database)),
        imports = ShootEditorImportApplicationAdapter(applicationService),
        pickerRegistry = registry,
        authority = authority,
        blockingDispatcher = blockingDispatcher,
    )
    val coordinator = ShootEditorPickerCoordinator(
        registry = registry,
        handler = ReferencePickerResultHandlerAdapter(pickerHandler),
        authority = authority,
    )
    return ShootEditorRuntimeParts(workflow, coordinator, registry::invalidate)
}

private inline fun closeSuppressing(primary: Throwable, close: () -> Unit) {
    try {
        close()
    } catch (closeFailure: Throwable) {
        primary.addSuppressed(closeFailure)
    }
}

private inline fun closeBoth(closeFirst: () -> Unit, closeSecond: () -> Unit) {
    var firstFailure: Throwable? = null
    try {
        closeFirst()
    } catch (failure: Throwable) {
        firstFailure = failure
    }
    try {
        closeSecond()
    } catch (failure: Throwable) {
        val existing = firstFailure
        if (existing == null) firstFailure = failure else existing.addSuppressed(failure)
    }
    firstFailure?.let { throw it }
}
