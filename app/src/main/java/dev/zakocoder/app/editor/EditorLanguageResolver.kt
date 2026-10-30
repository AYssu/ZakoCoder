package dev.zakocoder.app.editor

import java.util.Locale

/** Filename and optional first-line detection only; never executes an interpreter. */
object EditorLanguageResolver {
    val bundled = listOf("kotlin", "java", "javascript", "typescript", "python", "shell", "json", "c", "diff",
        "batch", "markdown", "html", "css", "yaml", "toml", "xml", "sql", "go", "rust", "dockerfile", "makefile")

    fun resolve(path: String, firstLine: String = ""): String? {
        val name = path.substringAfterLast('/').lowercase(Locale.ROOT)
        when {
            name in setOf(".bashrc", ".bash_profile", ".bash_login", ".bash_logout", ".profile", ".zshrc", ".zprofile", ".zshenv", ".zlogin", ".zlogout", "gradlew", "configure") -> return "shell"
            name == "dockerfile" || name == "containerfile" || name.startsWith("dockerfile.") || name.startsWith("containerfile.") -> return "dockerfile"
            name in setOf("makefile", "gnumakefile", "bsdmakefile") || name.endsWith(".mk") -> return "makefile"
        }
        val detected = when (name.substringAfterLast('.', "")) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "js", "jsx", "mjs", "cjs" -> "javascript"
            "ts", "tsx", "mts", "cts" -> "typescript"
            "py", "pyw", "pyi" -> "python"
            "sh", "bash", "zsh", "fish", "ksh" -> "shell"
            "bat", "cmd" -> "batch"
            "md", "markdown", "mdown", "mkd" -> "markdown"
            "htm", "html", "xhtml", "vue", "svelte" -> "html"
            "css", "scss", "sass", "less" -> "css"
            "yaml", "yml" -> "yaml"
            "toml" -> "toml"
            "xml", "svg", "xsd", "xsl", "xslt", "plist", "pom" -> "xml"
            "sql" -> "sql"
            "go" -> "go"
            "rs" -> "rust"
            "json", "jsonc", "jsonl", "ipynb" -> "json"
            "c", "h", "cpp", "hpp", "cc", "cxx", "hxx", "hh" -> "c"
            "diff", "patch" -> "diff"
            else -> null
        }
        if (detected != null) return detected
        // Bound work and reject malformed/unknown shebangs. env options need no shell parsing.
        val header = firstLine.take(512).substringBefore('\n').removePrefix("\uFEFF").trimEnd('\r')
        if (!header.startsWith("#!")) return null
        val words = header.drop(2).trim().split(Regex("\\s+"))
        val program = words.firstOrNull()?.substringAfterLast('/') ?: return null
        val interpreter = if (program == "env") {
            words.drop(1).firstOrNull { !it.startsWith('-') && '=' !in it } ?: return null
        } else program
        return when {
            interpreter in setOf("sh", "bash", "dash", "zsh", "fish", "ksh") -> "shell"
            Regex("python(?:[23](?:\\.[0-9]+)?)?").matches(interpreter) -> "python"
            interpreter in setOf("node", "nodejs") -> "javascript"
            else -> null
        }
    }
}
