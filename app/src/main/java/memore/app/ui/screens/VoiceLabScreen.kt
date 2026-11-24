package memore.app.ui.screens

import androidx.compose.foundation.layout.offset
import memore.app.ui.components.MemoreLineInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import memore.app.ui.components.MirrorDropdownMenu
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.core.voice.VoiceInfo
import memore.app.data.voice.VoiceSettings
import memore.app.data.voice.VoiceSettingsRepository
import memore.app.data.voice.VoiceTtsRepository
import memore.app.utils.UiSoundPlayer
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceLabScreen(
    voiceSettingsRepository: VoiceSettingsRepository,
    voiceTtsRepository: VoiceTtsRepository,
    onBack: () -> Unit
) {
    var settings by remember { mutableStateOf(voiceSettingsRepository.load()) }
    var voices by remember { mutableStateOf<List<VoiceInfo>>(emptyList()) }
    var voiceStatus by remember { mutableStateOf("TTS 状态：检测中…") }
    var voiceMenu by remember { mutableStateOf(false) }
    var showSoundEdit by remember { mutableStateOf(false) }
    var inAdvanced by remember { mutableStateOf(false) }
    var seedText by remember { mutableStateOf(settings.seed.toString()) }
    var topKText by remember { mutableStateOf(settings.topK.toString()) }
    var maxTokensText by remember { mutableStateOf(settings.maxTokens.toString()) }
    var maxCharsText by remember { mutableStateOf(settings.maxChars.toString()) }

    fun save(next: VoiceSettings) {
        settings = next
        voiceSettingsRepository.save(next)
        UiSoundPlayer.setEnabled(next.soundsEnabled)
    }

    LaunchedEffect(Unit) {
        try {
            voices = voiceTtsRepository.listVoices()
            voiceStatus = "TTS 在线：${voices.size} 个音色"
            // 无硬编码默认音色：若当前未选或不在服务端列表，自动取第一个可用音色
            val current = settings.voiceId
            if (voices.isNotEmpty() && (current.isBlank() || voices.none { it.id == current })) {
                save(settings.copy(voiceId = voices.first().id))
            }
        } catch (e: Exception) {
            voiceStatus = "TTS 未连接（${e.message}）"
        }
    }
    DisposableEffect(Unit) {
        onDispose { voiceTtsRepository.release() }
    }

    if (showSoundEdit) {
        SoundEditScreen(onBack = { showSoundEdit = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (inAdvanced) "高级参数" else "语音实验室",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (inAdvanced) inAdvanced = false else onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (inAdvanced) {
                // A1：去掉卡片；每个滑块 = 名字+值一行，滑块紧贴其下；数值项 = 行 + 右侧值
                Column(Modifier.fillMaxWidth()) {
                    VoiceSliderRow("温度", settings.temperature, 0.1f..1.5f) { save(settings.copy(temperature = it)) }
                    VoiceSliderRow("语速", settings.speed, 0.5f..2.0f) { save(settings.copy(speed = it)) }
                    VoiceSliderRow("top_p", settings.topP, 0.0f..1.0f) { save(settings.copy(topP = it)) }
                    VoiceSliderRow("重复惩罚", settings.repetitionPenalty, 0.5f..2.0f) { save(settings.copy(repetitionPenalty = it)) }

                    Text(
                        "其它",
                        fontSize = 10.5.sp,
                        letterSpacing = 1.1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 18.dp, bottom = 2.dp)
                    )
                    VoiceValueRow("随机种子", seedText, androidx.compose.ui.text.input.KeyboardType.Number) { input ->
                        val f = input.filter { c -> c.isDigit() }
                        seedText = f
                        f.toIntOrNull()?.let { n -> save(settings.copy(seed = n)) }
                    }
                    VoiceValueRow("top_k", topKText, androidx.compose.ui.text.input.KeyboardType.Number) { input ->
                        val f = input.filter { c -> c.isDigit() }
                        topKText = f
                        f.toIntOrNull()?.let { n -> save(settings.copy(topK = n.coerceAtLeast(1))) }
                    }
                    VoiceValueRow("max_tokens", maxTokensText, androidx.compose.ui.text.input.KeyboardType.Number) { input ->
                        val f = input.filter { c -> c.isDigit() }
                        maxTokensText = f
                        f.toIntOrNull()?.let { n -> save(settings.copy(maxTokens = n.coerceAtLeast(1))) }
                    }
                    VoiceValueRow("模型", settings.model, androidx.compose.ui.text.input.KeyboardType.Text) { input ->
                        save(settings.copy(model = input))
                    }
                }
            } else {
                // V1「仪表」：状态置顶；三个参数行统一「就地展开」，音色不再弹菜单（2026-10-23 第二次修）
                val voiceName = voices.firstOrNull { it.id == settings.voiceId }?.name
                    ?: settings.voiceId.ifBlank { "未选择" }
                val online = !voiceStatus.startsWith("TTS 未连接")
                var editEndpoint by remember { mutableStateOf(false) }
                var editMaxChars by remember { mutableStateOf(false) }
                var voiceListOpen by remember { mutableStateOf(false) }

                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(6.dp).clip(MirrorShapes.pill).background(
                            if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (online) "已连接" else "未连接",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    voiceName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    settings.ttsEndpoint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(14.dp))
                VoiceRule(strong = true)

                VoiceSectionLabel("合成")
                VoiceRow("端点", trimUrl(settings.ttsEndpoint), VoiceRowKind.Expand, expanded = editEndpoint) {
                    editEndpoint = !editEndpoint
                }
                if (editEndpoint) {
                    MemoreLineInput(
                        value = settings.ttsEndpoint,
                        onValueChange = { save(settings.copy(ttsEndpoint = it)) },
                        singleLine = true,
                        shape = MirrorShapes.row,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                }
                VoiceRow("音色", voiceName, VoiceRowKind.Expand, expanded = voiceListOpen) {
                    voiceListOpen = !voiceListOpen
                }
                if (voiceListOpen) {
                    if (voices.isEmpty()) {
                        Text(
                            "没有可用音色（先启动 EV 代理）",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    } else {
                        voices.forEach { v ->
                            val picked = v.id == settings.voiceId
                            Row(
                                Modifier.fillMaxWidth().height(48.dp).clickable {
                                    save(settings.copy(voiceId = v.id))
                                    voiceListOpen = false
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    v.name,
                                    fontSize = 14.5.sp,
                                    fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                                if (picked) {
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "已选",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                VoiceRow("最大字数", settings.maxChars.toString(), VoiceRowKind.Expand, expanded = editMaxChars) {
                    editMaxChars = !editMaxChars
                }
                if (editMaxChars) {
                    MemoreLineInput(
                        value = maxCharsText,
                        onValueChange = { input ->
                            val filtered = input.filter { c -> c.isDigit() }
                            maxCharsText = filtered
                            filtered.toIntOrNull()?.let { n -> save(settings.copy(maxChars = n)) }
                        },
                        singleLine = true,
                        shape = MirrorShapes.row,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                }

                VoiceSectionLabel("声音")
                VoiceSwitchRow("缓存音频", settings.cacheEnabled) { save(settings.copy(cacheEnabled = it)) }
                VoiceSwitchRow("提示音", settings.soundsEnabled) { save(settings.copy(soundsEnabled = it)) }
                VoiceRule(strong = false)
                VoiceRow("提示音编辑", "", VoiceRowKind.Navigate) { showSoundEdit = true }
                VoiceRow("音源", "本地", VoiceRowKind.Static) {}
                VoiceRow("高级参数", "", VoiceRowKind.Navigate) { inAdvanced = true }
            }
        }
    }
}

/** 分组线：strong = 18%（大分组），否则 10%（行间）。 */
@Composable
private fun VoiceRule(strong: Boolean) {
    Box(
        Modifier.fillMaxWidth().height(1.dp).background(
            MaterialTheme.colorScheme.onSurface.copy(alpha = if (strong) 0.18f else 0.10f)
        )
    )
}

@Composable
private fun VoiceSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
    )
}

/** 行的三种语义：就地展开 / 进入另一页 / 不可点。 */
private enum class VoiceRowKind { Expand, Navigate, Static }

/**
 * 参数行：52 高，标签 15/500，值 13 muted。
 *
 * 箭头必须**跟着语义走**，不能同一个符号混三种含义：
 *  - Expand：⌄ 展开 → ⌃ 收起（就地长出输入框或列表）
 *  - Navigate：› 不翻（跳到另一页）
 *  - Static：没有箭头（点不动）
 */
@Composable
private fun VoiceRow(
    label: String,
    value: String,
    kind: VoiceRowKind,
    expanded: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp)
            .clickable(enabled = kind != VoiceRowKind.Static, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(
                value,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        when (kind) {
            VoiceRowKind.Expand -> Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VoiceRowKind.Navigate -> Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VoiceRowKind.Static -> Unit
        }
    }
}

/** 端点显示用：去掉协议前缀，行尾只留主机和端口（控件里仍是完整 URL）。 */
private fun trimUrl(url: String): String =
    url.removePrefix("http://").removePrefix("https://").trimEnd('/')

@Composable
private fun VoiceSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
/** 一行滑块：名字 + 当前值在右，滑块紧贴其下（无卡片）。 */
@Composable
private fun VoiceSliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text(
                "%.2f".format(value),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        // 细滑块：2dp 轨道 + 9dp 圆点（M3 的 Slider 太厚，这里自己画）
        val primary = MaterialTheme.colorScheme.primary
        val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(22.dp).padding(end = 6.dp)
        ) {
            val trackW = maxWidth
            val frac = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(2.dp).align(Alignment.CenterStart).background(inactive))
            Box(
                Modifier.fillMaxWidth(frac).height(2.dp).align(Alignment.CenterStart)
                    .background(primary.copy(alpha = 0.45f))
            )
            Box(
                Modifier.align(Alignment.CenterStart)
                    .offset(x = trackW * frac - 4.dp)
                    .size(9.dp).clip(CircleShape)
                    .background(primary.copy(alpha = 0.75f))
            )
            Box(
                Modifier.fillMaxWidth().height(22.dp)
                    .pointerInput(range) {
                        detectDragGestures(
                            onDragStart = { off -> onChange(snap(range, off.x / size.width)) },
                            onDrag = { change, _ -> onChange(snap(range, change.position.x / size.width)) }
                        )
                    }
                    .pointerInput(range) {
                        detectTapGestures { off -> onChange(snap(range, off.x / size.width)) }
                    }
            )
        }
    }
}

/** 一行数值：名字 + 右侧可直接编辑的值（横线在整行下面）。 */
@Composable
private fun VoiceValueRow(
    label: String,
    value: String,
    keyboardType: androidx.compose.ui.text.input.KeyboardType,
    onChange: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
                // 数值列固定宽度、右对齐，再在行尾留 52dp —— 不再贴着屏幕右边缘
                modifier = Modifier.width(150.dp).padding(end = 52.dp)
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
    }
}
/** 把 0..1 的点击位置吸到 0.05 步进。 */
private fun snap(range: ClosedFloatingPointRange<Float>, frac: Float): Float {
    val raw = range.start + (range.endInclusive - range.start) * frac.coerceIn(0f, 1f)
    return Math.round(raw * 20) / 20f
}
