package com.tonyisup.poseguidesnap.data

import android.content.Context
import android.database.Cursor
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tonyisup.poseguidesnap.data.db.AppDatabase
import com.tonyisup.poseguidesnap.data.db.CaptureAttemptEntity
import com.tonyisup.poseguidesnap.data.db.CaptureConfirmationReceiptEntity
import com.tonyisup.poseguidesnap.data.db.CaptureExportOutboxEntity
import com.tonyisup.poseguidesnap.data.db.CaptureExportOutputEntity
import com.tonyisup.poseguidesnap.data.db.CaptureFileOperationEntity
import com.tonyisup.poseguidesnap.data.db.GuidedSessionDao
import com.tonyisup.poseguidesnap.data.db.PrivateCaptureOutputEntity
import com.tonyisup.poseguidesnap.data.db.ReferenceImportFileOperationEntity
import com.tonyisup.poseguidesnap.data.db.ReferenceImportIntentEntity
import com.tonyisup.poseguidesnap.data.db.ShootEntity
import com.tonyisup.poseguidesnap.data.db.ShootPoseEntity
import com.tonyisup.poseguidesnap.data.db.ShootSessionEntity
import com.tonyisup.poseguidesnap.domain.session.CaptureAttempt
import com.tonyisup.poseguidesnap.domain.session.CaptureToken
import com.tonyisup.poseguidesnap.domain.session.CaptureTrigger
import com.tonyisup.poseguidesnap.domain.session.PrivateOutputIdentity
import com.tonyisup.poseguidesnap.domain.session.ShootEffect
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@Database(
    entities = [
        ShootEntity::class,
        ShootPoseEntity::class,
        ShootSessionEntity::class,
        CaptureAttemptEntity::class,
        PrivateCaptureOutputEntity::class,
        CaptureConfirmationReceiptEntity::class,
        CaptureExportOutboxEntity::class,
        CaptureExportOutputEntity::class,
        ReferenceImportIntentEntity::class,
        ReferenceImportFileOperationEntity::class,
        CaptureFileOperationEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
internal abstract class GuidedSessionPacket2BDatabase : RoomDatabase() {
    internal abstract fun guidedSessionDao(): GuidedSessionDao
}

@RunWith(AndroidJUnit4::class)
class GuidedSessionPacket2BAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var shootId: String
    private lateinit var sessionId: String
    private lateinit var commandTokenValue: String
    private val commandToken: CaptureToken
        get() = CaptureToken(commandTokenValue)
    private var writerDatabase: AppDatabase? = null
    private var readerDatabase: GuidedSessionPacket2BDatabase? = null
    private var activeGate: SecondBootstrapSelectGate? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "guided_session_packet_2b_$suffix.db"
        shootId = "shoot-$suffix"
        sessionId = "session-$suffix"
        commandTokenValue = "command-$suffix"
        context.deleteRoomTestDatabase(databaseName)
        assertNoTestDatabaseResidue()
    }

    @After
    fun tearDown() {
        activeGate?.release()
        activeGate = null
        readerDatabase?.close()
        readerDatabase = null
        writerDatabase?.close()
        writerDatabase = null
        context.deleteRoomTestDatabase(databaseName)
        assertNoTestDatabaseResidue()
    }

    @Test
    fun immediateBootstrapBlocksConfirmationWriterAndReturnsCompletePreThenPostState() {
        val writer = openWriter()
        writer.openHelper.writableDatabase.seedBaseAuthority()
        prepareCapturingAttempt(writer)
        val gate = SecondBootstrapSelectGate()
        val reader = openReader(gate)
        val expectedPre = reader.guidedSessionDao().loadGuidedSessionBootstrap(sessionId)
        assertTrue(GuidedSessionBootstrapMapper.map(expectedPre) is GuidedSessionBootstrapResult.ReconciliationRequired)

        // Task 3D: direct first-application confirmation is fail-closed; committed authority is
        // seeded directly (journal-owned path lands in 14B.1C).
        val exclusion = proveImmediateWriterExclusion(gate) {
            writer.openHelper.writableDatabase.commitConfirmedAuthorityInTransaction()
        }

        assertCompleteRowsEqual(expectedPre, exclusion.readerRows)

        val postRows = reader.guidedSessionDao().loadGuidedSessionBootstrap(sessionId)
        val post = GuidedSessionBootstrapMapper.map(postRows)
        assertTrue(post is GuidedSessionBootstrapResult.Ready)
        val postSnapshot = (post as GuidedSessionBootstrapResult.Ready).snapshot
        assertEquals(1, postSnapshot.currentPoseIndex)
        assertEquals(1L, postSnapshot.nextAttemptNumber)
        assertEquals(1, postSnapshot.confirmedAttemptCount)
        assertEquals(3, postSnapshot.unresolvedExportCount)
        assertTrue(postSnapshot.appliedReceiptTokens.single() == commandToken.value)
        assertNotEquals(expectedPre.session, postRows.session)
        assertEquals(1, postRows.receipts.size)
        assertEquals(3, postRows.privateOutputs.size)
        assertEquals(1, postRows.outboxes.size)
        assertEquals(3, postRows.exportOutputs.size)
    }

    @Test
    fun immediateBootstrapBlocksDeletionWriterAndReturnsCompletePreThenPostState() {
        val writer = openWriter()
        writer.openHelper.writableDatabase.seedBaseAuthority()
        // Task 3D: direct first-application confirmation is fail-closed; committed authority is
        // seeded directly (journal-owned path lands in 14B.1C).
        writer.openHelper.writableDatabase.seedConfirmedAuthorityGraph()
        val gate = SecondBootstrapSelectGate()
        val reader = openReader(gate)
        val expectedPre = reader.guidedSessionDao().loadGuidedSessionBootstrap(sessionId)
        assertTrue(GuidedSessionBootstrapMapper.map(expectedPre) is GuidedSessionBootstrapResult.Ready)

        val exclusion = proveImmediateWriterExclusion(gate) {
            RoomShootRepository(writer).beginShootDeletion(shootId, 40L)
        }

        assertCompleteRowsEqual(expectedPre, exclusion.readerRows)
        assertEquals(BeginShootDeletionResult.Began(1L, 3, 1, 0), exclusion.writerResult)

        val postRows = reader.guidedSessionDao().loadGuidedSessionBootstrap(sessionId)
        assertEquals("DELETING", postRows.shoot?.lifecycleState)
        assertEquals(1L, postRows.shoot?.deletionGeneration)
        assertEquals("CANCELLED", postRows.outboxes.single().lifecycleState)
        assertTrue(postRows.exportOutputs.all { it.lifecycleState == "CANCELLED" })
        assertEquals(expectedPre.session, postRows.session)
        assertEquals(expectedPre.attempts, postRows.attempts)
        assertEquals(expectedPre.privateOutputs, postRows.privateOutputs)
        assertEquals(expectedPre.receipts, postRows.receipts)
        assertTrue(GuidedSessionBootstrapMapper.map(postRows) is GuidedSessionBootstrapResult.Rejected)
    }

    @Test
    fun nontransactionalConfirmationReadsCanProduceMixedPreSessionAndPostReceiptState() {
        val writer = openWriter()
        val sqlite = writer.openHelper.writableDatabase
        sqlite.seedBaseAuthority()
        prepareCapturingAttempt(writer)
        val preSession = sqlite.safeSessionFacts()

        // Task 3D: direct first-application confirmation is fail-closed; committed authority is
        // seeded directly (journal-owned path lands in 14B.1C).
        sqlite.commitConfirmedAuthorityInTransaction()
        val postReceipt = sqlite.safeReceiptFacts()

        assertEquals(SafeSessionFacts(0, 1L, "ACTIVE"), preSession)
        assertEquals(SafeReceiptFacts(1, 0, 1, 3, 3), postReceipt)
        assertEquals(1, sqlite.safeSessionFacts().currentPoseIndex)
    }

    @Test
    fun nontransactionalDeletionReadsCanProduceMixedPreSessionAndPostDeletionState() {
        val writer = openWriter()
        val sqlite = writer.openHelper.writableDatabase
        sqlite.seedBaseAuthority()
        // Task 3D: direct first-application confirmation is fail-closed; committed authority is
        // seeded directly (journal-owned path lands in 14B.1C).
        sqlite.seedConfirmedAuthorityGraph()
        val preSession = sqlite.safeSessionFacts()
        val preShoot = sqlite.safeShootFacts()

        assertEquals(
            BeginShootDeletionResult.Began(1L, 3, 1, 0),
            RoomShootRepository(writer).beginShootDeletion(shootId, 40L),
        )
        val postDeletion = sqlite.safeDeletionFacts()

        assertEquals(SafeSessionFacts(1, 1L, "ACTIVE"), preSession)
        assertEquals(SafeShootFacts("ACTIVE", 0L), preShoot)
        assertEquals(SafeDeletionFacts("DELETING", 1L, 1, 3), postDeletion)
    }

    @Test
    fun repeatedBootstrapsAreReadOnlyAcrossEveryV3AuthorityTableAndSchema() {
        val writer = openWriter()
        writer.openHelper.writableDatabase.seedBaseAuthority()
        // Task 3D: direct first-application confirmation is fail-closed; committed authority is
        // seeded directly (journal-owned path lands in 14B.1C).
        writer.openHelper.writableDatabase.seedConfirmedAuthorityGraph()
        val reader = openReader(SecondBootstrapSelectGate())
        val sqlite = reader.openHelper.writableDatabase
        val expected = reader.guidedSessionDao().loadGuidedSessionBootstrap(sessionId)
        assertTrue(GuidedSessionBootstrapMapper.map(expected) is GuidedSessionBootstrapResult.Ready)
        val before = sqlite.readOnlyEvidence()

        repeat(5) {
            assertCompleteRowsEqual(
                expected,
                reader.guidedSessionDao().loadGuidedSessionBootstrap(sessionId),
            )
        }
        val after = sqlite.readOnlyEvidence()

        AUTHORITY_TABLES.forEach { table ->
            assertArrayEquals(
                "bootstrap changed authority table $table",
                before.tableDigests.getValue(table),
                after.tableDigests.getValue(table),
            )
        }
        assertArrayEquals("bootstrap changed sqlite_master", before.schemaDigest, after.schemaDigest)
        assertEquals(before.totalChanges, after.totalChanges)
        assertEquals(before.dataVersion, after.dataVersion)
    }

    @Test
    fun packet2BSnapshotIncludesEmptyJournalFamilyDigest() {
        val writer = openWriter()
        val sqlite = writer.openHelper.writableDatabase
        sqlite.seedBaseAuthority()
        // Seed a minimal REGISTERED attempt via raw SQL so no journal rows exist yet: API
        // registration inserts burst ordinals 0..2 and would collide with the manual insert below.
        sqlite.seedRegisteredAttemptWithoutJournalRows()
        val before = sqlite.readOnlyEvidence()
        assertTrue(before.tableDigests.containsKey("capture_file_operations"))

        sqlite.execSQL(
            """
            INSERT INTO capture_file_operations
                (command_token, burst_ordinal, relative_final_path, relative_temp_path,
                 relative_quarantine_path, stage, byte_count, sha256,
                 captured_at_epoch_millis, last_failure_code, reconciliation_required,
                 created_at_epoch_millis, updated_at_epoch_millis)
            VALUES (?, 0, ?, ?, ?, 'EXPECTING_RESERVATION', NULL, NULL, NULL, NULL, 0, 25, 25)
            """.trimIndent(),
            arrayOf<Any>(
                commandTokenValue,
                "final/$commandTokenValue/0.jpg",
                "temp/$commandTokenValue/0.tmp",
                "quarantine/$commandTokenValue/0.bin",
            ),
        )
        val after = sqlite.readOnlyEvidence()

        assertFalse(
            "journal insert did not change the capture_file_operations digest",
            before.tableDigests.getValue("capture_file_operations")
                .contentEquals(after.tableDigests.getValue("capture_file_operations")),
        )
        AUTHORITY_TABLES.filter { it != "capture_file_operations" }.forEach { table ->
            assertArrayEquals(
                "journal insert changed unrelated authority table $table",
                before.tableDigests.getValue(table),
                after.tableDigests.getValue(table),
            )
        }
    }

    private fun openWriter(): AppDatabase = AppDatabase.create(context, databaseName).also { database ->
        writerDatabase = database
        assertEquals("wal", database.openHelper.writableDatabase.journalMode())
    }

    private fun openReader(gate: SecondBootstrapSelectGate): GuidedSessionPacket2BDatabase {
        activeGate = gate
        val callback = object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                gate.onQuery(sqlQuery)
            }
        }
        return Room.databaseBuilder(
            context.applicationContext,
            GuidedSessionPacket2BDatabase::class.java,
            databaseName,
        ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryCallback(callback, DIRECT_EXECUTOR)
            .build()
            .also { database ->
                readerDatabase = database
                assertEquals("wal", database.openHelper.writableDatabase.journalMode())
            }
    }

    private fun <WriterResult> proveImmediateWriterExclusion(
        gate: SecondBootstrapSelectGate,
        writer: () -> WriterResult,
    ): ExclusionResult<WriterResult> {
        val reader = checkNotNull(readerDatabase)
        val executor = Executors.newFixedThreadPool(2)
        val writerStarted = CountDownLatch(1)
        gate.arm()
        val readerFuture = executor.submit<GuidedSessionBootstrapRows> {
            reader.guidedSessionDao().loadGuidedSessionBootstrap(sessionId)
        }
        try {
            assertTrue("bootstrap did not pause at its second SELECT", gate.awaitSecondSelect())
            val writerFuture = executor.submit<WriterResult> {
                writerStarted.countDown()
                writer()
            }
            assertTrue(
                "writer worker did not start",
                writerStarted.await(LONG_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            var exclusionFailure: AssertionError? = null
            try {
                assertFutureRemainsBlocked(writerFuture)
            } catch (failure: AssertionError) {
                exclusionFailure = failure
            } finally {
                gate.release()
            }

            val readerRows = readerFuture.get(LONG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            gate.disarm()
            assertEquals(EXPECTED_BOOTSTRAP_SELECT_COUNT, gate.bootstrapSelectCount())
            val writerResult = writerFuture.get(LONG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            exclusionFailure?.let { throw it }
            return ExclusionResult(readerRows, writerResult)
        } finally {
            gate.release()
            gate.disarm()
            executor.shutdownNow()
            assertTrue(
                "Packet 2B executor did not terminate",
                executor.awaitTermination(LONG_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
        }
    }

    private fun assertFutureRemainsBlocked(future: Future<*>) {
        try {
            future.get(SHORT_BLOCK_MILLIS, TimeUnit.MILLISECONDS)
            fail("writer completed while bootstrap owned its Room transaction")
        } catch (_: TimeoutException) {
            // Expected: the production writer cannot acquire its transaction yet.
        }
    }

    private fun prepareCapturingAttempt(database: AppDatabase): ShootEffect.ConfirmAndAdvanceCapture {
        val repository = RoomShootRepository(database)
        val capture = captureCommand()
        assertEquals(
            AttemptRegistrationResult.Registered,
            repository.registerCaptureAttempt(sessionId, capture, 10L),
        )
        assertEquals(
            CaptureAttemptStartResult.Started,
            repository.markCaptureAttemptStarted(sessionId, commandToken, 20L),
        )
        return ShootEffect.ConfirmAndAdvanceCapture(
            token = commandToken,
            poseId = capture.poseId,
            poseIndex = capture.poseIndex,
            outputs = capture.outputs,
        )
    }

    private fun confirmPreparedAttempt(database: AppDatabase) {
        val confirmation = prepareCapturingAttempt(database)
        assertEquals(
            CaptureConfirmationResult.Applied,
            RoomShootRepository(database).confirmAndAdvance(
                command = confirmation,
                privateOutputs = durableOutputs(commandToken),
                exportTargets = exportTargets(commandToken),
                confirmedAtEpochMillis = 30L,
            ),
        )
    }

    private fun captureCommand(): ShootEffect.CaptureCommand = ShootEffect.CaptureCommand(
        CaptureAttempt.create(
            token = commandToken,
            trigger = CaptureTrigger.MANUAL,
            poseId = poseId(0),
            poseIndex = 0,
            attemptNumber = 0L,
        ),
    )

    private fun durableOutputs(token: CaptureToken): List<DurablePrivateOutput> =
        (0..2).map { ordinal ->
            DurablePrivateOutput(
                identity = PrivateOutputIdentity(token, ordinal),
                relativePath = "private/${token.value}/$ordinal.jpg",
                byteCount = 100L + ordinal,
                capturedAtEpochMillis = 21L + ordinal,
                integrityMetadata = null,
            )
        }

    private fun exportTargets(token: CaptureToken): List<CaptureExportTarget> =
        (0..2).map { ordinal ->
            CaptureExportTarget(
                identity = PrivateOutputIdentity(token, ordinal),
                targetCollectionUri = "content://media/external_primary/images/media",
                targetVolume = "external_primary",
                intendedDisplayName = "${token.value}-$ordinal.jpg",
                intendedRelativePath = "Pictures/PoseGuideSnap/",
                intendedMimeType = "image/jpeg",
            )
        }

    private fun poseId(index: Int): String = "pose-$index-${shootId.removePrefix("shoot-")}"

    private fun SupportSQLiteDatabase.seedBaseAuthority() {
        execSQL(
            """
            INSERT INTO shoots
                (shoot_id, name, created_at_epoch_millis, updated_at_epoch_millis,
                 lifecycle_state, deletion_generation)
            VALUES (?, 'Packet 2B shoot', 1, 1, 'ACTIVE', 0)
            """.trimIndent(),
            arrayOf<Any>(shootId),
        )
        repeat(3) { poseIndex ->
            execSQL(
                """
                INSERT INTO shoot_poses
                    (shoot_id, pose_index, pose_id, label, reference_asset_path,
                     mirror_allowed, validation_state, detector_metadata, model_metadata,
                     preprocessing_metadata, landmark_payload, coordinate_metadata)
                VALUES (?, ?, ?, 'Packet 2B pose', NULL, 0, 'VALID', NULL, NULL, NULL, NULL, NULL)
                """.trimIndent(),
                arrayOf<Any>(shootId, poseIndex, poseId(poseIndex)),
            )
        }
        execSQL(
            """
            INSERT INTO shoot_sessions
                (session_id, shoot_id, current_pose_index, next_attempt_number,
                 lifecycle_state, created_at_epoch_millis, updated_at_epoch_millis)
            VALUES (?, ?, 0, 0, 'ACTIVE', 1, 1)
            """.trimIndent(),
            arrayOf<Any>(sessionId, shootId),
        )
    }

    private fun SupportSQLiteDatabase.seedRegisteredAttemptWithoutJournalRows() {
        execSQL(
            """
            INSERT INTO capture_attempts
                (command_token, session_id, pose_id, pose_index, attempt_number, trigger_type,
                 lifecycle_state, reconciliation_required, captured_deletion_generation,
                 created_at_epoch_millis, updated_at_epoch_millis, confirmed_at_epoch_millis)
            VALUES (?, ?, ?, 0, 0, 'MANUAL', 'REGISTERED', 0, 0, 10, 10, NULL)
            """.trimIndent(),
            arrayOf<Any>(commandTokenValue, sessionId, poseId(0)),
        )
        execSQL(
            """
            UPDATE shoot_sessions
            SET next_attempt_number = 1, updated_at_epoch_millis = 10
            WHERE session_id = ?
            """.trimIndent(),
            arrayOf<Any>(sessionId),
        )
    }

    private fun SupportSQLiteDatabase.seedConfirmedAuthorityGraph() {
        execSQL(
            """
            INSERT INTO capture_attempts
                (command_token, session_id, pose_id, pose_index, attempt_number, trigger_type,
                 lifecycle_state, reconciliation_required, captured_deletion_generation,
                 created_at_epoch_millis, updated_at_epoch_millis, confirmed_at_epoch_millis)
            VALUES (?, ?, ?, 0, 0, 'MANUAL', 'CONFIRMED', 0, 0, 10, 30, 30)
            """.trimIndent(),
            arrayOf<Any>(commandTokenValue, sessionId, poseId(0)),
        )
        durableOutputs(commandToken).forEach { output ->
            execSQL(
                """
                INSERT INTO private_capture_outputs
                    (command_token, burst_ordinal, relative_path, byte_count, durability_state,
                     captured_at_epoch_millis, integrity_metadata)
                VALUES (?, ?, ?, ?, 'DURABLE', ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    commandTokenValue,
                    output.identity.ordinal,
                    output.relativePath,
                    output.byteCount,
                    output.capturedAtEpochMillis,
                    output.integrityMetadata,
                ),
            )
        }
        execSQL(
            """
            INSERT INTO capture_confirmation_receipts
                (command_token, from_pose_index, to_pose_index,
                 applied_deletion_generation, applied_at_epoch_millis)
            VALUES (?, 0, 1, 0, 30)
            """.trimIndent(),
            arrayOf<Any>(commandTokenValue),
        )
        execSQL(
            """
            INSERT INTO capture_export_outboxes
                (command_token, lifecycle_state, created_at_epoch_millis,
                 updated_at_epoch_millis, retry_metadata)
            VALUES (?, 'PENDING', 30, 30, NULL)
            """.trimIndent(),
            arrayOf<Any>(commandTokenValue),
        )
        exportTargets(commandToken).forEach { target ->
            execSQL(
                """
                INSERT INTO capture_export_outputs
                    (command_token, burst_ordinal, target_collection_uri, target_volume,
                     intended_display_name, intended_relative_path, intended_mime_type,
                     lifecycle_state, claim_token, media_uri_string, ambiguity_state,
                     deletion_generation, created_at_epoch_millis, updated_at_epoch_millis)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', NULL, NULL, 'NONE', 0, 30, 30)
                """.trimIndent(),
                arrayOf<Any>(
                    commandTokenValue,
                    target.identity.ordinal,
                    target.targetCollectionUri,
                    target.targetVolume,
                    target.intendedDisplayName,
                    target.intendedRelativePath,
                    target.intendedMimeType,
                ),
            )
        }
        execSQL(
            """
            UPDATE shoot_sessions
            SET current_pose_index = 1, next_attempt_number = 1, updated_at_epoch_millis = 30
            WHERE session_id = ?
            """.trimIndent(),
            arrayOf<Any>(sessionId),
        )
    }

    private fun SupportSQLiteDatabase.commitConfirmedAuthorityInTransaction() {
        beginTransaction()
        try {
            execSQL(
                "DELETE FROM capture_file_operations WHERE command_token = ?",
                arrayOf<Any>(commandTokenValue),
            )
            execSQL(
                "DELETE FROM capture_attempts WHERE command_token = ?",
                arrayOf<Any>(commandTokenValue),
            )
            seedConfirmedAuthorityGraph()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun SupportSQLiteDatabase.safeSessionFacts(): SafeSessionFacts =
        queryOne(
            """
            SELECT current_pose_index, next_attempt_number, lifecycle_state
            FROM shoot_sessions
            WHERE session_id = ?
            """.trimIndent(),
            arrayOf(sessionId),
        ) { cursor -> SafeSessionFacts(cursor.getInt(0), cursor.getLong(1), cursor.getString(2)) }

    private fun SupportSQLiteDatabase.safeShootFacts(): SafeShootFacts =
        queryOne(
            "SELECT lifecycle_state, deletion_generation FROM shoots WHERE shoot_id = ?",
            arrayOf(shootId),
        ) { cursor -> SafeShootFacts(cursor.getString(0), cursor.getLong(1)) }

    private fun SupportSQLiteDatabase.safeReceiptFacts(): SafeReceiptFacts {
        val receipt = queryOne(
            """
            SELECT COUNT(*), MIN(from_pose_index), MAX(to_pose_index)
            FROM capture_confirmation_receipts
            WHERE command_token = ?
            """.trimIndent(),
            arrayOf(commandToken.value),
        ) { cursor -> Triple(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2)) }
        return SafeReceiptFacts(
            receiptCount = receipt.first,
            fromPoseIndex = receipt.second,
            toPoseIndex = receipt.third,
            privateOutputCount = countRows("private_capture_outputs"),
            exportOutputCount = countRows("capture_export_outputs"),
        )
    }

    private fun SupportSQLiteDatabase.safeDeletionFacts(): SafeDeletionFacts {
        val shoot = safeShootFacts()
        return SafeDeletionFacts(
            shootLifecycle = shoot.lifecycleState,
            deletionGeneration = shoot.deletionGeneration,
            cancelledOutboxCount = countRows(
                "capture_export_outboxes",
                "lifecycle_state = 'CANCELLED'",
            ),
            cancelledOutputCount = countRows(
                "capture_export_outputs",
                "lifecycle_state = 'CANCELLED'",
            ),
        )
    }

    private fun SupportSQLiteDatabase.countRows(table: String, predicate: String? = null): Int {
        require(table in AUTHORITY_TABLES)
        val sql = buildString {
            append("SELECT COUNT(*) FROM ")
            append(table)
            if (predicate != null) {
                append(" WHERE ")
                append(predicate)
            }
        }
        return queryOne(sql, emptyArray()) { it.getInt(0) }
    }

    private fun SupportSQLiteDatabase.readOnlyEvidence(): ReadOnlyEvidence = ReadOnlyEvidence(
        tableDigests = AUTHORITY_TABLES.associateWith { table ->
            queryDigest("SELECT * FROM $table ORDER BY rowid")
        },
        schemaDigest = queryDigest(
            """
            SELECT type, name, tbl_name, sql
            FROM sqlite_master
            ORDER BY type, name, tbl_name
            """.trimIndent(),
        ),
        totalChanges = queryOne("SELECT total_changes()", emptyArray()) { it.getLong(0) },
        dataVersion = queryOne("PRAGMA data_version", emptyArray()) { it.getLong(0) },
    )

    private fun SupportSQLiteDatabase.queryDigest(sql: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        query(sql).use { cursor ->
            digest.putInt(cursor.columnCount)
            while (cursor.moveToNext()) {
                digest.putByte(ROW_MARKER)
                repeat(cursor.columnCount) { column ->
                    digest.putInt(cursor.getType(column))
                    when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> Unit
                        Cursor.FIELD_TYPE_INTEGER -> digest.putLong(cursor.getLong(column))
                        Cursor.FIELD_TYPE_FLOAT -> digest.putLong(
                            java.lang.Double.doubleToRawLongBits(cursor.getDouble(column)),
                        )
                        Cursor.FIELD_TYPE_STRING -> digest.putBytes(
                            cursor.getString(column).toByteArray(StandardCharsets.UTF_8),
                        )
                        Cursor.FIELD_TYPE_BLOB -> digest.putBytes(cursor.getBlob(column))
                        else -> error("unsupported SQLite cursor field type")
                    }
                }
            }
        }
        return digest.digest()
    }

    private fun SupportSQLiteDatabase.journalMode(): String =
        queryOne("PRAGMA journal_mode", emptyArray()) { it.getString(0).lowercase() }

    private fun <T> SupportSQLiteDatabase.queryOne(
        sql: String,
        args: Array<out Any?>,
        mapper: (Cursor) -> T,
    ): T = query(SimpleSQLiteQuery(sql, args)).use { cursor ->
        check(cursor.moveToFirst()) { "expected one SQLite row" }
        mapper(cursor)
    }

    private fun assertCompleteRowsEqual(
        expected: GuidedSessionBootstrapRows,
        actual: GuidedSessionBootstrapRows,
    ) {
        assertEquals(expected.shoot, actual.shoot)
        assertEquals(expected.session, actual.session)
        assertEquals(expected.poses, actual.poses)
        assertEquals(expected.attempts, actual.attempts)
        assertEquals(expected.privateOutputs, actual.privateOutputs)
        assertEquals(expected.receipts, actual.receipts)
        assertEquals(expected.outboxes, actual.outboxes)
        assertEquals(expected.exportOutputs, actual.exportOutputs)
        assertEquals(expected.captureFileOperations, actual.captureFileOperations)
    }

    private fun assertNoTestDatabaseResidue() {
        assertFalse(context.databaseList().contains(databaseName))
        val residue = context.roomTestDatabaseResidue(databaseName)
        assertTrue("test database residue remains: ${residue.map { it.name }}", residue.isEmpty())
    }

    private fun MessageDigest.putByte(value: Byte) {
        update(byteArrayOf(value))
    }

    private fun MessageDigest.putInt(value: Int) {
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
    }

    private fun MessageDigest.putLong(value: Long) {
        update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
    }

    private fun MessageDigest.putBytes(value: ByteArray) {
        putInt(value.size)
        update(value)
    }

    private data class ExclusionResult<WriterResult>(
        val readerRows: GuidedSessionBootstrapRows,
        val writerResult: WriterResult,
    )

    private data class SafeSessionFacts(
        val currentPoseIndex: Int,
        val nextAttemptNumber: Long,
        val lifecycleState: String,
    )

    private data class SafeShootFacts(
        val lifecycleState: String,
        val deletionGeneration: Long,
    )

    private data class SafeReceiptFacts(
        val receiptCount: Int,
        val fromPoseIndex: Int,
        val toPoseIndex: Int,
        val privateOutputCount: Int,
        val exportOutputCount: Int,
    )

    private data class SafeDeletionFacts(
        val shootLifecycle: String,
        val deletionGeneration: Long,
        val cancelledOutboxCount: Int,
        val cancelledOutputCount: Int,
    )

    private data class ReadOnlyEvidence(
        val tableDigests: Map<String, ByteArray>,
        val schemaDigest: ByteArray,
        val totalChanges: Long,
        val dataVersion: Long,
    )

    private companion object {
        const val SHORT_BLOCK_MILLIS = 300L
        const val LONG_TIMEOUT_SECONDS = 10L
        const val EXPECTED_BOOTSTRAP_SELECT_COUNT = 9
        const val ROW_MARKER: Byte = 1

        val DIRECT_EXECUTOR = Executor { command -> command.run() }
        val AUTHORITY_TABLES = listOf(
            "shoots",
            "shoot_poses",
            "shoot_sessions",
            "capture_attempts",
            "private_capture_outputs",
            "capture_confirmation_receipts",
            "capture_export_outboxes",
            "capture_export_outputs",
            "reference_import_intents",
            "reference_import_file_operations",
            "capture_file_operations",
        )
    }
}

private class SecondBootstrapSelectGate {
    private val armed = AtomicBoolean(false)
    private val selectCount = AtomicInteger(0)
    private val secondSelectReached = CountDownLatch(1)
    private val releaseSecondSelect = CountDownLatch(1)

    fun arm() {
        selectCount.set(0)
        armed.set(true)
    }

    fun disarm() {
        armed.set(false)
    }

    fun onQuery(sql: String) {
        if (!armed.get() || !sql.isGuidedBootstrapSelect()) return
        if (selectCount.incrementAndGet() == 2) {
            secondSelectReached.countDown()
            check(releaseSecondSelect.await(10L, TimeUnit.SECONDS)) {
                "bootstrap second SELECT was not released within its bound"
            }
        }
    }

    fun awaitSecondSelect(): Boolean = secondSelectReached.await(10L, TimeUnit.SECONDS)

    fun release() {
        releaseSecondSelect.countDown()
    }

    fun bootstrapSelectCount(): Int = selectCount.get()
}

private fun String.isGuidedBootstrapSelect(): Boolean {
    val normalized = lowercase().replace(Regex("\\s+"), " ").trim()
    if (!normalized.startsWith("select ")) return false
    return BOOTSTRAP_FROM_MARKERS.any(normalized::contains)
}

private val BOOTSTRAP_FROM_MARKERS = listOf(
    " from shoot_sessions ",
    " from shoots as shoot ",
    " from shoot_poses as pose ",
    " from capture_attempts ",
    " from private_capture_outputs as private_output ",
    " from capture_confirmation_receipts as receipt ",
    " from capture_export_outboxes as outbox ",
    " from capture_export_outputs as output ",
    " from capture_file_operations as operation ",
)
