package dev.zakocoder.app.ai

/** Project-owned Markdown instructions. No automatic script execution, installation or privilege changes. */
enum class AiSkillCategory { Coding, Expression, Project }
data class AiSkill(val path: String, val name: String, val description: String, val valid: Boolean = true,
    val builtIn: Boolean = false, val recommended: Boolean = false, val category: AiSkillCategory = AiSkillCategory.Project)
class AiSkills(private val files: AiProjectFiles, private val builtIns: AiBuiltinSkills? = null) {
    companion object { const val MAX_ENABLED = 16 }
    fun scan(project: String): List<AiSkill> {
        val bundled = builtIns?.scan().orEmpty()
        val projectSkills = try { scanProject(project) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { listOf(AiSkill(".zakocoder/skills", "SKILL.md", "", valid = false)) }
        return bundled + projectSkills
    }
    private fun scanProject(project: String): List<AiSkill> {
        val directory = files.resolve(project, ".zakocoder/skills")
        if (!directory.exists()) return emptyList()
        require(directory.isDirectory)
        val children = directory.listFiles().orEmpty().sortedBy { it.name }
        require(children.size <= 32)
        return children.map { child ->
            val path = ".zakocoder/skills/${child.name}/SKILL.md"
            try {
                val text = files.read(project, path).text
                val metadata = metadata(text)
                AiSkill(path, metadata.first.ifBlank { child.name }.take(100), metadata.second.take(500))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { AiSkill(path, child.name.take(100), "", false) }
        }
    }
    fun content(project: String, paths: List<String>): String {
        require(paths.size <= MAX_ENABLED && paths.distinct().size == paths.size)
        require(paths.count { it in AiBuiltinSkills.expressionStyles } <= 1)
        val available = scan(project).filter { it.valid }.associateBy { it.path }
        val result = paths.joinToString("\n\n") { path ->
            require(path in available)
            val text = if (available.getValue(path).builtIn) requireNotNull(builtIns).content(path) else files.read(project, path).text
            "Skill: ${available.getValue(path).name}; source: $path\n$text"
        }
        require(result.length <= 32000)
        return result
    }
    private fun metadata(text: String): Pair<String, String> {
        if (!text.startsWith("---\n") && !text.startsWith("---\r\n")) return "" to ""
        val lines = text.lineSequence().drop(1).takeWhile { it.trim() != "---" }.take(100).toList()
        fun value(name: String): String {
            val index = lines.indexOfFirst { it.startsWith("$name:") }
            if (index < 0) return ""
            val raw = lines[index].substringAfter(':').trim().trim('"', '\'')
            return if (raw in listOf(">", "|", ">-", "|-")) lines.drop(index + 1).takeWhile { it.startsWith(' ') || it.isBlank() }.joinToString(" ") { it.trim() } else raw
        }
        return value("name") to value("description")
    }
}
