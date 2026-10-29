package dev.zakocoder.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.editor.EditorController
import dev.zakocoder.app.editor.EditorDiskState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.text.LineSeparator
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

@Composable
internal fun CodeEditorPage(controller: EditorController, onReference: ((dev.zakocoder.app.ai.AiReference) -> Unit)? = null) {
    val state by controller.state.collectAsStateWithLifecycle()
    val document = state.document
    val editorDescription = stringResource(R.string.editor_content)
    val loadingDescription = stringResource(R.string.loading)
    val dark = LocalWorkbenchDark.current
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var matches by remember { mutableIntStateOf(0) }
    var leave by remember { mutableStateOf(false) }
    var closeTab by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var directory by remember(state.projectId) { mutableStateOf("") }
    var directoryRetry by remember(state.projectId, directory) { mutableIntStateOf(0) }
    val listing = rememberDirectoryListing(state.projectId, directory, picking, directoryRetry, enabled = picking && state.projectId != null) {
        controller.listFiles(requireNotNull(state.projectId), directory)
    }
    val choices = listing.entries
    val pickerError = listing.failure != null
    val projectTabs = state.tabs.filter { it.projectId == state.projectId }
    val tabLabels = distinguishEditorPaths(projectTabs.map { it.path })
    val compact = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var diskDetails by remember { mutableStateOf(false) }
    var replaceOpen by rememberSaveable(state.path) { mutableStateOf(false) }
    var reload by remember { mutableStateOf(false) }
    var diskReview by remember { mutableStateOf<dev.zakocoder.app.ai.AiReview?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) controller.checkDisk() }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.checkDisk()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var searching by rememberSaveable(state.path) { mutableStateOf(false) }
    var query by rememberSaveable(state.path) { mutableStateOf("") }
    var replacement by rememberSaveable(state.path) { mutableStateOf("") }
    val requestClose = { closeTab = false; if (state.dirty) leave = true else controller.close() }
    BackHandler { if (searching) { searching = false; editor?.searcher?.stopSearch() } else requestClose() }
    LaunchedEffect(searching, query, editor) {
        editor?.searcher?.let { searcher ->
            if (!searching || query.isEmpty()) searcher.stopSearch()
            else searcher.search(query, EditorSearcher.SearchOptions(false, false))
        }
    }
    Column(Modifier.fillMaxSize()) {
        Surface(color = PanelBackground) {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = requestClose, enabled = !state.loading && !state.saving) { WorkbenchIcon(Glyph.Back, description = stringResource(R.string.editor_back)) }
                Column(Modifier.weight(1f)) {
                    Text(state.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    val status = when {
                        state.saving -> stringResource(R.string.editor_saving)
                        state.dirty -> stringResource(R.string.editor_unsaved)
                        state.saved -> stringResource(R.string.editor_saved)
                        else -> null
                    }
                    Text(status?.let { stringResource(R.string.editor_path_with_status, it, state.path) } ?: state.path,
                        maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, fontSize = 11.sp,
                        color = if (state.dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { searching = !searching }, enabled = document != null && !state.loading) {
                    WorkbenchIcon(Glyph.Search, description = stringResource(R.string.editor_search))
                }
                TextButton(onClick = { controller.save() }, enabled = state.dirty && !state.saving && !state.loading) { Text(stringResource(R.string.editor_save)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            projectTabs.forEach { tab -> FilterChip(selected = tab.path == state.path, onClick = { controller.open(tab.projectId, tab.path) }, enabled = !state.loading && !state.saving, label = { Text(tabLabels.getValue(tab.path) + if (tab.dirty) " •" else "", maxLines = 1) }) }
            }
            IconButton(onClick = { closeTab = true; if (state.dirty) leave = true else controller.close(keepEditorOpen = true) }, enabled = !state.loading && !state.saving) { WorkbenchIcon(Glyph.Close, description = stringResource(R.string.editor_close_tab)) }
            IconButton(onClick = { picking = true }, enabled = !state.loading && !state.saving) { WorkbenchIcon(Glyph.Add, description = stringResource(R.string.editor_open_file)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (state.diskChecking) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.diskState == EditorDiskState.Changed || state.diskState == EditorDiskState.Unavailable) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            if (compact) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (state.diskState == EditorDiskState.Unavailable) R.string.editor_disk_unavailable_short else R.string.editor_disk_changed_short), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { diskDetails = true }) { Text(stringResource(R.string.editor_disk_details)) }
            } else Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(if (state.diskState == EditorDiskState.Unavailable) R.string.editor_disk_unavailable else if (state.dirty) R.string.editor_disk_changed_dirty else R.string.editor_disk_changed_clean),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { reload = true }, enabled = !state.loading && !state.saving && !state.diskChecking) { Text(stringResource(R.string.editor_disk_load)) }
                    state.diskVersion?.let { disk ->
                        TextButton(onClick = { document?.let { diskReview = dev.zakocoder.app.ai.AiReview(state.path, it.content.toString(), disk.text) } }, enabled = !state.loading && !state.saving && !state.diskChecking) {
                            Text(stringResource(R.string.editor_disk_compare))
                        }
                    }
                }
            }
        }
        if (state.error.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.error, Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                if (state.conflict || document == null) TextButton(onClick = { if (state.dirty) reload = true else controller.reload() }, enabled = !state.loading && !state.saving) {
                    Text(stringResource(if (state.conflict) R.string.editor_reload else R.string.editor_retry), fontSize = 12.sp)
                }
            }
        }
        if (searching && document != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.editor_find)) }, supportingText = if (compact) null else ({ Text(stringResource(R.string.editor_matches, matches)) }))
                    IconButton(onClick = { editor?.searcher?.takeIf { it.hasQuery() }?.gotoPrevious() }, enabled = query.isNotEmpty()) { WorkbenchIcon(Glyph.Up, description = stringResource(R.string.editor_previous_match)) }
                    IconButton(onClick = { editor?.searcher?.takeIf { it.hasQuery() }?.gotoNext() }, enabled = query.isNotEmpty()) { WorkbenchIcon(Glyph.Down, description = stringResource(R.string.editor_next_match)) }
                    if (compact) IconButton(onClick = { replaceOpen = true }) { WorkbenchIcon(Glyph.Swap, description = stringResource(R.string.editor_replace)) }
                    IconButton(onClick = { searching = false }, modifier = Modifier.size(48.dp)) { WorkbenchIcon(Glyph.Close, description = stringResource(R.string.editor_close_search)) }
                }
                if (!compact) Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(replacement, { replacement = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.editor_replacement)) })
                    TextButton(onClick = { editor?.searcher?.takeIf { it.hasQuery() }?.replaceCurrentMatch(replacement) }, enabled = query.isNotEmpty() && !state.saving) { Text(stringResource(R.string.editor_replace)) }
                    TextButton(onClick = { controller.replaceAll(query, replacement) }, enabled = query.isNotEmpty() && !state.saving) { Text(stringResource(R.string.editor_replace_all)) }
                }
            }
        }
        if (state.loading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text(loadingDescription) } }
        else if (document != null) key(document) {
            AndroidView(factory = { viewContext ->
                ThemeRegistry.getInstance().setTheme(if (dark) "zako-dark" else "zako-light")
                CodeEditor(viewContext).apply {
                    contentDescription = editorDescription
                    setTextSize(13f)
                    typefaceText = controller.languages.typeface
                    typefaceLineNumber = controller.languages.typeface
                    tabWidth = 4
                    isLineNumberEnabled = true
                    isWordwrap = false
                    setLigatureEnabled(false)
                    setDividerMargin(8f * resources.displayMetrics.density, 8f * resources.displayMetrics.density)
                    colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                    val language = controller.languages.language(state.path)
                    setEditorLanguage(if (language == null) EmptyLanguage() else TextMateLanguage.create("source.zako.$language", false))
                    setText(document.content, true, null)
                    setLineSeparator(if (document.baseline.text.contains("\r\n")) LineSeparator.CRLF else if (document.baseline.text.contains('\r') && !document.baseline.text.contains('\n')) LineSeparator.CR else LineSeparator.LF)
                    setSelection(document.line.coerceAtMost(lineCount - 1), document.column.coerceAtMost(text.getColumnCount(document.line.coerceAtMost(lineCount - 1))))
                    subscribeEvent(PublishSearchResultEvent::class.java) { _, _ -> matches = if (searcher.hasQuery()) searcher.matchedPositionCount else 0 }
                    subscribeEvent(ContentChangeEvent::class.java) { _, _ -> controller.changed() }
                    subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
                        document.line = event.left.line; document.column = event.left.column
                    }
                    // TextMate attachEditor reloads its theme; apply search colors after attachment.
                    colorScheme.setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, if (dark) 0xFF344B68.toInt() else 0xFFDCE9FF.toInt())
                    editor = this
                }
            }, modifier = Modifier.fillMaxWidth().weight(1f).background(if (dark) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface).padding(8.dp), onRelease = {
                if (editor === it) editor = null
                it.release()
            })
        } else Spacer(Modifier.weight(1f))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Surface(color = PanelBackground) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { editor?.undo(); controller.changed() }, enabled = state.canUndo && !state.loading) { WorkbenchIcon(Glyph.Undo, description = stringResource(R.string.editor_undo)) }
                IconButton(onClick = { editor?.redo(); controller.changed() }, enabled = state.canRedo && !state.loading) { WorkbenchIcon(Glyph.Redo, description = stringResource(R.string.editor_redo)) }
                if (onReference != null) IconButton(onClick = {
                    val view = editor
                    val doc = document
                    if (view != null && doc != null) {
                        val left = view.cursor.left; val right = view.cursor.right
                        val all = doc.content.toString()
                        val selected = if (right > left) all.substring(left, right) else all
                        onReference(dev.zakocoder.app.ai.AiReference(state.path, selected.take(6000), if (right > left) "selection" else "file", if (right > left) view.cursor.leftLine + 1 else 1, state.dirty, selected.length > 6000))
                    }
                }, enabled = document != null && !state.loading) { WorkbenchIcon(Glyph.Ai, description = stringResource(R.string.editor_reference_ai)) }
                Text(controller.languages.language(state.path)?.let { stringResource(R.string.editor_language, it) } ?: stringResource(R.string.editor_plain_text),
                    modifier = Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.editor_encoding), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { controller.checkDisk() }, enabled = document != null && !state.loading && !state.saving && !state.diskChecking) { WorkbenchIcon(Glyph.Check, description = stringResource(R.string.editor_disk_check)) }
                IconButton(onClick = { reload = true }, enabled = document != null && !state.loading && !state.saving) { WorkbenchIcon(Glyph.Refresh, description = stringResource(R.string.editor_reload)) }
            }
        }
    }
    if (replaceOpen && document != null) AlertDialog(onDismissRequest = { replaceOpen = false },
        title = { Text(stringResource(R.string.editor_replace)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.editor_matches, matches), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(replacement, { replacement = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.editor_replacement)) })
            TextButton(onClick = { editor?.searcher?.takeIf { it.hasQuery() }?.replaceCurrentMatch(replacement) }, enabled = query.isNotEmpty() && !state.saving) { Text(stringResource(R.string.editor_replace)) }
            TextButton(onClick = { controller.replaceAll(query, replacement) }, enabled = query.isNotEmpty() && !state.saving) { Text(stringResource(R.string.editor_replace_all)) }
        } },
        confirmButton = { TextButton(onClick = { replaceOpen = false }) { Text(stringResource(R.string.close)) } })
    if (diskDetails) AlertDialog(onDismissRequest = { diskDetails = false },
        title = { Text(stringResource(R.string.editor_disk_details)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(if (state.diskState == EditorDiskState.Unavailable) R.string.editor_disk_unavailable else if (state.dirty) R.string.editor_disk_changed_dirty else R.string.editor_disk_changed_clean))
            TextButton(onClick = { diskDetails = false; reload = true }, enabled = !state.loading && !state.saving && !state.diskChecking) { Text(stringResource(R.string.editor_disk_load)) }
            state.diskVersion?.let { disk ->
                TextButton(onClick = { document?.let { diskReview = dev.zakocoder.app.ai.AiReview(state.path, it.content.toString(), disk.text) }; diskDetails = false }, enabled = !state.loading && !state.saving && !state.diskChecking) { Text(stringResource(R.string.editor_disk_compare)) }
            }
        } },
        confirmButton = { TextButton(onClick = { diskDetails = false }) { Text(stringResource(R.string.close)) } })
    if (picking) WorkbenchSheet(stringResource(R.string.editor_open_file), { picking = false }, stringResource(R.string.editor_draft_help)) {
        Text(directory.ifEmpty { stringResource(R.string.project_root) }, style = MaterialTheme.typography.bodySmall)
        if (directory.isNotEmpty()) TextButton(onClick = { directory = directory.substringBeforeLast('/', "") }) { Text(stringResource(R.string.parent_directory)) }
        when {
            listing.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            pickerError -> {
                Text(stringResource(R.string.directory_read_error), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { directoryRetry++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.directory_retry))
                }
            }
            choices.isEmpty() -> Text(stringResource(R.string.directory_empty), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.heightIn(max = 400.dp)) { items(choices, key = { it.relative }) { file -> TextButton(onClick = { if (file.directory) directory = file.relative else { controller.open(state.projectId ?: return@TextButton, file.relative); picking = false } }, modifier = Modifier.fillMaxWidth()) { Text(file.name, Modifier.weight(1f)) } } }
    }
    if (leave) AlertDialog(onDismissRequest = { leave = false }, title = { Text(stringResource(R.string.editor_leave_title)) },
        text = { Text(stringResource(R.string.editor_leave_message)) },
        confirmButton = { TextButton(onClick = { leave = false; controller.save(closeAfter = true, keepEditorOpen = closeTab) }) { Text(stringResource(R.string.editor_save_leave)) } },
        dismissButton = { Row {
            TextButton(onClick = { leave = false }) { Text(stringResource(R.string.editor_keep_editing)) }
            TextButton(onClick = { leave = false; controller.close(keepEditorOpen = closeTab) }) { Text(stringResource(R.string.editor_discard)) }
        } })
    if (reload) AlertDialog(onDismissRequest = { reload = false }, title = { Text(stringResource(R.string.editor_reload)) },
        text = { Text(stringResource(R.string.editor_reload_message)) },
        confirmButton = { TextButton(onClick = { reload = false; controller.reload() }) { Text(stringResource(R.string.editor_confirm_reload)) } },
        dismissButton = { TextButton(onClick = { reload = false }) { Text(stringResource(R.string.cancel)) } })
    diskReview?.let { review -> AiCodeReview(review, controller.languages,
        labels = listOf(R.string.editor_buffer_version, R.string.editor_disk_version, R.string.ai_review_diff),
        note = stringResource(R.string.editor_disk_review_help)) { diskReview = null } }
}
