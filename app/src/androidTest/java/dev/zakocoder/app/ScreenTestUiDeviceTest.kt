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
                        LocalDensity provides Density(widthPx.coerceAtLeast(1) / 360f, 1.3f)) {
                        MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                        }
                    }
                } }
            }
            fun panel(model: ScreenTestUiModel, failure: String? = null) {
                show { WorkbenchSheet(context.getString(R.string.screen_test_title), {}) {
                    ScreenTestPanel(model, error = failure, onChoose = { choose.set(true) }, onEnable = { enable.set(true) },
                        onStop = { stopped.set(true) }, onReport = { openedReport.set(true) }, onAccess = { access.set(it) },
                        onRoot = { root.set(true) }, onShare = { shared.set(it) }, onInstallPermission = { installPermission.set(true) })
                } }
            }
            panel(ScreenTestUiModel())
            await("No project") { has(context.getString(R.string.screen_test_no_project)) }
            assertFalse(has(context.getString(R.string.screen_test_choose_apk)))
            capture(dark, "no-project")

            panel(ScreenTestUiModel(projectName = "Screen test fixture", hasProject = true))
            click(context.getString(R.string.screen_test_choose_apk)); assertTrue(choose.get())
            click(context.getString(R.string.screen_test_allow_install)); assertTrue(installPermission.get())
            assertFalse(has(context.getString(R.string.screen_test_enable_service)))
            click(context.getString(R.string.screen_test_access_accessibility)); assertEquals(ScreenTestAccess.Accessibility, access.get())
            capture(dark, "normal-360-font130")
            panel(ScreenTestUiModel(projectName = "Screen test fixture", hasProject = true, installAllowed = true))
            await("Installation source permission refreshed") { has(context.getString(R.string.screen_test_install_allowed)) }
            assertFalse(has(context.getString(R.string.screen_test_allow_install)))
            reveal(context.getString(R.string.screen_test_install_allowed))
            capture(dark, "normal-install-allowed")

            show { WorkbenchSheet(context.getString(R.string.screen_test_apk_heading), {}) {
                ScreenTestApkPicker(emptyList(), false, false, null, {}, {})
            } }
            await("Empty APK list") { has(context.getString(R.string.screen_test_apk_empty)) }
            capture(dark, "apk-empty")

            val chosen = AtomicReference<ScreenTestApkUi>()
            val apk = ScreenTestApkUi("app/build/outputs/apk/debug/app-debug.apk", "dev.zakocoder.fixture", "Screen test fixture", true)
            show { WorkbenchSheet(context.getString(R.string.screen_test_apk_heading), {}) {
                ScreenTestApkPicker(listOf(apk), false, false, null, {}, { chosen.set(it) })
            } }
            click(context.getString(R.string.screen_test_apk_authorize)); assertEquals(apk, chosen.get())
            capture(dark, "apk-selection")

            val selected = ScreenTestUiModel(projectName = "Screen test fixture", hasProject = true, packageName = apk.packageName,
                apkPath = apk.path, active = true, summary = "assert_text: failed — expected 保存成功", hasReport = true,
                access = ScreenTestAccess.Accessibility, installAllowed = true)
            val failure = "SCREEN_TEST_DISCONNECTED: 请开启屏幕交互后重试。"
            panel(selected, failure)
            await("Chosen target") { has(apk.packageName) }
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
                val switch = all.last { it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Switch }
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

            val rootGuidance = context.getString(R.string.screen_test_root_denied, context.packageName)
            panel(selected.copy(access = ScreenTestAccess.Root, rootGranted = false), rootGuidance)
            await("Actionable root authorization guidance") { has(rootGuidance) }
            assertTrue(rootGuidance.contains(context.packageName))
            assertTrue(rootGuidance.contains("APatch"))
            assertFalse(has(context.getString(R.string.screen_test_root_granted)))
            reveal(rootGuidance)
            capture(dark, "root-permission-guidance")

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
