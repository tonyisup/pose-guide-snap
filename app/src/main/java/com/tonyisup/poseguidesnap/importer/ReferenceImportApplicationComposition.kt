package com.tonyisup.poseguidesnap.importer

import com.tonyisup.poseguidesnap.data.RoomReferenceImportRepository
import com.tonyisup.poseguidesnap.data.db.AppDatabase

/** Production construction seam; callers provide only the already-open private database. */
internal object ReferenceImportApplicationComposition {
    fun create(database: AppDatabase): ReferenceImportApplicationService {
        val repository = RoomReferenceImportRepository(database)
        val admission = RoomReferenceImportAdmissionAdapter(repository)
        val identityProvider = ProductionReferenceImportIdentityProvider()
        val timelineProvider = ProductionReferenceImportLedgerTimelineProvider()
        return ReferenceImportApplicationService(
            admission = admission,
            tokenProvider = identityProvider,
            poseIdProvider = identityProvider,
            timelineProvider = timelineProvider,
        )
    }

    override fun toString(): String = "ReferenceImportApplicationComposition(redacted)"
}
