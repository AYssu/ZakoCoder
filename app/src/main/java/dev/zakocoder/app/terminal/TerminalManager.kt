package dev.zakocoder.app.terminal

import android.content.Context
import android.graphics.Typeface
import androidx.core.graphics.toColorInt
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.zakocoder.app.runtime.RuntimeCommand
import dev.zakocoder.app.runtime.OwnedProcesses
import dev.zakocoder.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.UUID

data class TerminalItem(val id: String, val projectId: String, val projectName: String, val session: TerminalSession, val number: Int, val startedAt: Long = android.os.SystemClock.elapsedRealtime())
data class TerminalModifiers(val control: Boolean = false, val alt: Boolean = false)

class TerminalManager(private val context: Context, private val changed: () -> Unit) {
    @Volatile private var terminalTypeface: Typeface? = null
    /** Load the packaged, unmodified font on IO before attaching any terminal view. */
    fun prepareTypeface() {
        if (terminalTypeface == null) terminalTypeface = Typeface.createFromAsset(context.assets, "fonts/JetBrainsMono-Regular.ttf")
    }
    val sessions = mutableListOf<TerminalItem>()
    private val mutablePreviews = MutableStateFlow<Map<String, String>>(emptyMap())
    val previews = mutablePreviews.asStateFlow()
    private val previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var previewPending = false
    private fun refreshPreviews() {
        if (previewPending) return
        previewPending = true
        previewHandler.postDelayed({
            previewPending = false
            mutablePreviews.value = sessions.associate { item ->
                val emulator = item.session.emulator
                item.id to if (emulator == null) "" else emulator.screen.getSelectedText(
                    0, maxOf(-emulator.screen.activeTranscriptRows, emulator.cursorRow - 2), emulator.mColumns, emulator.cursorRow,
                ).lineSequence().take(3).joinToString("\n") { it.take(2048) }.trimEnd()
            }
        }, 150)
    }
    /** Explicit UI request, serialized with emulator access on Main; no readiness or ownership inference. */
    fun previewAddresses(id: String): List<String> {
        val emulator = sessions.singleOrNull { it.id == id }?.session?.emulator ?: return emptyList()
        val screen = emulator.screen
        val text = screen.getSelectedText(0, maxOf(-screen.activeTranscriptRows, emulator.cursorRow - 100), emulator.mColumns, emulator.mRows - 1)
        return LocalPreviewAddress.candidates(text)
    }
    private var attached = WeakReference<TerminalView>(null)
    private val appearances = mutableMapOf<String, Boolean>()
    private var nextNumber = 1
    private val mutableModifiers = MutableStateFlow(TerminalModifiers())
    val modifiers = mutableModifiers.asStateFlow()
    fun toggleControl() { mutableModifiers.update { it.copy(control = !it.control) } }
    fun toggleAlt() { mutableModifiers.update { it.copy(alt = !it.alt) } }
    private fun clearModifiers() { mutableModifiers.value = TerminalModifiers() }
    fun create(projectId: String, name: String, command: RuntimeCommand): TerminalItem {
        require(sessions.size < 4)
        val session = TerminalSession(command.executable, context.filesDir.absolutePath,
            (listOf(command.executable) + command.arguments).toTypedArray(),
            command.environment.map { "${it.key}=${it.value}" }.toTypedArray(), 4000, client)
        return TerminalItem(UUID.randomUUID().toString(), projectId, name, session, nextNumber++).also {
            sessions += it
            // Start before exposing a closable session: an uninitialized upstream
            // TerminalSession has pid=0, which must never be passed to kill().
            session.updateSize(80, 24, 8, 16)
            session.emulator.mColors.tryParseColor(com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND, "#0D1117")
            session.emulator.mColors.tryParseColor(com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND, "#C9D1D9")
            changed()
            refreshPreviews()
        }
    }
    fun view(context: Context, item: TerminalItem, fontSp: Int): TerminalView = TerminalView(context, null).apply {
        clearModifiers()
        id = android.view.View.generateViewId()
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = context.getString(R.string.terminal_content)
        setTerminalViewClient(viewClient(this))
        setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, fontSp.toFloat(), resources.displayMetrics).toInt())
        setTypeface(checkNotNull(terminalTypeface))
        setBackgroundColor(android.graphics.Color.rgb(13, 17, 23))
        attachSession(item.session)
        attached = WeakReference(this)
        post { if (attached.get() === this) requestFocus() }
    }
    /** Theme changes affect only the emulator palette and view paint, preserving PTY and IME ownership. */
    fun appearance(view: TerminalView, item: TerminalItem, dark: Boolean) {
        val background = if (dark) "#05080C" else "#0D1117"
        if (appearances.put(item.id, dark) != dark) {
            item.session.emulator.mColors.tryParseColor(com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND, background)
        }
        view.setBackgroundColor(background.toColorInt())
    }
    /** Keep the same View and window token while switching PTYs. */
    fun bind(view: TerminalView, item: TerminalItem) {
        if (view.currentSession === item.session) return
        clearModifiers()
        view.stopTextSelectionMode()
        view.attachSession(item.session)
        attached = WeakReference(view)
        (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(view)
    }
    fun detach(view: TerminalView) {
        if (attached.get() === view) { attached.clear(); clearModifiers() }
        view.clearFocus()
    }
    suspend fun close(id: String) {
        val item = sessions.singleOrNull { it.id == id } ?: return
        withContext(Dispatchers.IO) { if (item.session.pid > 0) OwnedProcesses.terminate(item.session.pid) }
        if (item.session.pid > 0) item.session.finishIfRunning()
        sessions.remove(item); appearances.remove(item.id)
        mutablePreviews.update { it - item.id }
        changed()
    }
    suspend fun closeAll() { sessions.toList().forEach { close(it.id) } }
    fun keyboard(view: TerminalView) {
        view.requestFocus()
        (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
    fun toggleKeyboard(view: TerminalView) {
        val visible = androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        if (visible) hideKeyboard(view) else keyboard(view)
    }
    fun hideKeyboard(view: TerminalView) {
        (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }
    /** Route helper keys through the same emulator-aware path as physical keyboard events. */
    fun key(view: TerminalView, keyCode: Int, control: Boolean = false) {
        if (!view.currentSession.isRunning) return
        // Dedicated Ctrl+C is an interrupt even if a one-shot modifier was armed.
        if (control) clearModifiers()
        val meta = if (control) KeyEvent.META_CTRL_ON else 0
        view.onKeyDown(keyCode, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
    }
    private val client = object : TerminalSessionClient {
        override fun onTextChanged(session: TerminalSession) { attached.get()?.takeIf { it.currentSession === session }?.onScreenUpdated(); refreshPreviews() }
        override fun onTitleChanged(session: TerminalSession) = changed()
        override fun onSessionFinished(session: TerminalSession) { onTextChanged(session); changed() }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) { /* OSC 52 does not write the system clipboard automatically. */ }
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = onTextChanged(session)
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) = changed()
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String?, message: String?) { Log.e(tag, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag, message ?: "") }
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "Terminal failure", e) }
    }
    private fun viewClient(view: TerminalView) = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = 1f
        override fun onSingleTapUp(e: MotionEvent?) = keyboard(view)
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = false
        override fun shouldUseCtrlSpaceWorkaround() = true
        override fun isTerminalViewSelected() = attached.get() === view
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?) = false
        override fun readControlKey(): Boolean {
            val pressed = mutableModifiers.value.control
            if (pressed) mutableModifiers.update { it.copy(control = false) }
            return pressed
        }
        override fun readAltKey(): Boolean {
            val pressed = mutableModifiers.value.alt
            if (pressed) mutableModifiers.update { it.copy(alt = false) }
            return pressed
        }
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
        override fun onEmulatorSet() = Unit
        override fun logError(tag: String?, message: String?) = client.logError(tag, message)
        override fun logWarn(tag: String?, message: String?) = client.logWarn(tag, message)
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = client.logStackTraceWithMessage(tag, message, e)
        override fun logStackTrace(tag: String?, e: Exception?) = client.logStackTrace(tag, e)
    }
}
