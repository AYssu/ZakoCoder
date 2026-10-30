package dev.zakocoder.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.AiDeviceTest
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.warmDeviceActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Actual model editor and protocol sheets with isolated synthetic configuration; no API calls. */
@SdkSuppress(minSdkVersion = 30)
class AiProtocolUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test fun day360Font130() = render(false)
    @Test fun night360Font130() = render(true)

    @Test fun protocolCompatibilityMatchesExplicitCodecRestrictions() {
        fun supported(protocol: AiApiProtocol, parameters: AiParameters) = protocolParametersSupported(protocol, parameters)
        assertTrue(AiApiProtocol.entries.all { supported(it, AiParameters()) })
        assertFalse(supported(AiApiProtocol.Responses, AiParameters(seed = 1)))
        assertFalse(supported(AiApiProtocol.Responses, AiParameters(thinking = AiThinking.Disabled, reasoningEffort = AiReasoningEffort.High)))
        assertFalse(supported(AiApiProtocol.Anthropic, AiParameters(temperature = 1.1)))
        assertFalse(supported(AiApiProtocol.Anthropic, AiParameters(thinking = AiThinking.Enabled, topP = 0.9)))
        assertFalse(supported(AiApiProtocol.Anthropic, AiParameters(thinking = AiThinking.Enabled, maxTokens = 1024)))
        assertTrue(supported(AiApiProtocol.Anthropic, AiParameters(thinking = AiThinking.Enabled)))
        assertTrue(supported(AiApiProtocol.Anthropic, AiParameters(reasoningEffort = AiReasoningEffort.Max)))
        assertFalse(supported(AiApiProtocol.Gemini, AiParameters(reasoningEffort = AiReasoningEffort.Max)))
        assertFalse(supported(AiApiProtocol.Gemini, AiParameters(thinking = AiThinking.Disabled, reasoningEffort = AiReasoningEffort.Low)))
        assertEquals(AiProviderPresets.all.size, AiProviderPresets.all.map { it.id }.distinct().size)
        AiProviderPresets.all.forEach { assertEquals(it.endpoint, AiConfigStore.endpoint(it.endpoint)) }
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
    private fun description(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!predicate()) {
            check(SystemClock.uptimeMillis() < deadline) { message }; Thread.sleep(50)
        }
    }
    private fun click(label: String) = nodes { all ->
        var node = all.last { text(it) == label || label in description(it) } as SemanticsNode?
        while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
        assertTrue(requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
    }
    private fun field(label: Int): String = nodes { all ->
        all.last { context.getString(label) in description(it) && it.config.contains(SemanticsProperties.EditableText) }
            .config[SemanticsProperties.EditableText].text
    }
    private fun visible(label: String) = nodes { all -> all.any {
        text(it) == label && it.boundsInWindow.width > 0 && it.boundsInWindow.height >= it.size.height * .95f
    } }
    private fun settle() { Thread.sleep(650); instrumentation.waitForIdleSync() }
    private fun scrollTo(label: String, towardBottom: Boolean) {
        repeat(8) {
            if (visible(label)) return
            nodes { all ->
                var node = all.last { text(it) == label } as SemanticsNode?
                while (node != null && node.config.getOrNull(SemanticsActions.ScrollBy) == null) node = node.parent
                assertTrue(requireNotNull(node).config[SemanticsActions.ScrollBy].action?.invoke(0f,
                    if (towardBottom) 1400f else -1400f) == true)
            }
            settle()
        }
        assertTrue("Requested provider is visible after scrolling", visible(label))
    }
    private fun Context.isolated(root: File): Context = object : ContextWrapper(this) {
        override fun getFilesDir(): File = File(root, "application-files").apply { mkdirs() }
    }
    private fun render(dark: Boolean) {
        val base = "https://api.example.invalid/v1"
        val model = "review-model"
        AiDeviceTest.Fixture(context).use { fixture ->
            val isolated = context.isolated(fixture.root)
            val ai = AtomicReference<AiController>()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val controller = AiController(isolated, { File(fixture.root, "project") }, { _, _ -> false },
                        { _, _ -> error("No process may execute in this fixture") }, {}, {}, fixture.transport, fixture.config,
                        permissionStore = AiPermissionStore(isolated, "protocol-ui-permissions"), history = fixture.history)
                    ai.set(controller)
                    activity.setContent { Theme(dark) {
                        AiModelEditor(controller, AiProfile("legacy", "Review connection", base, model, false, AiParameters())) {}
                    } }
                }
                await("actual editor ready") { !ai.get().models.state.value.loading && nodes { all -> all.any { text(it).contains(base + "/chat/completions") } } }
                AiApiProtocol.entries.forEach { protocol ->
                    click(context.getString(R.string.ai_protocol_choose))
                    await("protocol picker opened") { nodes { all -> all.count { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton } == 4 } }
                    if (protocol == AiApiProtocol.ChatCompletions) capture(dark, "protocol-picker") {
                        nodes { all -> assertEquals(4, all.count { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                            it.boundsInWindow.width > 0 && it.boundsInWindow.height >= it.size.height * .95f }) }
                    }
                    click(context.getString(protocolLabel(protocol)))
                    val expected = AiProviderRouting.requestAddress(base, protocol, model)
                    await("selected protocol updates real request address") { nodes { all -> all.any { text(it).contains(expected) } } }
                    assertEquals(base, field(R.string.ai_endpoint)); assertEquals(model, field(R.string.ai_model))
                    capture(dark, "editor-${protocol.name.lowercase()}")
                }
                click(context.getString(R.string.ai_provider_choose))
                await("provider presets opened") { nodes { all -> all.any { text(it) == context.getString(R.string.ai_provider_openai_responses) } } }
                capture(dark, "provider-presets") {
                    assertTrue(visible(context.getString(R.string.ai_provider_openai_responses)))
                    assertTrue(visible(context.getString(R.string.ai_provider_anthropic)))
                }
                scrollTo(context.getString(R.string.ai_provider_xai), towardBottom = true)
                capture(dark, "provider-presets-bottom") {
                    assertTrue(visible(context.getString(R.string.ai_provider_xai)))
                }
                scrollTo(context.getString(R.string.ai_provider_openai_responses), towardBottom = false)
                click(context.getString(R.string.ai_provider_openai_responses))
                await("preset applies address and protocol") { field(R.string.ai_endpoint) == "https://api.openai.com/v1" && nodes { all -> all.any { text(it).contains("https://api.openai.com/v1/responses") } } }
                assertEquals(model, field(R.string.ai_model))
                nodes { all -> assertTrue(all.any { it.config.contains(SemanticsProperties.Password) &&
                    it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" }) }
                assertEquals(0, fixture.server.requestCount)
                capture(dark, "preset-selected")
                scenario.onActivity { activity -> activity.setContent { Theme(dark) {
                    Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(20.dp)) {
                        AiProtocolOptions(AiApiProtocol.Gemini, false) { error("Locked field changed") }
                    } }
                } } }
                await("locked protocol rendered") { nodes { all -> all.any { context.getString(R.string.ai_protocol_choose) in description(it) && it.config.contains(SemanticsProperties.Disabled) } } }
                capture(dark, "task-locked")
                scenario.onActivity { activity -> activity.setContent { Theme(dark) {
                    AiModelsPage(ai.get(), close = {})
                } } }
                await("actual model catalog shows model and protocol") {
                    !ai.get().models.state.value.loading && nodes { all ->
                        all.any { text(it) == "fixture-model" && it.boundsInWindow.width > 0 && it.boundsInWindow.height > 0 } &&
                            all.any { text(it) == context.getString(R.string.ai_protocol_chat) && it.boundsInWindow.width > 0 && it.boundsInWindow.height > 0 }
                    }
                }
                capture(dark, "model-catalog")
                instrumentation.runOnMainSync { ai.get().stop(); ai.get().select(null) }
            }
        }
    }
    @Composable private fun Theme(dark: Boolean, content: @Composable () -> Unit) {
        val current = LocalConfiguration.current
        val width = LocalWindowInfo.current.containerSize.width
        val configuration = remember(current, dark) { Configuration(current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        } }
        CompositionLocalProvider(LocalConfiguration provides configuration, LocalWorkbenchDark provides dark,
            LocalDensity provides Density(width.coerceAtLeast(1) / 360f, 1.3f)) {
            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), content = content)
            }
        }
    }
    private fun capture(dark: Boolean, name: String, verify: () -> Unit = {}) {
        settle()
        verify()
        val root = File(context.cacheDir, "ui-review/model-protocol").apply { mkdirs() }
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(root, "${if (dark) "night" else "day"}-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { image.recycle() }
    }
}
