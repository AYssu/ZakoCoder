package dev.zakocoder.app.ai

import android.content.Context
import dev.zakocoder.app.R
import dev.zakocoder.app.editor.EditorFileException

/** Expected file failures carry no paths, file contents or raw exception messages. */
internal class AiFileFailure(val reason: Reason) : IllegalArgumentException(reason.name) {
    enum class Reason(val message: Int) {
        INVALID_PATH(R.string.ai_file_invalid_path),
        PROTECTED_PATH(R.string.ai_file_protected_path),
        READ_REQUIRED(R.string.ai_file_read_required),
        EDITOR_OPEN(R.string.ai_file_editor_open),
        FILE_CONFLICT(R.string.ai_file_conflict),
        FILE_LIMIT(R.string.ai_file_limit),
        FILE_UNSUPPORTED(R.string.ai_file_unsupported),
        FILE_UNAVAILABLE(R.string.ai_file_unavailable),
        PARENT_MISSING(R.string.ai_file_parent_missing),
        TARGET_EXISTS(R.string.ai_file_target_exists),
        INVALID_RANGE(R.string.ai_file_invalid_range),
    }
}

/** Null means the caller must retain its existing generic failure handling. */
internal fun knownFileFailure(context: Context, error: Exception): String? {
    val reason = when (error) {
        is AiFileFailure -> error.reason
        is EditorFileException -> when (error.reason) {
            EditorFileException.Reason.CONFLICT -> AiFileFailure.Reason.FILE_CONFLICT
            EditorFileException.Reason.LIMIT -> AiFileFailure.Reason.FILE_LIMIT
            EditorFileException.Reason.UNSUPPORTED -> AiFileFailure.Reason.FILE_UNSUPPORTED
            EditorFileException.Reason.PATH -> AiFileFailure.Reason.FILE_UNAVAILABLE
        }
        is java.nio.file.FileAlreadyExistsException -> AiFileFailure.Reason.TARGET_EXISTS
        is java.nio.file.NoSuchFileException -> AiFileFailure.Reason.FILE_UNAVAILABLE
        is java.nio.charset.CharacterCodingException -> AiFileFailure.Reason.FILE_UNSUPPORTED
        else -> return null
    }
    return "[${reason.name}] ${context.getString(reason.message)}"
}
