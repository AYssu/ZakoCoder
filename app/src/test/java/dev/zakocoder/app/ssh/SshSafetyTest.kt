package dev.zakocoder.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SshSafetyTest {
    @Test fun profileAllowsIpv6AndSpacesInWorkingDirectory() {
        SshSafety.validate(SshProfile("project-1", "::1", username = "developer", workingDirectory = "/home/developer/123 测试"))
    }
    @Test fun profileRejectsAddressWithEmbeddedIdentity() {
        assertThrows(SshException::class.java) { SshSafety.validate(SshProfile("id", "user@host", username = "developer")) }
    }
    @Test fun pathRejectsParentAndAbsolutePaths() {
        listOf("../file", "a/../file", "/tmp/file", "a\u0000b").forEach { value ->
            assertThrows(SshException::class.java) { SshSafety.relativePath(value) }
        }
    }
    @Test fun pathKeepsSpacesAndQuotesLiteral() {
        assertEquals("123 测试/a'b.txt", SshSafety.relativePath("./123 测试/a'b.txt"))
        assertEquals("'a'\\''b'", SshSafety.quote("a'b"))
    }
    @Test fun replacementRequiresUniqueNonemptyOriginal() {
        assertThrows(SshException::class.java) { SshSafety.replaceExactlyOnce("aaaa", "aa", "x") }
        assertThrows(SshException::class.java) { SshSafety.replaceExactlyOnce("abc", "", "x") }
        assertThrows(SshException::class.java) { SshSafety.replaceExactlyOnce("abc", "d", "x") }
        assertEquals("axc", SshSafety.replaceExactlyOnce("abc", "b", "x"))
    }
    @Test fun sessionNamesCannotInjectTmuxTargets() {
        assertThrows(SshException::class.java) { SshSafety.taskName("project", "session:0") }
        val one = SshSafety.taskName("one", "a".repeat(32))
        val two = SshSafety.taskName("two", "a".repeat(32))
        assertFalse(one == two)
        assertEquals("zako_7692c3ad3540_" + "a".repeat(32), one)
    }
    @Test fun fingerprintIsOpenSshSha256() {
        assertEquals("SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0", SshSafety.fingerprint("abc".toByteArray()))
    }
    @Test fun utf8PagesKeepChineseAndEmojiBoundariesWithoutReplacement() {
        val bytes = "ab中文😀z".toByteArray()
        assertEquals("ab中", SshSafety.utf8Page(bytes, 0, 6).toString(Charsets.UTF_8))
        assertEquals("文", SshSafety.utf8Page(bytes, 5, 4).toString(Charsets.UTF_8))
        assertEquals("😀", SshSafety.utf8Page(bytes, 8, 4).toString(Charsets.UTF_8))
        assertThrows(SshException::class.java) { SshSafety.utf8Page(bytes, 3, 8) }
        assertThrows(SshException::class.java) { SshSafety.utf8Page(bytes, 2, 1) }
        assertThrows(SshException::class.java) { SshSafety.utf8Page(byteArrayOf(0xc0.toByte(), 0xff.toByte()), 0, 2) }
    }
    @Test fun credentialsNeverExposeValuesThroughToString() {
        assertEquals("SshCredentials(<redacted>)", SshCredentials("synthetic-password", "synthetic-private-key", "synthetic-passphrase").toString())
    }
}
