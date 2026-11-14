package dev.zakocoder.app.ai

/** Markdown instructions stored under the root provided by the owner. No automatic script execution, installation or privilege changes. */
enum class AiSkillCategory { Coding, Environment, Security, ReverseEngineering, Graphics, Research, SkillManagement, Expression, Project }
data class AiSkill(val path: String, val name: String, val description: String, val valid: Boolean = true,
    val builtIn: Boolean = false, val recommended: Boolean = false, val category: AiSkillCategory = AiSkillCategory.Project)
class AiSkills(private val files: AiProjectFiles, private val builtIns: AiBuiltinSkills? = null) {
    companion object {
        const val MAX_ENABLED = 16
        const val MAX_CUSTOM_CHARS = 24000
        const val MAX_CUSTOM_BYTES = 64000
    }
    /** Creates one skill under the supplied root. Existing directories are never reused or overwritten. Blocking IO. */
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
                val metadata = validateMarkdown(text)
                AiSkill(path, metadata.first, metadata.second)
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
            val text = if (available.getValue(path).builtIn) requireNotNull(builtIns).content(path) else readCustom(project, path)
            "Skill: ${available.getValue(path).name}; source: $path\n$text"
        }
        require(result.length <= 32000)
        return result
    }
    /** Uses metadata already shown to the user; constructing a request must not scan skill bodies. */
    internal fun session(project: String, paths: List<String>, metadataSnapshot: List<AiSkill>): AiSkillSession {
        require(paths.size <= MAX_ENABLED && paths.distinct().size == paths.size)
        require(paths.count { it in AiBuiltinSkills.expressionStyles } <= 1)
        val available = metadataSnapshot.filter { it.valid }.associateBy { it.path }
        val selected = paths.map { path -> requireNotNull(available[path]).also { skill ->
            if (skill.builtIn) require(AiBuiltinSkills.contains(path))
            else {
                require(path.startsWith(".zakocoder/skills/") && path.endsWith("/SKILL.md"))
                validateSkillResource(path)
            }
        } }
        return AiSkillSession(selected) { skill, resource ->
            validateSkillResource(resource)
            if (skill.builtIn) requireNotNull(builtIns).resource(skill.path, resource)
            else readCustom(project, skill.path.substringBeforeLast('/') + "/" + resource)
        }
    }

    fun readCustom(project: String, path: String): String {
        return files.read(project, path).text
    }

    /** Resolve exactly one custom package; never accept a root, resource or built-in path. */
    internal fun customDeletionDirectory(project: String, path: String): java.io.File {
        require(Regex("\\.zakocoder/skills/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)?/SKILL\\.md").matches(path))
        require(!java.nio.file.Files.isSymbolicLink(files.resolve(project, "", true).toPath()))
        val base = files.resolve(project, ".zakocoder/skills")
        val relative = path.substringBeforeLast('/')
        val parts = relative.removePrefix(".zakocoder/skills/").split('/')
        if (parts.size == 2) {
            require(parts.first().startsWith("bundle-"))
            require(files.resolve(project, ".zakocoder/skills/${parts.first()}/.import-sha256").isFile)
        } else require(!parts.single().startsWith("bundle-"))
        val directory = files.resolve(project, relative)
        require(java.nio.file.Files.isDirectory(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        require(directory.canonicalFile.toPath().startsWith(base.canonicalFile.toPath()) && directory.canonicalFile != base.canonicalFile)
        return directory
    }

    /** Blocking IO. walkFileTree deliberately has no FOLLOW_LINKS: resources may be links. */
    internal fun deleteCustom(project: String, path: String, checkActive: () -> Unit = {}) {
        val directory = customDeletionDirectory(project, path).canonicalFile
        val root = directory.toPath()
        fun checkOwned(entry: java.nio.file.Path) {
            checkActive()
            require(files.resolve(project, path.substringBeforeLast('/')).canonicalFile.toPath() == root)
            require(entry.toAbsolutePath().normalize().startsWith(root))
            // Check the parent, not a symbolic-link entry's destination; only the link is removed.
            if (entry != root) require(entry.parent.toFile().canonicalFile.toPath().startsWith(root))
        }
        java.nio.file.Files.walkFileTree(directory.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                checkOwned(dir)
                require(dir.toFile().canonicalFile.toPath().startsWith(root))
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                checkOwned(file)
                java.nio.file.Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: java.nio.file.Path, failure: java.io.IOException?): java.nio.file.FileVisitResult {
                if (failure != null) throw failure
                checkOwned(dir)
                java.nio.file.Files.delete(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
        check(!java.nio.file.Files.exists(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
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
