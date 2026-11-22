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
import dev.zakocoder.app.ui.*
import dev.zakocoder.app.test.R as TestR
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    /** Match the title first, then its nearest preference row containing one real toggle. */
    private fun findSwitchControl(all: List<SemanticsNode>, label: String): SemanticsNode? {
        fun controls(node: SemanticsNode): List<SemanticsNode> = buildList {
            // Disabled native switches can retain state while removing OnClick.
            if (node.config.getOrNull(SemanticsProperties.ToggleableState) != null) add(node)
            node.children.forEach { addAll(controls(it)) }
        }
        return all.asSequence().filter {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true ||
                label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }.firstNotNullOfOrNull { title ->
            generateSequence(title) { it.parent }.firstNotNullOfOrNull { controls(it).singleOrNull() }
        }
    }
    private fun switchControl(all: List<SemanticsNode>, label: String): SemanticsNode =
        requireNotNull(findSwitchControl(all, label)) { "Switch semantics for $label" }
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
        val stopped = AtomicBoolean()
        val openedReport = AtomicBoolean()
        val shared = AtomicBoolean()
        val fixtureReport = """# Virtual screen operations
Target: dev.zakocoder.fixture
Display: 7
Result: FAILED
1. launch: passed
2. assert_text: failed — expected 保存成功
UI-only fixture: no real app or task was started."""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var renderKey = 0
            val committedRenderKey = AtomicInteger()
            fun show(content: @Composable () -> Unit) {
                val currentRenderKey = ++renderKey
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
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                // Each fixture is a new presentation. Reusing the same composition
                                // slot would retain a dismissed dialog's remembered shown=false.
                                key(currentRenderKey) {
                                    content()
                                    SideEffect { committedRenderKey.set(currentRenderKey) }
                                }
                            }
                        }
                    }
                } }
                // setContent schedules recomposition; matching text can still belong to
                // the previous fixture until this presentation has actually committed.
                await("Fixture composition committed: $currentRenderKey") {
                    committedRenderKey.get() == currentRenderKey
                }
            }
            fun panel(model: VirtualScreenTaskUiModel) {
                show { WorkbenchSheet(context.getString(R.string.virtual_screen_title), {}) {
                    VirtualScreenTaskPanel(model, onShare = { shared.set(it) },
                        onStop = { stopped.set(true) }, onReport = { openedReport.set(true) })
                } }
            }
            fun assertNoLegacyControls() {
                assertFalse(has(instrumentation.context.getString(TestR.string.screen_test_no_project)))
                assertFalse(has(instrumentation.context.getString(TestR.string.screen_test_choose_apk)))
                assertFalse(has(instrumentation.context.getString(TestR.string.screen_test_allow_install)))
                assertFalse(has(instrumentation.context.getString(TestR.string.screen_test_access)))
                assertFalse(has(instrumentation.context.getString(TestR.string.screen_test_enable_service)))
                assertFalse(has(instrumentation.context.getString(TestR.string.screen_overlay_title)))
                assertFalse(has(instrumentation.context.getString(TestR.string.screen_overlay_open_settings)))
            }
            panel(VirtualScreenTaskUiModel())
            await("Task guidance without workspace or APK") { has(context.getString(R.string.screen_test_idle)) }
            assertNoLegacyControls()
            assertFalse(has(context.getString(R.string.virtual_screen_share)))
            assertFalse(has(context.getString(R.string.screen_test_report)))
            capture(dark, "virtual-task-idle")

            val active = VirtualScreenTaskUiModel(active = true, busy = true,
                summary = "观察虚拟显示 7", actionTitle = "正在检查保存结果",
                actionDetail = "等待界面出现保存成功；尚未确认结果。", hasReport = true)
            panel(active)
            await("Actual operation status") { has(active.actionTitle) && has(active.actionDetail) }
            assertNoLegacyControls()
            nodes { all ->
                val control = switchControl(all, context.getString(R.string.virtual_screen_share))
                assertEquals(androidx.compose.ui.state.ToggleableState.Off, control.config[SemanticsProperties.ToggleableState])
                assertTrue(control.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            await("Screenshot sharing callback") { shared.get() }
            click(context.getString(R.string.screen_test_stop))
            await("Stop task callback") { stopped.get() }
            reveal(context.getString(R.string.screen_test_report))
            click(context.getString(R.string.screen_test_report))
            await("Operation report callback") { openedReport.get() }
            capture(dark, "virtual-task-active")

            panel(active.copy(busy = false, shareScreenshots = true))
            await("Sharing state is visible") { nodes { all ->
                findSwitchControl(all, context.getString(R.string.virtual_screen_share))
                    ?.config?.getOrNull(SemanticsProperties.ToggleableState) == androidx.compose.ui.state.ToggleableState.On
            } }
            nodes { all ->
                assertTrue(switchControl(all, context.getString(R.string.virtual_screen_share))
                    .config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            await("Screenshot sharing revoked callback") { !shared.get() }
            capture(dark, "virtual-task-sharing")

            val failure = "assert_text: failed — expected 保存成功"
            val failedTitle = context.getString(R.string.screen_overlay_progress_failed,
                context.getString(R.string.screen_overlay_action_assert))
            panel(VirtualScreenTaskUiModel(summary = failure, actionTitle = failedTitle,
                errorMessage = failure, hasReport = true))
            await("Failure reason remains visible below a non-empty progress title") { has(failedTitle) && has(failure) }
            assertNoLegacyControls()
            assertFalse(has(context.getString(R.string.virtual_screen_share)))
            assertFalse(has(context.getString(R.string.screen_test_stop)))
            click(context.getString(R.string.screen_test_report))
            capture(dark, "virtual-task-failed-report")

            val consent = AtomicBoolean()
            val dismissed = AtomicBoolean()
            show { ScreenTestScreenshotConsent({ consent.set(true) }, { dismissed.set(true) }) }
            await("Screenshot consent") { has(context.getString(R.string.screen_test_share_confirm_title)) }
            assertFalse(consent.get())
            capture(dark, "screenshot-consent")
            click(context.getString(R.string.cancel))
            // The common dialog completes its exit animation before invoking the owner.
            await("Screenshot consent dismiss callback after exit") { dismissed.get() }
            assertFalse("Cancel must never grant screenshot sharing", consent.get())
            show { ScreenTestScreenshotConsent({ consent.set(true) }, {}) }
            click(context.getString(R.string.screen_test_share_confirm_action))
            await("Screenshot consent confirmation callback after exit") { consent.get() }

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
