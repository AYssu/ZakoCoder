package dev.zakocoder.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiCallDraft
import dev.zakocoder.app.ai.AiTool

/** Presentation only: incomplete arguments never become executable commands. */
@Composable
internal fun AiToolLivePreview(draft: AiCallDraft, modifier: Modifier = Modifier) {
    var expanded by remember(draft.index, draft.name) { mutableStateOf(false) }
    val label = AiTool.named(draft.name)?.let { stringResource(it.label) }
        ?: draft.name.ifBlank { stringResource(R.string.ai_receiving_arguments) }
    val preview = remember(draft.arguments, draft.previewLine) { draft.previewLine.ifEmpty { dev.zakocoder.app.ai.toolArgumentLine(draft.arguments, localEdit = draft.name == "edit_file") } }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.dp)
            WorkbenchIcon(aiToolGlyph(AiTool.named(draft.name)), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            ArrivalText(preview, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, animated = true)
            WorkbenchIcon(if (expanded) Glyph.ChevronDown else Glyph.Next, Modifier.size(12.dp),
                description = stringResource(if (expanded) R.string.ai_tool_collapse else R.string.ai_tool_details))
        }
        if (expanded) {
            Text(stringResource(R.string.ai_tool_generating), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CodeDetail(draft.arguments, animated = true)
            if (draft.argumentTail.isNotEmpty()) {
                Text(stringResource(R.string.ai_tool_preview_tail), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CodeDetail(draft.argumentTail, animated = true)
            }
        }
    }
}
