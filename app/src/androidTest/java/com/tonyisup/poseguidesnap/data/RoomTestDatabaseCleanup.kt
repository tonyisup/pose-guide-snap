package com.tonyisup.poseguidesnap.data

import android.content.Context
import java.io.File

internal fun Context.deleteRoomTestDatabase(databaseName: String) {
    deleteDatabase(databaseName)
    roomTestDatabaseResidue(databaseName).forEach { file ->
        check(file.delete() || !file.exists()) { "generated Room test residue could not be removed" }
    }
}

internal fun Context.roomTestDatabaseResidue(databaseName: String): List<File> {
    val databaseFile = getDatabasePath(databaseName)
    val exactNames = setOf(
        databaseName,
        "$databaseName-shm",
        "$databaseName-wal",
        "$databaseName-journal",
        "$databaseName.lck",
    )
    return listOfNotNull(databaseFile.parentFile, cacheDir)
        .flatMap { directory -> directory.listFiles().orEmpty().asList() }
        .filter { file -> file.name in exactNames }
        .distinctBy(File::getAbsolutePath)
}
