package dev.zakocoder.app.runtime

import dev.zakocoder.app.projects.ProjectKind
import dev.zakocoder.app.editor.lsp.LspServer
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
        DevelopmentTool("flutter", FlutterToolchain.PROBE, FlutterToolchain.packages),
    ) + LspServer.entries.map { DevelopmentTool(it.bundleId, it.probe, it.packages) }
    fun required(kinds: Set<ProjectKind>): List<DevelopmentTool> {
        val ids = setOf("bash", "git") + kinds.flatMap { it.tools }
        return catalog.filter { it.id in ids }
    }
    fun doctorScript(tools: List<DevelopmentTool>): String = tools.joinToString("\n") { tool ->
        val executable = LspServer.entries.firstOrNull { it.bundleId == tool.id }?.executable ?: when (tool.id) { "python", "pip", "venv" -> "python3"; "android-sdk" -> "sdkmanager"; "android-ndk" -> "${AndroidNdkToolchain.PATH}/clang"; else -> tool.id }
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
        val preserveJava = if ("openjdk-21-jdk-headless" in packages) preserveJavaAlternativesScript else ""
        return "set -e\n" + preserveJava + "apt-get -o APT::Update::Error-Mode=any update\napt-get install -y --no-install-recommends " + packages.joinToString(" ") + if ("npm" in packages) "\n" + NpmCompatibility.installScript() else ""
    }
    /** Preserve existing selected tools; --set intentionally pins previous auto choices as manual. */
    private val preserveJavaAlternativesScript = """
        zako_java_choices=${'$'}(mktemp)
        zako_restore_java() {
            zako_install_status=${'$'}?
            trap - EXIT HUP INT TERM
            set +e
            while IFS="${'$'}(printf '\t')" read -r zako_java_tool zako_java_value; do
                if ! update-alternatives --set "${'$'}zako_java_tool" "${'$'}zako_java_value"; then
                    printf 'Unable to restore Java alternative: %s\n' "${'$'}zako_java_tool" >&2
                    zako_install_status=1
                    continue
                fi
                zako_java_query=${'$'}(update-alternatives --query "${'$'}zako_java_tool")
                if [ "${'$'}?" -ne 0 ] || [ "${'$'}(printf '%s\n' "${'$'}zako_java_query" | sed -n 's/^Value: //p')" != "${'$'}zako_java_value" ]; then
                    printf 'Java alternative verification failed: %s\n' "${'$'}zako_java_tool" >&2
                    zako_install_status=1
                fi
            done < "${'$'}zako_java_choices"
            rm -f -- "${'$'}zako_java_choices" || zako_install_status=1
            exit "${'$'}zako_install_status"
        }
        trap zako_restore_java EXIT
        trap 'exit 129' HUP
        trap 'exit 130' INT
        trap 'exit 143' TERM
        for zako_java_tool in java javac jar javadoc javap jcmd jconsole jdb jdeprscan jdeps jfr jimage jinfo jlink jmap jmod jpackage jps jrunscript jshell jstack jstat jstatd keytool rmiregistry serialver; do
            if zako_java_query=${'$'}(update-alternatives --query "${'$'}zako_java_tool" 2>/dev/null); then
                zako_java_value=${'$'}(printf '%s\n' "${'$'}zako_java_query" | sed -n 's/^Value: //p')
                if [ -n "${'$'}zako_java_value" ] && printf '%s\n' "${'$'}zako_java_query" | grep -Fqx -- "Alternative: ${'$'}zako_java_value"; then
                    printf '%s\t%s\n' "${'$'}zako_java_tool" "${'$'}zako_java_value" >> "${'$'}zako_java_choices"
                fi
            fi
        done
    """.trimIndent() + "\n"
    /** Only the selected root's manifests produce plans. Monorepo subprojects can be run explicitly in the terminal. */
    fun dependencies(root: File): List<DependencyPlan> = buildList {
        // npm ci removes node_modules first; bundled PRoot can leave simulated hard-link entries behind.
        // Prefer the incremental developer install until that cleanup path is reliable on-device.
        if (File(root, "package.json").isFile) add(DependencyPlan("npm install", "."))
        if (File(root, "requirements.txt").isFile) add(DependencyPlan("python3 -m venv .venv && .venv/bin/python -m pip install -r requirements.txt", "."))
        else if (File(root, "pyproject.toml").isFile || File(root, "setup.py").isFile) add(DependencyPlan("python3 -m venv .venv && .venv/bin/python -m pip install -e .", "."))
        if (File(root, "go.mod").isFile) add(DependencyPlan("go mod download", "."))
        if (File(root, "Cargo.toml").isFile) add(DependencyPlan("cargo fetch", "."))
        val pubspec = File(root, "pubspec.yaml")
        if (pubspec.isFile && pubspec.length() < 262144 && !java.nio.file.Files.isSymbolicLink(pubspec.toPath()) &&
            Regex("(?m)^\\s*flutter\\s*:").containsMatchIn(pubspec.readText())) {
            add(DependencyPlan("flutter pub get", "."))
        }
        if (File(root, "gradlew").isFile) add(DependencyPlan("sh ./gradlew dependencies --console=plain", "."))
    }
}
