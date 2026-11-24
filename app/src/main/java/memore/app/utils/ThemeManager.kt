package memore.app.utils

import android.content.Context
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

data class ThemePreset(
    val name: String,
    val author: String,
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val primary: Color,
    val surfaceContainerLow: Color = surface,
    val surfaceContainer: Color = surface,
    val surfaceContainerHigh: Color = surface,
    val surfaceContainerHighest: Color = surface,
    val outlineVariant: Color = border,
    val surfaceTint: Color = primary
)

object ThemeManager {
    /** 默认主题：新装 / 用户从没选过主题时生效。 */
    const val DEFAULT_THEME = "cream"

    private var prefs: android.content.SharedPreferences? = null

    var currentTheme by mutableStateOf(DEFAULT_THEME)

    val presets = listOf(
        ThemePreset(
            "midnight", "midnight", true, Color(0xFF1A0F2E), Color(0xFF21182B), Color(0xFF3A2D49),
            Color(0xFFEAE6EE), Color(0xFFA89CB3), Color(0xFFA78BFA),
            surfaceContainerLow = Color(0xFF251B31),
            surfaceContainer = Color(0xFF21182B),
            surfaceContainerHigh = Color(0xFF2C2139),
            surfaceContainerHighest = Color(0xFF352A44),
            outlineVariant = Color(0xFF45364F),
            surfaceTint = Color(0xFFA78BFA)
        ),
        ThemePreset(
            "paper", "paper", false, Color(0xFFF5F0E8), Color(0xFFFFFDF8), Color(0xFFD5CFC5),
            Color(0xFF1A1423), Color(0xFF6B6075), Color(0xFF8B5CF6),
            surfaceContainerLow = Color(0xFFFBF7F0),
            surfaceContainer = Color(0xFFFFFDF8),
            // 弹窗底 = 最亮的一档（原来比卡片还暗、离背景只差 7 个亮度单位 → 弹窗像贴上去的一块补丁）
            surfaceContainerHigh = Color(0xFFFFFDF8),
            surfaceContainerHighest = Color(0xFFEAE1D0),
            outlineVariant = Color(0xFFE0D7C7),
            surfaceTint = Color(0xFF8B5CF6)
        ),
        ThemePreset(
            "cream", "cream", false, Color(0xFFEDE5D5), Color(0xFFFFF9F0), Color(0xFFD5CBB8),
            Color(0xFF1A1423), Color(0xFF6B6075), Color(0xFF8B5CF6),
            surfaceContainerLow = Color(0xFFFBF5E9),
            surfaceContainer = Color(0xFFFFF9F0),
            // 弹窗底 = 最亮的一档（同上）
            surfaceContainerHigh = Color(0xFFFFF9F0),
            surfaceContainerHighest = Color(0xFFEBDFC8),
            outlineVariant = Color(0xFFE1D5BF),
            surfaceTint = Color(0xFF8B5CF6)
        ),
        ThemePreset(
            "night", "dusk", true, Color(0xFF141A24), Color(0xFF1E2633), Color(0xFF2D3847),
            Color(0xFFEAE6EE), Color(0xFF8A90A0), Color(0xFFA78BFA),
            surfaceContainerLow = Color(0xFF232C3B),
            surfaceContainer = Color(0xFF1E2633),
            surfaceContainerHigh = Color(0xFF2B3545),
            surfaceContainerHighest = Color(0xFF354153),
            outlineVariant = Color(0xFF3C4859),
            surfaceTint = Color(0xFFA78BFA)
        ),
        ThemePreset(
            "amber", "cocoa", true, Color(0xFF2A1E14), Color(0xFF3D2E22), Color(0xFF4E3D30),
            Color(0xFFEAE6EE), Color(0xFFA89888), Color(0xFFC4B5FD),
            surfaceContainerLow = Color(0xFF443325),
            surfaceContainer = Color(0xFF3D2E22),
            surfaceContainerHigh = Color(0xFF4D3B2C),
            surfaceContainerHighest = Color(0xFF5A4533),
            outlineVariant = Color(0xFF5C4A3B),
            surfaceTint = Color(0xFFC4B5FD)
        ),
        ThemePreset(
            "graphite", "graphite", true, Color(0xFF1E1E24), Color(0xFF2A2A32), Color(0xFF3E3E48),
            Color(0xFFEAE6EE), Color(0xFF909098), Color(0xFFA78BFA),
            surfaceContainerLow = Color(0xFF303038),
            surfaceContainer = Color(0xFF2A2A32),
            surfaceContainerHigh = Color(0xFF383840),
            surfaceContainerHighest = Color(0xFF42424C),
            outlineVariant = Color(0xFF4A4A55),
            surfaceTint = Color(0xFFA78BFA)
        )
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences("mirror_prefs", Context.MODE_PRIVATE)
        val saved = prefs?.getString("theme", null)
        if (saved != null && presets.any { it.name == saved }) {
            currentTheme = saved
        }
    }

    fun apply(theme: String) {
        currentTheme = theme
        prefs?.edit()?.putString("theme", theme)?.apply()
    }

    fun getCurrent(): ThemePreset {
        return presets.find { it.name == currentTheme }
            ?: presets.find { it.name == DEFAULT_THEME }
            ?: presets[0]
    }
}

fun themeColorScheme(theme: ThemePreset) = if (theme.isDark) {
    darkColorScheme(
        primary = theme.primary, onPrimary = theme.textPrimary,
        secondary = theme.primary, onSecondary = theme.textPrimary,
        tertiary = theme.textSecondary,
        background = theme.bg, onBackground = theme.textPrimary,
        surface = theme.surface, onSurface = theme.textPrimary,
        surfaceVariant = theme.surfaceContainerLow, onSurfaceVariant = theme.textSecondary,
        surfaceContainerLow = theme.surfaceContainerLow,
        surfaceContainer = theme.surfaceContainer,
        surfaceContainerHigh = theme.surfaceContainerHigh,
        surfaceContainerHighest = theme.surfaceContainerHighest,
        error = Color(0xFFD45E5E), onError = theme.textPrimary,
        outline = theme.border,
        outlineVariant = theme.outlineVariant,
        surfaceTint = theme.surfaceTint,
        inverseSurface = Color(0xFFEAE6EE), inverseOnSurface = Color(0xFF1A0F2E),
        scrim = Color.Black
    )
} else {
    lightColorScheme(
        primary = theme.primary, onPrimary = Color.White,
        secondary = theme.primary, onSecondary = Color.White,
        tertiary = theme.textSecondary,
        background = theme.bg, onBackground = theme.textPrimary,
        surface = theme.surface, onSurface = theme.textPrimary,
        surfaceVariant = theme.surfaceContainerLow, onSurfaceVariant = theme.textSecondary,
        surfaceContainerLow = theme.surfaceContainerLow,
        surfaceContainer = theme.surfaceContainer,
        surfaceContainerHigh = theme.surfaceContainerHigh,
        surfaceContainerHighest = theme.surfaceContainerHighest,
        error = Color(0xFFD45E5E), onError = Color.White,
        outline = theme.border,
        outlineVariant = theme.outlineVariant,
        surfaceTint = theme.surfaceTint,
        inverseSurface = Color(0xFF21182B), inverseOnSurface = Color(0xFFFFFDF8),
        scrim = Color.Black
    )
}
