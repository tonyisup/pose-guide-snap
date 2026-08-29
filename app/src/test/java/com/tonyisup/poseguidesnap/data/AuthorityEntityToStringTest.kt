package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.data.db.CaptureAttemptEntity
import com.tonyisup.poseguidesnap.data.db.CaptureConfirmationReceiptEntity
import com.tonyisup.poseguidesnap.data.db.CaptureExportOutboxEntity
import com.tonyisup.poseguidesnap.data.db.CaptureExportOutputEntity
import com.tonyisup.poseguidesnap.data.db.PrivateCaptureOutputEntity
import com.tonyisup.poseguidesnap.data.db.ShootEntity
import com.tonyisup.poseguidesnap.data.db.ShootPoseEntity
import com.tonyisup.poseguidesnap.data.db.ShootSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AuthorityEntityToStringTest {
    @Test
    fun allV1RoomEntitiesRedactEverySensitiveStringField() {
        val sensitiveMarkers = mutableListOf<String>()
        fun sensitive(field: String, prefix: String = ""): String =
            "$prefix<SENSITIVE:$field>".also(sensitiveMarkers::add)

        val entities = listOf(
            ShootEntity(
                shootId = sensitive("shoot.shoot-id", "shoots/"),
                name = sensitive("shoot.name", "labels/"),
                createdAtEpochMillis = 101L,
                updatedAtEpochMillis = 102L,
                lifecycleState = sensitive("shoot.lifecycle-state", "metadata/"),
                deletionGeneration = 103L,
            ) to "ShootEntity(redacted)",
            ShootPoseEntity(
                shootId = sensitive("shoot-pose.shoot-id", "shoots/"),
                poseIndex = 201,
                poseId = sensitive("shoot-pose.pose-id", "poses/"),
                label = sensitive("shoot-pose.label", "labels/"),
                referenceAssetPath = sensitive("shoot-pose.reference-asset-path", "reference/private/"),
                mirrorAllowed = true,
                validationState = sensitive("shoot-pose.validation-state", "metadata/"),
                detectorMetadata = sensitive("shoot-pose.detector-metadata", "metadata/"),
                modelMetadata = sensitive("shoot-pose.model-metadata", "metadata/"),
                preprocessingMetadata = sensitive("shoot-pose.preprocessing-metadata", "metadata/"),
            ) to "ShootPoseEntity(redacted)",
            ShootSessionEntity(
                sessionId = sensitive("shoot-session.session-id", "sessions/"),
                shootId = sensitive("shoot-session.shoot-id", "shoots/"),
                currentPoseIndex = 301,
                nextAttemptNumber = 302L,
                lifecycleState = sensitive("shoot-session.lifecycle-state", "metadata/"),
                createdAtEpochMillis = 303L,
                updatedAtEpochMillis = 304L,
            ) to "ShootSessionEntity(redacted)",
            CaptureAttemptEntity(
                commandToken = sensitive("capture-attempt.command-token", "tokens/"),
                sessionId = sensitive("capture-attempt.session-id", "sessions/"),
                poseId = sensitive("capture-attempt.pose-id", "poses/"),
                poseIndex = 401,
                attemptNumber = 402L,
                triggerType = sensitive("capture-attempt.trigger-type", "metadata/"),
                lifecycleState = sensitive("capture-attempt.lifecycle-state", "metadata/"),
                reconciliationRequired = true,
                capturedDeletionGeneration = 403L,
                createdAtEpochMillis = 404L,
                updatedAtEpochMillis = 405L,
                confirmedAtEpochMillis = 406L,
            ) to "CaptureAttemptEntity(redacted)",
            PrivateCaptureOutputEntity(
                commandToken = sensitive("private-capture-output.command-token", "tokens/"),
                burstOrdinal = 501,
                relativePath = sensitive("private-capture-output.relative-path", "private/captures/"),
                byteCount = 502L,
                durabilityState = sensitive("private-capture-output.durability-state", "metadata/"),
                capturedAtEpochMillis = 503L,
                integrityMetadata = sensitive("private-capture-output.integrity-metadata", "metadata/"),
            ) to "PrivateCaptureOutputEntity(redacted)",
            CaptureConfirmationReceiptEntity(
                commandToken = sensitive("capture-confirmation-receipt.command-token", "tokens/"),
                fromPoseIndex = 601,
                toPoseIndex = 602,
                appliedDeletionGeneration = 603L,
                appliedAtEpochMillis = 604L,
            ) to "CaptureConfirmationReceiptEntity(redacted)",
            CaptureExportOutboxEntity(
                commandToken = sensitive("capture-export-outbox.command-token", "tokens/"),
                lifecycleState = sensitive("capture-export-outbox.lifecycle-state", "metadata/"),
                createdAtEpochMillis = 701L,
                updatedAtEpochMillis = 702L,
                retryMetadata = sensitive("capture-export-outbox.retry-metadata", "metadata/"),
            ) to "CaptureExportOutboxEntity(redacted)",
            CaptureExportOutputEntity(
                commandToken = sensitive("capture-export-output.command-token", "tokens/"),
                burstOrdinal = 801,
                targetCollectionUri = sensitive(
                    "capture-export-output.target-collection-uri",
                    "content://media/external/images/target/",
                ),
                targetVolume = sensitive("capture-export-output.target-volume", "volumes/"),
                intendedDisplayName = sensitive("capture-export-output.intended-display-name", "display/"),
                intendedRelativePath = sensitive(
                    "capture-export-output.intended-relative-path",
                    "relative-name/Pictures/PoseGuideSnap/",
                ),
                intendedMimeType = sensitive("capture-export-output.intended-mime-type", "metadata/"),
                lifecycleState = sensitive("capture-export-output.lifecycle-state", "metadata/"),
                claimToken = sensitive("capture-export-output.claim-token", "tokens/"),
                mediaUriString = sensitive(
                    "capture-export-output.exact-media-store-uri",
                    "content://media/external/images/media/",
                ),
                ambiguityState = sensitive("capture-export-output.ambiguity-state", "metadata/"),
                deletionGeneration = 802L,
                createdAtEpochMillis = 803L,
                updatedAtEpochMillis = 804L,
            ) to "CaptureExportOutputEntity(redacted)",
        )

        assertEquals("all eight V1 Room entity types must be covered", 8, entities.size)
        assertEquals("every String/String? constructor field needs its own marker", 37, sensitiveMarkers.size)
        assertEquals("sensitive markers must be distinctive", 37, sensitiveMarkers.toSet().size)

        entities.forEach { (entity, expected) ->
            val rendered = entity.toString()
            assertEquals("${entity.javaClass.simpleName} must be type-only", expected, rendered)
            sensitiveMarkers.forEach { marker ->
                assertFalse("${entity.javaClass.simpleName} leaked $marker", rendered.contains(marker))
            }
            FORBIDDEN_FRAGMENTS.forEach { fragment ->
                assertFalse(
                    "${entity.javaClass.simpleName} leaked sensitive fragment $fragment",
                    rendered.contains(fragment, ignoreCase = true),
                )
            }
        }
    }

    companion object {
        private val FORBIDDEN_FRAGMENTS = listOf(
            "content://",
            "shoots/",
            "sessions/",
            "poses/",
            "private/",
            "reference/",
            "labels/",
            "tokens/",
            "metadata/",
            "display/",
            "relative-name/",
        )
    }
}
