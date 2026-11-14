package dev.zakocoder.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.graphics.Rect
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.browser.BrowserController
import dev.zakocoder.app.browser.BrowserFailure
import dev.zakocoder.app.ui.AgentBrowserPage
import dev.zakocoder.app.ui.WorkbenchMiuixTheme
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Actual Compose browser chrome + real WebView/local HTTP, without model settings or user pages. */
class AgentBrowserUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val composeUi = dev.zakocoder.app.ui.UpgradeUiHarness()
    @Before fun foreground() = warmDeviceActivity()
    @Test(timeout = 90_000) fun dayNativeBrowserFormSharingAndBusyChrome(): Unit = runBlocking { exercise(false) }
    @Test(timeout = 90_000) fun nightNativeBrowserFormSharingAndBusyChrome(): Unit = runBlocking { exercise(true) }

    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(label: String, condition: () -> Boolean) {
        try { withTimeout(20_000) { while (!condition()) delay(50) } }
        catch (e: TimeoutCancellationException) { throw AssertionError(label, e) }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        fun walk(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            if (node.isVisibleToUser) add(node)
            repeat(node.childCount) { node.getChild(it)?.let { addAll(walk(it)) } }
        }
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == context.packageName }.flatMap(::walk)
    }
    private fun find(label: String) = nodes().firstOrNull {
        it.text?.toString()?.lines()?.contains(label) == true || it.contentDescription?.toString()?.lines()?.contains(label) == true
    }
    private suspend fun click(label: String) {
        fun candidate(): AccessibilityNodeInfo? {
            val matches = nodes().filter {
                it.text?.toString()?.lines()?.contains(label) == true || it.contentDescription?.toString()?.lines()?.contains(label) == true
            }
            fun descendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isEnabled && node.isClickable) return node
                return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::descendant) }
            }
            matches.firstNotNullOfOrNull(::descendant)?.let { return it }
            return matches.firstNotNullOfOrNull { match ->
                var ancestor = match.parent
                while (ancestor != null && (!ancestor.isClickable || !ancestor.isEnabled)) ancestor = ancestor.parent
                ancestor
            }
        }
        // Miuix's Switch handles pointer input without exposing ACTION_CLICK on some versions.
        // Prefer an accessibility action; otherwise exercise the labelled hit target with real input.
        fun pointerTarget() = nodes().lastOrNull {
            it.isEnabled && it.contentDescription?.toString()?.lines()?.contains(label) == true
        }
        await("clickable or pointer target $label") { candidate() != null || pointerTarget() != null }
        val clickable = candidate()
        if (clickable != null) assertTrue(label, clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        else {
            val bounds = Rect().also { requireNotNull(pointerTarget()).getBoundsInScreen(it) }
            assertFalse("Labelled hit target must be visible", bounds.isEmpty)
            val time = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            val up = MotionEvent.obtain(time, time + 40, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try {
                assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true))
                assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true))
            } finally { down.recycle(); up.recycle() }
        }
    }
    /** Accessibility existence is insufficient: an overdrawn control still has semantics. */
    private fun assertEmptyChromeVisible(bitmap: Bitmap) {
        fun bounds(id: Int): Rect = Rect().also { result ->
            requireNotNull(find(context.getString(id))) { "Missing browser control: ${context.getString(id)}" }
                .getBoundsInScreen(result)
            assertFalse("Browser control must have screen bounds", result.isEmpty)
            assertTrue("Browser control must fit the screenshot", result.left >= 0 && result.top >= 0 &&
                result.right <= bitmap.width && result.bottom <= bitmap.height)
        }
        val title = bounds(R.string.browser_title)
        val address = bounds(R.string.browser_address)
        val empty = bounds(R.string.browser_start_title)
        assertTrue("The address field must follow the native header", title.bottom <= address.top)
        assertTrue("The landing state must remain below the address controls", address.bottom < empty.top)
        listOf(title, address).forEach { rect ->
            var darkest = 255
            var lightest = 0
            for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                val pixel = bitmap.getPixel(x, y)
                val lightness = (android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) +
                    android.graphics.Color.blue(pixel)) / 3
                darkest = minOf(darkest, lightness)
                lightest = maxOf(lightest, lightness)
            }
            assertTrue("Native browser header/address must render visible text, not be covered by a solid landing layer", lightest - darkest > 50)
        }
    }
    private suspend fun screenshot(name: String, verifyEmptyChrome: Boolean = false) {
        delay(250)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { withContext(Dispatchers.IO) {
            File(context.cacheDir, "ui-review/browser/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
            if (verifyEmptyChrome) assertEmptyChromeVisible(bitmap)
        } finally { bitmap.recycle() }
    }
    private suspend fun exercise(dark: Boolean) {
        val previousFlags = instrumentation.uiAutomation.serviceInfo.flags
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = BrowserController(context, scope)
        val showing = mutableStateOf(true)
        val theme = if (dark) "night" else "day"
        try {
            AgentBrowserDeviceTest.LocalSite().use { site ->
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        composeUi.configureSystemBars(activity, dark)
                        activity.setContent {
                        WorkbenchMiuixTheme(dark) {
                            if (showing.value) AgentBrowserPage(controller, onBack = { showing.value = false })
                        }
                    } }
                    try {
                        controller.waitUntilAttached()
                        await("native empty state before navigation") { composeUi.has(R.string.browser_start_title) }
                        assertEquals("Opening the preview must not navigate to a guessed service", "", controller.state.value.url)
                        assertFalse(controller.state.value.busy)
                        assertFalse(controller.state.value.loading)
                        assertEquals(0, site.count("/"))
                        screenshot("$theme-empty", verifyEmptyChrome = true)
                        composeUi.setField(R.string.browser_address, site.url)
                        click(context.getString(R.string.browser_go))
                        await("local page ready") { controller.state.value.url == site.url && !controller.state.value.busy && !controller.state.value.loading }
                        assertTrue(controller.execute("read", JSONObject()).getString("text").contains("Preview ready"))
                        assertFalse(controller.state.value.shareImages)
                        composeUi.click(R.string.browser_share_images)
                        await("explicit image opt-in") { controller.state.value.shareImages }
                        screenshot("$theme-ready")
                        controller.execute("type", JSONObject().put("selector", "#name").put("text", "Native browser"))
                        assertTrue(controller.execute("click", JSONObject().put("selector", "#save").put("wait_text", "Saved Native browser")).getBoolean("verified"))
                        screenshot("$theme-form-verified")
                        supervisorScope {
                            val external = async { controller.execute("navigate", JSONObject().put("url", "https://example.invalid/")) }
                            await("external origin confirmation above the actual browser window") {
                                controller.state.value.pendingOriginRequestId != null &&
                                    composeUi.has(R.string.browser_allow) && composeUi.has(R.string.browser_deny)
                            }
                            screenshot("$theme-external-origin-confirmation")
                            composeUi.click(R.string.browser_deny)
                            try { external.await(); fail("Current origin denial must fail navigation") }
                            catch (e: BrowserFailure) { assertEquals("BROWSER_ORIGIN_DENIED", e.code) }
                        }
                        assertTrue(controller.state.value.attached)
                        assertEquals(site.url, controller.state.value.url)
                        assertNull(controller.state.value.pendingOriginRequestId)
                        assertTrue(controller.execute("read", JSONObject().put("selector", "#result")).getString("text").contains("Saved Native browser"))
                        val pending = CoroutineScope(currentCoroutineContext()).async {
                            controller.execute("wait", JSONObject().put("text", "NOT_IN_FIXTURE").put("timeout_ms", 30_000))
                        }
                        await("actual current-action chrome") {
                            controller.state.value.busy && find(context.getString(R.string.browser_action_wait)) != null
                        }
                        screenshot("$theme-waiting")
                        click(context.getString(R.string.browser_stop))
                        try { pending.await(); fail("UI stop must cancel the real operation") } catch (_: CancellationException) { }
                        await("idle after UI stop") { !controller.state.value.busy }
                        composeUi.click(R.string.browser_share_images)
                        await("explicit image opt-out") { !controller.state.value.shareImages }
                        // Assistant navigation changes the shown URL without editing the field.
                        // The Go button must submit that URL, not the previous typed draft.
                        val assistantUrl = site.url + "?from=assistant"
                        controller.execute("navigate", JSONObject().put("url", assistantUrl))
                        click(context.getString(R.string.browser_go))
                        await("Go uses the currently displayed address") {
                            site.count("/?from=assistant") >= 2 && !controller.state.value.busy && !controller.state.value.loading
                        }
                        assertEquals(assistantUrl, controller.state.value.url)
                        click(context.getString(R.string.close))
                        await("page disposal destroys the session") { !controller.state.value.attached && !showing.value }
                        main { showing.value = true }
                        controller.waitUntilAttached()
                        await("reopened browser shows the native landing state") { composeUi.has(R.string.browser_start_title) }
                        assertEquals("", controller.state.value.url)
                        assertFalse(controller.state.value.shareImages)
                        screenshot("$theme-reopened-empty", verifyEmptyChrome = true)
                    } catch (failure: Throwable) {
                        try { screenshot("$theme-failure") } catch (_: Throwable) { }
                        throw failure
                    } finally { main { showing.value = false; controller.close() } }
                }
            }
        } finally {
            scope.cancel()
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { flags = previousFlags }
        }
    }
}
