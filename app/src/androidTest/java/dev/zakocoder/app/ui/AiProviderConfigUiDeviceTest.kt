package dev.zakocoder.app.ui

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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.R
import dev.zakocoder.app.AiDeviceTest
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.warmDeviceActivity
import dev.zakocoder.app.ai.*
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real editor interactions, persisted synthetic profiles, 360dp/130% and both themes. */
@SdkSuppress(minSdkVersion = 30)
class AiProviderConfigUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 180000) fun day360Font130() = render(false)
    @Test(timeout = 180000) fun night360Font130() = render(true)
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
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (!condition()) { check(SystemClock.uptimeMillis() < deadline) { label }; Thread.sleep(50) }
    }
    private fun settle() { Thread.sleep(350); instrumentation.waitForIdleSync() }
    private fun click(label: String) = nodes { all ->
        var node = all.last { text(it) == label || label in description(it) } as SemanticsNode?
        while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
        assertTrue("Click $label", requireNotNull(node).config[SemanticsActions.OnClick].action?.invoke() == true)
    }
    private fun has(label: String) = nodes { all -> all.any { text(it) == label } }
    private fun field(label: Int) = nodes { all ->
        all.last { context.getString(label) in description(it) && it.config.contains(SemanticsProperties.EditableText) }
            .config[SemanticsProperties.EditableText].text
    }
    private fun setField(label: Int, value: String) {
        nodes { all ->
            val node = all.last { context.getString(label) in description(it) && it.config.contains(SemanticsProperties.EditableText) }
            assertTrue(node.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true)
        }
        settle()
        assertEquals(value, field(label))
    }
    private fun scrollTo(label: Int, down: Boolean = true) {
        val wanted = context.getString(label)
        repeat(12) {
            val done = nodes { all -> all.any { text(it) == wanted && it.boundsInWindow.width > 0 &&
                it.boundsInWindow.height >= it.size.height * .95f } }
            if (done) return
            nodes { all ->
                var node = all.last { text(it) == wanted } as SemanticsNode?
                while (node != null && node.config.getOrNull(SemanticsActions.ScrollBy) == null) node = node.parent
                assertTrue("Scroll to $wanted", requireNotNull(node).config[SemanticsActions.ScrollBy].action?.invoke(0f, if (down) 1000f else -1000f) == true)
            }
            settle()
        }
        error("Not visible: $wanted")
    }
    private fun shot(dark: Boolean, name: String) {
        settle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, "ui-review/provider-config/${if (dark) "night" else "day"}-$name.png").apply {
                parentFile!!.mkdirs()
                outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } finally { bitmap.recycle() }
    }
    private fun checkKeyboard(scenario: ActivityScenario<MainActivity>, dark: Boolean) {
        nodes { all ->
            val prompt = all.last { context.getString(R.string.ai_provider_prompt_field) in description(it) &&
                it.config.contains(SemanticsProperties.EditableText) }
            assertTrue(prompt.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
        await("actual software keyboard") {
            var shown = false
            scenario.onActivity { activity -> shown = activity.window.decorView.rootWindowInsets
                ?.isVisible(android.view.WindowInsets.Type.ime()) == true }
            shown
        }
        settle()
        // Bring the complete field above the real IME, including at 130% font size.
        scrollTo(R.string.ai_provider_prompt_field)
        var imeTop = 0
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            imeTop = root.height - requireNotNull(root.rootWindowInsets).getInsets(android.view.WindowInsets.Type.ime()).bottom
        }
        nodes { all ->
            val prompt = all.last { context.getString(R.string.ai_provider_prompt_field) in description(it) &&
                it.config.contains(SemanticsProperties.EditableText) }
            assertTrue("Prompt remains above IME: ${prompt.boundsInWindow}, imeTop=$imeTop", prompt.boundsInWindow.bottom <= imeTop)
            assertTrue("Prompt is not clipped", prompt.boundsInWindow.height >= prompt.size.height * .95f)
            assertTrue("Save remains visible", all.any { text(it) == context.getString(R.string.editor_save) &&
                it.boundsInWindow.top >= 0 && it.boundsInWindow.bottom <= imeTop })
        }
        shot(dark, "prompt-keyboard")
        scenario.onActivity { activity ->
            val manager = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            manager.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        await("keyboard hidden") {
            var hidden = false
            scenario.onActivity { activity -> hidden = activity.window.decorView.rootWindowInsets
                ?.isVisible(android.view.WindowInsets.Type.ime()) != true }
            hidden
        }
        settle()
    }
    private fun render(dark: Boolean) {
        AiDeviceTest.Fixture(context).use { f ->
            f.config.saveProfile(AiModelDraft("legacy", "服务商配置验收", f.server.url("/v1").toString(), "gpt-6.1-sol", null,
                context = AiContextOptions(contextWindowTokens = 256000), systemPrompt = "请使用中文回答。"))
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = File(f.root, "application-files").apply { mkdirs() }
            }
            val ai = AtomicReference<AiController>()
            val closed = AtomicBoolean()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    ai.set(AiController(isolated, { File(f.root, "project").apply { mkdirs() } }, { _, _ -> false },
                        { _, _ -> error("No commands expected") }, {}, {}, f.transport, f.config, history = f.history))
                }
                var revision = 0
                val rendered = AtomicInteger()
                fun show() {
                    closed.set(false)
                    val profile = f.config.catalog().active!!
                    val generation = ++revision
                    scenario.onActivity { activity -> activity.setContent { Theme(dark) {
                        key(generation) {
                            AiModelEditor(ai.get(), profile) { closed.set(true) }
                            SideEffect { rendered.set(generation) }
                        }
                    } } }
                    await("editor ready") { rendered.get() == generation && !ai.get().models.state.value.loading &&
                        !ai.get().models.state.value.saving && has(context.getString(R.string.ai_provider_prompt_title)) }
                }
                try {
                    show()
                    scrollTo(R.string.ai_model)
                    assertEquals("gpt-6.1-sol", field(R.string.ai_model))
                    assertTrue(has("256K"))
                    setField(R.string.ai_model, "gpt-6.1-sol")
                    assertTrue(has("256K"))
                    setField(R.string.ai_model, "grok-4.7")
                    assertTrue(has("500K"))
                    setField(R.string.ai_model, "gpt-6.1-sol")
                    assertTrue(has("1.05M"))
                    shot(dark, "recommended-window")
                    scrollTo(R.string.ai_options_title)
                    click(context.getString(R.string.ai_options_title)); settle()
                    scrollTo(R.string.ai_options_context_tokens)
                    setField(R.string.ai_options_context_tokens, "64000")
                    scrollTo(R.string.ai_model, false)
                    assertTrue(has("64K"))
                    f.server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("{\"data\":[{\"id\":\"gpt-6.1-sol\"},{\"id\":\"deepseek-flash\"}]}"))
                    click(context.getString(R.string.ai_models_fetch))
                    await("model list") { has("deepseek-flash") }
                    click(context.getString(R.string.ai_models_choose_named, "gpt-6.1-sol")); settle()
                    assertTrue(has("64K"))
                    f.server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("{\"data\":[{\"id\":\"gpt-6.1-sol\"},{\"id\":\"deepseek-flash\"}]}"))
                    click(context.getString(R.string.ai_models_fetch))
                    await("model list again") { has("deepseek-flash") }
                    click(context.getString(R.string.ai_models_choose_named, "deepseek-flash")); settle()
                    assertTrue(has("1M"))
                    setField(R.string.ai_model, "gpt-6.1-sol")
                    assertTrue(has("1.05M"))
                    scrollTo(R.string.ai_provider_prompt_field)
                    val prompt = "请使用中文回答。\n修改前先检查现有实现。\n完成后列出验证结果。"
                    setField(R.string.ai_provider_prompt_field, prompt)
                    shot(dark, "system-prompt")
                    checkKeyboard(scenario, dark)
                    setField(R.string.ai_provider_prompt_field, "x".repeat(AiSystemPrompt.MAX_CHARS + 1))
                    assertTrue(has(context.getString(R.string.ai_provider_prompt_too_long)))
                    setField(R.string.ai_provider_prompt_field, prompt)
                    scrollTo(R.string.ai_options_context_tokens)
                    setField(R.string.ai_options_context_tokens, "64000")
                    click(context.getString(R.string.editor_save))
                    await("save closed") { closed.get() }
                    assertEquals(prompt, f.config.load().systemPrompt)
                    assertEquals(64000, f.config.load().context.contextWindowTokens)
                    show()
                    scrollTo(R.string.ai_provider_prompt_field)
                    assertEquals(prompt, field(R.string.ai_provider_prompt_field))
                    setField(R.string.ai_provider_prompt_field, "未保存的修改")
                    click(context.getString(R.string.ai_models_back)); settle()
                    assertTrue(has(context.getString(R.string.ai_models_discard_title)))
                    click(context.getString(R.string.ai_models_discard))
                    await("discard closed") { closed.get() }
                    assertEquals(prompt, f.config.load().systemPrompt)
                    show()
                    scrollTo(R.string.ai_provider_prompt_field)
                    setField(R.string.ai_provider_prompt_field, "")
                    click(context.getString(R.string.editor_save))
                    await("cleared saved") { closed.get() }
                    assertEquals("", f.config.load().systemPrompt)
                    assertEquals(64000, f.config.load().context.contextWindowTokens)
                } finally {
                    instrumentation.runOnMainSync { ai.get().stop(); ai.get().select(null) }
                }
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
}
