package com.tonyisup.poseguidesnap.importer

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import com.tonyisup.poseguidesnap.data.MAX_ENCODED_REFERENCE_ASSET_BYTES

import com.tonyisup.poseguidesnap.data.withExclusiveReferenceAssetMutation
import com.tonyisup.poseguidesnap.domain.model.PoseObservation
import com.tonyisup.poseguidesnap.pose.movenet.MoveNetArtifactContract
import com.tonyisup.poseguidesnap.pose.movenet.MoveNetPoseDetector
import com.tonyisup.poseguidesnap.pose.movenet.MoveNetResultMapper
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.math.max

/** Closed, path-free failure stages for the blocking reference analyzer boundary. */
enum class AndroidMoveNetReferenceAnalyzerFailureStage {
    MAIN_THREAD,
    ASSET_VALIDATION,
    ASSET_READ,
    SOURCE_GEOMETRY,
    DECODE,
    INFERENCE,
}

/** A closed analyzer failure that deliberately retains no raw cause or private path. */
class AndroidMoveNetReferenceAnalyzerException internal constructor(
    val stage: AndroidMoveNetReferenceAnalyzerFailureStage,
) : IllegalStateException("reference analysis failed at ${stage.name.lowercase()}") {
    override fun toString(): String = "AndroidMoveNetReferenceAnalyzerException(stage=${stage.name})"
}

/** Injectable composition boundary; it does not transfer detector ownership to the analyzer. */
fun interface MoveNetReferenceDetectorMapperAdapter {
    fun detectAndMapUpright(
        bitmap: Bitmap,
        monotonicTimestampNanos: Long,
    ): PoseObservation
}

/** Real blocking detector/mapper composition. The detector remains caller-owned and is never closed here. */
class BlockingMoveNetReferenceDetectorMapperAdapter(
    private val detector: MoveNetPoseDetector,
    private val mapper: MoveNetResultMapper,
) : MoveNetReferenceDetectorMapperAdapter {
    override fun detectAndMapUpright(
        bitmap: Bitmap,
        monotonicTimestampNanos: Long,
    ): PoseObservation {
        val detection = detector.detectUpright(bitmap)
        return mapper.map(
            rawOutput = detection.rawOutput,
            letterbox = detection.letterbox,
            monotonicTimestampNanos = monotonicTimestampNanos,
        )
    }
}

internal data class ReferenceDecodeGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val decodedWidth: Int,
    val decodedHeight: Int,
)

/** Pure header validation and pre-allocation decode sizing. */
internal object ReferenceDecodeBounds {
    fun geometry(sourceWidth: Int, sourceHeight: Int): ReferenceDecodeGeometry {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            fail(AndroidMoveNetReferenceAnalyzerFailureStage.SOURCE_GEOMETRY)
        }
        val sourcePixels = sourceWidth.toLong() * sourceHeight.toLong()
        if (sourcePixels > AndroidMoveNetReferenceAnalyzer.MAX_SOURCE_IMAGE_PIXELS) {
            fail(AndroidMoveNetReferenceAnalyzerFailureStage.SOURCE_GEOMETRY)
        }

        val longest = max(sourceWidth, sourceHeight)
        if (longest <= AndroidMoveNetReferenceAnalyzer.MAX_DECODED_IMAGE_DIMENSION) {
            return ReferenceDecodeGeometry(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                decodedWidth = sourceWidth,
                decodedHeight = sourceHeight,
            )
        }

        val maximum = AndroidMoveNetReferenceAnalyzer.MAX_DECODED_IMAGE_DIMENSION
        val decodedWidth: Int
        val decodedHeight: Int
        if (sourceWidth >= sourceHeight) {
            decodedWidth = maximum
            decodedHeight = scaledMinorDimension(sourceHeight, sourceWidth, maximum)
        } else {
            decodedWidth = scaledMinorDimension(sourceWidth, sourceHeight, maximum)
            decodedHeight = maximum
        }
        return ReferenceDecodeGeometry(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            decodedWidth = decodedWidth,
            decodedHeight = decodedHeight,
        )
    }

    private fun scaledMinorDimension(minor: Int, major: Int, targetMajor: Int): Int =
        ((minor.toLong() * targetMajor + major / 2L) / major).toInt().coerceAtLeast(1)
}

internal data class ReferenceAnalysisMetadata(
    val detector: String,
    val model: String,
    val preprocessing: String,
    val coordinates: String,
) {
    companion object {
        fun forGeometry(geometry: ReferenceDecodeGeometry): ReferenceAnalysisMetadata =
            ReferenceAnalysisMetadata(
                detector = "adapter=${MoveNetArtifactContract.ADAPTER_NAME};" +
                    "adapterVersion=${MoveNetArtifactContract.ADAPTER_VERSION}",
                model = "model=${MoveNetArtifactContract.MODEL_NAME};" +
                    "modelVersion=${MoveNetArtifactContract.MODEL_VERSION};" +
                    "sha256=${MoveNetArtifactContract.MODEL_SHA_256};" +
                    "runtime=${MoveNetArtifactContract.RUNTIME_NAME};" +
                    "runtimeVersion=${MoveNetArtifactContract.RUNTIME_VERSION}",
                preprocessing = "preprocessing=${MoveNetArtifactContract.PREPROCESSING_NAME};" +
                    "preprocessingVersion=${MoveNetArtifactContract.PREPROCESSING_VERSION};" +
                    "source=${geometry.sourceWidth}x${geometry.sourceHeight};" +
                    "decoded=${geometry.decodedWidth}x${geometry.decodedHeight};" +
                    "target=${MoveNetArtifactContract.INPUT_SIZE}x${MoveNetArtifactContract.INPUT_SIZE}",
                coordinates = "space=normalized-upright-source;origin=top-left;x=right;y=down;z=0;" +
                    "visibility=MoveNet-keypoint-score;presence=MoveNet-keypoint-score;" +
                    "scoreSemantics=visibility-and-presence-are-aliases",
            )
    }
}

/** Pure path-shape/confinement validation, including every app-private relative-path component. */
internal object ReferenceAnalysisAssetContract {
    private val exactAssetPath = Regex("reference-assets/assets/[0-9a-f]{64}\\.asset")

    fun resolveExisting(noBackupRoot: Path, safeRelativePath: String): Path {
        try {
            if (!exactAssetPath.matches(safeRelativePath)) {
                fail(AndroidMoveNetReferenceAnalyzerFailureStage.ASSET_VALIDATION)
            }
            val root = noBackupRoot.toAbsolutePath().normalize()
            val referenceRoot = root.resolve("reference-assets").normalize()
            val assetsRoot = referenceRoot.resolve("assets").normalize()
            val resolved = root.resolve(safeRelativePath).normalize()
            if (!resolved.startsWith(root) ||
                resolved.parent != assetsRoot ||
                resolved.fileName.toString() != safeRelativePath.substringAfterLast('/')
            ) {
                fail(AndroidMoveNetReferenceAnalyzerFailureStage.ASSET_VALIDATION)
            }

            requireDirectoryWithoutSymlink(root)
            requireDirectoryWithoutSymlink(referenceRoot)
            requireDirectoryWithoutSymlink(assetsRoot)
            val leaf = Files.readAttributes(
                resolved,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (!leaf.isRegularFile || Files.isSymbolicLink(resolved)) {
                fail(AndroidMoveNetReferenceAnalyzerFailureStage.ASSET_VALIDATION)
            }
            return resolved
        } catch (failure: AndroidMoveNetReferenceAnalyzerException) {
            throw failure
        } catch (_: Exception) {
            fail(AndroidMoveNetReferenceAnalyzerFailureStage.ASSET_VALIDATION)
        }
    }

    private fun requireDirectoryWithoutSymlink(path: Path) {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!attributes.isDirectory || Files.isSymbolicLink(path)) {
            fail(AndroidMoveNetReferenceAnalyzerFailureStage.ASSET_VALIDATION)
        }
    }
}

/**
 * Blocking analyzer for one already-published app-private reference asset.
 *
 * It owns neither provider URI nor detector lifecycle. Callers must dispatch this boundary off the
 * Android main looper and keep the injected detector alive for the intended composition lifetime.
 */
class AndroidMoveNetReferenceAnalyzer(
    noBackupFilesDirectory: File,
    private val detectorMapperAdapter: MoveNetReferenceDetectorMapperAdapter,
) : ReferenceImportAnalyzerPort {
    private val noBackupRoot = noBackupFilesDirectory.toPath().toAbsolutePath().normalize()

    override fun analyze(asset: DurableReferenceAnalyzerAsset): ReferenceAnalysisEvidence {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            fail(AndroidMoveNetReferenceAnalyzerFailureStage.MAIN_THREAD)
        }

        validateDurableAsset(asset)
        val privateBytes = readPrivatePublishedBytes(asset)
        val decoded = decodeUprightImmutable(privateBytes)
        try {
            val observation = try {
                detectorMapperAdapter.detectAndMapUpright(
                    bitmap = decoded.bitmap,
                    monotonicTimestampNanos = STATIC_REFERENCE_TIMESTAMP_NANOS,
                )
            } catch (_: OutOfMemoryError) {
                fail(AndroidMoveNetReferenceAnalyzerFailureStage.INFERENCE)
            } catch (_: Exception) {
                fail(AndroidMoveNetReferenceAnalyzerFailureStage.INFERENCE)
            }
            val metadata = ReferenceAnalysisMetadata.forGeometry(decoded.geometry)
            return try {
                ReferenceAnalysisEvidence(
                    detectedPersonCount = observation.detectedPersonCount,
                    landmarks = observation.landmarks,
                    detectorMetadata = metadata.detector,
                    modelMetadata = metadata.model,
                    preprocessingMetadata = metadata.preprocessing,
                    coordinateMetadata = metadata.coordinates,
                )
            } catch (_: OutOfMemoryError) {
                fail(AndroidMoveNetReferenceAnalyzerFailureStage.INFERENCE)
            } catch (_: Exception) {
                fail(AndroidMoveNetReferenceAnalyzerFailureStage.INFERENCE)
            }
        } finally {
            decoded.bitmap.recycle()
        }
    }

    private fun validateDurableAsset(asset: DurableReferenceAnalyzerAsset) {
        if (!EXACT_SHA_256.matches(asset.sha256) ||
            asset.byteCount <= 0L ||
            asset.byteCount > MAX_ENCODED_REFERENCE_ASSET_BYTES ||
            asset.byteCount > Int.MAX_VALUE.toLong()
        ) {
            fail(AndroidMoveNetReferenceAnalyzerFailureStage.ASSET_VALIDATION)
        }
        // The exact deterministic ReferenceImportAssetPath shape is checked without needing its token.
        ReferenceAnalysisAssetContract.resolveExisting(noBackupRoot, asset.safeRelativePath)
    }

    private fun readPrivatePublishedBytes(asset: DurableReferenceAnalyzerAsset): ByteArray =
        withExclusiveReferenceAssetMutation {
            val path = ReferenceAnalysisAssetContract.resolveExisting(noBackupRoot, asset.safeRelativePath)
            try {
                val descriptor = Os.open(
                    path.toString(),
                    OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                    0,
                )
                try {
                    val before = Os.fstat(descriptor)
                    if (!OsConstants.S_ISREG(before.st_mode) || before.st_size != asset.byteCount) {
                        fail(AndroidMoveNetReferenceAnalyzerFailureStage.ASSET_READ)
                    }
                    val bytes = ByteArray(asset.byteCount.toInt())
                    FileInputStream(descriptor).use { input ->
                        var offset = 0
                        while (offset < bytes.size) {
                            val read = input.read(bytes, offset, bytes.size - offset)
                            if (read <= 0) fail(AndroidMoveNetReferenceAnalyzerFailureStage.ASSET_READ)
                            offset += read
                        }
                        if (input.read() != -1) fail(AndroidMoveNetReferenceAnalyzerFailureStage.ASSET_READ)
                    }
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
                    if (digest != asset.sha256) {
                        fail(AndroidMoveNetReferenceAnalyzerFailureStage.ASSET_READ)
                    }
                    bytes
                } catch (failure: AndroidMoveNetReferenceAnalyzerException) {
                    tryClose(descriptor)
                    throw failure
                } catch (_: OutOfMemoryError) {
                    tryClose(descriptor)
                    fail(AndroidMoveNetReferenceAnalyzerFailureStage.ASSET_READ)
                } catch (_: Exception) {
                    tryClose(descriptor)
                    fail(AndroidMoveNetReferenceAnalyzerFailureStage.ASSET_READ)
                }
            } catch (failure: AndroidMoveNetReferenceAnalyzerException) {
                throw failure
            } catch (_: OutOfMemoryError) {
                fail(AndroidMoveNetReferenceAnalyzerFailureStage.ASSET_READ)
            } catch (_: Exception) {
                fail(AndroidMoveNetReferenceAnalyzerFailureStage.ASSET_READ)
            }
        }

    private fun decodeUprightImmutable(privateBytes: ByteArray): DecodedReferenceBitmap {
        var headerGeometry: ReferenceDecodeGeometry? = null
        val decoded = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(privateBytes))) {
                    decoder,
                    info,
                    _,
                ->
                val geometry = ReferenceDecodeBounds.geometry(info.size.width, info.size.height)
                headerGeometry = geometry
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                // ImageDecoder applies encoded EXIF orientation; target sizing happens in that upright space.
                decoder.setTargetSize(geometry.decodedWidth, geometry.decodedHeight)
            }
        } catch (failure: AndroidMoveNetReferenceAnalyzerException) {
            throw failure
        } catch (_: OutOfMemoryError) {
            fail(AndroidMoveNetReferenceAnalyzerFailureStage.DECODE)
        } catch (_: Exception) {
            fail(AndroidMoveNetReferenceAnalyzerFailureStage.DECODE)
        }

        try {
            check(!decoded.isMutable)
            val geometry = headerGeometry
                ?: fail(AndroidMoveNetReferenceAnalyzerFailureStage.DECODE)
            if (decoded.width != geometry.decodedWidth || decoded.height != geometry.decodedHeight) {
                fail(AndroidMoveNetReferenceAnalyzerFailureStage.DECODE)
            }
            return DecodedReferenceBitmap(decoded, geometry)
        } catch (failure: AndroidMoveNetReferenceAnalyzerException) {
            decoded.recycle()
            throw failure
        } catch (_: OutOfMemoryError) {
            decoded.recycle()
            fail(AndroidMoveNetReferenceAnalyzerFailureStage.DECODE)
        } catch (_: Exception) {
            decoded.recycle()
            fail(AndroidMoveNetReferenceAnalyzerFailureStage.DECODE)
        }
    }

    private fun tryClose(descriptor: java.io.FileDescriptor) {
        try {
            Os.close(descriptor)
        } catch (_: Exception) {
            // The public failure remains closed and path-free.
        }
    }

    private data class DecodedReferenceBitmap(
        val bitmap: Bitmap,
        val geometry: ReferenceDecodeGeometry,
    )

    companion object {
        /** Header-level source-pixel safety cap, checked before decoder allocation. */
        const val MAX_SOURCE_IMAGE_PIXELS = 40_000_000L

        /** Longest decoded edge after header validation and before bitmap allocation. */
        const val MAX_DECODED_IMAGE_DIMENSION = 2_048

        const val STATIC_REFERENCE_TIMESTAMP_NANOS = 0L

        private val EXACT_SHA_256 = Regex("[0-9a-f]{64}")
    }
}

private fun fail(stage: AndroidMoveNetReferenceAnalyzerFailureStage): Nothing =
    throw AndroidMoveNetReferenceAnalyzerException(stage)

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
