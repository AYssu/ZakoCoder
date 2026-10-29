package dev.zakocoder.app.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WorkspaceEntry(val uri: Uri, val name: String, val isDirectory: Boolean)

/** SAF document URIs are identifiers, never Linux paths. No writes or shell execution here. */
class WorkspaceRepository(private val context: Context) {
    private val resolver = context.contentResolver
    private val preferences = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)

    fun savedTree(): Uri? = preferences.getString("tree", null)?.let(Uri::parse)

    fun selectTree(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(uri, flags)
        val previous = savedTree()
        preferences.edit { putString("tree", uri.toString()) }
        // Keep one selected workspace without accumulating directory permissions.
        if (previous != null && previous != uri) {
            val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == previous }
            if (permission != null) {
                var grantedFlags = 0
                if (permission.isReadPermission) grantedFlags = grantedFlags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (permission.isWritePermission) grantedFlags = grantedFlags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    resolver.releasePersistableUriPermission(previous, grantedFlags)
                } catch (error: SecurityException) {
                    // The old provider may revoke permission between inspection and release.
                    // New selection is already persisted and must remain selected.
                    Log.w("Workspace", "Old workspace permission was already revoked", error)
                }
            }
        }
    }

    suspend fun list(tree: Uri, directory: Uri? = null): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        val parentId = if (directory == null) DocumentsContract.getTreeDocumentId(tree)
            else DocumentsContract.getDocumentId(directory)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val cursor = checkNotNull(resolver.query(children, columns, null, null, null)) {
            "Document provider returned no cursor"
        }
        cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(WorkspaceEntry(
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0)),
                        name = it.getString(1),
                        isDirectory = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    ))
                }
            }.sortedWith(compareByDescending<WorkspaceEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }
}
