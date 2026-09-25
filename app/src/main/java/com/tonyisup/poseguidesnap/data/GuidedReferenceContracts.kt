package com.tonyisup.poseguidesnap.data

import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseImageSize
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import java.util.Collections

class GuidedReferenceSnapshot internal constructor(
    val poseId: String,
    val label: String,
    val relativeAssetPath: String,
    val mirrorAllowed: Boolean,
    landmarks: Iterable<Landmark>,
    val imageSize: PoseImageSize,
) {
    val landmarks: List<Landmark> =
        Collections.unmodifiableList(ArrayList<Landmark>().apply { addAll(landmarks) })

    init {
        require(ReferenceImportPolicy.validateOwnershipIdentity(poseId))
        require(label.isNotBlank())
        require(VALIDATED_GUIDED_REFERENCE_ASSET_PATH.matches(relativeAssetPath))
        require(this.landmarks.isNotEmpty())
        require(this.landmarks.map(Landmark::type).distinct().size == this.landmarks.size)
    }

    override fun toString(): String = "GuidedReferenceSnapshot(redacted)"
}

sealed interface GuidedCurrentReferenceResult {
    class Ready(val reference: GuidedReferenceSnapshot) : GuidedCurrentReferenceResult {
        override fun toString(): String = "GuidedCurrentReferenceResult.Ready(redacted)"
    }

    data object Completed : GuidedCurrentReferenceResult
    data object ReconciliationRequired : GuidedCurrentReferenceResult
    data object UnknownSession : GuidedCurrentReferenceResult
    data object AuthorityInvalid : GuidedCurrentReferenceResult
}

internal object GuidedReferenceMapper {
    fun map(rows: GuidedSessionBootstrapRows): GuidedCurrentReferenceResult =
        when (val bootstrap = GuidedSessionBootstrapMapper.map(rows)) {
            is GuidedSessionBootstrapResult.Ready -> {
                val pose = rows.poses.getOrNull(bootstrap.snapshot.currentPoseIndex)
                    ?: return GuidedCurrentReferenceResult.AuthorityInvalid
                val reference = pose.toGuidedReferenceOrNull()
                    ?: return GuidedCurrentReferenceResult.AuthorityInvalid
                if (
                    reference.poseId != bootstrap.snapshot.orderedPoseIds[
                        bootstrap.snapshot.currentPoseIndex
                    ]
                ) {
                    GuidedCurrentReferenceResult.AuthorityInvalid
                } else {
                    GuidedCurrentReferenceResult.Ready(reference)
                }
            }
            is GuidedSessionBootstrapResult.Completed -> GuidedCurrentReferenceResult.Completed
            is GuidedSessionBootstrapResult.ReconciliationRequired ->
                GuidedCurrentReferenceResult.ReconciliationRequired
            GuidedSessionBootstrapResult.UnknownSession -> GuidedCurrentReferenceResult.UnknownSession
            is GuidedSessionBootstrapResult.Rejected -> GuidedCurrentReferenceResult.AuthorityInvalid
        }

    private fun GuidedPoseAuthorityRow.toGuidedReferenceOrNull(): GuidedReferenceSnapshot? {
        if (validationState != "VALIDATED") return null
        val assetPath = referenceAssetPath ?: return null
        val payload = landmarkPayload ?: return null
        val preprocessing = preprocessingMetadata ?: return null
        val decoded = decodeReferenceLandmarks(payload) ?: return null
        val imageSize = decodeReferenceImageSize(preprocessing) ?: return null
        return try {
            GuidedReferenceSnapshot(
                poseId = poseId,
                label = label,
                relativeAssetPath = assetPath,
                mirrorAllowed = mirrorAllowed,
                landmarks = decoded,
                imageSize = imageSize,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

internal fun decodeReferenceLandmarks(payload: String): List<Landmark>? {
    if (!payload.startsWith(LANDMARK_PAYLOAD_PREFIX_FOR_GUIDED_REFERENCE)) return null
    val encoded = payload.removePrefix(LANDMARK_PAYLOAD_PREFIX_FOR_GUIDED_REFERENCE)
    if (encoded.isEmpty()) return null
    val rows = encoded.split(';')
    if (rows.size !in 1..PoseLandmark.entries.size) return null
    val landmarks = try {
        rows.map { row ->
            val values = row.split(',')
            if (values.size != 6 || values.any(String::isEmpty)) return null
            Landmark(
                type = PoseLandmark.valueOf(values[0]),
                x = values[1].toDouble(),
                y = values[2].toDouble(),
                z = values[3].toDouble(),
                visibility = values[4].toDouble(),
                presence = values[5].toDouble(),
            )
        }
    } catch (_: RuntimeException) {
        return null
    }
    if (landmarks.map(Landmark::type).distinct().size != landmarks.size) return null
    return if (ReferenceLandmarkPayload.from(landmarks).value == payload) landmarks else null
}

internal fun decodeReferenceImageSize(preprocessingMetadata: String): PoseImageSize? {
    val decodedFields = preprocessingMetadata.split(';').filter { it.startsWith("decoded=") }
    if (decodedFields.size != 1) return null
    val match = GUIDED_REFERENCE_DECODED_SIZE.matchEntire(decodedFields.single()) ?: return null
    val width = match.groupValues[1].toIntOrNull() ?: return null
    val height = match.groupValues[2].toIntOrNull() ?: return null
    if (width !in 1..MAX_GUIDED_REFERENCE_EDGE || height !in 1..MAX_GUIDED_REFERENCE_EDGE) {
        return null
    }
    return PoseImageSize(width, height)
}

private const val LANDMARK_PAYLOAD_PREFIX_FOR_GUIDED_REFERENCE = "v1|"
private const val MAX_GUIDED_REFERENCE_EDGE = 8_192
private val GUIDED_REFERENCE_DECODED_SIZE = Regex("decoded=([1-9][0-9]*)x([1-9][0-9]*)")
private val VALIDATED_GUIDED_REFERENCE_ASSET_PATH =
    Regex("reference-assets/assets/[0-9a-f]{64}\\.asset")
