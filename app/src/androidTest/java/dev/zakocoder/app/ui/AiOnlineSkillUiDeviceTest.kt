package dev.zakocoder.app.ui

import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.AiDeviceTest
import dev.zakocoder.app.MainActivity
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.warmDeviceActivity
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual import page with an injected transport and real isolated archive installer, never public network access. */
@SdkSuppress(minSdkVersion = 30)
class AiOnlineSkillUiDeviceTest {
    private val ui = UpgradeUiHarness()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 180000) fun daySelectionPreviewFailureRetryAndLoadingCancel() = review(false)
    @Test(timeout = 180000) fun nightSelectionPreviewFailureRetryAndLoadingCancel() = review(true)

    private fun review(dark: Boolean) {
        AiDeviceTest.Fixture(context).use { fixture ->
            val preferences = mutableSetOf<String>()
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir() = File(fixture.root, "files").apply { mkdirs() }
                override fun getNoBackupFilesDir() = File(fixture.root, "private").apply { mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                    val key = "${fixture.token}-$name"
                    synchronized(preferences) { preferences += key }
                    return context.getSharedPreferences(key, mode)
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val source = GithubFixture()
            val saveAttempts = AtomicInteger()
            val closed = AtomicBoolean()
            val ai = AtomicReference<AiController>()
            val online = AtomicReference<AiOnlineSkillController>()
            val theme = if (dark) "night" else "day"
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        ui.configureSystemBars(activity, dark)
                        val controller = AiController(isolated, { error("No workspace used by global import") }, { _, _ -> false },
                            { _, _ -> error("Skill import never runs commands") }, {}, {}, fixture.transport, fixture.config, history = fixture.history)
                        ai.set(controller)
                        controller.refreshGlobalSkills()
                        val importer = AiOnlineSkillController(AiOnlineSkills(source), scope) { bytes, id ->
                            if (saveAttempts.incrementAndGet() == 1) false
                            else controller.saveGlobalSkill("", "", "", true, id, bytes)
                        }
                        online.set(importer)
                        activity.setContent {
                            WorkbenchMiuixTheme(dark) {
                                Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                                    AiOnlineSkillImport(controller, providedController = importer) { closed.set(true) }
                                }
                            }
                        }
                    }
                    val controller = ai.get()
                    val importer = online.get()
                    ui.await("Global skill catalog ready") { !controller.globalSkills.value.skillsLoading && controller.globalSkills.value.skillsInitialized }
                    val before = controller.globalSkills.value.enabledSkills
                    ui.setField(R.string.online_skills_url, "https://github.com/example/skills")
                    ui.click(R.string.online_skills_discover)
                    ui.await("Loading state visible") { importer.state.value.loading && ui.has(R.string.online_skills_loading) }
                    ui.capture("online-skills/$theme-loading")
                    ui.click(R.string.online_skills_cancel)
                    ui.await("Loading cancellation reaches transport") { source.cancelled.get() && !importer.state.value.loading }
                    assertNull(importer.state.value.catalog)
                    assertEquals(0, saveAttempts.get())

                    source.mode.set("failure")
                    ui.scrollTop()
                    ui.click(R.string.online_skills_discover)
                    ui.await("Discovery failure is understandable") { ui.has(R.string.online_skills_error_rate_limit) }
                    ui.capture("online-skills/$theme-discovery-error")
                    source.mode.set("catalog")
                    ui.scrollTop()
                    ui.click(R.string.online_skills_discover)
                    ui.await("Fixture catalog loaded") { importer.state.value.catalog?.candidates?.size == 1 && !importer.state.value.loading }
                    assertTrue(importer.state.value.selected.isEmpty())
                    val label = context.getString(R.string.online_skills_select, "frontend")
                    ui.click(label)
                    ui.await("User explicitly selected one directory") { importer.state.value.selected == setOf("frontend") }
                    ui.capture("online-skills/$theme-selection")
                    ui.click(R.string.online_skills_prepare)
                    ui.await("Selected skill downloaded and validated") { importer.state.value.prepared?.names == listOf("ui-online-frontend") && !importer.state.value.preparing }
                    ui.reveal(context.getString(R.string.online_skills_review_title))
                    ui.capture("online-skills/$theme-review")
                    assertEquals(0, saveAttempts.get())
                    assertEquals(1, source.rawDownloads.get())
                    val downloaded = requireNotNull(importer.state.value.prepared).bytes.copyOf()
                    ui.click(context.getString(R.string.online_skills_preview_document, "ui-online-frontend"))
                    ui.await("Read-only document preview opens") { ui.has(R.string.online_skills_preview_title) }
                    ui.reveal(source.documentText)
                    assertTrue("Preview must show the complete downloaded Skill, including its metadata", ui.has(source.documentText))
                    assertTrue(ui.has(context.getString(R.string.online_skills_commit, "1".repeat(40))))
                    assertTrue(ui.has(context.getString(R.string.online_skills_license, "MIT")))
                    assertTrue(ui.has(R.string.online_skills_preview_notice))
                    ui.capture("online-skills/$theme-document-preview")
                    ui.click(R.string.close)
                    ui.await("Closing the document returns to pending confirmation") { !ui.has(R.string.online_skills_preview_title) }
                    assertEquals(0, saveAttempts.get())
                    assertArrayEquals("Reading must not change the validated installation archive", downloaded, importer.state.value.prepared!!.bytes)
                    ui.click(R.string.online_skills_preview_license)
                    ui.reveal(source.licenseText)
                    assertTrue("The original license must be readable before installation", ui.has(source.licenseText))
                    ui.capture("online-skills/$theme-license-preview")
                    ui.click(R.string.close)
                    ui.await("Closing license returns to review") { ui.has(R.string.online_skills_review_title) }
                    ui.reveal(context.getString(R.string.online_skills_acknowledge))
                    ui.toggleOnlyControl()
                    ui.click(R.string.online_skills_install)
                    ui.await("Failed install exposes a reason") { importer.state.value.problem == OnlineSkillProblem.Install }
                    ui.reveal(context.getString(R.string.online_skills_error_install))
                    assertFalse(importer.state.value.installed)
                    assertTrue(controller.globalSkills.value.skills.none { !it.builtIn })
                    ui.capture("online-skills/$theme-install-error")
                    ui.click(R.string.online_skills_install)
                    ui.await("Retry installs into isolated shared store") { importer.state.value.installed }
                    ui.reveal(context.getString(R.string.online_skills_installed))
                    assertEquals(2, saveAttempts.get())
                    val added = controller.globalSkills.value.skills.single { !it.builtIn }
                    assertEquals("ui-online-frontend", added.name)
                    assertEquals("Import must not enable new skills", before, controller.globalSkills.value.enabledSkills)
                    assertFalse(added.path in controller.globalSkills.value.enabledSkills)
                    val origin = File(isolated.filesDir, "shared-skills/${added.path.substringBeforeLast('/')}/ZAKOCODER_ORIGIN.json")
                    assertTrue(origin.exists())
                    assertEquals("1".repeat(40), JSONObject(origin.readText()).getString("commit"))
                    assertNull(controller.state.value.projectId)
                    assertEquals("The model fixture must never be contacted", 0, fixture.server.requestCount)
                    ui.capture("online-skills/$theme-installed")
                    ui.click(R.string.online_skills_done)
                    assertTrue(closed.get())
                }
            } finally {
                runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
                instrumentation.runOnMainSync { ai.get()?.stop() }
                synchronized(preferences) { preferences.forEach { context.deleteSharedPreferences(it) } }
            }
        }
    }

    private class GithubFixture : OnlineSkillTransport {
        val mode = AtomicReference("slow")
        val cancelled = AtomicBoolean()
        val rawDownloads = AtomicInteger()
        private val commit = "1".repeat(40)
        private val tree = "2".repeat(40)
        val documentText = "---\nname: ui-online-frontend\ndescription: Synthetic online import UI fixture\n---\n\nReview the existing UI before editing.\n"
        val licenseText = "Synthetic fixture license: preserve this notice when redistributing.\n"
        private val document = documentText.toByteArray()
        private val license = licenseText.toByteArray()
        private val blob = MessageDigest.getInstance("SHA-1").apply {
            update("blob ${document.size}\u0000".toByteArray()); update(document)
        }.digest().joinToString("") { "%02x".format(it) }
        override suspend fun fetch(url: String, limit: Int): ByteArray {
            when (mode.get()) {
                "slow" -> try { awaitCancellation() } finally { cancelled.set(true) }
                "failure" -> throw OnlineSkillException(OnlineSkillProblem.RateLimited, 403)
            }
            val api = "https://api.github.com/repos/example/skills"
            val value: Any = when (url) {
                api -> JSONObject().put("default_branch", "main")
                "$api/commits/main" -> JSONObject().put("sha", commit).put("commit", JSONObject().put("tree", JSONObject().put("sha", tree)))
                "$api/git/trees/$tree?recursive=1" -> JSONObject().put("truncated", false).put("tree", JSONArray().put(JSONObject()
                    .put("path", "frontend/SKILL.md").put("mode", "100644").put("type", "blob").put("size", document.size).put("sha", blob)))
                "$api/license?ref=$commit" -> JSONObject().put("path", "LICENSE").put("encoding", "base64")
                    .put("size", license.size).put("sha", MessageDigest.getInstance("SHA-1").apply {
                        update("blob ${license.size}\u0000".toByteArray()); update(license)
                    }.digest().joinToString("") { "%02x".format(it) })
                    .put("content", Base64.getEncoder().encodeToString(license)).put("license", JSONObject().put("spdx_id", "MIT"))
                "https://raw.githubusercontent.com/example/skills/$commit/frontend/SKILL.md" -> { rawDownloads.incrementAndGet(); document }
                else -> error("Unexpected fixture URL: $url")
            }
            return if (value is ByteArray) value else value.toString().toByteArray()
        }
    }
}
