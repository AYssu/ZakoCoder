package dev.zakocoder.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class ProjectTemplatesTest {
    private val projectName = "My Project"

    @Test fun otherTemplateCreatesAnEmptyFolder() {
        assertTrue(ProjectTemplates.files(ProjectTemplate.Other, projectName).isEmpty())
    }

    @Test fun entriesAreOfferedInTheDocumentedOrder() {
        assertEquals(listOf(ProjectTemplate.Web, ProjectTemplate.Node, ProjectTemplate.Python,
            ProjectTemplate.Cpp, ProjectTemplate.Go, ProjectTemplate.Rust, ProjectTemplate.Other),
            ProjectTemplates.entries)
        ProjectTemplates.entries.forEach {
            assertNotEquals(0, it.label)
            assertNotEquals(0, it.description)
        }
    }

    @Test fun everyTemplateProvidesBoundedSafeFiles() {
        val expected = mapOf(
            ProjectTemplate.Web to setOf("index.html", "styles.css", "app.js", "README.md"),
            ProjectTemplate.Node to setOf("package.json", "index.js", "README.md", ".gitignore"),
            ProjectTemplate.Python to setOf("main.py", "requirements.txt", "README.md", ".gitignore"),
            ProjectTemplate.Cpp to setOf("CMakeLists.txt", "src/main.cpp", "README.md", ".gitignore"),
            ProjectTemplate.Go to setOf("go.mod", "main.go", "README.md", ".gitignore"),
            ProjectTemplate.Rust to setOf("Cargo.toml", "src/main.rs", "README.md", ".gitignore"),
        )
        expected.forEach { (template, names) ->
            val files = ProjectTemplates.files(template, projectName)
            assertEquals(names, files.keys)
            assertTrue(files.size <= ProjectTemplates.MAX_FILES)
            files.forEach { (path, content) ->
                assertTrue("unsafe path: $path", ProjectTemplates.isSafeRelativePath(path))
                assertEquals("duplicate path: $path", files.keys.size, files.keys.distinct().size)
                assertTrue("too large: $path", content.toByteArray(StandardCharsets.UTF_8).size <= ProjectTemplates.MAX_FILE_BYTES)
                assertFalse("binary content: $path", content.contains('\u0000'))
            }
        }
    }

    @Test fun identifierCleansInvalidInput() {
        assertEquals("my-project", ProjectTemplates.identifier("My Project"))
        assertEquals("hello-world", ProjectTemplates.identifier("  Hello__World!!  "))
        assertEquals("abc", ProjectTemplates.identifier("AbC"))
        assertEquals("abc", ProjectTemplates.identifier("-abc-"))
        assertEquals("app", ProjectTemplates.identifier("中文项目"))
        assertEquals("app", ProjectTemplates.identifier("---"))
        assertEquals("app", ProjectTemplates.identifier(""))
        assertEquals("caf", ProjectTemplates.identifier("café"))
        assertEquals("a".repeat(ProjectTemplates.MAX_IDENTIFIER_LENGTH),
            ProjectTemplates.identifier("A".repeat(80)))
    }

    @Test fun contentsUseTheCleanedIdentifierAndRawProjectName() {
        val identifier = ProjectTemplates.identifier(projectName)
        assertEquals("my-project", identifier)

        val node = ProjectTemplates.files(ProjectTemplate.Node, projectName)
        assertTrue(node.getValue("package.json").contains("\"name\": \"$identifier\""))
        assertTrue(node.getValue("index.js").contains("My Project"))
        assertTrue(node.getValue("README.md").startsWith("# My Project"))

        val web = ProjectTemplates.files(ProjectTemplate.Web, projectName)
        assertTrue(web.getValue("index.html").contains("<title>My Project</title>"))
        assertTrue(web.getValue("README.md").startsWith("# My Project"))

        val python = ProjectTemplates.files(ProjectTemplate.Python, projectName)
        assertTrue(python.getValue("main.py").contains(identifier))
        assertTrue(python.getValue("README.md").startsWith("# My Project"))
    }

    @Test fun extendedTemplatesUseCleanedIdentifierAndRawProjectName() {
        val identifier = ProjectTemplates.identifier(projectName)
        assertEquals("my-project", identifier)

        val cpp = ProjectTemplates.files(ProjectTemplate.Cpp, projectName)
        assertTrue(cpp.getValue("CMakeLists.txt").contains("cmake_minimum_required(VERSION 3.20)"))
        assertTrue(cpp.getValue("CMakeLists.txt").contains("project(my-project LANGUAGES CXX)"))
        assertTrue(cpp.getValue("CMakeLists.txt").contains("set(CMAKE_CXX_STANDARD 17)"))
        assertTrue(cpp.getValue("CMakeLists.txt").contains("add_executable(my-project src/main.cpp)"))
        assertTrue(cpp.getValue("src/main.cpp").contains("#include <iostream>"))
        assertTrue(cpp.getValue("src/main.cpp").contains("Hello from My Project"))
        assertTrue(cpp.getValue("README.md").startsWith("# My Project"))
        assertEquals("build/\n.cache/", cpp.getValue(".gitignore"))

        val go = ProjectTemplates.files(ProjectTemplate.Go, projectName)
        assertEquals("module my-project\n\ngo 1.22", go.getValue("go.mod"))
        assertTrue(go.getValue("main.go").contains("package main"))
        assertTrue(go.getValue("main.go").contains("import \"fmt\""))
        assertTrue(go.getValue("main.go").contains("fmt.Println(\"Hello from My Project\")"))
        assertTrue(go.getValue("README.md").startsWith("# My Project"))
        assertEquals("/bin/\n*.exe", go.getValue(".gitignore"))

        val rust = ProjectTemplates.files(ProjectTemplate.Rust, projectName)
        assertTrue(rust.getValue("Cargo.toml").contains("[package]"))
        assertTrue(rust.getValue("Cargo.toml").contains("name = \"my-project\""))
        assertTrue(rust.getValue("Cargo.toml").contains("edition = \"2021\""))
        assertTrue(rust.getValue("src/main.rs").contains("fn main()"))
        assertTrue(rust.getValue("src/main.rs").contains("println!(\"Hello from My Project\")"))
        assertTrue(rust.getValue("README.md").startsWith("# My Project"))
        assertEquals("/target/", rust.getValue(".gitignore"))
    }

    @Test fun cLikeTemplatesEscapeDoubleQuotes() {
        val quoted = "Say \"Hi\""
        val expected = "Hello from Say \\\"Hi\\\""
        assertTrue(ProjectTemplates.files(ProjectTemplate.Cpp, quoted).getValue("src/main.cpp").contains(expected))
        assertTrue(ProjectTemplates.files(ProjectTemplate.Go, quoted).getValue("main.go").contains(expected))
        assertTrue(ProjectTemplates.files(ProjectTemplate.Rust, quoted).getValue("src/main.rs").contains(expected))
    }

    @Test fun markupInProjectNamesIsEscaped() {
        val hostile = "<script>alert(1)</script>"
        val web = ProjectTemplates.files(ProjectTemplate.Web, hostile)
        val html = web.getValue("index.html")
        assertFalse(html.contains(hostile))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertEquals("script-alert-1-script", ProjectTemplates.identifier(hostile))
    }

    @Test fun safeRelativePathsRejectTraversalAndAbsoluteForms() {
        listOf("", "/etc/passwd", "\\windows", "a\\b", ".", "..", "a/../b", "a//b",
            "a/\u0000b", "C:/windows", "../outside", "a/", "/", "./a").forEach {
            assertFalse("should reject: $it", ProjectTemplates.isSafeRelativePath(it))
        }
        listOf("index.html", "src/main.py", ".gitignore", "a-b/c_d.txt").forEach {
            assertTrue("should accept: $it", ProjectTemplates.isSafeRelativePath(it))
        }
    }
}
