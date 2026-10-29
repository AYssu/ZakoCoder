package dev.zakocoder.app

import dev.zakocoder.app.terminal.LocalPreviewAddress
import org.junit.Assert.*
import org.junit.Test

class LocalPreviewAddressTest {
    @Test fun localAddressesNormalizeWithoutDnsAndPreserveExplicitPaths() {
        assertEquals("http://127.0.0.1:5173/", LocalPreviewAddress.normalize("http://0.0.0.0:5173"))
        assertEquals("https://[::1]:443/", LocalPreviewAddress.normalize("https://[::]:443/"))
        assertEquals("http://localhost:8080/a%20b?q=1#tail", LocalPreviewAddress.normalize(" http://localhost:8080/a%20b?q=1#tail "))
        assertEquals("http://[::1]:8080/", LocalPreviewAddress.normalize("http://[::1]:8080/"))
    }
    @Test fun rejectsExternalHostsCredentialsSchemesAndMalformedPorts() {
        listOf("http://example.org:80/", "http://localhost.example.org:80/", "http://localhost:80@example.org/", "http://user:secret@127.0.0.1:80/",
            "http://2130706433:80/", "http://127.1:80/", "http://127.0.0.2:80/", "http://localhost/", "http://localhost:0/", "http://localhost:65536/",
            "http://localhost:abc/", "file:///tmp/a", "javascript:alert(1)", "//localhost:8080/", "ftp://localhost:21/", "http://localhost:80/\nsecret", "http://localhost:80/a b")
            .forEach { assertNull(it, LocalPreviewAddress.normalize(it)) }
    }
    @Test fun tooLongAddressNeverOpensTruncatedUrl() { assertNull(LocalPreviewAddress.normalize("http://localhost:80/" + "a".repeat(2048))) }
    @Test fun candidatesAreRecentDistinctBoundedOriginsWithoutOutputParameters() {
        val text = "http://external.example:1234/ http://0.0.0.0:5173/private?token=never-copy#secret\n" +
            "(http://127.0.0.1:5173/) http://localhost:8080/secret http://[::1]:9000/\n" +
            "http://127.0.0.1:1000/ http://127.0.0.1:2000/"
        val found = LocalPreviewAddress.candidates(text)
        assertEquals(listOf("http://127.0.0.1:2000/", "http://127.0.0.1:1000/", "http://[::1]:9000/", "http://localhost:8080/"), found)
        assertFalse(found.toString().contains("secret"))
        assertEquals(listOf("http://127.0.0.1:5173/"), LocalPreviewAddress.candidates("http://0.0.0.0:5173/private?token=never-copy#secret"))
        assertTrue(LocalPreviewAddress.candidates("http://localhost:8080/" + "x".repeat(32768)).isEmpty())
    }
}
