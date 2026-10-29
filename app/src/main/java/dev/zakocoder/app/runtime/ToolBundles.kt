package dev.zakocoder.app.runtime

import dev.zakocoder.app.R
import dev.zakocoder.app.projects.ProjectKind

/** Curated combinations of the existing, fixed tool probes and package allowlist. */
data class ToolBundle(val id: String, val title: Int, val description: Int, val tools: Set<String>, val manual: Boolean = false)
enum class BundleStatus { Unknown, Ready, Missing, Broken }

object ToolBundles {
    val catalog = listOf(
        ToolBundle("zsh", R.string.bundle_zsh, R.string.bundle_zsh_description, setOf("zsh")),
        ToolBundle("git", R.string.bundle_git, R.string.bundle_git_description, setOf("git")),
        ToolBundle("node", R.string.bundle_node, R.string.bundle_node_description, setOf("node", "npm")),
        ToolBundle("python", R.string.bundle_python, R.string.bundle_python_description, setOf("python", "pip", "venv")),
        ToolBundle("go", R.string.bundle_go, R.string.bundle_go_description, setOf("go")),
        ToolBundle("rust", R.string.bundle_rust, R.string.bundle_rust_description, setOf("rustc", "cargo")),
        ToolBundle("jdk", R.string.bundle_jdk, R.string.bundle_jdk_description, setOf("java", "javac")),
        ToolBundle("cpp", R.string.bundle_cpp, R.string.bundle_cpp_description, setOf("cc", "make", "cmake")),
        ToolBundle("android", R.string.kind_android, R.string.bundle_android_description, setOf("android-sdk", "java", "javac")),
        ToolBundle("android-ndk", R.string.bundle_android_ndk, R.string.bundle_android_ndk_description, setOf("android-ndk")),
        ToolBundle("flutter", R.string.kind_flutter, R.string.bundle_manual_description, setOf("flutter"), manual = true),
    )
    fun status(bundle: ToolBundle, checks: List<ToolCheck>): BundleStatus {
        val matches = checks.filter { it.id in bundle.tools }
        return when {
            matches.size != bundle.tools.size -> BundleStatus.Unknown
            matches.any { it.status == ToolStatus.Broken } -> BundleStatus.Broken
            matches.any { it.status == ToolStatus.Missing } -> BundleStatus.Missing
            else -> BundleStatus.Ready
        }
    }
    fun recommended(bundle: ToolBundle, kinds: Set<ProjectKind>): Boolean =
        kinds.isNotEmpty() && DevelopmentTools.required(kinds).any { it.id in bundle.tools }

    /** Select by bundle IDs; shell arguments never originate from UI or arbitrary package text. */
    fun packages(ids: Set<String>, checks: List<ToolCheck>): List<String> {
        require(ids.isNotEmpty())
        val bundles = ids.map { id -> catalog.single { it.id == id }.also { require(!it.manual) } }
        val tools = bundles.flatMap { it.tools }.toSet()
        return DevelopmentTools.catalog.filter { tool -> tool.id in tools && checks.none { it.id == tool.id && it.status == ToolStatus.Ready } }
            .flatMap { it.packages }.distinct().sorted()
    }
}
