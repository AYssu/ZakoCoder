package dev.zakocoder.app.ai

import android.content.Context
import dev.zakocoder.app.R

/** App-owned, offline mobile guidance. Never copied into a user's workspace or executed as code. */
class AiBuiltinSkills(private val context: Context) {
    fun scan(): List<AiSkill> = entries.map { entry ->
        AiSkill("builtin:${entry.id}", entry.id, context.getString(entry.description), builtIn = true,
            recommended = entry.id in recommendedIds, category = entry.category)
    }
    fun content(path: String): String = resource(path, "SKILL.md")
    internal fun resource(path: String, resource: String): String {
        require(contains(path))
        validateSkillResource(resource)
        return context.assets.open("skills/${path.removePrefix("builtin:")}/$resource").bufferedReader().use { reader ->
            val buffer = CharArray(32001)
            var count = 0
            while (count < buffer.size) {
                val read = reader.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            require(count <= 32000)
            String(buffer, 0, count)
        }
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
            Entry("audit", R.string.ai_builtin_audit, AiSkillCategory.Security),
            Entry("env-audit", R.string.ai_builtin_env_audit, AiSkillCategory.Environment),
            Entry("skill-creator", R.string.ai_builtin_skill_creator, AiSkillCategory.SkillManagement),
            Entry("skill-installer", R.string.ai_builtin_skill_installer, AiSkillCategory.SkillManagement),
            Entry("self-improving-agent", R.string.ai_builtin_self_improving_agent, AiSkillCategory.Research),
            Entry("reverse-engineer-anything", R.string.ai_builtin_reverse_engineer_anything, AiSkillCategory.ReverseEngineering),
            Entry("api-security", R.string.ai_builtin_api_security, AiSkillCategory.Security),
            Entry("apk-reverse", R.string.ai_builtin_apk_reverse, AiSkillCategory.ReverseEngineering),
            Entry("binary-analysis", R.string.ai_builtin_binary_analysis, AiSkillCategory.ReverseEngineering),
            Entry("binary-diff", R.string.ai_builtin_binary_diff, AiSkillCategory.ReverseEngineering),
            Entry("dotnet-reverse", R.string.ai_builtin_dotnet_reverse, AiSkillCategory.ReverseEngineering),
            Entry("dsl-vm-reverse", R.string.ai_builtin_dsl_vm_reverse, AiSkillCategory.ReverseEngineering),
            Entry("dynamic-instrumentation", R.string.ai_builtin_dynamic_instrumentation, AiSkillCategory.ReverseEngineering),
            Entry("firmware-analysis", R.string.ai_builtin_firmware_analysis, AiSkillCategory.ReverseEngineering),
            Entry("game-engine", R.string.ai_builtin_game_engine, AiSkillCategory.Graphics),
            Entry("graphics-api", R.string.ai_builtin_graphics_api, AiSkillCategory.Graphics),
            Entry("ida-reverse", R.string.ai_builtin_ida_reverse, AiSkillCategory.ReverseEngineering),
            Entry("js-reverse", R.string.ai_builtin_js_reverse, AiSkillCategory.ReverseEngineering),
            Entry("mobile-reverse", R.string.ai_builtin_mobile_reverse, AiSkillCategory.ReverseEngineering),
            Entry("mobile-security", R.string.ai_builtin_mobile_security, AiSkillCategory.Security),
            Entry("network-diagnostics", R.string.ai_builtin_network_diagnostics, AiSkillCategory.Environment),
            Entry("protocol-reverse-engineering", R.string.ai_builtin_protocol_reverse_engineering, AiSkillCategory.ReverseEngineering),
            Entry("radare2", R.string.ai_builtin_radare2, AiSkillCategory.ReverseEngineering),
            Entry("research-rigor", R.string.ai_builtin_research_rigor, AiSkillCategory.Research),
            Entry("reverse-engineering-api", R.string.ai_builtin_reverse_engineering_api, AiSkillCategory.ReverseEngineering),
            Entry("windows-kernel", R.string.ai_builtin_windows_kernel, AiSkillCategory.ReverseEngineering),
            Entry("memory-analysis", R.string.ai_builtin_memory_analysis, AiSkillCategory.ReverseEngineering),
            Entry("malware-analysis", R.string.ai_builtin_malware_analysis, AiSkillCategory.ReverseEngineering),
            Entry("research-workspace", R.string.ai_builtin_research_workspace, AiSkillCategory.Research),
            Entry("license-security", R.string.ai_builtin_license_security, AiSkillCategory.Security),
            Entry("osint", R.string.ai_builtin_osint, AiSkillCategory.Research),
            Entry("crash-triage", R.string.ai_builtin_crash_triage, AiSkillCategory.Environment),
            Entry("anti-cheat-systems", R.string.ai_builtin_anti_cheat_systems, AiSkillCategory.Security),
            Entry("dma-security", R.string.ai_builtin_dma_security, AiSkillCategory.Security),
            Entry("security-testing", R.string.ai_builtin_security_testing, AiSkillCategory.Security),
            Entry("render-pipeline", R.string.ai_builtin_render_pipeline, AiSkillCategory.Graphics),
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
