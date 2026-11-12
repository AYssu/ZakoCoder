package dev.zakocoder.app.ui

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Real Miuix dialog and window dispatcher; no model request, permission or workspace mutation. */
class MiuixApprovalDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val title = "审批返回手势回归"
    private val allow = "测试允许"
    private val deny = "测试拒绝"

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun <T> inspect(block: (List<Pair<View, SemanticsNode>>) -> T): T = onMain {
        val nodes = mutableListOf<Pair<View, SemanticsNode>>()
        fun collect(view: View, node: SemanticsNode) {
            nodes += view to node
            node.children.forEach { collect(view, it) }
        }
        fun visit(view: View) {
            if (!view.isShown) return
            if (view is ViewRootForTest) collect(view, view.semanticsOwner.unmergedRootSemanticsNode)
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        WindowInspector.getGlobalWindowViews().forEach(::visit)
        block(nodes)
    }

    private fun SemanticsNode.hasText(value: String) =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true

    private fun awaitTitle() {
        val deadline = android.os.SystemClock.uptimeMillis() + 12_000
        while (!inspect { nodes -> nodes.any { it.second.hasText(title) } }) {
            check(android.os.SystemClock.uptimeMillis() < deadline) { "Approval dialog did not appear" }
            Thread.sleep(40)
        }
        // Let the real window's entrance spring settle before comparing gesture transforms.
        Thread.sleep(700)
    }

    private fun bounds(): Rect = inspect { nodes ->
        val (view, node) = nodes.single { it.second.hasText(title) }
        node.boundsInWindow.also { bounds ->
            assertTrue("Approval title has visible area", bounds.width > 0f && bounds.height > 0f)
            assertTrue("Approval title stays inside its actual dialog window", bounds.left >= 0f &&
                bounds.top >= 0f && bounds.right <= view.rootView.width + 1f &&
                bounds.bottom <= view.rootView.height + 1f)
        }
    }

    private fun assertUnmoved(original: Rect) {
        val current = bounds()
        assertTrue("Non-dismissible approval must not follow predictive back: $original -> $current",
            abs(original.left - current.left) < 3f && abs(original.top - current.top) < 3f &&
                abs(original.right - current.right) < 3f && abs(original.bottom - current.bottom) < 3f)
    }

    private fun click(value: String) = inspect { nodes ->
        var target = nodes.single { it.second.hasText(value) }.second
        while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
        assertTrue("Approval button remains actionable", target.config[SemanticsActions.OnClick].action?.invoke() == true)
    }

    @Test fun nonDismissibleApprovalSurvivesCompletedAndCancelledPredictiveBackInBothThemes() {
        warmDeviceActivity()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                val approved = AtomicInteger()
                val rejected = AtomicInteger()
                val dismissed = AtomicInteger()
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                        WorkbenchMiuixTheme(dark) {
                            WorkbenchAlertDialog(dismissible = false,
                                onDismissRequest = { dismissed.incrementAndGet() },
                                title = { Text(title) }, text = { Text("需要明确选择允许或拒绝。") },
                                confirmButton = { TextButton(onClick = { approved.incrementAndGet() }) { Text(allow) } },
                                dismissButton = { TextButton(onClick = { rejected.incrementAndGet() }) { Text(deny) } })
                        }
                    }
                } }
                awaitTitle()
                val original = bounds()
                val input = DirectNavigationEventInput()
                val dispatcher: NavigationEventDispatcher = inspect { nodes ->
                    val view = nodes.single { it.second.hasText(title) }.first
                    requireNotNull(view.findViewTreeNavigationEventDispatcherOwner()).navigationEventDispatcher.also {
                        it.addInput(input)
                    }
                }
                try {
                    for (cancel in listOf(false, true)) {
                        onMain { input.backStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT)) }
                        for (progress in listOf(.5f, 1f)) {
                            onMain { input.backProgressed(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = progress)) }
                            Thread.sleep(100)
                            assertUnmoved(original)
                        }
                        onMain { if (cancel) input.backCancelled() else input.backCompleted() }
                        Thread.sleep(250)
                        assertUnmoved(original)
                        assertEquals("Back must not approve", 0, approved.get())
                        assertEquals("Back must not reject", 0, rejected.get())
                        assertEquals("Back must not dismiss", 0, dismissed.get())
                    }
                    click(allow)
                    assertEquals(1, approved.get())
                    assertEquals(0, rejected.get())
                    click(deny)
                    assertEquals(1, rejected.get())
                    assertEquals(0, dismissed.get())
                } finally {
                    onMain { dispatcher.removeInput(input) }
                }
            }
        }
    }
}
