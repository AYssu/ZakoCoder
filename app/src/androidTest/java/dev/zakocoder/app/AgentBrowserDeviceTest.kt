package dev.zakocoder.app

import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.browser.BrowserController
import dev.zakocoder.app.browser.BrowserFailure
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Real WebView and local HTTP only. Does not read model credentials, user projects or existing pages. */
class AgentBrowserDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(40) }
    private fun args(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }
    private fun BrowserController.answerPending(allow: Boolean): Boolean {
        val pending = state.value
        return confirmOrigin(requireNotNull(pending.pendingOrigin), requireNotNull(pending.pendingOriginRequestId), allow)
    }

    internal class LocalSite(
        host: String = "127.0.0.1",
        private val pageFor: ((String) -> String)? = null,
        private val redirectFor: ((String) -> String?)? = null,
    ) : Closeable {
        private val server = ServerSocket(0, 10, InetAddress.getByName(host))
        private val worker = Executors.newSingleThreadExecutor()
        val requests = ConcurrentHashMap<String, AtomicInteger>()
        val delays = ConcurrentHashMap<String, Long>()
        val url = "http://$host:${server.localPort}/"
        init {
            worker.submit {
                while (!server.isClosed) try {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = socket.getInputStream().bufferedReader()
                        val path = input.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                        requests.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                        var line: String? = input.readLine()
                        while (!line.isNullOrEmpty()) line = input.readLine()
                        delays[path]?.let { Thread.sleep(it) }
                        val redirect = redirectFor?.invoke(path)
                        if (redirect != null) {
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 302 Found\r\nLocation: $redirect\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                flush()
                            }
                            return@use
                        }
                        val page = (pageFor?.invoke(path) ?: """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Browser fixture</title></head>
                            <body><h1>Preview ready</h1><input id="name" aria-label="Display name"><input id="secret" type="password" value="PRIVATE_INPUT">
                            <button id="save" onclick="document.querySelector('#result').textContent='Saved '+document.querySelector('#name').value">Save</button>
                            <p id="result">Not saved</p><button class="duplicate">One</button><button class="duplicate">Two</button>
                            <a id="outside" href="https://example.invalid/">External</a><div style="height:2000px">More content</div></body></html>""".trimIndent()).toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: ${page.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(page); flush()
                        }
                    }
                } catch (_: Exception) { if (server.isClosed) break }
            }
        }
        fun count(path: String) = requests[path]?.get() ?: 0
        override fun close() { server.close(); worker.shutdownNow() }
    }

    private suspend fun withBrowser(block: suspend (BrowserController) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = BrowserController(context, scope)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var web: WebView? = null
                scenario.onActivity { activity -> web = WebView(activity).also { view ->
                    activity.addContentView(view, ViewGroup.LayoutParams(-1, -1))
                    controller.attach(view); controller.setForeground(true)
                } }
                try { controller.waitUntilAttached(); block(controller) }
                finally { main { controller.close(); (web?.parent as? ViewGroup)?.removeView(web) } }
            }
        } finally { scope.cancel() }
    }

    @org.junit.Before fun foreground() = warmDeviceActivity()

    @Test(timeout = 90_000) fun readsEditsVerifiesCapturesAndRejectsUnavailableBrowser(): Unit = runBlocking {
        LocalSite().use { site ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val controller = BrowserController(context, scope)
            try {
                try { controller.execute("read", JSONObject()); fail("Detached browser must fail") } catch (e: BrowserFailure) { assertEquals("BROWSER_NOT_OPEN", e.code) }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    var web: WebView? = null
                    scenario.onActivity { activity ->
                        web = WebView(activity).also { view ->
                            activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            controller.attach(view); controller.setForeground(true)
                        }
                    }
                    try {
                        controller.waitUntilAttached()
                        assertEquals("verified", controller.execute("navigate", args("url" to site.url)).getString("status"))
                        val snapshot = controller.execute("read", JSONObject())
                        assertTrue(snapshot.getString("text").contains("Preview ready"))
                        assertFalse(snapshot.toString().contains("PRIVATE_INPUT"))
                        assertTrue(snapshot.getJSONArray("elements").length() >= 5)
                        assertEquals(1, controller.execute("find", args("text" to "Display name")).getInt("count"))
                        assertTrue(controller.execute("type", args("selector" to "#name", "text" to "Mobile user")).getBoolean("verified"))
                        val dispatched = controller.execute("click", args("selector" to "#save"))
                        assertEquals("dispatched", dispatched.getString("status")); assertFalse(dispatched.getBoolean("verified"))
                        assertTrue(controller.execute("wait", args("selector" to "#result", "text" to "Saved Mobile user")).getBoolean("verified"))
                        try { controller.execute("screenshot", JSONObject()); fail("Screenshot sharing defaults off") }
                        catch (e: BrowserFailure) { assertEquals("BROWSER_IMAGE_SHARING_DISABLED", e.code) }
                        main { controller.setShareImages(true) }
                        val capture = controller.execute("screenshot", JSONObject())
                        val image = File(capture.getString("image_path"))
                        assertTrue(image.isFile && image.length() > 100); image.delete()
                        try { controller.execute("click", args("selector" to ".duplicate")); fail("Ambiguous selector must fail") }
                        catch (e: BrowserFailure) { assertEquals("BROWSER_AMBIGUOUS_SELECTOR", e.code) }
                        try { controller.execute("type", args("selector" to "#secret", "text" to "do not fill")); fail("Password must not be automated") }
                        catch (e: BrowserFailure) { assertEquals("BROWSER_INPUT_UNSUPPORTED", e.code) }
                        supervisorScope {
                            val pending = async { controller.execute("navigate", args("url" to "https://example.invalid/")) }
                            await { controller.state.value.pendingOrigin != null }
                            main { controller.answerPending(false) }
                            try { pending.await(); fail("Origin denial must propagate") } catch (e: BrowserFailure) { assertEquals("BROWSER_ORIGIN_DENIED", e.code) }
                        }
                    } finally {
                        main { controller.close(); (web?.parent as? ViewGroup)?.removeView(web) }
                    }
                }
                assertFalse(controller.state.value.attached)
            } finally { scope.cancel() }
        }
    }

    @Test(timeout = 45_000) fun leavingForegroundCancelsWaitInsteadOfReportingSuccess(): Unit = runBlocking {
        LocalSite().use { site ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val controller = BrowserController(context, scope)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    var web: WebView? = null
                    scenario.onActivity { activity -> web = WebView(activity).also { view ->
                        activity.addContentView(view, ViewGroup.LayoutParams(-1, -1)); controller.attach(view); controller.setForeground(true)
                    } }
                    try {
                        controller.execute("navigate", args("url" to site.url))
                        val wait = async { controller.execute("wait", args("text" to "NEVER_APPEARS", "timeout_ms" to 30_000)) }
                        await { controller.state.value.busy }
                        main { controller.setForeground(false) }
                        try { wait.await(); fail("Leaving page must cancel") } catch (_: CancellationException) { }
                        try { controller.execute("read", JSONObject()); fail("Background browser must fail") }
                        catch (e: BrowserFailure) { assertEquals("BROWSER_NOT_FOREGROUND", e.code) }
                    } finally { main { controller.close(); (web?.parent as? ViewGroup)?.removeView(web) } }
                }
            } finally { scope.cancel() }
        }
    }
    @Test(timeout = 30_000) fun statusIsAnImmediateStateSnapshotEvenWhileDetachedOrBusy(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val detached = BrowserController(context, scope)
            val before = detached.state.value
            assertFalse(detached.execute("status", JSONObject()).getBoolean("available"))
            assertEquals(before, detached.state.value)
        } finally { scope.cancel() }
        LocalSite().use { site -> withBrowser { controller ->
            controller.execute("navigate", args("url" to site.url))
            val pending = async { controller.execute("wait", args("text" to "NEVER", "timeout_ms" to 30_000)) }
            await { controller.state.value.busy }
            val before = controller.state.value
            val result = withTimeout(1000) { controller.execute("status", JSONObject()) }
            assertTrue(result.getBoolean("busy")); assertEquals("wait", result.getString("current_action"))
            assertEquals(before, controller.state.value)
            main { controller.stop() }
            try { pending.await(); fail("Stop must cancel active wait") } catch (_: CancellationException) { }
        } }
    }

    @Test(timeout = 45_000) fun backWaitsForTheDestinationDocumentAndHandlesFragmentHistory(): Unit = runBlocking {
        LocalSite(pageFor = { path -> "<html><body><h1>Page $path</h1></body></html>" }).use { site -> withBrowser { controller ->
            controller.execute("navigate", args("url" to site.url + "first"))
            controller.execute("navigate", args("url" to site.url + "second"))
            site.delays["/first"] = 500
            val result = controller.execute("back", JSONObject())
            assertTrue(result.getBoolean("verified"))
            assertFalse(controller.state.value.loading)
            assertTrue(controller.execute("read", JSONObject()).getString("text").contains("Page /first"))
            controller.execute("navigate", args("url" to site.url + "first#fragment"))
            controller.execute("back", JSONObject())
            assertEquals(site.url + "first", controller.state.value.url)
        } }
    }

    @Test(timeout = 45_000) fun cancellationStopsLoadingAndRevokesQueuedNavigation(): Unit = runBlocking {
        LocalSite().use { site -> withBrowser { controller ->
            site.delays["/slow"] = 1500
            val loading = async { controller.execute("navigate", args("url" to site.url + "slow")) }
            await { site.count("/slow") == 1 }
            val queued = async(start = CoroutineStart.UNDISPATCHED) { controller.execute("navigate", args("url" to site.url + "queued")) }
            // Ensure the queued execute has reached the Main dispatcher and is waiting on the mutex.
            main { }
            main { controller.stop() }
            try { loading.await(); fail("Loading must be cancelled") } catch (_: CancellationException) { }
            try { queued.await(); fail("Queued action must be cancelled") } catch (_: CancellationException) { }
            delay(1800)
            assertFalse(controller.state.value.loading)
            assertEquals(0, site.count("/queued"))
            main { controller.requestNavigation(site.url + "user-queued"); controller.stop() }
            delay(150)
            assertEquals(0, site.count("/user-queued"))
        } }
    }

    @Test(timeout = 60_000) fun crossOriginClickWaitOwnsConsentAndNeverReplaysTheClick(): Unit = runBlocking {
        // 127.0.0.2 stays on-device, but intentionally is not an auto-trusted localhost origin.
        LocalSite(host = "127.0.0.2", pageFor = { "<html><body><h1>Arrived once</h1></body></html>" }).use { destination ->
            LocalSite(pageFor = { """<html><body>
                <a id="link" href="${destination.url}link" onclick="sessionStorage.clicks=Number(sessionStorage.clicks||0)+1">Go</a>
                <button id="dynamic" onclick="sessionStorage.clicks=Number(sessionStorage.clicks||0)+1;location.href='${destination.url}dynamic'">Dynamic</button>
                <p id="click-count">0</p><script>document.querySelector('#click-count').textContent=sessionStorage.clicks||0</script>
                </body></html>""" }).use { source -> withBrowser { controller ->
                controller.execute("navigate", args("url" to source.url))
                supervisorScope {
                    val denied = async { controller.execute("click", args("selector" to "#link", "wait_text" to "Arrived once")) }
                    await { controller.state.value.pendingOrigin != null }
                    assertEquals(0, destination.count("/link"))
                    main { controller.answerPending(false) }
                    try { denied.await(); fail("Denied click must fail") } catch (e: BrowserFailure) { assertEquals("BROWSER_ORIGIN_DENIED", e.code) }
                }
                assertEquals(0, destination.count("/link"))
                assertTrue(controller.execute("read", args("selector" to "#click-count")).getString("text").contains("0"))
                // This button has no href/form action: its intercepted navigation must be drained
                // by the same click+wait, rather than queued until that wait times out.
                val clicked = async { controller.execute("click", args("selector" to "#dynamic", "wait_text" to "Arrived once")) }
                await { controller.state.value.pendingOrigin != null }
                assertEquals(0, destination.count("/dynamic"))
                main { controller.answerPending(true) }
                assertTrue(clicked.await().getBoolean("verified"))
                assertEquals(1, destination.count("/dynamic"))
                controller.execute("back", JSONObject())
                assertTrue(controller.execute("read", args("selector" to "#click-count")).getString("text").contains("1"))
            } }
        }
    }

    @Test(timeout = 45_000) fun stoppedOriginConsentCannotNavigateLater(): Unit = runBlocking {
        LocalSite().use { site -> withBrowser { controller ->
            controller.execute("navigate", args("url" to site.url))
            val pending = async { controller.execute("click", args("selector" to "#outside", "wait_text" to "Never arrives")) }
            await { controller.state.value.pendingOrigin != null }
            val stopped = controller.state.value
            main {
                controller.stop()
                assertFalse(controller.confirmOrigin(requireNotNull(stopped.pendingOrigin), requireNotNull(stopped.pendingOriginRequestId), true))
            }
            try { pending.await(); fail("Stopped consent must cancel") } catch (_: CancellationException) { }
            delay(200)
            assertNull(controller.state.value.pendingOrigin)
            assertEquals(site.url, controller.state.value.url)
            assertFalse(controller.state.value.loading)
        } }
    }

    @Test(timeout = 45_000) fun originConsentRejectsOldIdsEvenWhenTheOriginIsIdentical(): Unit = runBlocking {
        LocalSite(host = "127.0.0.2").use { site -> withBrowser { controller ->
            supervisorScope {
                val old = async { controller.execute("navigate", args("url" to site.url)) }
                await { controller.state.value.pendingOriginRequestId != null }
                val stale = controller.state.value
                old.cancelAndJoin()
                assertNull(controller.state.value.pendingOriginRequestId)
                val next = async { controller.execute("navigate", args("url" to site.url)) }
                await { controller.state.value.pendingOriginRequestId != null }
                val current = controller.state.value
                assertNotEquals(stale.pendingOriginRequestId, current.pendingOriginRequestId)
                main {
                    assertFalse(controller.confirmOrigin(requireNotNull(stale.pendingOrigin), requireNotNull(stale.pendingOriginRequestId), true))
                    assertFalse(controller.confirmOrigin("https://another.example", requireNotNull(current.pendingOriginRequestId), true))
                }
                assertFalse(next.isCompleted)
                assertEquals(0, site.count("/"))
                main { assertTrue(controller.answerPending(false)) }
                try { next.await(); fail("Current denial must fail the exact request") }
                catch (e: BrowserFailure) { assertEquals("BROWSER_ORIGIN_DENIED", e.code) }
                assertNull(controller.state.value.pendingOriginRequestId)
                val accepted = async { controller.execute("navigate", args("url" to site.url)) }
                await { controller.state.value.pendingOriginRequestId != null }
                main {
                    assertFalse(controller.confirmOrigin(requireNotNull(current.pendingOrigin), requireNotNull(current.pendingOriginRequestId), true))
                    assertTrue(controller.answerPending(true))
                }
                assertTrue(accepted.await().getBoolean("verified"))
                assertEquals(1, site.count("/"))
                assertNull(controller.state.value.pendingOriginRequestId)
            }
        } }
    }

    @Test(timeout = 45_000) fun fullAccessIsOperationScopedAndDoesNotGrantManualNavigationOrScreenshots(): Unit = runBlocking {
        LocalSite(host = "127.0.0.2").use { site -> withBrowser { controller ->
            assertTrue(controller.execute("navigate", args("url" to site.url)) { true }.getBoolean("verified"))
            assertNull(controller.state.value.pendingOrigin)
            assertEquals(1, site.count("/"))
            try {
                controller.execute("screenshot", JSONObject()) { true }
                fail("Full Access must not enable screenshot sharing")
            } catch (e: BrowserFailure) { assertEquals("BROWSER_IMAGE_SHARING_DISABLED", e.code) }

            // A finished Full Access call must not leave an origin grant behind for the UI.
            main { controller.requestNavigation(site.url + "manual") }
            await { controller.state.value.pendingOrigin != null }
            assertEquals(0, site.count("/manual"))
            main { assertTrue(controller.answerPending(false)) }
            await { !controller.state.value.busy }

            supervisorScope {
                val ordinary = async { controller.execute("navigate", args("url" to site.url + "ordinary")) }
                await { controller.state.value.pendingOrigin != null }
                assertEquals(0, site.count("/ordinary"))
                main { assertTrue(controller.answerPending(false)) }
                try { ordinary.await(); fail("Ordinary mode must retain origin confirmation") }
                catch (e: BrowserFailure) { assertEquals("BROWSER_ORIGIN_DENIED", e.code) }
            }
        } }
    }

    @Test(timeout = 45_000) fun fullAccessRevocationBeforeRedirectCannotUseAnEarlierGrant(): Unit = runBlocking {
        LocalSite(host = "127.0.0.3").use { destination ->
            LocalSite(host = "127.0.0.2", redirectFor = { destination.url + "redirected" }).use { source ->
                source.delays["/jump"] = 700
                withBrowser { controller -> supervisorScope {
                    val liveAccess = AtomicBoolean(true)
                    val loading = async { controller.execute("navigate", args("url" to source.url + "jump")) { liveAccess.get() } }
                    await { source.count("/jump") == 1 }
                    // Models cannot retain authority after the task/policy/capability lease changes.
                    liveAccess.set(false)
                    await { controller.state.value.pendingOrigin != null }
                    assertEquals(0, destination.count("/redirected"))
                    main { assertTrue(controller.answerPending(false)) }
                    try { loading.await(); fail("Revoked Full Access must not follow a redirect") }
                    catch (e: BrowserFailure) { assertEquals("BROWSER_ORIGIN_DENIED", e.code) }
                    assertEquals(0, destination.count("/redirected"))
                } }
            }
        }
    }

}
