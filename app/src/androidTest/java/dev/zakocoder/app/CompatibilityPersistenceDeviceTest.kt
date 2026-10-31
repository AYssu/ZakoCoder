package dev.zakocoder.app

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import dev.zakocoder.app.ai.AiCheckpoints
import dev.zakocoder.app.ai.AiHistoryStore
import dev.zakocoder.app.ai.AiPhase
import dev.zakocoder.app.ai.AiState
import dev.zakocoder.app.ai.AiConfigStore
import dev.zakocoder.app.ai.AiModelDraft
import dev.zakocoder.app.ai.AiContextOptions
import dev.zakocoder.app.ai.AiEntry
import dev.zakocoder.app.ai.AiRole
import dev.zakocoder.app.editor.EditorController
import dev.zakocoder.app.projects.ProjectRepository
import dev.zakocoder.app.runtime.PrivateFiles
import dev.zakocoder.app.settings.AppSettings
import dev.zakocoder.app.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.security.KeyStore

/** Host runs each method separately, force-stopping and then installing a newer APK between phases. */
@SdkSuppress(minSdkVersion = 30)
class CompatibilityPersistenceDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val runId: String get() = requireNotNull(InstrumentationRegistry.getArguments().getString("compatibilityRunId")) {
        "Explicit compatibilityRunId UUID required; run only against the selected compatibility device"
    }.also { require(UUID.fromString(it).toString() == it) }
    private val base get() = File(context.noBackupFilesDir, "compatibility-probes/$runId")
    private val proofFile get() = File(base, "ownership.json")
    private val isolated get() = object : ContextWrapper(context) {
        override fun getFilesDir() = File(base, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(base, "private").apply { mkdirs() }
        override fun getCacheDir() = File(base, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("compatibility-$runId-$name", mode)
    }
    private fun repository() = ProjectRepository(isolated, File(base, "workspaces"))
    private fun history() = AiHistoryStore(File(base, "history"))
    private val configAlias get() = "compatibility-ai-$runId"
    private fun config() = AiConfigStore(isolated, "compatibility-model", configAlias)
    private val fixtureSettings get() = AppSettings(terminalFontSp = 12, additionalPath = "/opt/compatibility/bin", dns = "1.1.1.1")
    private val userText get() = "检查本次兼容性项目 $runId 的源码；这是本地持久化测试文本。"
    private val assistantText get() = "本地测试回复 $runId：检查点与草稿分别保存，不会覆盖未保存源码。"
    private fun contextTurn() = JSONArray()
        .put(JSONObject().put("role", "user").put("content", userText))
        .put(JSONObject().put("role", "assistant").put("content", assistantText)).toString()
    private fun versionCode() = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    private fun checkProcess() {
        assertEquals("Instrumentation must exercise the real app process", context.packageName, Application.getProcessName())
        assertEquals(context.applicationInfo.uid, Process.myUid())
    }
    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(20_000) {
        while (!condition()) delay(40)
    }

    @Test(timeout = 60_000) fun prepare(): Unit = runBlocking {
        checkProcess()
        val repo = repository()
        val proof = withContext(Dispatchers.IO) {
            check(!proofFile.exists()) { "Run ID already owns a fixture; verify it or choose a new UUID" }
            base.mkdirs()
            val project = repo.create("compatibility $runId")
            repo.save(listOf(project))
            repo.select(project.id)
            File(repo.directory(project.id), "main.py").writeText("print('original')\n")
            val checkpoint = AiCheckpoints(File(base, "checkpoints"), repo::directory)
            val checkpointId = checkpoint.begin(project.id, "compatibility", 1)
            File(repo.directory(project.id), "main.py").writeText("print('verified task result')\n")
            checkpoint.update(project.id, checkpointId, true)
            SettingsRepository(isolated).save(fixtureSettings)
            config().saveProfile(AiModelDraft(null, "Compatibility fixture", "https://compatibility.example.invalid/v1",
                "compatibility-fixture-model", "", context = AiContextOptions(contextWindowTokens = 1_000_000)))
            val savedState = AiState(turn = 1, phase = AiPhase.Completed, checkpointId = checkpointId,
                timeline = listOf(AiEntry("user-$runId", userText, role = AiRole.User, turn = 1),
                    AiEntry("assistant-$runId", assistantText, role = AiRole.Assistant, turn = 1)),
                contextTurns = listOf(contextTurn()))
            history().save(project.id, savedState)
            JSONObject().put("runId", runId).put("project", project.id).put("checkpoint", checkpointId)
                .put("conversation", savedState.conversationId)
                .put("preparedPid", Process.myPid()).put("preparedVersionCode", versionCode())
        }
        lateinit var editor: EditorController
        withContext(Dispatchers.Main) { editor = EditorController(isolated, repo, {}) }
        await { !editor.state.value.loading }
        withContext(Dispatchers.Main) { editor.open(proof.getString("project"), "main.py") }
        await { !editor.state.value.loading && editor.state.value.document != null }
        withContext(Dispatchers.Main) {
            editor.state.value.document!!.content.let { it.replace(0, it.length, "print('unsaved draft $runId')\n") }
            editor.changed()
        }
        // Wait for the actual production draft writer, rather than relying on a fixed sleep.
        await { withContext(Dispatchers.IO) {
            val file = File(base, "private/editor-drafts/drafts-v1.json")
            file.exists() && runCatching { file.readText().contains("unsaved draft $runId") }.getOrDefault(false)
        } }
        withContext(Dispatchers.IO) { proofFile.writeText(proof.toString()) }
        // Do not close/discard the draft. The host must now terminate this process.
    }

    @Test(timeout = 60_000) fun verifyAfterProcessDeath(): Unit = runBlocking {
        val proof = verifyStoredState()
        assertNotEquals("Host must force-stop before this phase", proof.getInt("preparedPid"), Process.myPid())
        assertEquals("Process-death phase precedes APK upgrade", proof.getLong("preparedVersionCode"), versionCode())
        withContext(Dispatchers.IO) {
            proof.put("verifiedDeathPid", Process.myPid())
            proofFile.writeText(proof.toString())
        }
    }

    @Test(timeout = 60_000) fun verifyAfterUpgradeAndClean(): Unit = runBlocking {
        val proof = verifyStoredState()
        assertTrue("Install a higher versionCode APK with adb install -r; reinstall is not upgrade evidence",
            versionCode() > proof.getLong("preparedVersionCode"))
        assertNotEquals("APK upgrade must have replaced the prior app process", proof.getInt("verifiedDeathPid"), Process.myPid())
        withContext(Dispatchers.IO) {
            // Delete only a UUID subtree whose ownership marker was verified above.
            PrivateFiles.deleteTree(base)
            listOf("projects", "development-settings", "proxy-credentials").forEach {
                context.deleteSharedPreferences("compatibility-$runId-$it")
            }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(configAlias) }
        }
    }

    private suspend fun verifyStoredState(): JSONObject {
        checkProcess()
        val proof = withContext(Dispatchers.IO) { JSONObject(proofFile.readText()) }
        assertEquals(runId, proof.getString("runId"))
        val projectId = proof.getString("project")
        val repo = repository()
        withContext(Dispatchers.IO) {
            val project = repo.load().single()
            assertEquals(projectId, project.id)
            assertEquals("compatibility $runId", project.name)
            assertEquals(projectId, repo.selected())
            assertEquals("print('verified task result')\n", File(repo.directory(projectId), "main.py").readText())
            assertEquals(fixtureSettings, SettingsRepository(isolated).load())
            val model = config().load()
            assertEquals("https://compatibility.example.invalid/v1", model.endpoint)
            assertEquals("compatibility-fixture-model", model.model)
            assertEquals("", model.key)
            assertEquals(1_000_000, model.context.contextWindowTokens)
            val state = requireNotNull(history().load(projectId).state)
            assertEquals(AiPhase.Completed, state.phase)
            assertEquals(1, state.turn)
            assertEquals(proof.getString("checkpoint"), state.checkpointId)
            assertEquals(proof.getString("conversation"), state.conversationId)
            assertEquals(listOf(AiRole.User, AiRole.Assistant), state.timeline.map { it.role })
            assertEquals(listOf(userText, assistantText), state.timeline.map { it.text })
            assertEquals(listOf(contextTurn()), state.contextTurns)
            val change = AiCheckpoints(File(base, "checkpoints"), repo::directory)
                .changes(projectId, proof.getString("checkpoint")).single()
            assertEquals("print('original')\n", change.before)
            assertEquals("print('verified task result')\n", change.after)
        }
        lateinit var editor: EditorController
        withContext(Dispatchers.Main) { editor = EditorController(isolated, repo, {}) }
        await { !editor.state.value.loading }
        assertEquals(1, editor.state.value.tabs.size)
        withContext(Dispatchers.Main) { editor.open(projectId, "main.py") }
        await { !editor.state.value.loading && editor.state.value.document != null }
        assertEquals("print('unsaved draft $runId')\n", editor.state.value.document!!.content.toString())
        assertTrue(editor.state.value.dirty)
        return proof
    }
}
