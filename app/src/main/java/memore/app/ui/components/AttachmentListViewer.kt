package memore.app.ui.components

import androidx.compose.ui.unit.sp
import android.content.Intent
import memore.app.ui.components.MirrorAlertDialog
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import memore.app.data.model.Record
import memore.app.data.repository.AttachmentView
import memore.app.data.repository.RecordRepository
import memore.app.utils.UiSoundPlayer
import memore.app.utils.memoreToast
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AttachmentListViewer(
    record: Record,
    recordRepository: RecordRepository,
    amendRefreshKey: Int = 0,
    resolvedAttachments: List<AttachmentView>? = null,
    onUnlockTemp: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var atts by remember(record.id, amendRefreshKey) { mutableStateOf<List<memore.app.data.model.Attachment>>(emptyList()) }
    var playingPath by remember { mutableStateOf<String?>(null) }
    var progressMs by remember { mutableStateOf(0L) }
    var audioDurations by remember { mutableStateOf(mapOf<String, Long>()) }
    var mpRef by remember { mutableStateOf<MediaPlayer?>(null) }
    var deleteArmedId by remember { mutableStateOf<Long?>(null) }
    var confirmDeleteId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(record.id, amendRefreshKey, resolvedAttachments) {
        atts = if (resolvedAttachments != null) {
            resolvedAttachments.map { av ->
                memore.app.data.model.Attachment(
                    id = av.id, recordId = "", fileName = av.fileName, filePath = av.filePath,
                    type = av.type, fileSize = av.fileSize, duration = av.duration
                )
            }
        } else if (record.isLocked) {
            emptyList()
        } else {
            recordRepository.getAttachments(record.id)
        }
    }
    LaunchedEffect(playingPath) {
        while (playingPath != null) {
            try { if (mpRef?.isPlaying == true) { progressMs = mpRef!!.currentPosition.toLong() } } catch (_: Exception) {}
            delay(200)
        }
        progressMs = 0
    }
    DisposableEffect(Unit) { onDispose { try { mpRef?.release() } catch (_: Exception) {} } }

    MirrorSheet(onDismissRequest = { try { mpRef?.stop(); mpRef?.reset() } catch (_: Exception) {}; onDismiss() }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("\u9644\u4EF6 (${atts.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (record.isLocked && resolvedAttachments == null) {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = MirrorShapes.card, border = mirrorHairline(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Image, "\u9501\u5B9A", Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("\u9644\u4EF6\u5DF2\u52A0\u5BC6", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text("\u4E34\u65F6\u67E5\u770B\u540E\u53EF\u9884\u89C8", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (onUnlockTemp != null) {
                            TextButton(onClick = onUnlockTemp) { Text("\u4E34\u65F6\u67E5\u770B") }
                        }
                    }
                }
            }
            atts.forEach { att ->
                // F1：卡片改行 —— 一行 = 缩略图/播放键 + 文件名 + 类型·大小·时间
                Column(Modifier.fillMaxWidth()) {
                    if (att.type == "IMAGE") {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).combinedClickable(
                            onClick = {
                                try { val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", java.io.File(att.filePath)); context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "image/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Exception) { context.memoreToast("无法打开", Toast.LENGTH_SHORT) }
                            },
                            onLongClick = { deleteArmedId = att.id }
                        ), verticalAlignment = Alignment.CenterVertically) {
                            val imageIcon = rememberVectorPainter(Icons.Outlined.Image)
                            AsyncImage(
                                model = java.io.File(att.filePath),
                                contentDescription = att.fileName,
                                modifier = Modifier.size(44.dp).clip(MirrorShapes.row),
                                placeholder = imageIcon,
                                error = imageIcon,
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(att.fileName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "图片 · ${formatFileSize(att.fileSize)}",
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (deleteArmedId == att.id) {
                                IconButton(onClick = { deleteArmedId = null; confirmDeleteId = att.id }, modifier = Modifier.size(28.dp)) {
                                    Icon(IconTrash, "删除", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).combinedClickable(
                            onClick = {
                                val fp = att.filePath
                                if (playingPath == fp && mpRef?.isPlaying == true) { mpRef?.pause() } else {
                                    try { val m = mpRef ?: MediaPlayer().also { mpRef = it }; m.reset(); m.setDataSource(fp); m.prepare(); val d = m.duration.toLong(); if (d > 0) { audioDurations = audioDurations + (fp to d) }; m.start(); playingPath = fp } catch (_: Exception) { context.memoreToast("播放失败", Toast.LENGTH_SHORT) }
                                }
                            },
                            onLongClick = { deleteArmedId = att.id }
                        ), verticalAlignment = Alignment.CenterVertically) {
                            // 音频：音轨条 + 播放键（和新建记录里的待发送附件同一套样子）
                            Box(
                                Modifier.size(44.dp).clip(MirrorShapes.row)
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    repeat(4) { index ->
                                        Box(
                                            Modifier.padding(horizontal = 1.dp).width(2.dp)
                                                .height((7 + index % 3 * 4).dp)
                                                .clip(MirrorShapes.pill)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(att.fileName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "音频 · ${formatDuration(audioDurations[att.filePath] ?: att.duration ?: 0)}" +
                                        (if (playingPath == att.filePath) " · ${formatDuration(progressMs)}" else ""),
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                if (playingPath == att.filePath && mpRef?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                "播放",
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            if (deleteArmedId == att.id) {
                                IconButton(onClick = { deleteArmedId = null; confirmDeleteId = att.id }, modifier = Modifier.size(28.dp)) {
                                    Icon(IconTrash, "删除", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
                }
            }
        }
    }

    confirmDeleteId?.let { targetId ->
        MirrorStackDialog(
            title = "删除附件",
            message = "确定彻底删除该附件吗？此操作不可恢复。",
            confirmText = "删除",
            destructive = true,
            onDismiss = { confirmDeleteId = null },
            onConfirm = {
                val id = targetId
                confirmDeleteId = null
                scope.launch {
                    try { recordRepository.deleteAttachment(id) } catch (_: Exception) {}
                    atts.find { it.id == id }?.let { att ->
                        try { java.io.File(att.filePath).delete() } catch (_: Exception) {}
                    }
                    atts = atts.filter { it.id != id }
                    UiSoundPlayer.playDelete(context)
                }
            }
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    }
}

private fun formatDuration(ms: Long): String {
    val sec = ms / 1000
    return "%02d:%02d".format(sec / 60, sec % 60)
}
