package com.tonyisup.poseguidesnap.importer

import com.tonyisup.poseguidesnap.data.JournaledReferenceAssetStore
import com.tonyisup.poseguidesnap.data.RoomReferenceImportFileJournal
import com.tonyisup.poseguidesnap.data.RoomReferenceImportRepository
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import java.io.File

/** One composition for live imports and restart recovery, sharing protocol and file ownership. */
internal fun createReferenceImportRuntime(
    database: AppDatabase,
    noBackupFilesDirectory: File,
    analyzer: ReferenceImportAnalyzerPort,
    nowEpochMillis: () -> Long = System::currentTimeMillis,
): ReferenceImportRuntime {
    val repository = RoomReferenceImportRepository(database)
    val journal = RoomReferenceImportFileJournal(database)
    val assets = JournaledReferenceAssetStore(noBackupFilesDirectory)
    val importer = JournaledReferencePoseImporter(
        authority = RoomReferenceImportAuthorityAdapter(repository),
        journal = RoomReferenceImportFileJournalAdapter(journal),
        assets = JournaledReferenceAssetStoreAdapter(assets),
        analyzer = analyzer,
    )
    val reconciler = ReferenceImportStartupReconciler(
        authority = RoomReferenceImportRecoveryAuthorityAdapter(repository),
        journal = RoomReferenceImportRecoveryJournalAdapter(journal),
        assets = JournaledReferenceAssetRecoveryAdapter(assets),
    )
    return ReferenceImportRuntime(
        importer = JournaledReferencePickerImporterPort(importer::importReference),
        recovery = {
            reconciler.reconcile { operation ->
                val intent = repository.findExactImportForRecovery(operation.importToken)
                referenceRecoveryTimeline(
                    afterEpochMillis = maxOf(operation.updatedAtEpochMillis, intent?.updatedAtEpochMillis ?: 0L),
                    nowEpochMillis = nowEpochMillis(),
                )
            }
        },
    )
}
