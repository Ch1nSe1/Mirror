package memore.app.utils

import android.content.Context
import android.net.Uri
import memore.app.utils.GsonInstance
import java.io.File
import java.util.UUID

/**
 * 提示音配置存储：用户对每个「音效位置」绑定的音频做覆盖（换/删/加/导入）。
 * 单一事实源：位置 → 绑定的音频列表（音源为 assets/sounds/ 的 wav 路径，或导入复制的绝对文件路径）。
 * 存 SharedPreferences（JSON），无 Room 迁移成本；改动即写、即生效。
 */
class SoundConfigStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("sound_config", Context.MODE_PRIVATE)
    private val key = "overrides"

    /** 读用户覆盖：位置名 -> 绑定音频列表。 */
    fun loadOverrides(): Map<String, List<String>> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            (GsonInstance.gson.fromJson(raw, Map::class.java) as? Map<String, List<String>>) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** 保存某位置覆盖的音频列表。
     *  空列表 = 显式静音（**保留 key**，不落回默认）；非空 = 自定义。删除 key（恢复默认）用 [reset]。
     *  三态语义：key 不存在=默认音；key 存在且空=静音；key 存在且非空=自定义。 */
    fun saveOverride(eventName: String, assets: List<String>) {
        val overrides = loadOverrides().toMutableMap()
        overrides[eventName] = assets   // 空也保留 key：空覆盖表达"静音"，不是"没覆盖"
        prefs.edit().putString(key, GsonInstance.gson.toJson(overrides)).apply()
    }

    /** 整体备份还原：一次性替换全部覆盖（保留"存在但为空=静音"的三态语义）。 */
    fun saveAllOverrides(overrides: Map<String, List<String>>) {
        prefs.edit().putString(key, GsonInstance.gson.toJson(overrides)).apply()
    }

    /** 查询某位置实际生效音频（用户覆盖优先，否则默认）；只返回实际存在、能播的音源。
     *  覆盖存在且为空 = 显式静音（返回空，不再落回默认）。 */
    fun resolveAssets(event: SoundEvent): List<String> {
        val overrides = loadOverrides()
        val list = if (overrides.containsKey(event.name)) (overrides[event.name] ?: emptyList()) else event.defaultAssets
        return list.filter { assetExists(it) }
    }

    /** 判断某音源是否实际存在：assets/sounds 下的内置音，或导入复制的绝对文件路径。 */
    private fun assetExists(path: String): Boolean {
        return try {
            if (path.startsWith("/") || path.startsWith("file://")) {
                File(if (path.startsWith("file://")) android.net.Uri.parse(path).path else path).exists()
            } else {
                appContext.assets.openFd(path).use { true }
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 某位置是否被用户覆盖过。 */
    fun isOverridden(eventName: String): Boolean = loadOverrides().containsKey(eventName)

    /** 某位置是否被显式清空（静音）：覆盖里存在空列表。 */
    fun isCleared(eventName: String): Boolean =
        loadOverrides()[eventName]?.isEmpty() == true

    /** 删除 sound_custom/ 下未被任何位置覆盖引用的孤立导入音源（重置/移除后残留的孤儿文件）。
     *  内置 assets/sounds（不在该目录）及仍被某位置绑定的导入音源不受影响。 */
    fun cleanupImportOrphans() {
        val referenced = loadOverrides().values.flatten().toHashSet()
        val dir = File(appContext.filesDir, "sound_custom")
        dir.listFiles()?.forEach { f ->
            val abs = f.absolutePath
            if (abs !in referenced) {
                try { f.delete() } catch (_: Exception) {}
            }
        }
    }

    /** 列出该位置"实际存在"的内置默认音源（候选清单用，只出不存在的跳过）。 */
    fun availableDefaultAssets(event: SoundEvent): List<String> =
        event.defaultAssets.filter { assetExists(it) }

    /** 用默认值重置该位置覆盖。 */
    fun reset(event: SoundEvent) {
        val overrides = loadOverrides().toMutableMap()
        overrides.remove(event.name)
        prefs.edit().putString(key, GsonInstance.gson.toJson(overrides)).apply()
    }

    /** 从 content Uri 导入音频到 app 私有目录（sound_custom/），返回绝对路径；失败返回 null。 */
    fun importAudio(uri: Uri, displayName: String?): String? {
        return try {
            val dir = File(appContext.filesDir, "sound_custom").apply { mkdirs() }
            val ext = (displayName?.substringAfterLast('.', "wav") ?: "wav").ifBlank { "wav" }
            val dest = File(dir, "${UUID.randomUUID().toString().take(12)}.$ext")
            val input = appContext.contentResolver.openInputStream(uri) ?: return null
            input.use { ins -> dest.outputStream().use { out -> ins.copyTo(out) } }
            dest.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /** 列出该位置所有自定义导入音源（仅供 UI 展示用；实际绑定以 resolveAssets 为准）。 */
    fun listCustomAssets(): List<String> {
        val dir = File(appContext.filesDir, "sound_custom")
        return dir.listFiles()?.map { it.absolutePath }?.sorted() ?: emptyList()
    }
}
