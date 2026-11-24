package memore.app.data.repository

import android.content.Context
import com.google.gson.Gson
import memore.app.data.model.RedactionGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 脱敏词表：以"组"形式存储，同一组的多个形态一起管理，命中时替换为随机乱码，不保留关联。
 * 无默认值、全部手动添加。同时管理全局开关（脱敏 / 拒导入）。
 */
class RedactionRepository(private val context: Context) {

    private val gson = Gson()
    private val file = File(context.filesDir, "redaction_mappings.json")
    private val prefs = context.getSharedPreferences("mirror_prefs", Context.MODE_PRIVATE)

    suspend fun loadGroups(): List<RedactionGroup> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            val text = file.readText()
            val groups = if (text.isBlank()) emptyList()
            else gson.fromJson(text, Array<RedactionGroup>::class.java).toList()
            // 早期版本会把新组的 id 写成空串，导致列表 key 冲突、编辑/删除错对象。
            // 读到空 id 时统一补 UUID 并回写，保证旧数据也能正常保存。
            if (groups.any { it.id.isBlank() }) {
                val migrated = groups.map { g ->
                    if (g.id.isBlank()) g.copy(id = UUID.randomUUID().toString()) else g
                }
                writeGroups(migrated)
                return@withContext migrated
            }
            groups
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveGroups(groups: List<RedactionGroup>) = withContext(Dispatchers.IO) {
        writeGroups(groups)
    }

    /** 新增或覆盖同 id 的组；空 id 自动补 UUID，避免旧代码写坏的数据继续污染。 */
    suspend fun addGroup(group: RedactionGroup) {
        val safe = if (group.id.isBlank()) group.copy(id = UUID.randomUUID().toString()) else group
        val groups = loadGroups().toMutableList()
        val idx = groups.indexOfFirst { it.id == safe.id }
        if (idx >= 0) groups[idx] = safe else groups.add(safe)
        saveGroups(groups)
    }

    /** 更新组；找不到同 id 时按新增处理，不再静默失败。 */
    suspend fun updateGroup(group: RedactionGroup) {
        val safe = if (group.id.isBlank()) group.copy(id = UUID.randomUUID().toString()) else group
        val groups = loadGroups().toMutableList()
        val idx = groups.indexOfFirst { it.id == safe.id }
        if (idx >= 0) {
            groups[idx] = safe.copy(updatedAt = System.currentTimeMillis())
        } else {
            groups.add(safe)
        }
        saveGroups(groups)
    }

    suspend fun deleteGroup(id: String) {
        val groups = loadGroups().toMutableList()
        groups.removeAll { it.id == id }
        saveGroups(groups)
    }

    fun redactionEnabled(): Boolean = prefs.getBoolean("redaction_enabled", false)
    fun setRedactionEnabled(enabled: Boolean) = prefs.edit().putBoolean("redaction_enabled", enabled).apply()

    fun importBlockEnabled(): Boolean = prefs.getBoolean("import_block_enabled", false)
    fun setImportBlockEnabled(enabled: Boolean) = prefs.edit().putBoolean("import_block_enabled", enabled).apply()

    /** 乱码方式：random=每次独立随机，hash=同组统一哈希。 */
    fun redactionMode(): String = prefs.getString("redaction_mode", "random") ?: "random"
    fun setRedactionMode(mode: String) = prefs.edit().putString("redaction_mode", mode).apply()

    /** 先写临时文件再替换，避免写入中断把整个词表打成空文件。 */
    private fun writeGroups(groups: List<RedactionGroup>) {
        val tmp = File(file.parentFile, "redaction_mappings.json.tmp")
        tmp.writeText(gson.toJson(groups))
        if (!tmp.renameTo(file)) {
            file.writeText(gson.toJson(groups))
            tmp.delete()
        }
    }
}
