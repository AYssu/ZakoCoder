package dev.zakocoder.app.ai

/** Project-owned Markdown instructions. No automatic script execution, installation or privilege changes. */
enum class AiSkillCategory { Coding, Expression, Project }
data class AiSkill(val path: String, val name: String, val description: String, val valid: Boolean = true,
    val builtIn: Boolean = false, val recommended: Boolean = false, val category: AiSkillCategory = AiSkillCategory.Project)
class AiSkills(private val files: AiProjectFiles, private val builtIns: AiBuiltinSkills? = null) {
    companion object {
        const val MAX_ENABLED = 16
        const val MAX_CUSTOM_CHARS = 24000
        const val MAX_CUSTOM_BYTES = 64000
    }
    /** Creates one project-owned skill. Existing directories are never reused or overwritten. Blocking IO. */
    fun create(project: String, name: String, description: String, body: String, operationId: String = java.util.UUID.randomUUID().toString()): AiSkill {
        require(name.isNotBlank() && name.length <= 100 && name.none(Char::isISOControl))
        require(description.isNotBlank() && description.length <= 500 && description.none(Char::isISOControl))
        require(body.isNotBlank())
        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return importMarkdown(project, "---\nname: ${quote(name.trim())}\ndescription: ${quote(description.trim())}\n---\n\n${body.trim()}\n", operationId)
    }
    /** Import preserves the full SKILL.md, including optional metadata. No scripts are executed. */
    fun importMarkdown(project: String, text: String, operationId: String = java.util.UUID.randomUUID().toString()): AiSkill {
        require(java.util.UUID.fromString(operationId).toString() == operationId)
        val metadata = validateMarkdown(text)
        val base = ".zakocoder/skills"
        val directory = files.resolve(project, base)
        require(!directory.exists() || directory.isDirectory)
        // The form retains this identifier across Activity recreation. Retrying a completed save
        // returns the same file; it must never silently create a second skill directory.
        val path = "$base/skill-$operationId"
        val target = files.resolve(project, path)
        val skillPath = "$path/SKILL.md"
        if (target.exists()) {
            require(target.isDirectory)
            if (files.resolve(project, skillPath).exists()) {
                require(files.read(project, skillPath).text == text)
                return AiSkill(skillPath, metadata.first, metadata.second)
            }
            require(target.listFiles()?.isEmpty() == true)
        } else {
            require(scanProject(project).size < 32)
            require(directory.isDirectory || directory.mkdirs())
            files.resolve(project, path)
            check(target.mkdir())
        }
        try {
            val skill = skillPath
            files.write(project, skill, null, text)
            return AiSkill(skill, metadata.first, metadata.second)
        } catch (failure: Throwable) {
            // Delete only the empty directory this call created; never recurse or follow links.
            if (!java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
            throw failure
        }
    }
    /** All archive files are staged outside the discoverable skill tree, then published together. */
    fun importArchive(project: String, bytes: ByteArray, operationId: String = java.util.UUID.randomUUID().toString()): List<AiSkill> {
        require(java.util.UUID.fromString(operationId).toString() == operationId)
        val parsed = AiSkillArchive.parse(bytes)
        val metadata = parsed.map { validateMarkdown(it.document) }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val base = ".zakocoder/skills"
        val destinationPath = "$base/bundle-$operationId"
        val destination = files.resolve(project, destinationPath)
        if (destination.exists()) {
            require(destination.isDirectory && files.read(project, "$destinationPath/.import-sha256").text == digest)
            return scanProject(project).filter { it.path.startsWith("$destinationPath/") }.also { require(it.size == parsed.size && it.all(AiSkill::valid)) }
        }
        require(scanProject(project).size + parsed.size <= 32)
        val directory = files.resolve(project, base)
        require(!directory.exists() || directory.isDirectory)
        val stagingPath = ".zakocoder/skill-import-${java.util.UUID.randomUUID()}"
        val staging = files.resolve(project, stagingPath)
        // Never reuse a stale or externally supplied staging tree.
        require(!java.nio.file.Files.exists(staging.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        val stagingParent = requireNotNull(staging.parentFile)
        require(stagingParent.isDirectory || stagingParent.mkdirs())
        check(staging.mkdir())
        try {
            parsed.forEachIndexed { index, skill ->
                val target = java.io.File(staging, "skill-$index")
                check(target.mkdir())
                skill.files.forEach { (relative, content) ->
                    val normalized = if (relative.substringAfterLast('/').equals("SKILL.md", true)) "SKILL.md" else relative
                    val file = java.io.File(target, normalized)
                    val parent = requireNotNull(file.parentFile)
                    require(parent.isDirectory || parent.mkdirs())
                    files.resolve(project, file.relativeTo(files.resolve(project, "", true)).invariantSeparatorsPath)
                    java.nio.file.Files.newOutputStream(file.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.LinkOption.NOFOLLOW_LINKS).use {
                        it.write(if (normalized == "SKILL.md") skill.document.toByteArray(Charsets.UTF_8) else content)
                    }
                }
            }
            java.io.File(staging, ".import-sha256").writeText(digest)
            require(directory.isDirectory || directory.mkdirs())
            files.resolve(project, destinationPath)
            files.resolve(project, stagingPath)
            java.nio.file.Files.move(staging.toPath(), destination.toPath())
            return metadata.mapIndexed { index, item -> AiSkill("$destinationPath/skill-$index/SKILL.md", item.first, item.second) }
        } finally {
            // walkFileTree does not follow symbolic links. A failed batch never becomes discoverable.
            if (java.nio.file.Files.exists(staging.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                java.nio.file.Files.walkFileTree(staging.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                        java.nio.file.Files.deleteIfExists(file); return java.nio.file.FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(dir: java.nio.file.Path, failure: java.io.IOException?): java.nio.file.FileVisitResult {
                        if (failure != null) throw failure
                        java.nio.file.Files.deleteIfExists(dir); return java.nio.file.FileVisitResult.CONTINUE
                    }
                })
            }
        }
    }

    internal fun validateMarkdown(text: String): Pair<String, String> {
        require(text.length <= MAX_CUSTOM_CHARS && text.toByteArray(Charsets.UTF_8).size <= MAX_CUSTOM_BYTES && '\u0000' !in text)
        val metadata = metadata(text)
        require(metadata.first.isNotBlank() && metadata.first.length <= 100 && metadata.first.none(Char::isISOControl))
        require(metadata.second.isNotBlank() && metadata.second.length <= 500 && metadata.second.none(Char::isISOControl))
        val header = Regex("\\A---\\r?\\n[\\s\\S]*?\\r?\\n---(?:\\r?\\n|$)").find(text)
        require(header != null && text.substring(header.range.last + 1).isNotBlank())
        return metadata
    }

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
        val paths = children.flatMap { child ->
            val relative = ".zakocoder/skills/${child.name}"
            try {
                files.resolve(project, relative)
                if (child.name.startsWith("bundle-") && files.resolve(project, "$relative/.import-sha256").isFile) {
                    child.listFiles().orEmpty().filter { it.name != ".import-sha256" }.sortedBy { it.name }.map { "$relative/${it.name}/SKILL.md" }
                } else listOf("$relative/SKILL.md")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { listOf("$relative/SKILL.md") }
        }
        require(paths.size <= 32)
        return paths.map { path ->
            val fallback = path.substringBeforeLast('/').substringAfterLast('/')
            try {
                val text = files.read(project, path).text
                val metadata = metadata(text)
                AiSkill(path, metadata.first.ifBlank { fallback }.take(100), metadata.second.take(500))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { AiSkill(path, fallback.take(100), "", false) }
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
            val encoded = lines[index].substringAfter(':').trim()
            val raw = if (encoded.startsWith('"') && encoded.endsWith('"') && encoded.length >= 2)
                encoded.substring(1, encoded.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                else encoded.trim('\'')
            return if (raw in listOf(">", "|", ">-", "|-")) lines.drop(index + 1).takeWhile { it.startsWith(' ') || it.isBlank() }.joinToString(" ") { it.trim() } else raw
        }
        return value("name") to value("description")
    }
}
