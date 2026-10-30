package dev.zakocoder.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.screentest.RootScreenTestBackend
import dev.zakocoder.app.screentest.ScreenTestException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Requires the disposable screen-app fixture and an explicitly approved test-host root grant. */
class ScreenTestRootDeviceTest {
    @Test fun androidIcuParsesWindowFramesAndRejectsCoveringWindows() {
        val target = """
            mCurrentFocus=Window{target u0 dev.example.fixture/.MainActivity}
            Window #1 Window{target u0 dev.example.fixture/.MainActivity}:
              mHasSurface=true
              isOnScreen=true
              isVisible=true
              Frames: frame=[0,0][1080,2400]
        """.trimIndent()
        assertTrue(RootScreenTestBackend.isUncoveredWindowRegion(target, "dev.example.fixture", 100, 100, 300, 300))
        val overlay = """
            Window #0 Window{overlay u0 dev.example.overlay/.MainActivity}:
              mHasSurface=true
              isOnScreen=true
              isVisible=true
              Frames: frame=[0,0][1080,2400]
        """.trimIndent()
        assertFalse(RootScreenTestBackend.isUncoveredWindowRegion(overlay + "\n" + target,
            "dev.example.fixture", 100, 100, 300, 300))
    }

    @Test fun rootControlsOnlySelectedForegroundAndVerifiesRealUi() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Match the real entry path: the user starts a test from the foreground host.
        instrumentation.runOnMainSync {
            context.startActivity(android.content.Intent(context, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        kotlinx.coroutines.delay(1000)
        val backend = RootScreenTestBackend(context)
        try {
            assertTrue("Approve root for the isolated test host to run this integration test", backend.requestAccess())
            fun shell(command: String): String {
                val child = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                check(child.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
                return child.inputStream.bufferedReader().use { it.readText() }
            }
            shell("am force-stop dev.zakocoder.screentest.fixture")
            shell("am start -W -n dev.zakocoder.screentest.fixture/.MainActivity")
            kotlinx.coroutines.delay(1000)
            suspend fun call(action: String, extra: (JSONObject) -> Unit = {}): JSONObject {
                val args = JSONObject().put("action", action); extra(args)
                return JSONObject(backend.execute("dev.zakocoder.screentest.fixture", args).output)
            }
            val initial = call("observe")
            val nodes = initial.getJSONArray("nodes")
            val button = (0 until nodes.length()).map(nodes::getJSONObject).first { it.optString("text") == "Increment" }
            call("click") { it.put("snapshot", initial.getString("snapshot")).put("node", button.getString("id")) }
            kotlinx.coroutines.delay(500)
            assertEquals("passed", call("assert_text") { it.put("text", "Counter: 1") }.getString("assertion"))
            try {
                call("assert_text") { it.put("text", "Counter: 999") }
                fail("A failed assertion must not pass")
            } catch (_: ScreenTestException) { }
            try {
                call("click") { it.put("snapshot", initial.getString("snapshot")).put("node", button.getString("id")) }
                fail("Consumed snapshots must be rejected")
            } catch (_: ScreenTestException) { }
            shell("input keyevent KEYCODE_HOME")
            kotlinx.coroutines.delay(500)
            try { call("observe"); fail("Other apps cannot be observed") } catch (_: ScreenTestException) { }
        } finally { backend.stop() }
    }
}
