package com.tonyisup.poseguidesnap.ui.navigation

import com.tonyisup.poseguidesnap.ui.shoots.OwnedShootListWorkflow
import com.tonyisup.poseguidesnap.ui.shoots.ShootListAuthority
import com.tonyisup.poseguidesnap.ui.shoots.ShootListCreateOutcome
import com.tonyisup.poseguidesnap.ui.shoots.ShootListPage
import com.tonyisup.poseguidesnap.ui.shoots.ShootListUiState
import com.tonyisup.poseguidesnap.ui.shoots.ShootListViewModel
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShootListProductionFactoryTest {
    @Test
    fun everyPostAllocationConstructionFailureClosesDatabaseExactlyOnceAndRethrows() {
        listOf(FailureStage.REPOSITORY, FailureStage.WORKFLOW, FailureStage.VIEW_MODEL).forEach { stage ->
            val database = FakeDatabase()
            val error = assertThrows(IllegalStateException::class.java) {
                initializeOwnedShootList(
                    ShootListInitializationSeams(
                        createDatabase = { database },
                        closeDatabase = FakeDatabase::close,
                        createRepository = {
                            if (stage == FailureStage.REPOSITORY) error("repository failure")
                            FakeRepository
                        },
                        createWorkflow = { _, authority ->
                            if (stage == FailureStage.WORKFLOW) error("workflow failure")
                            FakeOwnedWorkflow(authority)
                        },
                        createViewModel = { workflow ->
                            if (stage == FailureStage.VIEW_MODEL) error("view model failure")
                            ShootListViewModel(
                                workflow = workflow,
                                dispatcher = UnconfinedTestDispatcher(),
                                closeAuthority = workflow::close,
                            )
                        },
                    ),
                )
            }

            assertTrue(error.message.orEmpty().contains("failure"))
            assertEquals("failure at $stage must close once", 1, database.closeCount)
        }
    }

    @Test
    fun successfulOwnershipTransferDoesNotCloseEarlyAndViewModelClearClosesExactlyOnce() {
        val database = FakeDatabase()
        val viewModel = initializeOwnedShootList(
            ShootListInitializationSeams(
                createDatabase = { database },
                closeDatabase = FakeDatabase::close,
                createRepository = { FakeRepository },
                createWorkflow = { _, authority -> FakeOwnedWorkflow(authority) },
                createViewModel = { workflow ->
                    ShootListViewModel(
                        workflow = workflow,
                        dispatcher = UnconfinedTestDispatcher(),
                        closeAuthority = workflow::close,
                    )
                },
            ),
        )

        assertEquals(0, database.closeCount)
        assertTrue(viewModel.state.value is ShootListUiState.Empty)
        invokeOnCleared(viewModel)
        invokeOnCleared(viewModel)
        assertEquals(1, database.closeCount)
    }

    @Test
    fun appNavHostInitializerDelegatesToNarrowFactoryAndEditorPlaceholderUsesBothInsets() {
        val navigation = source(
            "app/src/main/java/com/tonyisup/poseguidesnap/ui/navigation/AppNavHost.kt",
        )
        val factory = source(
            "app/src/main/java/com/tonyisup/poseguidesnap/ui/navigation/ShootListProductionFactory.kt",
        )
        val initializer = bounded(navigation, "initializer {", "val shootListViewModel")
        assertTrue("initializer must call the ownership helper", "createShootListViewModel(applicationContext)" in initializer)
        listOf("AppDatabase.create", "RoomShootPreparationRepository", "RoomShootListWorkflow", "database::close")
            .forEach { forbidden ->
                assertFalse("AppNavHost must not manually own $forbidden", forbidden in navigation)
            }
        listOf(
            "initializeOwnedShootList(",
            "createDatabase = { AppDatabase.create(applicationContext) }",
            "createRepository = ::RoomShootPreparationRepository",
            "createWorkflow = { repository, authority ->",
            "closeAuthority = workflow::close",
        ).forEach { marker -> assertTrue("Missing production factory marker: $marker", marker in factory) }

        val editor = bounded(navigation, "private fun EditorUnavailableScreen", "private fun FailClosedToList")
        assertTrue("editor must pad below status bars", ".statusBarsPadding()" in editor)
        assertTrue("editor must pad above navigation bars", ".navigationBarsPadding()" in editor)
    }

    private fun invokeOnCleared(viewModel: ShootListViewModel) {
        viewModel.javaClass.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(viewModel)
    }

    private fun bounded(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end)
        assertTrue("Missing bounded source start: $start", startIndex >= 0)
        assertTrue("Missing bounded source end: $end", endIndex >= 0)
        assertTrue("Invalid bounded source order", startIndex < endIndex)
        return source.substring(startIndex, endIndex)
    }

    private fun source(path: String): String = projectRoot().resolve(path).readText()

    private fun projectRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir).absoluteFile, File::getParentFile)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
            ?: error("Could not resolve project root from $userDir")
    }

    private enum class FailureStage { REPOSITORY, WORKFLOW, VIEW_MODEL }

    private class FakeDatabase {
        var closeCount = 0
        fun close() {
            closeCount += 1
        }
    }

    private data object FakeRepository

    private class FakeOwnedWorkflow(
        private val authority: ShootListAuthority,
    ) : OwnedShootListWorkflow {
        override fun observeShootPage(limit: Int, offset: Int): Flow<ShootListPage> =
            flowOf(ShootListPage(emptyList(), hasMore = false))

        override suspend fun createShoot(trimmedName: String): ShootListCreateOutcome =
            ShootListCreateOutcome.Created

        override fun close() {
            authority.close()
        }
    }
}
