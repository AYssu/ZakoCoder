package dev.zakocoder.app.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * UI-safe catalog state. [link] is the user's own input; [subscription] holds node credentials and
 * therefore must only be rendered through masked/redacted values.
 */
data class ProxyCatalogState(
    val link: String = "",
    val busy: Boolean = false,
    val error: ProxySubscriptionError? = null,
    val probe: ProxyProbe? = null,
    val subscription: ProxySubscription? = null,
    val selectedId: String? = null,
    val updatedAt: Long? = null,
) {
    // The default data-class toString would print the user's subscription link (often a token URL).
    override fun toString() =
        "ProxyCatalogState(link=<redacted>, busy=$busy, error=$error, hasProbe=${probe != null}, " +
            "hasSubscription=${subscription != null}, selectedId=$selectedId, updatedAt=$updatedAt)"
}

/**
 * State machine around [ProxySubscriptionFetcher], [ProxySubscriptionParser] and
 * [ProxySubscriptionStore].
 *
 * Contract:
 * - **no implicit network**: [loadCached] only reads the encrypted store. Every HTTP request is
 *   triggered by an explicit [probe], [import] or [refresh] call from the UI;
 * - [probe] never persists and never turns a successful look into an applied setting;
 * - [import] only saves when the last probe is [ProxyProbe.Airport] or [ProxyProbe.SingleNode];
 * - [refresh] is the only operation that replaces a cached subscription, and it keeps the previous
 *   cache on every failure while exposing one [ProxySubscriptionError];
 * - failures are reduced to [ProxySubscriptionError]; exception messages, provider bodies and the
 *   full subscription URL never enter [state].
 *
 * Network calls are serialized by a mutex; while one runs, [ProxyCatalogState.busy] is true and
 * further calls are no-ops instead of overlapping requests.
 */
class ProxyCatalog(
    private val fetcher: ProxySubscriptionFetcher,
    private val store: ProxySubscriptionStore,
    private val parser: ProxySubscriptionParser = ProxySubscriptionParser(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(ProxyCatalogState())
    val state: StateFlow<ProxyCatalogState> = mutable.asStateFlow()
    private val mutex = Mutex()

    /** Last parsed subscription from a successful [probe]; needed to import a single node. */
    private var probed: ProxySubscription? = null

    /** Incremented by [clear] so an in-flight probe/import cannot repopulate removed state. */
    private var generation = 0L

    /** The encrypted store is decrypted once per session; saves/clears keep the in-memory copy. */
    private var cacheLoaded = false

    /** Editing the link invalidates the previous look but keeps the cached/imported subscription. */
    fun updateLink(link: String) {
        probed = null
        mutable.update { it.copy(link = link, error = null, probe = null) }
    }

    /** Explicitly fetches and classifies [ProxyCatalogState.link]. Never saves. */
    suspend fun probe() {
        if (!mutex.tryLock()) return
        val probeGeneration = generation
        try {
            probed = null
            mutable.update { it.copy(busy = true, error = null, probe = null) }
            val link = mutable.value.link.trim()
            if (link.isEmpty() || link.length > MAX_LINK_CHARS) {
                fail(ProxySubscriptionError.InvalidLink)
                return
            }
            when (val linkError = classifyLink(link)) {
                null -> Unit
                else -> {
                    fail(linkError)
                    return
                }
            }
            val content = try {
                fetcher.fetch(link)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ProxySubscriptionException) {
                fail(error.reason)
                return
            } catch (_: Exception) {
                fail(ProxySubscriptionError.Unreachable)
                return
            }
            // The user may keep typing while the request is in flight; a result for the old link must
            // never be shown as the classification of the new one.
            if (mutable.value.link.trim() != link || generation != probeGeneration) {
                probed = null
                mutable.update { it.copy(busy = false, error = null, probe = null) }
                return
            }
            val subscription = try {
                withContext(Dispatchers.Default) { parser.parse(content, link) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (subscription == null) {
                mutable.update { it.copy(busy = false, error = ProxySubscriptionError.UnsupportedContent, probe = ProxyProbe.NotSubscription) }
                return
            }
            if (generation != probeGeneration) {
                probed = null
                mutable.update { it.copy(busy = false, error = null, probe = null) }
                return
            }
            probed = subscription
            val classified = withContext(Dispatchers.Default) { parser.classify(subscription) }
            if (generation != probeGeneration) {
                probed = null
                mutable.update { it.copy(busy = false, error = null, probe = null) }
                return
            }
            mutable.update { it.copy(busy = false, error = null, probe = classified) }
        } finally {
            // Clear busy before releasing the lock so a waiter cannot observe it with busy=true.
            resetBusyAfterCancellation()
            mutex.unlock()
        }
    }

    /** Persists the last successful probe when it produced a usable subscription or node. */
    suspend fun import() {
        if (!mutex.tryLock()) return
        val importGeneration = generation
        try {
            val probe = mutable.value.probe
            if (probe !is ProxyProbe.Airport && probe !is ProxyProbe.SingleNode) return
            val parsed = probed ?: return
            mutable.update { it.copy(busy = true, error = null) }
            val subscription = parsed.copy(fetchedAt = clock())
            if (importGeneration != generation) return
            try {
                persist(subscription)
            } catch (_: Exception) {
                // Keystore/storage failure: keep the previous cache and expose one stable category.
                mutable.update { it.copy(busy = false, error = ProxySubscriptionError.Unreachable) }
                return
            }
            if (importGeneration != generation) {
                // The user removed the subscription while the encrypted save was in flight.
                runCatching { store.clear() }
                return
            }
            cacheLoaded = true
            probed = subscription
            mutable.update {
                it.copy(
                    busy = false,
                    error = null,
                    subscription = subscription,
                    updatedAt = subscription.fetchedAt,
                    selectedId = retainSelection(subscription, it.selectedId),
                )
            }
        } finally {
            // Clear busy before releasing the lock so a waiter cannot observe it with busy=true.
            resetBusyAfterCancellation()
            mutex.unlock()
        }
    }

    /**
     * Re-fetches the cached subscription source. On any failure the previous subscription,
     * timestamp and selection stay untouched and only [ProxyCatalogState.error] changes.
     */
    suspend fun refresh() {
        if (!mutex.tryLock()) return
        val refreshGeneration = generation
        try {
            val cached = mutable.value.subscription
            if (cached == null) {
                mutable.update { it.copy(error = ProxySubscriptionError.NotFound) }
                return
            }
            mutable.update { it.copy(busy = true, error = null) }
            val source = cached.sourceUrl
            when (val linkError = classifyLink(source)) {
                null -> Unit
                else -> {
                    mutable.update { it.copy(busy = false, error = linkError) }
                    return
                }
            }
            val content = try {
                fetcher.fetch(source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ProxySubscriptionException) {
                probed = null
                if (refreshGeneration == generation) mutable.update { it.copy(busy = false, error = error.reason) }
                return
            } catch (_: Exception) {
                probed = null
                if (refreshGeneration == generation) mutable.update { it.copy(busy = false, error = ProxySubscriptionError.Unreachable) }
                return
            }
            if (refreshGeneration != generation) return
            val parsed = try {
                withContext(Dispatchers.Default) { parser.parse(content, source) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (parsed == null) {
                probed = null
                mutable.update { it.copy(busy = false, error = ProxySubscriptionError.UnsupportedContent) }
                return
            }
            val subscription = parsed.copy(fetchedAt = clock())
            try {
                persist(subscription)
            } catch (_: Exception) {
                mutable.update { it.copy(busy = false, error = ProxySubscriptionError.Unreachable) }
                return
            }
            if (refreshGeneration != generation) {
                runCatching { store.clear() }
                return
            }
            cacheLoaded = true
            probed = subscription
            val probe = withContext(Dispatchers.Default) { parser.classify(subscription) }
            mutable.update {
                it.copy(
                    busy = false,
                    error = null,
                    subscription = subscription,
                    updatedAt = subscription.fetchedAt,
                    probe = probe,
                    selectedId = retainSelection(subscription, it.selectedId),
                )
            }
        } finally {
            resetBusyAfterCancellation()
            mutex.unlock()
        }
    }

    /**
     * Reads the encrypted cache only; never touches the network. Decryption runs on IO because the
     * Android Keystore call is blocking and must not run on the composition/main thread.
     */
    suspend fun loadCached() {
        if (cacheLoaded) return
        val loadGeneration = generation
        val cached = withContext(Dispatchers.IO) { store.load() } ?: return
        if (generation != loadGeneration) return
        cacheLoaded = true
        probed = null
        mutable.update {
            it.copy(
                busy = false,
                error = null,
                probe = null,
                subscription = cached,
                updatedAt = cached.fetchedAt,
                selectedId = retainSelection(cached, it.selectedId),
            )
        }
    }

    /** Selection is limited to currently listed, usable nodes; unsupported nodes cannot be applied. */
    fun select(nodeId: String) {
        val nodes = currentNodes()
        val node = nodes.firstOrNull { it.id == nodeId } ?: return
        if (!node.supported) return
        mutable.update { it.copy(selectedId = nodeId) }
    }

    /** Removes the stored subscription and every in-memory field (the link input included). */
    fun clear() {
        generation++
        cacheLoaded = false
        try {
            store.clear()
        } catch (_: Exception) {
            // Clearing an unreadable entry must not trap the user in the page.
        }
        probed = null
        mutable.value = ProxyCatalogState()
    }

    /** Nodes from the imported subscription or from the last probe (import may have failed). */
    private fun currentNodes(): List<ProxyNode> = mutable.value.subscription?.nodes
        ?: when (val probe = mutable.value.probe) {
            is ProxyProbe.Airport -> probe.subscription.nodes
            is ProxyProbe.SingleNode -> listOf(probe.node)
            else -> emptyList()
        }

    private fun fail(reason: ProxySubscriptionError) {
        probed = null
        mutable.update { it.copy(busy = false, error = reason, probe = null) }
    }

    /**
     * Keystore encryption and the preferences write are disk/crypto work: keep them off the caller's
     * dispatcher. NonCancellable makes the save atomic with respect to cancellation so a cancelled
     * import cannot leave `busy` stuck or persist without reporting.
     */
    private suspend fun persist(subscription: ProxySubscription) {
        withContext(NonCancellable + Dispatchers.IO) { store.save(subscription) }
    }

    /** A cancelled probe/import/refresh must never leave the UI stuck in the busy state. */
    private fun resetBusyAfterCancellation() {
        mutable.update { if (it.busy) it.copy(busy = false) else it }
    }

    private fun retainSelection(subscription: ProxySubscription, previous: String?): String? =
        previous?.takeIf { id -> subscription.nodes.any { it.id == id && it.supported } }
            ?: subscription.nodes.firstOrNull { it.supported }?.id

    /** Returns null for a usable HTTPS link, otherwise the UI-safe rejection category. */
    private fun classifyLink(link: String): ProxySubscriptionError? {
        val uri = try {
            URI(link)
        } catch (_: Exception) {
            return ProxySubscriptionError.InvalidLink
        }
        val scheme = uri.scheme ?: return ProxySubscriptionError.InvalidLink
        if (!scheme.equals("https", ignoreCase = true)) return ProxySubscriptionError.InsecureScheme
        val host = uri.host
        if (host.isNullOrEmpty()) return ProxySubscriptionError.InvalidLink
        return null
    }

    private companion object {
        const val MAX_LINK_CHARS = 2048
    }
}
