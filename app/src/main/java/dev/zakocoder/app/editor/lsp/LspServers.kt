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
    ;
    fun languageFor(extension: String): String = when (extension.lowercase()) { "js" -> "javascript"; "jsx" -> "javascriptreact"; "tsx" -> "typescriptreact"; else -> languageId }
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
