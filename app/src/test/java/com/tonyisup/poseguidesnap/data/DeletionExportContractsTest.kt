package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionExportContractsTest {
    @Test
    fun deletionPolicyAcceptsOnlyNonblankShootIdsAndNonnegativeTimestamps() {
        assertNull(BeginShootDeletionPolicy.validate("shoot-1", 0L))
        listOf("", " ", "\t\n").forEach { shootId ->
            assertEquals(
                BeginShootDeletionRejectionReason.INVALID_SHOOT_ID,
                BeginShootDeletionPolicy.validate(shootId, 0L),
            )
        }
        assertEquals(
            BeginShootDeletionRejectionReason.INVALID_TIMESTAMP,
            BeginShootDeletionPolicy.validate("shoot-1", -1L),
        )
    }

    @Test
    fun deletionResultsRejectNegativeGenerationsAndCounts() {
        assertThrows(IllegalArgumentException::class.java) {
            BeginShootDeletionResult.Began(-1L, 0, 0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BeginShootDeletionResult.AlreadyDeleting(-1L)
        }
        listOf(
            intArrayOf(-1, 0, 0),
            intArrayOf(0, -1, 0),
            intArrayOf(0, 0, -1),
        ).forEach { counts ->
            assertThrows(IllegalArgumentException::class.java) {
                BeginShootDeletionResult.Began(0L, counts[0], counts[1], counts[2])
            }
        }
    }

    @Test
    fun exportClaimTokenRejectsBlankInputWithoutRenderingItsValue() {
        listOf("", " ", "\t\n").forEach { rawToken ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                ExportClaimToken(rawToken)
            }
            assertEquals("export claim token must be nonblank", failure.message)
        }
    }

    @Test
    fun exportClaimPreservesExactIdentityGenerationAndTargetFields() {
        val identity = identity("exact-output-token")
        val token = ExportClaimToken("exact-claim-token")
        val claim = claim(identity = identity, claimToken = token, deletionGeneration = 7L)

        assertEquals(identity, claim.identity)
        assertEquals(token, claim.claimToken)
        assertEquals(7L, claim.deletionGeneration)
        assertEquals("content://media/external_primary/images/media", claim.targetCollectionUri)
        assertEquals("external_primary", claim.targetVolume)
        assertEquals("pose-guide-0.jpg", claim.intendedDisplayName)
        assertEquals("Pictures/PoseGuideSnap/", claim.intendedRelativePath)
        assertEquals("image/jpeg", claim.intendedMimeType)
    }

    @Test
    fun exportClaimRejectsNegativeDeletionGeneration() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            claim(deletionGeneration = -1L)
        }

        assertEquals("export claim deletion generation must be nonnegative", failure.message)
    }

    @Test
    fun exportClaimReusesStrictCaptureExportTargetValidation() {
        val invalidTargets = listOf(
            targetArguments(targetCollectionUri = "content://media/external_primary/images/media/42"),
            targetArguments(targetVolume = "external/primary"),
            targetArguments(intendedDisplayName = " \t"),
            targetArguments(intendedRelativePath = "Pictures/../Private/"),
            targetArguments(intendedMimeType = "\n"),
        )

        invalidTargets.forEach { target ->
            val targetFailure = assertThrows(IllegalArgumentException::class.java) {
                captureExportTarget(target)
            }
            val claimFailure = assertThrows(IllegalArgumentException::class.java) {
                claim(target = target)
            }
            assertEquals(targetFailure.message, claimFailure.message)
        }
    }

    @Test
    fun claimInputPolicyAcceptsOnlyNonnegativeTimestamps() {
        assertNull(ExportOutputClaimPolicy.validate(0L))
        assertEquals(
            ExportOutputClaimRejectionReason.INVALID_TIMESTAMP,
            ExportOutputClaimPolicy.validate(-1L),
        )
    }

    @Test
    fun onlyFreshAcquisitionGrantsExternalCreateAuthority() {
        val acquired = ExportOutputClaimResult.Acquired(claim())
        assertTrue(acquired.grantsFreshExternalCreateAuthority)

        ExportAuthorityStage.entries.forEach { stage ->
            assertFalse(
                "persisted $stage replay must require reconciliation rather than authorize create",
                ExportOutputClaimResult.IdempotentReplay(stage)
                    .grantsFreshExternalCreateAuthority,
            )
        }
        assertFalse(ExportOutputClaimResult.BlockedByDeletion.grantsFreshExternalCreateAuthority)
        ExportOutputClaimRejectionReason.entries.forEach { reason ->
            assertFalse(
                ExportOutputClaimResult.Rejected(reason).grantsFreshExternalCreateAuthority,
            )
        }
    }

    @Test
    fun sensitiveContractRepresentationsAreExactlyRedacted() {
        val identitySecret = "<SECRET:identity-token>"
        val claimTokenSecret = "<SECRET:claim-token>"
        val secretVolume = "secret_volume_marker"
        val displayNameSecret = "<SECRET:display-name>"
        val relativePathSecret = "<SECRET:relative-path>"
        val mimeTypeSecret = "<SECRET:mime-type>"
        val claimToken = ExportClaimToken(claimTokenSecret)
        val claim = ExportOutputClaim(
            identity = identity(identitySecret),
            claimToken = claimToken,
            deletionGeneration = 4L,
            targetCollectionUri = "content://media/$secretVolume/images/media",
            targetVolume = secretVolume,
            intendedDisplayName = "$displayNameSecret.jpg",
            intendedRelativePath = "Pictures/$relativePathSecret/",
            intendedMimeType = "image/$mimeTypeSecret",
        )
        val acquired = ExportOutputClaimResult.Acquired(claim)

        assertEquals("ExportClaimToken(redacted)", claimToken.toString())
        assertEquals("ExportOutputClaim(redacted)", claim.toString())
        assertEquals("ExportOutputClaimResult.Acquired", acquired.toString())
        listOf(claimToken, claim, acquired).forEach { value ->
            val rendered = value.toString()
            listOf(
                identitySecret,
                claimTokenSecret,
                displayNameSecret,
                relativePathSecret,
                mimeTypeSecret,
            ).forEach { secret -> assertFalse(rendered.contains(secret)) }
            assertFalse(rendered.contains("content://"))
            assertFalse(rendered.contains(secretVolume))
            assertFalse(rendered.contains("Pictures/"))
        }
    }

    @Test
    fun deletionAndClaimResultsRenderOnlyNonsensitiveContractState() {
        val deletionResults = mapOf(
            BeginShootDeletionResult.Began(2L, 1, 2, 3) to
                "BeginShootDeletionResult.Began",
            BeginShootDeletionResult.AlreadyDeleting(2L) to
                "BeginShootDeletionResult.AlreadyDeleting",
            BeginShootDeletionResult.UnknownShoot to
                "BeginShootDeletionResult.UnknownShoot",
            BeginShootDeletionResult.Rejected(
                BeginShootDeletionRejectionReason.AUTHORITY_INCONSISTENT,
            ) to "BeginShootDeletionResult.Rejected(reason=AUTHORITY_INCONSISTENT)",
        )
        val claimResults = mapOf(
            ExportOutputClaimResult.IdempotentReplay(ExportAuthorityStage.CLAIMED) to
                "ExportOutputClaimResult.IdempotentReplay(stage=CLAIMED)",
            ExportOutputClaimResult.BlockedByDeletion to
                "ExportOutputClaimResult.BlockedByDeletion",
            ExportOutputClaimResult.Rejected(
                ExportOutputClaimRejectionReason.OWNED_BY_DIFFERENT_CLAIM,
            ) to "ExportOutputClaimResult.Rejected(reason=OWNED_BY_DIFFERENT_CLAIM)",
        )

        (deletionResults + claimResults).forEach { (result, expected) ->
            assertEquals(expected, result.toString())
            assertFalse(result.toString().contains("<SECRET:"))
        }
        BeginShootDeletionRejectionReason.entries.forEach { reason ->
            assertEquals(reason.name, reason.toString())
        }
        ExportOutputClaimRejectionReason.entries.forEach { reason ->
            assertEquals(reason.name, reason.toString())
        }
        ExportAuthorityStage.entries.forEach { stage ->
            assertEquals(stage.name, stage.toString())
        }
    }

    private fun claim(
        identity: PrivateOutputIdentity = identity("private-output-token"),
        claimToken: ExportClaimToken = ExportClaimToken("export-claim-token"),
        deletionGeneration: Long = 0L,
        target: TargetArguments = targetArguments(),
    ): ExportOutputClaim = ExportOutputClaim(
        identity = identity,
        claimToken = claimToken,
        deletionGeneration = deletionGeneration,
        targetCollectionUri = target.targetCollectionUri,
        targetVolume = target.targetVolume,
        intendedDisplayName = target.intendedDisplayName,
        intendedRelativePath = target.intendedRelativePath,
        intendedMimeType = target.intendedMimeType,
    )

    private fun captureExportTarget(target: TargetArguments): CaptureExportTarget =
        CaptureExportTarget(
            identity = identity("private-output-token"),
            targetCollectionUri = target.targetCollectionUri,
            targetVolume = target.targetVolume,
            intendedDisplayName = target.intendedDisplayName,
            intendedRelativePath = target.intendedRelativePath,
            intendedMimeType = target.intendedMimeType,
        )

    private fun identity(rawToken: String): PrivateOutputIdentity =
        PrivateOutputIdentity(CaptureToken(rawToken), 0)

    private fun targetArguments(
        targetCollectionUri: String = "content://media/external_primary/images/media",
        targetVolume: String = "external_primary",
        intendedDisplayName: String = "pose-guide-0.jpg",
        intendedRelativePath: String = "Pictures/PoseGuideSnap/",
        intendedMimeType: String = "image/jpeg",
    ): TargetArguments = TargetArguments(
        targetCollectionUri = targetCollectionUri,
        targetVolume = targetVolume,
        intendedDisplayName = intendedDisplayName,
        intendedRelativePath = intendedRelativePath,
        intendedMimeType = intendedMimeType,
    )

    private data class TargetArguments(
        val targetCollectionUri: String,
        val targetVolume: String,
        val intendedDisplayName: String,
        val intendedRelativePath: String,
        val intendedMimeType: String,
    )
}
