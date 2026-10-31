package dev.zakocoder.app

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.projects.ProjectTemplate
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Explicit paid-service acceptance, NEVER part of ordinary CI/device regression.
 * Run against dev.zakocoder.app with liveAcceptance=true and liveAcceptanceStage=build.
 * Then install the produced APK; rerun with liveAcceptanceStage=screen, acceptanceProjectId,
 * authorizeAcceptanceScreen=true and the independently checked acceptanceApkSha256.
 * The explicit opt-in binds ONLY that project artifact for this process, never a persisted lease.
 * Credentials are consumed only by the normal application controller, never read by this test.
 */
class LiveAndroidGameAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 65 * 60 * 1000L)
    fun paidModelDevelopsAndBuildsAndroid2048() = runBlocking {
        optIn("build")
        val workbench = applicationWorkbench()
        ready(workbench)
        check(!workbench.ai.state.value.active) { "An existing task is running; acceptance must not interrupt it" }
        val existingId = arguments.getString("acceptanceProjectId").orEmpty()
        val encodedFeedback = arguments.getString("acceptanceFeedbackBase64").orEmpty()
        require(existingId.isEmpty() == encodedFeedback.isEmpty()) { "Refinement requires an explicit project and feedback together" }
        val project = if (existingId.isEmpty()) {
            val before = workbench.state.value.projects.map { it.id }.toSet()
            main { workbench.create("2048 Acceptance ${System.currentTimeMillis()}", ProjectTemplate.AndroidJava) }
            await(120000) { !workbench.state.value.busy && workbench.state.value.projects.any { it.id !in before } }
            workbench.state.value.projects.single { it.id !in before }
        } else {
            require(UUID.fromString(existingId).toString() == existingId)
            workbench.state.value.projects.single { it.id == existingId }
        }
        val prompt = if (existingId.isEmpty()) BUILD_PROMPT else {
            require(encodedFeedback.length <= 48000)
            String(android.util.Base64.decode(encodedFeedback, android.util.Base64.DEFAULT), Charsets.UTF_8).also { require(it.isNotBlank() && it.length <= 16000) }
        }
        main { workbench.ai.select(project.id) }
        aiReady(workbench, project.id)
        val root = withContext(Dispatchers.IO) { workbench.projects.directory(project.id) }
        val recorder = Recorder(if (existingId.isEmpty()) "build" else "refine", project.id)
        try {
            runTask(workbench, project.id, prompt, recorder)
            withContext(Dispatchers.IO) {
                val artifact = File(root, "app/build/outputs/apk/debug/app-debug.apk")
                check(artifact.isFile && artifact.length() > 1024) { "No actual Debug APK was produced" }
                ZipFile(artifact).use { zip ->
                    check(zip.getEntry("AndroidManifest.xml") != null && zip.getEntry("classes.dex") != null) { "Produced artifact is not an Android APK" }
                }
                val finished = workbench.ai.state.value
                check(finished.events.any { event ->
                    if (event.turn != finished.turn || event.tool != AiTool.RunCommand || event.status != AiToolStatus.Succeeded || event.exitCode != 0) false
                    else {
                        // Require a direct build command in THIS turn, not an old event or text
                        // mentioning assembleDebug in echoed output. The project is already cwd.
                        val command = runCatching { JSONObject(event.input).getString("command").trim() }.getOrDefault("")
                        val parts = command.split(Regex("\\s+"))
                        parts.firstOrNull() in setOf("gradle", "./gradlew") &&
                            parts.any { it in setOf(":app:assembleDebug", "app:assembleDebug", "assembleDebug") } &&
                            parts.none { it in setOf("--dry-run", "-m", "--help", "-h", "--version", "-v", "-x", "--exclude-task") } &&
                            command.none { it in ";&|`\n\r" } && !command.contains('$' + "(")
                    }
                }) { "No successful direct assembleDebug command in this turn" }
                val sources = File(root, "app/src").walkTopDown().filter { it.isFile }.toList() +
                    listOf("app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradle.properties").map { File(root, it) }.filter { it.isFile }
                check(sources.all { it.lastModified() <= artifact.lastModified() + 2000 }) { "APK predates current project sources" }
                recorder.write("artifact", JSONObject().put("path", "app/build/outputs/apk/debug/app-debug.apk")
                    .put("bytes", artifact.length()).put("sha256", digest(artifact)))
            }
            recorder.write("stage_result", JSONObject().put("passed", true).put("scope", "AI development and APK build; installation and gameplay are separate"))
        } catch (failure: Throwable) {
            recorder.write("stage_result", JSONObject().put("passed", false).put("failureClass", failure.javaClass.simpleName))
            throw failure
        } finally { recorder.close() }
    }

    @Test(timeout = 65 * 60 * 1000L)
    fun paidModelTestsOwnerAuthorizedInstalledGame() = runBlocking {
        optIn("screen")
        val projectId = arguments.getString("acceptanceProjectId").orEmpty()
        require(UUID.fromString(projectId).toString() == projectId)
        val workbench = applicationWorkbench()
        ready(workbench)
        check(workbench.state.value.projects.any { it.id == projectId }) { "Acceptance project not found" }
        check(!workbench.ai.state.value.active) { "An existing task is running" }
        if (arguments.getString("authorizeAcceptanceScreen") == "true") {
            val expected = arguments.getString("acceptanceApkSha256").orEmpty()
            require(expected.matches(Regex("[0-9a-f]{64}")))
            val root = withContext(Dispatchers.IO) { workbench.projects.directory(projectId) }
            val actual = withContext(Dispatchers.IO) { digest(File(root, "app/build/outputs/apk/debug/app-debug.apk")) }
            check(actual == expected) { "Artifact changed since explicit screen authorization" }
            await(30000) { workbench.capabilityPackages.state.value.loaded }
            main {
                workbench.capabilityPackages.setEnabled(projectId, dev.zakocoder.app.capabilities.CapabilityPackage.Screen, true)
                workbench.screenTests.setAccess(dev.zakocoder.app.screentest.ScreenTestAccess.Root)
            }
            await(30000) { !workbench.capabilityPackages.state.value.busy }
            check(workbench.capabilityPackages.enabled(projectId, dev.zakocoder.app.capabilities.CapabilityPackage.Screen))
            check(workbench.screenTests.requestRootAccess()) { "Root manager did not authorize the app; no permission bypass" }
            workbench.screenTests.selectApk(projectId, "app/build/outputs/apk/debug/app-debug.apk")
        }
        check(workbench.screenTests.taskLease(projectId) != null) { "Owner must select and authorize the built project APK first" }
        main { workbench.ai.select(projectId) }
        aiReady(workbench, projectId)
        val recorder = Recorder("screen", projectId)
        val screenLease = requireNotNull(workbench.screenTests.taskLease(projectId))
        val screenStarted = System.currentTimeMillis()
        try {
            runTask(workbench, projectId, SCREEN_PROMPT, recorder)
            val events = workbench.ai.state.value.events.filter { it.turn == workbench.ai.state.value.turn && it.tool == AiTool.AndroidTest }
            val actions = events.filter { it.status == AiToolStatus.Succeeded }.map { JSONObject(it.input).getString("action") }
            val observeIndex = actions.indexOf("observe")
            val interactionIndex = actions.indexOfFirst { it == "click" || it == "swipe" }
            check(observeIndex >= 0) { "No successful AI screen observation" }
            check(interactionIndex > observeIndex) { "No successful AI game interaction after observing" }
            check(actions.drop(interactionIndex + 1).any { it in setOf("observe", "assert_text", "screenshot") }) { "No follow-up verification after interaction" }
            check("assert_text" in actions) { "This turn has no successful explicit screen assertion" }
            val report = workbench.screenTests.readReport(projectId)
            val rows = report.lineSequence().mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
                .filter { it.optString("session") == screenLease && it.optLong("time") >= screenStarted }.toList()
            check(rows.any { it.optString("action") == "observe" && it.optBoolean("operationSucceeded") })
            check(rows.any { it.optString("action") in setOf("click", "swipe") && it.optBoolean("operationSucceeded") })
            check(rows.any { it.optString("action") == "assert_text" && it.optBoolean("assertionPassed") }) { "No actual passing screen assertion in persistent report" }
            recorder.write("screen_report", JSONObject().put("report", report))
            recorder.write("stage_result", JSONObject().put("passed", true).put("scope", "AI screen actions recorded; independent human/agent gameplay audit still required"))
        } catch (failure: Throwable) {
            recorder.write("stage_result", JSONObject().put("passed", false).put("failureClass", failure.javaClass.simpleName))
            throw failure
        } finally { recorder.close() }
    }

    private fun optIn(stage: String) {
        assumeTrue("Paid live acceptance is opt-in", arguments.getString("liveAcceptance") == "true")
        assumeTrue("Different acceptance stage", arguments.getString("liveAcceptanceStage", "build") == stage)
        check(context.packageName == "dev.zakocoder.app") { "Use the explicitly authorized daily app, never a fixture's provider config" }
    }
    private fun applicationWorkbench(): Workbench = (context.applicationContext as ZakoApplication).workbench
    private suspend fun ready(workbench: Workbench) {
        await(120000) { !workbench.state.value.loading && !workbench.state.value.busy }
        check(workbench.state.value.installed) { "Existing Ubuntu runtime is required; acceptance does not replace it" }
    }
    private suspend fun aiReady(workbench: Workbench, id: String) {
        await(120000) { workbench.ai.state.value.let { it.projectId == id && it.configured && !it.permissionsLoading && !it.skillsLoading && !it.restoring } && !workbench.ai.models.state.value.loading }
        check(!workbench.ai.state.value.historyError)
    }
    private suspend fun runTask(workbench: Workbench, projectId: String, prompt: String, recorder: Recorder) = coroutineScope {
        val ai = workbench.ai
        val snapshots = Channel<AiState>(Channel.UNLIMITED)
        // Enqueue snapshots without IO so history's bounded 32-request window is not the archive.
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { ai.state.collect { snapshots.send(it) } }
        val writer = launch(Dispatchers.IO) { for (snapshot in snapshots) if (snapshot.projectId == projectId) recorder.capture(snapshot) }
        var ownedTask = false
        try {
            main { check(ai.send(prompt)); ownedTask = true }
            val approved = mutableSetOf<String>()
            withTimeout(60 * 60 * 1000L) {
                while (true) {
                    val state = ai.state.value
                    check(state.projectId == projectId) { "Project changed during acceptance" }
                    state.approval?.let { approval ->
                        val event = state.events.singleOrNull { it.id == approval.eventId }
                        if (event != null && approved.add(event.id)) {
                            val allowed = approvedTool(event)
                            recorder.write("approval", JSONObject().put("eventId", event.id).put("tool", event.name).put("decision", if (allowed) "Once" else "Reject"))
                            main { ai.approve(if (allowed) AiDecision.Once else AiDecision.Reject) }
                            check(allowed) { "Acceptance rejected a tool outside this project workflow" }
                        }
                    }
                    if (!state.active) {
                        recorder.capture(state)
                        check(state.error.isBlank() && state.phase == AiPhase.Completed) { "AI task ended without successful completion: ${state.failureKind}" }
                        break
                    }
                    delay(50)
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (ownedTask && ai.state.value.projectId == projectId && ai.state.value.active) {
                    main { ai.stop() }
                    withTimeoutOrNull(30000) { while (ai.state.value.active) delay(100) }
                }
                collector.cancelAndJoin(); snapshots.close()
                // Finish the evidence archive before closing its stream. Silently timing out
                // here could lose the final requests or race a still-running writer.
                writer.join()
                recorder.capture(ai.state.value)
            }
        }
    }
    private fun approvedTool(event: AiToolEvent): Boolean {
        if (event.tool !in setOf(AiTool.ListFiles, AiTool.ReadFile, AiTool.SearchFiles, AiTool.WriteFile, AiTool.MakeDirectory,
                AiTool.RunCommand, AiTool.ReadCommandLog, AiTool.ListTerminals, AiTool.ReadTerminal, AiTool.StartTerminal,
                AiTool.SendTerminalInput, AiTool.CloseTerminal, AiTool.UpdatePlan, AiTool.AndroidTest)) return false
        // This is a task approval filter, not a security sandbox. Commands still share the app UID.
        val forbidden = Regex("(?i)(/data/(?:data|user)|/sdcard|/storage/|\\.ssh|\\.netrc|git-credentials|ai-config|printenv|\\b(?:sudo|su|ssh|scp)\\b)")
        return !forbidden.containsMatchIn(event.input)
    }
    private suspend fun await(timeout: Long, condition: () -> Boolean) = withTimeout(timeout) { while (!condition()) delay(100) }
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    private inner class Recorder(stage: String, private val projectId: String) {
        private val directory = File(context.noBackupFilesDir, "acceptance/${System.currentTimeMillis()}-$stage-$projectId").apply { check(mkdirs()) }
        private val file = File(directory, "events.jsonl")
        private val stream = file.outputStream().bufferedWriter()
        private val start = SystemClock.elapsedRealtime()
        private val seen = mutableMapOf<String, String>()
        private val rawRequestBodies = mutableMapOf<String, String>()
        // These records are immutable data classes, replaced with copy() on updates.
        private val capturedRequests = mutableMapOf<String, AiRequestLog>()
        private val capturedTools = mutableMapOf<String, AiToolEvent>()
        private val capturedMessages = mutableMapOf<String, AiEntry>()
        // Changes are immutable, appended records. Identity distinguishes repeated edits of the
        // same path (even identical before/after values), and survives takeLast(32) index shifts.
        private val capturedChanges = java.util.IdentityHashMap<AiChange, Int>()
        private val scrub = AiLogRedactor("")
        private var bytes = 0L
        init {
            write("begin", JSONObject().put("projectId", projectId).put("stage", stage).put("paidLiveService", true)
                .put("credentialsReadByTest", false).put("requestArchive", "incremental state snapshots, independent of bounded history"))
            instrumentation.sendStatus(0, Bundle().apply { putString("acceptanceProjectId", projectId); putString("acceptanceLog", file.absolutePath) })
        }
        @Synchronized fun write(kind: String, value: JSONObject) {
            val row = JSONObject().put("time", System.currentTimeMillis()).put("elapsedMs", SystemClock.elapsedRealtime() - start)
                .put("kind", kind).put("value", value)
            val clean = scrub.clean(row.toString())
            bytes += clean.toByteArray().size + 1
            check(bytes <= 256L * 1024 * 1024) { "Acceptance log exceeded 256 MiB; stop rather than silently discard evidence" }
            stream.write(clean); stream.newLine(); stream.flush()
        }
        @Synchronized fun capture(state: AiState) {
            fun changed(key: String, value: JSONObject) {
                val text = value.toString()
                if (seen.put(key, text) != text) write(key.substringBefore(':'), value)
            }
            changed("state", JSONObject().put("projectId", state.projectId).put("conversationId", state.conversationId).put("turn", state.turn)
                .put("phase", state.phase.name).put("active", state.active).put("round", state.round).put("toolCount", state.toolCount)
                .put("error", state.error).put("failureKind", state.failureKind?.name).put("diagnostic", state.taskDiagnostic)
                .put("contextCompactions", state.contextCompactions).put("contextTokens", state.estimatedContextTokens))
            fun textChanged(key: String, identity: String, value: String) {
                val previous = seen[key]
                if (previous == value) return
                val appended = previous != null && value.startsWith(previous)
                seen[key] = value
                write(key.substringBefore(':'), JSONObject().put("id", identity).put("append", appended)
                    .put("text", if (appended) value.substring(previous!!.length) else value))
            }
            state.requests.forEach { request ->
                if (capturedRequests.put(request.id, request) === request) return@forEach
                val encoded = request.encode()
                // Provider wire reasoning signatures/opaque state are not public audit content.
                val body = encoded.optString("request")
                encoded.remove("request"); encoded.remove("response")
                changed("request:${request.id}", encoded)
                if (body.isNotEmpty() && rawRequestBodies.put(request.id, body) != body) {
                    changed("request_body:${request.id}", JSONObject().put("id", request.id).put("body", sanitizeWire(body)))
                }
                if (request.response.isNotEmpty()) textChanged("response:${request.id}", request.id, request.response)
            }
            state.events.forEach { event ->
                if (capturedTools.put(event.id, event) === event) return@forEach
                changed("tool:${event.id}", JSONObject().put("id", event.id).put("callId", event.callId)
                .put("tool", event.name).put("input", event.input).put("reason", event.reason).put("target", event.target)
                .put("permission", event.permission.name).put("permissionSource", context.getString(event.permissionSource))
                .put("status", event.status.name).put("exitCode", event.exitCode)
                .put("started", event.started).put("finished", event.finished).put("truncated", event.truncated).put("turn", event.turn))
                textChanged("tool_output:${event.id}", event.id, event.output)
            }
            state.timeline.forEach { entry ->
                if (capturedMessages.put(entry.id, entry) === entry) return@forEach
                changed("message:${entry.id}", JSONObject().put("id", entry.id).put("role", entry.role.name)
                    .put("turn", entry.turn).put("reasoningTruncated", entry.reasoningTruncated))
                textChanged("message_text:${entry.id}", entry.id, entry.text)
                textChanged("public_reasoning:${entry.id}", entry.id, entry.reasoning)
            }
            state.changes.forEach { change ->
                if (capturedChanges.containsKey(change)) return@forEach
                val recordId = capturedChanges.size
                capturedChanges[change] = recordId
                changed("change:$recordId", JSONObject().put("id", recordId).put("path", change.path)
                    .put("before", change.before?.toString()).put("after", change.after.toString()))
            }
            state.commandChanges.forEach { change -> changed("command_change:${change.id}", change.encode()) }
            state.turnTimings.forEach { timing -> changed("timing:${timing.turn}", JSONObject().put("turn", timing.turn).put("started", timing.started).put("finished", timing.finished)) }
        }
        @Synchronized fun close() { stream.flush(); stream.close() }
        private fun sanitizeWire(text: String): String {
            fun clean(value: Any?): Any? = when (value) {
                is JSONObject -> JSONObject().also { output -> value.keys().forEach { key ->
                    if (key.lowercase() !in setOf("encrypted_content", "signature", "thoughtsignature", "authorization", "x-api-key", "api_key")) output.put(key, clean(value.opt(key)))
                } }
                is JSONArray -> JSONArray().also { output -> repeat(value.length()) { output.put(clean(value.opt(it))) } }
                else -> value
            }
            return try { clean(JSONObject(text)).toString() } catch (_: Exception) { "[non-JSON request diagnostic omitted]" }
        }
    }
    private fun digest(file: File): String = java.security.MessageDigest.getInstance("SHA-256").let { hash ->
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) } }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
    companion object {
        private val BUILD_PROMPT = """
            请在当前新建 Android Java 项目中独立完成一个可玩、布局精致的原生 2048 小游戏。
            使用现有项目和手机 Ubuntu 中已有 Android SDK/JDK/Gradle/AAPT2，不重新安装或替换环境。
            必须实现正确的四方向移动/单次合并/随机新块、计分、最佳分数、重新开始、胜利和无合法移动结束提示。
            为手机触摸提供滑动与可访问的方向按钮；窄屏与深浅色文字均可读，不要求新引入大型框架。
            给核心合并算法编写真实可执行的确定性测试，实际运行并修复失败。
            然后真实构建 app/build/outputs/apk/debug/app-debug.apk，核对构建成功，不仅写代码或列操作说明。
            构建证据必须包含当前轮直接执行的单条 gradle --no-daemon :app:assembleDebug 命令，不带dry-run、排除任务或仅打印命令。
            只操作此项目源码与构建产物；可使用既有 Gradle 缓存及 SDK。不要读取其他项目、密钥配置、个人目录或系统凭据，不申请root。
            此阶段不安装、不启动、不操作屏幕；完成后如实列出修改文件、执行命令、测试结果、APK位置、未验证项。
            如遇错误先定位修复继续执行，遇到无法解决的环境问题如实说明，不伪造完成。
        """.trimIndent()
        private val SCREEN_PROMPT = """
            继续当前 2048 项目的验收。用户已明确授权当前项目APK作为屏幕测试目标。
            先使用android_test的status核对安装及制品匹配；未安装或制品不匹配时用install请求系统安装，等待用户在系统界面确认，再用status核对。不操作安装器或更改系统权限。
            安装确认后，请使用 android_test 工具启动、observe实际节点，操作方向按钮或滑动推进游戏，核对分数/棋盘变化，测试重新开始。
            每次关键操作后再次observe核对变化，至少用assert_text对实际节点中的分数或重新开始后的状态做一次通过断言。
            先读取已有验收记录，避免无目的重复整套测试；当前轮完成观察、一次实际交互及其后续状态断言，再核对重开流程即可汇总。已有失败照实保留，不为追求全绿无限重复点击。
            真实验证窄屏可用、按钮可点、数字清晰；可用时获取截图证据，未授予截图条件则明确未验证，不能停掉其他测试。依据实际节点决定动作，不猜node id。
            若发现应用缺陷，修复并重新构建，但不要越过系统安装确认，明确告知需要重新安装后再验。
            只测试被授权目标，不修改系统权限、不访问其他应用。最后根据实际工具结果报告通过/失败/未验证项，不能以一次click成功宣称整款游戏验收通过。
        """.trimIndent()
    }
}
