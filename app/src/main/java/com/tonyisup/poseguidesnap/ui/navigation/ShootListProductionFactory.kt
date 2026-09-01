package com.tonyisup.poseguidesnap.ui.navigation

import android.content.Context
import com.tonyisup.poseguidesnap.data.RoomShootPreparationRepository
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.ui.shoots.DeferredCloseAuthority
import com.tonyisup.poseguidesnap.ui.shoots.OwnedShootListWorkflow
import com.tonyisup.poseguidesnap.ui.shoots.RoomShootListRepositoryAdapter
import com.tonyisup.poseguidesnap.ui.shoots.RoomShootListWorkflow
import com.tonyisup.poseguidesnap.ui.shoots.ShootListAuthority
import com.tonyisup.poseguidesnap.ui.shoots.ShootListViewModel

internal class ShootListInitializationSeams<Database, Repository, Workflow, ViewModel>(
    val createDatabase: () -> Database,
    val closeDatabase: (Database) -> Unit,
    val createRepository: (Database) -> Repository,
    val createWorkflow: (Repository, ShootListAuthority) -> Workflow,
    val createViewModel: (Workflow) -> ViewModel,
) where Workflow : OwnedShootListWorkflow

internal fun <Database, Repository, Workflow, ViewModel> initializeOwnedShootList(
    seams: ShootListInitializationSeams<Database, Repository, Workflow, ViewModel>,
): ViewModel where Workflow : OwnedShootListWorkflow {
    val database = seams.createDatabase()
    val authority = DeferredCloseAuthority { seams.closeDatabase(database) }
    var ownershipTransferred = false
    try {
        val repository = seams.createRepository(database)
        val workflow = seams.createWorkflow(repository, authority)
        val viewModel = seams.createViewModel(workflow)
        ownershipTransferred = true
        return viewModel
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

internal fun createShootListViewModel(applicationContext: Context): ShootListViewModel =
    initializeOwnedShootList(
        ShootListInitializationSeams(
            createDatabase = { AppDatabase.create(applicationContext) },
            closeDatabase = AppDatabase::close,
            createRepository = ::RoomShootPreparationRepository,
            createWorkflow = { repository, authority ->
                RoomShootListWorkflow(
                    repository = RoomShootListRepositoryAdapter(repository),
                    authority = authority,
                )
            },
            createViewModel = { workflow ->
                ShootListViewModel(
                    workflow = workflow,
                    closeAuthority = workflow::close,
                )
            },
        ),
    )
