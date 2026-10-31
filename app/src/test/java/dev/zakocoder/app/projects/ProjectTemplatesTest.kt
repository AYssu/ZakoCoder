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
        assertEquals(listOf(ProjectTemplate.Web, ProjectTemplate.Node, ProjectTemplate.Python, ProjectTemplate.Flutter,
            ProjectTemplate.AndroidJava, ProjectTemplate.AndroidKotlin, ProjectTemplate.AndroidNative,
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

    @Test fun androidTemplatesProvideBuildableTextOnlyScaffolds() {
        listOf(ProjectTemplate.AndroidJava, ProjectTemplate.AndroidKotlin).forEach { template ->
            val kotlin = template == ProjectTemplate.AndroidKotlin
            val files = ProjectTemplates.files(template, projectName)
            val sourcePath = "app/src/main/${if (kotlin) "kotlin" else "java"}/dev/zakocoder/generated/MainActivity.${if (kotlin) "kt" else "java"}"
            assertEquals(setOf("settings.gradle.kts", "build.gradle.kts", "gradle.properties",
                "app/build.gradle.kts", "app/src/main/AndroidManifest.xml",
                "app/src/main/res/values/strings.xml", sourcePath, ".gitignore", "README.md"), files.keys)
            assertEquals(9, files.size)
            files.forEach { (path, contents) ->
                assertTrue(ProjectTemplates.isSafeRelativePath(path))
                assertTrue(contents.toByteArray(StandardCharsets.UTF_8).size <= ProjectTemplates.MAX_FILE_BYTES)
                assertFalse(contents.contains('\u0000'))
            }
            assertTrue(files.getValue("build.gradle.kts").contains("8.13.2"))
            assertEquals(kotlin, files.getValue("build.gradle.kts").contains("2.2.21"))
            val appBuild = files.getValue("app/build.gradle.kts")
            assertTrue(appBuild.contains("compileSdk = 36"))
            assertTrue(appBuild.contains("buildToolsVersion = \"36.0.0\""))
            assertTrue(appBuild.contains("minSdk = 26"))
            assertTrue(appBuild.contains("targetSdk = 35"))
            assertFalse(appBuild.contains("compose", ignoreCase = true))
            assertFalse(appBuild.contains("ndk", ignoreCase = true))
            assertFalse(appBuild.contains("appcompat", ignoreCase = true))
            assertTrue(files.getValue(sourcePath).contains("android.app.Activity"))
            assertTrue(files.getValue("README.md").contains("gradle --no-daemon :app:assembleDebug"))
            assertFalse(files.keys.any { it.contains("gradlew") || it.endsWith(".jar") || it == "local.properties" })
            assertFalse(files.values.any { it.contains("/opt/") || it.contains("/home/") })
        }
    }

    @Test fun nativeTemplateConnectsKotlinJniToArm64CmakeBuild() {
        val files = ProjectTemplates.files(ProjectTemplate.AndroidNative, projectName)
        assertEquals(11, files.size)
        files.forEach { (path, contents) ->
            assertTrue(ProjectTemplates.isSafeRelativePath(path))
            assertTrue(contents.toByteArray(StandardCharsets.UTF_8).size <= ProjectTemplates.MAX_FILE_BYTES)
            assertFalse(contents.contains('\u0000'))
        }
        val appBuild = files.getValue("app/build.gradle.kts")
        assertTrue(appBuild.contains("ndkVersion = \"28.2.13676358\""))
        assertTrue(appBuild.contains("abiFilters += \"arm64-v8a\""))
        assertTrue(appBuild.contains("arguments += \"-DANDROID_STL=c++_static\""))
        assertTrue(appBuild.contains("path = file(\"src/main/cpp/CMakeLists.txt\")"))
        assertTrue(appBuild.contains("version = \"3.28.3\""))
        val cmake = files.getValue("app/src/main/cpp/CMakeLists.txt")
        assertTrue(cmake.contains("add_library(zako_native SHARED native-lib.cpp)"))
        assertTrue(cmake.contains("cxx_std_17"))
        val activity = files.getValue("app/src/main/kotlin/dev/zakocoder/generated/MainActivity.kt")
        assertTrue(activity.contains("System.loadLibrary(\"zako_native\")"))
        assertTrue(activity.contains("private external fun greeting(): String"))
        assertTrue(activity.contains("text = greeting()"))
        val cpp = files.getValue("app/src/main/cpp/native-lib.cpp")
        assertTrue(cpp.contains("Java_dev_zakocoder_generated_MainActivity_greeting"))
        assertTrue(cpp.contains("Hello from C++!"))
        assertFalse(files.keys.any { it.contains("gradlew") || it.endsWith(".jar") || it == "local.properties" })
        assertFalse(files.values.any { it.contains("/opt/") || it.contains("/home/") })
    }

    @Test fun androidProjectsHaveIndependentApplicationIdsAndStableSourceNamespace() {
        val applicationIds = mutableSetOf<String>()
        listOf(ProjectTemplate.AndroidJava, ProjectTemplate.AndroidKotlin, ProjectTemplate.AndroidNative).forEach { template ->
            repeat(2) {
                val files = ProjectTemplates.files(template, projectName)
                val appBuild = files.getValue("app/build.gradle.kts")
                val applicationId = Regex("applicationId = \"([^\"]+)\"").find(appBuild)!!.groupValues[1]
                assertTrue(applicationId.matches(Regex("dev\\.zakocoder\\.generated\\.p[0-9a-f]{32}")))
                assertTrue("New projects must not share an install identity", applicationIds.add(applicationId))
                assertTrue(appBuild.contains("namespace = \"dev.zakocoder.generated\""))
                val activity = files.entries.single { it.key.endsWith("MainActivity.kt") || it.key.endsWith("MainActivity.java") }
                assertTrue(activity.value.startsWith("package dev.zakocoder.generated"))
                assertTrue(files.getValue("app/src/main/AndroidManifest.xml").contains("android:name=\".MainActivity\""))
                // The ID is literal build configuration, never regenerated by Gradle on each build.
                assertFalse(appBuild.contains("randomUUID"))
                assertFalse(appBuild.contains("UUID"))
            }
        }
        assertEquals(6, applicationIds.size)
    }

    @Test fun androidProjectNamesAreResourceEscapedAndNeverInjectedInSource() {
        val name = "@test \" & < > ' \\ \$name\nnext"
        listOf(ProjectTemplate.AndroidJava, ProjectTemplate.AndroidKotlin, ProjectTemplate.AndroidNative).forEach { template ->
            val files = ProjectTemplates.files(template, name)
            val xml = files.getValue("app/src/main/res/values/strings.xml")
            assertTrue(xml.contains("formatted=\"false\""))
            assertTrue(xml.contains("&amp; &lt; &gt;"))
            assertTrue(xml.contains("\\\""))
            assertTrue(xml.contains("\\'"))
            assertTrue(xml.contains("\\\\"))
            assertTrue(xml.contains("\\nnext"))
            val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(java.io.ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
            assertEquals("resources", document.documentElement.tagName)
            assertFalse(files.filterKeys { it.endsWith(".kt") || it.endsWith(".java") }.values.any { it.contains(name) })
            assertTrue(files.getValue("settings.gradle.kts").contains("rootProject.name = \"test-name-next\""))
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
        assertTrue(rust.getValue("src/main.rs").contains("println!(\"{}\", \"Hello from My Project\")"))
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

    @Test fun rustFormatStringKeepsProjectNameOutOfTheTemplate() {
        val name = "Demo {}"
        val rust = ProjectTemplates.files(ProjectTemplate.Rust, name)
        val main = rust.getValue("src/main.rs")
        assertTrue(main.contains("println!(\"{}\", \"Hello from Demo {}\");"))
        assertFalse("name must not be embedded in the format string", main.contains("println!(\"Hello from"))
        assertEquals("println!(\"{}\", \"Hello from Demo {}\");", main.lines().single { it.contains("println!") }.trim())
        assertTrue("Cargo.toml name must stay a valid identifier", rust.getValue("Cargo.toml").contains("name = \"demo\""))

        // Braces in the raw name must not be able to inject format placeholders either.
        val bracesOnly = ProjectTemplates.files(ProjectTemplate.Rust, "{{}}{{}}").getValue("src/main.rs")
        assertEquals("println!(\"{}\", \"Hello from {{}}{{}}\");", bracesOnly.lines().single { it.contains("println!") }.trim())
    }

    @Test fun rustPackageNameAvoidsNumericAndKeywordNames() {
        assertEquals("app-123", ProjectTemplates.rustPackageName("123"))
        assertEquals("app-fn", ProjectTemplates.rustPackageName("fn"))
        assertEquals("app-match", ProjectTemplates.rustPackageName("match"))
        assertEquals("app-self", ProjectTemplates.rustPackageName("self"))
        assertEquals("app-Self", ProjectTemplates.rustPackageName("Self"))
        assertEquals("demo", ProjectTemplates.rustPackageName("demo"))
        assertEquals("my-project", ProjectTemplates.rustPackageName("my-project"))
        assertEquals("app", ProjectTemplates.rustPackageName(""))
        assertEquals(ProjectTemplates.MAX_RUST_PACKAGE_LENGTH,
            ProjectTemplates.rustPackageName("a".repeat(ProjectTemplates.MAX_RUST_PACKAGE_LENGTH + 10)).length)

        val keywords = setOf("as", "break", "const", "continue", "crate", "else", "enum", "extern",
            "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut",
            "pub", "ref", "return", "self", "static", "struct", "super", "trait", "true", "type",
            "unsafe", "use", "where", "while", "async", "await", "dyn", "abstract", "become", "box",
            "do", "final", "macro", "override", "priv", "typeof", "unsized", "virtual", "yield", "try")
        listOf("123", "fn", "match", "self", "Self", "my-project").forEach { rawName ->
            val cargo = ProjectTemplates.files(ProjectTemplate.Rust, rawName).getValue("Cargo.toml")
            val name = Regex("name = \"([^\"]+)\"").find(cargo)!!.groupValues[1]
            assertTrue("must start with a letter: $name", name.first().isLetter())
            assertTrue("must be a valid Cargo name: $name", name.matches(Regex("[A-Za-z][A-Za-z0-9_-]*")))
            assertFalse("must not be a Rust keyword: $name", name.lowercase() in keywords)
            assertTrue("must respect the length cap: $name", name.length <= ProjectTemplates.MAX_RUST_PACKAGE_LENGTH)
            assertEquals(ProjectTemplates.rustPackageName(ProjectTemplates.identifier(rawName)), name)
        }
    }

    @Test fun cmakeTargetAvoidsReservedTargetsAndMatchesReadme() {
        val reserved = setOf("all", "clean", "help", "install", "test", "package", "package_source",
            "edit_cache", "rebuild_cache", "list_install_components")
        reserved.forEach { reservedName ->
            val target = ProjectTemplates.cmakeTargetName(reservedName)
            assertFalse("target must not stay reserved: $target", target.lowercase() in reserved)
            assertTrue("target must start with a letter: $target", target.first().isLetter())
            assertEquals("reserved targets get the conservative prefix", "app-$reservedName", target)
        }
        assertEquals("app-123", ProjectTemplates.cmakeTargetName("123"))
        assertEquals("my-project", ProjectTemplates.cmakeTargetName("my-project"))
        assertEquals("app", ProjectTemplates.cmakeTargetName(""))
        assertEquals(ProjectTemplates.MAX_CMAKE_TARGET_LENGTH,
            ProjectTemplates.cmakeTargetName("a".repeat(ProjectTemplates.MAX_CMAKE_TARGET_LENGTH + 10)).length)

        val cpp = ProjectTemplates.files(ProjectTemplate.Cpp, "all")
        val cmake = cpp.getValue("CMakeLists.txt")
        val target = Regex("add_executable\\(([^ ]+) src/main\\.cpp\\)").find(cmake)!!.groupValues[1]
        assertNotEquals("all", target)
        assertTrue(target.first().isLetter())
        assertTrue(cmake.contains("project($target LANGUAGES CXX)"))
        val readme = cpp.getValue("README.md")
        assertTrue("README must reference the actual executable: $target", readme.contains("./build/$target"))
        assertFalse("README must not reference the reserved target", readme.contains("./build/all"))
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
