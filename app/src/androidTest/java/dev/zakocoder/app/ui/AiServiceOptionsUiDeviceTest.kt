package dev.zakocoder.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Actual compatibility controls, synthetic memory-only state, no model/network/config access. */
@SdkSuppress(minSdkVersion = 30)
class AiServiceOptionsUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun <T> nodes(block: (List<SemanticsNode>) -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync {
            val found = mutableListOf<SemanticsNode>()
            fun collect(node: SemanticsNode) { found += node; node.children.forEach(::collect) }
            fun visit(view: android.view.View) {
                if (!view.isShown) return
                if (view is ViewRootForTest) collect(view.semanticsOwner.unmergedRootSemanticsNode)
                if (view is android.view.ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
            }
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            result.set(block(found))
        }
        return result.get()
    }
    private fun label(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    private fun matching(node: SemanticsNode, resource: Int) = label(node) == context.getString(resource) ||
        context.getString(resource) in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun has(resource: Int) = nodes { all -> all.any { matching(it, resource) } }
    private fun settle() { Thread.sleep(100); instrumentation.waitForIdleSync() }
    private fun click(resource: Int) {
        nodes { all ->
            var node = all.last { matching(it, resource) } as SemanticsNode?
            while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
            assertTrue("Click ${context.getString(resource)}", requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        settle()
    }
    private fun visible(resource: Int): Boolean = nodes { all -> all.any {
        matching(it, resource) && it.boundsInWindow.height >= it.size.height * .95f && it.boundsInWindow.width > 0
    } }
    private fun scrollTo(resource: Int) {
        repeat(30) {
            if (visible(resource)) return
            nodes { all ->
                var node = all.last { matching(it, resource) } as SemanticsNode?
                while (node != null && node.config.getOrNull(SemanticsActions.ScrollBy) == null) node = node.parent
                assertTrue("Scroll to ${context.getString(resource)}", requireNotNull(node).config[SemanticsActions.ScrollBy].action?.invoke(0f, 240f) == true)
            }
            settle()
        }
        assertTrue("Control visible at 360dp / 130%", visible(resource))
    }
    private fun screenshot(protocol: AiApiProtocol, dark: Boolean, preset: Boolean = false) {
        capture("service-options-${protocol.name}-${if (preset) "preset-" else ""}${if (dark) "night" else "day"}.png")
    }
    private fun capture(name: String) {
        // Popup dismissal is animated; capture the settled component, not its outgoing menu.
        Thread.sleep(350)
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, name)
                .outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    @Test(timeout = 120000) fun day360Font130() = render(false)
    @Test(timeout = 120000) fun night360Font130() = render(true)

    @Test(timeout = 30000) fun connectionAndToolChecksRemainSeparateAndThinkingIsNotClaimedVerified() {
        val state = mutableStateOf(AiModelsState(probe = AiProbe.Failed,
            probeProgress = AiModelProbeProgress(status = 200, textChars = 42)))
        val dark = mutableStateOf(false)
        warmDeviceActivity()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f), LocalWorkbenchDark provides dark.value) {
                    MaterialTheme(colorScheme = if (dark.value) WorkbenchDarkColors else WorkbenchColors) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.safeDrawingPadding().width(360.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                                AiModelProbeProgressContent(state.value)
                            }
                        }
                    }
                }
            } }
            for (night in listOf(false, true)) {
                scenario.onActivity { activity ->
                    androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                        isAppearanceLightStatusBars = !night
                        isAppearanceLightNavigationBars = !night
                    }
                    @Suppress("DEPRECATION")
                    activity.window.navigationBarColor = if (night) android.graphics.Color.rgb(15, 23, 42) else android.graphics.Color.WHITE
                }
                instrumentation.runOnMainSync { dark.value = night; state.value = state.value.copy(probe = AiProbe.Failed) }
                settle()
                assertTrue(has(R.string.ai_probe_connection_passed))
                assertTrue(has(R.string.ai_probe_tools_unverified))
                assertFalse(has(R.string.ai_probe_tools_passed))
                assertTrue(has(R.string.ai_probe_thinking_unverified))
                capture("service-probe-failed-${if (night) "night" else "day"}.png")
                instrumentation.runOnMainSync { state.value = state.value.copy(probe = AiProbe.Passed) }
                settle()
                assertTrue(has(R.string.ai_probe_tools_passed))
                assertFalse(has(R.string.ai_probe_tools_unverified))
                assertTrue(has(R.string.ai_probe_thinking_unverified))
                capture("service-probe-passed-${if (night) "night" else "day"}.png")
            }
        }
    }

    private fun render(dark: Boolean) {
        val protocol = mutableStateOf(AiApiProtocol.ChatCompletions)
        val value = mutableStateOf(AiServiceOptions(usePreset = true))
        val enabled = mutableStateOf(true)
        warmDeviceActivity()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
                @Suppress("DEPRECATION")
                activity.window.navigationBarColor = if (dark) android.graphics.Color.rgb(15, 23, 42) else android.graphics.Color.WHITE
                activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f), LocalWorkbenchDark provides dark) {
                    MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors) {
                        Surface(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize()) {
                            key(protocol.value) {
                                Column(Modifier.safeDrawingPadding().width(360.dp).verticalScroll(rememberScrollState())
                                    .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AiServiceOptionsSection(protocol.value, value.value, enabled.value, "https://api.deepseek.com/v1") { value.value = it }
                                }
                            }
                            }
                        }
                    }
                }
            } }
            for (current in AiApiProtocol.entries) {
                instrumentation.runOnMainSync { protocol.value = current; value.value = AiServiceOptions(usePreset = true); enabled.value = true }
                settle()
                assertTrue(has(R.string.ai_service_preset))
                assertFalse(has(R.string.ai_service_chat_thinking))
                assertFalse(has(R.string.ai_service_max_tokens))
                screenshot(current, dark, preset = true)
                click(R.string.ai_service_title)
                assertFalse(has(R.string.ai_service_streaming))
                assertTrue(has(R.string.ai_service_manual_hint))
                click(R.string.ai_service_preset)
                assertFalse(value.value.usePreset)
                assertTrue(has(R.string.ai_service_streaming))
                assertTrue(has(R.string.ai_service_sampling))
                assertTrue(has(R.string.ai_service_streaming_hint))
                assertEquals(current == AiApiProtocol.ChatCompletions, has(R.string.ai_service_chat_thinking))
                assertEquals(current == AiApiProtocol.Responses, has(R.string.ai_service_responses_cache))
                assertEquals(current == AiApiProtocol.Anthropic, has(R.string.ai_service_anthropic_cache))
                when (current) {
                    AiApiProtocol.ChatCompletions -> {
                        scrollTo(R.string.ai_service_thinking_object)
                        click(R.string.ai_service_thinking_object)
                        click(R.string.ai_service_enable_thinking)
                        assertEquals(AiChatThinkingFormat.EnableThinking, value.value.chatThinking)
                        scrollTo(R.string.ai_service_max_tokens)
                        click(R.string.ai_service_max_tokens)
                        click(R.string.ai_service_max_completion_tokens)
                        assertEquals(AiChatTokenLimit.MaxCompletionTokens, value.value.chatTokenLimit)
                        assertTrue(has(R.string.ai_service_max_completion_tokens_help))
                    }
                    AiApiProtocol.Responses -> {
                        scrollTo(R.string.ai_service_responses_cache)
                        click(R.string.ai_service_responses_cache)
                        assertTrue(value.value.responsesCacheKey)
                    }
                    AiApiProtocol.Anthropic -> {
                        scrollTo(R.string.ai_service_cache_disabled)
                        click(R.string.ai_service_cache_disabled)
                        click(R.string.ai_service_cache_one_hour)
                        assertEquals(AiAnthropicCache.OneHour, value.value.anthropicCache)
                    }
                    AiApiProtocol.Gemini -> {
                        click(R.string.ai_service_sampling)
                        assertFalse(value.value.samplingParameters)
                    }
                }
                screenshot(current, dark)
                instrumentation.runOnMainSync { enabled.value = false }
                settle()
                nodes { all ->
                    val toggles = all.filter { it.config.contains(SemanticsProperties.ToggleableState) }
                    assertTrue("Actual switches present", toggles.isNotEmpty())
                    assertTrue("Controls disabled while locked", toggles.all { it.config.contains(SemanticsProperties.Disabled) })
                }
            }
        }
    }
}
