package dev.zakocoder.app.editor

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class EditorLanguageResolverTest {
    @Test fun commonExtensionsSelectBundledLexers() {
        val examples = mapOf("src/Main.kt" to "kotlin", "build.gradle.kts" to "kotlin", "Main.java" to "java", "lib/main.DART" to "dart",
            "index.jsx" to "javascript", "main.tsx" to "typescript", "types.pyi" to "python",
            "start.sh" to "shell", "setup.fish" to "shell", "build.BAT" to "batch", "launch.cmd" to "batch",
            "README.md" to "markdown", "notes.markdown" to "markdown", "index.html" to "html",
            "app.vue" to "html", "theme.scss" to "css", "workflow.yml" to "yaml", "Cargo.toml" to "toml",
            "AndroidManifest.xml" to "xml", "icon.svg" to "xml", "init.sql" to "sql", "main.go" to "go",
            "main.rs" to "rust", "config.jsonc" to "json", "native.hpp" to "c", "change.patch" to "diff")
        examples.forEach { (path, language) ->
            assertEquals(path, language, EditorLanguageResolver.resolve(path))
            assertTrue(language in EditorLanguageResolver.bundled)
        }
        assertEquals(EditorLanguageResolver.bundled.size, EditorLanguageResolver.bundled.distinct().size)
    }

    @Test fun dotfilesAndBuildFileNamesAreRecognized() {
        val examples = mapOf("home/.bashrc" to "shell", ".zshrc" to "shell", ".profile" to "shell",
            "gradlew" to "shell", "Dockerfile" to "dockerfile", "dockerfile.dev" to "dockerfile",
            "Containerfile" to "dockerfile", "Makefile" to "makefile", "GNUmakefile" to "makefile", "rules.mk" to "makefile")
        examples.forEach { (path, language) -> assertEquals(path, language, EditorLanguageResolver.resolve(path)) }
        assertNull(EditorLanguageResolver.resolve("not-a-Makefile.txt"))
        assertNull(EditorLanguageResolver.resolve("DockerfileNotes"))
    }

    @Test fun exactShebangProgramsRecognizeExtensionlessScriptsWithoutExecutingAnything() {
        val examples = mapOf("#!/bin/sh" to "shell", "#!/usr/bin/env bash" to "shell",
            "#!/usr/bin/env -S python3 -u" to "python", "#!/usr/bin/python3.12" to "python",
            "\uFEFF#!/bin/zsh\r" to "shell", "#!/usr/bin/env node" to "javascript")
        examples.forEach { (header, language) -> assertEquals(header, language, EditorLanguageResolver.resolve("script", header)) }
        listOf("echo sh", "#!/bin/fishier", "#!/usr/bin/env", "#!/usr/bin/env python-malicious", "#!/bin/unknown\n#!/bin/sh")
            .forEach { assertNull(it, EditorLanguageResolver.resolve("script", it)) }
        assertEquals("markdown", EditorLanguageResolver.resolve("README.md", "#!/bin/sh"))
        assertNull(EditorLanguageResolver.resolve("unknown.txt"))
    }

    @Test fun matchingIsIndependentOfDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("dockerfile", EditorLanguageResolver.resolve("DOCKERFILE"))
            assertEquals("markdown", EditorLanguageResolver.resolve("README.MARKDOWN"))
        } finally { Locale.setDefault(original) }
    }
}
