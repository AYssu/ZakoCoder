package dev.zakocoder.app

import dev.zakocoder.app.screentest.protectedDeviceScreenPackage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceScreenPolicyTest {
    @Test fun ordinaryForegroundAppsNeedNoDebugOrProjectIdentity() {
        listOf("dev.fixture.release", "dev.fixture.other", "com.android.settings").forEach {
            assertFalse(protectedDeviceScreenPackage(it, "dev.zakocoder.app", "dev.oem.permissions"))
        }
    }

    @Test fun approvalAndInstallerSurfacesCannotBeDelegated() {
        listOf("", "dev.zakocoder.app", "dev.oem.permissions", "android", "com.android.systemui",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.miui.packageinstaller", "com.samsung.android.packageinstaller").forEach {
            assertTrue(it, protectedDeviceScreenPackage(it, "dev.zakocoder.app", "dev.oem.permissions"))
        }
    }
}
