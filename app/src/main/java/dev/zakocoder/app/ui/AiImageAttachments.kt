package dev.zakocoder.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiImageAttachment
import kotlinx.coroutines.CancellationException

@Composable
internal fun AiImageAttachments(images: List<AiImageAttachment>, load: suspend (AiImageAttachment) -> Bitmap,
                                enabled: Boolean = true, onRemove: ((String) -> Unit)? = null) {
    if (images.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEach { image -> key(image.id) {
            val preview by produceState<Bitmap?>(null, image.id) {
                value = try { load(image) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            }
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val bitmap = preview
                    if (bitmap != null) Image(bitmap.asImageBitmap(), image.displayName, Modifier.size(56.dp), contentScale = ContentScale.Fit)
                    else WorkbenchIcon(Glyph.File, Modifier.size(40.dp), description = stringResource(R.string.ai_images_preview_unavailable))
                    Column(Modifier.widthIn(max = 140.dp)) {
                        Text(image.displayName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${image.width} × ${image.height}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (onRemove != null) IconButton(onClick = { onRemove(image.id) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                        WorkbenchIcon(Glyph.Close, Modifier.size(16.dp), description = stringResource(R.string.ai_images_remove, image.displayName))
                    }
                }
            }
        } }
    }
}
