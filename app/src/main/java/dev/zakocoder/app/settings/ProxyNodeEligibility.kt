package dev.zakocoder.app.settings

import java.net.IDN
import java.util.Locale

/**
 * Recognizes subscription metadata disguised as a node. Call only when importing subscription
 * content, never for a manually supplied single-node link. A local endpoint alone is valid and
 * must not make a user-configured local proxy unusable.
 *
 * This deliberately uses no DNS lookup and does not infer that other nodes sharing an endpoint
 * are placeholders. Neither a display name nor an endpoint by itself is sufficient evidence.
 */
internal fun isSubscriptionPlaceholder(node: ProxyNode): Boolean {
    if (!isLocalPlaceholderAddress(node.host)) return false
    val name = node.name.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    return subscriptionNoticeMarkers.any(name::contains) ||
        datedNotice.matches(name) || referralNotice.matches(name) || isBareWebsite(name)
}

private val datedNotice = Regex("(?:通知|公告)更新[于於]\\s*\\d{1,4}[/.-]\\d{1,2}[/.-]\\d{1,4}")
private val referralNotice = Regex("邀[请請]新(?:用户|用戶)\\s*\\d+(?:\\.\\d+)?\\s*%\\s*返利")

/** A bare website label is metadata only in combination with a local placeholder endpoint. */
private fun isBareWebsite(name: String): Boolean {
    val ascii = try {
        IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
    } catch (_: IllegalArgumentException) {
        return false
    }
    if (ascii.length !in 4..253) return false
    val labels = ascii.split('.')
    if (labels.size < 2 || labels.any { it.isEmpty() || it.length > 63 }) return false
    // Require a domain suffix, rather than treating a dotted version number or an IPv4 as a site.
    val suffix = labels.last()
    return suffix.matches(Regex("[a-z]{2,63}")) ||
        (suffix.startsWith("xn--") && suffix.length > 4)
}

private val subscriptionNoticeMarkers = listOf(
    "剩余流量", "剩餘流量", "流量剩余", "流量剩餘", "套餐流量", "已用流量", "流量用尽", "流量用盡",
    "到期时间", "到期時間", "过期时间", "過期時間", "套餐到期", "订阅到期", "訂閱到期", "有效期",
    "重置时间", "重置時間", "流量重置", "下次重置", "订阅说明", "訂閱說明", "订阅信息", "訂閱資訊",
    "官网", "官網", "官方网站", "官方網站", "更新订阅", "更新訂閱", "更新客户端", "更新客戶端",
    "客户端不支持", "客戶端不支援", "请使用", "請使用", "请更新", "請更新", "公告", "占位", "佔位",
    "traffic remaining", "remaining traffic", "remaining quota", "data remaining", "used traffic",
    "expiry", "expires", "expiration", "reset time", "traffic reset", "subscription info",
    "subscription notice", "official website", "update subscription", "update your client", "placeholder",
)

private fun isLocalPlaceholderAddress(raw: String): Boolean {
    val host = stripHostBrackets(raw.trim()).lowercase(Locale.ROOT).removeSuffix(".")
    if (host == "localhost" || host.endsWith(".localhost")) return true
    ipv4Octets(host)?.let { return it[0] == 127 || it.all { octet -> octet == 0 } }
    val words = ipv6Words(host) ?: return false
    if (words.take(7).all { it == 0 } && words[7] in 0..1) return true
    // IPv4-mapped IPv6 may use either dotted decimal or hexadecimal tail words.
    return words.take(5).all { it == 0 } && words[5] == 0xffff &&
        (words[6] shr 8 == 127 || (words[6] == 0 && words[7] == 0))
}

private fun ipv4Octets(host: String): List<Int>? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    return parts.map { part ->
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
        part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
    }
}

/** Numeric parsing only: never resolves a subscription-controlled hostname or interface name. */
private fun ipv6Words(host: String): List<Int>? {
    if (':' !in host || host.any { it !in "0123456789abcdef:." }) return null
    var address = host
    if ('.' in address) {
        val octets = ipv4Octets(address.substringAfterLast(':')) ?: return null
        address = address.substringBeforeLast(':') + ":" +
            ((octets[0] shl 8) or octets[1]).toString(16) + ":" +
            ((octets[2] shl 8) or octets[3]).toString(16)
    }
    val halves = address.split("::")
    if (halves.size > 2) return null
    fun parseWords(text: String): List<Int>? {
        if (text.isEmpty()) return emptyList()
        return text.split(':').map { word ->
            if (word.isEmpty() || word.length > 4) return null
            word.toIntOrNull(16) ?: return null
        }
    }
    val left = parseWords(halves[0]) ?: return null
    val right = if (halves.size == 2) parseWords(halves[1]) ?: return null else emptyList()
    val count = left.size + right.size
    if ((halves.size == 1 && count != 8) || (halves.size == 2 && count >= 8)) return null
    return left + List(8 - count) { 0 } + right
}
