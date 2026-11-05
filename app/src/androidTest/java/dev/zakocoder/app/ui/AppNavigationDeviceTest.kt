package dev.zakocoder.app.ui

import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Real application navigation. Does not create projects, edit drafts, or send model requests. */
class AppNavigationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

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

    private fun matches(node: SemanticsNode, label: String): Boolean =
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true

    private fun clickable(node: SemanticsNode): SemanticsNode? =
        generateSequence(node) { it.parent }.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null }

    private fun drawerItem(node: SemanticsNode): SemanticsNode? =
        generateSequence(node) { it.parent }.firstOrNull {
            it.config.getOrNull(SemanticsProperties.Selected) != null
        }

    private fun visible(node: SemanticsNode): Boolean {
        val bounds = node.boundsInWindow
        return bounds.width > 0 && bounds.height > 0 && bounds.right > 0 && bounds.bottom > 0 &&
            bounds.center.x > 0 && bounds.center.x < context.resources.displayMetrics.widthPixels
    }

    private fun await(label: String, timeout: Long = 12_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError(label)
            Thread.sleep(10)
        }
    }

    private fun click(label: String, destinationOnly: Boolean = false) {
        fun eligible(node: SemanticsNode) = matches(node, label) && visible(node) &&
            (!destinationOnly || drawerItem(node) != null)
        await("Visible enabled action: $label") { nodes { all -> all.any(::eligible) } }
        nodes { all ->
            val target = requireNotNull(clickable(all.first(::eligible)))
            assertNull("Enabled: $label", target.config.getOrNull(SemanticsProperties.Disabled))
            assertTrue("Click: $label", target.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }

    private fun drawerClosed() = nodes { all ->
        all.none { matches(it, context.getString(R.string.app_navigation_close)) && visible(it) }
    }

    private fun ensureActivityResumed(scenario: ActivityScenario<MainActivity>) {
        assertEquals("Back must keep the application Activity", Lifecycle.State.RESUMED, scenario.state)
        scenario.onActivity { assertFalse("Activity must not finish", it.isFinishing) }
    }

    @Test(timeout = 120_000)
    fun actualDrawerOpeningBackStableBackAndAllFiveDestinations() {
        // Only wake the screen; do not alter lock credentials, app permissions, or saved configuration.
        listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard").forEach { command ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        warmDeviceActivity()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val menu = context.getString(R.string.app_navigation_open)
            val close = context.getString(R.string.app_navigation_close)
            click(menu)
            // Observe the actual drawer moving into the screen; no fixed wait that hides the race.
            await("Drawer must be observed before its opening animation settles", timeout = 3_000) {
                nodes { all -> all.filter { matches(it, context.getString(R.string.nav_workspace)) }
                    .mapNotNull(::drawerItem).any {
                        // boundsInWindow is clipped at the screen edge. Position and measured
                        // size preserve the negative translation during the drawer animation.
                        val left = it.positionInWindow.x
                        left < 0 && left + it.size.width > 0 && it.size.width > 0
                    } }
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await("Back during drawer opening closes the drawer") { drawerClosed() }
            ensureActivityResumed(scenario)

            click(menu)
            var lastLeft = Float.NaN
            var unchangedSince = SystemClock.uptimeMillis()
            await("Drawer reaches stable fully visible position") {
                val left = nodes { all -> all.filter { matches(it, context.getString(R.string.nav_workspace)) }
                    .mapNotNull(::drawerItem).firstOrNull { it.positionInWindow.x >= 0 && visible(it) }
                    ?.positionInWindow?.x }
                if (left == null || left != lastLeft) {
                    lastLeft = left ?: Float.NaN
                    unchangedSince = SystemClock.uptimeMillis()
                    false
                } else SystemClock.uptimeMillis() - unchangedSince >= 80
            }
            // A stable frame is verified from geometry, not inferred from a fixed sleep.
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await("Back on a fully opened drawer closes it") { drawerClosed() }
            ensureActivityResumed(scenario)

            listOf(R.string.nav_workspace, R.string.nav_environment, R.string.nav_terminal,
                R.string.nav_ai, R.string.nav_settings).forEach { destination ->
                click(menu)
                val label = context.getString(destination)
                click(label, destinationOnly = true)
                await("Destination closes navigation: $label") { drawerClosed() }
                ensureActivityResumed(scenario)
                click(menu)
                await("Correct selected destination: $label") {
                    nodes { all -> all.filter { matches(it, label) && visible(it) }.mapNotNull(::drawerItem)
                        .any { it.config.getOrNull(SemanticsProperties.Selected) == true } }
                }
                click(close)
                await("Explicit drawer close: $label") { drawerClosed() }
            }
        }
    }
}
