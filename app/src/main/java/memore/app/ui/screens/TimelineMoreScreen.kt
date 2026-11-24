package memore.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.data.local.AppDatabase
import memore.app.data.repository.ProtocolRepository
import memore.app.data.repository.RecordRepository
import memore.app.data.repository.TagGroupRepository
import memore.app.data.repository.TagRepository
import memore.app.ui.components.MemorePage
import memore.app.ui.components.mirrorHairlineColor
import memore.app.utils.FeatureConsole
import kotlinx.coroutines.flow.first

/**
 * 时间线的「更多」页（2026-11-11 定稿）。
 *
 * 版式依据：M3 列表项规范里 **两行式 = 72dp**（headline + supportingText），
 * 只有一行字时才 56dp。之前这页"太扁"的根因就是每行只放了一行字，靠加行高撑不起来。
 * 所以每行都带一句真实状态（记录总数 / 筛选条件 / 聚类组数 / 标签数 / 协议数）。
 *
 * 分隔线照设置页：start = 41dp（让开图标，和文字左边缘对齐）、end = 8dp、mirrorHairlineColor。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineMoreScreen(
    onBack: () -> Unit,
    onBatchSelect: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenFilter: () -> Unit,
    /** 2026-11-17：主题（每月聚类命名）从「隐藏功能」收回时间线入口，顶替旧聚类的位置。 */
    onOpenOrigin: () -> Unit = {},
    onOpenTagManage: () -> Unit,
    onOpenProtocolManage: () -> Unit,
    /** 2026-11-15：图文集与锚点从时间线顶栏移到这里——顶栏要腾位置给排序（法条 1.4）。 */
    onOpenAlbum: () -> Unit = {},
    onOpenAnchor: () -> Unit = {},
    db: AppDatabase? = null,
    recordRepository: RecordRepository? = null,
    tagRepository: TagRepository? = null,
    tagGroupRepository: TagGroupRepository? = null,
    protocolRepository: ProtocolRepository? = null,
    filterSummary: String? = null
) {
    var recordCount by remember { mutableIntStateOf(0) }
    var tagCount by remember { mutableIntStateOf(0) }
    var groupCount by remember { mutableIntStateOf(0) }
    var protocolCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        runCatching { recordRepository?.getAllActiveRecords()?.size ?: 0 }.onSuccess { recordCount = it }
        runCatching { tagRepository?.getAllTags()?.first()?.size ?: 0 }.onSuccess { tagCount = it }
        runCatching { tagGroupRepository?.getGroups()?.size ?: 0 }.onSuccess { groupCount = it }
        runCatching { protocolRepository?.getAllProtocols()?.first()?.size ?: 0 }.onSuccess { protocolCount = it }
    }

    MemorePage(title = "时间线", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            RowRule()
            if (FeatureConsole.visible(FeatureConsole.BATCH)) {
                TwoLineRow(Icons.Outlined.Checklist, "批量选择", "共 $recordCount 条 · 可多选导出") { onBatchSelect() }
                RowRule()
            }
            TwoLineRow(Icons.Outlined.DeleteOutline, "回收站", "已删除的记录可恢复") { onOpenTrash() }
            RowRule()
            if (FeatureConsole.visible(FeatureConsole.FILTER)) {
                TwoLineRow(
                    Icons.Outlined.FilterList, "筛选",
                    filterSummary ?: "未设置条件"
                ) { onOpenFilter() }
                RowRule()
            }
            if (FeatureConsole.visible(FeatureConsole.SOURCE_MANAGE)) {
                TwoLineRow(Icons.Outlined.FormatQuote, "来源", "各来源的叙事与统计分量") { onOpenOrigin() }
                RowRule()
            }
            if (FeatureConsole.visible(FeatureConsole.MANAGE_TAG_GROUPS)) {
                TwoLineRow(Icons.Outlined.Sell, "管理标签组", "$groupCount 组 · $tagCount 个标签") { onOpenTagManage() }
                RowRule()
            }
            if (FeatureConsole.visible(FeatureConsole.MANAGE_PROTOCOLS)) {
                TwoLineRow(Icons.Outlined.Article, "管理协议", "$protocolCount 个") { onOpenProtocolManage() }
                RowRule()
            }
            // 2026-11-15：图文集与区域**从这里删掉**。
            // 它们的入口在顶栏「视图」那个两格浮层里（2026-11-15 定的）；
            // 这一页再放一遍，就是两个入口讲同一件事（铁律 3）。
        }
    }
}

/** 与设置页同一条线：让开图标从文字左边缘开始，右侧只留 8dp。 */
@Composable
private fun RowRule() {
    HorizontalDivider(
        // 41 = 行内 8 + 图标 18 + 缝 15：线要对齐**文字**左缘，不是图标左缘。
        // 原来写的 33 把行的 8dp 内边距也减掉了，实测线比文字左了 10dp。
        modifier = Modifier.padding(start = 41.dp, end = 8.dp),
        color = mirrorHairlineColor()
    )
}

@Composable
private fun TwoLineRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 8.dp).height(72.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                supporting,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }
        Icon(
            Icons.Outlined.KeyboardArrowRight,
            null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}
