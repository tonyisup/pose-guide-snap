package com.tonyisup.poseguidesnap.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.tonyisup.poseguidesnap.ui.editor.StartedSessionHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartedSessionBootstrapFactoryTest {
    @Test
    fun everyPostAllocationFailureClosesExactlyOnceAndSuppressesCloseFailure() {
        FailureStage.entries.forEach { stage ->
            val database = FakeDatabase(closeFailure = IllegalArgumentException("close-private"))
            val failure = assertThrows(IllegalStateException::class.java) {
                initializeOwnedStartedSessionBootstrap(
                    seams(database, stage),
                )
            }
            assertEquals("primary-$stage", failure.message)
            assertEquals(1, database.closeCount)
            assertEquals(1, failure.suppressed.size)
            assertSame(database.closeFailure, failure.suppressed.single())
        }
    }

    @Test
    fun sameViewModelStoreRetainsOneOwnerAndClearClosesOnce() {
        val database = FakeDatabase()
        var ownerCreations = 0
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                ownerCreations += 1
                return initializeOwnedStartedSessionBootstrap(
                    seams(database, failureStage = null),
                ) as T
            }
        }
        val store = ViewModelStore()

        val first = ViewModelProvider(store, factory)["started", StartedSessionBootstrapViewModel::class.java]
        val retained = ViewModelProvider(store, factory)["started", StartedSessionBootstrapViewModel::class.java]

        assertSame(first, retained)
        assertEquals(1, ownerCreations)
        assertEquals(0, database.closeCount)
        store.clear()
        store.clear()
        assertEquals(1, database.closeCount)
    }

    private fun seams(
        database: FakeDatabase,
        failureStage: FailureStage?,
    ): StartedSessionBootstrapInitializationSeams<FakeDatabase, Unit, FakeWorkflow, StartedSessionBootstrapViewModel> =
        StartedSessionBootstrapInitializationSeams(
            createDatabase = { database },
            closeDatabase = FakeDatabase::close,
            createRepository = {
                if (failureStage == FailureStage.REPOSITORY) error("primary-$failureStage")
            },
            createWorkflow = { _, authority ->
                if (failureStage == FailureStage.WORKFLOW) error("primary-$failureStage")
                FakeWorkflow(authority)
            },
            createOwner = { workflow ->
                if (failureStage == FailureStage.OWNER) error("primary-$failureStage")
                StartedSessionBootstrapViewModel(
                    handle = StartedSessionHandle("session-safe"),
                    workflow = workflow,
                    dispatcher = UnconfinedTestDispatcher(),
                    closeAuthority = workflow::close,
                )
            },
        )

    private enum class FailureStage { REPOSITORY, WORKFLOW, OWNER }

    private class FakeDatabase(val closeFailure: RuntimeException? = null) {
        var closeCount = 0
        fun close() {
            closeCount += 1
            closeFailure?.let { throw it }
        }
    }

    private class FakeWorkflow(
        private val authority: StartedSessionResourceAuthority,
    ) : OwnedStartedSessionBootstrapWorkflow {
        override suspend fun load(
            handle: StartedSessionHandle,
        ): StartedSessionBootstrapState = StartedSessionBootstrapState.Missing

        override fun close() {
            authority.close()
        }
    }
}
