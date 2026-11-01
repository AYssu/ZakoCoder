package dev.zakocoder.app.screentest

import android.app.UiAutomation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ui.WorkbenchColors
import dev.zakocoder.app.ui.WorkbenchDarkColors
import dev.zakocoder.app.ui.WorkbenchTypography
import dev.zakocoder.app.warmDeviceActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Uses real optional windows over an owned fixture, never a user's foreground application. */
@SdkSuppress(minSdkVersion = 30)
class ScreenTestOverlayUiDeviceTest {
    @Before fun foreground() = warmDeviceActivity()
    @Test fun dayMonitorDetailsAndCaptureRecovery() = review(false)
    @Test fun nightMonitorDetailsAndCaptureRecovery() = review(true)
    @SdkSuppress(minSdkVersion = 30)
    @Test fun landscapeLongConversationKeepsActionsInsideSafeBounds() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val autoRotate = Settings.System.getInt(target.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        val userRotation = Settings.System.getInt(target.contentResolver, Settings.System.USER_ROTATION, 0)
        try {
            check(automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
            withTimeout(10_000) {
                while (target.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) delay(50)
            }
            review(false, landscape = true)
        } finally {
            check(automation.setRotation(if (autoRotate == 1) UiAutomation.ROTATION_UNFREEZE else userRotation))
        }
    }
    private fun review(dark: Boolean, landscape: Boolean = false) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        check(target.packageName.endsWith(".runtimecheck"))
        assumeTrue("Owner enables overlay access for the isolated test package", Settings.canDrawOverlays(target))
        val configuration = Configuration(target.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            fontScale = 1.3f
        }
        val context = target.createConfigurationContext(configuration)
        var visible = false; var stopped = false; var returned = false
        val overlay = ScreenTestOverlay(context, { visible = it }, { _, _ -> returned = true }, { _, _ -> stopped = true }, { error("Overlay failed: $it") })
        val automation = instrumentation.uiAutomation
        val serviceInfo = automation.serviceInfo
        val previousFlags = serviceInfo.flags
        automation.serviceInfo = serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val directory = File(target.cacheDir, "debug-overlay-redesign").apply { mkdirs() }
        val prefix = "${if (dark) "night" else "day"}${if (landscape) "-landscape" else ""}"
        val conversation = "正在执行工具\n你：检查详情页按钮，确认跳转后能返回列表。\n助手：已经启动应用，正在核对页面节点与按钮位置。下一步会点击详情按钮，检查返回列表的操作。"
        val longConversation = conversation + "\n详情页应保留项目名称和返回入口；我会先观察当前页面，再执行按钮点击，核对结果后继续下一步。".repeat(12)
        val running = ScreenOverlayProgress(ScreenOverlayPhase.Verifying, "observe", "读取界面 · 校验应用制品")
        val succeeded = ScreenOverlayProgress(ScreenOverlayPhase.Succeeded, "observe", "读取界面 · 请求完成", "已恢复监看；读屏和触控时会暂时让出屏幕。")
        suspend fun update(progress: ScreenOverlayProgress, lease: String = "lease", text: String = conversation) = withContext(Dispatchers.Main) {
            overlay.update(true, "isolated-project", lease, "dev.zakocoder.fixture", "", text, progress)
        }
        fun roots() = automation.windows.mapNotNull { it.root }.filter { it.refresh() }
        fun descendants(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            if (!root.refresh()) return@buildList
            add(root)
            repeat(root.childCount) { index -> root.getChild(index)?.let { addAll(descendants(it)) } }
        }
        fun nodes() = roots().flatMap(::descendants).filter { it.packageName?.toString() == target.packageName }
        fun hasText(label: String) = nodes().any { it.text?.contains(label) == true || it.contentDescription?.contains(label) == true }
        fun node(label: String) = nodes().filter {
            (it.text?.contains(label) == true || it.contentDescription?.contains(label) == true) && it.isVisibleToUser
        }.sortedByDescending { it.isClickable }.firstOrNull()
        suspend fun settled() {
            instrumentation.waitForIdleSync(); automation.waitForIdle(100, 5000); delay(150)
            withTimeout(5000) { while (withContext(Dispatchers.Main) { overlay.isReadingScrollActive() }) delay(20) }
            instrumentation.waitForIdleSync()
        }
        fun capture(suffix: String) {
            check(automation.rootInActiveWindow?.packageName?.toString() == target.packageName) { "Do not capture another application's private background" }
            val bitmap = checkNotNull(automation.takeScreenshot())
            File(directory, "$prefix-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun click(label: String) {
            var candidate = checkNotNull(node(label))
            while (!candidate.isClickable && candidate.parent != null) candidate = candidate.parent
            assertTrue("No clickable action for $label", candidate.isClickable)
            assertTrue("Could not click $label", candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun scrollNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!root.refresh()) return null
            if (root.isScrollable) return root
            repeat(root.childCount) { index -> root.getChild(index)?.let { child -> scrollNode(child)?.let { return it } } }
            return null
        }
        try {
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("项目预览", style = MaterialTheme.typography.titleLarge)
                            Text("独立界面验收背景，不是用户项目。", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } }
            update(running); settled()
            assertTrue(visible)
            capture("monitor-running")
            instrumentation.sendStatus(0, Bundle().apply { putString("geometry", overlay.geometryDiagnostics()) })
            assertTrue("The monitor must expose the current action without opening details", hasText(running.title))
            assertNull(node(context.getString(R.string.debug_overlay_return)))
            capture("monitor-running")
            click(context.getString(R.string.screen_overlay_handle)); settled()
            assertNotNull(node("dev.zakocoder.fixture"))
            assertNotNull(node(context.getString(R.string.debug_overlay_return)))
            capture("details")
            update(running, text = longConversation); settled()
            val actions = withContext(Dispatchers.Main) { overlay.actionBoundsSnapshot() }
            val metrics = target.getSystemService(WindowManager::class.java).currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val safe = Rect(insets.left, insets.top, metrics.bounds.width() - insets.right, metrics.bounds.height() - insets.bottom)
            actions.values.forEach { assertTrue("Action outside safe viewport: $it, safe=$safe", safe.contains(it)) }
            assertEquals(2, actions.size)
            val body = checkNotNull(roots().firstNotNullOfOrNull(::scrollNode)) { "Long conversation must scroll" }
            assertTrue(body.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)); settled()
            val reading = withContext(Dispatchers.Main) { overlay.readingSnapshot() }
            assertFalse("Manual reading must disable following new output", reading.third)
            val fixed = withContext(Dispatchers.Main) { overlay.actionBoundsSnapshot() }
            assertEquals(actions, fixed)
            capture("details-reading")
            withContext(Dispatchers.Main) { overlay.hideForAction() }; settled()
            assertFalse(visible)
            assertNull(node(context.getString(R.string.screen_overlay_handle)))
            capture("capture-hidden")
            update(succeeded, text = longConversation); settled()
            assertTrue(visible)
            val restored = withContext(Dispatchers.Main) { overlay.readingSnapshot() }
            val maxScroll = withContext(Dispatchers.Main) { overlay.maxScroll() }
            assertEquals("Temporary hiding must preserve expansion, reading position (clamped to new viewport) and follow mode",
                reading.copy(second = reading.second.coerceAtMost(maxScroll)), restored)
            assertNotNull(node(context.getString(R.string.debug_overlay_return)))
            capture("details-restored")
            click(context.getString(R.string.debug_overlay_return)); settled(); assertTrue(returned)
            click(context.getString(R.string.debug_overlay_minimize)); settled()
            assertTrue(hasText(succeeded.title))
            capture("monitor-restored")
            update(ScreenOverlayProgress(ScreenOverlayPhase.Failed, "observe", "读取界面 · 未完成，请返回查看原因")); settled()
            capture("monitor-failed")
            update(ScreenOverlayProgress(), "new-lease", ""); settled()
            assertFalse(withContext(Dispatchers.Main) { overlay.readingSnapshot().first })
            assertNull(node(context.getString(R.string.debug_overlay_return)))
            assertFalse(hasText(succeeded.title))
            capture("monitor-new-target")
            click(context.getString(R.string.screen_overlay_handle)); settled()
            click(context.getString(R.string.debug_overlay_stop)); settled()
            assertTrue(stopped); assertFalse(visible)
            instrumentation.sendStatus(0, Bundle().apply { putString("overlayScreenshot", directory.absolutePath) })
        } finally {
            withContext(Dispatchers.Main) { overlay.remove() }
            automation.serviceInfo = serviceInfo.apply { flags = previousFlags }
            scenario.close()
        }
    }
}
