package memore.app.utils

import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出的**交付方式**。
 *
 * 三种并列，在导出界面里选，**选完记住**（存 `mirror_prefs`），下次直接用，不用每次都切。
 *
 * - [SAVE_AS]   「另存为…」：`ACTION_CREATE_DOCUMENT`（SAF），**自己挑位置和文件名**。
 *               零权限，重名由系统弹"是否覆盖"。默认项，做法借自 ArmsDex 的
 *               `SettingsScreen.backupLauncher`。
 * - [DOWNLOADS] 直接落进公共 Download（MediaStore），不问你位置。
 * - [SHARE]     **保留原有行为**：存 Download + 弹分享界面 —— 发给朋友或 AI 最方便，不能删。
 *
 * 注意 [SAVE_AS] 不走引擎的落盘路径：UI 拿到 SAF 的 uri 后，把流直接交给
 * `ExportEngine.export(..., output = it)`，因此不落 cacheDir、不写 Download、不弹分享。
 */
object ExportDelivery {
    const val SAVE_AS = "save_as"
    const val DOWNLOADS = "downloads"
    const val SHARE = "share"
    const val PREF_KEY = "export_delivery"

    fun read(prefs: SharedPreferences): String = prefs.getString(PREF_KEY, SAVE_AS) ?: SAVE_AS

    fun write(prefs: SharedPreferences, value: String) {
        prefs.edit().putString(PREF_KEY, value).apply()
    }

    fun mimeOf(isZip: Boolean): String = if (isZip) "application/zip" else "application/json"

    /** Markdown（给 AI 读）的 mime；与 isZip 二选一，调用方按格式选。 */
    fun markdownMime(): String = "text/markdown"

    /** Markdown 的默认文件名（不含目录）。 */
    fun markdownName(stamp: String): String = "memory_$stamp.md"

    /** 给系统文件选择器的建议文件名（用户可以在选择器里改）。 */
    fun suggestedName(isZip: Boolean): String =
        "memory_" + stamp() + if (isZip) ".zip" else ".json"

    /** 时间戳片段（文件名用）。 */
    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
