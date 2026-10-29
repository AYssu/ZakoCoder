package dev.zakocoder.app.projects

import androidx.annotation.StringRes
import dev.zakocoder.app.R
import java.io.File

enum class ProjectKind(@param:StringRes val label: Int, val tools: List<String>) {
    Node(R.string.kind_node, listOf("node", "npm")),
    Python(R.string.kind_python, listOf("python", "pip", "venv")),
    Go(R.string.kind_go, listOf("go")),
    Rust(R.string.kind_rust, listOf("rustc", "cargo")),
    Jvm(R.string.kind_jvm, listOf("java", "javac")),
    Cpp(R.string.kind_cpp, listOf("cc", "make", "cmake")),
    Android(R.string.kind_android, listOf("java", "javac", "android-sdk")),
    Flutter(R.string.kind_flutter, listOf("flutter", "android-sdk")),
}

object ProjectDetector {
    val excluded = setOf("node_modules", ".gradle", ".venv", "venv", "target", ".dart_tool", "build", ".git")
    fun detect(root: File): Set<ProjectKind> {
        val found = linkedSetOf<ProjectKind>()
        var visited = 0
        fun scan(directory: File, depth: Int) {
            if (depth > 3 || ++visited > 2000) return
            val files = (directory.listFiles() ?: error("Cannot read project directory")).filterNot { java.nio.file.Files.isSymbolicLink(it.toPath()) }
            val names = files.map { it.name }.toSet()
            if ("package.json" in names) found += ProjectKind.Node
            if (names.any { it in setOf("pyproject.toml", "requirements.txt", "setup.py", "Pipfile") }) found += ProjectKind.Python
            if ("go.mod" in names) found += ProjectKind.Go
            if ("AndroidManifest.xml" in names) found += ProjectKind.Android
            if ("Cargo.toml" in names) found += ProjectKind.Rust
            if (names.any { it in setOf("pom.xml", "build.gradle", "build.gradle.kts") }) found += ProjectKind.Jvm
            if (names.any { it in setOf("CMakeLists.txt", "Makefile", "meson.build") }) found += ProjectKind.Cpp
            files.filter { it.isFile && it.name in setOf("build.gradle", "build.gradle.kts", "pubspec.yaml") && it.length() < 262144 }
                .forEach {
                    val content = it.readText()
                    if (it.name == "pubspec.yaml" && Regex("(?m)^\\s*flutter\\s*:").containsMatchIn(content)) found += ProjectKind.Flutter
                    if (it.name.startsWith("build.gradle") && ("com.android.application" in content || "com.android.library" in content || "libs.plugins.android.application" in content || "libs.plugins.android.library" in content)) found += ProjectKind.Android
                }
            files.filter { it.isDirectory && it.name !in excluded && !it.name.startsWith('.') }.forEach { scan(it, depth + 1) }
        }
        scan(root, 0)
        return found
    }
}
