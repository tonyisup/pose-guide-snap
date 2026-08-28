package com.tonyisup.poseguidesnap

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupExclusionManifestTest {
    @Test
    fun installedPackageOptsOutOfBackupAndHasOnlyAndroidXSignaturePermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val applicationInfo = requireNotNull(packageInfo.applicationInfo)
        val expectedPermission =
            "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

        assertEquals("com.tonyisup.poseguidesnap", packageInfo.packageName)
        assertEquals(0, applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals(
            setOf(expectedPermission),
            packageInfo.requestedPermissions.orEmpty().toSet(),
        )
    }
}
