package com.tonyisup.poseguidesnap.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.MainActivity
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import com.tonyisup.poseguidesnap.domain.session.ShootEvent
import com.tonyisup.poseguidesnap.domain.session.ShootReducer
import com.tonyisup.poseguidesnap.domain.session.ShootState
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compile-ready Pixel acceptance for the real rear CameraX exactly-three candidate path.
 *
 * This test is intentionally not run by the checkpoint-F2 child. An authorized parent must grant
 * CAMERA, launch it on the named Pixel, and record device/artifact evidence separately. The test
 * uses no fixture image and emits no camera path, token, frame, or exception logging.
 */
@RunWith(AndroidJUnit4::class)
class CameraXThreePhotoCaptureTest {
    @Test
    fun authorizedPixelCapturesExactReducerCommandThenSameTokenCollidesWithoutClobber() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(
            "CAMERA must be granted before authorized Pixel acceptance",
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA),
        )

        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val ready = CountDownLatch(1)
        val cameraFailed = AtomicBoolean(false)
        val previewReference = AtomicReference<PreviewView>()
        instrumentation.runOnMainSync {
            previewReference.set(
                PreviewView(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                },
            )
        }
        val preview = requireNotNull(previewReference.get())
        val controller = CameraXController.create(
            context = context.applicationContext,
            onFrame = { },
            onState = { state ->
                if (state.status == CameraControllerStatus.READY) ready.countDown()
                if (state.status == CameraControllerStatus.FAILED) {
                    cameraFailed.set(true)
                    ready.countDown()
                }
            },
            onFailure = {
                cameraFailed.set(true)
                ready.countDown()
            },
        )
        val callbackExecutor = Executors.newSingleThreadExecutor()
        var capture: CameraXThreePhotoCapture? = null
        var candidateRoot: File? = null
        val knownFinals = ArrayList<File>(3)

        try {
            instrumentation.runOnMainSync {
                activity.setContentView(preview)
                preview.post {
                    val viewPort = preview.viewPort
                    if (viewPort == null) {
                        cameraFailed.set(true)
                        ready.countDown()
                    } else {
                        controller.bind(
                            lifecycleOwner = activity,
                            surfaceProvider = preview.surfaceProvider,
                            viewPort = viewPort,
                            targetRotation = preview.display.rotation,
                        )
                    }
                }
            }
            assertTrue("Rear CameraX controller did not reach a terminal bind state", ready.await(30, TimeUnit.SECONDS))
            assertFalse("Rear CameraX controller failed to bind", cameraFailed.get())
            assertEquals(CameraControllerStatus.READY, controller.state.status)

            capture = CameraXThreePhotoCapture.create(
                controller = controller,
                context = context.applicationContext,
                callbackExecutor = callbackExecutor,
            )
            val command = reducerCommand()
            assertEquals((0..2).toList(), command.outputs.map { it.ordinal })
            assertTrue(command.outputs.all { it.token == command.token })

            val firstDone = CountDownLatch(1)
            val firstSuccess = AtomicReference<ThreePhotoCaptureSuccess>()
            val firstFailure = AtomicReference<ThreePhotoCaptureFailure>()
            assertEquals(
                ThreePhotoCaptureSubmission.ACCEPTED,
                capture.submit(
                    command = command,
                    onSuccess = {
                        firstSuccess.set(it)
                        firstDone.countDown()
                    },
                    onFailure = {
                        firstFailure.set(it)
                        firstDone.countDown()
                    },
                ),
            )
            assertTrue("Exactly-three CameraX capture timed out", firstDone.await(45, TimeUnit.SECONDS))
            assertNull("Unexpected exactly-three capture failure", firstFailure.get())
            val success = requireNotNull(firstSuccess.get())
            assertEquals(command.token, success.token)
            assertEquals(command.outputs, success.outputs.map { it.identity })
            assertEquals(3, success.outputs.size)
            knownFinals += success.outputs.map { it.finalFile }

            val expectedPrefix = sha256(command.token.value)
            val decoded = success.outputs.mapIndexed { ordinal, output ->
                assertEquals("$expectedPrefix-$ordinal.jpg", output.finalFile.name)
                assertFalse(output.finalFile.name.contains(command.token.value))
                assertTrue(output.finalFile.isFile)
                assertTrue(output.finalFile.length() > 0L)
                assertEquals(output.finalFile.length(), output.byteCount)
                val jpegBytes = output.finalFile.readBytes()
                assertTrue(jpegBytes.size >= 4)
                assertArrayEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte()), jpegBytes.copyOfRange(0, 2))
                assertArrayEquals(
                    byteArrayOf(0xff.toByte(), 0xd9.toByte()),
                    jpegBytes.copyOfRange(jpegBytes.size - 2, jpegBytes.size),
                )
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(output.finalFile.absolutePath, bounds)
                assertTrue(bounds.outWidth > 0)
                assertTrue(bounds.outHeight > 0)
                val orientation = ExifInterface(output.finalFile.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED,
                )
                Triple(bounds.outWidth, bounds.outHeight, orientation)
            }
            assertTrue(decoded.all { it == decoded.first() })

            val root = requireNotNull(success.outputs.first().finalFile.parentFile)
            candidateRoot = root
            assertEquals(3, root.listFiles().orEmpty().count { it.extension == "jpg" })
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".pending") })
            val exactHashes = success.outputs.map { sha256(it.finalFile.readBytes()) }

            val repeatDone = CountDownLatch(1)
            val repeatSuccess = AtomicReference<ThreePhotoCaptureSuccess>()
            val repeatFailure = AtomicReference<ThreePhotoCaptureFailure>()
            assertEquals(
                ThreePhotoCaptureSubmission.ACCEPTED,
                capture.submit(
                    command,
                    onSuccess = {
                        repeatSuccess.set(it)
                        repeatDone.countDown()
                    },
                    onFailure = {
                        repeatFailure.set(it)
                        repeatDone.countDown()
                    },
                ),
            )
            assertTrue("Repeated-token collision did not terminate", repeatDone.await(10, TimeUnit.SECONDS))
            assertNull(repeatSuccess.get())
            val collision = requireNotNull(repeatFailure.get())
            assertEquals(ThreePhotoCaptureFailureStage.RECONCILIATION_REQUIRED, collision.stage)
            assertEquals(command.outputs[0], collision.failedIdentity)
            assertTrue(collision.publishedOutputs.isEmpty())
            assertTrue(collision.finalMayExist)
            assertTrue(collision.reconciliationRequired)
            assertFalse(collision.cleanupPending)
            assertEquals(exactHashes, success.outputs.map { sha256(it.finalFile.readBytes()) })
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".pending") })
        } finally {
            capture?.close()
            knownFinals.forEach { final ->
                if (final.exists() && !final.delete()) fail("Could not remove known acceptance final")
            }
            assertTrue(knownFinals.none { it.exists() })
            candidateRoot?.let { root ->
                assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".pending") })
            }
            instrumentation.runOnMainSync {
                controller.close()
                activity.finish()
            }
            callbackExecutor.shutdown()
            assertTrue(callbackExecutor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    private fun reducerCommand(): ShootEffect.CaptureCommand {
        val reducer = ShootReducer()
        var state = ShootState.initial(
            sessionId = "pixel-three-photo-acceptance-v1",
            poseIds = listOf("pose-0", "pose-1", "pose-2"),
        )
        state = reducer.reduce(state, ShootEvent.PreparationCompleted(0L)).nextState
        return reducer.reduce(state, ShootEvent.ManualCaptureRequested(1L)).effects
            .single { it is ShootEffect.CaptureCommand } as ShootEffect.CaptureCommand
    }

    private fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
