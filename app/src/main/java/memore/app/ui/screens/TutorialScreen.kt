package memore.app.ui.screens

import androidx.compose.material.icons.outlined.Bolt
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.ui.components.IconBack
import memore.app.ui.components.IconTutorial
import memore.app.ui.components.MirrorShapes
import memore.app.ui.components.mirrorHairline
import memore.app.utils.UiSoundPlayer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal sealed interface TutorialBlock {
    data class Heading(val level: Int, val text: String) : TutorialBlock
    data class Paragraph(val text: String) : TutorialBlock
    data class Bullet(val ordered: Boolean, val number: Int, val indent: Int, val text: String) : TutorialBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : TutorialBlock
    data class Quote(val text: String) : TutorialBlock
}

internal fun parseTutorialMarkdown(markdown: String): List<TutorialBlock> {
    val lines = markdown.lines()
    val blocks = mutableListOf<TutorialBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        when {
            line.isBlank() || line.startsWith("# ") || line.matches(Regex("^-{3,}$")) -> i++
            line.startsWith("## ") || line.startsWith("### ") || line.startsWith("#### ") -> {
                val level = line.takeWhile { it == '#' }.length
                blocks += TutorialBlock.Heading(level, line.drop(level).trim())
                i++
            }
            line.startsWith("|") -> {
                val tableLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    tableLines += lines[i].trim()
                    i++
                }
                val parsed = tableLines.map { row ->
                    row.trim('|').split('|').map { it.trim() }
                }.filter { cells -> cells.any { it.isNotBlank() } }
                if (parsed.isNotEmpty()) {
                    val separator = Regex("^:?-+:?$")
                    val header = parsed.first()
                    val body = parsed.drop(1).filter { cells -> !cells.all { it.matches(separator) } }
                    blocks += TutorialBlock.Table(header, body)
                }
            }
            line.trimStart().startsWith("> ") -> {
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                blocks += TutorialBlock.Quote(quote.toString())
            }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                val indent = (line.indexOfFirst { it != ' ' && it != '\t' }).coerceAtLeast(0) / 2
                val content = line.trim().removePrefix("- ").removePrefix("* ")
                blocks += TutorialBlock.Bullet(false, 0, indent, content)
                i++
            }
            line.matches(Regex("^\\s*\\d+\\.\\s+.*")) -> {
                val indent = (line.indexOfFirst { it != ' ' && it != '\t' }).coerceAtLeast(0) / 2
                val number = line.trim().takeWhile { it.isDigit() }.toInt()
                val content = line.trim().replace(Regex("^\\d+\\.\\s+"), "")
                blocks += TutorialBlock.Bullet(true, number, indent, content)
                i++
            }
            else -> {
                val sb = StringBuilder(line.trim())
                i++
                while (i < lines.size) {
                    val next = lines[i].trimEnd()
                    val blockStart = next.isBlank() || next.startsWith("#") || next.startsWith("|") ||
                        next.trimStart().startsWith("> ") || next.trimStart().startsWith("- ") ||
                        next.trimStart().startsWith("* ") || next.matches(Regex("^\\s*\\d+\\.\\s+.*"))
                    if (blockStart) break
                    sb.append('\n').append(next.trim())
                    i++
                }
                blocks += TutorialBlock.Paragraph(sb.toString())
            }
        }
    }
    return blocks
}

internal fun tutorialAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        val boldStart = text.indexOf("**", index)
        val codeStart = text.indexOf('`', index)
        if (boldStart == -1 && codeStart == -1) {
            append(text.substring(index))
            break
        }
        if (codeStart != -1 && (boldStart == -1 || codeStart < boldStart)) {
            append(text.substring(index, codeStart))
            val end = text.indexOf('`', codeStart + 1)
            if (end == -1) {
                append(text.substring(codeStart))
                break
            }
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                append(text.substring(codeStart + 1, end))
            }
            index = end + 1
        } else {
            append(text.substring(index, boldStart))
            val end = text.indexOf("**", boldStart + 2)
            if (end == -1) {
                append(text.substring(boldStart))
                break
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(text.substring(boldStart + 2, end))
            }
            index = end + 2
        }
    }
}

private data class TutorialSection(val title: String, val blocks: List<TutorialBlock>)

private fun splitSections(blocks: List<TutorialBlock>): List<TutorialSection> {
    val sections = mutableListOf<TutorialSection>()
    var title = "前言"
    var current = mutableListOf<TutorialBlock>()
    for (block in blocks) {
        if (block is TutorialBlock.Heading && block.level == 2) {
            if (current.isNotEmpty()) sections += TutorialSection(title, current.toList())
            title = block.text
            current = mutableListOf()
        } else {
            current += block
        }
    }
    if (current.isNotEmpty()) sections += TutorialSection(title, current.toList())
    return sections
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(onBack: () -> Unit, startSection: String? = null) {
    val context = LocalContext.current
    var markdown by remember { mutableStateOf<String?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        markdown = runCatching {
            context.assets.open("tutorial.md").bufferedReader().use { it.readText() }
        }.getOrNull()
        loadFailed = markdown == null
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("使用教程") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(IconBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when {
            loadFailed -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("教程内容加载失败", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            markdown == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                memore.app.ui.components.MirrorLoading()
            }
            else -> {
                val blocks = remember(markdown) { parseTutorialMarkdown(markdown!!) }
                val sections = remember(blocks) { splitSections(blocks) }
                val listState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                val prefs = remember { context.getSharedPreferences("tutorial_prefs", Context.MODE_PRIVATE) }
                val collapsed = remember { mutableStateMapOf<Int, Boolean>() }
                var progress by remember { mutableFloatStateOf(0f) }

                LaunchedEffect(listState, sections.size) {
                    snapshotFlow { listState.firstVisibleItemIndex }
                        .distinctUntilChanged()
                        .collect { index ->
                            progress = if (sections.isEmpty()) 0f else (index.toFloat() / sections.size).coerceIn(0f, 1f)
                        }
                }
                LaunchedEffect(Unit) {
                    if (sections.isNotEmpty()) {
                        // 带 startSection 进来时先找那一节（Agent 页右上角的教程键走这条）；
                        // 找不到再回到上次读到的位置。
                        val match = startSection?.let { kw ->
                            sections.indexOfFirst { it.title.contains(kw, ignoreCase = true) }
                        } ?: -1
                        val target = if (match >= 0) match
                        else prefs.getInt("tutorial_section", 0).coerceIn(0, sections.lastIndex)
                        val offset = if (match >= 0) 0 else prefs.getInt("tutorial_offset", 0)
                        try {
                            listState.scrollToItem(target, offset)
                        } catch (_: Exception) {
                        }
                    }
                }
                DisposableEffect(Unit) {
                    onDispose {
                        prefs.edit()
                            .putInt("tutorial_section", listState.firstVisibleItemIndex.coerceAtLeast(0))
                            .putInt("tutorial_offset", listState.firstVisibleItemScrollOffset)
                            .apply()
                    }
                }

                Column(Modifier.fillMaxSize().padding(padding)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                    if (sections.size > 1) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            sections.forEachIndexed { index, section ->
                                SuggestionChip(
                                    onClick = { scope.launch { listState.animateScrollToItem(index) } },
                                    label = { Text(section.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 36.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(sections) { index, section ->
                            TutorialSectionCard(
                                title = section.title,
                                blocks = section.blocks,
                                expanded = collapsed.containsKey(index).not(),
                                onToggle = {
                                    if (collapsed.containsKey(index)) collapsed.remove(index) else collapsed[index] = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorialSectionCard(
    title: String,
    blocks: List<TutorialBlock>,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    // 纸上的"章"：标题行 + 展开内容，章节之间一条 18% 发丝线（2026-10-24 去卡片）
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        if (expanded) {
            Column(Modifier.padding(bottom = 10.dp)) {
                blocks.forEach { block -> TutorialBlockView(block) }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
        )
    }
}

@Composable
private fun TutorialBlockView(block: TutorialBlock) {
    when (block) {
        is TutorialBlock.Heading -> Text(
            tutorialAnnotated(block.text),
            style = if (block.level == 3) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            color = if (block.level == 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
            fontWeight = if (block.level == 3) FontWeight.SemiBold else null,
            modifier = Modifier.padding(top = if (block.level == 3) 14.dp else 10.dp, bottom = 6.dp)
        )
        is TutorialBlock.Paragraph -> Text(
            tutorialAnnotated(block.text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 3.dp)
        )
        is TutorialBlock.Bullet -> Row(
            Modifier.fillMaxWidth().padding(start = (block.indent * 12).dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                if (block.ordered) "${block.number}." else "•",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 6.dp)
            )
            Text(
                tutorialAnnotated(block.text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
        is TutorialBlock.Table -> TutorialTable(block)
        is TutorialBlock.Quote -> Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).height(IntrinsicSize.Min),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier.width(2.dp).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                tutorialAnnotated(block.text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TutorialTable(table: TutorialBlock.Table) {
    if (table.header.size <= 2) {
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            table.rows.forEachIndexed { index, row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        // 2026-11-17：两列表头是"列名"，不是每行的标签。
                        // 原来这里取 header[0]，结果术语表每行都印出表头（「词」），
                        // 第一列的真实内容（时间线 / 回顾…）被吞掉了。
                        tutorialAnnotated(row.getOrElse(0) { "" }),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(96.dp).padding(end = 8.dp)
                    )
                    Text(
                        tutorialAnnotated(row.getOrElse(1) { "" }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (index < table.rows.lastIndex) {
                    Box(
                        Modifier.fillMaxWidth().height(1.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                    )
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            table.header.forEach { cell ->
                Text(
                    tutorialAnnotated(cell),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(end = 4.dp)
                )
            }
        }
        table.rows.forEachIndexed { index, row ->
            Row(
                Modifier.fillMaxWidth()
                    .background(if (index % 2 == 0) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                row.forEach { cell ->
                    Text(
                        tutorialAnnotated(cell),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TutorialOnboardingScreen(
    onFinish: () -> Unit,
    onSkip: () -> Unit,
    /** 2026-11-16：从引导直接进完整教程页（原来引导页没有任何通往教程的入口）。 */
    onOpenTutorial: (() -> Unit)? = null
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { UiSoundPlayer.playTutorialOpen(context) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val pages = listOf(
        OnboardingPage(
            title = "这是什么",
            icon = IconTutorial,
            items = listOf("纯本地的记忆工具", "没有账号，没有云同步", "一切都存在这台手机上")
        ),
        OnboardingPage(
            title = "三条主线",
            icon = Icons.Outlined.Edit,
            items = listOf("记录：写下发生的事", "秒针：把时间切成一段一段", "回顾与洞察：这周、这月发生了什么")
        ),
        OnboardingPage(
            title = "让它变聪明",
            icon = Icons.Outlined.Bolt,
            items = listOf("配置 AI 端点后，在记录上点 AI 分析", "搜索框右侧切到语义搜索", "在记录上生成洞察，或做一次近一月洞察")
        ),
        OnboardingPage(
            title = "记住三点",
            icon = Icons.Outlined.Warning,
            items = listOf("删除进回收站，原文不可改", "加密只遮蔽界面，查看要密码", "定期做一次整体备份")
        )
    )
    val current = pages[page]
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                shape = CircleShape,
                modifier = Modifier.size(88.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(current.icon, null, Modifier.size(46.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(current.title, style = memore.app.ui.theme.MemoreType.pageTitleSans, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(18.dp))
            current.items.forEach { item ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MirrorShapes.card,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    border = mirrorHairline()
                ) {
                    Text(
                        item,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                repeat(pages.size) { index ->
                    Box(
                        Modifier.size(if (index == page) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == page) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            // 2026-11-16：按法条 187 行重做——**44dp、平分、格间 1dp 竖线、只放文字**。
            // 原来是 SpaceBetween + 一颗填充胶囊：三个动作摊成三座孤岛、胶囊与文字基线对不齐。
            // 现在三个动作合成一条页脚规则，主次靠字色/字重，不靠色块。
            Row(
                Modifier.fillMaxWidth().height(44.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FooterCell("跳过", MaterialTheme.colorScheme.onSurfaceVariant, FontWeight.Normal, onSkip)
                FooterDivider()
                if (onOpenTutorial != null) {
                    FooterCell("完整教程", MaterialTheme.colorScheme.onSurfaceVariant, FontWeight.Normal, onOpenTutorial)
                    FooterDivider()
                }
                FooterCell(
                    if (page < pages.lastIndex) "下一步" else "开始使用",
                    MaterialTheme.colorScheme.primary,
                    FontWeight.SemiBold
                ) { if (page < pages.lastIndex) page++ else onFinish() }
            }
        }
    }
}

private data class OnboardingPage(val title: String, val icon: ImageVector, val items: List<String>)

/** 页脚行的一格（2026-11-16）：平分、居中、只有文字。字号走 MemoreType.button，不写裸 sp。 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.FooterCell(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    weight: FontWeight,
    onClick: () -> Unit
) {
    Box(
        Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = memore.app.ui.theme.MemoreType.button.copy(fontWeight = weight),
            color = color
        )
    }
}

/** 页脚行的 1dp 竖线（2026-11-16）：与法条 187 行的按钮行同一种分隔。 */
@Composable
private fun FooterDivider() {
    Box(
        Modifier.width(1.dp).height(14.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
    )
}
