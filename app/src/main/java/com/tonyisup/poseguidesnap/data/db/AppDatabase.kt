package com.tonyisup.poseguidesnap.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    internal abstract fun captureAttemptDao(): CaptureAttemptDao

    internal abstract fun captureConfirmationDao(): CaptureConfirmationDao

    internal abstract fun deletionExportDao(): DeletionExportDao

    companion object {
        const val DATABASE_NAME = "pose_guide_snap_private.db"

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
            ).addCallback(AUTHORITY_SCHEMA_CALLBACK)
                .build()
    }
}