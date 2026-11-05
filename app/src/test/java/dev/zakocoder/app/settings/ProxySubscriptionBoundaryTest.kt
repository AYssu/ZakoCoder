package dev.zakocoder.app.settings
import org.junit.Assert.*
import org.junit.Test
class ProxySubscriptionBoundaryTest {
 private val parser = ProxySubscriptionParser()
 private val id = "11111111-1111-4111-8111-111111111111"
 private val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it+1).toByte() })
 private fun vless(query: String) = requireNotNull(parser.parseLink("vless://$id@edge.example:443?encryption=none&type=tcp&security=reality&sni=www.example.com&fp=chrome&pbk=$key&$query#fixture"))
 @Test fun remoteNumericDomainIsPreserved() { assertTrue(requireNotNull(parser.parseLink("trojan://fixture@127.example.com:443#fixture")).supported) }
 @Test fun expandedLoopbackNoticeIsExcluded() {
  val sub = requireNotNull(parser.parse("trojan://fixture@[0:0:0:0:0:0:0:1]:443#订阅说明", "https://sub.example/test"))
  assertEquals(0, sub.supportedCount)
 }
 @Test fun manuallySuppliedLocalKernelIsPreserved() { assertTrue(requireNotNull(parser.parseLink("trojan://fixture@127.0.0.1:443#local")).supported) }
 @Test fun malformedRealityShortIdIsRejected() { val n=vless("sid=xyz"); assertFalse(n.supported); assertNull(ProxyKernelConfig.build(n,10808)) }
 @Test fun tcpHttpObfuscationCannotBeSilentlyDropped() { val n=vless("sid=0123&headerType=http"); assertFalse(n.supported); assertNull(ProxyKernelConfig.build(n,10808)) }
 @Test fun clashNestedRealityIsPreserved() {
  val yaml="""proxies:
  - name: fixture
    type: vless
    server: edge.example
    port: 443
    uuid: $id
    tls: true
    network: tcp
    servername: www.example.com
    client-fingerprint: chrome
    reality-opts:
      public-key: $key
      short-id: '0123'
"""
  val n=requireNotNull(parser.parse(yaml)).nodes.single()
  assertTrue(n.supported)
  assertTrue(requireNotNull(ProxyKernelConfig.build(n,10808)).contains("realitySettings"))
 }
}
