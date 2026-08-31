package com.tonyisup.poseguidesnap.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    internal abstract fun captureAttemptDao(): CaptureAttemptDao

    internal abstract fun captureConfirmationDao(): CaptureConfirmationDao

    internal abstract fun deletionExportDao(): DeletionExportDao

    internal abstract fun referenceImportDao(): ReferenceImportDao

    internal abstract fun referenceImportFileOperationDao(): ReferenceImportFileOperationDao

    companion object {
        const val DATABASE_NAME = "pose_guide_snap_private.db"

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `shoot_poses` ADD COLUMN `landmark_payload` TEXT")
                db.execSQL("ALTER TABLE `shoot_poses` ADD COLUMN `coordinate_metadata` TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reference_import_intents` (
                        `import_token` TEXT NOT NULL,
                        `shoot_id` TEXT NOT NULL,
                        `pose_id` TEXT NOT NULL,
                        `pose_index` INTEGER NOT NULL,
                        `relative_asset_path` TEXT NOT NULL,
                        `lifecycle_state` TEXT NOT NULL,
                        `created_at_epoch_millis` INTEGER NOT NULL,
                        `updated_at_epoch_millis` INTEGER NOT NULL,
                        `asset_ready_at_epoch_millis` INTEGER,
                        `terminal_at_epoch_millis` INTEGER,
                        PRIMARY KEY(`import_token`),
                        FOREIGN KEY(`shoot_id`) REFERENCES `shoots`(`shoot_id`)
                            ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_reference_import_intents_shoot_id_pose_id` ON " +
                        "`reference_import_intents` (`shoot_id`, `pose_id`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_reference_import_intents_shoot_id_pose_index` ON " +
                        "`reference_import_intents` (`shoot_id`, `pose_index`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reference_import_intents_lifecycle_state` " +
                        "ON `reference_import_intents` (`lifecycle_state`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reference_import_file_operations` (
                        `import_token` TEXT NOT NULL,
                        `relative_asset_path` TEXT NOT NULL,
                        `relative_temp_path` TEXT NOT NULL,
                        `relative_quarantine_path` TEXT NOT NULL,
                        `stage` TEXT NOT NULL,
                        `byte_count` INTEGER,
                        `sha256` TEXT,
                        `last_failure_code` TEXT,
                        `reconciliation_required` INTEGER NOT NULL,
                        `created_at_epoch_millis` INTEGER NOT NULL,
                        `updated_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`import_token`),
                        FOREIGN KEY(`import_token`) REFERENCES `reference_import_intents`(`import_token`)
                            ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_reference_import_file_operations_stage` " +
                        "ON `reference_import_file_operations` (`stage`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_reference_import_file_operations_reconciliation_required` ON " +
                        "`reference_import_file_operations` (`reconciliation_required`)",
                )
            }
        }

        private val AUTHORITY_SCHEMA_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                AuthorityOrdinalTriggers.install(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                AuthorityOrdinalTriggers.install(db)
            }
        }

        fun create(context: Context): AppDatabase = create(context, DATABASE_NAME)

        internal fun create(
            context: Context,
            databaseName: String,
        ): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                databaseName,
            ).addMigrations(MIGRATION_1_2)
                .addCallback(AUTHORITY_SCHEMA_CALLBACK)
                .build()
    }
}