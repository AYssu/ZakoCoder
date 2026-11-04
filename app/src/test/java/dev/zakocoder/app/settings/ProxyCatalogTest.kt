package dev.zakocoder.app.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog state machine against fake network/store: no test may reach the real network or disk.
 * Covers cache loading, probe classification, import, refresh failure/success, clear, select and
 * stable error mapping.
 */
class ProxyCatalogTest {
    @Test fun defaultSubscriptionUserAgentIncludesVersion() {
        assertTrue(proxySubscriptionUserAgent().matches(Regex("ZakoCoder/.+")))
    }

    private val link = "https://sub.example.com/api/v1/client/token-value?token=redacted"
    private val httpA = testNode("node-a", name = "A")
    private val socksB = testNode("node-b", name = "B", protocol = ProxyProtocol.Socks5, host = "198.51.100.2", port = 1080)
    private val unsupported = testNode("node-vm", name = "VM", protocol = ProxyProtocol.Vmess, host = "", port = 0)

    private val twoSupportedLinks = "http://192.0.2.5:8080#A\nsocks5://198.51.100.2:1080#B"

    private fun catalog(
        fetcher: FakeProxySubscriptionFetcher = FakeProxySubscriptionFetcher(),
        store: FakeProxySubscriptionStore = FakeProxySubscriptionStore(),
    ) = ProxyCatalog(fetcher, store, clock = { 5_000L })

    @Test fun loadCachedReadsStoreWithoutNetworkAndSelectsFirstUsableNode() = runBlocking {
        val store = FakeProxySubscriptionStore(testSubscription(listOf(unsupported, httpA)))
        val fetcher = FakeProxySubscriptionFetcher()
        val catalog = catalog(fetcher, store)

        catalog.loadCached()

        assertEquals(0, fetcher.calls)
        val state = catalog.state.value
        assertEquals(2, state.subscription!!.nodes.size)
        assertEquals(1_000L, state.updatedAt)
        assertEquals("node-a", state.selectedId)
        assertFalse(state.busy)
        assertNull(state.error)
        assertNull(state.probe)
    }

    @Test fun probeClassifiesAirportWithoutSavingOrApplying() = runBlocking {
        val fetcher = FakeProxySubscriptionFetcher(content = twoSupportedLinks)
        val store = FakeProxySubscriptionStore()
        val catalog = catalog(fetcher, store)

        catalog.updateLink(link)
        catalog.probe()

        assertEquals(1, fetcher.calls)
        assertEquals(listOf(link), fetcher.urls)
        val state = catalog.state.value
        assertTrue(state.probe is ProxyProbe.Airport)
        assertNull(state.subscription)
        assertNull(state.error)
        assertFalse(state.busy)
        assertEquals("probe must not persist", 0, store.saves)
    }

    @Test fun importSavesAirportAndSelectsFirstUsableNode() = runBlocking {
        val fetcher = FakeProxySubscriptionFetcher(content = twoSupportedLinks)
        val store = FakeProxySubscriptionStore()
        val catalog = catalog(fetcher, store)

        catalog.updateLink(link)
        catalog.probe()
        catalog.import()

        assertEquals(1, store.saves)
        val saved = requireNotNull(store.cached)
        assertEquals(link, saved.sourceUrl)
        assertEquals(5_000L, saved.fetchedAt)
        val state = catalog.state.value
        assertEquals(saved, state.subscription)
        assertEquals(5_000L, state.updatedAt)
        assertEquals(saved.nodes.first { it.supported }.id, state.selectedId)
        assertNull(state.error)
    }

    @Test fun singleNodeProbeCanBeImportedAsAOneNodeSubscription() = runBlocking {
        val fetcher = FakeProxySubscriptionFetcher(content = "socks5://alice:pw@192.0.2.5:1080#Only")
        val store = FakeProxySubscriptionStore()
        val catalog = catalog(fetcher, store)

        catalog.updateLink(link)
        catalog.probe()
        assertTrue(catalog.state.value.probe is ProxyProbe.SingleNode)
        catalog.import()

        assertEquals(1, store.saves)
        val saved = requireNotNull(store.cached)
        assertEquals(1, saved.nodes.size)
        assertEquals("Only", saved.nodes.single().name)
        assertEquals(saved.nodes.single().id, catalog.state.value.selectedId)
    }

    @Test fun importBeforeProbeOrAfterUnsupportedProbeDoesNothing() = runBlocking {
        val fetcher = FakeProxySubscriptionFetcher(content = "hysteria2://pw@192.0.2.6:443#HY")
        val store = FakeProxySubscriptionStore()
        val catalog = catalog(fetcher, store)

        catalog.updateLink(link)
        catalog.import()
        assertEquals(0, store.saves)

        catalog.probe()
        assertTrue(catalog.state.value.probe is ProxyProbe.UnsupportedOnly)
        catalog.import()
        assertEquals(0, store.saves)
        assertNull(catalog.state.value.subscription)
    }

    @Test fun refreshFailureKeepsTheCachedSubscriptionAndReportsOneError() = runBlocking {
        val old = testSubscription(listOf(httpA, socksB), fetchedAt = 1_000L)
        val fetcher = FakeProxySubscriptionFetcher(failure = ProxySubscriptionError.Unreachable)
        val store = FakeProxySubscriptionStore(old)
        val catalog = catalog(fetcher, store)

        catalog.loadCached()
        catalog.refresh()

        val state = catalog.state.value
        assertSame(old, state.subscription)
        assertEquals(1_000L, state.updatedAt)
        assertEquals(ProxySubscriptionError.Unreachable, state.error)
        assertFalse(state.busy)
        assertEquals("A failed refresh must not overwrite the cache", 0, store.saves)
        assertEquals(1, fetcher.calls)
    }

    @Test fun refreshSuccessReplacesCacheAndSelection() = runBlocking {
        val old = testSubscription(listOf(httpA), fetchedAt = 1_000L)
        val fetcher = FakeProxySubscriptionFetcher(content = "http://192.0.2.9:8080#New")
        val store = FakeProxySubscriptionStore(old)
        val catalog = catalog(fetcher, store)

        catalog.loadCached()
        assertEquals("node-a", catalog.state.value.selectedId)
        catalog.refresh()

        assertEquals(1, store.saves)
        val state = catalog.state.value
        assertEquals("https://sub.example.com/api/v1/client/token-value?token=redacted", fetcher.urls.single())
        assertEquals(5_000L, state.updatedAt)
        assertEquals("New", state.subscription!!.nodes.single().name)
        assertEquals(state.subscription!!.nodes.single().id, state.selectedId)
        assertNull(state.error)
    }

    @Test fun refreshWithoutCacheIsNotFoundAndNeverFetches() = runBlocking {
        val fetcher = FakeProxySubscriptionFetcher()
        val store = FakeProxySubscriptionStore()
        val catalog = catalog(fetcher, store)

        catalog.refresh()

        assertEquals(ProxySubscriptionError.NotFound, catalog.state.value.error)
        assertEquals(0, fetcher.calls)
        assertEquals(0, store.saves)
    }

    @Test fun clearRemovesStoreAndResetsState() = runBlocking {
        val store = FakeProxySubscriptionStore(testSubscription(listOf(httpA)))
        val catalog = catalog(FakeProxySubscriptionFetcher(), store)
        catalog.loadCached()
        catalog.updateLink(link)

        catalog.clear()

        assertEquals(1, store.clears)
        assertNull(store.cached)
        assertEquals(ProxyCatalogState(), catalog.state.value)
    }

    @Test fun selectOnlyAcceptsListedUsableNodes() = runBlocking {
        val other = testNode("node-c", name = "C", host = "192.0.2.30")
        val store = FakeProxySubscriptionStore(testSubscription(listOf(httpA, other, unsupported)))
        val catalog = catalog(FakeProxySubscriptionFetcher(), store)
        catalog.loadCached()
        assertEquals("node-a", catalog.state.value.selectedId)

        catalog.select("node-c")
        assertEquals("node-c", catalog.state.value.selectedId)

        catalog.select("node-vm")
        assertEquals("Unsupported nodes cannot be selected", "node-c", catalog.state.value.selectedId)

        catalog.select("missing")
        assertEquals("node-c", catalog.state.value.selectedId)
    }

    @Test fun loadCachedPreservesAStillValidSelection() = runBlocking {
        val store = FakeProxySubscriptionStore(testSubscription(listOf(httpA, socksB)))
        val catalog = catalog(FakeProxySubscriptionFetcher(), store)
        catalog.loadCached()
        catalog.select("node-b")

        catalog.loadCached()
        assertEquals("node-b", catalog.state.value.selectedId)
    }

    @Test fun updateLinkClearsErrorAndProbeButKeepsImportedSubscription() = runBlocking {
        val fetcher = FakeProxySubscriptionFetcher(content = twoSupportedLinks)
        val store = FakeProxySubscriptionStore()
        val catalog = catalog(fetcher, store)
        catalog.updateLink(link)
        catalog.probe()
        catalog.import()
        val imported = catalog.state.value.subscription

        catalog.updateLink("https://other.example.com/new")

        val state = catalog.state.value
        assertEquals("https://other.example.com/new", state.link)
        assertNull(state.probe)
        assertNull(state.error)
        assertEquals(imported, state.subscription)
    }

    @Test fun fetcherFailuresMapToStableCategoriesOnly() = runBlocking {
        val reasons = listOf(
            ProxySubscriptionError.TooLarge,
            ProxySubscriptionError.HttpStatus,
            ProxySubscriptionError.Unreachable,
            ProxySubscriptionError.Empty,
        )
        for (reason in reasons) {
            val fetcher = FakeProxySubscriptionFetcher(failure = reason, failureCause = IllegalStateException("provider body"))
            val store = FakeProxySubscriptionStore()
            val catalog = catalog(fetcher, store)
            // Deliberately token-free link: the assertion below is about the exception text, not
            // about the user's own input, which is allowed to live in ProxyCatalogState.link.
            catalog.updateLink("https://sub.example.com/api")

            catalog.probe()

            val state = catalog.state.value
            assertEquals(reason, state.error)
            assertNull(state.probe)
            assertFalse(state.busy)
            assertFalse("Exception text must not reach state", state.toString().contains("provider body"))
            assertFalse("Subscription link must not appear in state toString", state.toString().contains("https://"))
            assertEquals(0, store.saves)
        }
    }

    @Test fun unparseableContentIsUnsupportedContentWithNotSubscriptionProbe() = runBlocking {
        val fetcher = FakeProxySubscriptionFetcher(content = "<html>definitely not a subscription</html>")
        val store = FakeProxySubscriptionStore()
        val catalog = catalog(fetcher, store)
        catalog.updateLink(link)

        catalog.probe()

        assertEquals(ProxySubscriptionError.UnsupportedContent, catalog.state.value.error)
        assertSame(ProxyProbe.NotSubscription, catalog.state.value.probe)
        assertNull(catalog.state.value.subscription)
        assertEquals(0, store.saves)
    }

    @Test fun storeSaveFailureDoesNotPretendImportSucceeded() = runBlocking {
        val fetcher = FakeProxySubscriptionFetcher(content = twoSupportedLinks)
        val store = FakeProxySubscriptionStore().apply { saveFailure = IllegalStateException("keystore unavailable") }
        val catalog = catalog(fetcher, store)
        catalog.updateLink(link)
        catalog.probe()

        catalog.import()

        assertEquals(0, store.saves)
        assertNull(catalog.state.value.subscription)
        assertEquals(ProxySubscriptionError.Unreachable, catalog.state.value.error)
        assertFalse(catalog.state.value.busy)
    }

    @Test fun probeRejectsNonHttpsLinkBeforeCallingFetcher() = runBlocking {
        val fetcher = FakeProxySubscriptionFetcher()
        val store = FakeProxySubscriptionStore()
        val catalog = catalog(fetcher, store)
        catalog.updateLink("http://sub.example.com/api")

        catalog.probe()

        assertEquals(ProxySubscriptionError.InsecureScheme, catalog.state.value.error)
        assertEquals(0, fetcher.calls)
        assertNull(catalog.state.value.probe)
        assertFalse(catalog.state.value.busy)
        assertEquals(0, store.saves)
    }

    @Test fun probeResultIsDiscardedWhenTheLinkChangesWhileTheRequestIsInFlight() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fetcher = FakeProxySubscriptionFetcher(content = twoSupportedLinks)
        fetcher.beforeFetch = { started.complete(Unit); release.await() }
        val store = FakeProxySubscriptionStore()
        val catalog = catalog(fetcher, store)
        val oldLink = "https://sub.example.com/api/v1/client/old-value?token=old"
        val newLink = "https://sub.example.com/api/v1/client/new-value?token=new"
        catalog.updateLink(oldLink)

        val job = launch(Dispatchers.Default) { catalog.probe() }
        started.await()
        catalog.updateLink(newLink)
        release.complete(Unit)
        job.join()

        // The stale response must not be presented as the classification of the new link.
        assertNull(catalog.state.value.probe)
        assertNull(catalog.state.value.error)
        assertFalse(catalog.state.value.busy)
        assertEquals(newLink, catalog.state.value.link)
        assertEquals(0, store.saves)
        catalog.import()
        assertEquals(0, store.saves)
    }

    @Test fun selectWorksOnProbedNodesEvenBeforeImportPersists() = runBlocking {
        val fetcher = FakeProxySubscriptionFetcher(content = twoSupportedLinks)
        val store = FakeProxySubscriptionStore()
        val catalog = catalog(fetcher, store)
        catalog.updateLink("https://sub.example.com/api")

        catalog.probe()
        val probe = catalog.state.value.probe as ProxyProbe.Airport
        val second = probe.subscription.nodes[1].id
        catalog.select(second)
        assertEquals(second, catalog.state.value.selectedId)
        assertEquals(0, store.saves)

        // A failed import must not dead-lock the selection: the probe nodes stay selectable.
        store.saveFailure = IllegalStateException("keystore unavailable")
        catalog.import()
        assertEquals(ProxySubscriptionError.Unreachable, catalog.state.value.error)
        val first = probe.subscription.nodes[0].id
        catalog.select(first)
        assertEquals(first, catalog.state.value.selectedId)
    }

    @Test fun importedKernelTransportStaysViaKernelAfterReloadFromCache() = runBlocking {
        val vmessJson = """{"v":"2","ps":"Kernel","add":"vm.example.com","port":"443","id":"uuid-1","aid":"0",""" +
            """"scy":"auto","net":"ws","tls":"tls","sni":"sni.example.com","path":"/ws"}"""
        val link = "vmess://" + java.util.Base64.getEncoder().encodeToString(vmessJson.toByteArray(Charsets.UTF_8))
        val store = FakeProxySubscriptionStore()
        val catalog = catalog(FakeProxySubscriptionFetcher(content = link), store)

        catalog.updateLink("https://sub.example.com/api")
        catalog.probe()
        catalog.import()
        val imported = requireNotNull(catalog.state.value.subscription).nodes.single()
        assertTrue(imported.viaKernel)
        assertTrue(imported.supported)

        // Simulates the post-restart path: a fresh catalog reads the saved subscription (the fake
        // store stands in for the encrypted store, whose JSON is covered by ProxyTransportCodecTest).
        val reloadedCatalog = catalog(FakeProxySubscriptionFetcher(), store)
        reloadedCatalog.loadCached()
        val reloaded = requireNotNull(reloadedCatalog.state.value.subscription).nodes.single()
        assertTrue("The cached transport must survive reload", reloaded.viaKernel)
        assertTrue(reloaded.supported)
        assertEquals(imported.transport, reloaded.transport)
        assertEquals("ws", reloaded.transport!!.vmess!!.network)
        assertEquals("sni.example.com", reloaded.transport!!.vmess!!.sni)
    }
}
