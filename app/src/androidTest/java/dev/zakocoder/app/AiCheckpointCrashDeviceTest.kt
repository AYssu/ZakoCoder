package dev.zakocoder.app

import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.AiCheckpoints
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in two-phase proof. Host force-stops while a real guest command is still running. */
class AiCheckpointCrashDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base get() = File(context.cacheDir, "checkpoint-crash-proof")
    private fun enabled() = org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("checkpointCrash") == "true")
    @Test(timeout = 180000) fun prepareUnsealedCommandAndWaitForHostKill(): Unit = runBlocking {
        enabled()
        ActivityScenario.launch(MainActivity::class.java).use {
            val app = (context.applicationContext as ZakoApplication).workbench
            withTimeout(20000) { while (app.state.value.loading || app.state.value.busy) delay(50) }
            assertTrue(app.state.value.installed)
            withContext(Dispatchers.IO) {
                check(!base.exists()); check(base.mkdir())
                val id = UUID.randomUUID().toString()
                val root = File(app.runtime.workspaces, id).apply { check(mkdir()) }
                File(root, "a.txt").writeText("original")
                val cp = AiCheckpoints(File(base, "snapshots")) { root }
                val key = cp.begin(id, "crash-source", 1)
                File(base, "proof.json").writeText(JSONObject().put("id", id).put("key", key).put("pid", Process.myPid()).toString())
                try {
                    app.runtime.execute("printf 'guest changed' > a.txt; printf 'ready' > ready.flag; sleep 120", id, app.state.value.settings, timeoutMillis = 150000)
                    error("Host must kill while command runs")
                } finally { cp.update(id, key, true) }
            }
        }
    }
    @Test(timeout = 60000) fun verifyActualProcessDeathRequiresReviewAndClean() = runBlocking {
        enabled()
        ActivityScenario.launch(MainActivity::class.java).use {
            val app = (context.applicationContext as ZakoApplication).workbench
            withTimeout(20000) { while (app.state.value.loading || app.state.value.busy) delay(50) }
            withContext(Dispatchers.IO) {
                val proof = JSONObject(File(base, "proof.json").readText())
                val id = proof.getString("id"); require(UUID.fromString(id).toString() == id)
                val root = File(app.runtime.workspaces, id)
                try {
                    assertNotEquals(proof.getInt("pid"), Process.myPid())
                    val cp = AiCheckpoints(File(base, "snapshots")) { root }
                    val key = proof.getString("key")
                    assertFalse(cp.list(id).single().sealed)
                    assertEquals("guest changed", File(root, "a.txt").readText())
                    assertTrue(runCatching { cp.restore(id, key) { false } }.isFailure)
                    val review = cp.reviewInterrupted(id, key)
                    assertTrue(review.changes.any { it.path == "a.txt" && it.after == "guest changed" })
                    File(root, "a.txt").writeText("user after review")
                    assertTrue(runCatching { cp.restore(id, key, review) { false } }.isFailure)
                    assertEquals("user after review", File(root, "a.txt").readText())
                    assertEquals(2, cp.restore(id, key, cp.reviewInterrupted(id, key)) { false })
                    assertEquals("original", File(root, "a.txt").readText())
                    assertFalse(File(root, "ready.flag").exists())
                    assertEquals("completed", cp.workspaceRestorations(id).single().status)
                } finally { root.deleteRecursively(); base.deleteRecursively() }
            }
        }
    }
}
