package memore.app.ui.components

import android.widget.Toast
import memore.app.ui.components.MirrorDropdownMenu
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorMenu
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import memore.app.utils.memoreToast

data class CopyOption(val label: String, val payload: String)

/**
 * 对话录严格只分两段：第一段是“我”的输入，第二段是 AI 回复。
 * 优先按第一个空行切，没有空行就按第一个换行切，内部再多换行也不拆出第三段。
 */
fun dialogueSegments(content: String, aiReply: String = ""): List<String> {
    val trimmed = content.trim()
    if (trimmed.isBlank()) return listOf(trimmed)
    if (aiReply.isNotBlank()) {
        val reply = aiReply.trim()
        val userPart = trimmed.removeSuffix(reply).trim()
        return listOf(userPart, reply).filter { it.isNotBlank() }.ifEmpty { listOf(trimmed) }
    }
    val paragraphs = trimmed.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }
    if (paragraphs.size < 2) return listOf(trimmed)
    val aiStart = (1 until paragraphs.size).firstOrNull { looksLikeAiOpening(paragraphs[it]) } ?: 1
    val user = paragraphs.take(aiStart).joinToString("\n\n")
    val reply = paragraphs.drop(aiStart).joinToString("\n\n")
    return listOf(user, reply).filter { it.isNotBlank() }.ifEmpty { listOf(trimmed) }
}

private val aiOpeningRegexes = listOf(
    Regex("^你"), Regex("^从你"), Regex("^是的"), Regex("^好的"), Regex("^好，"), Regex("^好。"),
    Regex("^我(明白|理解|听到|直接|确认|注意到|觉得你|来)"),
    Regex("^这(段|是|为什么|种|不是|正是|一份|一阶段|个板块)"),
    Regex("^事件摘要"), Regex("^系统响应"), Regex("^AI分析"), Regex("^底层结构"), Regex("^总结"),
    Regex("^修正"), Regex("^纠错"), Regex("^关于"), Regex("^根据"), Regex("^以下是"),
    Regex("^[一二三四五六七八九十]+、"), Regex("^\\d+[\\.、．]"), Regex("^·"), Regex("^“"), Regex("^---"),
    Regex("^项目原点"), Regex("^第[一二三四五六七八九十]+[部分板块]"), Regex("^初期状态"),
    Regex("^核心命题"), Regex("^原生缺陷"), Regex("^假设"), Regex("^结论"), Regex("^条目"),
    Regex("^触发条件"), Regex("^内部补偿"), Regex("^近期"), Regex("^底色推演"), Regex("^重度归档"),
    Regex("^[0-9]{1,2}月")
)

private fun looksLikeAiOpening(paragraph: String): Boolean =
    aiOpeningRegexes.any { it.containsMatchIn(paragraph) }

/**
 * 对话录落库时的正文拼法：**两段之间空一行**；哪一段是空的就不留空段。
 *
 * 为什么单独抽出来：**转录**进来的对话录可能只填了第二个框（AI 那栏），
 * 那时正文就该只存 AI 那一段，而不是留下一个空洞（"\n\nAI"），
 * 否则 [dialogueSegments] 读回来会变成两段、界面上那句话会出现两遍。
 */
fun dialogueRecordContent(user: String, ai: String): String =
    listOf(user.trim(), ai.trim()).filter { it.isNotBlank() }.joinToString("\n\n")

/**
 * 长按弹出自定义复制菜单：点空白自动关闭，复制动作直接进剪贴板。
 * 不再进入系统选择模式，因此不存在“退不出选择”的问题。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CopyableText(
    text: String,
    style: TextStyle = LocalTextStyle.current,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    copyOptions: List<CopyOption>? = null,
    /** 置 false = 这段文字**不吃点击**（长按复制也关掉）：让点按穿到卡片上（批量选择用）。 */
    interactive: Boolean = true,
    onClick: (() -> Unit)? = null,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null
) = CopyableText(
    text = AnnotatedString(text),
    style = style,
    modifier = modifier,
    color = color,
    maxLines = maxLines,
    overflow = overflow,
    copyOptions = copyOptions,
    interactive = interactive,
    onClick = onClick,
    onTextLayout = onTextLayout
)

/** 支持带内联样式文本的长按复制。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CopyableText(
    text: AnnotatedString,
    style: TextStyle = LocalTextStyle.current,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    copyOptions: List<CopyOption>? = null,
    interactive: Boolean = true,
    onClick: (() -> Unit)? = null,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val options = copyOptions ?: listOf(CopyOption("复制全文", text.text))
    Box(modifier) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = onTextLayout ?: { },
            modifier = if (!interactive) Modifier else Modifier.combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = { menuOpen = true }
            )
        )
        MirrorMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            actions = buildList {
                options.forEach { option ->
                    add(MirrorMenuAction(option.label) {
                        menuOpen = false
                        clipboard.setText(AnnotatedString(option.payload))
                        context.memoreToast("已复制", Toast.LENGTH_SHORT)
                    })
                }
                add(MirrorMenuAction("取消") { menuOpen = false })
            }
        )
    }
}
