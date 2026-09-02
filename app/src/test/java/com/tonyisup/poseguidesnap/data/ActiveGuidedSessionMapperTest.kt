package com.tonyisup.poseguidesnap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ActiveGuidedSessionMapperTest {
    @Test
    fun canonicalSingleActiveSessionAmongCompletedSessionsMapsToExact() {
        val result = ActiveGuidedSessionMapper.map(
            rows(
                shoot(),
                listOf(
                    session(sessionId = "session-0", lifecycleState = "COMPLETED"),
                    session(sessionId = "session-1", lifecycleState = "ACTIVE"),
                ),
            ),
        )

        assertEquals(ActiveGuidedSessionResult.Exact("session-1"), result)
    }

    @Test
    fun knownActiveShootWithoutActiveSessionsMapsToNone() {
        assertEquals(
            ActiveGuidedSessionResult.None,
            ActiveGuidedSessionMapper.map(rows(shoot(), emptyList())),
        )
        assertEquals(
            ActiveGuidedSessionResult.None,
            ActiveGuidedSessionMapper.map(
                rows(
                    shoot(),
                    listOf(session(sessionId = "session-0", lifecycleState = "COMPLETED")),
                ),
            ),
        )
    }

    @Test
    fun absentShootWithoutSessionsMapsToUnknownShoot() {
        assertEquals(
            ActiveGuidedSessionResult.UnknownShoot,
            ActiveGuidedSessionMapper.map(rows(shoot = null, sessions = emptyList())),
        )
    }

    @Test
    fun orphanSessionsWithoutOwningShootAreRejectedInconsistent() {
        assertInconsistent(
            rows(shoot = null, sessions = listOf(session(lifecycleState = "ACTIVE"))),
        )
        assertInconsistent(
            rows(shoot = null, sessions = listOf(session(lifecycleState = "COMPLETED"))),
        )
    }

    @Test
    fun deletingOrUnknownShootLifecycleIsRejectedInconsistent() {
        listOf("DELETING", "COMPLETED", "garbage-lifecycle", "").forEach { lifecycle ->
            assertInconsistent(
                rows(
                    shoot(lifecycleState = lifecycle),
                    listOf(session(lifecycleState = "ACTIVE")),
                ),
            )
        }
    }

    @Test
    fun incoherentShootShapeIsRejectedInconsistent() {
        listOf(
            shoot(shootId = "unsafe/shoot"),
            shoot(name = " "),
            shoot(createdAtEpochMillis = -1L),
            shoot(createdAtEpochMillis = 10L, updatedAtEpochMillis = 9L),
            shoot(deletionGeneration = -1L),
        ).forEach { incoherentShoot ->
            assertInconsistent(
                rows(incoherentShoot, listOf(session(lifecycleState = "ACTIVE"))),
            )
        }
    }

    @Test
    fun multipleActiveSessionsAreRejectedInconsistent() {
        assertInconsistent(
            rows(
                shoot(),
                listOf(
                    session(sessionId = "session-0", lifecycleState = "ACTIVE"),
                    session(sessionId = "session-1", lifecycleState = "ACTIVE"),
                ),
            ),
        )
    }

    @Test
    fun unknownSessionLifecycleAnywhereIsRejectedInconsistent() {
        listOf("DELETING", "garbage-lifecycle", "").forEach { lifecycle ->
            assertInconsistent(
                rows(
                    shoot(),
                    listOf(
                        session(sessionId = "session-0", lifecycleState = lifecycle),
                        session(sessionId = "session-1", lifecycleState = "ACTIVE"),
                    ),
                ),
            )
        }
    }

    @Test
    fun incoherentSessionShapeAnywhereIsRejectedInconsistent() {
        listOf(
            session(sessionId = "unsafe/session", lifecycleState = "ACTIVE"),
            session(lifecycleState = "ACTIVE", shootId = "different-shoot"),
            session(lifecycleState = "ACTIVE", currentPoseIndex = -1),
            session(lifecycleState = "ACTIVE", nextAttemptNumber = -1L),
            session(
                lifecycleState = "ACTIVE",
                createdAtEpochMillis = -1L,
                updatedAtEpochMillis = -1L,
            ),
            session(
                lifecycleState = "ACTIVE",
                createdAtEpochMillis = 10L,
                updatedAtEpochMillis = 9L,
            ),
        ).forEach { incoherentSession ->
            assertInconsistent(rows(shoot(), listOf(incoherentSession)))
        }
        assertInconsistent(
            rows(
                shoot(),
                listOf(
                    session(
                        sessionId = "session-0",
                        lifecycleState = "COMPLETED",
                        nextAttemptNumber = -1L,
                    ),
                    session(sessionId = "session-1", lifecycleState = "ACTIVE"),
                ),
            ),
        )
    }

    @Test
    fun exhaustedAttemptCounterOnActiveSessionIsRejectedInconsistent() {
        assertInconsistent(
            rows(
                shoot(),
                listOf(
                    session(lifecycleState = "ACTIVE", nextAttemptNumber = Long.MAX_VALUE),
                ),
            ),
        )
    }

    @Test
    fun exactConstructorRejectsUnsafeSessionIdentity() {
        listOf("", ".", "..", "unsafe/session", "unsafe session", "a\u0000b", "content://x")
            .forEach { unsafe ->
                assertThrows(IllegalArgumentException::class.java) {
                    ActiveGuidedSessionResult.Exact(unsafe)
                }
            }
    }

    @Test
    fun everyResultVariantRendersWithoutSessionOrShootIdentity() {
        val secret = "SECRET-session-identity"

        assertEquals(
            "ActiveGuidedSessionResult.Exact(redacted)",
            ActiveGuidedSessionResult.Exact(secret).toString(),
        )
        assertEquals(
            "ActiveGuidedSessionResult.None",
            ActiveGuidedSessionResult.None.toString(),
        )
        assertEquals(
            "ActiveGuidedSessionResult.UnknownShoot",
            ActiveGuidedSessionResult.UnknownShoot.toString(),
        )
        ActiveGuidedSessionRejectionReason.entries.forEach { reason ->
            assertEquals(
                "ActiveGuidedSessionResult.Rejected(reason=${reason.name})",
                ActiveGuidedSessionResult.Rejected(reason).toString(),
            )
        }
        assertFalse(
            ActiveGuidedSessionResult.Exact(secret).toString().contains(secret),
        )
    }

    private fun assertInconsistent(candidateRows: ActiveGuidedSessionCandidateRows) {
        assertEquals(
            ActiveGuidedSessionResult.Rejected(
                ActiveGuidedSessionRejectionReason.AUTHORITY_INCONSISTENT,
            ),
            ActiveGuidedSessionMapper.map(candidateRows),
        )
    }

    private fun rows(
        shoot: GuidedShootAuthorityRow?,
        sessions: List<GuidedSessionAuthorityRow>,
    ): ActiveGuidedSessionCandidateRows = ActiveGuidedSessionCandidateRows(shoot, sessions)

    private fun shoot(
        shootId: String = SHOOT_ID,
        name: String = "Discovery shoot",
        createdAtEpochMillis: Long = 1L,
        updatedAtEpochMillis: Long = 2L,
        lifecycleState: String = "ACTIVE",
        deletionGeneration: Long = 0L,
    ): GuidedShootAuthorityRow = GuidedShootAuthorityRow(
        shootId = shootId,
        name = name,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        lifecycleState = lifecycleState,
        deletionGeneration = deletionGeneration,
    )

    private fun session(
        sessionId: String = SESSION_ID,
        shootId: String = SHOOT_ID,
        currentPoseIndex: Int = 0,
        nextAttemptNumber: Long = 0L,
        lifecycleState: String = "ACTIVE",
        createdAtEpochMillis: Long = 1L,
        updatedAtEpochMillis: Long = 2L,
    ): GuidedSessionAuthorityRow = GuidedSessionAuthorityRow(
        sessionId = sessionId,
        shootId = shootId,
        currentPoseIndex = currentPoseIndex,
        nextAttemptNumber = nextAttemptNumber,
        lifecycleState = lifecycleState,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private companion object {
        const val SHOOT_ID = "shoot-1"
        const val SESSION_ID = "session-1"
    }
}
