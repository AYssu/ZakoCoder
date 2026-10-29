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
            ProjectTemplate.AndroidJava, ProjectTemplate.AndroidKotlin, ProjectTemplate.AndroidNative, ProjectTemplate.Other),
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
