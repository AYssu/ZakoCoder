package dev.zakocoder.app

import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.ui.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** PR illustrations: isolated native app, generated image and local TLS test service only. */
class AiReviewScreenshotsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var screenshotDark = false
    @Before fun foreground() {
        val component = android.content.ComponentName(context.packageName, MainActivity::class.java.name).flattenToString()
        // Both names are fixed Android package/class identifiers, never user or model text.
        require(component.matches(Regex("[A-Za-z0-9_./]+")))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n $component"
        )).use { stream ->
            val buffer = ByteArray(4096)
            while (stream.read(buffer) >= 0) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
            }
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
    private fun matches(node: SemanticsNode, label: String) = text(node) == label || node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    private fun has(label: String) = nodes { all -> all.any { matches(it, label) } }
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(20000) { while (!predicate()) delay(70) } }
        catch (failure: TimeoutCancellationException) { diagnostics(label); throw AssertionError(label, failure) }
    }
    private suspend fun click(label: String) {
        reveal(label)
        nodes { all ->
            var control: SemanticsNode? = all.first { matches(it, label) }
            while (control != null && control.config.getOrNull(SemanticsActions.OnClick) == null) control = control.parent
            assertTrue("Clickable control: $label", requireNotNull(control).config[SemanticsActions.OnClick].action?.invoke() == true)
        }
    }
    private suspend fun screenshot(scenario: ActivityScenario<MainActivity>, name: String, marker: String) {
        reveal(marker)
        val frames = CountDownLatch(1)
        scenario.onActivity { activity -> activity.window.decorView.postOnAnimation {
            activity.window.decorView.postOnAnimation { frames.countDown() }
        } }
        assertTrue("Two rendered frames before $name", frames.await(5, TimeUnit.SECONDS))
        delay(300) // Allow native sheet/alert transitions to settle after their first frame.
        assertTrue("Expected page remains visible: $name", has(marker))
        val insets = AtomicReference<android.graphics.Insets>()
        scenario.onActivity { activity -> insets.set(activity.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.systemBars())) }
        val raw = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val bars = requireNotNull(insets.get())
        val bitmap = Bitmap.createBitmap(raw, bars.left, bars.top, raw.width - bars.left - bars.right, raw.height - bars.top - bars.bottom)
        val themedName = "${if (screenshotDark) "dark" else "light"}-$name"
        try {
            // Left edge above the cropped navigation inset belongs to the app/sheet surface,
            // away from text, thumbnails, controls and the OS status/navigation bars.
            val pixel = bitmap.getPixel(8.coerceAtMost(bitmap.width - 1), (bitmap.height - 16).coerceAtLeast(0))
            val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
            assertTrue("Rendered theme for $themedName: brightness=$brightness",
                if (screenshotDark) brightness < 100 else brightness > 80)
            withContext(Dispatchers.IO) {
            File(context.cacheDir, "ui-review/pr23").mkdirs()
            File(context.cacheDir, "ui-review/pr23/$themedName.insets.json").writeText(JSONObject().put("left", bars.left).put("top", bars.top)
                .put("right", bars.right).put("bottom", bars.bottom).put("rawWidth", raw.width).put("rawHeight", raw.height).put("systemBarsCropped", true).put("darkMode", screenshotDark).toString())
            File(context.cacheDir, "ui-review/pr23/$themedName.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } } finally { if (bitmap !== raw) bitmap.recycle(); raw.recycle() }
    }

    private suspend fun diagnostics(reason: String) {
        val dump = nodes { all -> buildString {
            appendLine(reason)
            all.take(250).forEach { node ->
                val label = text(node).take(160)
                val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty().take(160)
                val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                if (label.isNotEmpty() || description.isNotEmpty() || range != null) {
                    val y = runCatching { node.layoutInfo.coordinates.localToWindow(Offset.Zero).y }.getOrNull()
                    appendLine("text=$label; description=$description; y=$y; scroll=${range?.value?.invoke()}/${range?.maxValue?.invoke()}")
                }
            }
        } }
        withContext(Dispatchers.IO) {
            File(context.cacheDir, "ui-review/pr23/failure-semantics.txt").apply { parentFile!!.mkdirs() }.writeText(dump)
        }
    }

    /** Handles lazy items not yet composed and form fields clipped by an owning scroll container. */
    private suspend fun reveal(label: String) {
        repeat(60) { attempt ->
            val settled = nodes { all ->
                val height = if (android.os.Build.VERSION.SDK_INT >= 30)
                    context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds.height().toFloat()
                else android.content.res.Resources.getSystem().displayMetrics.heightPixels.toFloat()
                val node = all.firstOrNull { matches(it, label) }
                if (node != null) {
                    val y = node.layoutInfo.coordinates.localToWindow(Offset.Zero).y
                    val bottom = y + node.size.height
                    val visible = y >= 0f && bottom <= height - 24f && node.boundsInWindow.height > 0f
                    var parent: SemanticsNode? = node
                    while (parent != null && (parent.config.getOrNull(SemanticsActions.ScrollBy) == null ||
                        (parent.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f) <= 0f)) parent = parent.parent
                    val range = parent?.config?.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                    if (visible && (parent == null || y <= height * .60f ||
                        (range != null && range.value() >= range.maxValue() - 1f))) return@nodes true
                    if (parent != null) {
                        parent.config[SemanticsActions.ScrollBy].action?.invoke(0f, (y - height * .28f).coerceIn(-600f, 600f))
                    }
                } else {
                    // Auto-follow may initially compose only the footer. Go to the first lazy item,
                    // then scan downward so absent content cannot pass as a valid screenshot.
                    val lazy = all.firstOrNull { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }
                    if (lazy != null) {
                        if (attempt % 16 == 0) lazy.config[SemanticsActions.ScrollToIndex].action?.invoke(0)
                        else lazy.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, height * .30f)
                    }
                }
                false
            }
            if (settled) return
            delay(150)
        }
        diagnostics("Could not reveal $label")
        throw AssertionError("Could not scroll $label into screenshot viewport; see failure-semantics.txt")
    }

    /** Every 1024-byte block contains a complete SSE event; comments pad only the wire chunks.
     * MockWebServer sends the first block now and each later block after five seconds, so the
     * screenshots must observe genuine transport callbacks rather than a fabricated active state.
     */
    private fun enqueuePaced(server: MockWebServer, groups: List<List<JSONObject>>) {
        val body = buildString {
            groups.forEachIndexed { index, events ->
                val eventText = buildString {
                    events.forEach { append("data: $it\n\n") }
                    if (index == groups.lastIndex) append("data: [DONE]\n\n")
                }
                val remaining = 1024 - eventText.toByteArray(Charsets.UTF_8).size
                require(remaining >= 3) { "Fixture SSE event must fit one wire block" }
                append(eventText)
                append(":").append(" ".repeat(remaining - 3)).append("\n\n")
            }
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(body).throttleBody(1024, 5, TimeUnit.SECONDS))
    }

    private fun delta(fields: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", fields)))
    private fun finished(reason: String = "stop") = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", JSONObject()).put("finish_reason", reason)))

    private fun generatedImage(root: File): AiImageAttachment {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val bytes = try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(245, 247, 250))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.rgb(37, 99, 235)
            canvas.drawRoundRect(32f, 24f, 608f, 108f, 16f, 16f, paint)
            paint.color = Color.WHITE; paint.textSize = 30f
            canvas.drawText("UI REVIEW / LOCAL FIXTURE", 56f, 76f, paint)
            paint.color = Color.rgb(226, 232, 240)
            canvas.drawRoundRect(32f, 132f, 608f, 304f, 16f, 16f, paint)
            paint.color = Color.rgb(51, 65, 85); paint.textSize = 26f
            canvas.drawText("PROJECT  /  EDIT  /  PREVIEW", 56f, 198f, paint)
            paint.textSize = 22f
            canvas.drawText("Generated test image - no user content", 56f, 254f, paint)
            ByteArrayOutputStream().use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)); output.toByteArray() }
        } finally { bitmap.recycle() }
        val image = AiImageAttachment(UUID.randomUUID().toString(), "界面布局参考（测试生成）.jpg", "image/jpeg", 640, 360, bytes.size)
        File(root, "ai-images-v1/${image.id}.jpg").apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        return image
    }

    @Test(timeout = 240000) fun completeAppConversationAttachmentsAndPermissionScreenshotsLight() = runReview(false)

    @Test(timeout = 240000) fun completeAppConversationAttachmentsAndPermissionScreenshotsDark() = runReview(true)

    @Test(timeout = 90000) fun localDirectoryPermissionScreenshotLight() = permissionScreenshot(false)
    @Test(timeout = 90000) fun localDirectoryPermissionScreenshotDark() = permissionScreenshot(true)

    private fun permissionScreenshot(dark: Boolean) = runBlocking {
        org.junit.Assume.assumeTrue("Permission-primer fixture requires file access to be disabled", android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager())
        screenshotDark = dark
        val own = File(context.cacheDir, "permission-review-${UUID.randomUUID()}")
        val base = context
        val token = own.name
        val preferences = mutableSetOf<String>()
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(own, "files").apply { mkdirs() }
            override fun getCacheDir() = File(own, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(own, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$token-$name".also { preferences += it }, mode)
        }
        val wb = withContext(Dispatchers.Main) { Workbench(isolated) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val current = LocalConfiguration.current
                    val themed = remember(current, dark) { Configuration(current).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    } }
                    CompositionLocalProvider(LocalConfiguration provides themed, LocalWorkbenchDark provides dark) {
                        ZakoCoderApp(wb)
                        MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                            LocalProjectBindingSheet(true, {}, { error("Permission primer never binds a directory") })
                        }
                    }
                } }
                screenshot(scenario, "07b-local-directory-permission", context.getString(R.string.local_browser_grant))
                assertTrue(has(context.getString(R.string.local_browser_permission_description)))
            }
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { wb.stopAll() }
                withContext(Dispatchers.IO) { PrivateFiles.deleteTree(own); preferences.forEach { base.deleteSharedPreferences(it) } }
            }
        }
    }

    private fun runReview(dark: Boolean) = runBlocking {
        screenshotDark = dark
        val token = "pr-review-${UUID.randomUUID()}"
        val own = File(context.cacheDir, token)
        val preferences = mutableSetOf<String>()
        val base = context
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(own, "files").apply { mkdirs() }
            override fun getCacheDir() = File(own, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(own, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$token-$name".also { preferences += it }, mode)
        }
        var workbench: Workbench? = null
        var sharedFixture: File? = null
        try {
            withContext(Dispatchers.IO) {
                // Test-owned UUID leaf only. No existing storage directory is enumerated or removed.
                val leaf = File(android.os.Environment.getExternalStorageDirectory(), "ZakoCoder-review-${UUID.randomUUID().toString().take(8)}")
                check(leaf.mkdir()) { "Cannot create isolated shared-storage screenshot fixture; grant access only to the review package" }
                sharedFixture = leaf
                listOf("android-app", "scripts", "web-preview").forEach { child -> check(File(leaf, child).mkdir()) }
                File(leaf, "README.md").writeText("# Local UI fixture\nGenerated solely for screenshots; not a user project.\n")
            }
            AiDeviceTest.Fixture(isolated, timeoutSeconds = 90).use { fixture ->
                // Pace outbound screenshot events, never the much larger system/tool upload.
                fixture.server.dispatcher = object : okhttp3.mockwebserver.QueueDispatcher() {
                    override fun peek() = MockResponse()
                }
                withContext(Dispatchers.IO) {
                    val active = requireNotNull(fixture.config.catalog().active)
                    fixture.config.saveProfile(AiModelDraft(active.id, "界面验收（本地测试）", active.endpoint, "本地测试模型", null))
                }
                val image = withContext(Dispatchers.IO) { generatedImage(isolated.noBackupFilesDir) }
                val prompt = "参考这张布局图，说明移动端页面应该怎么调整。"
                val reasoning = "先核对布局层次和操作位置，再检查阅读顺序。本地测试服务公开返回，仅用于界面验收。"
                val answer = "建议：顶部放项目身份，中间保留内容，底部提供模型与权限入口。\n\n本地测试服务回复；未修改文件或执行构建。"
                val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("reasoning_content", reasoning).put("content", answer))))
                val finish = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", "stop")))
                fixture.server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: $event\n\ndata: $finish\n\ndata: [DONE]\n\n"))
                var record: AiRequestLog? = null
                val started = System.currentTimeMillis()
                val reply = fixture.transport.request(withContext(Dispatchers.IO) { fixture.config.load() },
                    JSONArray().put(imageMessage(prompt, listOf(image))), JSONArray(), imageData = AiImageStore(isolated)::dataUrl,
                    observe = { record = it }) { }
                assertEquals(reasoning, reply.reasoningContent)
                val wb = withContext(Dispatchers.Main) { Workbench(isolated, aiTransport = fixture.transport, aiConfig = fixture.config, aiHistory = fixture.history) }
                workbench = wb
                await("Isolated workbench starts") { !wb.state.value.loading }
                withContext(Dispatchers.Main) { wb.create("隔离验收 · 移动端项目") }
                await("Private fixture project") { !wb.state.value.busy && wb.state.value.project != null }
                val project = requireNotNull(wb.state.value.project)
                withContext(Dispatchers.IO) {
                    File(wb.projects.directory(project.id), "README.md").writeText("# 隔离界面验收\n\n仅用于本地测试服务截图，不包含用户项目。\n")
                    fixture.history.save(project.id, AiState(turn = 1, phase = AiPhase.Completed,
                        timeline = listOf(AiEntry("pr-user", prompt, role = AiRole.User, turn = 1, images = listOf(image)),
                            AiEntry("pr-assistant", reply.text, role = AiRole.Assistant, turn = 1, reasoning = reply.reasoningContent.orEmpty())),
                        contextTurns = listOf(JSONArray().put(imageMessage(prompt, listOf(image))).put(reply.asAssistantMessage()).toString()),
                        requests = listOf(requireNotNull(record).copy(turn = 1, round = 1)),
                        turnTimings = listOf(AiTurnTiming(1, started, System.currentTimeMillis()))))
                }
                withContext(Dispatchers.Main) { wb.ai.select(project.id) }
                await("Local test history loaded") { !wb.ai.state.value.permissionsLoading && wb.ai.state.value.timeline.size == 2 }
                assertEquals(reasoning, wb.ai.state.value.timeline.last().reasoning)
                await("Only local model configured") { !wb.ai.models.state.value.loading && wb.ai.state.value.configured }
                var binding by mutableStateOf(false)
                var models by mutableStateOf(false)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> activity.setContent {
                        val current = LocalConfiguration.current
                        val themed = remember(current, dark) { Configuration(current).apply {
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                        } }
                        CompositionLocalProvider(LocalConfiguration provides themed, LocalWorkbenchDark provides dark) {
                            if (models) MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                                Surface(Modifier.fillMaxSize().safeDrawingPadding()) { AiModelsPage(wb.ai) { models = false } }
                            } else ZakoCoderApp(wb)
                            MaterialTheme(colorScheme = if (dark) WorkbenchDarkColors else WorkbenchColors, typography = WorkbenchTypography) {
                                if (binding) LocalProjectBindingSheet(true, { binding = false }, { error("Screenshot must not bind a real directory") },
                                    initialDirectory = requireNotNull(sharedFixture).path)
                            }
                        }
                    } }
                    click(context.getString(R.string.nav_ai))
                    screenshot(scenario, "01-assistant-reasoning-collapsed", context.getString(R.string.ai_reasoning_title))
                    click(context.getString(R.string.ai_reasoning_title))
                    screenshot(scenario, "02-assistant-reasoning-expanded", context.getString(R.string.ai_reasoning_provider_hint))
                    click(context.getString(R.string.ai_reasoning_title))

                    val liveFirst = "本地测试流：先检查项目入口和移动端布局。"
                    val liveNext = "继续接收：再核对文件操作与底部按钮的位置。"
                    val liveAnswer = "本地测试流已完成。建议保留清晰的项目入口和底部操作区；未执行任何项目命令。"
                    enqueuePaced(fixture.server, listOf(
                        listOf(delta(JSONObject().put("reasoning_content", liveFirst))),
                        listOf(delta(JSONObject().put("reasoning_content", liveNext))),
                        listOf(delta(JSONObject().put("content", liveAnswer)), finished()),
                    ))
                    withContext(Dispatchers.Main) {
                        assertTrue("Start a real local streamed AI turn", wb.ai.send("用本地测试流检查移动端布局，不执行命令。"))
                    }
                    await("First reasoning delta belongs to an active round") {
                        val current = wb.ai.state.value
                        current.active && current.reasoningEntryId != null &&
                            current.timeline.any { it.id == current.reasoningEntryId && it.reasoning == liveFirst }
                    }
                    screenshot(scenario, "11-live-thinking-start", liveFirst)
                    assertNotNull("First screenshot was taken during reasoning", wb.ai.state.value.reasoningEntryId)
                    await("Later reasoning delta visibly appends before the answer") {
                        val current = wb.ai.state.value
                        current.active && current.reasoningEntryId != null &&
                            current.timeline.any { it.id == current.reasoningEntryId && it.reasoning == liveFirst + liveNext }
                    }
                    screenshot(scenario, "12-live-thinking-growing", liveFirst + liveNext)
                    assertNotNull("Second screenshot was taken during reasoning", wb.ai.state.value.reasoningEntryId)
                    await("Answer completes and releases reasoning ownership") {
                        val current = wb.ai.state.value
                        !current.active && current.phase == AiPhase.Completed && current.reasoningEntryId == null &&
                            current.timeline.lastOrNull { it.role == AiRole.Assistant }?.text == liveAnswer
                    }
                    await("Thinking auto-collapses when answer begins") { !has(liveFirst + liveNext) }
                    screenshot(scenario, "13-answer-thinking-collapsed", liveAnswer)

                    click(context.getString(R.string.ai_context_attach))
                    screenshot(scenario, "03-attachments-sheet", context.getString(R.string.ai_images_add))
                    click(context.getString(R.string.ai_attachment_build_log))
                    screenshot(scenario, "03b-attachments-build-log", context.getString(R.string.ai_reference_error_log))
                    click(context.getString(R.string.close))
                    click(context.getString(R.string.ai_permissions))
                    screenshot(scenario, "04-permissions-normal", context.getString(R.string.ai_permission_preset_normal))
                    click(context.getString(R.string.ai_permission_preset_elevated))
                    screenshot(scenario, "05-permissions-elevated-confirmation", context.getString(R.string.ai_auto_allow_confirm))
                    click(context.getString(R.string.ai_auto_allow_confirm))
                    await("Explicit test project policy saved") { !wb.ai.state.value.permissionsSaving && wb.ai.state.value.policy.default == AiPermission.Allow }
                    screenshot(scenario, "06-permissions-elevated", context.getString(R.string.ai_permission_preset_current, context.getString(R.string.ai_permission_preset_elevated)))
                    click(context.getString(R.string.close))
                    withContext(Dispatchers.Main) { binding = true }
                    await("Only generated browser fixture directories are displayed") {
                        has("android-app") && has("web-preview") && has(requireNotNull(sharedFixture).path)
                    }
                    screenshot(scenario, "07-local-directory-binding", context.getString(R.string.local_browser_title))
                    click(context.getString(R.string.close))
                    withContext(Dispatchers.Main) { models = true }
                    click(context.getString(R.string.ai_models_edit_named, "界面验收（本地测试）"))
                    click(context.getString(R.string.ai_options_title))
                    reveal(context.getString(R.string.ai_options_thinking))
                    screenshot(scenario, "08-model-thinking-options", context.getString(R.string.ai_options_thinking))
                    reveal(context.getString(R.string.ai_options_context))
                    screenshot(scenario, "09-model-context-options", context.getString(R.string.ai_options_context))
                    reveal(context.getString(R.string.ai_options_headers))
                    screenshot(scenario, "10-model-request-headers", context.getString(R.string.ai_options_headers))
                    val probeText = "本地测试服务正在返回文本与 connection_probe 参数；不会执行项目命令。"
                    val probeFirst = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0)
                        .put("id", "review-probe").put("function", JSONObject().put("name", "connection_probe").put("arguments", "{\"ack\":"))))
                    val probeLast = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0)
                        .put("function", JSONObject().put("arguments", "\"ok\"}"))))
                    enqueuePaced(fixture.server, listOf(
                        listOf(delta(JSONObject().put("content", probeText))),
                        listOf(delta(probeFirst)),
                        listOf(delta(probeLast), finished("tool_calls")),
                    ))
                    click(context.getString(R.string.ai_models_test_button))
                    await("Actual probe response text streams while test is active") {
                        val current = wb.ai.models.state.value
                        current.probe == AiProbe.Testing && (current.probeProgress?.textChars ?: 0) > 0
                    }
                    screenshot(scenario, "14-model-probe-live-text", context.getString(R.string.ai_probe_live_title))
                    assertEquals("Text screenshot was captured before probe completion", AiProbe.Testing, wb.ai.models.state.value.probe)
                    await("Actual probe tool arguments stream while test is active") {
                        val current = wb.ai.models.state.value
                        current.probe == AiProbe.Testing && (current.probeProgress?.argumentChars ?: 0) > 0
                    }
                    screenshot(scenario, "15-model-probe-live-tool", context.getString(R.string.ai_probe_live_title))
                    assertEquals("Tool screenshot was captured before probe completion", AiProbe.Testing, wb.ai.models.state.value.probe)
                    await("Local tool envelope and ack verified") { wb.ai.models.state.value.probe == AiProbe.Passed }
                    screenshot(scenario, "16-model-probe-verified", context.getString(R.string.ai_probe_verified))
                    assertEquals("History, live chat and probe contacted only the local TLS fixture", 3, fixture.server.requestCount)
                }
            }
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { workbench?.stopAll() }
                delay(250)
                withContext(Dispatchers.IO) {
                    PrivateFiles.deleteTree(own)
                    sharedFixture?.let(PrivateFiles::deleteTree)
                    preferences.forEach { base.deleteSharedPreferences(it) }
                }
            }
        }
    }
}
