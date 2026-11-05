package dev.zakocoder.app.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyNodeEligibilityTest {
    @Test fun recognizesLocalSubscriptionNoticesInBothLanguages() {
        listOf(
            "剩余流量：25 GB", "套餐到期：2027-01-01", "官网：https://example.invalid",
            "请使用新版客户端", "流量重置：下月", "Traffic Remaining: 25 GB", "Expires: 2027-01-01",
        ).forEach { name -> assertTrue(name, isSubscriptionPlaceholder(node("127.0.0.1", name))) }
    }

    @Test fun recognizesOnlyLiteralLocalAddressesWithoutLookingUpDns() {
        listOf("127.0.0.2", "127.255.255.254", "localhost", "LOCALHOST.", "proxy.localhost", "::1",
            "[::1]", "0:0:0:0:0:0:0:1", "0::1", "::0:1", "::ffff:7f00:1", "0:0:0:0:0:ffff:7f00:1", "::ffff:127.0.0.1", "0.0.0.0", "::")
            .forEach { host -> assertTrue(host, isSubscriptionPlaceholder(node(host, "订阅说明"))) }
        listOf("127.0.0.256", "127.example.com", "localhost.example.com", "192.168.1.1", "10.0.0.1",
            "198.51.100.1", "proxy.example.com", "2001:db8::1", "::ffff:c000:201", "0::2", "1::1", ":::1", "0::0::1", "::ffff:127.0.0.256")
            .forEach { host -> assertFalse(host, isSubscriptionPlaceholder(node(host, "订阅说明"))) }
    }

    @Test fun localNodesWithNormalNamesRemainEligible() {
        listOf("本机调试", "Local Proxy", "VMess", "测试节点", "123", "")
            .forEach { name -> assertFalse(name, isSubscriptionPlaceholder(node("127.0.0.1", name))) }
    }

    @Test fun aNoticeDoesNotContaminateOtherNodesSharingItsEndpoint() {
        assertTrue(isSubscriptionPlaceholder(node("127.0.0.1", "官网：example.invalid")))
        assertFalse(isSubscriptionPlaceholder(node("127.0.0.1", "本机开发代理")))
        assertFalse(isSubscriptionPlaceholder(node("198.51.100.1", "官网节点")))
    }

    @Test fun recognizesFiveObservedSubscriptionMetadataShapes() {
        listOf(
            "请立即到官网下载新客户端！", "通知更新于 2026/11/4", "邀请新用户20%返利",
            "示例.com", "subscription.example.com",
        ).forEach { name -> assertTrue(name, isSubscriptionPlaceholder(node("127.0.0.1", name))) }
    }

    @Test fun noticeAndReferralPatternsDoNotMatchOrdinaryLocalNames() {
        listOf("邀请朋友测试", "邀请新用户", "返利节点", "通知更新测试", "普通本机节点", "1.2.3.4",
            "not a domain.com", "invalid..com", "-invalid.com", "example.com/path", "example.com:443")
            .forEach { name -> assertFalse(name, isSubscriptionPlaceholder(node("127.0.0.1", name))) }
    }

    @Test fun remoteWebsiteNamesRemainNodes() {
        listOf("示例.com", "example.com", "通知更新于 2026/11/4", "邀请新用户20%返利")
            .forEach { name -> assertFalse(name, isSubscriptionPlaceholder(node("198.51.100.1", name))) }
    }

    @Test fun manualSingleNodeLinkDoesNotClassifyLocalWebsiteNameAsPlaceholder() {
        val parsed = requireNotNull(ProxySubscriptionParser().parseLink("socks5://127.0.0.1:1080#example.com"))
        assertFalse(parsed.subscriptionPlaceholder)
        assertTrue(parsed.supported)
    }

    private fun node(host: String, name: String) = ProxyNode(
        id = "fixture", name = name, protocol = ProxyProtocol.Socks5, host = host, port = 1080,
    )
}
