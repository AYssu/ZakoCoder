package dev.zakocoder.app

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.kernel.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Checks real component wiring and Binder reconnection without reading any proxy configuration. */
class KernelDistributionDeviceTest {
    @Test(timeout = 45000) fun editionSelectsCorrectComponentAndFullRecoversIndependentProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context.packageName, "dev.zakocoder.app.kernel.KernelService")
        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        assertEquals(BuildConfig.BUNDLED_KERNEL, info.enabled)
        assertFalse(info.exported)
        assertEquals("${context.packageName}:kernel", info.processName)
        if (!BuildConfig.BUNDLED_KERNEL) return@runBlocking
        assumeTrue("Only write synthetic state in the isolated verification app", context.packageName.endsWith(".runtimecheck"))
        val processes = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // Start from a genuinely cold service process, rather than a previous test's warm instance.
        processes.runningAppProcesses.orEmpty().filter { it.processName == info.processName }.forEach {
            assertNotEquals(Process.myPid(), it.pid)
            Process.killProcess(it.pid)
        }
        val coldDeadline = android.os.SystemClock.uptimeMillis() + 5000
        while (processes.runningAppProcesses.orEmpty().any { it.processName == info.processName }
            && android.os.SystemClock.uptimeMillis() < coldDeadline) Thread.sleep(50)
        assertFalse("Kernel process must be cold before writing the main-process fixture",
            processes.runningAppProcesses.orEmpty().any { it.processName == info.processName })
        val portPreferences = context.getSharedPreferences("proxy-kernel", Context.MODE_PRIVATE)
        val settingsPreferences = context.getSharedPreferences("development-settings", Context.MODE_PRIVATE)
        val originalPort = if (portPreferences.contains("port")) portPreferences.getInt("port", 0) else null
        val originalProxy = if (settingsPreferences.contains("proxy")) settingsPreferences.getString("proxy", null) else null
        val fixturePort = 45678
        val fixtureProxy = "socks5://127.0.0.1:$fixturePort"
        assertTrue(portPreferences.edit().putInt("port", fixturePort).commit())
        assertTrue(settingsPreferences.edit().putString("proxy", fixtureProxy).commit())
        val preferencesDirectory = File(context.applicationInfo.dataDir, "shared_prefs")
        fun readDiskPreference(name: String, key: String): String? {
            // Read the actual backing file: SharedPreferences caches can hide cross-process writes.
            val file = File(preferencesDirectory, "$name.xml")
            file.reader().use { reader ->
                val parser = android.util.Xml.newPullParser()
                parser.setInput(reader)
                while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG
                        && parser.getAttributeValue(null, "name") == key) {
                        return if (parser.name == "string") parser.nextText() else parser.getAttributeValue(null, "value")
                    }
                    parser.next()
                }
            }
            return null
        }
        // A harmless, test-owned operation directory also catches duplicated Git startup sweeping.
        val authParent = File(context.filesDir, "linux-runtime/rootfs/.zakocoder-git-auth")
        val leaseFixture = if (authParent.isDirectory) File(authParent, "operation-kernel-fixture-${java.util.UUID.randomUUID()}")
            .also { check(it.mkdir()); File(it, "lifecycle-fixture").writeText("not-a-credential") } else null
        fun assertMainStatePreserved(stage: String) {
            assertEquals("$stage removed main-process kernel ownership marker", fixturePort.toString(), readDiskPreference("proxy-kernel", "port"))
            assertEquals("$stage changed main-process proxy setting", fixtureProxy, readDiskPreference("development-settings", "proxy"))
            leaseFixture?.let { assertTrue("$stage swept the main-process Git operation", File(it, "lifecycle-fixture").isFile) }
        }
        val manager = KernelPluginManager(context)
        val lost = CountDownLatch(1)
        try {
            assertTrue(manager.status.value is KernelPluginStatus.Installed)
            assertEquals(BuildConfig.KERNEL_VERSION, (manager.status.value as KernelPluginStatus.Installed).info.versionName)
            assertEquals(KernelPluginInstallResult.Cancelled, manager.downloadAndInstall())
            manager.uninstall() // Must not detach or open any external package installer.
            val first = requireNotNull(manager.bind(onLost = { lost.countDown() }))
            assertEquals(1, first.contractVersion())
            assertTrue(first.status().contains("stopped"))
            Thread.sleep(1500) // Allow any erroneous asynchronous startup recovery to reach disk.
            assertMainStatePreserved("cold bind")
            val pid = requireNotNull(processes.runningAppProcesses.firstOrNull { it.processName == info.processName }).pid
            assertNotEquals(Process.myPid(), pid)
            Process.killProcess(pid)
            assertTrue("Process death must invalidate current binding", lost.await(10, TimeUnit.SECONDS))
            assertFalse(manager.isCurrentService(first))
            val second = requireNotNull(manager.bind())
            assertEquals(1, second.contractVersion())
            assertTrue(manager.isCurrentService(second))
            assertTrue(manager.diagnostics().contains("distribution=full"))
            Thread.sleep(1500)
            assertMainStatePreserved("process restart/rebind")
        } finally {
            manager.unbind()
            portPreferences.edit().also { editor ->
                if (originalPort == null) editor.remove("port") else editor.putInt("port", originalPort)
            }.commit()
            settingsPreferences.edit().also { editor ->
                if (originalProxy == null) editor.remove("proxy") else editor.putString("proxy", originalProxy)
            }.commit()
            leaseFixture?.let { File(it, "lifecycle-fixture").delete(); it.delete() }
        }
    }
}
