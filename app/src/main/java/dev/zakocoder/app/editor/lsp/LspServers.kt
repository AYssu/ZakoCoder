package dev.zakocoder.app.editor.lsp

import java.net.URI
import java.util.UUID

/** Fixed, explicit language-server choices; project configuration never supplies executable text. */
enum class LspServer(val languageId: String, val extensions: Set<String>, val executable: String, val script: String, val detectScript: String, val installScript: String) {
    Python("python", setOf("py", "pyi"), "/opt/zakocoder/lsp/python/bin/pylsp",
        "exec /opt/zakocoder/lsp/python/bin/pylsp",
        "test -x /opt/zakocoder/lsp/python/bin/pylsp",
        "set -eu\npython3 -m venv /opt/zakocoder/lsp/python\n/opt/zakocoder/lsp/python/bin/pip install 'python-lsp-server==1.13.1' 'pyflakes==3.4.0'"),
    TypeScript("typescript", setOf("ts", "tsx", "js", "jsx"), "/opt/zakocoder/lsp/typescript/node_modules/.bin/typescript-language-server",
        "exec /opt/zakocoder/lsp/typescript/node_modules/.bin/typescript-language-server --stdio",
        "test -x /opt/zakocoder/lsp/typescript/node_modules/.bin/typescript-language-server",
        "set -eu\ncommand -v node >/dev/null\ncommand -v npm >/dev/null\nmkdir -p /opt/zakocoder/lsp/typescript\nnpm install --prefix /opt/zakocoder/lsp/typescript --no-audit --no-fund --ignore-scripts typescript@5.9.3 typescript-language-server@4.4.0"),
    Java("java", setOf("java"), "/usr/bin/java",
        LspInstallScripts.javaCommand,
        "test -f /opt/zakocoder/lsp/java/1.46.1/.zakocoder-verified && test -x /usr/lib/jvm/java-21-openjdk-$(dpkg --print-architecture)/bin/java",
        LspInstallScripts.java),
    Kotlin("kotlin", setOf("kt", "kts"), "/opt/zakocoder/lsp/kotlin/263.7084.0/bin/intellij-server",
        "exec /opt/zakocoder/lsp/kotlin/263.7084.0/bin/intellij-server --stdio",
        "test -x /opt/zakocoder/lsp/kotlin/263.7084.0/bin/intellij-server && test -x /opt/zakocoder/lsp/kotlin/263.7084.0/jbr/bin/java",
        LspInstallScripts.kotlin),
    Cpp("cpp", setOf("c", "h", "cc", "cpp", "cxx", "c++", "hh", "hpp", "hxx", "h++"), "/usr/bin/clangd",
        "exec /usr/bin/clangd --background-index --clang-tidy=false -j=2",
        "test -x /usr/bin/clangd", "set -eu\n/usr/bin/clangd --version"),
    Go("go", setOf("go"), "/usr/bin/gopls", "exec /usr/bin/gopls", "test -x /usr/bin/gopls",
        "set -eu\n/usr/bin/gopls version"),
    Rust("rust", setOf("rs"), "/opt/zakocoder/lsp/rust/2026-10-26/rust-analyzer",
        "exec /opt/zakocoder/lsp/rust/2026-10-26/rust-analyzer",
        "test -x /opt/zakocoder/lsp/rust/2026-10-26/rust-analyzer", LspInstallScripts.rust),
    Dart("dart", setOf("dart"), "/opt/zakocoder/flutter/bin/cache/dart-sdk/bin/dart",
        "exec /opt/zakocoder/flutter/bin/cache/dart-sdk/bin/dart language-server --protocol=lsp",
        "test -x /opt/zakocoder/flutter/bin/cache/dart-sdk/bin/dart",
        "set -eu\n/opt/zakocoder/flutter/bin/cache/dart-sdk/bin/dart --version"),
    ;
    val bundleId: String get() = "lsp-" + when (this) { TypeScript -> "typescript"; Cpp -> "cpp"; else -> name.lowercase() }
    val packages: List<String> get() = when (this) {
        Python -> listOf("python3", "python3-venv")
        TypeScript -> listOf("nodejs", "npm", "curl", "ca-certificates")
        Java -> listOf("openjdk-21-jdk-headless", "curl", "ca-certificates", "tar", "util-linux")
        Kotlin -> listOf("curl", "ca-certificates", "tar", "util-linux", "libfreetype6", "libfontconfig1", "libxi6", "libxrender1", "libxtst6")
        Cpp -> listOf("clangd", "build-essential")
        Go -> listOf("golang-go", "gopls")
        Rust -> listOf("rustc", "cargo", "rust-src", "curl", "ca-certificates", "gzip", "util-linux")
        Dart -> emptyList()
    }
    val probe: String get() = when (this) {
        Java -> "test -f /opt/zakocoder/lsp/java/1.46.1/.zakocoder-verified && /usr/lib/jvm/java-21-openjdk-$(dpkg --print-architecture)/bin/java -version"
        Kotlin -> "test -x $executable && /opt/zakocoder/lsp/kotlin/263.7084.0/jbr/bin/java -version"
        Go -> "$executable version"
        else -> "$executable --version"
    }
    val connectTimeoutMillis: Long get() = when (this) { Java -> 120_000L; Kotlin -> 180_000L; else -> 45_000L }
    fun languageFor(extension: String): String = when {
        this == Cpp -> if (extension == "c") "c" else "cpp"
        else -> when (extension.lowercase()) { "js" -> "javascript"; "jsx" -> "javascriptreact"; "tsx" -> "typescriptreact"; else -> languageId }
    }
    companion object { fun forPath(path: String): LspServer? = entries.firstOrNull { path.substringAfterLast('.', "").lowercase() in it.extensions } }
}
internal fun lspProjectPath(projectId: String): String {
    require(UUID.fromString(projectId).toString() == projectId)
    return "/workspace/$projectId"
}
internal fun lspDocumentPath(projectId: String, path: String): String {
    require(path.isNotEmpty() && path.length <= 4096 && !path.startsWith('/') && path.none(Char::isISOControl))
    require(path.split('/').none { it.isEmpty() || it == "." || it == ".." || it == ".git" })
    return "${lspProjectPath(projectId)}/$path"
}
internal fun lspFileUri(projectId: String, path: String): String = URI("file", "", lspDocumentPath(projectId, path), null).toASCIIString()
