package memore.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.utils.FeatureConsole

/**
 * 功能控制台（2026-10-23 重做，方案 C3）。
 *
 * 原来每项一张卡片 + 青色开关 + 顶部一条解释小字。现在：
 *  - 按用途分组，组内平铺，不折叠（父子关系用缩进表达，子项始终可见）
 *  - 开关用主题主色（青色是协议专用色）
 *  - 顶部只留一行数据（多少项、多少项关闭），不再写解释句
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureConsoleScreen(onBack: () -> Unit) {
    val flags = remember {
        mutableStateMapOf<String, Boolean>().apply {
            FeatureConsole.FEATURES.forEach { this[it.key] = FeatureConsole.rawShown(it.key) }
        }
    }
    val offCount = FeatureConsole.FEATURES.count { flags[it.key] == false }
    val totalCount = FeatureConsole.FEATURES.size

    // 分组：显示哪些「根」功能，子项跟随父级缩进展示
    val groups = remember {
        listOf(
            "记录" to listOf(
                    FeatureConsole.GEN_SUMMARY,
                FeatureConsole.ALBUM,
                FeatureConsole.ZONES,
                FeatureConsole.SORT_TIMELINE,
                FeatureConsole.TAG_PROTOCOL_MANAGE,
                FeatureConsole.FILTER,
                FeatureConsole.ID_DISPLAY,
                FeatureConsole.BATCH,
                FeatureConsole.ADDITIONS
            ),
            "AI" to listOf(
                FeatureConsole.AI_CONFIG,
                FeatureConsole.EMOTION,
                FeatureConsole.PROMPT_MANAGE,
                FeatureConsole.VECTOR,
                FeatureConsole.AGENT_LINK,
                FeatureConsole.INSIGHT_ARCHIVE
            ),
            "语音与安全" to listOf(
                FeatureConsole.VOICE,
                FeatureConsole.ENCRYPTION,
                FeatureConsole.APP_LOCK,
                FeatureConsole.ALIAS_MANAGE
            ),
            "界面" to listOf(
                FeatureConsole.TUTORIAL
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("功能控制台") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
        ) {
            item {
                Text(
                    "$totalCount 项 · $offCount 项关闭",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
            }
            groups.forEach { (groupName, rootKeys) ->
                item(key = "g_$groupName") {
                    Text(
                        groupName,
                        fontSize = 10.5.sp,
                        letterSpacing = 1.1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp)
                    )
                }
                rootKeys.forEach { rootKey ->
                    val root = FeatureConsole.FEATURES.firstOrNull { it.key == rootKey } ?: return@forEach
                    item(key = rootKey) {
                        FeatureLine(root, isChild = false, flags)
                    }
                    FeatureConsole.childrenOf[rootKey]?.forEach { child ->
                        item(key = child.key) {
                            FeatureLine(child, isChild = true, flags)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun FeatureLine(
    row: FeatureConsole.Feature,
    isChild: Boolean,
    flags: MutableMap<String, Boolean>
) {
    val parentOn = row.parentKey?.let { flags[it] ?: true } ?: true
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (isChild && !parentOn) 0.4f else 1f)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(if (isChild) 44.dp else 50.dp)
                .padding(start = if (isChild) 18.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                row.label,
                fontSize = if (isChild) 13.5.sp else 14.5.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            Switch(
                checked = flags[row.key] ?: true,
                onCheckedChange = { v -> flags[row.key] = v; FeatureConsole.setFlag(row.key, v) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                )
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = if (isChild) 18.dp else 0.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
        )
    }
}
