package dev.zakocoder.app.screentest

import android.app.UiAutomation
import android.content.res.Configuration
import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Isolated test package only; grants and device access are owned by the test orchestrator. */
class ScreenTestOverlayUiDeviceTest {
    @Before fun foreground() = warmDeviceActivity()
    @Test fun dayConversationAndCollapse() = review(false)
    @Test fun nightConversationAndCollapse() = review(true)

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
        assumeTrue("The device owner must grant the isolated test package overlay access", Settings.canDrawOverlays(target))
        val configuration = Configuration(target.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            fontScale = 1.3f
        }
        val context = target.createConfigurationContext(configuration)
        var visible = false
        var stopped = false
        val overlay = ScreenTestOverlay(context, { visible = it }, { _, _ -> }, { _, _ -> stopped = true }, { error("Overlay failed: $it") })
        val automation = instrumentation.uiAutomation
        val previous = automation.serviceInfo
        val previousFlags = previous.flags
        automation.serviceInfo = previous.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors,
                    typography = WorkbenchTypography) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("屏幕测试界面验收", style = MaterialTheme.typography.titleLarge)
                            Text("隔离测试背景，不是用户项目或真实会话。", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } }
            instrumentation.waitForIdleSync()
            withContext(Dispatchers.Main) {
                overlay.update(true, "isolated-project", "lease", "dev.zakocoder.fixture",
                    if (landscape) "已连接 · 正在验证页面\n界面节点已读取，等待检查\n所有操作仍受目标权限限制" else "已连接 · 正在验证页面",
                    "你：检查构建后的按钮是否能够打开详情。\n助手：已启动测试应用，正在读取页面节点并核对跳转结果。".repeat(if (landscape) 8 else 1))
            }
            delay(1000)
            assertTrue(visible)
            // Custom overlay layout can settle after the window-content event was queued.
            // Refresh both the root and the returned node: a fresh window list alone still
            // permits AccessibilityInteractionClient to serve pre-layout child bounds.
            fun roots() = automation.windows.mapNotNull { it.root }.filter { it.refresh() }
            fun node(text: String) = roots()
                .flatMap { it.findAccessibilityNodeInfosByText(text) }
                .firstOrNull { it.refresh() && it.packageName?.toString() == target.packageName &&
                    it.text?.toString() == text && it.isVisibleToUser }
            fun awaitLayoutIdle() {
                instrumentation.waitForIdleSync()
                automation.waitForIdle(100, 5_000)
                instrumentation.waitForIdleSync()
            }
            awaitLayoutIdle()
            assertTrue("The default collapsed entry must not expose conversation", node("dev.zakocoder.fixture") == null)
            assertTrue(node(context.getString(R.string.screen_overlay_return)) == null)
            val initialHandle = checkNotNull(node(context.getString(R.string.screen_overlay_handle)))
            assertTrue(initialHandle.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            awaitLayoutIdle()
            assertTrue(node("dev.zakocoder.fixture") != null)
            assertTrue(node(context.getString(R.string.screen_overlay_return)) != null)
            val directory = File(target.cacheDir, "screen-overlay-review").apply { mkdirs() }
            fun capture(suffix: String) {
                check(automation.rootInActiveWindow?.packageName?.toString() == target.packageName) {
                    "Do not capture a launcher or another application's private background"
                }
                val image = checkNotNull(automation.takeScreenshot())
                File(directory, "${if (dark) "night" else "day"}${if (landscape) "-landscape" else ""}-$suffix.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            if (landscape) {
                val metrics = target.getSystemService(WindowManager::class.java).currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val safe = Rect(insets.left, insets.top, metrics.bounds.width() - insets.right, metrics.bounds.height() - insets.bottom)
                fun accessibilityBounds(text: Int): Rect = Rect().also { checkNotNull(node(context.getString(text))).getBoundsInScreen(it) }
                suspend fun physicalBounds() = withContext(Dispatchers.Main) { overlay.actionBoundsSnapshot() }
                // Some device accessibility services report offset window coordinates even
                // after refresh. Verify physical layout with live View screen coordinates;
                // retain accessibility nodes for discovery, scrolling and actual clicks.
                val actions = physicalBounds()
                val returnBounds = checkNotNull(actions[R.string.screen_overlay_return])
                val stopBounds = checkNotNull(actions[R.string.screen_overlay_stop])
                val accessibilityReturn = accessibilityBounds(R.string.screen_overlay_return)
                val accessibilityStop = accessibilityBounds(R.string.screen_overlay_stop)
                val productGeometry = withContext(Dispatchers.Main) { overlay.geometryDiagnostics() }
                val boundsDetail = "safe=$safe; metrics=${metrics.bounds}; insets=$insets; return=$returnBounds; stop=$stopBounds; " +
                    "accessibilityReturn=$accessibilityReturn; accessibilityStop=$accessibilityStop; " +
                    "accessibilityCoordinatesMatchPhysical=${accessibilityReturn == returnBounds && accessibilityStop == stopBounds}; " +
                    "orientation=${target.resources.configuration.orientation}; fixtureFontScale=${configuration.fontScale}; product: $productGeometry"
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("overlayBounds", boundsDetail)
                    putString("overlayScreenshot", directory.absolutePath)
                })
                // Capture the actual failing layout over the isolated fixture before any assertion.
                capture("bounds")
                assertTrue("Return action must remain wholly in the safe viewport: $boundsDetail", safe.contains(returnBounds))
                assertTrue("Stop action must remain wholly in the safe viewport: $boundsDetail", safe.contains(stopBounds))
                fun scrollable(root: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                    if (!root.refresh()) return null
                    if (root.className?.toString()?.endsWith("ScrollView") == true && root.isScrollable) return root
                    repeat(root.childCount) { index -> root.getChild(index)?.let { child -> scrollable(child)?.let { return it } } }
                    return null
                }
                val scroll = checkNotNull(roots().firstNotNullOfOrNull(::scrollable)) {
                    "Long text in a short window must have a scrollable body"
                }
                assertTrue(scroll.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                awaitLayoutIdle()
                val afterScroll = physicalBounds()
                assertTrue("Return action must stay fixed while body scrolls", returnBounds == afterScroll[R.string.screen_overlay_return])
                assertTrue("Stop action must stay fixed while body scrolls", stopBounds == afterScroll[R.string.screen_overlay_stop])
            }
            capture("conversation")
            val handle = checkNotNull(node(context.getString(R.string.screen_overlay_handle)))
            assertTrue(handle.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            awaitLayoutIdle()
            assertTrue(node(context.getString(R.string.screen_overlay_return)) == null)
            capture("collapsed")
            assertTrue(checkNotNull(node(context.getString(R.string.screen_overlay_handle)))
                .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            awaitLayoutIdle()
            assertTrue(checkNotNull(node(context.getString(R.string.screen_overlay_stop))).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            delay(100)
            assertTrue(stopped)
            assertFalse(visible)
            instrumentation.sendStatus(0, Bundle().apply { putString("overlayScreenshot", directory.absolutePath) })
        } finally {
            withContext(Dispatchers.Main) { overlay.remove() }
            automation.serviceInfo = previous.apply { flags = previousFlags }
            scenario.close()
        }
    }
}
