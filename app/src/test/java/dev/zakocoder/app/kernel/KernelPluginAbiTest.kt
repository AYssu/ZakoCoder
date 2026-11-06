package dev.zakocoder.app.kernel

import org.junit.Assert.*
import org.junit.Test

class KernelPluginAbiTest {
    @Test fun downloadMatchesDeviceAbiRatherThanAlwaysArm64() {
        assertTrue(requireNotNull(KernelPluginManager.downloadUrl(listOf("arm64-v8a", "armeabi-v7a"))).endsWith("kernel-plugin-arm64-v8a.apk"))
        assertTrue(requireNotNull(KernelPluginManager.downloadUrl(listOf("x86_64", "x86"))).endsWith("kernel-plugin-x86_64.apk"))
        assertNull(KernelPluginManager.downloadUrl(listOf("x86")))
    }
}
