package memore.app.data.repository

import memore.app.data.local.dao.RecordPresetDao
import memore.app.data.model.RecordPreset

/**
 * 新建记录预设（2026-11-17）。
 *
 * 只做存取与"用一次就把 last_used_at 顶上去"，排序规则见 [RecordPresetDao.all]。
 * 不含业务判断：套用时怎么填、附件要不要清，由编辑页决定。
 */
class RecordPresetRepository(private val dao: RecordPresetDao) {

    suspend fun all(): List<RecordPreset> = runCatching { dao.all() }.getOrDefault(emptyList())

    suspend fun save(preset: RecordPreset): Long =
        if (preset.id == 0L) dao.insert(preset) else { dao.update(preset); preset.id }

    suspend fun delete(id: Long) = dao.delete(id)

    /** 套用后调用：把它顶到最前面（"哪个后执行就在最前面"）。 */
    suspend fun markUsed(id: Long, at: Long = System.currentTimeMillis()) = dao.touch(id, at)
}
