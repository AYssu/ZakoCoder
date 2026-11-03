package dev.zakocoder.app.kernel

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.warmDeviceActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in: the fixture APK must be signed with the same key as the isolated test app. */
class KernelPluginInstallDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && predicate(node)) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::walk) }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }.firstNotNullOfOrNull(::walk)
    }

    private suspend fun click(vararg texts: String, root: Boolean = false) {
        withTimeout(20_000) {
            while (true) {
                var node = find { n -> (!root || n.viewIdResourceName == "android:id/title") && texts.any { it.equals(n.text?.toString(), ignoreCase = true) || it.equals(n.contentDescription?.toString(), ignoreCase = true) } }
                if (node != null) {
                    while (!node!!.isClickable && node.parent != null) node = node.parent
                    if (node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withTimeout
                }
                delay(200)
            }
        }
    }

    @Test
    fun pluginFileProviderReadsStagedPackageAlongsideProjectProvider() {
        val fixture = File(context.cacheDir, "kernel-plugin/provider-proof.apk")
        fixture.parentFile!!.mkdirs()
        val bytes = "kernel-plugin-provider-regression".toByteArray()
        try {
            fixture.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.kernel-plugin", fixture)
            context.contentResolver.openInputStream(uri).use { stream ->
                assertNotNull(stream)
                assertArrayEquals(bytes, stream!!.readBytes())
            }
            assertNotNull(context.packageManager.resolveContentProvider("${context.packageName}.apk-files", 0))
        } finally { fixture.delete() }
    }

    @Test(timeout = 150_000)
    fun localSafPackageOpensSystemInstaller(): Unit = runBlocking {
        assumeTrue("Opt in only on an isolated installation", InstrumentationRegistry.getArguments().getString("kernelPluginInstall") == "true")
        assertTrue("Do not run against a user's normal application", context.packageName.endsWith(".plugintest"))
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        find { it.text?.toString().equals("Done", ignoreCase = true) || it.text?.toString() == "完成" }
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        warmDeviceActivity()
        val chosen = CompletableDeferred<Uri?>()
        var picker: ActivityResultLauncher<Array<String>>? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                picker = activity.activityResultRegistry.register("kernel-plugin-install-proof", ActivityResultContracts.OpenDocument()) { chosen.complete(it) }
                picker!!.launch(arrayOf("application/vnd.android.package-archive"))
            }
            try {
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\nKERNEL_PHASE=select-document\n") })
                click("显示根目录", "Show roots")
                click("下载内容", "下载", "Downloads", root = true)
                click(InstrumentationRegistry.getArguments().getString("kernelPluginFixture") ?: "pr48-kernel-install.apk")
                val uri = withTimeout(20_000) { chosen.await() }
                assertNotNull("SAF must grant a content URI", uri)
                assertEquals("content", uri!!.scheme)
                val manager = KernelPluginManager(context)
                manager.refresh()
                val previous = manager.status.value
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\nKERNEL_PHASE=import-and-validate\n") })
                val result = manager.installFromUri(uri)
                val expectedFailure = InstrumentationRegistry.getArguments().getString("kernelPluginExpectedFailure")
                if (expectedFailure != null) {
                    assertEquals(KernelPluginInstallResult.Failed(KernelPluginFailure.valueOf(expectedFailure)), result)
                    manager.refresh()
                    assertEquals("Rejected APK must preserve the installed version", previous, manager.status.value)
                    println("KERNEL_INSTALL_REJECTED reason=$expectedFailure")
                    return@use
                }
                assertEquals(KernelPluginInstallResult.InstallerOpened, result)
                val cancel = InstrumentationRegistry.getArguments().getString("kernelPluginCancel") == "true"
                if (cancel) {
                    instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\nKERNEL_PHASE=cancel-installer\n") })
                    click("取消", "Cancel", "取消安装", "取消更新")
                    delay(500)
                    manager.refresh()
                    assertEquals("Cancellation must preserve the installed version", previous, manager.status.value)
                    println("KERNEL_INSTALL_CANCEL_CONFIRMED")
                    return@use
                }
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\nKERNEL_PHASE=confirm-installer\n") })
                click("安装", "Install", "更新", "Update", "继续安装", "继续更新")
                withTimeout(45_000) {
                    while (true) {
                        manager.refresh()
                        val current = manager.status.value
                        val expected = (InstrumentationRegistry.getArguments().getString("kernelPluginExpectedVersion") ?: "1").toInt()
                        if (current is KernelPluginStatus.Installed && current.info.versionCode == expected) break
                        delay(250)
                    }
                }
                click("完成", "Done")
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\nKERNEL_PHASE=refresh-and-bind\n") })
                val installed = manager.status.value as KernelPluginStatus.Installed
                assertEquals((InstrumentationRegistry.getArguments().getString("kernelPluginExpectedVersion") ?: "1").toInt(), installed.info.versionCode)
                val service = withContext(Dispatchers.IO) { manager.bind() }
                assertNotNull("Installed plugin must be bindable", service)
                assertEquals(1, withContext(Dispatchers.IO) { service!!.contractVersion() })
                manager.unbind()
                println("KERNEL_INSTALL_CONFIRMED version=${installed.info.versionCode}")
            } finally { instrumentation.runOnMainSync { picker?.unregister() } }
        }
    }
}
