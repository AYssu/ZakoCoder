package dev.zakocoder.app.editor.lsp

import org.junit.Assert.*
import org.junit.Test
import dev.zakocoder.app.runtime.DevelopmentTools
import dev.zakocoder.app.runtime.ToolBundles

class LspServersTest {
    private val id = "9516372b-a8da-4a82-b2b7-9a5acb1cc52c"
    @Test fun recognizesOnlySupportedSourceFiles() {
        assertEquals(LspServer.Python, LspServer.forPath("src/main.py"))
        assertEquals(LspServer.Python, LspServer.forPath("typing.pyi"))
        assertEquals(LspServer.TypeScript, LspServer.forPath("src/Page.tsx"))
        assertEquals(LspServer.TypeScript, LspServer.forPath("src/index.JS"))
        val mappings = mapOf(
            LspServer.Java to listOf("src/Main.java", "Main.JAVA"),
            LspServer.Kotlin to listOf("Main.kt", "build.gradle.kts", "Main.KT"),
            LspServer.Cpp to listOf("src/main.c", "main.C", "main.cpp", "main.cc", "main.cxx", "api.h", "api.hpp", "api.HPP"),
            LspServer.Go to listOf("cmd/main.go", "main.GO"),
            LspServer.Rust to listOf("src/main.rs", "main.RS"),
            LspServer.Dart to listOf("lib/main.dart", "main.DART"),
        )
        mappings.forEach { (server, paths) -> paths.forEach { assertEquals(it, server, LspServer.forPath(it)) } }
        assertNull(LspServer.forPath("README.md")); assertNull(LspServer.forPath("Cargo.toml"))
    }
    @Test fun cAndCppUseDifferentLanguageIdsAndKnownHeaderDefault() {
        assertEquals("c", LspServer.Cpp.languageFor("c"))
        assertEquals("cpp", LspServer.Cpp.languageFor("C"))
        assertEquals("cpp", LspServer.Cpp.languageFor("cpp"))
        assertEquals("cpp", LspServer.Cpp.languageFor("hpp"))
        assertEquals("cpp", LspServer.Cpp.languageFor("h"))
        assertEquals("kotlin", LspServer.Kotlin.languageFor("kts"))
    }
    @Test fun everyServerHasExactlyOneToolAndBundleWithConsistentPackagesAndProbe() {
        assertEquals(LspServer.entries.size, LspServer.entries.map { it.bundleId }.toSet().size)
        for (server in LspServer.entries) {
            val tool = DevelopmentTools.catalog.single { it.id == server.bundleId }
            val bundle = ToolBundles.catalog.single { it.id == server.bundleId }
            assertTrue(server.name, server.bundleId in bundle.tools)
            assertEquals(server.packages, tool.packages)
            assertEquals(server.probe, tool.probe)
            assertTrue(server.name, server.bundleId.startsWith("lsp-"))
        }
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
            assertTrue(server.name, server.executable.startsWith("/"))
            assertTrue(server.name, server.script.contains("exec "))
            assertFalse(server.script.contains("install"))
            assertFalse(server.script.contains("apt-get"))
            assertFalse(server.script.contains("curl "))
            assertTrue(server.detectScript.startsWith("test "))
            assertTrue(server.installScript.startsWith("set -eu"))
        }
        assertEquals("javascriptreact", LspServer.TypeScript.languageFor("jsx"))
        assertEquals("typescriptreact", LspServer.TypeScript.languageFor("tsx"))
    }
}
