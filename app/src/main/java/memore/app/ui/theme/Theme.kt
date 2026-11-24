package memore.app.ui.theme

import memore.app.ui.components.MirrorShapes

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import memore.app.utils.ThemeManager
import memore.app.utils.themeColorScheme

/**
 * 全局圆角刻度。M3 组件（弹窗、菜单、卡片、按钮…）都按这套走，
 * 页面里手写的 RoundedCornerShape 逐步并到这几档。
 */
private val MirrorThemeShapes = Shapes(
    extraSmall = MirrorShapes.row,
    small = MirrorShapes.row,
    medium = MirrorShapes.card,
    large = MirrorShapes.field,
    extraLarge = MirrorShapes.modal
)

@Composable
fun MemoreTheme(content: @Composable () -> Unit) {
    val theme = ThemeManager.getCurrent()
    val scheme = themeColorScheme(theme)
    MaterialTheme(
        colorScheme = scheme,
        typography = MemoreTypography,
        // 圆角一套到底：行 10 / 卡 14 / 输入 18 / 浮层 22（原来 12/14/16/24/99 混着用）
        shapes = MirrorThemeShapes,
        content = content
    )
}
