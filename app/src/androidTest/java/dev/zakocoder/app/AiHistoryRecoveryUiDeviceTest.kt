package dev.zakocoder.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.ui.ZakoCoderApp
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Real confirmation/cancellation callbacks over disposable history; no model requests or installed user data. */
class AiHistoryRecoveryUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun nodes(): List<AccessibilityNodeInfo> {
        fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            if (!node.refresh()) return@buildList
            add(node)
            repeat(node.childCount) { node.getChild(it)?.let { addAll(descendants(it)) } }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root?.takeIf { root -> root.refresh() } }
            .filter { it.packageName?.toString() == context.packageName }.flatMap(::descendants)
    }
    private fun find(label: String) = nodes().firstOrNull {
        it.isVisibleToUser && (it.text?.toString()?.lines()?.contains(label) == true ||
            it.contentDescription?.toString()?.lines()?.contains(label) == true)
    }
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(25000) { while (!predicate()) delay(60) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(label, error) }
    }
    private suspend fun click(label: String) {
        fun target(): AccessibilityNodeInfo? {
            var node = find(label) ?: return null
            while (!node.isClickable && node.parent != null) node = node.parent
            return node.takeIf { it.isEnabled && it.isClickable }
        }
        await("Enabled action: $label") { target() != null }
        assertTrue("Click $label", requireNotNull(target()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        delay(120)
    }
    private suspend fun screenshot(name: String) {
        delay(250)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            withContext(Dispatchers.IO) {
                File(context.cacheDir, "ui-review/ai-history-recovery/$name.png").apply { parentFile!!.mkdirs() }
                    .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } finally { bitmap.recycle() }
    }
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }

    @Test(timeout = 150000) fun dayCorruptHistoryCancelThenConfirm() = runBlocking { exercise(false) }
    @Test(timeout = 150000) fun nightUnsupportedHistoryCancelThenConfirm() = runBlocking { exercise(true) }

    private suspend fun exercise(dark: Boolean) {
        check(context.packageName.endsWith(".runtimecheck"))
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val fixture = withContext(Dispatchers.IO) { AiDeviceTest.Fixture(context) }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(fixture.root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
            override fun getCacheDir() = File(fixture.root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("${fixture.token}-$name", mode)
            override fun stopService(service: android.content.Intent) = false
        }
        var owned: Workbench? = null
        val theme = if (dark) "night" else "day"
        val previousNight = withContext(Dispatchers.IO) { shell("cmd uimode night").substringAfter(":").trim() }
        check(previousNight in setOf("yes", "no", "auto", "custom_schedule", "custom_bedtime"))
        try {
            withContext(Dispatchers.IO) { shell("cmd uimode night ${if (dark) "yes" else "no"}") }
            withContext(Dispatchers.Main) {
                owned = Workbench(isolated, aiTransport = fixture.transport, aiConfig = fixture.config,
                    aiHistory = fixture.history, aiPermissions = AiPermissionStore(isolated))
            }
            val workbench = requireNotNull(owned)
            await("Workbench initialized") { !workbench.state.value.loading && !workbench.ai.models.state.value.loading && !workbench.mcp.state.value.loading }
            withContext(Dispatchers.Main) { workbench.create("会话恢复验收") }
            await("Synthetic project ready") { !workbench.state.value.busy && workbench.state.value.project != null }
            val projectId = requireNotNull(workbench.state.value.selectedId)
            val good = AiState()
            val bad = AiState()
            val directory = File(File(fixture.root, "history"), MessageDigest.getInstance("SHA-256")
                .digest(projectId.toByteArray()).joinToString("") { "%02x".format(it) })
            val goodFile = File(directory, "${good.conversationId}.json")
            val badFile = File(directory, "${bad.conversationId}.json")
            val expectedGood = withContext(Dispatchers.IO) {
                fixture.history.save(projectId, good); fixture.history.save(projectId, bad)
                badFile.writeText(if (dark) JSONObject(badFile.readText()).put("schemaVersion", 99).toString() else "{broken")
                goodFile.readBytes()
            }
            val source = withContext(Dispatchers.IO) {
                File(workbench.projects.directory(projectId), "keep.txt").apply { writeText("preserve user source") }
            }
            withContext(Dispatchers.Main) { workbench.ai.select(projectId) }
            val ai = workbench.ai
            await("Precisely identified unreadable record") { !ai.state.value.permissionsLoading && ai.state.value.historyProblem?.conversationId == bad.conversationId }
            val expectedReason = if (dark) AiHistoryProblem.Reason.Unsupported else AiHistoryProblem.Reason.Corrupt
            assertEquals(expectedReason, ai.state.value.historyProblem!!.reason)
            withContext(Dispatchers.IO) { warmDeviceActivity() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { ZakoCoderApp(workbench) } }
                click(context.getString(R.string.app_navigation_open)); click(context.getString(R.string.nav_ai))
                await("Recovery action displayed") { find(context.getString(R.string.ai_history_remove_unreadable)) != null }
                screenshot("$theme-error")
                click(context.getString(R.string.ai_history_remove_unreadable))
                await("Confirmation dialog displayed") { find(context.getString(R.string.ai_history_remove_unreadable_title)) != null }
                screenshot("$theme-confirm")
                click(context.getString(R.string.cancel))
                await("Cancel dismisses dialog") { find(context.getString(R.string.ai_history_remove_unreadable_title)) == null }
                withContext(Dispatchers.IO) {
                    assertTrue(badFile.exists()); assertArrayEquals(expectedGood, goodFile.readBytes())
                }
                assertTrue(ai.state.value.historyError)
                screenshot("$theme-cancelled")
                click(context.getString(R.string.ai_history_remove_unreadable))
                click(context.getString(R.string.ai_history_remove_unreadable_confirm))
                await("Confirmed recovery opens healthy session") {
                    !ai.state.value.permissionsLoading && !ai.state.value.historyError && ai.state.value.conversationId == good.conversationId
                }
                withContext(Dispatchers.IO) {
                    assertFalse(badFile.exists()); assertArrayEquals(expectedGood, goodFile.readBytes())
                    assertEquals("preserve user source", source.readText())
                    assertEquals(good.conversationId, fixture.history.load(projectId).state!!.conversationId)
                }
                assertEquals(0, fixture.server.requestCount)
                screenshot("$theme-recovered")
            }
        } finally {
            withContext(Dispatchers.Main) { owned?.stopAll() }
            withContext(Dispatchers.IO) { try { fixture.close() } finally { shell("cmd uimode night $previousNight") } }
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }
}
