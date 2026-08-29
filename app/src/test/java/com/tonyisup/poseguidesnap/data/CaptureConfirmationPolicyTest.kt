package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureConfirmationPolicyTest {
    @Test
    fun validConfirmationRequestIsAccepted() {
        val command = command()

        assertNull(
            CaptureConfirmationPolicy.validate(
                command = command,
                privateOutputs = privateOutputs(command.token),
                exportTargets = exportTargets(command.token),
                confirmedAtEpochMillis = 0L,
            ),
        )
    }

    @Test
    fun negativeConfirmationTimestampIsRejected() {
        val command = command()

        assertEquals(
            CaptureConfirmationRejectionReason.INVALID_TIMESTAMP,
            CaptureConfirmationPolicy.validate(
                command,
                privateOutputs(command.token),
                exportTargets(command.token),
                -1L,
            ),
        )
    }

    @Test
    fun malformedPrivateOutputIdentityFamiliesFailClosed() {
        val command = command()
        val expected = identities(command.token)
        val foreign = identities(CaptureToken("foreign-private-token"))
        val malformedFamilies = listOf(
            expected.take(2),
            expected + expected.last(),
            expected.reversed(),
            listOf(expected[0], expected[1], expected[1]),
            foreign,
        )

        malformedFamilies.forEach { identities ->
            assertEquals(
                "private identity family $identities must be rejected",
                CaptureConfirmationRejectionReason.INVALID_PRIVATE_OUTPUTS,
                CaptureConfirmationPolicy.validate(
                    command,
                    identities.mapIndexed { index, identity -> privateOutput(index, identity) },
                    exportTargets(command.token),
                    1L,
                ),
            )
        }
    }

    @Test
    fun malformedExportTargetIdentityFamiliesFailClosedSeparately() {
        val command = command()
        val expected = identities(command.token)
        val foreign = identities(CaptureToken("foreign-export-token"))
        val malformedFamilies = listOf(
            expected.take(2),
            expected + expected.last(),
            expected.reversed(),
            listOf(expected[0], expected[1], expected[1]),
            foreign,
        )

        malformedFamilies.forEach { identities ->
            assertEquals(
                "export identity family $identities must be rejected",
                CaptureConfirmationRejectionReason.INVALID_EXPORT_TARGETS,
                CaptureConfirmationPolicy.validate(
                    command,
                    privateOutputs(command.token),
                    identities.mapIndexed { index, identity -> exportTarget(index, identity) },
                    1L,
                ),
            )
        }
    }

    @Test
    fun privateOutputRejectsUnsafeRelativePaths() {
        val invalidPaths = listOf(
            "/absolute/photo.jpg",
            "\\absolute\\photo.jpg",
            "../photo.jpg",
            "captures/../photo.jpg",
            "./photo.jpg",
            "captures/./photo.jpg",
            "captures//photo.jpg",
            "captures\\\\photo.jpg",
            "captures\\..\\photo.jpg",
            "C:/captures/photo.jpg",
            "z:\\captures\\photo.jpg",
            "captures/photo\u0000.jpg",
        )

        invalidPaths.forEach { path ->
            assertThrows("unsafe private path must be rejected", IllegalArgumentException::class.java) {
                privateOutput(identity = identity(), relativePath = path)
            }
        }
    }

    @Test
    fun privateOutputRejectsBlankPath() {
        assertThrows(IllegalArgumentException::class.java) {
            privateOutput(identity = identity(), relativePath = " \t")
        }
    }

    @Test
    fun privateOutputRejectsNonpositiveByteCount() {
        listOf(0L, -1L).forEach { byteCount ->
            assertThrows(IllegalArgumentException::class.java) {
                privateOutput(identity = identity(), byteCount = byteCount)
            }
        }
    }

    @Test
    fun privateOutputRejectsNegativeCaptureTimestamp() {
        assertThrows(IllegalArgumentException::class.java) {
            privateOutput(identity = identity(), capturedAtEpochMillis = -1L)
        }
    }

    @Test
    fun privateOutputRejectsBlankIntegrityMetadataWhenPresent() {
        assertThrows(IllegalArgumentException::class.java) {
            privateOutput(identity = identity(), integrityMetadata = " \t")
        }
    }

    @Test
    fun exportTargetAcceptsExactMediaStoreImageCollectionsForSupportedVolumes() {
        listOf(
            "external",
            "external_primary",
            "internal",
            "0123-4567",
        ).forEach { volume ->
            exportTarget(
                identity = identity(),
                targetCollectionUri = "content://media/$volume/images/media",
                targetVolume = volume,
            )
        }
    }

    @Test
    fun exportTargetRejectsInvalidOrNonCollectionMediaStoreUris() {
        listOf(
            "",
            " ",
            "file:///private/photo.jpg",
            "CONTENT://media/external/images/media",
            "content://",
            "content://media",
            "content:///external/images/media",
            "content://Media/external/images/media",
            "content://media/external/Images/media",
            "content://media/external/images/Media",
            "content://other/external/images/media",
            "content://media/external/video/media",
            "content://media/external/images/thumbnails",
            "content://media/external/images",
            "content://media/external/images/media/42",
            "content://media/external/images/media/",
            "content://media//external/images/media",
            "content://media/external//images/media",
            "content://media/external/images//media",
            "content://media/external/./images/media",
            "content://media/external/../images/media",
            "content://media/external/images/media?limit=1",
            "content://media/external/images/media#fragment",
            "content://media/internal/images/media",
            "content://media/external primary/images/media",
            "content://media/external\\primary/images/media",
            "content://media/external\u0000/images/media",
        ).forEach { uri ->
            assertThrows("invalid collection URI must be rejected", IllegalArgumentException::class.java) {
                exportTarget(identity = identity(), targetCollectionUri = uri)
            }
        }
    }

    @Test
    fun exportTargetRejectsInvalidMediaStoreVolumeSegments() {
        listOf(
            "",
            " ",
            "\t",
            "external primary",
            "external/primary",
            "external\\primary",
            ".",
            "..",
            "external.primary",
            "external?primary",
            "external#primary",
            "external\u0000primary",
            "externál",
        ).forEach { volume ->
            assertThrows("invalid target volume must be rejected", IllegalArgumentException::class.java) {
                exportTarget(
                    identity = identity(),
                    targetCollectionUri = "content://media/$volume/images/media",
                    targetVolume = volume,
                )
            }
        }
    }

    @Test
    fun exportTargetValidationMessagesDoNotContainRejectedValues() {
        val invalidVolume = "<SECRET:invalid-volume>"
        val invalidUri = "content://media/external/images/media?<SECRET:query>"

        val volumeFailure = assertThrows(IllegalArgumentException::class.java) {
            exportTarget(
                identity = identity(),
                targetCollectionUri = "content://media/external/images/media",
                targetVolume = invalidVolume,
            )
        }
        val uriFailure = assertThrows(IllegalArgumentException::class.java) {
            exportTarget(identity = identity(), targetCollectionUri = invalidUri)
        }

        assertFalse(volumeFailure.message.orEmpty().contains(invalidVolume))
        assertFalse(uriFailure.message.orEmpty().contains(invalidUri))
    }

    @Test
    fun exportTargetRejectsBlankRequiredMetadata() {
        val invalidTargets = listOf(
            exportTargetArguments(targetVolume = " "),
            exportTargetArguments(intendedDisplayName = "\t"),
            exportTargetArguments(intendedRelativePath = " "),
            exportTargetArguments(intendedMimeType = "\n"),
        )

        invalidTargets.forEach { arguments ->
            assertThrows(IllegalArgumentException::class.java) {
                CaptureExportTarget(
                    identity = identity(),
                    targetCollectionUri = arguments.targetCollectionUri,
                    targetVolume = arguments.targetVolume,
                    intendedDisplayName = arguments.intendedDisplayName,
                    intendedRelativePath = arguments.intendedRelativePath,
                    intendedMimeType = arguments.intendedMimeType,
                )
            }
        }
    }

    @Test
    fun exportTargetRejectsUnsafeOrUnnormalizedRelativeMediaPaths() {
        val invalidPaths = listOf(
            "/Pictures/PoseGuideSnap/",
            "\\Pictures\\PoseGuideSnap\\",
            "../Pictures/PoseGuideSnap/",
            "Pictures/../PoseGuideSnap/",
            "./Pictures/PoseGuideSnap/",
            "Pictures/./PoseGuideSnap/",
            "Pictures//PoseGuideSnap/",
            "Pictures\\PoseGuideSnap\\",
            "C:/Pictures/PoseGuideSnap/",
            "z:\\Pictures\\PoseGuideSnap\\",
            "Pictures/Pose\u0000GuideSnap/",
            "Pictures/PoseGuideSnap",
            "Pictures/PoseGuideSnap//",
        )

        invalidPaths.forEach { path ->
            assertThrows("unsafe media path must be rejected", IllegalArgumentException::class.java) {
                exportTarget(identity = identity(), intendedRelativePath = path)
            }
        }
    }

    @Test
    fun contractRepresentationsAreStableAndTypeOnly() {
        val secretToken = "<SECRET:confirmation-token>"
        val privateOutput = DurablePrivateOutput(
            identity = PrivateOutputIdentity(CaptureToken(secretToken), 0),
            relativePath = "private/<SECRET:relative-path>/photo.jpg",
            byteCount = 42L,
            capturedAtEpochMillis = 43L,
            integrityMetadata = "<SECRET:integrity-metadata>",
        )
        val exportTarget = CaptureExportTarget(
            identity = PrivateOutputIdentity(CaptureToken(secretToken), 0),
            targetCollectionUri = "content://media/secret_target_volume/images/media",
            targetVolume = "secret_target_volume",
            intendedDisplayName = "<SECRET:display-name>.jpg",
            intendedRelativePath = "Pictures/<SECRET:media-path>/",
            intendedMimeType = "image/<SECRET:mime-type>",
        )

        assertEquals("DurablePrivateOutput(redacted)", privateOutput.toString())
        assertEquals("CaptureExportTarget(redacted)", exportTarget.toString())
        listOf(privateOutput.toString(), exportTarget.toString()).forEach { rendered ->
            assertFalse(rendered.contains("<SECRET:"))
            assertFalse(rendered.contains("content://"))
            assertFalse(rendered.contains("secret_target_volume"))
            assertFalse(rendered.contains("private/"))
        }
    }

    @Test
    fun resultAndReasonRepresentationsAreStableAndIdentifierFree() {
        val representations = mapOf(
            CaptureConfirmationResult.Applied to "CaptureConfirmationResult.Applied",
            CaptureConfirmationResult.AlreadyApplied to "CaptureConfirmationResult.AlreadyApplied",
            CaptureConfirmationResult.BlockedByDeletion to
                "CaptureConfirmationResult.BlockedByDeletion",
            CaptureConfirmationResult.Rejected(
                CaptureConfirmationRejectionReason.TOKEN_POSE_CONFLICT,
            ) to "CaptureConfirmationResult.Rejected(reason=TOKEN_POSE_CONFLICT)",
        )

        representations.forEach { (value, expected) ->
            assertEquals(expected, value.toString())
            assertFalse(value.toString().contains("<SECRET:"))
        }
        CaptureConfirmationRejectionReason.entries.forEach { reason ->
            assertEquals(reason.name, reason.toString())
            assertFalse(reason.toString().contains("<SECRET:"))
        }
    }

    private fun command(
        token: CaptureToken = CaptureToken("confirmation-token"),
    ): ShootEffect.ConfirmAndAdvanceCapture = ShootEffect.ConfirmAndAdvanceCapture(
        token = token,
        poseId = "pose-0",
        poseIndex = 0,
        outputs = identities(token),
    )

    private fun identities(token: CaptureToken): List<PrivateOutputIdentity> =
        (0..2).map { ordinal -> PrivateOutputIdentity(token, ordinal) }

    private fun identity(): PrivateOutputIdentity =
        PrivateOutputIdentity(CaptureToken("output-token"), 0)

    private fun privateOutputs(token: CaptureToken): List<DurablePrivateOutput> =
        identities(token).mapIndexed { index, identity -> privateOutput(index, identity) }

    private fun privateOutput(
        index: Int = 0,
        identity: PrivateOutputIdentity,
        relativePath: String = "captures/photo-$index.jpg",
        byteCount: Long = index + 1L,
        capturedAtEpochMillis: Long = index.toLong(),
        integrityMetadata: String? = null,
    ): DurablePrivateOutput = DurablePrivateOutput(
        identity = identity,
        relativePath = relativePath,
        byteCount = byteCount,
        capturedAtEpochMillis = capturedAtEpochMillis,
        integrityMetadata = integrityMetadata,
    )

    private fun exportTargets(token: CaptureToken): List<CaptureExportTarget> =
        identities(token).mapIndexed { index, identity -> exportTarget(index, identity) }

    private fun exportTarget(
        index: Int = 0,
        identity: PrivateOutputIdentity,
        targetCollectionUri: String = "content://media/external/images/media",
        targetVolume: String = "external",
        intendedDisplayName: String = "photo-$index.jpg",
        intendedRelativePath: String = "Pictures/PoseGuideSnap/",
        intendedMimeType: String = "image/jpeg",
    ): CaptureExportTarget = CaptureExportTarget(
        identity = identity,
        targetCollectionUri = targetCollectionUri,
        targetVolume = targetVolume,
        intendedDisplayName = intendedDisplayName,
        intendedRelativePath = intendedRelativePath,
        intendedMimeType = intendedMimeType,
    )

    private fun exportTargetArguments(
        targetCollectionUri: String = "content://media/external/images/media",
        targetVolume: String = "external",
        intendedDisplayName: String = "photo.jpg",
        intendedRelativePath: String = "Pictures/PoseGuideSnap/",
        intendedMimeType: String = "image/jpeg",
    ): ExportTargetArguments = ExportTargetArguments(
        targetCollectionUri,
        targetVolume,
        intendedDisplayName,
        intendedRelativePath,
        intendedMimeType,
    )

    private data class ExportTargetArguments(
        val targetCollectionUri: String,
        val targetVolume: String,
        val intendedDisplayName: String,
        val intendedRelativePath: String,
        val intendedMimeType: String,
    )
}
