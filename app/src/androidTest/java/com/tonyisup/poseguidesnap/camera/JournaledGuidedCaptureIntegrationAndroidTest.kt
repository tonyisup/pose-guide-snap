package com.tonyisup.poseguidesnap.camera

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.MainActivity
import com.tonyisup.poseguidesnap.data.CaptureFileOperationPaths
import com.tonyisup.poseguidesnap.data.GuidedCurrentReferenceResult
import com.tonyisup.poseguidesnap.data.ReferenceLandmarkPayload
import com.tonyisup.poseguidesnap.data.RoomShootRepository
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.data.deleteRoomTestDatabase
import com.tonyisup.poseguidesnap.data.roomTestDatabaseResidue
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import com.tonyisup.poseguidesnap.domain.session.ShootEvent
import com.tonyisup.poseguidesnap.domain.session.ShootMode
import com.tonyisup.poseguidesnap.domain.session.ShootReducer
import com.tonyisup.poseguidesnap.domain.session.ShootState
import com.tonyisup.poseguidesnap.domain.model.Landmark
import com.tonyisup.poseguidesnap.domain.model.PoseImageSize
import com.tonyisup.poseguidesnap.domain.model.PoseLandmark
import com.tonyisup.poseguidesnap.ui.camera.CameraXJournaledStillCaptureWriter
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournaledGuidedCaptureIntegrationAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var privateRoot: File
    private var database: AppDatabase? = null
    private var coordinator: JournaledThreePhotoCaptureCoordinator? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "journaled_guided_capture_integration_$suffix.db"
        privateRoot = File(context.noBackupFilesDir, "journaled-guided-capture-integration-$suffix")
        context.deleteRoomTestDatabase(databaseName)
        assertFalse(privateRoot.exists())
        assertTrue(context.roomTestDatabaseResidue(databaseName).isEmpty())
    }

    @After
    fun tearDown() {
        coordinator?.close()
        coordinator = null
        database?.close()
        database = null
        context.deleteRoomTestDatabase(databaseName)
        if (privateRoot.exists()) assertTrue(privateRoot.deleteRecursively())
        assertFalse(privateRoot.exists())
        assertTrue(context.roomTestDatabaseResidue(databaseName).isEmpty())
    }

    @Test
    fun generatedManualCapturePublishesExactlyThreeThenConfirmsAndAdvancesOnce() {
        val created = AppDatabase.create(context, databaseName).also { database = it }
        seedActiveSession(created.openHelper.writableDatabase)
        val repository = RoomShootRepository(created)
        val currentReference = repository.loadCurrentGuidedReference(SESSION_ID)
            as GuidedCurrentReferenceResult.Ready
        assertEquals(POSE_IDS.first(), currentReference.reference.poseId)
        assertEquals(PoseImageSize(1920, 1080), currentReference.reference.imageSize)
        assertEquals(4, currentReference.reference.landmarks.size)
        val store = JournaledPrivateCaptureStore(privateRoot)
        val writer = GeneratedCaptureWriter()
        val clock = IncreasingEpochClock()
        val captureCoordinator = JournaledThreePhotoCaptureCoordinator(
            authority = RoomJournaledCaptureAuthorityAdapter(repository, created),
            files = JournaledCaptureFileAdapter(store),
            writer = writer,
            recovery = JournaledCaptureRecoveryPort {
                throw AssertionError("successful generated capture must not enter recovery")
            },
            clock = clock,
            executor = Executor(Runnable::run),
        ).also { coordinator = it }

        val reducer = ShootReducer()
        var state = reducer.reduce(
            ShootState.initial(SESSION_ID, POSE_IDS),
            ShootEvent.PreparationCompleted(1L),
        ).nextState
        val manual = reducer.reduce(state, ShootEvent.ManualCaptureRequested(2L))
        state = manual.nextState
        val command = manual.effects.single() as ShootEffect.CaptureCommand
        var captureResult: JournaledCaptureResult? = null

        assertEquals(
            JournaledCaptureSubmission.ACCEPTED,
            captureCoordinator.submit(SESSION_ID, command) { captureResult = it },
        )
        assertTrue(captureResult is JournaledCaptureResult.Durable)
        assertEquals(listOf(0, 1, 2), writer.ordinals)
        assertEquals(
            listOf("FINAL_DURABLE", "FINAL_DURABLE", "FINAL_DURABLE"),
            queryStrings(
                "SELECT stage FROM capture_file_operations " +
                    "WHERE command_token = ? ORDER BY burst_ordinal",
                command.token.value,
            ),
        )
        command.outputs.forEach { identity ->
            val paths = CaptureFileOperationPaths.forIdentity(identity)
            val finalFile = File(privateRoot, paths.relativeFinalPath)
            assertTrue(finalFile.isFile)
            assertArrayEquals(writer.payloads.getValue(identity.ordinal), finalFile.readBytes())
            assertFalse(File(privateRoot, paths.relativeTempPath).exists())
            assertFalse(File(privateRoot, paths.relativeQuarantinePath).exists())
        }

        val durability = reducer.reduce(
            state,
            ShootEvent.PrivateCaptureDurabilityConfirmed(
                command.token,
                command.outputs,
                3L,
            ),
        )
        state = durability.nextState
        val confirmation = durability.effects.single() as ShootEffect.ConfirmAndAdvanceCapture
        var confirmationResult: JournaledConfirmationResult? = null
        assertEquals(
            JournaledCaptureSubmission.ACCEPTED,
            captureCoordinator.confirm(confirmation) { confirmationResult = it },
        )
        assertTrue(confirmationResult is JournaledConfirmationResult.Advanced)

        state = reducer.reduce(
            state,
            ShootEvent.CaptureConfirmedAndAdvanced(command.token, 4L),
        ).nextState
        assertEquals(1, state.currentPoseIndex)
        assertTrue(state.mode is ShootMode.SearchingForPerson)
        assertEquals(
            listOf("1", "1", "ACTIVE"),
            queryStrings(
                "SELECT current_pose_index, next_attempt_number, lifecycle_state " +
                    "FROM shoot_sessions WHERE session_id = ?",
                SESSION_ID,
            ),
        )
        assertEquals(
            listOf("CONFIRMED"),
            queryStrings(
                "SELECT lifecycle_state FROM capture_attempts WHERE command_token = ?",
                command.token.value,
            ),
        )
        assertEquals(0L, count("capture_file_operations", command.token.value))
        assertEquals(3L, count("private_capture_outputs", command.token.value))
        assertEquals(1L, count("capture_confirmation_receipts", command.token.value))
        assertEquals(1L, count("capture_export_outboxes", command.token.value))
        assertEquals(3L, count("capture_export_outputs", command.token.value))
    }

    @Test
    fun authorizedPixelRealCameraPublishesExactlyThreeThenConfirmsAndAdvancesOnce() {
        assertEquals(
            "CAMERA must be granted before authorized Pixel acceptance",
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA),
        )
        val created = AppDatabase.create(context, databaseName).also { database = it }
        seedActiveSession(created.openHelper.writableDatabase)
        val repository = RoomShootRepository(created)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
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
        val ready = CountDownLatch(1)
        val cameraFailed = AtomicBoolean(false)
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
        val cameraCallbackExecutor = Executors.newSingleThreadExecutor()
        val coordinatorExecutor = Executors.newSingleThreadExecutor()

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

            val captureCoordinator = JournaledThreePhotoCaptureCoordinator(
                authority = RoomJournaledCaptureAuthorityAdapter(repository, created),
                files = JournaledCaptureFileAdapter(androidJournaledPrivateCaptureStore(privateRoot)),
                writer = CameraXJournaledStillCaptureWriter(
                    imageCapture = controller::requireImageCapture,
                    mainExecutor = ContextCompat.getMainExecutor(context),
                    callbackExecutor = cameraCallbackExecutor,
                ),
                recovery = JournaledCaptureRecoveryPort {
                    throw AssertionError("successful live capture must not enter recovery")
                },
                clock = IncreasingEpochClock(),
                executor = coordinatorExecutor,
            ).also { coordinator = it }
            val reducer = ShootReducer()
            var state = reducer.reduce(
                ShootState.initial(SESSION_ID, POSE_IDS),
                ShootEvent.PreparationCompleted(1L),
            ).nextState
            val manual = reducer.reduce(state, ShootEvent.ManualCaptureRequested(2L))
            state = manual.nextState
            val command = manual.effects.single() as ShootEffect.CaptureCommand
            val captureDone = CountDownLatch(1)
            val captureResult = AtomicReference<JournaledCaptureResult>()

            assertEquals(
                JournaledCaptureSubmission.ACCEPTED,
                captureCoordinator.submit(SESSION_ID, command) {
                    captureResult.set(it)
                    captureDone.countDown()
                },
            )
            assertTrue("Journaled real-camera capture timed out", captureDone.await(60, TimeUnit.SECONDS))
            assertTrue(
                "Expected durable real-camera capture, got ${captureResult.get()}",
                captureResult.get() is JournaledCaptureResult.Durable,
            )
            assertEquals(
                listOf("FINAL_DURABLE", "FINAL_DURABLE", "FINAL_DURABLE"),
                queryStrings(
                    "SELECT stage FROM capture_file_operations " +
                        "WHERE command_token = ? ORDER BY burst_ordinal",
                    command.token.value,
                ),
            )
            command.outputs.forEach { identity ->
                val paths = CaptureFileOperationPaths.forIdentity(identity)
                val finalFile = File(privateRoot, paths.relativeFinalPath)
                assertTrue(finalFile.isFile)
                assertTrue(finalFile.length() > 0L)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(finalFile.absolutePath, bounds)
                assertTrue(bounds.outWidth > 0)
                assertTrue(bounds.outHeight > 0)
                assertFalse(File(privateRoot, paths.relativeTempPath).exists())
                assertFalse(File(privateRoot, paths.relativeQuarantinePath).exists())
            }

            val durability = reducer.reduce(
                state,
                ShootEvent.PrivateCaptureDurabilityConfirmed(
                    command.token,
                    command.outputs,
                    3L,
                ),
            )
            state = durability.nextState
            val confirmation = durability.effects.single() as ShootEffect.ConfirmAndAdvanceCapture
            val confirmationDone = CountDownLatch(1)
            val confirmationResult = AtomicReference<JournaledConfirmationResult>()
            assertEquals(
                JournaledCaptureSubmission.ACCEPTED,
                captureCoordinator.confirm(confirmation) {
                    confirmationResult.set(it)
                    confirmationDone.countDown()
                },
            )
            assertTrue("Journaled real-camera confirmation timed out", confirmationDone.await(30, TimeUnit.SECONDS))
            assertTrue(confirmationResult.get() is JournaledConfirmationResult.Advanced)

            state = reducer.reduce(
                state,
                ShootEvent.CaptureConfirmedAndAdvanced(command.token, 4L),
            ).nextState
            assertEquals(1, state.currentPoseIndex)
            assertTrue(state.mode is ShootMode.SearchingForPerson)
            assertEquals(0L, count("capture_file_operations", command.token.value))
            assertEquals(3L, count("private_capture_outputs", command.token.value))
            assertEquals(1L, count("capture_confirmation_receipts", command.token.value))
            assertEquals(1L, count("capture_export_outboxes", command.token.value))
            assertEquals(3L, count("capture_export_outputs", command.token.value))
        } finally {
            coordinator?.close()
            coordinator = null
            instrumentation.runOnMainSync {
                controller.close()
                activity.finish()
            }
            cameraCallbackExecutor.shutdown()
            coordinatorExecutor.shutdown()
            assertTrue(cameraCallbackExecutor.awaitTermination(30, TimeUnit.SECONDS))
            assertTrue(coordinatorExecutor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    private fun seedActiveSession(sqlite: SupportSQLiteDatabase) {
        val landmarkPayload = ReferenceLandmarkPayload.from(
            listOf(
                landmark(PoseLandmark.LEFT_SHOULDER, 0.35, 0.30),
                landmark(PoseLandmark.RIGHT_SHOULDER, 0.65, 0.30),
                landmark(PoseLandmark.LEFT_HIP, 0.40, 0.65),
                landmark(PoseLandmark.RIGHT_HIP, 0.60, 0.65),
            ),
        ).value
        sqlite.execSQL(
            "INSERT INTO shoots (shoot_id, name, created_at_epoch_millis, " +
                "updated_at_epoch_millis, lifecycle_state, deletion_generation) " +
                "VALUES (?, 'Generated integration shoot', 1, 1, 'ACTIVE', 0)",
            arrayOf<Any>(SHOOT_ID),
        )
        POSE_IDS.forEachIndexed { poseIndex, poseId ->
            sqlite.execSQL(
                "INSERT INTO shoot_poses (shoot_id, pose_index, pose_id, label, " +
                    "reference_asset_path, mirror_allowed, validation_state, detector_metadata, " +
                    "model_metadata, preprocessing_metadata, landmark_payload, " +
                    "coordinate_metadata) " +
                    "VALUES (?, ?, ?, ?, ?, 0, 'VALIDATED', 'generated-detector', " +
                    "'generated-model', 'decoded=1920x1080', ?, 'normalized-upright')",
                arrayOf<Any>(
                    SHOOT_ID,
                    poseIndex,
                    poseId,
                    "Generated pose ${poseIndex + 1}",
                    "reference-assets/assets/${(poseIndex + 1).toString().repeat(64)}.asset",
                    landmarkPayload,
                ),
            )
        }
        sqlite.execSQL(
            "INSERT INTO shoot_sessions (session_id, shoot_id, current_pose_index, " +
                "next_attempt_number, lifecycle_state, created_at_epoch_millis, " +
                "updated_at_epoch_millis) VALUES (?, ?, 0, 0, 'ACTIVE', 1, 1)",
            arrayOf<Any>(SESSION_ID, SHOOT_ID),
        )
    }

    private fun landmark(type: PoseLandmark, x: Double, y: Double) = Landmark(
        type = type,
        x = x,
        y = y,
        z = 0.0,
        visibility = 0.9,
        presence = 0.9,
    )

    private fun queryStrings(sql: String, argument: String): List<String> =
        requireNotNull(database).openHelper.writableDatabase.query(
            sql,
            arrayOf<Any>(argument),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    repeat(cursor.columnCount) { column -> add(cursor.getString(column)) }
                }
            }
        }

    private fun count(table: String, token: String): Long =
        requireNotNull(database).openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM $table WHERE command_token = ?",
            arrayOf<Any>(token),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private class GeneratedCaptureWriter : JournaledStillCaptureWriter {
        val ordinals = mutableListOf<Int>()
        val payloads = (0..2).associateWith { ordinal ->
            "generated-private-capture-$ordinal".toByteArray(StandardCharsets.UTF_8)
        }

        override fun write(tempFile: File, callback: JournaledStillCaptureWriter.Callback) {
            val ordinal = ordinals.size
            ordinals += ordinal
            tempFile.writeBytes(payloads.getValue(ordinal))
            callback.onImageSaved()
            callback.onImageSaved()
        }
    }

    private class IncreasingEpochClock : CaptureOperationClock {
        private var last = 10L

        override fun nextAfter(floor: Long): Long? {
            if (floor == Long.MAX_VALUE) return null
            return maxOf(last + 1L, floor + 1L).also { last = it }
        }
    }

    private companion object {
        const val SHOOT_ID = "guided-capture-integration-shoot"
        const val SESSION_ID = "guided-capture-integration-session"
        val POSE_IDS = listOf("guided-capture-pose-0", "guided-capture-pose-1", "guided-capture-pose-2")
    }
}
