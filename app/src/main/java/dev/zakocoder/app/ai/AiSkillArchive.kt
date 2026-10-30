package dev.zakocoder.app.ai

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Bounded ZIP data, never extracted by an external process. Entries are ordinary byte files only. */
internal object AiSkillArchive {
    const val MAX_ZIP_BYTES = 5 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 16 * 1024 * 1024
    const val MAX_FILE_BYTES = 2 * 1024 * 1024
    data class Skill(val directory: String, val document: String, val files: Map<String, ByteArray>)

    fun isZip(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()
    fun parse(bytes: ByteArray): List<Skill> {
        require(bytes.size <= MAX_ZIP_BYTES && isZip(bytes))
        // Java's ZipInputStream does not expose Unix file types. Validate the central directory
        // first, rejecting links/devices and ZIP64 before reading any uncompressed content.
        fun u16(i: Int): Int { require(i >= 0 && i + 2 <= bytes.size); return (bytes[i].toInt() and 255) or ((bytes[i + 1].toInt() and 255) shl 8) }
        fun u32(i: Int): Long = u16(i).toLong() or (u16(i + 2).toLong() shl 16)
        val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65557)).firstOrNull {
            u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == bytes.size
        } ?: error("ZIP directory missing")
        val count = u16(end + 10)
        require(u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) == count && count in 1..256)
        val central = u32(end + 16); val centralSize = u32(end + 12)
        require(central + centralSize == end.toLong())
        var cursor = central.toInt()
        val centralNames = linkedSetOf<String>()
        repeat(count) {
            require(u32(cursor) == 0x02014b50L)
            val flags = u16(cursor + 8)
            val kind = ((u32(cursor + 38) shr 16).toInt() and 0xf000)
            require(flags and 1 == 0 && kind in setOf(0, 0x8000, 0x4000))
            require(u32(cursor + 20) != 0xffffffffL && u32(cursor + 24) != 0xffffffffL && u32(cursor + 42) < central)
            val length = u16(cursor + 28); val extra = u16(cursor + 30); val comment = u16(cursor + 32)
            require(cursor + 46 + length + extra + comment <= end)
            val name = decode(bytes.copyOfRange(cursor + 46, cursor + 46 + length))
            validatePath(name)
            require(centralNames.add(name))
            cursor += 46 + length + extra + comment
        }
        require(cursor == end)
        val entries = linkedMapOf<String, ByteArray>()
        val names = mutableSetOf<String>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                validatePath(name)
                require(name in centralNames && names.add(name))
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = zip.read(buffer)
                    if (size < 0) break
                    total += size
                    require(total <= MAX_TOTAL_BYTES && output.size() + size <= MAX_FILE_BYTES)
                    output.write(buffer, 0, size)
                }
                if (entry.isDirectory) require(output.size() == 0) else entries[name] = output.toByteArray()
                zip.closeEntry()
            }
        }
        require(names == centralNames)
        val documents = entries.keys.filter { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
        require(documents.size in 1..32)
        val directories = documents.map { it.substringBeforeLast('/', "") }
        require(directories.distinct().size == directories.size)
        require(directories.none { parent -> directories.any { it != parent && (parent.isEmpty() || it.startsWith("$parent/")) } })
        return documents.map { document ->
            val directory = document.substringBeforeLast('/', "")
            val prefix = if (directory.isEmpty()) "" else "$directory/"
            val owned = entries.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
            Skill(directory, decode(entries.getValue(document)).removePrefix("\uFEFF"), owned)
        }
    }

    fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    private fun validatePath(name: String) {
        require(name.isNotEmpty() && name.length <= 512 && !name.startsWith('/') && '\\' !in name && ':' !in name)
        val parts = name.removeSuffix("/").split('/')
        require(parts.size <= 12 && parts.all { it.isNotEmpty() && it != "." && it != ".." && it.none(Char::isISOControl) && !isSensitiveProjectName(it) })
    }
}
