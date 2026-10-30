package dev.zakocoder.app

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.*
import dev.zakocoder.app.screentest.ScreenTestResult
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Local TLS and isolated project/app files. No system permission or real app is controlled. */
class AiScreenTestDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(45000) { while (!condition()) delay(10) }
    private suspend fun ready(ai: AiController) = await { ai.state.value.configured && !ai.state.value.permissionsLoading && !ai.state.value.skillsLoading && !ai.models.state.value.loading }
    private fun controller(f: AiDeviceTest.Fixture, execute: suspend (String, JSONObject) -> ScreenTestResult,
        finish: suspend (String) -> Unit = {}, stop: () -> Unit = {}, share: Boolean = false,
        lease: (String) -> String? = { "fixture-session-$it" },
        finishSession: (suspend (String, String?) -> Unit)? = null): AiController {
        val isolated = object : ContextWrapper(base) {
            override fun getFilesDir() = File(f.root, "controller-files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(f.root, "controller-private").apply { mkdirs() }
        }
        return AiController(isolated, { File(f.root, "project-$it").apply { mkdirs() } }, { _, _ -> false },
            { _, _ -> error("No host or guest command expected") }, {}, {}, f.transport, f.config, history = f.history,
            capabilitySnapshot = { mapOf(dev.zakocoder.app.capabilities.CapabilityPackage.Screen to "screen-package-test") },
            screenTestTool = { id, args, _ -> execute(id, args) }, screenTestLease = lease,
            finishScreenTestTask = { id, token -> if (finishSession != null) finishSession(id, token) else finish(id) }, stopScreenTest = stop, shareScreenTestImages = { share })
    }
    private fun call(action: String) = AiCall("screen-$action", "android_test", JSONObject().put("action", action).put("reason", "fixture screen verification").toString())
    private fun urls(body: JSONObject): List<String> {
        val messages = body.getJSONArray("messages")
        return (0 until messages.length()).flatMap { i ->
            val content = messages.getJSONObject(i).optJSONArray("content") ?: JSONArray()
            (0 until content.length()).map(content::getJSONObject).filter { it.optString("type") == "image_url" }
                .map { it.getJSONObject("image_url").getString("url") }
        }
    }
    private fun image(directory: File): Uri {
        val file = File(directory, "capture.png")
        val bitmap = Bitmap.createBitmap(30, 50, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(0xff205080.toInt()); file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        return FileProvider.getUriForFile(base, "${base.packageName}.apk-files", file)
    }

    @Test(timeout = 120000) fun denialCannotReachScreenExecutorEvenUnderUnrestrictedPolicy() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            var executed = 0
            lateinit var ai: AiController
            main { ai = controller(f, { _, _ -> executed++; ScreenTestResult("unexpected") }); ai.select("screen-deny") }; ready(ai)
            main { ai.setPolicy(AiPolicy(AiPermission.Allow, mapOf(AiTool.AndroidTest to AiPermission.Deny))) }
            await { !ai.state.value.permissionsSaving }
            f.enqueue(calls = listOf(call("launch"))); f.enqueue("Permission was denied")
            main { assertTrue(ai.send("Test this app")) }; await { !ai.state.value.active }
            assertEquals(0, executed)
            assertEquals(AiToolStatus.Denied, ai.state.value.events.single().status)
            assertEquals(AiToolStatus.Denied, f.history.load("screen-deny").state!!.events.single().status)
        }
    }

    @Test(timeout = 120000) fun allowPolicyStillAsksAndProjectCannotSwitchDuringScreenAction() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val owners = mutableListOf<String>(); val finished = mutableListOf<String>()
            lateinit var ai: AiController
            main { ai = controller(f, { id, _ -> owners += id; ScreenTestResult("verified status") }, { finished += it }); ai.select("screen-a") }; ready(ai)
            main { ai.setPolicy(AiPolicy(AiPermission.Allow, emptyMap())) }; await { !ai.state.value.permissionsSaving }
            f.enqueue(calls = listOf(call("status"))); f.enqueue("Done")
            main { assertTrue(ai.send("Inspect test status")) }; await { ai.state.value.approval != null }
            assertTrue(owners.isEmpty())
            main { ai.select("screen-b") }; assertEquals("screen-a", ai.state.value.projectId)
            main { ai.approve(AiDecision.Once) }; await { !ai.state.value.active }
            assertEquals(listOf("screen-a"), owners); assertTrue("Status alone must not own or revoke a selected test session", finished.isEmpty())
            assertEquals(AiToolStatus.Succeeded, ai.state.value.events.single().status)
        }
    }

    @Test(timeout = 120000) fun stopCancelsInFlightActionAndPersistsCancellationBeforeNewTask() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val entered = CompletableDeferred<Unit>(); var revoked = 0; var completed = 0
            lateinit var ai: AiController
            main { ai = controller(f, { _, _ -> entered.complete(Unit); awaitCancellation() }, { completed++ }, { revoked++ }); ai.select("screen-stop") }; ready(ai)
            revoked = 0
            f.enqueue(calls = listOf(call("observe")))
            main { assertTrue(ai.send("Observe app")) }; await { ai.state.value.approval != null }
            main { ai.approve(AiDecision.TaskTool) }; withTimeout(45000) { entered.await() }
            main { ai.stop() }; await { !ai.state.value.active }
            assertTrue(revoked > 0); assertEquals(1, completed)
            assertEquals(AiToolStatus.Cancelled, ai.state.value.events.single().status)
            assertEquals(AiToolStatus.Cancelled, f.history.load("screen-stop").state!!.events.single().status)
            f.enqueue(calls = listOf(call("status"))); f.enqueue("Rejected")
            main { assertTrue(ai.send("Inspect again")) }; await { ai.state.value.approval != null }
            main { ai.approve(AiDecision.Reject) }; await { !ai.state.value.active }
            assertEquals(AiToolStatus.Denied, ai.state.value.events.last().status)
        }
    }

    @Test(timeout = 120000) fun screenshotPixelsReachNextModelRequestOnlyWithUserSharingAndNeverLogsOrHistory() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val source = File(base.cacheDir, "apk-install/${f.token}-screen-test").apply { mkdirs() }
            try {
                val uri = image(source)
                lateinit var ai: AiController
                main { ai = controller(f, { _, _ -> ScreenTestResult("Captured report screenshot", uri) }, share = true); ai.select("screen-vision") }; ready(ai)
                f.enqueue(calls = listOf(call("screenshot"))); f.enqueue("Actual screenshot received")
                main { assertTrue(ai.send("Inspect screenshot")) }; await { ai.state.value.approval != null }
                main { ai.approve(AiDecision.Once) }; await { !ai.state.value.active }
                f.server.takeRequest(2, TimeUnit.SECONDS)
                val wire = JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
                val url = urls(wire).single(); assertTrue(url.startsWith("data:image/jpeg;base64,"))
                assertTrue(ai.state.value.requests.all { !it.request.contains("data:image") && !it.request.contains(url.substringAfter(',')) })
                val saved = f.history.load("screen-vision").state!!
                assertTrue(saved.contextTurns.none { it.contains("zako_images") || it.contains("data:image") })
                assertTrue(File(f.root, "controller-private/ai-images-v1").listFiles().orEmpty().isEmpty())
                f.enqueue("No stale screenshot pixels")
                main { assertTrue(ai.send("Continue without recapturing")) }; await { !ai.state.value.active }
                assertTrue(urls(JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())).isEmpty())
            } finally { source.deleteRecursively() }
        }
    }

    @Test(timeout = 120000) fun taskGrantCoversOneBatchButScreenshotRetentionIsBoundedAndNextTaskAsksAgain() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val source = File(base.cacheDir, "apk-install/${f.token}-screen-bounded").apply { mkdirs() }
            try {
                val uri = image(source); var captures = 0
                lateinit var ai: AiController
                main { ai = controller(f, { _, _ -> captures++; ScreenTestResult("Capture $captures", uri) }, share = true); ai.select("screen-bounded") }; ready(ai)
                f.enqueue(calls = (1..3).map { call("screenshot").copy(id = "capture-$it") }); f.enqueue("Latest captures inspected")
                main { assertTrue(ai.send("Capture three states")) }; await { ai.state.value.approval != null }
                main { ai.approve(AiDecision.TaskTool) }; await { !ai.state.value.active }
                assertEquals(3, captures)
                f.server.takeRequest(2, TimeUnit.SECONDS)
                assertEquals(2, urls(JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())).size)
                assertTrue(File(f.root, "controller-private/ai-images-v1").listFiles().orEmpty().isEmpty())
                f.enqueue(calls = listOf(call("status"))); f.enqueue("Rejected")
                main { assertTrue(ai.send("New task")) }; await { ai.state.value.approval != null }
                assertEquals(3, captures)
                main { ai.approve(AiDecision.Reject) }; await { !ai.state.value.active }
                assertEquals(3, captures)
            } finally { source.deleteRecursively() }
        }
    }

    @Test(timeout = 120000) fun unconfirmedModelReceivesExplicitTextWithoutScreenshotPixels() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val source = File(base.cacheDir, "apk-install/${f.token}-screen-unshared").apply { mkdirs() }
            try {
                val uri = image(source)
                lateinit var ai: AiController
                main { ai = controller(f, { _, _ -> ScreenTestResult("Report screenshot saved", uri) }); ai.select("screen-text") }; ready(ai)
                f.enqueue(calls = listOf(call("screenshot"))); f.enqueue("Pixels not inspected")
                main { assertTrue(ai.send("Capture screenshot")) }; await { ai.state.value.approval != null }
                main { ai.approve(AiDecision.Once) }; await { !ai.state.value.active }
                f.server.takeRequest(2, TimeUnit.SECONDS)
                val wire = JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
                assertTrue(urls(wire).isEmpty())
                assertTrue(ai.state.value.events.single().output.contains(base.getString(R.string.ai_screen_test_image_disabled)))
            } finally { source.deleteRecursively() }
        }
    }

    @Test(timeout = 120000) fun oldTaskCannotFollowOrRevokeUsersReplacementTestSession() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            var selectedLease = "first-user-selection"
            var executed = 0
            val finishedLeases = mutableListOf<String?>()
            lateinit var ai: AiController
            main {
                ai = controller(f, { _, _ ->
                    executed++
                    // A new explicit user selection while the model is between tool batches.
                    selectedLease = "replacement-user-selection"
                    ScreenTestResult("First target observed")
                }, lease = { selectedLease }, finishSession = { _, lease ->
                    finishedLeases += lease
                    if (selectedLease == lease) selectedLease = "stopped"
                })
                ai.select("screen-lease")
            }; ready(ai)
            f.enqueue(calls = listOf(call("observe")))
            f.enqueue(calls = listOf(call("observe").copy(id = "observe-new-target")))
            f.enqueue("Target changed; new session was not controlled")
            main { assertTrue(ai.send("Observe the selected app twice")) }; await { ai.state.value.approval != null }
            main { ai.approve(AiDecision.TaskTool) }; await { !ai.state.value.active }
            assertEquals(1, executed)
            assertEquals(AiToolStatus.Failed, ai.state.value.events.last().status)
            assertTrue(ai.state.value.events.last().output.contains(base.getString(R.string.ai_screen_test_session_changed)))
            assertEquals(listOf("first-user-selection"), finishedLeases)
            assertEquals("replacement-user-selection", selectedLease)
        }
    }

    @Test(timeout = 120000) fun taskWithoutScreenActionsDoesNotOwnOrStopSelectedTestSession() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            var finished = 0
            lateinit var ai: AiController
            main { ai = controller(f, { _, _ -> error("No screen action expected") }, { finished++ }); ai.select("screen-unrelated") }; ready(ai)
            f.enqueue("The source-only explanation is complete")
            main { assertTrue(ai.send("Explain this project without testing")) }; await { !ai.state.value.active }
            assertEquals(0, finished)
        }
    }

    @Test(timeout = 120000) fun replacementSessionsScreenshotConsentCannotSendOldCapture() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            val source = File(base.cacheDir, "apk-install/${f.token}-screen-replaced").apply { mkdirs() }
            try {
                val uri = image(source)
                var selectedLease = "captured-session"
                lateinit var ai: AiController
                main {
                    ai = controller(f, { _, _ ->
                        // The user replaces the target and opts in for that new target while
                        // the old asynchronous screenshot is returning.
                        selectedLease = "new-opted-in-session"
                        ScreenTestResult("Old session screenshot captured", uri)
                    }, share = true, lease = { selectedLease })
                    ai.select("screen-capture-replaced")
                }; ready(ai)
                f.enqueue(calls = listOf(call("screenshot"))); f.enqueue("Old pixels were not supplied")
                main { assertTrue(ai.send("Inspect selected screen")) }; await { ai.state.value.approval != null }
                main { ai.approve(AiDecision.Once) }; await { !ai.state.value.active }
                f.server.takeRequest(2, TimeUnit.SECONDS)
                assertTrue(urls(JSONObject(f.server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())).isEmpty())
                assertTrue(ai.state.value.events.single().output.contains(base.getString(R.string.ai_screen_test_image_disabled)))
                assertEquals("new-opted-in-session", selectedLease)
            } finally { source.deleteRecursively() }
        }
    }

    @Test(timeout = 120000) fun approvalForPreviousSelectionCannotAuthorizeReplacementTarget() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            var selectedLease = "shown-in-approval"
            var executed = 0
            var finished = 0
            lateinit var ai: AiController
            main {
                ai = controller(f, { _, _ -> executed++; ScreenTestResult("unexpected") }, { finished++ }, lease = { selectedLease })
                ai.select("screen-approval-replaced")
            }; ready(ai)
            f.enqueue(calls = listOf(call("observe"))); f.enqueue("Selection changed before approval")
            main { assertTrue(ai.send("Inspect the app")) }; await { ai.state.value.approval != null }
            main { selectedLease = "replacement-after-preview"; ai.approve(AiDecision.Once) }
            await { !ai.state.value.active }
            assertEquals(0, executed)
            assertEquals(0, finished)
            assertEquals(AiToolStatus.Failed, ai.state.value.events.single().status)
            assertEquals("replacement-after-preview", selectedLease)
        }
    }

    @Test(timeout = 120000) fun targetAuthorizedInSettingsSurvivesFirstOpeningOfSameProjectAssistant() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            // This selection exists before the AI controller and before ai.select is ever called,
            // as when the user authorizes an APK in Settings then opens its assistant for the first time.
            var selectedProject: String? = "settings-selected-project"
            val selectionLease = "explicit-settings-selection"
            var stopped = 0
            var executed = 0
            lateinit var ai: AiController
            main {
                ai = controller(f, { id, _ ->
                    assertEquals(selectedProject, id)
                    executed++
                    ScreenTestResult("Settings target observed")
                }, stop = { stopped++; selectedProject = null },
                    lease = { id -> selectionLease.takeIf { selectedProject == id } })
                ai.select("settings-selected-project")
            }; ready(ai)
            assertEquals(0, stopped)
            assertEquals("settings-selected-project", selectedProject)
            f.enqueue(calls = listOf(call("observe"))); f.enqueue("Authorized target verified")
            main { assertTrue(ai.send("Test the app already selected in Settings")) }; await { ai.state.value.approval != null }
            main { ai.approve(AiDecision.Once) }; await { !ai.state.value.active }
            assertEquals(1, executed)
        }
    }

    @Test(timeout = 120000) fun switchingAssistantToUnrelatedProjectOrDeselectingRevokesSettingsTarget() = runBlocking {
        AiDeviceTest.Fixture(base).use { f ->
            var selectedProject: String? = "settings-project-a"
            var stopped = 0
            lateinit var ai: AiController
            main {
                ai = controller(f, { _, _ -> error("No screen execution expected") },
                    stop = { stopped++; selectedProject = null }, lease = { id -> "settings-lease".takeIf { id == selectedProject } })
                ai.select("settings-project-a")
            }; ready(ai)
            assertEquals(0, stopped)
            main { ai.select("unrelated-project-b") }; ready(ai)
            assertEquals(1, stopped); assertNull(selectedProject)
            main { selectedProject = "unrelated-project-b"; ai.select(null) }
            assertEquals(2, stopped); assertNull(selectedProject)
        }
    }
}
