package dev.zakocoder.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.screentest.ScreenTestAccess
import dev.zakocoder.app.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Render-only fixture: never installs apps, grants accessibility/root, or reads user projects. */
@SdkSuppress(minSdkVersion = 30)
class ScreenTestUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()

    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync {
            val all = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) { all += node; node.children.forEach(::collect) }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(block(all))
        }
        return result.get()
    }
    private fun switchControl(all: List<SemanticsNode>, label: String): SemanticsNode {
        val labelled = all.single { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        fun find(node: SemanticsNode): SemanticsNode? {
            if (node.config.getOrNull(SemanticsProperties.ToggleableState) != null) return node
            return node.children.firstNotNullOfOrNull(::find)
        }
        return requireNotNull(find(labelled)) { "Switch semantics for $label" }
    }
    private fun text(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }.orEmpty()
    private fun has(label: String) = nodes { all -> all.any { text(it) == label } }
    private fun await(label: String, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= until) throw AssertionError(label)
            Thread.sleep(50)
        }
    }
    private fun click(label: String) {
        await("Control: $label") { has(label) }
        nodes { all ->
            var node = all.last { text(it) == label } as SemanticsNode?
            while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
            val control = requireNotNull(node)
            assertNull("Enabled: $label", control.config.getOrNull(SemanticsProperties.Disabled))
            assertTrue(control.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }
    private fun reveal(label: String) {
        // Expansion is a recomposition, not an immediate mutation of the semantics tree.
        await("Reveal content composed: $label") { has(label) }
        var diagnostic = "No target geometry"
        repeat(30) {
            val visible = nodes { all ->
                val target = all.lastOrNull { text(it) == label } ?: return@nodes false
                var scroll: SemanticsNode? = target
                while (scroll != null && scroll.config.getOrNull(SemanticsActions.ScrollBy) == null) scroll = scroll.parent
                val viewport = scroll?.boundsInWindow
                val targetTop = target.layoutInfo.coordinates.localToWindow(Offset.Zero).y
                val targetBottom = targetTop + target.layoutInfo.coordinates.size.height
                // Use the actual clipped scroll viewport, including this Sheet's system insets.
                // A fixed 100px bottom margin cannot be satisfied by some final list items.
                val visibleTop = (viewport?.top ?: 0f) + 4f
                val visibleBottom = (viewport?.bottom ?: context.resources.displayMetrics.heightPixels.toFloat()) - 4f
                val axis = scroll?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                diagnostic = "target=$targetTop..$targetBottom viewport=$visibleTop..$visibleBottom " +
                    "scroll=${axis?.value?.invoke()}/${axis?.maxValue?.invoke()}"
                if (targetTop >= visibleTop && targetBottom <= visibleBottom) return@nodes true
                val delta = when {
                    targetBottom > visibleBottom -> targetBottom - visibleBottom + 8f
                    targetTop < visibleTop -> targetTop - visibleTop - 8f
                    else -> 0f
                }
                scroll?.config?.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, delta)
                false
            }
            if (visible) return
            instrumentation.waitForIdleSync(); SystemClock.sleep(120)
        }
        throw AssertionError("Could not reveal: $label; $diagnostic")
    }
    @Test(timeout = 120000) fun lightScreenTestingStatesAndCallbacks() = review(false)
    @Test(timeout = 120000) fun darkScreenTestingStatesAndCallbacks() = review(true)

    private fun review(dark: Boolean) {
        val choose = AtomicBoolean()
        val enable = AtomicBoolean()
        val installPermission = AtomicBoolean()
        val stopped = AtomicBoolean()
        val openedReport = AtomicBoolean()
        val shared = AtomicBoolean()
        val overlaySettings = AtomicBoolean()
        val overlayEnabled = AtomicReference<Boolean>()
        val root = AtomicBoolean()
        val access = AtomicReference<ScreenTestAccess>()
        val fixtureReport = """# Android screen test
Project: Screen test fixture
Target: dev.zakocoder.fixture
Result: FAILED
1. launch: passed
2. assert_text: failed — expected 保存成功
No test result was invented; this is UI-only fixture content."""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun show(content: @Composable () -> Unit) {
                scenario.onActivity { activity -> activity.setContent {
                    val current = LocalConfiguration.current
                    val widthPx = LocalWindowInfo.current.containerSize.width
                    val configuration = remember(current, dark) { Configuration(current).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    } }
                    CompositionLocalProvider(LocalConfiguration provides configuration, LocalWorkbenchDark provides dark,
                        LocalDensity provides Density(widthPx.coerceAtLeast(1) / 393f, 1f)) {
                        WorkbenchMiuixTheme(dark) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                        }
                    }
                } }
            }
            fun panel(model: ScreenTestUiModel, failure: String? = null) {
                show { WorkbenchSheet(context.getString(R.string.screen_test_title), {}) {
                    ScreenTestPanel(model, error = failure, onEnable = { enable.set(true) },
                        onStop = { stopped.set(true) }, onReport = { openedReport.set(true) }, onAccess = { access.set(it) },
                        onRoot = { root.set(true) }, onShare = { shared.set(it) },
                        onOverlayEnabled = { overlayEnabled.set(it) }, onOverlaySettings = { overlaySettings.set(true) })
                } }
            }
            panel(ScreenTestUiModel())
            await("Global screen scope without a workspace") { has(context.getString(R.string.screen_device_current)) }
            assertFalse(has(context.getString(R.string.screen_test_no_project)))
            assertFalse(has(context.getString(R.string.screen_test_choose_apk)))
            assertFalse(has(context.getString(R.string.screen_test_allow_install)))
            click(context.getString(R.string.screen_test_enable_service)); assertTrue(enable.get())
            click(context.getString(R.string.screen_test_access_root)); assertEquals(ScreenTestAccess.Root, access.get())
            capture(dark, "global-no-workspace")

            val selected = ScreenTestUiModel(packageName = "dev.zakocoder.fixture", active = true, summary = "assert_text: failed — expected 保存成功", hasReport = true,
                access = ScreenTestAccess.Accessibility)
            val failure = "SCREEN_TEST_DISCONNECTED: 请开启屏幕交互后重试。"
            panel(selected, failure)
            await("Chosen target") { has(context.getString(R.string.screen_device_foreground, "dev.zakocoder.fixture")) }
            click(context.getString(R.string.screen_test_enable_service)); assertTrue(enable.get())
            capture(dark, "accessibility-target")
            click(context.getString(R.string.screen_test_stop)); assertTrue(stopped.get())
            click(context.getString(R.string.screen_test_report)); assertTrue(openedReport.get())
            reveal(failure)
            capture(dark, "activity-error-report")

            panel(selected.copy(access = ScreenTestAccess.Root, rootGranted = true))
            click(context.getString(R.string.screen_test_root_request)); assertTrue(root.get())
            await("Root granted") { has(context.getString(R.string.screen_test_root_granted)) }
            nodes { all ->
                val switch = switchControl(all, context.getString(R.string.screen_test_share_label))
                assertTrue(switch.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            assertTrue(shared.get())
            reveal(context.getString(R.string.screen_test_report)); capture(dark, "root-share-stop")
            click(context.getString(R.string.screen_test_details))
            reveal(context.getString(R.string.screen_test_root_text_hint))
            capture(dark, "root-capabilities")
            enable.set(false)
            reveal(context.getString(R.string.screen_test_enable_service))
            click(context.getString(R.string.screen_test_enable_service)); assertTrue(enable.get())
            capture(dark, "root-accessibility-entry")

            val rootGuidance = context.getString(R.string.screen_test_root_denied)
            panel(selected.copy(access = ScreenTestAccess.Root, rootGranted = false), rootGuidance)
            await("Actionable root authorization guidance") { has(rootGuidance) }
            assertFalse(rootGuidance.contains(context.packageName))
            assertTrue(rootGuidance.contains("重试"))
            assertFalse(has(context.getString(R.string.screen_test_root_granted)))
            reveal(rootGuidance)
            capture(dark, "root-permission-guidance")

            // Render-only overlay states never request a real system permission or create a window.
            panel(selected.copy(overlayEnabled = true, overlayGranted = false))
            reveal(context.getString(R.string.screen_overlay_open_settings))
            nodes { all ->
                val control = switchControl(all, context.getString(R.string.screen_overlay_switch))
                assertNotNull(control.config.getOrNull(SemanticsProperties.Disabled))
                assertEquals(androidx.compose.ui.state.ToggleableState.Off, control.config[SemanticsProperties.ToggleableState])
            }
            capture(dark, "overlay-permission-needed")
            click(context.getString(R.string.screen_overlay_open_settings)); assertTrue(overlaySettings.get())
            assertNull(overlayEnabled.get())

            panel(selected.copy(overlayGranted = true))
            reveal(context.getString(R.string.screen_overlay_off))
            nodes { all ->
                val control = switchControl(all, context.getString(R.string.screen_overlay_switch))
                assertEquals(androidx.compose.ui.state.ToggleableState.Off, control.config[SemanticsProperties.ToggleableState])
                assertTrue(control.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            assertEquals(true, overlayEnabled.get())
            capture(dark, "overlay-authorized-off")

            panel(selected.copy(overlayGranted = true, overlayEnabled = true, overlayVisible = true))
            reveal(context.getString(R.string.screen_overlay_visible)); capture(dark, "overlay-visible-status")
            panel(selected.copy(active = false, overlayGranted = true, overlayEnabled = true))
            reveal(context.getString(R.string.screen_overlay_waiting_target)); capture(dark, "overlay-waiting-target")

            listOf(
                "window_permission" to R.string.screen_overlay_failure_permission,
                "foreground_service" to R.string.screen_overlay_failure_background,
                "window_token" to R.string.screen_overlay_failure_window,
            ).forEach { (code, message) ->
                panel(selected.copy(overlayGranted = code != "window_permission", overlayFailure = code))
                reveal(context.getString(message))
                assertFalse(has(code))
                assertFalse(has(context.getString(R.string.screen_overlay_off)))
                reveal(context.getString(R.string.screen_overlay_failure_fallback))
                capture(dark, "overlay-failure-$code")
                if (code != "window_permission") {
                    overlayEnabled.set(null)
                    reveal(context.getString(R.string.screen_overlay_retry))
                    click(context.getString(R.string.screen_overlay_retry)); assertEquals(true, overlayEnabled.get())
                } else {
                    reveal(context.getString(R.string.screen_overlay_open_settings))
                    click(context.getString(R.string.screen_overlay_open_settings))
                }
            }

            val consent = AtomicBoolean()
            val dismissed = AtomicBoolean()
            show { ScreenTestScreenshotConsent({ consent.set(true) }, { dismissed.set(true) }) }
            await("Screenshot consent") { has(context.getString(R.string.screen_test_share_confirm_title)) }
            assertFalse(consent.get())
            capture(dark, "screenshot-consent")
            click(context.getString(R.string.cancel)); assertTrue(dismissed.get()); assertFalse(consent.get())
            show { ScreenTestScreenshotConsent({ consent.set(true) }, {}) }
            click(context.getString(R.string.screen_test_share_confirm_action)); assertTrue(consent.get())

            show { WorkbenchSheet(context.getString(R.string.screen_test_report_title), {}) { ScreenTestReport(fixtureReport) } }
            await("Actual report fixture") { has(fixtureReport) }
            capture(dark, "report-content")
        }
    }

    private fun capture(dark: Boolean, name: String) {
        instrumentation.waitForIdleSync()
        // A passing semantic callback can precede the Sheet expansion animation. Wait for
        // actual window-relative geometry to settle before treating the screenshot as evidence.
        SystemClock.sleep(400)
        var previous = emptyList<Pair<String, Int>>()
        var stable = 0
        val until = SystemClock.uptimeMillis() + 5000
        while (stable < 3 && SystemClock.uptimeMillis() < until) {
            val geometry = nodes { all -> all.filter { text(it).isNotBlank() }.map {
                text(it) to it.layoutInfo.coordinates.localToWindow(Offset.Zero).y.toInt()
            } }
            stable = if (geometry == previous) stable + 1 else 0
            previous = geometry
            SystemClock.sleep(80)
        }
        assertTrue("Sheet layout settled before $name", stable >= 3)
        val raw = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, "ui-review/screen-testing/${if (dark) "dark" else "light"}-$name.png")
                .apply { parentFile!!.mkdirs() }.outputStream().use { assertTrue(raw.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { raw.recycle() }
    }
}
