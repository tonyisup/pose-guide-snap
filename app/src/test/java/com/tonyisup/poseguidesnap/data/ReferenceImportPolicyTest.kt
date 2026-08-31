package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceImportPolicyTest {
    @Test
    fun deterministicPathIsTokenHashBoundWithoutLeakingOwnershipIdentity() {
        val token = ReferenceImportToken("import-token")
        assertEquals(
            "reference-assets/assets/" +
                "7bfec5ef341a225462a03b2b88c486b59c0ac31845783cedc24d008b17dc676e.asset",
            ReferenceImportAssetPath.forToken(token),
        )
        assertFalse(ReferenceImportAssetPath.forToken(token).contains(token.value))
        assertFalse(ReferenceImportAssetPath.forToken(token).contains("shoot-1"))
        assertFalse(ReferenceImportAssetPath.forToken(token).contains("pose-5"))
    }

    @Test
    fun reservationRequiresExactDeterministicPathAndSafeToken() {
        val path = ReferenceImportAssetPath.forToken(ReferenceImportToken("import-token"))
        ReferenceImportReservation(
            ReferenceImportToken("import-token"),
            "shoot-1",
            "pose-5",
            4,
            path,
        )

        listOf("", " ", "content://provider/item").forEach { invalidToken ->
            assertThrows(IllegalArgumentException::class.java) {
                ReferenceImportToken(invalidToken)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReferenceImportReservation(
                ReferenceImportToken("import-token"),
                "shoot-1",
                "pose-5",
                4,
                "reference-assets/assets/not-deterministic.asset",
            )
        }
        listOf("", "..", "with/slash", "content://provider/item").forEach { unsafe ->
            assertThrows(IllegalArgumentException::class.java) {
                ReferenceImportReservation(
                    ReferenceImportToken("import-token"),
                    unsafe,
                    "pose-5",
                    4,
                    path,
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                ReferenceImportReservation(
                    ReferenceImportToken("import-token"),
                    "shoot-1",
                    unsafe,
                    4,
                    path,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReferenceImportReservation(
                ReferenceImportToken("import-token"),
                "shoot-1",
                "pose-5",
                -1,
                path,
            )
        }
    }

    @Test
    fun landmarkPayloadIsCanonicalByLandmarkIdentity() {
        val left = landmark(PoseLandmark.LEFT_SHOULDER, 0.2)
        val nose = landmark(PoseLandmark.NOSE, 0.1)

        val first = ReferenceLandmarkPayload.from(listOf(left, nose))
        val second = ReferenceLandmarkPayload.from(listOf(nose, left))

        assertEquals(first, second)
        assertEquals(
            "v1|NOSE,0.1,0.2,0.0,0.9,0.8;" +
                "LEFT_SHOULDER,0.2,0.30000000000000004,0.0,0.9,0.8",
            first.value,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ReferenceLandmarkPayload.from(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReferenceLandmarkPayload.from(listOf(nose, nose))
        }
    }

    @Test
    fun evidenceRequiresExactIdentityPathAndNonblankUriFreeMetadata() {
        val valid = evidence()
        assertEquals(
            "reference-assets/assets/" +
                "7bfec5ef341a225462a03b2b88c486b59c0ac31845783cedc24d008b17dc676e.asset",
            valid.relativeAssetPath,
        )

        assertThrows(IllegalArgumentException::class.java) {
            evidence(relativeAssetPath = "reference-assets/assets/wrong.asset")
        }
        assertThrows(IllegalArgumentException::class.java) {
            evidence(detectorMetadata = "content://provider/private")
        }
        assertThrows(IllegalArgumentException::class.java) {
            evidence(coordinateMetadata = " ")
        }
    }

    @Test
    fun timestampsAreExplicitlyValidatedWithoutHiddenClock() {
        assertTrue(ReferenceImportPolicy.validateTimestamp(0L))
        assertTrue(ReferenceImportPolicy.validateTimestamp(Long.MAX_VALUE))
        assertFalse(ReferenceImportPolicy.validateTimestamp(-1L))
    }

    @Test
    fun sensitiveContractsAndAllResultsHaveStableRedactedRepresentations() {
        val values = listOf(
            ReferenceImportToken("<SECRET:token>"),
            ReferenceLandmarkPayload.from(listOf(landmark(PoseLandmark.NOSE, 0.1))),
            reservation(),
            evidence(),
            PendingReferenceImport(
                ReferenceImportToken("<SECRET:pending-token>"),
                "secret-shoot",
                "secret-pose",
                4,
                ReferenceImportAssetPath.forToken(ReferenceImportToken("<SECRET:pending-token>")),
                ReferenceImportLifecycle.PREPARING,
                1L,
                2L,
            ),
        )
        values.forEach { value ->
            assertFalse(value.toString().contains("<SECRET:"))
        }

        val results = listOf(
            ReferenceImportReserveResult.Reserved,
            ReferenceImportReserveResult.AlreadyCommitted,
            ReferenceImportReserveResult.ExistingWorkRequiresReconciliation,
            ReferenceImportReserveResult.Rejected(ReferenceImportReserveRejectionReason.TOKEN_CONFLICT),
            ReferenceImportRestartCleanedResult.Restarted,
            ReferenceImportRestartCleanedResult.Rejected(
                ReferenceImportRestartCleanedRejectionReason.WRONG_STATE,
            ),
            ReferenceImportAssetReadyResult.MarkedAssetReady,
            ReferenceImportAssetReadyResult.AlreadyAssetReady,
            ReferenceImportAssetReadyResult.Rejected(ReferenceImportAssetReadyRejectionReason.WRONG_STATE),
            ReferenceImportCommitResult.Committed,
            ReferenceImportCommitResult.AlreadyCommitted,
            ReferenceImportCommitResult.BlockedByDeletion,
            ReferenceImportCommitResult.Rejected(ReferenceImportCommitRejectionReason.EVIDENCE_CONFLICT),
            ReferenceImportSettlementResult.Settled,
            ReferenceImportSettlementResult.AlreadySettled,
            ReferenceImportSettlementResult.Rejected(ReferenceImportSettlementRejectionReason.SETTLEMENT_CONFLICT),
        )
        results.forEach { result ->
            assertFalse(result.toString().contains("<SECRET:"))
            assertFalse(result.toString().contains("content://"))
        }
    }

    private fun reservation(): ReferenceImportReservation = ReferenceImportReservation(
        ReferenceImportToken("<SECRET:token>"),
        "shoot-1",
        "pose-5",
        4,
        ReferenceImportAssetPath.forToken(ReferenceImportToken("<SECRET:token>")),
    )

    private fun evidence(
        relativeAssetPath: String = ReferenceImportAssetPath.forToken(
            ReferenceImportToken("import-token"),
        ),
        detectorMetadata: String = "detector-v1",
        coordinateMetadata: String = "normalized-upright-v1",
    ): ReferenceImportEvidence = ReferenceImportEvidence(
        importToken = ReferenceImportToken("import-token"),
        shootId = "shoot-1",
        poseId = "pose-5",
        poseIndex = 4,
        label = "Reference pose",
        relativeAssetPath = relativeAssetPath,
        mirrorAllowed = true,
        landmarkPayload = ReferenceLandmarkPayload.from(listOf(landmark(PoseLandmark.NOSE, 0.1))),
        detectorMetadata = detectorMetadata,
        modelMetadata = "model-v1",
        preprocessingMetadata = "letterbox-v1",
        coordinateMetadata = coordinateMetadata,
    )

    private fun landmark(type: PoseLandmark, x: Double): Landmark = Landmark(
        type = type,
        x = x,
        y = x + 0.1,
        z = 0.0,
        visibility = 0.9,
        presence = 0.8,
    )
}
