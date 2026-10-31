package dev.zakocoder.app

import android.Manifest
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.permissions.AppPermission
import dev.zakocoder.app.permissions.AppPermissions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppPermissionsDeviceTest {
    @Test fun snapshotReadsEveryCapabilityWithoutNavigationOrGrant() = runBlocking {
        val access = AppPermissions(InstrumentationRegistry.getInstrumentation().targetContext)
        val first = access.snapshot(); val second = access.snapshot()
        assertEquals(AppPermission.entries.toSet(), first.map { it.permission }.toSet())
        assertEquals(first, second)
        assertNotNull(first.single { it.permission == AppPermission.Notifications }.notificationStatus)
    }
    @Test fun runtimePermissionRequestsOnlyUseAlreadyDeclaredVersionAppropriatePermissions() {
        val access = AppPermissions(InstrumentationRegistry.getInstrumentation().targetContext)
        assertArrayEquals(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray<String>(),
            access.runtimePermissions(AppPermission.Notifications))
        assertArrayEquals(if (Build.VERSION.SDK_INT < 30) arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyArray<String>(),
            access.runtimePermissions(AppPermission.Files))
        listOf(AppPermission.InstallPackages, AppPermission.Overlay, AppPermission.Accessibility, AppPermission.Battery).forEach {
            assertTrue(access.runtimePermissions(it).isEmpty())
        }
    }
}
