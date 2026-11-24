package memore.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import memore.app.data.model.Record
import memore.app.data.voice.RecordVoiceStatus
import memore.app.data.voice.RecordVoiceState
import memore.app.data.voice.VoiceTtsRepository
import memore.app.utils.memoreToast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceRecordSheet(
    record: Record,
    voiceTtsRepository: VoiceTtsRepository,
    onDismiss: () -> Unit,
    onStartTts: (String) -> Unit,
    onStateLoaded: (String, RecordVoiceState) -> Unit = { _, _ -> },
    lockedNotDecrypted: Boolean = false,
    onUnlockTemp: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    var state by remember(record.id) { mutableStateOf<RecordVoiceState?>(null) }
    var playing by remember { mutableStateOf(false) }

    LaunchedEffect(record.id) {
        state = voiceTtsRepository.loadRecordVoice(record.id)
        state?.let { onStateLoaded(record.id, it) }
    }
    DisposableEffect(Unit) {
        onDispose { voiceTtsRepository.stopPlayback() }
    }

    MirrorSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Translate, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(if (state?.status == RecordVoiceStatus.AUDIO_READY) "语音" else "语音翻译", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
            val st = state
            when {
                lockedNotDecrypted -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("未解密", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("此记录已加密，临时解密后可查看译文和播放语音", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    if (onUnlockTemp != null) {
                        Button(onClick = onUnlockTemp, modifier = Modifier.fillMaxWidth()) { Text("临时解密") }
                    }
                }
                st == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("加载中…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                st?.status == RecordVoiceStatus.AUDIO_READY && !st.audioPath.isNullOrBlank() -> {
                    val path = st.audioPath
                    Row(
                        Modifier.fillMaxWidth().clip(MirrorShapes.card).background(MaterialTheme.colorScheme.surface).clickable {
                            if (playing) { voiceTtsRepository.stopPlayback(); playing = false }
                            else {
                                try {
                                    voiceTtsRepository.playAudioFile(
                                        path,
                                        onComplete = { playing = false },
                                        onError = { msg -> playing = false; context.memoreToast(msg) }
                                    )
                                    playing = true
                                } catch (e: Exception) {
                                    playing = false
                                    context.memoreToast("播放失败：${e.message}")
                                }
                            }
                        }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (playing) "暂停" else "播放",
                            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("语音.wav", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text("已缓存，点击播放", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                else -> Column {
                    val text = st?.translation?.takeIf { it.isNotBlank() } ?: "暂无译文"
                    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, MirrorShapes.row).padding(12.dp)) {
                        CopyableText(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { st?.translation?.takeIf { it.isNotBlank() }?.let(onStartTts) }, modifier = Modifier.fillMaxWidth(), enabled = !st?.translation.isNullOrBlank()) {
                        Text("TTS")
                    }
                }
            }
        }
    }
}
