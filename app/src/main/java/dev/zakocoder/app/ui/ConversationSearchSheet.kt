package dev.zakocoder.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiConversationSearchHit
import dev.zakocoder.app.ai.AiConversationSearchKind
import dev.zakocoder.app.ai.AiConversationSearchResult
import dev.zakocoder.app.projects.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** The controller owns IO and serialization; opening or searching never selects a conversation. */
@Composable
internal fun ConversationSearchSheet(
    projects: List<Project>,
    search: suspend (String) -> AiConversationSearchResult,
    onHit: (projectId: String, conversationId: String, entryId: String) -> Unit,
    onDismiss: () -> Unit,
    revision: Any? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<AiConversationSearchResult?>(null) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var request by remember { mutableIntStateOf(0) }
    val currentSearch by rememberUpdatedState(search)
    val projectNames = remember(projects) { projects.associate { it.id to it.name } }
    val projectIds = remember(projects) { projects.map { it.id } }
    LaunchedEffect(query, projectIds, revision, retry) {
        val ticket = ++request
        val value = query.trim()
        result = null
        failed = false
        searching = value.isNotEmpty()
        if (value.isEmpty()) return@LaunchedEffect
        try {
            delay(250)
            val found = currentSearch(value)
            if (ticket == request) result = found
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (ticket == request) failed = true }
        finally { if (ticket == request) searching = false }
    }
    WorkbenchSheet(stringResource(R.string.conversation_search_title), onDismiss,
        subtitle = stringResource(R.string.conversation_search_scope)) {
        WorkbenchTextField(query, { if (it.length <= 200) query = it }, singleLine = true,
            label = stringResource(R.string.conversation_search_query), modifier = Modifier.fillMaxWidth())
        when {
            query.isBlank() -> Text(stringResource(R.string.conversation_search_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            searching -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.conversation_search_loading), style = MaterialTheme.typography.bodySmall)
            }
            failed -> {
                Text(stringResource(R.string.conversation_search_failed), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retry++ }) { Text(stringResource(R.string.conversation_search_retry)) }
            }
            else -> result?.let { found ->
                if (found.partial) Text(stringResource(R.string.conversation_search_partial, found.unreadableConversations),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (found.hits.isEmpty()) Text(stringResource(if (found.partial) R.string.conversation_search_partial_empty else R.string.conversation_search_empty),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    Text(stringResource(R.string.conversation_search_results, found.hits.size),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // The common sheet scrolls its body. Explicitly bound this virtualized list so it
                    // never receives unbounded vertical constraints from that outer scroll container.
                    LazyColumn(Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(found.hits, key = { index, hit -> "${hit.projectId}:${hit.conversationId}:${hit.entryId}:${hit.kind}:$index" }) { _, hit ->
                            SearchHit(hit, projectNames[hit.projectId].orEmpty()) {
                                onHit(hit.projectId, hit.conversationId, hit.entryId)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHit(hit: AiConversationSearchHit, projectName: String, onClick: () -> Unit) {
    val matchColor = MaterialTheme.colorScheme.primary
    val snippet = remember(hit.snippet, hit.matchStart, hit.matchEnd, matchColor) { buildAnnotatedString {
        append(hit.snippet)
        if (hit.matchStart >= 0 && hit.matchEnd in hit.matchStart..hit.snippet.length)
            addStyle(SpanStyle(color = matchColor, fontWeight = FontWeight.Medium), hit.matchStart, hit.matchEnd)
    } }
    val kind = stringResource(when (hit.kind) {
        AiConversationSearchKind.User -> R.string.conversation_search_user
        AiConversationSearchKind.Assistant -> R.string.conversation_search_assistant
        AiConversationSearchKind.Reasoning -> R.string.conversation_search_reasoning
        AiConversationSearchKind.Tool -> R.string.conversation_search_tool
        AiConversationSearchKind.Error -> R.string.conversation_search_error
        AiConversationSearchKind.Title -> R.string.conversation_search_conversation
    })
    val date = remember(hit.updated) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(hit.updated)) }
    WorkbenchCard(color = MaterialTheme.colorScheme.surfaceContainerLow, cornerRadius = 12.dp) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(hit.title.ifBlank { stringResource(R.string.ai_empty_conversation) },
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(snippet, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.conversation_search_hit_meta, projectName, kind, date), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
