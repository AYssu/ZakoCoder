package dev.zakocoder.app.sharing

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import dev.zakocoder.app.R
import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.ai.AiController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/** Receives only explicit file shares. URIs are read while the receiving activity owns their grant. */
internal fun sharedFileUris(intent: Intent): List<Uri>? {
    if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
    val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE)
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    else listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
    // Some document providers populate ClipData only; others repeat EXTRA_STREAM there.
    val clips = intent.clipData
    val uris = (streams.take(9) + (0 until minOf(clips?.itemCount ?: 0, 9)).mapNotNull {
        clips?.getItemAt(it)?.uri
    }).distinct()
    require(uris.isNotEmpty() && uris.size <= 8 && uris.all { it.scheme == "content" })
    return uris
}

class IncomingFileShares internal constructor(
    scope: CoroutineScope,
    private val app: StateFlow<WorkbenchState>,
    private val ai: AiController,
    private val feedback: (Int, Boolean) -> Unit,
) {
    private data class Pending(val uris: List<Uri>, val project: String?, val conversation: String?)
    private val queue = Channel<Pending>(4)
    private val mutableNavigation = MutableStateFlow<String?>(null)
    val navigation = mutableNavigation.asStateFlow()

    init {
        scope.launch {
            for (pending in queue) {
                try {
                    app.first { !it.loading }
                    if (app.value.selectedId == null) feedback(R.string.share_choose_workspace, false)
                    // First-run setup and workspace selection remain under the user's control.
                    app.first { !it.loading && it.selectedId != null && !it.onboarding.required }
                    val selected = app.value.selectedId
                    if (pending.project != null && pending.project != selected) {
                        feedback(R.string.share_conversation_changed, true)
                        continue
                    }
                    val target = combine(app, ai.state) { workspace, conversation -> workspace to conversation }.first { (workspace, conversation) ->
                        workspace.selectedId != selected ||
                            (conversation.projectId == selected && !conversation.permissionsLoading && !conversation.restoring)
                    }.second
                    if (app.value.selectedId != selected || target.projectId != selected) {
                        feedback(R.string.share_conversation_changed, true)
                        continue
                    }
                    if ((pending.project != null && target.projectId != pending.project) ||
                        (pending.conversation != null && target.conversationId != pending.conversation)) {
                        feedback(R.string.share_conversation_changed, true)
                        continue
                    }
                    if (target.historyError) {
                        feedback(R.string.share_unavailable, true)
                        continue
                    }
                    if (target.active) feedback(R.string.share_waiting, false)
                    val ready = combine(app, ai.state) { workspace, conversation -> workspace to conversation }.first { (workspace, conversation) ->
                        workspace.selectedId != selected || conversation.projectId != target.projectId ||
                            conversation.conversationId != target.conversationId ||
                            (!conversation.active && !conversation.restoring && !conversation.imagesLoading && !conversation.permissionsLoading && !conversation.permissionsSaving)
                    }.second
                    if (app.value.selectedId != selected || ready.projectId != target.projectId || ready.conversationId != target.conversationId) {
                        feedback(R.string.share_conversation_changed, true)
                        continue
                    }
                    if (ai.addSharedFiles(pending.uris, requireNotNull(target.projectId), target.conversationId))
                        feedback(R.string.share_attached, false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    feedback(R.string.share_unavailable, true)
                }
            }
        }
    }

    /** The activity consumes the intent only after admission, preventing rotation re-imports. */
    fun receive(intent: Intent): Boolean {
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return false
        val uris = try { requireNotNull(sharedFileUris(intent)) }
        catch (_: Exception) { feedback(R.string.share_unavailable, true); return true }
        val current = ai.state.value
        val bound = current.projectId != null && !current.permissionsLoading && !current.restoring
        val pending = Pending(uris, current.projectId ?: app.value.selectedId, current.conversationId.takeIf { bound })
        if (!queue.trySend(pending).isSuccess) feedback(R.string.share_queue_full, true)
        else mutableNavigation.value = UUID.randomUUID().toString()
        return true
    }

    fun consumeNavigation(id: String) {
        if (mutableNavigation.value == id) mutableNavigation.value = null
    }
}
