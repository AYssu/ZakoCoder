package dev.zakocoder.app

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.projects.ProjectApkInstaller
import dev.zakocoder.app.projects.ProjectRepository
import dev.zakocoder.app.runtime.LinuxRuntime
import dev.zakocoder.app.runtime.PrivateFiles
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Run after AndroidBuildDeviceTest. Opens the native confirmation, never approves an installation. */
class AndroidApkInstallerDeviceTest {
    @Test(timeout = 60000) fun realApkReadGrantReachesSystemInstaller() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val context = instrumentation.targetContext
        val runtime = LinuxRuntime(context)
        val id = UUID.randomUUID().toString()
        val root = File(runtime.workspaces, id)
        val output = File(context.cacheDir, "android-build-validation")
        try {
            withContext(Dispatchers.IO) {
                val built = File(output, "AndroidJava.apk")
                check(built.isFile) { "Run AndroidBuildDeviceTest before the installer acceptance check" }
                check(root.mkdirs()); built.copyTo(File(root, "debug.apk"))
            }
            val installer = ProjectApkInstaller(context, ProjectRepository(context, runtime.workspaces))
            val intent = installer.prepare(id, "debug.apk").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            warmDeviceActivity()
            context.startActivity(intent)
            withTimeout(15000) {
                while (true) {
                    val packages = instrumentation.uiAutomation.windows.mapNotNull { it.root?.packageName?.toString() }
                    if (packages.any { it.contains("installer") || it.contains("securitycenter") || it == "com.android.settings" }) {
                        withContext(Dispatchers.IO) { File(output, "installer-window.txt").writeText(packages.distinct().joinToString("\n")) }
                        break
                    }
                    delay(200)
                }
            }
            delay(1000)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { withContext(Dispatchers.IO) {
                File(output, "installer.png").outputStream().use { assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            } } finally { screenshot.recycle() }
        } finally {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 4")).use { it.readBytes() }
            withContext(NonCancellable + Dispatchers.IO) { PrivateFiles.deleteTree(root) }
        }
    }
}
