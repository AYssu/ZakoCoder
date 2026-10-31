package dev.zakocoder.app

import dev.zakocoder.app.projects.ProjectDetector
import dev.zakocoder.app.projects.ProjectKind
import dev.zakocoder.app.runtime.DevelopmentTools
import dev.zakocoder.app.runtime.ToolStatus
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class ProjectDetectorTest {
    @Test fun detectsRealManifestsAndIgnoresDependencyTrees() {
        val root = Files.createTempDirectory("zako-detect").toFile()
        try {
            File(root, "package.json").writeText("{}")
            File(root, "server").mkdir()
            File(root, "server/pyproject.toml").writeText("[project]")
            File(root, "node_modules/fake").mkdirs()
            File(root, "node_modules/fake/Cargo.toml").writeText("[package]")
            assertEquals(setOf(ProjectKind.Node, ProjectKind.Python), ProjectDetector.detect(root))
            assertTrue(DevelopmentTools.dependencies(root).single().command == "npm install")
            File(root, "package-lock.json").writeText("{}")
            assertTrue(DevelopmentTools.dependencies(root).single().command == "npm install")
        } finally { root.deleteRecursively() }
    }
    @Test fun absentProbeIsBrokenAndMissingToolsProduceOnlyCatalogPackages() {
        val tools = DevelopmentTools.required(setOf(ProjectKind.Node))
        val result = DevelopmentTools.parse("ZAKO_TOOL\tbash\tReady\t5.2\nZAKO_TOOL\tnode\tMissing\t-\n", tools)
        assertEquals(ToolStatus.Ready, result.single { it.id == "bash" }.status)
        assertEquals(ToolStatus.Broken, result.single { it.id == "npm" }.status)
        assertTrue("nodejs" in DevelopmentTools.packages(result))
        assertThrows(IllegalArgumentException::class.java) { DevelopmentTools.installScript(listOf("curl; rm -rf /")) }
    }

    @Test fun flutterDependenciesRequireTheRootFlutterManifest() {
        val root = Files.createTempDirectory("zako-flutter-deps").toFile()
        try {
            File(root, "pubspec.yaml").writeText("name: app\ndependencies:\n  flutter:\n    sdk: flutter\n")
            assertEquals(listOf("flutter pub get"), DevelopmentTools.dependencies(root).map { it.command })
            File(root, "nested").mkdir()
            File(root, "nested/pubspec.yaml").writeText(File(root, "pubspec.yaml").readText())
            File(root, "pubspec.yaml").writeText("name: dart_only\n")
            assertTrue(DevelopmentTools.dependencies(root).isEmpty())
        } finally { root.deleteRecursively() }
    }
}
