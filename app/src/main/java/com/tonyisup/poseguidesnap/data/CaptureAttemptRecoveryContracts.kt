package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity

internal sealed interface JournalFreeCaptureAttemptCandidateResult {
    data object None : JournalFreeCaptureAttemptCandidateResult

    class Candidate(
        val sessionId: String,
        val token: CaptureToken,
        val authorityUpdatedAtEpochMillis: Long,
    ) : JournalFreeCaptureAttemptCandidateResult {
        init {
            require(authorityUpdatedAtEpochMillis >= 0L)
        }

        val identities: List<PrivateOutputIdentity> =
            (0..2).map { ordinal -> PrivateOutputIdentity(token, ordinal) }

        override fun toString(): String =
            "JournalFreeCaptureAttemptCandidateResult.Candidate(redacted)"
    }

    data object AuthorityInvalid : JournalFreeCaptureAttemptCandidateResult
}
