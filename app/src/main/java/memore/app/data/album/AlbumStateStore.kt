package memore.app.data.album

import memore.app.utils.GsonInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class AlbumFootnote(
    val content: String,
    val createdAt: Long,
    val updatedAt: Long? = null
)

data class AlbumState(
    val selected: Map<String, Int> = emptyMap(),
    val footnotes: Map<String, List<AlbumFootnote>> = emptyMap(),
    val pinned: Set<String> = emptySet()
)

private data class StateFile(
    val selected: Map<String, Int> = emptyMap(),
    val footnotes: Map<String, List<AlbumFootnote>> = emptyMap(),
    val pinned: Set<String> = emptySet()
)

/** 图文集专属本地状态：主图选择记忆 + 注脚（不进入时间线/详情/导出）。 */
class AlbumStateStore(
    private val file: File
) {

    private val mutex = Mutex()

    suspend fun load(): AlbumState = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext AlbumState()
        try {
            val text = file.readText()
            if (text.isBlank()) return@withContext AlbumState()
            // 2026-11-23：手写解析，不再依赖 Gson 的泛型签名。
            // R8 / 备份导入都可能让 Map<String, List<AlbumFootnote>> 退化成 LinkedTreeMap，
            // 导出时 cast 崩溃（ClassCastException: LinkedTreeMap -> AlbumFootnote）。
            val root = com.google.gson.JsonParser.parseString(text).asJsonObject
            val selected = root.getAsJsonObject("selected")?.entrySet()?.mapNotNull { (id, v) ->
                runCatching { id to v.asInt }.getOrNull()
            }?.toMap().orEmpty()
            val footnotes = root.getAsJsonObject("footnotes")?.entrySet()?.mapNotNull { (recordId, arr) ->
                val entries = runCatching {
                    arr.asJsonArray.mapNotNull { el ->
                        val o = el.asJsonObject
                        AlbumFootnote(
                            content = o.get("content")?.asString ?: "",
                            createdAt = o.get("createdAt")?.asLong ?: 0L,
                            updatedAt = o.get("updatedAt")?.takeIf { !it.isJsonNull }?.asLong
                        )
                    }
                }.getOrDefault(emptyList())
                if (entries.isEmpty()) null else recordId to entries
            }?.toMap().orEmpty()
            val pinned = runCatching {
                root.getAsJsonArray("pinned")?.mapNotNull { runCatching { it.asString }.getOrNull() }?.toSet()
            }.getOrNull().orEmpty()
            AlbumState(selected = selected, footnotes = footnotes, pinned = pinned)
        } catch (_: Exception) {
            // 损坏文件改名保留现场，避免后续操作直接覆盖原始数据。
            runCatching {
                val backup = File(file.parentFile, "album_state.json.bak-${System.currentTimeMillis()}")
                file.renameTo(backup)
            }
            AlbumState()
        }
    }

    suspend fun setSelected(recordId: String, index: Int): AlbumState = mutex.withLock {
        val state = load()
        val next = state.copy(selected = state.selected + (recordId to index))
        write(next)
        next
    }

    /** 切换"重点"标记（图文集时间刻迹线一键直达用）。 */
    suspend fun togglePinned(recordId: String): AlbumState = mutex.withLock {
        val state = load()
        val next = state.copy(pinned = if (recordId in state.pinned) state.pinned - recordId else state.pinned + recordId)
        write(next)
        next
    }

    suspend fun addFootnote(recordId: String, content: String): AlbumState = mutex.withLock {
        val state = load()
        val entry = AlbumFootnote(content = content, createdAt = System.currentTimeMillis())
        val list = (state.footnotes[recordId] ?: emptyList()) + entry
        val next = state.copy(footnotes = state.footnotes + (recordId to list))
        write(next)
        next
    }

    suspend fun updateFootnote(recordId: String, entryId: Long, content: String): AlbumState = mutex.withLock {
        val state = load()
        val list = state.footnotes[recordId] ?: return@withLock state
        val updated = list.map { entry ->
            if (entry.createdAt == entryId) entry.copy(content = content, updatedAt = System.currentTimeMillis()) else entry
        }
        val next = state.copy(footnotes = state.footnotes + (recordId to updated))
        write(next)
        next
    }

    suspend fun deleteFootnote(recordId: String, entryId: Long): AlbumState = mutex.withLock {
        val state = load()
        val list = state.footnotes[recordId]?.filterNot { it.createdAt == entryId } ?: return@withLock state
        val next = if (list.isEmpty()) state.copy(footnotes = state.footnotes - recordId)
        else state.copy(footnotes = state.footnotes + (recordId to list))
        write(next)
        next
    }

    /** 导入备份时合并注脚：按 createdAt 去重，避免重复导入产生重复条目。 */
    suspend fun importFootnotes(footnotes: Map<String, List<AlbumFootnote>>): AlbumState = mutex.withLock {
        val state = load()
        val next = state.copy(
            footnotes = footnotes.entries.fold(state.footnotes) { acc, (recordId, entries) ->
                if (entries.isEmpty()) acc
                else acc + (recordId to ((acc[recordId] ?: emptyList()) + entries).distinctBy { it.createdAt })
            }
        )
        write(next)
        next
    }

    private suspend fun write(state: AlbumState) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, "album_state.json.tmp")
        tmp.writeText(GsonInstance.gson.toJson(StateFile(state.selected, state.footnotes, state.pinned)))
        if (!tmp.renameTo(file)) {
            // rename 失败时回退：删旧文件后再移动，避免覆盖残留损坏内容。
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** 整体备份还原：合并写入"每张图选中项"与"置顶"（与注脚导入互补）。 */
    suspend fun importState(selected: Map<String, Int>, pinned: Set<String>): AlbumState = mutex.withLock {
        val state = load()
        val next = state.copy(selected = state.selected + selected, pinned = state.pinned + pinned)
        write(next)
        next
    }
}
