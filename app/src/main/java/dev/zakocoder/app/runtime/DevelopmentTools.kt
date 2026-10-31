package dev.zakocoder.app.runtime

import dev.zakocoder.app.projects.ProjectKind
import java.io.File

data class DevelopmentTool(val id: String, val probe: String, val packages: List<String>)
enum class ToolStatus { Ready, Missing, Broken }
data class ToolCheck(val id: String, val status: ToolStatus, val version: String)
data class DependencyPlan(val command: String, val cwd: String)

object DevelopmentTools {
    val catalog = listOf(
        DevelopmentTool("bash", "bash --version", emptyList()),
        DevelopmentTool("zsh", "zsh --version", listOf("zsh")),
        DevelopmentTool("git", "git --version", listOf("git", "ca-certificates", "curl")),
        DevelopmentTool("node", "node --version", listOf("nodejs")),
        DevelopmentTool("npm", "npm --version", listOf("npm", "curl", "ca-certificates")),
        DevelopmentTool("python", "python3 --version", listOf("python3")),
        DevelopmentTool("pip", "python3 -m pip --version", listOf("python3-pip")),
        DevelopmentTool("venv", "python3 -c 'import venv, ensurepip; print(\"venv / ensurepip\")'", listOf("python3-venv")),
        DevelopmentTool("lsp-python", "/opt/zakocoder/lsp/python/bin/pylsp --version", listOf("python3", "python3-venv")),
        DevelopmentTool("lsp-typescript", "/opt/zakocoder/lsp/typescript/node_modules/.bin/typescript-language-server --version", listOf("nodejs", "npm", "curl", "ca-certificates")),
        DevelopmentTool("go", "go version", listOf("golang-go")),
        DevelopmentTool("rustc", "rustc --version", listOf("rustc")),
        DevelopmentTool("cargo", "cargo --version", listOf("cargo")),
        DevelopmentTool("java", "java -version", listOf("openjdk-17-jdk-headless")),
        DevelopmentTool("javac", "javac -version", listOf("openjdk-17-jdk-headless")),
        DevelopmentTool("cc", "cc --version", listOf("build-essential")),
        DevelopmentTool("make", "make --version", listOf("build-essential")),
        DevelopmentTool("cmake", "cmake --version", listOf("cmake", "pkg-config")),
        DevelopmentTool("android-sdk", AndroidToolchain.PROBE, AndroidToolchain.packages),
        DevelopmentTool("android-ndk", AndroidNdkToolchain.PROBE, AndroidNdkToolchain.packages),
        DevelopmentTool("flutter", "flutter --version", emptyList()),
    )
    fun required(kinds: Set<ProjectKind>): List<DevelopmentTool> {
        val ids = setOf("bash", "git") + kinds.flatMap { it.tools }
        return catalog.filter { it.id in ids }
    }
    fun doctorScript(tools: List<DevelopmentTool>): String = tools.joinToString("\n") { tool ->
        val executable = when (tool.id) { "python", "pip", "venv" -> "python3"; "lsp-python" -> "/opt/zakocoder/lsp/python/bin/pylsp"; "lsp-typescript" -> "/opt/zakocoder/lsp/typescript/node_modules/.bin/typescript-language-server"; "android-sdk" -> "sdkmanager"; "android-ndk" -> "${AndroidNdkToolchain.PATH}/clang"; else -> tool.id }
        """
            if ! command -v $executable >/dev/null 2>&1; then
              printf 'ZAKO_TOOL\t${tool.id}\tMissing\t-\n'
            else
              version=${'$'}(${tool.probe} 2>&1); code=${'$'}?
              if [ "${'$'}code" -eq 0 ]; then state=Ready; else state=Broken; fi
              printf 'ZAKO_TOOL\t${tool.id}\t%s\t%s\n' "${'$'}state" "${'$'}(printf '%s' "${'$'}version" | head -n 1 | tr '\t\r' '  ')"
            fi
        """.trimIndent()
    }
    fun parse(output: String, tools: List<DevelopmentTool>): List<ToolCheck> {
        val lines = output.lineSequence().filter { it.startsWith("ZAKO_TOOL\t") }.mapNotNull { line ->
            val parts = line.split('\t', limit = 4)
            if (parts.size != 4) null else {
                val state = ToolStatus.valueOf(parts[2])
                ToolCheck(parts[1], if (parts[1] == "npm" && state == ToolStatus.Ready && !NpmCompatibility.supported(parts[3])) ToolStatus.Broken else state, parts[3])
            }
        }.associateBy { it.id }
        return tools.map { lines[it.id] ?: ToolCheck(it.id, ToolStatus.Broken, "No probe result") }
    }
    fun packages(checks: List<ToolCheck>): List<String> = checks.filter { it.status != ToolStatus.Ready }
        .flatMap { check -> catalog.single { it.id == check.id }.packages }.distinct().sorted()
    fun installScript(packages: List<String>): String {
        require(packages.isNotEmpty() && packages.all { name -> catalog.any { name in it.packages } })
        return "set -e\napt-get -o APT::Update::Error-Mode=any update\napt-get install -y --no-install-recommends " + packages.joinToString(" ") + if ("npm" in packages) "\n" + NpmCompatibility.installScript() else ""
    }
    /** Only the selected root's manifests produce plans. Monorepo subprojects can be run explicitly in the terminal. */
    fun dependencies(root: File): List<DependencyPlan> = buildList {
        // npm ci removes node_modules first; bundled PRoot can leave simulated hard-link entries behind.
        // Prefer the incremental developer install until that cleanup path is reliable on-device.
        if (File(root, "package.json").isFile) add(DependencyPlan("npm install", "."))
        if (File(root, "requirements.txt").isFile) add(DependencyPlan("python3 -m venv .venv && .venv/bin/python -m pip install -r requirements.txt", "."))
        else if (File(root, "pyproject.toml").isFile || File(root, "setup.py").isFile) add(DependencyPlan("python3 -m venv .venv && .venv/bin/python -m pip install -e .", "."))
        if (File(root, "go.mod").isFile) add(DependencyPlan("go mod download", "."))
        if (File(root, "Cargo.toml").isFile) add(DependencyPlan("cargo fetch", "."))
        if (File(root, "gradlew").isFile) add(DependencyPlan("sh ./gradlew dependencies --console=plain", "."))
    }
}
