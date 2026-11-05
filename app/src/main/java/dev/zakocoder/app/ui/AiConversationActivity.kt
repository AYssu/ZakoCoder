package dev.zakocoder.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.*

internal data class AiDisplayActivity(
    val entries: List<AiEntry>,
    val tools: Map<String, List<AiToolEvent>>,
    val preparation: Map<String, List<AiEntry>>,
)

/** Fold consecutive completed tools and known preparation notices; active work and decisions stay explicit. */
internal fun aiDisplayActivity(state: AiState, preparationTexts: Set<String>): AiDisplayActivity {
    val events = state.events.associateBy { it.id }
    // A tool-only response creates an empty assistant transport entry. It has no visible
    // content and must not split the adjacent operations into separate rows.
    val timeline = state.timeline.filterNot { it.role == AiRole.Assistant && it.toolId == null &&
        it.text.isEmpty() && it.reasoning.isEmpty() && !it.reasoningTruncated && it.images.isEmpty() }
    val entries = mutableListOf<AiEntry>()
    val groups = linkedMapOf<String, List<AiToolEvent>>()
    val notices = linkedMapOf<String, List<AiEntry>>()
    var index = 0
    fun completedTool(entry: AiEntry): AiToolEvent? = events[entry.toolId]?.takeIf {
        it.turn == entry.turn && it.status == AiToolStatus.Succeeded &&
            it.tool != null && it.tool != AiTool.AskUser && entry.text.isEmpty() &&
            entry.reasoning.isEmpty() && !entry.reasoningTruncated
    }
    while (index < timeline.size) {
        val first = timeline[index]
        entries += first
        if (first.role == AiRole.Notice && first.toolId == null && first.text in preparationTexts) {
            val run = timeline.drop(index).takeWhile {
                it.turn == first.turn && it.role == AiRole.Notice && it.toolId == null && it.text in preparationTexts
            }
            notices[first.id] = run
            index += run.size
            val completed = timeline.drop(index).takeWhile { it.turn == first.turn && completedTool(it) != null }
            if (completed.isNotEmpty()) {
                groups[first.id] = completed.map { requireNotNull(completedTool(it)) }
                index += completed.size
            }
        } else if (completedTool(first) != null) {
            val run = timeline.drop(index).takeWhile { it.turn == first.turn && completedTool(it) != null }
            groups[first.id] = run.map { requireNotNull(completedTool(it)) }
            index += run.size
        } else index++
    }
    // Reasoning may be inserted between preparation and its first completed tool. Preserve its place,
    // but attach the known successful notices to that tool group rather than adding another row.
    val foldedPreparation = mutableSetOf<String>()
    entries.forEachIndexed { position, preparation ->
        val details = notices[preparation.id] ?: return@forEachIndexed
        if (groups.containsKey(preparation.id)) return@forEachIndexed
        for (next in entries.drop(position + 1)) {
            if (next.turn != preparation.turn) break
            if (groups.containsKey(next.id)) {
                notices[next.id] = details + notices[next.id].orEmpty()
                foldedPreparation += preparation.id
                break
            }
            val reasoningOnly = next.role == AiRole.Assistant && next.text.isEmpty() &&
                (next.reasoning.isNotEmpty() || next.reasoningTruncated)
            if (!reasoningOnly) break
        }
    }
    foldedPreparation.forEach(notices::remove)
    return AiDisplayActivity(entries.filterNot { it.id in foldedPreparation }, groups, notices)
}

@Composable
internal fun AiPreparationActivity(entries: List<AiEntry>) {
    var expanded by remember(entries.first().id) { mutableStateOf(false) }
    Column {
        ActivityDisclosure(stringResource(R.string.ai_activity_preparation), expanded) { expanded = !expanded }
        if (expanded) entries.forEach { Text(it.text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun AiReadActivity(events: List<AiToolEvent>, preparation: List<AiEntry> = emptyList()) {
    var expanded by remember(events.first().id) { mutableStateOf(false) }
    val parts = buildList {
        val reads = events.filter { it.tool == AiTool.ReadFile }.map { it.target }.distinct().size
        val searches = events.count { it.tool == AiTool.SearchFiles }
        val directories = events.count { it.tool == AiTool.ListFiles }
        val otherTools = events.filter { it.tool !in setOf(AiTool.ReadFile, AiTool.SearchFiles, AiTool.ListFiles) }
        val grouped = otherTools.groupBy { requireNotNull(it.tool) }
        val primary = setOf(AiTool.WriteFile, AiTool.MakeDirectory, AiTool.RunCommand, AiTool.SendTerminalInput)
        grouped.filterKeys { it in primary }.forEach { (tool, calls) ->
            add(stringResource(tool.label) + " " + calls.size)
        }
        if (reads > 0) add(stringResource(R.string.ai_activity_read_files, reads))
        if (searches > 0) add(stringResource(R.string.ai_activity_searches, searches))
        if (directories > 0) add(stringResource(R.string.ai_activity_directories, directories))
        grouped.filterKeys { it !in primary }.forEach { (tool, calls) ->
            add(stringResource(tool.label) + " " + calls.size)
        }
    }
    Column {
        ActivityDisclosure(parts.joinToString(" · "), expanded) { expanded = !expanded }
        if (expanded) {
            preparation.forEach { Text(it.text, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            events.forEach { AiToolCard(it) }
        }
    }
}

@Composable
private fun ActivityDisclosure(label: String, expanded: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, fontSize = 12.sp, lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        WorkbenchIcon(if (expanded) Glyph.ChevronDown else Glyph.Next, Modifier.size(12.dp),
            description = stringResource(if (expanded) R.string.ai_tool_collapse else R.string.ai_tool_details),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
