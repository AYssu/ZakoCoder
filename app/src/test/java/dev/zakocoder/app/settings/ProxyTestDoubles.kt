package dev.zakocoder.app.settings

/** Test doubles shared by the pure-JVM catalog/classification suites; no real network or disk. */
internal class FakeProxySubscriptionFetcher(
    var content: String = "",
    var failure: ProxySubscriptionError? = null,
    var failureCause: Throwable? = null,
    /** Test hook to keep a probe in flight (state must stay consistent while the user edits). */
    var beforeFetch: (suspend () -> Unit)? = null,
) : ProxySubscriptionFetcher {
    var calls: Int = 0
    val urls: MutableList<String> = mutableListOf()

    override suspend fun fetch(url: String): String {
        calls++
        urls += url
        beforeFetch?.invoke()
        failure?.let { throw ProxySubscriptionException(it, failureCause) }
        return content
    }
}

internal class FakeProxySubscriptionStore(
    var cached: ProxySubscription? = null,
) : ProxySubscriptionStore {
    var saves: Int = 0
    var clears: Int = 0
    var saveFailure: Exception? = null

    override fun load(): ProxySubscription? = cached

    override fun save(subscription: ProxySubscription) {
        saveFailure?.let { throw it }
        cached = subscription
        saves++
    }

    override fun clear() {
        cached = null
        clears++
    }
}

internal fun testSubscription(
    nodes: List<ProxyNode>,
    sourceUrl: String = "https://sub.example.com/api/v1/client/token-value?token=redacted",
    format: SubscriptionFormat = SubscriptionFormat.PlainLinks,
    fetchedAt: Long = 1_000L,
): ProxySubscription = ProxySubscription(sourceUrl, fetchedAt, nodes, format)

internal fun testNode(
    id: String,
    name: String = id,
    protocol: ProxyProtocol = ProxyProtocol.Http,
    host: String = "192.0.2.10",
    port: Int = 8080,
    username: String? = null,
    password: String? = null,
): ProxyNode = ProxyNode(id, name, protocol, host, port, username, password)
