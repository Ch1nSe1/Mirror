package memore.app.ui.screens

import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.vector.VectorStats
import memore.app.ui.theme.MemoreType
import kotlinx.coroutines.launch
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorMenu

/**
 * 向量系统的控制台（2026-11-05 重做）。
 *
 * 四段回答四个问题：
 *   ① 多少东西被理解了（覆盖率细条）
 *   ② 跑得怎么样（模型 / 已建 / 跳过 / 失败）
 *   ③ 分成了什么结构（主题 / 未归类 / 关联 —— 都是入口）
 *   ④ 最近发生过什么（AI 记录）+ 能做的动作（重建）
 */
data class VectorOverview(
    val total: Int = 0,
    val linkCount: Int = 0,
    /** 锚点行数（概念数展示用）。 */
    val anchorCount: Int = 0,
    val lastRunText: String? = null,
    val okRuns: Int = 0,
    val failedRuns: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VectorIndexScreen(
    loadStats: suspend () -> VectorStats?,
    loadOverview: suspend () -> VectorOverview,
    onRebuildIndex: suspend () -> Unit,
    onOpenLinks: () -> Unit,
    onOpenAiRuns: () -> Unit,
    onBack: () -> Unit
) {
    var stats by remember { mutableStateOf<VectorStats?>(null) }
    var overview by remember { mutableStateOf(VectorOverview()) }
    var busy by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        stats = runCatching { loadStats() }.getOrNull()
        overview = runCatching { loadOverview() }.getOrDefault(VectorOverview())
    }

    val rebuildState by memore.app.data.vector.VectorRebuildState.state.collectAsState()
    if (rebuildState.running) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { },
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            androidx.compose.material3.Surface(
                shape = memore.app.ui.components.MirrorShapes.modal,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = memore.app.ui.components.mirrorHairline()
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    memore.app.ui.components.MirrorLoading()
                    Spacer(Modifier.height(16.dp))
                    Text("重建中…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text("请勿随意退出", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("向量索引", style = MemoreType.pageTitleSans) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = busy.isBlank()) {
                            Icon(Icons.Outlined.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MirrorMenu(
                            expanded = menu,
                            onDismissRequest = { menu = false },
                            actions = listOf(
                                MirrorMenuAction(if (busy == "index") "重建中…" else "重建全部索引") {
                                    menu = false
                                    busy = "index"
                                    scope.launch {
                                        // try/finally（2026-11-13）：重建抛异常时也必须 finish，
                                        // 否则 VectorRebuildState.running 永远是 true ——
                                        // 弹窗会一直挡住整个页面，而且还会把聚类的进度抢走。
                                        try {
                                            onRebuildIndex()
                                        } catch (e: Exception) {
                                            memore.app.utils.LogUtil.e("VectorIndexScreen", "rebuild index failed", e)
                                        } finally {
                                            memore.app.data.vector.VectorRebuildState.finish()
                                            stats = runCatching { loadStats() }.getOrNull()
                                            overview = runCatching { loadOverview() }.getOrDefault(overview)
                                            busy = ""
                                        }
                                    }
                                }
                            )
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            val s = stats
            val total = overview.total.coerceAtLeast(s?.totalRecords ?: 0)
            if (s == null) {
                memore.app.ui.components.MirrorLoading()
                return@Scaffold
            }
            val indexed = s?.indexed ?: 0

            // ① 覆盖率
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp)) {
                Text(
                    "$total 条记录 · $indexed 条已理解" +
                        if (total > indexed) " · ${total - indexed} 条没理解" else "",
                    fontSize = 10.5.sp,
                    letterSpacing = 0.4.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().height(3.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                ) {
                    val ratio = if (total > 0) (indexed.toFloat() / total).coerceIn(0f, 1f) else 0f
                    Box(
                        Modifier.fillMaxWidth(ratio).height(3.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    )
                }
                Spacer(Modifier.height(14.dp))
            }

            // ② 索引
            SectionLabel("索引")
            DetailRow("模型", s?.model?.ifBlank { "未加载" } ?: "读取中…")
            DetailRow("已建向量", s?.let { "${it.indexed} 条" } ?: "—")
            DetailRow("加密跳过", s?.let { "${it.locked} 条" } ?: "—")
            DetailRow("内容过短", s?.let { "${it.short} 条" } ?: "—")
            DetailRow("失败", s?.let { "${it.failed} 条" } ?: "—")
            if (s != null && s.pending > 0) DetailRow("待建", "${s.pending} 条")

            // ③ 结构（都是入口）
            SectionLabel("结构")
            if (memore.app.utils.FeatureConsole.visible(memore.app.utils.FeatureConsole.LINKS)) {
                NavRow("关联", "${overview.linkCount} 条", onOpenLinks)
            }
            DetailRow("概念", "${overview.anchorCount} 个")
            // ④ 最近
            SectionLabel("最近")
            NavRow(
                "AI 记录",
                buildString {
                    if (!overview.lastRunText.isNullOrBlank()) append(overview.lastRunText)
                    else append("还没有调用")
                    if (overview.okRuns + overview.failedRuns > 0) {
                        append(" · ")
                        append(overview.okRuns).append("/").append(overview.failedRuns)
                    }
                },
                onOpenAiRuns
            )

        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MemoreType.micro,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp)
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Hairline()
}

@Composable
private fun NavRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text("›", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Hairline()
}

@Composable
private fun Hairline() {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    )
}

@Composable
private fun ActionText(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 13.sp,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp)
    )
}
