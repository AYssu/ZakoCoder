package dev.zakocoder.app.ui

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Miuix cards in both themes, without project, request or permission changes. */
class WorkbenchCardDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun <T> inspect(block: (List<SemanticsNode>) -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync {
            val nodes = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::collect)
            }
            fun visit(view: View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(block(nodes))
        }
        return result.get()
    }

    private fun node(nodes: List<SemanticsNode>, label: String): SemanticsNode = nodes.single {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
    }

    private fun ancestors(node: SemanticsNode): Sequence<SemanticsNode> = generateSequence(node) { it.parent }

    private fun awaitLayout(label: String) {
        val deadline = SystemClock.uptimeMillis() + 12_000
        while (!inspect { nodes -> nodes.any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true &&
                it.boundsInWindow.width > 0f && it.boundsInWindow.height > 0f
        } }) {
            check(SystemClock.uptimeMillis() < deadline) { "Miuix card content did not lay out: $label" }
            Thread.sleep(40)
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun callbacksDisabledStateAndRadioRoleSurviveBothThemes() {
        warmDeviceActivity()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (dark in listOf(false, true)) {
                val suffix = if (dark) "night" else "day"
                val clickable = "card-click-$suffix"
                val disabled = "card-disabled-$suffix"
                val radio = "card-radio-$suffix"
                val longPress = "card-long-$suffix"
                val clicked = AtomicInteger()
                val disabledClicks = AtomicInteger()
                val selected = AtomicInteger()
                val shortClicks = AtomicInteger()
                val longClicks = AtomicInteger()
                scenario.onActivity { activity -> activity.setContent {
                    CompositionLocalProvider(LocalWorkbenchDark provides dark) {
                        WorkbenchMiuixTheme(dark) {
                            Column {
                                WorkbenchCard(onClick = { clicked.incrementAndGet() }) { Text(clickable) }
                                WorkbenchCard(enabled = false, onClick = { disabledClicks.incrementAndGet() }) { Text(disabled) }
                                WorkbenchCard(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .semantics { role = Role.RadioButton; this.selected = true },
                                    onClick = { selected.incrementAndGet() }) { Text(radio) }
                                WorkbenchCard(onClick = { shortClicks.incrementAndGet() },
                                    onLongClick = { longClicks.incrementAndGet() }) { Text(longPress) }
                            }
                        }
                    }
                } }
                awaitLayout(longPress)
                inspect { nodes ->
                    val clickAction = ancestors(node(nodes, clickable)).firstNotNullOf {
                        it.config.getOrNull(SemanticsActions.OnClick)?.action
                    }
                    assertTrue("Enabled card has an actionable click", clickAction())
                    assertEquals(1, clicked.get())

                    val disabledAncestors = ancestors(node(nodes, disabled)).toList()
                    assertTrue("Disabled card exposes its state", disabledAncestors.any {
                        it.config.contains(SemanticsProperties.Disabled)
                    })
                    disabledAncestors.forEach { it.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() }
                    assertEquals("Disabled card cannot invoke its callback", 0, disabledClicks.get())

                    val radioAncestors = ancestors(node(nodes, radio)).toList()
                    val radioNode = radioAncestors.first { it.config.getOrNull(SemanticsProperties.Role) != null }
                    assertEquals("Card must preserve its external radio role", Role.RadioButton,
                        radioNode.config[SemanticsProperties.Role])
                    assertEquals(true, radioNode.config.getOrNull(SemanticsProperties.Selected))
                    // In the unmerged tree, the external role and the Miuix click action
                    // can belong to separate ancestors of the same visible card content.
                    val radioClick = radioAncestors.firstNotNullOf {
                        it.config.getOrNull(SemanticsActions.OnClick)?.action
                    }
                    assertTrue("Radio card retains its actionable click", radioClick())
                    assertEquals(1, selected.get())

                    val longAncestors = ancestors(node(nodes, longPress)).toList()
                    val longAction = longAncestors.firstNotNullOf {
                        it.config.getOrNull(SemanticsActions.OnLongClick)?.action
                    }
                    assertTrue("Long press remains independently actionable", longAction())
                    assertEquals(1, longClicks.get())
                    assertEquals("Long press must not also click", 0, shortClicks.get())
                    val shortAction = longAncestors.firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
                    assertTrue(shortAction())
                    assertEquals(1, shortClicks.get())
                    assertEquals("Click must not also long press", 1, longClicks.get())
                }
                instrumentation.waitForIdleSync()
            }
        }
    }
}
