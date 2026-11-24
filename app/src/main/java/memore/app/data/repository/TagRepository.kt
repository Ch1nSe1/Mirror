package memore.app.data.repository

import memore.app.data.local.dao.TagDao
import memore.app.data.model.RecordTagCrossRef
import memore.app.data.model.Tag
import kotlinx.coroutines.flow.Flow

class TagRepository(
    private val tagDao: TagDao
) {

    suspend fun getOrCreateTag(name: String): Tag {
        return tagDao.getOrCreateTag(name)
    }

    suspend fun addTagToRecord(recordId: String, tagId: Int) {
        tagDao.addTagToRecord(RecordTagCrossRef(recordId, tagId))
        tagDao.refreshUsageCount(tagId)
    }

    suspend fun removeTagFromRecord(recordId: String, tagId: Int) {
        tagDao.removeTagFromRecord(recordId, tagId)
        tagDao.refreshUsageCount(tagId)
    }

    suspend fun getTagsForRecord(recordId: String): List<Tag> {
        return tagDao.getTagsForRecord(recordId)
    }

    /**
     * 批量取标签（2026-11-19）：筛选/搜索结果是全库查询，可能包含时间线分页之外的记录，
     * 那些记录不在联合查询结果里，只能按 id 批量补。
     */
    suspend fun getTagsForRecords(recordIds: List<String>): Map<String, List<Tag>> {
        if (recordIds.isEmpty()) return emptyMap()
        return tagDao.getTagsForRecords(recordIds)
            .groupBy { it.recordId }
            .mapValues { (_, list) -> list.map { Tag(id = it.id, name = it.name, createdAt = it.createdAt, usageCount = it.usageCount) } }
    }

    suspend fun renameTag(tagId: Int, newName: String): Result<Unit> {
        return try {
            tagDao.renameTag(tagId, newName)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun mergeTags(fromId: Int, toId: Int): Result<Unit> {
        return try {
            tagDao.removeMergeConflicts(fromId, toId)
            tagDao.mergeTags(fromId, toId)
            tagDao.deleteTagTag(fromId)
            tagDao.refreshUsageCount(toId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteTag(tagId: Int): Result<Unit> {
        return try {
            tagDao.removeAllRecordAssociations(tagId)
            tagDao.deleteTagTag(tagId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 一次性扫掉孤儿关联（记录已不存在但关联还在）。启动时调一次就够。 */
    suspend fun purgeOrphanLinks() {
        tagDao.purgeOrphanLinks()
    }

    fun getAllTags(): Flow<List<Tag>> {
        return tagDao.getAllTags()
    }

    suspend fun refreshAllCounts() {
        tagDao.refreshAllUsageCounts()
    }

    suspend fun getUsageCount(tagId: Int): Int {
        return tagDao.getUsageCount(tagId)
    }
}
