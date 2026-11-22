package dev.zakocoder.app.ai

import android.content.Context
import dev.zakocoder.app.R
import org.json.JSONObject

/** Routes existing read tools to read-only, conversation-owned private copies, never project paths. */
internal class AiAttachmentAccess(private val context: Context, private val store: AiFileAttachmentStore) {
    suspend fun execute(project: String, state: AiState, tool: AiTool, args: JSONObject): String {
        val attachment = try { requireOwned(project, state, tool, args.getString("path")) }
        catch (_: IllegalArgumentException) { throw AiTerminalException(context.getString(R.string.share_file_unavailable)) }
        try {
            val result = when (tool) {
                AiTool.ReadFile -> {
                    require(!args.has("start_line") && !args.has("end_line"))
                    val offset = args.optInt("offset", 0)
                    val limit = args.optInt("limit", 12000)
                    require(offset >= 0 && limit in 1..64000)
                    store.read(attachment, offset, minOf(limit, 12000))
                }
                AiTool.SearchFiles -> {
                    require(!args.optBoolean("filenames", false) && (!args.has("glob") || args.getString("glob") == "**"))
                    val limit = args.optInt("limit", 30)
                    require(limit in 1..200)
                    store.search(attachment, args.getString("query"), minOf(limit, 100), args.optBoolean("case_sensitive", true))
                }
                else -> error("Attachment is read-only")
            }
            return result.put("path", "attachment:${attachment.id}").put("untrusted_reference", true).toString()
        } catch (failure: AiFileAttachmentException) {
            throw AiTerminalException(context.getString(if (failure.reason == AiFileAttachmentException.Reason.Unsupported)
                R.string.share_text_unsupported else R.string.share_file_unavailable))
        } catch (_: IllegalArgumentException) {
            throw AiTerminalException(context.getString(R.string.share_attachment_range))
        }
    }

    companion object {
        fun requireOwned(project: String, state: AiState, tool: AiTool, path: String): AiFileAttachment {
            require(tool == AiTool.ReadFile || tool == AiTool.SearchFiles)
            require(state.projectId == project && !state.historyError)
            require(path.startsWith("attachment:"))
            val id = path.removePrefix("attachment:")
            require(AiFileAttachments.validId(id))
            return requireNotNull(state.fileAttachments.singleOrNull { it.id == id })
        }
    }
}
