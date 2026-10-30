package dev.zakocoder.app

import android.content.ComponentName
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.AiEntry
import dev.zakocoder.app.ai.AiRole
import dev.zakocoder.app.ui.AiReasoningContent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Native live-section behavior in both themes; fixtures never read existing conversations. */
class AiLiveReasoningUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() {
        val component = ComponentName(context.packageName, MainActivity::class.java.name).flattenToString()
        require(component.matches(Regex("[A-Za-z0-9_./]+")))
        instrumentation.uiAutomation.executeShellCommand("am start -W -n $component").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
    }
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
    private fun has(label: String) = nodes { it.any { node -> text(node) == label } }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError(label)
            Thread.sleep(40)
        }
    }
    private fun click(label: String) {
        await("Control: $label") { has(label) }
        nodes { all ->
            var node = all.first { text(it) == label } as SemanticsNode?
            while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
            assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }
    @Test(timeout = 120000) fun liveDeltaIsExpandedAutoFoldsOnAnswerAndRemainsManuallyReadableInBothThemes() {
        listOf(false, true).forEach { dark ->
            val entry = mutableStateOf(AiEntry("live-$dark", role = AiRole.Assistant, reasoning = "first public fragment"))
            val streaming = mutableStateOf(true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Column(Modifier.widthIn(max = 360.dp).padding(16.dp)) {
                                AiReasoningContent(entry.value, streaming = streaming.value)
                            }
                        }
                    }
                } }
                await("Current reasoning starts expanded in dark=$dark") { has("first public fragment") }
                instrumentation.runOnMainSync { entry.value = entry.value.copy(reasoning = "first public fragment\nsecond public fragment") }
                await("Live fragment appears before completion in dark=$dark") { has(entry.value.reasoning) }
                instrumentation.runOnMainSync { streaming.value = false; entry.value = entry.value.copy(text = "final answer") }
                await("Answer collapses reasoning in dark=$dark") { !has(context.getString(R.string.ai_reasoning_copy)) }
                assertFalse(has(entry.value.reasoning))
                click(context.getString(R.string.ai_reasoning_title))
                await("Ended reasoning is manually readable in dark=$dark") { has(entry.value.reasoning) }
                instrumentation.runOnMainSync { entry.value = AiEntry("next-$dark", role = AiRole.Assistant, reasoning = "next round"); streaming.value = true }
                await("Next round opens independently in dark=$dark") { has("next round") }
                instrumentation.runOnMainSync { streaming.value = false }
                await("Reasoning-only finish collapses in dark=$dark") { !has("next round") }
            }
        }
    }
}
