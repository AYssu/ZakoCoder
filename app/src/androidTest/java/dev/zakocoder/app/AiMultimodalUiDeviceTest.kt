package dev.zakocoder.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Isolated native UI. No provider, user image, existing project, network or environment installation. */
class AiMultimodalUiDeviceTest {
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
    private fun has(label: String) = nodes { it.any { node -> text(node) == label } }
    private fun await(label: String, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 20000
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= until) throw AssertionError(label)
            Thread.sleep(70)
        }
    }
    private fun click(label: String) {
        await("Visible control: $label") { has(label) }
        nodes { all ->
            var control: SemanticsNode? = all.first { text(it) == label }
            while (control != null && control.config.getOrNull(SemanticsActions.OnClick) == null) control = control.parent
            assertTrue("Clickable control: $label", requireNotNull(control).config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }
    private fun screenshot(name: String, expectedDark: Boolean? = null) {
        val until = SystemClock.uptimeMillis() + 20000
        while (true) {
            instrumentation.waitForIdleSync()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            // This part of the fixture is blank Surface background, below the attachment rows.
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height * 3 / 4)
            val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
            val correctTheme = expectedDark == null || if (expectedDark) brightness < 100 else brightness > 180
            try {
                if (correctTheme) {
                    File(context.cacheDir, "ui-review/multimodal/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    return
                }
                if (SystemClock.uptimeMillis() >= until)
                    throw AssertionError("Screenshot $name did not render expected dark=$expectedDark background; brightness=$brightness")
            } finally { bitmap.recycle() }
            Thread.sleep(70)
        }
    }

    @Test(timeout = 90000) fun reasoningStartsCollapsedAndCopiesRetainedTextWithHonestTruncation() {
        val reasoning = "fixture reasoning line\n".repeat(610)
        val entry = AiEntry("reasoning-ui-fixture", role = AiRole.Assistant, reasoning = reasoning, reasoningTruncated = true)
        val title = context.getString(R.string.ai_reasoning_title)
        val copy = context.getString(R.string.ai_reasoning_copy)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Column(Modifier.padding(16.dp)) { AiReasoningContent(entry) }
                } }
            } }
            await("Collapsed reasoning heading") { has(title) }
            assertFalse("Copy is hidden while collapsed", has(copy))
            assertFalse(has(context.getString(R.string.ai_reasoning_provider_hint)))
            click(title)
            await("Provider explanation") { has(context.getString(R.string.ai_reasoning_provider_hint)) }
            assertTrue("Preview cap is disclosed", has(context.getString(R.string.ai_reasoning_preview_limited)))
            assertTrue("Retention cap is disclosed", has(context.getString(R.string.ai_reasoning_retention_limited)))
            assertTrue("Visible text is capped", has(reasoning.take(12000)))
            click(copy)
            instrumentation.runOnMainSync {
                assertEquals("Copy retains all stored text, not only the 12000-character preview", reasoning,
                    context.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
            }
            screenshot("reasoning-expanded")
            click(title)
            await("Collapsed again") { !has(copy) }
        }
    }

    @Test(timeout = 120000) fun permissionPresetsConfirmPersistAndPreserveProhibitions() = runBlocking {
        val token = "multimodal-permissions-${UUID.randomUUID()}"
        val own = File(context.cacheDir, token)
        val preferences = mutableSetOf<String>()
        val base = context
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(own, "files").apply { mkdirs() }
            override fun getCacheDir() = File(own, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(own, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$token-$name".also { preferences += it }, mode)
        }
        val workbench = withContext(Dispatchers.Main) { Workbench(isolated) }
        try {
            await("Fixture starts") { !workbench.state.value.loading }
            instrumentation.runOnMainSync { workbench.create("权限验收") }
            await("Fixture project created") { !workbench.state.value.busy && workbench.state.value.project != null }
            val project = requireNotNull(workbench.state.value.project)
            instrumentation.runOnMainSync { workbench.ai.select(project.id) }
            await("Fixture permissions loaded") { !workbench.ai.state.value.permissionsLoading }
            val custom = AiPolicy(overrides = AiPolicy().overrides + mapOf(AiTool.WriteFile to AiPermission.Deny, AiTool.RunCommand to AiPermission.Allow))
            instrumentation.runOnMainSync { workbench.ai.setPolicy(custom) }
            await("Custom policy saved") { !workbench.ai.state.value.permissionsSaving && workbench.ai.state.value.policy == custom }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme { AiPermissionsSheet(workbench, project.name) {} }
                } }
                val currentCustom = context.getString(R.string.ai_permission_preset_current, context.getString(R.string.ai_permission_preset_custom))
                await("Custom is labelled honestly") { has(currentCustom) }
                assertFalse("Advanced tools start collapsed", has(context.getString(R.string.ai_default_permission)))
                click(context.getString(R.string.ai_permission_preset_elevated))
                await("High permissions require confirmation") { has(context.getString(R.string.ai_permission_preset_warning, project.name)) }
                assertEquals(custom, workbench.ai.state.value.policy)
                click(context.getString(R.string.cancel))
                await("Cancel dismisses confirmation") { !has(context.getString(R.string.ai_permission_preset_warning, project.name)) }
                assertEquals(custom, workbench.ai.state.value.policy)
                click(context.getString(R.string.ai_permission_preset_elevated))
                click(context.getString(R.string.ai_auto_allow_confirm))
                await("Elevated saved") { !workbench.ai.state.value.permissionsSaving && workbench.ai.state.value.policy.default == AiPermission.Allow }
                val elevated = workbench.ai.state.value.policy
                assertEquals(AiPermission.Deny, elevated.permission(AiTool.WriteFile))
                assertEquals(AiPermission.Ask, elevated.permission(AiTool.UseMcp))
                assertEquals(elevated, withContext(Dispatchers.IO) { AiPermissionStore(isolated).load(project.id) })
                screenshot("permissions-elevated")
                click(context.getString(R.string.ai_permission_preset_normal))
                await("Normal saved") { !workbench.ai.state.value.permissionsSaving && workbench.ai.state.value.policy.default == AiPermission.Ask }
                assertEquals(AiPermission.Deny, workbench.ai.state.value.policy.permission(AiTool.WriteFile))
                assertEquals(AiPermission.Ask, workbench.ai.state.value.policy.permission(AiTool.RunCommand))
                click(context.getString(R.string.ai_permission_advanced_show))
                await("Advanced rules stay available") { has(context.getString(R.string.ai_default_permission)) }
                click(context.getString(R.string.ai_permission_advanced_hide))
                await("Advanced rules fold") { !has(context.getString(R.string.ai_default_permission)) }
            }
        } finally {
            withContext(NonCancellable) {
                instrumentation.runOnMainSync { workbench.stopAll() }
                delay(250)
                withContext(Dispatchers.IO) {
                    PrivateFiles.deleteTree(own)
                    preferences.forEach { base.deleteSharedPreferences(it) }
                }
            }
        }
    }

    @Test(timeout = 90000) fun attachmentsAndReasoningFitNarrowLargeTextInBothThemes() {
        val image = AiImageAttachment(UUID.randomUUID().toString(), "界面预览截图.jpg", "image/jpeg", 80, 320, 100)
        var dark by mutableStateOf(false)
        var removed by mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f), LocalWorkbenchDark provides dark) {
                        Surface(Modifier.fillMaxSize().safeDrawingPadding().semantics { stateDescription = if (dark) "fixture-theme-dark" else "fixture-theme-light" }) {
                            Column(Modifier.requiredWidth(360.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                AiReasoningContent(AiEntry("narrow-thinking", role = AiRole.Assistant, reasoning = "公开返回的思考内容"))
                                if (!removed) AiImageAttachments(listOf(image), load = {
                                    Bitmap.createBitmap(80, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(37, 99, 235)) }
                                }, onRemove = { removed = true })
                            }
                        }
                    }
                }
            } }
            listOf(false, true).forEach { theme ->
                instrumentation.runOnMainSync { dark = theme }
                val themeMarker = if (theme) "fixture-theme-dark" else "fixture-theme-light"
                await("Theme composition is current: $themeMarker") { nodes { all ->
                    all.any { it.config.getOrNull(SemanticsProperties.StateDescription) == themeMarker }
                } }
                await("Fixture attachment visible") { has(image.displayName) }
                val description = context.getString(R.string.ai_images_remove, image.displayName)
                await("Remove is accessible") { nodes { all -> all.any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true } } }
                screenshot(if (theme) "narrow-large-dark" else "narrow-large-light", expectedDark = theme)
            }
            nodes { all ->
                val description = context.getString(R.string.ai_images_remove, image.displayName)
                var control: SemanticsNode? = all.first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }
                while (control != null && control.config.getOrNull(SemanticsActions.OnClick) == null) control = control.parent
                assertTrue(requireNotNull(control).config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            await("Attachment removal updates the draft") { !has(image.displayName) }
        }
    }

    @Test(timeout = 90000) fun imageOnlyMessageDoesNotExposeEmptyCopyBubble() {
        val image = AiImageAttachment(UUID.randomUUID().toString(), "image-only-fixture.jpg", "image/jpeg", 80, 320, 100)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Column(Modifier.padding(16.dp)) {
                        AiMessage(AiEntry("image-only-message", role = AiRole.User, turn = 1, images = listOf(image)),
                            projectId = "fixture", onProjectPath = {}, onLink = {}, imageLoader = {
                                Bitmap.createBitmap(80, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
                            })
                    }
                } }
            } }
            await("Sent image metadata remains visible") { has(image.displayName) }
            assertFalse("Image-only messages must not create an empty clickable text-copy bubble", nodes { all ->
                all.any { it.config.getOrNull(SemanticsActions.OnClick) != null }
            })
            assertFalse(has(context.getString(R.string.ai_copy_user)))
        }
    }

}
