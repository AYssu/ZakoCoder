package dev.zakocoder.app.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Each navigation identity owns a fresh state, including while its loader is suspended. */
internal class DirectoryListing<T>(enabled: Boolean = true) {
    var entries by mutableStateOf<List<T>>(emptyList())
        private set
    var loading by mutableStateOf(enabled)
        private set
    var failure by mutableStateOf<Exception?>(null)
        private set

    suspend fun load(loader: suspend () -> List<T>) {
        try {
            val result = loader()
            currentCoroutineContext().ensureActive()
            entries = result
            loading = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            failure = error
            loading = false
        }
    }
}

@Composable
internal fun <T> rememberDirectoryListing(vararg identity: Any?, enabled: Boolean = true, loader: suspend () -> List<T>): DirectoryListing<T> {
    val listing = remember(*identity, enabled) { DirectoryListing<T>(enabled) }
    LaunchedEffect(listing) { if (enabled) listing.load(loader) }
    return listing
}

internal fun distinguishEditorPaths(paths: List<String>): Map<String, String> {
    val unique = paths.distinct()
    return unique.associateWith { path ->
        val parts = path.split('/')
        (1..parts.size).firstNotNullOfOrNull { count ->
            val suffix = parts.takeLast(count).joinToString("/")
            suffix.takeIf { unique.none { other -> other != path && other.split('/').takeLast(count).joinToString("/") == suffix } }
        } ?: path
    }
}

internal fun commandScriptPreview(script: String): String = script.take(1600).lineSequence().take(4).joinToString("\n")
