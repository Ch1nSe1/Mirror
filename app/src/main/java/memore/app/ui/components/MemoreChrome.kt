package memore.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import memore.app.ui.theme.MemoreType

/**
 * 屏框（设计法 L0）：**全项目唯一允许出现 `TopAppBar` 的地方。**
 *
 * 为什么要有它：现在 45 个屏各自搭顶栏，于是同一个东西长出了三种字号（18/20/22）、
 * 三种返回图标（85 处 Material + 18 处自绘）、四种标题对齐。设计法 1.1–1.6 想管的
 * 就是这一层，而只要每屏还在自己写 TopAppBar，它就永远管不住。
 *
 * 高度用 M3 小顶栏的 **64dp**，不自己压成 56：56 是 M2 的数，压下去标题基线和点击热区
 * 都要重新对，收益不抵风险。设计法 1.1 已按 64 改正。
 *
 * 右端动作只有四档（1.4）：
 *  - **0 个**：什么都不放；
 *  - **1 个**：直接一个 IconButton，不进菜单；
 *  - **2–3 个**：全部平铺成 IconButton；
 *  - **≥4 个**：一个 `⋮`，点它**打开这一屏的工具页**（不是弹菜单）；
 *  - 有选中集合时（批量模式）：动作用底栏动作条，不占顶栏。
 *
 * 用法：
 * ```
 * MemorePage(title = "筛选", onBack = { back() }) { padding ->
 *     Column(Modifier.padding(padding)) { … }
 * }
 * // 根屏（4 个 tab）不传 onBack：
 * MemorePage(title = "设置") { … }
 * ```
 */

/**
 * 唯一的分节顶栏。
 *
 * @param title 标题：18sp SemiBold，无衬线（衬线只给阅读正文，见 6.1）
 * @param onBack 返回；**根屏传 null**（1.3）。不为 null 时固定一个 24dp `ArrowBack`
 * @param subtitle 可选副标题，**只能是数据**（条数 / 分组 / 区间），不许是解释句（2.4）
 * @param actions 右端动作，按 1.4 的四档放
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoreTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            if (subtitle == null) {
                Text(title, style = MemoreType.pageTitleSans, color = MaterialTheme.colorScheme.onBackground)
            } else {
                Column {
                    Text(title, style = MemoreType.pageTitleSans, color = MaterialTheme.colorScheme.onBackground)
                    Text(subtitle, style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
    )
}

/**
 * 整屏骨架：顶栏 + 内容 + 可选底栏，背景统一走 `colorScheme.background`。
 *
 * 刻意**不加** elevation、不加分割线：这一层靠色阶区分，不靠阴影（三个主题亮度的教训）。
 *
 * @param bottomBar 底栏；表单类页面放「次左主右」双按钮（5.3）
 * @param content 内容，`padding` 必须作用在最外层，否则会被顶栏压住
 */
@Composable
fun MemorePage(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = { MemoreTopBar(title = title, onBack = onBack, subtitle = subtitle, actions = actions) },
        bottomBar = bottomBar,
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        content(padding)
    }
}
