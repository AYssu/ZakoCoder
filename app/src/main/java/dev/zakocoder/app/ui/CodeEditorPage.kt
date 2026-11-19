package dev.zakocoder.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
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
internal fun CodeEditorPage(controller: EditorController, onInstallLsp: ((dev.zakocoder.app.editor.lsp.LspServer) -> Unit)? = null,
    lspEnabled: Boolean = false, onLspEnabledChanged: (Boolean) -> Unit = {}, fullScreen: Boolean = false,
    onConversation: (() -> Unit)? = null,
    onReference: ((dev.zakocoder.app.ai.AiReference) -> Unit)? = null) {
    if (fullScreen) {
        var requestClose by remember { mutableStateOf<() -> Unit>({}) }
        // The file browser remains composed underneath; the editor owns the whole window,
        // including system bars, rather than inheriting the browser's already-consumed insets.
        Dialog(onDismissRequest = { requestClose() }, properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            val view = LocalView.current
            val dark = LocalWorkbenchDark.current
            DisposableEffect(view, dark) {
                (view.parent as? DialogWindowProvider)?.window?.let { window ->
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    window.setDimAmount(0f)
                    if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
                    window.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                onDispose { }
            }
            CodeEditorContent(controller, onInstallLsp, lspEnabled, onLspEnabledChanged, onReference, onConversation,
                dialogWindow = true, onCloseReady = { requestClose = it })
        }
    } else CodeEditorContent(controller, onInstallLsp, lspEnabled, onLspEnabledChanged, onReference, onConversation)
}

@Composable
private fun CodeEditorContent(controller: EditorController, onInstallLsp: ((dev.zakocoder.app.editor.lsp.LspServer) -> Unit)?,
    lspEnabled: Boolean, onLspEnabledChanged: (Boolean) -> Unit, onReference: ((dev.zakocoder.app.ai.AiReference) -> Unit)?,
    onConversation: (() -> Unit)? = null, dialogWindow: Boolean = false, onCloseReady: ((() -> Unit) -> Unit)? = null) {
    val state by controller.state.collectAsStateWithLifecycle()
    val document = state.document
    val language = document?.let { controller.languages.language(state.path, it.content.getLineString(0)) }
    var preview by rememberSaveable(state.projectId, state.path) { mutableStateOf(false) }
    val showingPreview = preview && language == "markdown" && document != null && !state.loading
    val editorDescription = stringResource(R.string.editor_content)
    val loadingDescription = stringResource(R.string.loading)
    val dark = LocalWorkbenchDark.current
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var cursorLine by remember(document) { mutableIntStateOf(document?.line ?: 0) }
    var cursorColumn by remember(document) { mutableIntStateOf(document?.column ?: 0) }
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
    var fileActions by rememberSaveable(state.path) { mutableStateOf(false) }
    var diskReview by remember { mutableStateOf<dev.zakocoder.app.ai.AiReview?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) controller.checkDisk() }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.checkDisk()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(controller, editor, document, state.projectId, state.path, language, lspEnabled, showingPreview) {
        val view = editor
        if (lspEnabled && !showingPreview && view != null && document != null && view.text === document.content) {
            controller.lsp?.attach(view, requireNotNull(state.projectId), state.path) {
                if (language == null) EmptyLanguage() else TextMateLanguage.create("source.zako.$language", false)
            }
        }
        onDispose { view?.let { controller.lsp?.detach(it, restoreSyntax = true) } }
    }
    var searching by rememberSaveable(state.path) { mutableStateOf(false) }
    var query by rememberSaveable(state.path) { mutableStateOf("") }
    var replacement by rememberSaveable(state.path) { mutableStateOf("") }
    val clearNavigationFocus = rememberWorkbenchClearNavigationFocus()
    val requestClose = {
        if (!state.saving) {
            clearNavigationFocus()
            closeTab = false
            if (state.dirty) leave = true else controller.close()
        }
    }
    val interactive = LocalWorkbenchPageInteractive.current
    val internalBack = showingPreview || searching
    val back = rememberWorkbenchBackMotion(enabled = interactive && !state.saving && !internalBack, dialogWindow = dialogWindow,
        resetKey = listOf(state.path, internalBack, leave), animateExit = !state.dirty && !state.saving, onBack = requestClose)
    // Search and Markdown preview are modes within this page, not navigation to the file list.
    // Consume their return without moving the whole editor and revealing the wrong destination.
    SideEffect { onCloseReady?.invoke(back.close) }
    val closeInternalMode = {
        if (!state.saving) {
            if (showingPreview) preview = false
            else { searching = false; editor?.searcher?.stopSearch() }
        }
    }
    if (dialogWindow) NavigationBackHandler(state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = interactive && (state.saving || internalBack), onBackCompleted = closeInternalMode)
    else {
        BackHandler(enabled = interactive && state.saving) { }
        BackHandler(enabled = interactive && !state.saving && internalBack, onBack = closeInternalMode)
    }
    LaunchedEffect(searching, query, editor) {
        editor?.searcher?.let { searcher ->
            if (!searching || query.isEmpty()) searcher.stopSearch()
            else searcher.search(query, EditorSearcher.SearchOptions(false, false))
        }
    }
    Column(Modifier.fillMaxSize().then(back.modifier).background(PanelBackground)
        .then(if (dialogWindow) Modifier.windowInsetsPadding(WindowInsets.systemBars).imePadding() else Modifier)) {
        Surface(color = PanelBackground) {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back.close, enabled = !state.loading && !state.saving) { WorkbenchIcon(Glyph.Back, description = stringResource(R.string.editor_back)) }
                Column(Modifier.weight(1f)) {
                    Text(state.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(state.path, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { fileActions = true }, enabled = document != null && !state.loading && !state.saving) {
                        WorkbenchIcon(Glyph.More, description = stringResource(R.string.editor_file_actions))
                    }
                    DropdownMenu(expanded = fileActions, onDismissRequest = { fileActions = false }, shape = RoundedCornerShape(12.dp)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.editor_select_line)) },
                            onClick = { fileActions = false; editor?.let { view ->
                                val line = view.cursor.leftLine
                                view.setSelectionRegion(line, 0, line, view.text.getColumnCount(line))
                            } }, enabled = !showingPreview)
                        DropdownMenuItem(text = { Text(stringResource(R.string.editor_delete_line)) },
                            onClick = { fileActions = false; editor?.let { view ->
                                val line = view.cursor.leftLine
                                if (line < view.lineCount - 1) view.text.delete(line, 0, line + 1, 0)
                                else if (line > 0) view.text.delete(line - 1, view.text.getColumnCount(line - 1), line, view.text.getColumnCount(line))
                                else view.text.delete(0, 0, 0, view.text.getColumnCount(0))
                            } }, enabled = !showingPreview)
                        listOf(R.string.editor_uppercase_line to true, R.string.editor_lowercase_line to false).forEach { (label, uppercase) ->
                            DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                fileActions = false
                                editor?.let { view ->
                                    val line = view.cursor.leftLine
                                    val value = view.text.getLineString(line)
                                    view.text.replace(line, 0, line, value.length,
                                        if (uppercase) value.uppercase(java.util.Locale.ROOT) else value.lowercase(java.util.Locale.ROOT))
                                }
                            }, enabled = !showingPreview)
                        }
                        if (onReference != null) DropdownMenuItem(text = { Text(stringResource(R.string.editor_reference_ai)) }, onClick = {
                            fileActions = false
                            editor?.let { view ->
                                val left = view.cursor.left; val right = view.cursor.right
                                val all = view.text.toString()
                                val selected = if (right > left) all.substring(left, right) else all
                                onReference(dev.zakocoder.app.ai.AiReference(state.path, selected.take(6000), if (right > left) "selection" else "file",
                                    if (right > left) view.cursor.leftLine + 1 else 1, state.dirty, selected.length > 6000))
                            }
                        }, enabled = !showingPreview)
                        DropdownMenuItem(text = { Text(stringResource(R.string.editor_find)) },
                            onClick = { fileActions = false; replaceOpen = false; searching = true }, enabled = !showingPreview)
                        DropdownMenuItem(text = { Text(stringResource(R.string.editor_replace)) },
                            onClick = { fileActions = false; replaceOpen = true; searching = true }, enabled = !showingPreview)

                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 8.dp).testTag("editor-file-tabs"),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                projectTabs.forEach { tab ->
                    val active = tab.path == state.path
                    val label = tabLabels.getValue(tab.path)
                    Box(Modifier.height(44.dp).widthIn(min = 48.dp, max = 152.dp).width(IntrinsicSize.Max)
                        .semantics { selected = active }
                        .clickable(enabled = !state.loading && !state.saving, role = Role.Tab) { controller.open(tab.projectId, tab.path) },
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (active) MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.ui.graphics.Color.Transparent)
                            .padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                            Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            IconButton(onClick = { closeTab = true; if (state.dirty) leave = true else controller.close(keepEditorOpen = true) },
                modifier = Modifier.size(44.dp), enabled = !state.loading && !state.saving) {
                WorkbenchIcon(Glyph.Close, Modifier.size(16.dp), description = stringResource(R.string.editor_close_tab))
            }
            IconButton(onClick = { picking = true }, modifier = Modifier.size(44.dp), enabled = !state.loading && !state.saving) {
                WorkbenchIcon(Glyph.Add, Modifier.size(16.dp), description = stringResource(R.string.editor_open_file))
            }
        }
        if (language == "markdown" && document != null && !state.loading) SecondaryTabRow(selectedTabIndex = if (showingPreview) 1 else 0) {
            Tab(selected = !showingPreview, onClick = { preview = false }, text = { Text(stringResource(R.string.editor_mode_edit)) })
            Tab(selected = showingPreview, onClick = {
                searching = false; editor?.searcher?.stopSearch(); editor?.clearFocus()
                (editor?.context?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                    ?.hideSoftInputFromWindow(editor?.windowToken, 0)
                preview = true
            }, text = { Text(stringResource(R.string.editor_mode_preview)) })
        }
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
        if (searching && document != null) EditorSearchBar(
            query = query, onQueryChange = { query = it }, replacement = replacement, onReplacementChange = { replacement = it },
            replacing = replaceOpen, matches = matches, enabled = !state.loading && !state.saving,
            onPrevious = { editor?.searcher?.takeIf { it.hasQuery() }?.gotoPrevious() },
            onNext = { editor?.searcher?.takeIf { it.hasQuery() }?.gotoNext() },
            onReplace = { editor?.searcher?.takeIf { it.hasQuery() }?.replaceCurrentMatch(replacement) },
            onReplaceAll = { controller.replaceAll(query, replacement) },
            onClose = { searching = false; replaceOpen = false })
        if (state.loading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text(loadingDescription) } }
        else if (showingPreview) {
            val snapshot = remember(document, state.revision, showingPreview) { requireNotNull(document).content.toString() }
            key(document) { EditorMarkdownPreview(snapshot, Modifier.weight(1f).fillMaxWidth()) }
        }
        else if (document != null) key(document, language, dark) {
            AndroidView(factory = { viewContext ->
                ThemeRegistry.getInstance().setTheme(if (dark) "zako-dark" else "zako-light")
                CodeEditor(viewContext).apply {
                    contentDescription = editorDescription
                    setTextSize(13f)
                    typefaceText = controller.languages.typeface
                    typefaceLineNumber = controller.languages.typeface
                    tabWidth = 4
                    isLineNumberEnabled = true
                    setLineNumberMarginLeft(8f * resources.displayMetrics.density)
                    isHighlightCurrentLine = true
                    isHighlightBracketPair = true
                    isBlockLineEnabled = true
                    isCursorAnimationEnabled = true
                    setLineSpacing(0f, 1.15f)
                    isWordwrap = false
                    setLigatureEnabled(false)
                    setDividerMargin(8f * resources.displayMetrics.density, 8f * resources.displayMetrics.density)
                    colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                    setEditorLanguage(if (language == null) EmptyLanguage() else TextMateLanguage.create("source.zako.$language", false))
                    setText(document.content, true, null)
                    setLineSeparator(if (document.baseline.text.contains("\r\n")) LineSeparator.CRLF else if (document.baseline.text.contains('\r') && !document.baseline.text.contains('\n')) LineSeparator.CR else LineSeparator.LF)
                    setSelection(document.line.coerceAtMost(lineCount - 1), document.column.coerceAtMost(text.getColumnCount(document.line.coerceAtMost(lineCount - 1))))
                    subscribeEvent(PublishSearchResultEvent::class.java) { _, _ -> matches = if (searcher.hasQuery()) searcher.matchedPositionCount else 0 }
                    subscribeEvent(ContentChangeEvent::class.java) { _, _ -> controller.changed() }
                    subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
                        document.line = event.left.line; document.column = event.left.column
                        cursorLine = event.left.line; cursorColumn = event.left.column
                        controller.cursorChanged()
                    }
                    // TextMate attachEditor reloads its theme; apply search colors after attachment.
                    colorScheme.setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, if (dark) 0xFF344B68.toInt() else 0xFFDCE9FF.toInt())
                    editor = this
                }
            }, modifier = Modifier.fillMaxWidth().weight(1f).background(if (dark) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface), onRelease = {
                controller.lsp?.detach(it, restoreSyntax = false)
                if (editor === it) editor = null
                it.release()
            })
        } else Spacer(Modifier.weight(1f))
        Surface(color = PanelBackground) {
            val swipeThreshold = with(LocalDensity.current) { 24.dp.toPx() }
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("editor-workspace-bar")
                .pointerInput(state.projectId) {
                    var drag = 0f
                    detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = { drag = 0f },
                        onDragCancel = { drag = 0f }) { change, amount ->
                        drag += amount
                        if (drag < -swipeThreshold) { picking = true; change.consume() }
                    }
                }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { picking = true }, contentPadding = PaddingValues(horizontal = 4.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    WorkbenchIcon(Glyph.Up, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.projects_title), fontSize = 11.sp)
                }
                Text(listOf(language?.let { stringResource(R.string.editor_language, it) } ?: stringResource(R.string.editor_plain_text), stringResource(R.string.editor_encoding)).joinToString(" · "),
                    Modifier.weight(1f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (document != null && !showingPreview) controller.lsp?.let { lsp ->
                    Box(Modifier.widthIn(max = 160.dp)) {
                        EditorLspStatus(lsp, requireNotNull(state.projectId), state.path, enabled = lspEnabled, onEnabledChanged = onLspEnabledChanged,
                            onInstall = { server -> onInstallLsp?.invoke(server) }, onRetry = {
                                editor?.takeIf { lspEnabled }?.let { view -> lsp.attach(view, requireNotNull(state.projectId), state.path) {
                                    if (language == null) EmptyLanguage() else TextMateLanguage.create("source.zako.$language", false)
                                } }
                            })
                    }
                }
                if (document != null && !showingPreview) {
                    val cursorDescription = stringResource(R.string.editor_cursor_description, cursorLine + 1, cursorColumn + 1)
                    Text(stringResource(R.string.editor_cursor_position, cursorLine + 1, cursorColumn + 1),
                        Modifier.padding(start = 8.dp).semantics { contentDescription = cursorDescription },
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!showingPreview) Surface(color = PanelBackground) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val canEdit = document != null && !state.loading && !state.saving
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).testTag("editor-shortcuts")) {
                IconButton(onClick = { editor?.undo(); controller.changed() }, enabled = state.canUndo && canEdit) { WorkbenchIcon(Glyph.Undo, description = stringResource(R.string.editor_undo)) }
                IconButton(onClick = { editor?.redo(); controller.changed() }, enabled = state.canRedo && canEdit) { WorkbenchIcon(Glyph.Redo, description = stringResource(R.string.editor_redo)) }
                    TextButton(onClick = { editor?.let { it.indentOrCommitTab(); it.requestFocus() } }, enabled = canEdit,
                        modifier = Modifier.size(48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Text(stringResource(R.string.editor_indent_key), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    listOf("{", "}", "(", ")", "[", "]", ";", "=", "\"", "'").forEach { symbol ->
                        TextButton(onClick = { editor?.let { it.commitText(symbol); it.requestFocus() } }, enabled = canEdit,
                            modifier = Modifier.size(48.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            Text(symbol, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        }
                    }
                }
                if (onConversation != null) IconButton(onClick = onConversation, enabled = !state.loading) {
                    WorkbenchIcon(Glyph.Ai, description = stringResource(R.string.editor_conversation))
                } else if (onReference != null) IconButton(onClick = {
                    val view = editor
                    val doc = document
                    if (view != null && doc != null) {
                        val left = view.cursor.left; val right = view.cursor.right
                        val all = doc.content.toString()
                        val selected = if (right > left) all.substring(left, right) else all
                        onReference(dev.zakocoder.app.ai.AiReference(state.path, selected.take(6000), if (right > left) "selection" else "file", if (right > left) view.cursor.leftLine + 1 else 1, state.dirty, selected.length > 6000))
                    }
                }, enabled = canEdit) { WorkbenchIcon(Glyph.Ai, description = stringResource(R.string.editor_reference_ai)) }
            }
        }
    }
    if (diskDetails) WorkbenchAlertDialog(onDismissRequest = { diskDetails = false },
        title = { Text(stringResource(R.string.editor_disk_details)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(if (state.diskState == EditorDiskState.Unavailable) R.string.editor_disk_unavailable else if (state.dirty) R.string.editor_disk_changed_dirty else R.string.editor_disk_changed_clean))
            TextButton(onClick = { diskDetails = false; reload = true }, enabled = !state.loading && !state.saving && !state.diskChecking) { Text(stringResource(R.string.editor_disk_load)) }
            state.diskVersion?.let { disk ->
                TextButton(onClick = { document?.let { diskReview = dev.zakocoder.app.ai.AiReview(state.path, it.content.toString(), disk.text) }; diskDetails = false }, enabled = !state.loading && !state.saving && !state.diskChecking) { Text(stringResource(R.string.editor_disk_compare)) }
            }
        } },
        confirmButton = { WorkbenchDialogButton(onClick = { diskDetails = false }) { Text(stringResource(R.string.close)) } })
    if (picking) WorkbenchSheet(stringResource(R.string.editor_workspace_files), { picking = false }) {
        val finish = LocalWorkbenchSheetAction.current
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
        LazyColumn(Modifier.heightIn(max = 400.dp)) { items(choices, key = { it.relative }) { file -> TextButton(onClick = { if (file.directory) directory = file.relative else { val id = state.projectId ?: return@TextButton; finish { controller.open(id, file.relative) } } }, modifier = Modifier.fillMaxWidth()) { Text(file.name, Modifier.weight(1f)) } } }
    }
    if (leave) WorkbenchAlertDialog(onDismissRequest = { leave = false }, title = { Text(stringResource(R.string.editor_leave_title)) },
        text = { Text(stringResource(R.string.editor_leave_message)) },
        confirmButton = { WorkbenchDialogButton(onClick = { leave = false; controller.save(closeAfter = true, keepEditorOpen = closeTab) }) { Text(stringResource(R.string.editor_save_leave)) } },
        dismissButton = { Row {
            WorkbenchDialogButton(onClick = { leave = false }) { Text(stringResource(R.string.editor_keep_editing)) }
            WorkbenchDialogButton(onClick = { leave = false; controller.close(keepEditorOpen = closeTab) }) { Text(stringResource(R.string.editor_discard)) }
        } })
    if (reload) WorkbenchAlertDialog(onDismissRequest = { reload = false }, title = { Text(stringResource(R.string.editor_reload)) },
        text = { Text(stringResource(R.string.editor_reload_message)) },
        confirmButton = { WorkbenchDialogButton(onClick = { reload = false; controller.reload() }) { Text(stringResource(R.string.editor_confirm_reload)) } },
        dismissButton = { WorkbenchDialogButton(onClick = { reload = false }) { Text(stringResource(R.string.cancel)) } })
    diskReview?.let { review -> AiCodeReview(review, controller.languages,
        labels = listOf(R.string.editor_buffer_version, R.string.editor_disk_version, R.string.ai_review_diff),
        note = stringResource(R.string.editor_disk_review_help)) { diskReview = null } }
}
