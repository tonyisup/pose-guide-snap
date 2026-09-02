package com.tonyisup.poseguidesnap.data

object ActiveGuidedSessionMapper {
    fun map(rows: ActiveGuidedSessionCandidateRows): ActiveGuidedSessionResult {
        val shoot = rows.shoot
        if (shoot == null) {
            return if (rows.sessions.isEmpty()) {
                ActiveGuidedSessionResult.UnknownShoot
            } else {
                inconsistent()
            }
        }
        if (shoot.lifecycleState != ACTIVE || !shoot.hasCoherentShape()) {
            return inconsistent()
        }
        rows.sessions.forEach { session ->
            if (session.lifecycleState !in KNOWN_SESSION_LIFECYCLES) return inconsistent()
            if (!session.hasCoherentShape(shoot.shootId)) return inconsistent()
        }
        val activeSessions = rows.sessions.filter { it.lifecycleState == ACTIVE }
        val active = when (activeSessions.size) {
            0 -> return ActiveGuidedSessionResult.None
            1 -> activeSessions.single()
            else -> return inconsistent()
        }
        if (active.nextAttemptNumber == Long.MAX_VALUE) {
            return inconsistent()
        }
        return ActiveGuidedSessionResult.Exact(active.sessionId)
    }

    private fun GuidedShootAuthorityRow.hasCoherentShape(): Boolean =
        isSafeOwnershipIdentity(shootId) &&
            name.isNotBlank() &&
            createdAtEpochMillis >= 0L &&
            updatedAtEpochMillis >= createdAtEpochMillis &&
            deletionGeneration >= 0L

    private fun GuidedSessionAuthorityRow.hasCoherentShape(expectedShootId: String): Boolean =
        isSafeOwnershipIdentity(sessionId) &&
            shootId == expectedShootId &&
            currentPoseIndex >= 0 &&
            nextAttemptNumber >= 0L &&
            createdAtEpochMillis >= 0L &&
            updatedAtEpochMillis >= createdAtEpochMillis

    private fun isSafeOwnershipIdentity(value: String): Boolean =
        ReferenceImportPolicy.validateOwnershipIdentity(value)

    private fun inconsistent(): ActiveGuidedSessionResult.Rejected =
        ActiveGuidedSessionResult.Rejected(
            ActiveGuidedSessionRejectionReason.AUTHORITY_INCONSISTENT,
        )

    private const val ACTIVE = "ACTIVE"
    private const val COMPLETED = "COMPLETED"
    private val KNOWN_SESSION_LIFECYCLES = setOf(ACTIVE, COMPLETED)
}
