package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.domain.session.CaptureAttempt
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.CaptureTrigger
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureAttemptPolicyTest {
    @Test
    fun registrationPolicyAcceptsValidReducerCommand() {
        assertNull(
            CaptureAttemptRegistrationPolicy.validate(
                sessionId = "session-1",
                command = command(attemptNumber = 7L),
                recordedAtEpochMillis = 100L,
            ),
        )
    }

    @Test
    fun registrationPolicyRejectsBlankSessionAndNegativeTimestamp() {
        assertEquals(
            AttemptRegistrationRejectionReason.INVALID_SESSION_ID,
            CaptureAttemptRegistrationPolicy.validate(" ", command(), 0L),
        )
        assertEquals(
            AttemptRegistrationRejectionReason.INVALID_TIMESTAMP,
            CaptureAttemptRegistrationPolicy.validate("session-1", command(), -1L),
        )
    }

    @Test
    fun registrationPolicyRejectsCounterExhaustion() {
        assertEquals(
            AttemptRegistrationRejectionReason.COUNTER_EXHAUSTED,
            CaptureAttemptRegistrationPolicy.validate(
                sessionId = "session-1",
                command = command(attemptNumber = Long.MAX_VALUE),
                recordedAtEpochMillis = 0L,
            ),
        )
    }

    @Test
    fun registrationPolicyFailsClosedOnCommandOutputMismatch() {
        val token = CaptureToken("adapter-boundary-secret-token")
        val malformed = malformedCommand(
            token = token,
            outputs = listOf(
                PrivateOutputIdentity(token, 0),
                PrivateOutputIdentity(token, 2),
                PrivateOutputIdentity(token, 1),
            ),
        )

        assertEquals(
            AttemptRegistrationRejectionReason.INVALID_COMMAND_OUTPUTS,
            CaptureAttemptRegistrationPolicy.validate("session-1", malformed, 0L),
        )
    }

    @Test
    fun captureAttemptStartPolicyRejectsBlankSessionAndNegativeTimestamp() {
        assertEquals(
            CaptureAttemptStartRejectionReason.INVALID_SESSION_ID,
            CaptureAttemptStartPolicy.validate("\t", 0L),
        )
        assertEquals(
            CaptureAttemptStartRejectionReason.INVALID_TIMESTAMP,
            CaptureAttemptStartPolicy.validate("session-1", -1L),
        )
        assertNull(CaptureAttemptStartPolicy.validate("session-1", 0L))
    }

    @Test
    fun repositoryResultsAndReasonsHaveRedactedStringRepresentations() {
        val secret = "raw-token/private/path/content://secret/pose-label"
        val values = listOf(
            AttemptRegistrationResult.Registered,
            AttemptRegistrationResult.AlreadyRegistered,
            AttemptRegistrationResult.Rejected(AttemptRegistrationRejectionReason.TOKEN_CONFLICT),
            CaptureAttemptStartResult.Started,
            CaptureAttemptStartResult.AlreadyStarted,
            CaptureAttemptStartResult.BlockedByDeletion,
            CaptureAttemptStartResult.Rejected(CaptureAttemptStartRejectionReason.TOKEN_SESSION_CONFLICT),
            CaptureAttemptStartResult.Rejected(CaptureAttemptStartRejectionReason.INACTIVE_SESSION),
            CaptureAttemptStartResult.Rejected(CaptureAttemptStartRejectionReason.STALE_POSE),
        )

        values.forEach { value ->
            assertFalse(value.toString().contains(secret))
            assertFalse(value.toString().contains("content://"))
            assertFalse(value.toString().contains("private/path"))
        }
    }

    private fun command(
        rawToken: String = "capture-token",
        attemptNumber: Long = 0L,
    ): ShootEffect.CaptureCommand = ShootEffect.CaptureCommand(
        CaptureAttempt.create(
            token = CaptureToken(rawToken),
            trigger = CaptureTrigger.MANUAL,
            poseId = "pose-0",
            poseIndex = 0,
            attemptNumber = attemptNumber,
        ),
    )

    private fun malformedCommand(
        token: CaptureToken,
        outputs: List<PrivateOutputIdentity>,
    ): ShootEffect.CaptureCommand {
        val constructor = ShootEffect.CaptureCommand::class.java.declaredConstructors
            .single { it.parameterTypes.firstOrNull() == String::class.java }
        constructor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return constructor.newInstance(
            token.value,
            CaptureTrigger.MANUAL,
            "pose-0",
            0,
            0L,
            outputs,
        ) as ShootEffect.CaptureCommand
    }
}
