package dev.zakocoder.app.projects

import androidx.annotation.StringRes
import dev.zakocoder.app.R
import java.util.Locale

/** Initialization choices offered when a project folder is created. */
enum class ProjectTemplate(@param:StringRes val label: Int, @param:StringRes val description: Int) {
    Web(R.string.template_web, R.string.template_web_description),
    Node(R.string.template_node, R.string.template_node_description),
    Python(R.string.template_python, R.string.template_python_description),
    AndroidJava(R.string.template_android_java, R.string.template_android_java_description),
    AndroidKotlin(R.string.template_android_kotlin, R.string.template_android_kotlin_description),
    AndroidNative(R.string.template_android_native, R.string.template_android_native_description),
    Cpp(R.string.template_cpp, R.string.template_cpp_description),
    Go(R.string.template_go, R.string.template_go_description),
    Rust(R.string.template_rust, R.string.template_rust_description),
    Other(R.string.template_other, R.string.template_other_description),
}

/**
 * Pure template contents for newly created projects. [ProjectTemplate.Other] produces no files, so
 * the create flow stays an empty working folder unless the user picks a scaffold.
 *
 * All returned paths are project-relative and are re-validated by [ProjectRepository] before they
 * are written; keeping validation here as well keeps the scaffold contract testable on the JVM.
 */
object ProjectTemplates {
    const val MAX_FILES = 11
    const val MAX_FILE_BYTES = 16 * 1024
    const val MAX_IDENTIFIER_LENGTH = 40
    const val MAX_RUST_PACKAGE_LENGTH = 64
    const val MAX_CMAKE_TARGET_LENGTH = 64
    private const val MAX_PATH_LENGTH = 512

    /** Rust keywords/reserved names that Cargo rejects as package names (compared lowercased). */
    private val RUST_KEYWORDS = setOf(
        "as", "break", "const", "continue", "crate", "else", "enum", "extern", "false", "fn", "for",
        "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return",
        "self", "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where",
        "while", "async", "await", "dyn", "abstract", "become", "box", "do", "final", "macro",
        "override", "priv", "typeof", "unsized", "virtual", "yield", "try",
    )

    /** CMake built-in/reserved target names that must not be used by `add_executable` (lowercased). */
    private val CMAKE_RESERVED_TARGETS = setOf(
        "all", "clean", "help", "install", "test", "package", "package_source", "edit_cache",
        "rebuild_cache", "list_install_components", "run_tests", "nightly", "continuous", "experimental",
    )

    val entries: List<ProjectTemplate> = ProjectTemplate.entries

    /** Relative path to UTF-8 text. Template generation never touches disk. */
    fun files(template: ProjectTemplate, projectName: String): Map<String, String> {
        val identifier = identifier(projectName)
        val generated = when (template) {
            ProjectTemplate.Other -> emptyMap()
            ProjectTemplate.AndroidJava -> AndroidProjectTemplates.files(projectName, kotlin = false)
            ProjectTemplate.AndroidKotlin -> AndroidProjectTemplates.files(projectName, kotlin = true)
            ProjectTemplate.AndroidNative -> AndroidProjectTemplates.files(projectName, kotlin = true, native = true)
            ProjectTemplate.Web -> mapOf(
                "index.html" to webIndex(projectName, identifier),
                "styles.css" to WEB_STYLES,
                "app.js" to WEB_SCRIPT,
                "README.md" to readme(projectName,
                    "由 ZakoCoder 新建的静态网页项目，无需安装依赖。",
                    listOf("`index.html`：页面结构", "`styles.css`：样式", "`app.js`：交互脚本"),
                    "直接在浏览器中打开 `index.html` 预览，或在项目目录启动静态服务器。"),
            )
            ProjectTemplate.Node -> mapOf(
                "package.json" to nodeManifest(identifier),
                "index.js" to nodeEntry(projectName),
                "README.md" to readme(projectName,
                    "由 ZakoCoder 新建的 Node.js 项目。",
                    listOf("`index.js`：入口脚本", "`package.json`：项目信息与运行脚本", "`.gitignore`：忽略依赖与本地环境"),
                    "在项目目录运行 `npm start`（需先安装 Node.js 工具包），或运行 `node index.js`。"),
                ".gitignore" to NODE_GITIGNORE,
            )
            ProjectTemplate.Python -> mapOf(
                "main.py" to pythonEntry(projectName, identifier),
                "requirements.txt" to PYTHON_REQUIREMENTS,
                "README.md" to readme(projectName,
                    "由 ZakoCoder 新建的 Python 项目。",
                    listOf("`main.py`：入口脚本", "`requirements.txt`：第三方依赖清单", "`.gitignore`：忽略缓存与虚拟环境"),
                    "在项目目录运行 `python3 main.py`（需先安装 Python 工具包），再按需编辑 `requirements.txt`。"),
                ".gitignore" to PYTHON_GITIGNORE,
            )
            ProjectTemplate.Cpp -> {
                // CMake reserves target names such as `all`; the executable name in the README must match.
                val target = cmakeTargetName(identifier)
                mapOf(
                    "CMakeLists.txt" to cppManifest(target),
                    "src/main.cpp" to cppEntry(projectName),
                    "README.md" to readme(projectName,
                        "由 ZakoCoder 新建的 C++ 项目，使用 CMake 构建。",
                        listOf("`CMakeLists.txt`：构建配置（C++17）", "`src/main.cpp`：入口源码", "`.gitignore`：忽略构建产物"),
                        "安装 C++ 编译器与 CMake 后，在项目目录运行 `cmake -S . -B build && cmake --build build`，再执行 `./build/$target`。"),
                    ".gitignore" to CPP_GITIGNORE,
                )
            }
            ProjectTemplate.Go -> mapOf(
                "go.mod" to goMod(identifier),
                "main.go" to goEntry(projectName),
                "README.md" to readme(projectName,
                    "由 ZakoCoder 新建的 Go 项目。",
                    listOf("`go.mod`：模块定义", "`main.go`：入口源码", "`.gitignore`：忽略构建产物"),
                    "在项目目录运行 `go run .`（需先安装 Go 工具包），或使用 `go build ./...`。"),
                ".gitignore" to GO_GITIGNORE,
            )
            ProjectTemplate.Rust -> {
                // Cargo rejects package names that start with a digit or are Rust keywords.
                val packageName = rustPackageName(identifier)
                mapOf(
                    "Cargo.toml" to rustManifest(packageName),
                    "src/main.rs" to rustEntry(projectName),
                    "README.md" to readme(projectName,
                        "由 ZakoCoder 新建的 Rust 项目。",
                        listOf("`Cargo.toml`：包定义（`$packageName`）", "`src/main.rs`：入口源码", "`.gitignore`：忽略构建产物"),
                        "在项目目录运行 `cargo run`（需先安装 Rust 工具包），或使用 `cargo build`。"),
                    ".gitignore" to RUST_GITIGNORE,
                )
            }
        }
        require(generated.size <= MAX_FILES) { "Template exceeds the file limit" }
        generated.forEach { (path, content) ->
            require(isSafeRelativePath(path)) { "Template path is not a safe relative path: $path" }
            require(content.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "Template file exceeds the size limit: $path" }
        }
        return generated
    }

    /**
     * Lowercase ASCII identifier for manifests: invalid runs collapse to `-`, edges are trimmed,
     * the result is bounded and falls back to `app` when nothing usable remains.
     */
    fun identifier(name: String): String {
        val folded = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9-]+"), "-").trim('-')
        val bounded = folded.take(MAX_IDENTIFIER_LENGTH).trimEnd('-')
        return bounded.ifEmpty { "app" }
    }

    /**
     * Cargo package name for [identifier]: must start with a letter, must not be a Rust keyword and
     * may only contain `[A-Za-z0-9_-]`. Numeric-leading or reserved names get the `app-` prefix.
     */
    fun rustPackageName(identifier: String): String {
        val sanitized = identifier.replace(Regex("[^A-Za-z0-9_-]"), "-").trim('-')
        val base = sanitized.ifEmpty { "app" }
        val reserved = base.lowercase(Locale.ROOT) in RUST_KEYWORDS
        val prefixed = if (base.first().isLetter() && !reserved) base else "app-$base"
        return prefixed.take(MAX_RUST_PACKAGE_LENGTH).trimEnd('-').ifEmpty { "app" }
    }

    /**
     * CMake target name for [identifier]: avoids CMake reserved targets (`all`, `test`, ...) and
     * numeric-leading names. The generated README uses the same executable name.
     */
    fun cmakeTargetName(identifier: String): String {
        val sanitized = identifier.replace(Regex("[^A-Za-z0-9_-]"), "-").trim('-')
        val base = sanitized.ifEmpty { "app" }
        val reserved = base.lowercase(Locale.ROOT) in CMAKE_RESERVED_TARGETS
        val prefixed = if (base.first().isLetter() && !reserved) base else "app-$base"
        return prefixed.take(MAX_CMAKE_TARGET_LENGTH).trimEnd('-').ifEmpty { "app" }
    }

    /** Guards both generation and disk writes: relative, segment-safe, no controls or separators. */
    fun isSafeRelativePath(path: String): Boolean {
        if (path.isEmpty() || path.length > MAX_PATH_LENGTH) return false
        if (path.startsWith('/') || path.contains('\\') || path.contains(':')) return false
        if (path.any(Char::isISOControl)) return false
        val segments = path.split('/')
        return segments.isNotEmpty() && segments.all { it.isNotEmpty() && it != "." && it != ".." }
    }

    private fun readme(projectName: String, intro: String, files: List<String>, next: String): String = buildString {
        append("# ").append(projectName).append('\n')
        append('\n').append(intro).append('\n')
        append("\n## 文件\n\n")
        files.forEach { append("- ").append(it).append('\n') }
        append("\n## 下一步\n\n").append(next).append('\n')
    }

    private fun webIndex(projectName: String, identifier: String): String = """
        <!doctype html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta name="application-name" content="$identifier">
          <title>${escapeHtml(projectName)}</title>
          <link rel="stylesheet" href="styles.css">
        </head>
        <body>
          <main class="page">
            <h1>${escapeHtml(projectName)}</h1>
            <p id="message">静态网页模板已就绪。</p>
            <button id="action" type="button">点击</button>
          </main>
          <script src="app.js"></script>
        </body>
        </html>
    """.trimIndent()

    private fun nodeManifest(identifier: String): String = """
        {
          "name": "$identifier",
          "version": "0.1.0",
          "private": true,
          "description": "ZakoCoder Node.js 项目模板",
          "main": "index.js",
          "scripts": {
            "start": "node index.js"
          }
        }
    """.trimIndent()

    private fun nodeEntry(projectName: String): String = """
        // Node.js 入口脚本，运行：npm start
        const name = '${escapeJs(projectName)}';

        console.log('Hello from ' + name);
    """.trimIndent()

    private fun pythonEntry(projectName: String, identifier: String): String = """
        # Python 项目入口。
        PROJECT_IDENTIFIER = "$identifier"


        def main() -> None:
            print("Hello from ${escapePython(projectName)}")


        if __name__ == "__main__":
            main()
    """.trimIndent()

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun escapeJs(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

    /** Escapes a value for a double-quoted C-family string literal (Python/C++/Go/Rust). */
    private fun escapeDoubleQuoted(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun escapePython(value: String): String = escapeDoubleQuoted(value)

    private fun cppManifest(target: String): String = """
        cmake_minimum_required(VERSION 3.20)
        project($target LANGUAGES CXX)

        set(CMAKE_CXX_STANDARD 17)
        set(CMAKE_CXX_STANDARD_REQUIRED ON)

        add_executable($target src/main.cpp)
    """.trimIndent()

    private fun cppEntry(projectName: String): String = """
        #include <iostream>

        int main() {
            std::cout << "Hello from ${escapeDoubleQuoted(projectName)}" << std::endl;
            return 0;
        }
    """.trimIndent()

    private fun goMod(identifier: String): String = """
        module $identifier

        go 1.22
    """.trimIndent()

    private fun goEntry(projectName: String): String =
        "package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Println(\"Hello from ${escapeDoubleQuoted(projectName)}\")\n}\n"

    private fun rustManifest(packageName: String): String = """
        [package]
        name = "$packageName"
        version = "0.1.0"
        edition = "2021"

        [dependencies]
    """.trimIndent()

    /** The project name is always passed as a `println!` argument, never embedded in the format string. */
    private fun rustEntry(projectName: String): String = """
        fn main() {
            println!("{}", "Hello from ${escapeRustString(projectName)}");
        }
    """.trimIndent()

    /** Escapes a value for a Rust double-quoted string literal, including control characters. */
    private fun escapeRustString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private val WEB_STYLES = """
        :root {
          color-scheme: light dark;
          font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
        }

        body {
          margin: 0;
          background: #f5f7fa;
          color: #0f172a;
        }

        .page {
          max-width: 640px;
          margin: 0 auto;
          padding: 48px 20px;
        }

        button {
          min-height: 44px;
          padding: 10px 18px;
          border: 0;
          border-radius: 10px;
          background: #2563eb;
          color: #ffffff;
          font-size: 16px;
          cursor: pointer;
        }
    """.trimIndent()

    private val WEB_SCRIPT = """
        const button = document.getElementById('action');
        const message = document.getElementById('message');
        let count = 0;

        button.addEventListener('click', () => {
          count += 1;
          message.textContent = '已点击 ' + count + ' 次';
        });
    """.trimIndent()

    private val NODE_GITIGNORE = """
        node_modules/
        npm-debug.log*
        .env
    """.trimIndent()

    private val PYTHON_REQUIREMENTS = """
        # 在此添加依赖，例如：
        # requests>=2.31
    """.trimIndent()

    private val PYTHON_GITIGNORE = """
        __pycache__/
        *.py[cod]
        .venv/
    """.trimIndent()

    private val CPP_GITIGNORE = """
        build/
        .cache/
    """.trimIndent()

    private val GO_GITIGNORE = """
        /bin/
        *.exe
    """.trimIndent()

    private val RUST_GITIGNORE = """
        /target/
    """.trimIndent()
}
