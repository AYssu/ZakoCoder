package dev.zakocoder.app.editor.lsp

import org.junit.Assert.*
import org.junit.Test

class LspServersTest {
    private val id = "9516372b-a8da-4a82-b2b7-9a5acb1cc52c"
    @Test fun recognizesOnlySupportedSourceFiles() {
        assertEquals(LspServer.Python, LspServer.forPath("src/main.py"))
        assertEquals(LspServer.Python, LspServer.forPath("typing.pyi"))
        assertEquals(LspServer.TypeScript, LspServer.forPath("src/Page.tsx"))
        assertEquals(LspServer.TypeScript, LspServer.forPath("src/index.JS"))
        assertNull(LspServer.forPath("app/Main.kt")); assertNull(LspServer.forPath("README.md"))
    }
    @Test fun guestWorkspaceUrisRetainSpacesAndUnicodeWithoutGuessingHostPaths() {
        assertEquals("/workspace/$id/src/测试 文件.py", lspDocumentPath(id, "src/测试 文件.py"))
        assertEquals("file:///workspace/$id/src/a%20b.py", lspFileUri(id, "src/a b.py"))
    }
    @Test fun rejectsTraversalAndUnscopedWorkspaceIdentifiers() {
        for (path in listOf("", "/root/file.py", "../file.py", "src/../file.py", "src//file.py", "src/./file.py", ".git/config", "bad\n.py")) assertTrue(path, runCatching { lspDocumentPath(id, path) }.isFailure)
        assertTrue(runCatching { lspDocumentPath("not-a-project", "a.py") }.isFailure)
    }
    @Test fun commandsAreFixedAndInstallIsSeparateFromAttach() {
        for (server in LspServer.entries) {
            assertTrue(server.script.startsWith("exec /opt/zakocoder/lsp/"))
            assertFalse(server.script.contains("install")); assertTrue(server.detectScript.startsWith("test -x "))
            assertTrue(server.installScript.startsWith("set -eu"))
        }
        assertEquals("javascriptreact", LspServer.TypeScript.languageFor("jsx"))
        assertEquals("typescriptreact", LspServer.TypeScript.languageFor("tsx"))
    }
}
