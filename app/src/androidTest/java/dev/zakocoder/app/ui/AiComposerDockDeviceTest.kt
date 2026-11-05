package dev.zakocoder.app.ui

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Explicit local component fixture: no account, model request, project or saved conversation. */
class AiComposerDockDeviceTest {
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
    private fun await(label: String, timeout: Long = 15000, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeout
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= until) throw AssertionError(label)
            Thread.sleep(40)
        }
    }
    private fun matching(node: SemanticsNode, label: String): Boolean =
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
    private fun click(label: String) {
        await("Reachable control: $label") { nodes { all -> all.any { matching(it, label) } } }
        nodes { all ->
            var target: SemanticsNode? = all.first { matching(it, label) }
            while (target != null && target.config.getOrNull(SemanticsActions.OnClick) == null) target = target.parent
            assertTrue("Enabled control: $label", requireNotNull(target).config.getOrNull(SemanticsProperties.Disabled) == null)
            assertTrue("Click: $label", target.config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }
    private fun edit(value: String) {
        nodes { all ->
            val node = all.first { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(node.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true)
        }
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(350)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "composer-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }
    @Test(timeout = 180000) fun callbacksDraftStopAndKeyboardInLightDarkNarrowLargeText() {
        val component = ComponentName(context.packageName, MainActivity::class.java.name).flattenToString()
        require(component.matches(Regex("[A-Za-z0-9_./]+")))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n $component")).use { it.readBytes() }
        val dark = mutableStateOf(false)
        val narrow = mutableStateOf(false)
        val largeText = mutableStateOf(false)
        val draft = mutableStateOf("检查当前项目并修复构建问题")
        val running = mutableStateOf(false)
        val attached = AtomicInteger()
        val models = AtomicInteger()
        val permissions = AtomicInteger()
        val sent = AtomicInteger()
        val stopped = AtomicInteger()
        val profile = AiProfile("composer-fixture", "本地组件示意", "https://example.invalid", "模型示意 · 长名称布局检查", false,
            AiParameters(reasoningEffort = AiReasoningEffort.High), AiContextOptions(contextWindowTokens = 1048576))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalWorkbenchDark provides dark.value,
                        LocalDensity provides Density(density.density, if (largeText.value) 1.3f else density.fontScale)) {
                        MaterialTheme(colorScheme = if (dark.value) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                            Surface(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                                    Column(Modifier.fillMaxHeight().widthIn(max = if (narrow.value) 320.dp else 420.dp)
                                        .fillMaxWidth().imePadding().padding(12.dp)) {
                                        Text("组件测试示意 · 不含真实项目数据", style = MaterialTheme.typography.labelSmall)
                                        Spacer(Modifier.height(16.dp))
                                        Text("检查项目并整理修改", style = MaterialTheme.typography.titleMedium)
                                        AiReasoningContent(AiEntry("composer-reasoning", role = AiRole.Assistant,
                                            reasoning = "先核对项目规则，再执行受影响的验证。"))
                                        AiToolCard(AiToolEvent("composer-tool", "fixture", AiTool.ReadFile, "read_file", "{}",
                                            "核对当前项目规则", "AGENTS.md", status = AiToolStatus.Succeeded,
                                            started = 1000, finished = 1120))
                                        Text("已读取项目规则，可以开始修改。", style = MaterialTheme.typography.bodyMedium)
                                        Spacer(Modifier.weight(1f))
                                        AiComposerDock(draft.value, { draft.value = it }, true, false, true,
                                            running.value, true, { attached.incrementAndGet() }, {
                                                if (running.value) { stopped.incrementAndGet(); running.value = false }
                                                else { sent.incrementAndGet(); running.value = true }
                                            }, configuration = {
                                                TextButton(onClick = { permissions.incrementAndGet() },
                                                    contentPadding = PaddingValues(0.dp),
                                                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                                                        contentDescription = context.getString(R.string.ai_permissions)
                                                        stateDescription = context.getString(AiPermission.Ask.label)
                                                    }) {
                                                    Surface(shape = androidx.compose.foundation.shape.CircleShape,
                                                        color = MaterialTheme.colorScheme.surface.copy(alpha = .35f),
                                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                                        Row(Modifier.heightIn(min = 28.dp).padding(horizontal = 6.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                            WorkbenchIcon(Glyph.Shield, Modifier.size(14.dp),
                                                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                            if (!narrow.value && !largeText.value) Text(
                                                                context.getString(R.string.ai_permission_short_ask), fontSize = 10.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        }
                                                    }
                                                }
                                                AiComposerModelButton(profile, { models.incrementAndGet() }, Modifier.weight(1f))
                                            })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            await("Composer mounted") { nodes { all -> all.any { matching(it, context.getString(R.string.ai_send)) } } }
            listOf(false, true).forEach { theme ->
                instrumentation.runOnMainSync { dark.value = theme }
                click(context.getString(R.string.ai_context_attach))
                click(context.getString(R.string.ai_switch_model))
                click(context.getString(R.string.ai_permissions))
                assertEquals("Configuration leaves draft intact", "检查当前项目并修复构建问题", draft.value)
                capture(if (theme) "dark" else "light")
            }
            nodes { all ->
                val model = all.first { matching(it, context.getString(R.string.ai_switch_model)) }
                val permission = all.first { matching(it, context.getString(R.string.ai_permissions)) }
                val send = all.first { matching(it, context.getString(R.string.ai_send)) }
                assertTrue("Permission precedes model", permission.boundsInWindow.center.x < model.boundsInWindow.center.x)
                assertTrue("Model precedes send", model.boundsInWindow.center.x < send.boundsInWindow.center.x)
                assertTrue("Model summary stays accessible", model.config.getOrNull(SemanticsProperties.StateDescription)
                    ?.contains(profile.model) == true)
            }
            assertEquals(2, attached.get()); assertEquals(2, models.get()); assertEquals(2, permissions.get())
            edit("验证发送与停止时保留草稿")
            click(context.getString(R.string.ai_send))
            await("Stop replaces send") { running.value }
            assertEquals(1, sent.get())
            click(context.getString(R.string.ai_stop))
            assertEquals(1, stopped.get())
            assertEquals("验证发送与停止时保留草稿", draft.value)
            instrumentation.runOnMainSync { narrow.value = true; largeText.value = true }
            capture("dark-narrow-130")
            // Request a real device IME; screenshots do not simulate a keyboard with an empty box.
            nodes { all ->
                val field = all.first { it.config.getOrNull(SemanticsActions.SetText) != null }
                assertTrue(field.config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() == true)
            }
            scenario.onActivity { activity ->
                val view = requireNotNull(activity.currentFocus)
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
            await("Real keyboard visible", 10000) {
                val visible = AtomicReference(false)
                scenario.onActivity { visible.set(ViewCompat.getRootWindowInsets(it.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true) }
                visible.get()
            }
            capture("dark-narrow-130-keyboard")
            nodes { all ->
                val icon = all.first { matching(it, context.getString(R.string.ai_send)) }
                val root = generateSequence(icon) { it.parent }.last()
                assertTrue("Send remains inside visible root with IME", icon.boundsInRoot.bottom <= root.boundsInRoot.bottom)
                assertTrue("Send has visible bounds", icon.boundsInRoot.width > 0 && icon.boundsInRoot.height > 0)
            }
            val sendBounds = nodes { all -> all.first { matching(it, context.getString(R.string.ai_send)) }.boundsInWindow }
            val downTime = SystemClock.uptimeMillis()
            listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
                val event = android.view.MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    sendBounds.center.x, sendBounds.center.y, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            await("Keyboard does not block physical send tap") { sent.get() == 2 }
            scenario.onActivity { activity ->
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
        }
    }
}
