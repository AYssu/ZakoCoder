package dev.zakocoder.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Local TLS protocol fixture; never connects to a commercial service or reads user configuration. */
class AiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun await(condition: () -> Boolean) = withTimeout(20000) { while (!condition()) delay(50) }
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    class Fixture(val context: android.content.Context, timeoutSeconds: Long = 15, responseOnly: Boolean = false) : AutoCloseable {
        val token = "ai-test-${UUID.randomUUID()}"
        val root = File(context.cacheDir, token).apply { mkdirs() }
        val history = AiHistoryStore(File(root, "history"))
        val alias = "$token-key"
        val config = AiConfigStore(context, token, alias)
        val server = MockWebServer()
        val transport: ChatTransport
        init {
            val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
            val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            if (responseOnly) server.dispatcher = ResponseOnlyDispatcher()
            server.useHttps(serverCertificates.sslSocketFactory(), false); server.start()
            transport = ChatTransport(OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .followRedirects(false).followSslRedirects(false).callTimeout(timeoutSeconds, TimeUnit.SECONDS).build())
            config.save(server.url("/v1").toString(), "fixture-model", "test-only-placeholder")
        }
        fun enqueue(text: String = "", calls: List<AiCall> = emptyList(), finish: String = if (calls.isEmpty()) "stop" else "tool_calls", done: Boolean = true, delayMillis: Long = 1) {
            val parts = mutableListOf<JSONObject>()
            if (text.isNotEmpty()) parts += JSONObject().put("content", text)
            calls.forEachIndexed { index, call ->
                val halfway = call.arguments.length / 2
                parts += JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", index).put("id", call.id).put("function", JSONObject().put("name", call.name).put("arguments", call.arguments.take(halfway)))))
                parts += JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", index).put("function", JSONObject().put("arguments", call.arguments.drop(halfway)))))
            }
            val body = buildString {
                parts.forEach { append("data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", it)))}\n\n") }
                append("data: ${JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", finish)))}\n\n")
                if (done) append("data: [DONE]\n\n")
            }
            enqueueResponse(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body).throttleBody(37, delayMillis, TimeUnit.MILLISECONDS))
        }
        fun enqueueResponse(response: MockResponse) {
            val dispatcher = server.dispatcher
            if (dispatcher is ResponseOnlyDispatcher) dispatcher.enqueue(response) else server.enqueue(response)
        }
        override fun close() {
            server.shutdown(); root.deleteRecursively()
            listOf("$token.json", "$token-profiles-v2.json").forEach { name -> File(context.filesDir, name).delete(); File(context.filesDir, "$name.bak").delete() }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
    @Test(timeout = 120000) fun encryptedConfigStreamingToolsApprovalRealUbuntuAndUndo() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val workbench = (context.applicationContext as ZakoApplication).workbench
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            assertTrue(workbench.state.value.installed)
            Fixture(context).use { fixture ->
                val project = withContext(Dispatchers.IO) { workbench.projects.create(fixture.token) }
                val root = workbench.projects.directory(project.id)
                var controller: AiController? = null
                try {
                    File(root, "main.py").writeText("print('before')\n")
                    val read = AiCall("read-1", "read_file", JSONObject().put("path", "main.py").toString())
                    val write = AiCall("write-1", "write_file", JSONObject().put("path", "main.py").put("content", "print('AI_VERIFIED')\n").toString())
                    val command = AiCall("command-1", "run_command", JSONObject().put("command", "python3 main.py").toString())
                    fixture.enqueue(calls = listOf(read)); fixture.enqueue(calls = listOf(write, command)); fixture.enqueue("文件已修改，命令输出已验证。")
                    main { controller = AiController(context, workbench.projects::directory, { _, _ -> false },
                        { id, script -> workbench.runtime.execute(script, id, workbench.state.value.settings, timeoutMillis = 120000) }, {}, {}, fixture.transport, fixture.config, history = fixture.history)
                        controller!!.select(project.id) }
                    val ai = controller!!
                    await { ai.state.value.configured && !ai.state.value.permissionsLoading }
                    val encrypted = File(context.filesDir, "${fixture.token}.json").readText()
                    assertFalse(encrypted.contains("test-only-placeholder"))
                    main { ai.send("修改程序并执行验证") }
                    await { ai.state.value.approval != null }
                    assertTrue(ai.state.value.approval!!.detail.contains("+print('AI_VERIFIED')"))
                    assertEquals("print('before')\n", File(root, "main.py").readText())
                    main { ai.approve(true) }
                    await { ai.state.value.approval?.title == context.getString(R.string.ai_command) }
                    assertEquals("print('AI_VERIFIED')\n", File(root, "main.py").readText())
                    assertTrue(ai.state.value.approval!!.detail.contains("/workspace/${project.id}"))
                    main { ai.approve(true) }
                    await { !ai.state.value.active }
                    assertEquals("", ai.state.value.error)
                    assertTrue(ai.state.value.messages.any { message -> message.contains("退出码：0") && message.contains("AI_VERIFIED") })
                    val requests = (1..3).map { fixture.server.takeRequest(5, TimeUnit.SECONDS) ?: error("missing request") }
                    val lastMessages = JSONObject(requests.last().body.readUtf8()).getJSONArray("messages")
                    assertTrue((0 until lastMessages.length()).any { n -> lastMessages.getJSONObject(n).optString("tool_call_id") == "command-1" })
                    val change = ai.state.value.changes.single()
                    File(root, "main.py").writeText("manual change\n")
                    main { ai.undo(change) }; await { ai.state.value.approval != null }; main { ai.approve(true) }; await { !ai.state.value.active }
                    assertEquals("manual change\n", File(root, "main.py").readText())
                    File(root, "main.py").writeText(change.after.text)
                    main { ai.undo(change) }; await { ai.state.value.approval != null }; main { ai.approve(true) }; await { !ai.state.value.active }
                    assertEquals("print('before')\n", File(root, "main.py").readText())
                    assertTrue(ai.state.value.changes.isEmpty())
                    withContext(Dispatchers.IO) {
                        try { fixture.config.save("https://different.example/v1", "model", null); fail("Origin change must not reuse key") } catch (_: IllegalArgumentException) { }
                        fixture.config.save(fixture.server.url("/v1").toString(), "new-model", "")
                        assertEquals("", fixture.config.load().key)
                        fixture.config.save(fixture.server.url("/v1").toString(), "new-model", "another-test-placeholder")
                        assertEquals("another-test-placeholder", fixture.config.load().key)
                    }
                } finally { main { controller?.stop() }; await { controller?.state?.value?.active != true }; withContext(Dispatchers.IO) { root.deleteRecursively() } }
            }
        }
    }
    @Test(timeout = 100000) fun rejectionTruncatedToolAndStopNeverWrite() = runBlocking {
        Fixture(context).use { fixture ->
            val call = AiCall("write-reject", "write_file", JSONObject().put("path", "new.txt").put("content", "should-not-appear").toString())
            var controller: AiController? = null
            try {
                main { controller = AiController(context, { fixture.root }, { _, _ -> false }, { _, _ -> error("No command allowed") }, {}, {}, fixture.transport, fixture.config, history = fixture.history); controller!!.select("fixture") }
                val ai = controller!!; await { ai.state.value.configured && !ai.state.value.permissionsLoading }
                fixture.enqueue(calls = listOf(call)); fixture.enqueue("已尊重拒绝。")
                main { ai.send("提出新文件") }; await { ai.state.value.approval != null }; main { ai.approve(false) }; await { !ai.state.value.active }
                assertFalse(File(fixture.root, "new.txt").exists())
                fixture.enqueue(calls = listOf(call), finish = "length")
                main { ai.send("截断响应") }; await { !ai.state.value.active }
                assertNull(ai.state.value.approval); assertFalse(File(fixture.root, "new.txt").exists()); assertTrue(ai.state.value.error.isNotEmpty())
                fixture.enqueue(calls = listOf(call))
                main { ai.send("待确认时停止") }; await { ai.state.value.approval != null }; main { ai.stop() }; await { !ai.state.value.active }
                assertNull(ai.state.value.approval); assertFalse(File(fixture.root, "new.txt").exists())
                fixture.server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
                main { ai.send("网络等待时停止") }; delay(200); main { ai.stop() }; await { !ai.state.value.active }
                assertTrue(ai.state.value.messages.last().contains("停止"))
            } finally { main { controller?.stop() }; await { controller?.state?.value?.active != true } }
        }
    }
    @Test(timeout = 120000) fun commandFailureAndCancellationKeepRealExitAndKillOwnedProcess() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val workbench = (context.applicationContext as ZakoApplication).workbench
            await { !workbench.state.value.loading && !workbench.state.value.busy }
            Fixture(context).use { fixture ->
                val project = withContext(Dispatchers.IO) { workbench.projects.create(fixture.token) }
                val root = workbench.projects.directory(project.id)
                var controller: AiController? = null
                try {
                    main { controller = AiController(context, workbench.projects::directory, { _, _ -> false },
                        { id, script -> workbench.runtime.execute(script, id, workbench.state.value.settings, timeoutMillis = 120000) }, {}, {}, fixture.transport, fixture.config, history = fixture.history)
                        controller!!.select(project.id) }
                    val ai = controller!!; await { ai.state.value.configured && !ai.state.value.permissionsLoading }
                    fixture.enqueue(calls = listOf(AiCall("exit-seven", "run_command", JSONObject().put("command", "printf 'EXPECTED_FAILURE\\n'; exit 7").toString())))
                    fixture.enqueue("命令失败，退出码 7。")
                    main { ai.send("验证失败退出码") }; await { ai.state.value.approval != null }; main { ai.approve(true) }; await { !ai.state.value.active }
                    assertTrue(ai.state.value.messages.any { message -> message.contains("退出码：7") && message.contains("EXPECTED_FAILURE") })
                    fixture.enqueue(calls = listOf(AiCall("owned-sleep", "run_command", JSONObject().put("command", "echo ${'$'}${'$'} > owned.pid; sleep 60").toString())))
                    main { ai.send("启动后停止命令") }; await { ai.state.value.approval != null }; main { ai.approve(true) }
                    val pidFile = File(root, "owned.pid"); await { pidFile.exists() && pidFile.readText().trim().isNotEmpty() }
                    val pid = pidFile.readText().trim().toInt(); assertTrue(File("/proc/$pid").exists())
                    main { ai.stop() }; await { !ai.state.value.active }; await { !File("/proc/$pid").exists() }
                    assertTrue(ai.state.value.messages.last().contains("停止"))
                } finally { main { controller?.stop() }; await { controller?.state?.value?.active != true }; withContext(Dispatchers.IO) { root.deleteRecursively() } }
            }
        }
    }

    @Test(timeout = 60000) fun interruptedMarkerRemainsVisibleAfterProjectSelectionAndNeverReplays() = runBlocking {
        Fixture(context).use { fixture ->
            val wrapped = object : android.content.ContextWrapper(context) { override fun getFilesDir(): File = fixture.root }
            val marker = File(fixture.root, "ai-task-marker.json")
            marker.writeText(JSONObject().put("project", "fixture").put("paths", JSONArray().put("edited.txt")).toString())
            var controller: AiController? = null
            try {
                main { controller = AiController(wrapped, { fixture.root }, { _, _ -> false }, { _, _ -> error("Must not replay") }, {}, {}, fixture.transport, fixture.config, history = fixture.history) }
                val ai = controller!!; await { ai.state.value.configured && !ai.state.value.permissionsLoading }
                assertTrue(ai.state.value.interrupted)
                main { ai.select("fixture") }
                assertTrue(ai.state.value.interrupted); assertFalse(ai.state.value.active); assertEquals(0, fixture.server.requestCount)
                await { !ai.state.value.permissionsLoading }
                fixture.enqueue("新任务完成。")
                main { ai.send("新的独立任务") }; await { !ai.state.value.active }
                assertFalse(ai.state.value.interrupted); assertFalse(marker.exists())
            } finally { main { controller?.stop() }; await { controller?.state?.value?.active != true } }
        }
    }

}
