package com.tonyisup.poseguidesnap.importer

import com.tonyisup.poseguidesnap.data.ReferenceImportAdmissionCheckBlockReason
import com.tonyisup.poseguidesnap.data.ReferenceImportAdmissionCheckResult
import com.tonyisup.poseguidesnap.data.RoomReferenceImportRepository

/** Production adapter from Room-owned admission policy to the application boundary. */
internal class RoomReferenceImportAdmissionAdapter(
    private val repository: RoomReferenceImportRepository,
) : ReferenceImportAdmissionPort {
    override fun check(shootId: String): ReferenceImportAdmissionResult =
        repository.checkImportAdmission(shootId).toApplicationAdmissionResult()

    override fun toString(): String = "RoomReferenceImportAdmissionAdapter(redacted)"
}

internal fun ReferenceImportAdmissionCheckResult.toApplicationAdmissionResult():
    ReferenceImportAdmissionResult = when (this) {
    ReferenceImportAdmissionCheckResult.Allowed -> ReferenceImportAdmissionResult.Allowed
    is ReferenceImportAdmissionCheckResult.Blocked -> ReferenceImportAdmissionResult.Blocked(
        when (reason) {
            ReferenceImportAdmissionCheckBlockReason.UNKNOWN_SHOOT ->
                ReferenceImportAdmissionBlockReason.UNKNOWN_SHOOT
            ReferenceImportAdmissionCheckBlockReason.SHOOT_DELETING ->
                ReferenceImportAdmissionBlockReason.SHOOT_DELETING
            ReferenceImportAdmissionCheckBlockReason.PLAYLIST_FULL ->
                ReferenceImportAdmissionBlockReason.PLAYLIST_FULL
            ReferenceImportAdmissionCheckBlockReason.ACTIVE_SESSION ->
                ReferenceImportAdmissionBlockReason.ACTIVE_SESSION
            ReferenceImportAdmissionCheckBlockReason.IMPORT_IN_PROGRESS ->
                ReferenceImportAdmissionBlockReason.IMPORT_IN_PROGRESS
            ReferenceImportAdmissionCheckBlockReason.RECONCILIATION_REQUIRED ->
                ReferenceImportAdmissionBlockReason.RECONCILIATION_REQUIRED
            ReferenceImportAdmissionCheckBlockReason.AUTHORITY_INCONSISTENT ->
                ReferenceImportAdmissionBlockReason.AUTHORITY_INCONSISTENT
        },
    )
}
