package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiReview
import dev.zakocoder.app.ai.fileDiff
import dev.zakocoder.app.editor.EditorLanguages
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Separate read-only Sora view; cannot save a proposed or historical version over the working file. */
@Composable
internal fun AiCodeReview(review: AiReview, languages: EditorLanguages,
    labels: List<Int> = listOf(R.string.ai_review_before, R.string.ai_review_after, R.string.ai_review_diff),
    note: String? = null, close: () -> Unit) {
    var tab by remember(review) { mutableIntStateOf(2) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(languages) { withContext(Dispatchers.IO) { languages.prepare() }; ready = true }
    val dark = LocalWorkbenchDark.current
    val description = stringResource(R.string.ai_review_content)
    val rendered by produceState<Pair<Pair<AiReview, Int>, String>?>(null, review, tab) {
        val selection = review to tab
        value = selection to withContext(Dispatchers.Default) { when (tab) { 0 -> review.before.orEmpty(); 1 -> review.after.orEmpty(); else -> fileDiff(review.before.orEmpty(), review.after.orEmpty()) } }
    }
    val text = rendered?.takeIf { it.first == (review to tab) }?.second
    AiFullScreen(review.path, close) {
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                AiSectionTab(stringResource(label), tab == index, { tab = index }, Modifier.weight(1f))
            }
        }
        note?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
        if (!review.directory && (review.beforeMode != null || review.afterMode != null)) {
            val beforeMode = review.beforeMode ?: stringResource(if (review.before == null) R.string.ai_review_mode_missing else R.string.ai_review_mode_unknown)
            val afterMode = review.afterMode ?: stringResource(if (review.after == null) R.string.ai_review_mode_missing else R.string.ai_review_mode_unknown)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.ai_review_file_permissions, beforeMode, afterMode), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ai_review_file_permissions_help), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (tab == 2 && !review.directory && review.before != null && review.before == review.after)
            Text(stringResource(R.string.ai_review_body_unchanged), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        if (review.directory) Text(stringResource(R.string.ai_review_directory), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        if ((tab == 0 && review.before == null) || (tab == 1 && review.after == null)) Text(stringResource(
            if (!review.directory) R.string.ai_review_missing else if (tab == 0) R.string.ai_review_directory_missing_before else R.string.ai_review_directory_missing_after
        ), Modifier.padding(16.dp))
        if (!ready || text == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else key(review, tab, dark, text) {
            AndroidView(factory = { context ->
                ThemeRegistry.getInstance().setTheme(if (dark) "zako-dark" else "zako-light")
                CodeEditor(context).apply {
                    contentDescription = description
                    setTextSize(12f)
                    typefaceText = languages.typeface; typefaceLineNumber = languages.typeface
                    isLineNumberEnabled = true; isWordwrap = false; isEditable = false
                    setLigatureEnabled(false)
                    colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                    val language = if (tab == 2) "diff" else languages.language(review.path)
                    setEditorLanguage(if (language == null) EmptyLanguage() else TextMateLanguage.create("source.zako.$language", false))
                    setText(text.orEmpty())
                }
            }, modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp), onRelease = { it.release() })
        }
    }
}
