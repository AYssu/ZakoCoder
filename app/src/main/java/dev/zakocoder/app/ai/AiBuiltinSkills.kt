package dev.zakocoder.app.ai

import android.content.Context
import dev.zakocoder.app.R

/** App-owned, offline mobile guidance. Never copied into a user's workspace or executed as code. */
class AiBuiltinSkills(private val context: Context) {
    fun scan(): List<AiSkill> = entries.map { entry ->
        AiSkill("builtin:${entry.id}", entry.id, context.getString(entry.description), builtIn = true,
            recommended = entry.id in recommendedIds, category = entry.category)
    }
    fun content(path: String): String {
        require(contains(path))
        return context.assets.open("skills/${path.removePrefix("builtin:")}/SKILL.md").bufferedReader().use { it.readText() }
            .also { require(it.length <= 32000) }
    }
    private data class Entry(val id: String, val description: Int, val category: AiSkillCategory = AiSkillCategory.Coding)
    companion object {
        private val entries = listOf(
            Entry("frontend-design", R.string.ai_builtin_frontend),
            Entry("lean-build", R.string.ai_builtin_lean),
            Entry("surgical-patch", R.string.ai_builtin_patch),
            Entry("investigate-first", R.string.ai_builtin_investigate),
            Entry("safe-refactor", R.string.ai_builtin_refactor),
            Entry("verify-and-stop", R.string.ai_builtin_verify),
            Entry("audit", R.string.ai_builtin_audit),
            Entry("caveman", R.string.ai_builtin_caveman, AiSkillCategory.Expression),
            Entry("megacave", R.string.ai_builtin_megacave, AiSkillCategory.Expression),
            Entry("ultracave", R.string.ai_builtin_ultracave, AiSkillCategory.Expression),
            Entry("caveman-commit", R.string.ai_builtin_commit, AiSkillCategory.Expression),
            Entry("caveman-compress", R.string.ai_builtin_compress, AiSkillCategory.Expression),
            Entry("caveman-help", R.string.ai_builtin_help, AiSkillCategory.Expression),
        )
        private val recommendedIds = setOf("lean-build", "surgical-patch", "investigate-first", "verify-and-stop", "caveman")
        val defaults: List<String> = entries.filter { it.id in recommendedIds }.map { "builtin:${it.id}" }
        val expressionStyles: Set<String> = setOf("builtin:caveman", "builtin:megacave", "builtin:ultracave")
        fun contains(path: String): Boolean = entries.any { path == "builtin:${it.id}" }
    }
}
