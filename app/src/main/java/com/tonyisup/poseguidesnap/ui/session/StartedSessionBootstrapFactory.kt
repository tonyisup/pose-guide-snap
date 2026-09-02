package com.tonyisup.poseguidesnap.ui.session

import android.content.Context
import com.tonyisup.poseguidesnap.data.RoomShootRepository
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.ui.editor.StartedSessionHandle

internal class StartedSessionBootstrapInitializationSeams<Database, Repository, Workflow, Owner>(
    val createDatabase: () -> Database,
    val closeDatabase: (Database) -> Unit,
    val createRepository: (Database) -> Repository,
    val createWorkflow: (Repository, StartedSessionResourceAuthority) -> Workflow,
    val createOwner: (Workflow) -> Owner,
) where Workflow : OwnedStartedSessionBootstrapWorkflow

internal fun <Database, Repository, Workflow, Owner> initializeOwnedStartedSessionBootstrap(
    seams: StartedSessionBootstrapInitializationSeams<Database, Repository, Workflow, Owner>,
): Owner where Workflow : OwnedStartedSessionBootstrapWorkflow {
    val database = seams.createDatabase()
    val authority = StartedSessionResourceAuthority { seams.closeDatabase(database) }
    var ownershipTransferred = false
    try {
        val repository = seams.createRepository(database)
        val workflow = seams.createWorkflow(repository, authority)
        val owner = seams.createOwner(workflow)
        ownershipTransferred = true
        return owner
    } catch (constructionFailure: Throwable) {
        if (!ownershipTransferred) {
            try {
                authority.close()
            } catch (closeFailure: Throwable) {
                constructionFailure.addSuppressed(closeFailure)
            }
        }
        throw constructionFailure
    }
}

/** Called by a route-scoped ViewModel initializer so Room allocation is retained and lazy by route. */
internal fun createStartedSessionBootstrapViewModel(
    applicationContext: Context,
    handle: StartedSessionHandle,
): StartedSessionBootstrapViewModel = initializeOwnedStartedSessionBootstrap(
    StartedSessionBootstrapInitializationSeams(
        createDatabase = { AppDatabase.create(applicationContext) },
        closeDatabase = AppDatabase::close,
        createRepository = ::RoomShootRepository,
        createWorkflow = { repository, authority ->
            RoomStartedSessionBootstrapWorkflow(
                repository = RoomStartedSessionBootstrapRepositoryAdapter(repository),
                authority = authority,
            )
        },
        createOwner = { workflow ->
            StartedSessionBootstrapViewModel(
                handle = handle,
                workflow = workflow,
                closeAuthority = workflow::close,
            )
        },
    ),
)
