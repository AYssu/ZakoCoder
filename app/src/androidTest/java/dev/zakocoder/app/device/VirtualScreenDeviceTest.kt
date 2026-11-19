package dev.zakocoder.app.device

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** No UiAutomator/getUiAutomation: the Root backend owns the one automation connection. */
class VirtualScreenDeviceTest {
    @Test(timeout = 150_000) fun rootDisplayCompletesInputWithoutMovingPhysicalActivityAndRevokesOnBasic() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val production = instrumentation.targetContext
        val prefix = "virtual-screen-${UUID.randomUUID()}-"
        val isolated = object : ContextWrapper(production) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = production.getSharedPreferences(prefix + name, mode)
        }
        // Test preferences alone select Root; do not grant/revoke unrelated production app permissions.
        isolated.getSharedPreferences("device_access", Context.MODE_PRIVATE).edit().putString("mode", "root").commit()
        val access = DeviceAccessController(isolated)
        val screen = VirtualScreenController(isolated, access)
        try {
            withTimeout(10_000) { access.state.first { it.loaded } }
            assertTrue("A real Root authorization is required on the owned test device", access.ensureRoot())
            val physicalBefore = physicalActivity(access)
            screen.start(720, 1600, 320)
            val id = screen.state.value.displayId!!
            assertTrue(id > 0)
            screen.launch(instrumentation.context.packageName)
            assertEquals(physicalBefore, physicalActivity(access))
            suspend fun observe(): JSONObject = JSONObject(screen.execute("", JSONObject().put("action", "observe")).output)
            suspend fun waitForText(text: String): JSONObject {
                android.util.Log.i("ZakoVirtualTest", "wait=$text")
                return withTimeout(8000) {
                while (true) {
                    val observed = observe()
                    if (observed.getJSONArray("nodes").let { nodes -> (0 until nodes.length()).any { nodes.getJSONObject(it).optString("text").contains(text) } }) return@withTimeout observed
                    delay(120)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
                }
            }
            suspend fun clickText(text: String) {
                val observed = waitForText(text)
                val nodes = observed.getJSONArray("nodes")
                val node = (0 until nodes.length()).map(nodes::getJSONObject).first { it.optString("text") == text }
                screen.execute("", JSONObject().put("action", "click").put("snapshot", observed.getString("snapshot")).put("node", node.getString("id")))
            }
            waitForText("Virtual screen fixture")
            val firstPng = screen.capture()
            java.io.File(production.filesDir, "ui-review/device-root/virtual-fixture.png").apply { parentFile!!.mkdirs(); writeBytes(firstPng) }
            val first = BitmapFactory.decodeByteArray(firstPng, 0, firstPng.size)
            assertEquals(720, first.width); assertEquals(1600, first.height); first.recycle()
            clickText("Increment")
            waitForText("Count: 1")
            assertFalse(firstPng.contentEquals(screen.capture()))
            var observed = observe()
            val input = observed.getJSONArray("nodes").let { nodes -> (0 until nodes.length()).map(nodes::getJSONObject).first { it.optBoolean("editable") } }
            screen.execute("", JSONObject().put("action", "click").put("snapshot", observed.getString("snapshot")).put("node", input.getString("id")))
            observed = observe()
            val focused = observed.getJSONArray("nodes").let { nodes -> (0 until nodes.length()).map(nodes::getJSONObject).first { it.optBoolean("editable") && it.optBoolean("focused") } }
            screen.execute("", JSONObject().put("action", "input_text").put("snapshot", observed.getString("snapshot")).put("node", focused.getString("id")).put("text", "Root 测试 ✓"))
            waitForText("Root 测试 ✓")
            assertTrue(JSONObject(screen.execute("", JSONObject().put("action", "assert_text").put("text", "Root 测试 ✓")).output).getBoolean("verified"))
            clickText("Next page")
            waitForText("Second virtual page")
            screen.execute("", JSONObject().put("action", "back"))
            waitForText("Virtual screen fixture")
            assertEquals(physicalBefore, physicalActivity(access))
            access.useBasic()
            withTimeout(10_000) { screen.state.first { !it.active } }
            try {
                screen.execute("", JSONObject().put("action", "observe"))
                fail("Revoked display must not accept observations")
            } catch (_: IllegalStateException) { /* expected */ }
            withTimeout(15_000) {
                val displays = production.getSystemService(android.hardware.display.DisplayManager::class.java)
                while (displays.getDisplay(id) != null) delay(100)
            }
        } finally {
            screen.close()
            access.stop()
            production.deleteSharedPreferences(prefix + "device_access")
        }
    }

    private suspend fun physicalActivity(access: DeviceAccessController): String {
        val output = access.execute("/system/bin/dumpsys activity activities", 20).stdout
        val displays = Regex("(?m)^\\s*Display #(\\d+)").findAll(output).toList()
        val index = displays.indexOfFirst { it.groupValues[1] == "0" }
        check(index >= 0) { "physical_display_activity_not_available" }
        val section = output.substring(displays[index].range.first, displays.getOrNull(index + 1)?.range?.first ?: output.length)
        return Regex("(?:mResumedActivity|topResumedActivity|ResumedActivity):?[^\\n]*?([A-Za-z][A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)").find(section)?.groupValues?.get(1)
            ?: error("physical_display_activity_not_available")
    }
}

/** Shipped only in androidTest APK, with no storage/network or personal data. */
class VirtualScreenFixtureActivity : Activity() {
    private var count = 0
    private var second = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        showPage()
    }
    private fun showPage() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 32)
            setBackgroundColor(Color.WHITE)
        }
        fun label(text: String) = TextView(this).apply { this.text = text; textSize = 22f; setTextColor(Color.BLACK) }
        if (second) layout.addView(label("Second virtual page")) else {
            layout.addView(label("Virtual screen fixture"))
            val counter = label("Count: $count").also(layout::addView)
            layout.addView(Button(this).apply { text = "Increment"; isAllCaps = false; setOnClickListener { count++; counter.text = "Count: $count" } })
            layout.addView(EditText(this).apply {
                id = View.generateViewId(); hint = "Fixture input"; contentDescription = "Fixture input"
                setTextColor(Color.BLACK); setHintTextColor(Color.DKGRAY); showSoftInputOnFocus = false
            })
            layout.addView(Button(this).apply { text = "Next page"; isAllCaps = false; setOnClickListener { second = true; showPage() } })
        }
        setContentView(layout)
    }
    @Deprecated("Test fixture deliberately exercises display-specific BACK key input")
    override fun onBackPressed() { if (second) { second = false; showPage() } else super.onBackPressed() }
}
