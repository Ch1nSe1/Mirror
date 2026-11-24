package memore.app.ui.screens

import androidx.compose.foundation.BorderStroke
import memore.app.ui.components.MemoreLineInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import memore.app.data.model.DraftAskItem
import memore.app.data.model.DraftKind
import memore.app.data.model.formatAskBlock
import memore.app.data.model.formatSuggestBlock
import memore.app.data.repository.DraftRepository
import memore.app.ui.components.MirrorShapes
import memore.app.utils.UiSoundPlayer
import kotlinx.coroutines.launch

/**
 * 质疑 / 讨论的作答页（**独立页面，不是弹窗**）。
 *
 * 为什么独立：弹窗里做题时键盘一弹就把题挡住、表单还得在弹层里滚 —— 很难用。
 *
 * 版式约定（用户要求"简洁美观"）：
 * - 每题：序号 + 题干，下面**整行等宽**的选项（不再用宽度不一、间距猎奇的 chip ✗）；
 * - 选项之间统一 8dp、题目之间统一 28dp，整页一个节奏；
 * - 「自己写」默认收起，点了才展开输入框（默认视图保持干净）；
 * - 「跳过」放题干右下角；底部固定通栏「提交」（键盘弹起也不会被挡住）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftAskScreen(
    draftId: Long,
    kind: String,
    items: List<DraftAskItem>,
    draftRepository: DraftRepository,
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // 每题的答案；留空 = 该题跳过
    val answers = remember { mutableStateMapOf<Int, String>() }
    // 哪几题展开了"自己写"
    val customOpen = remember { mutableStateMapOf<Int, Boolean>() }
    var submitting by remember { mutableStateOf(false) }
    val isProbe = kind == DraftKind.QA

    fun submit() {
        if (submitting) return
        submitting = true
        val text = items.mapIndexed { i, item ->
            val answer = answers[i]
            if (isProbe) formatAskBlock(item, answer) else formatSuggestBlock(item, answer)
        }.joinToString("\n\n")
        scope.launch {
            draftRepository.add(draftId, text, kind = kind)
            UiSoundPlayer.playSave(context)
            submitting = false
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isProbe) "质疑" else "讨论") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = { submit() },
                    enabled = !submitting,
                    shape = MirrorShapes.card,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(48.dp)
                ) { Text("提交") }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            items.forEachIndexed { i, item ->
                if (i > 0) Spacer(Modifier.height(28.dp))
                // 题干：序号 + 问题（序号用主题色，视觉上起到分节作用）
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(26.dp).padding(top = 1.dp)
                    )
                    Text(
                        item.text,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                // 选项：整行等宽、统一间距
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.options.forEach { opt ->
                        val selected = answers[i] == opt
                        OptionRow(
                            text = opt,
                            selected = selected,
                            onClick = {
                                customOpen[i] = false
                                answers[i] = if (selected) "" else opt
                            }
                        )
                    }
                    val custom = customOpen[i] == true
                    OptionRow(
                        text = "自己写",
                        selected = custom,
                        onClick = {
                            customOpen[i] = true
                            if (answers[i] in item.options) answers[i] = ""
                        }
                    )
                    if (custom) {
                        MemoreLineInput(
                            value = answers[i] ?: "",
                            onValueChange = { answers[i] = it },
                            placeholder = { Text("写在这里") },
                            shape = MirrorShapes.menu,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp, max = 160.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        answers[i] = ""
                        customOpen[i] = false
                    }) { Text("跳过", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 整行等宽的选项：淡底 + 圆形指示 + 文字；选中时用主题色。 */
@Composable
private fun OptionRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        shape = MirrorShapes.card,
        color = if (selected) primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (selected) primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
