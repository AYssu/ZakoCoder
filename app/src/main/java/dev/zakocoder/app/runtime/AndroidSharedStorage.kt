package dev.zakocoder.app.runtime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

/** Explicit aliases for the current Android user's primary shared volume, not private app data. */
internal data class SharedStorageMounts(val source: File?, val paths: List<String>) {
    val arguments: List<String> get() = source?.let { directory ->
        paths.flatMap { listOf("-b", "${directory.path}:$it") }
    }.orEmpty()

    fun modelContext(): String = if (source == null) {
        "Android shared storage is not mounted in Ubuntu. To access phone files, ask the user to enable " +
            "local file access in Settings > Permission management, then start a new command or terminal. " +
            "Do not request Root, guess private paths, or bypass Android permissions."
    } else {
        "Android primary shared storage is mounted in Ubuntu at ${paths.joinToString(", ")}. " +
            "This applies to new commands and terminals; existing terminals created before file authorization " +
            "must be reopened to receive these mounts. " +
            "These paths refer to the same real phone files, not workspace copies. Use approved run_command " +
            "or terminal tools for user-requested access; structured file tools remain workspace-scoped. " +
            "Do not scan unrelated files. Changes outside the workspace are not covered by its checkpoints. " +
            "Android private app directories remain subject to system restrictions."
    }
}

internal object AndroidSharedStorage {
    /** Called on IO immediately before building each process or task context. Never requests a grant. */
    fun current(context: Context): SharedStorageMounts = try {
        val granted = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE).all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        @Suppress("DEPRECATION")
        plan(Environment.getExternalStorageDirectory(), granted)
    } catch (_: SecurityException) {
        SharedStorageMounts(null, emptyList())
    } catch (_: IOException) {
        // A disappearing/unmounted volume must not prevent ordinary Ubuntu commands from starting.
        SharedStorageMounts(null, emptyList())
    }

    @SuppressLint("SdCardPath") // Guest aliases only; the host source always comes from Environment.
    internal fun plan(directory: File, authorized: Boolean): SharedStorageMounts {
        if (!authorized || !directory.isDirectory || !directory.canRead()) return SharedStorageMounts(null, emptyList())
        val source = directory.canonicalFile
        // Keep the real user/volume spelling, including secondary Android users; never guess user 0.
        return SharedStorageMounts(source, listOf(directory.absolutePath, "/sdcard", "/storage/self/primary").distinct())
    }
}
