package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import memore.app.data.model.Record
import memore.app.data.model.RecordTagCrossRef
import memore.app.data.model.Tag
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: Tag): Long

    @Query("SELECT * FROM tags WHERE name = :name")
    suspend fun getTagByName(name: String): Tag?

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getTagById(id: Int): Tag?

    @Transaction
    suspend fun getOrCreateTag(name: String): Tag {
        val existing = getTagByName(name)
        if (existing != null) return existing
        val tag = Tag(name = name)
        val id = insertTag(tag)
        if (id > 0) return Tag(id = id.toInt(), name = name, createdAt = tag.createdAt)
        return getTagByName(name)!!
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTagToRecord(crossRef: RecordTagCrossRef)

    @Query("DELETE FROM record_tags WHERE record_id = :recordId AND tag_id = :tagId")
    suspend fun removeTagFromRecord(recordId: String, tagId: Int)

    /** 标签 + 每个标签的条数（Agent 服务 /facets 用）。 */
    @Query("SELECT t.name AS name, COUNT(rt.record_id) AS n FROM tags t LEFT JOIN record_tags rt ON t.id = rt.tag_id GROUP BY t.id ORDER BY n DESC")
    suspend fun tagCounts(): List<TagCount>

    /** 全部标签（Agent 服务用，2026-11-13）。 */
    @Query("SELECT * FROM tags ORDER BY name")
    suspend fun allTags(): List<Tag>

    @Query("SELECT t.* FROM tags t JOIN record_tags rt ON t.id = rt.tag_id WHERE rt.record_id = :recordId")
    suspend fun getTagsForRecord(recordId: String): List<Tag>

    /** 批量查询多个记录的标签，返回 record_id → tags 映射。 */
    @Query("SELECT t.*, rt.record_id AS _record_id FROM tags t JOIN record_tags rt ON t.id = rt.tag_id WHERE rt.record_id IN (:recordIds)")
    suspend fun getTagsForRecords(recordIds: List<String>): List<TagWithRecordId>

    data class TagWithRecordId(
        @androidx.room.ColumnInfo(name = "_record_id") val recordId: String,
        @androidx.room.ColumnInfo(name = "id") val id: Int,
        @androidx.room.ColumnInfo(name = "name") val name: String,
        @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long,
        @androidx.room.ColumnInfo(name = "usage_count") val usageCount: Int,
        @androidx.room.ColumnInfo(name = "group_id") val groupId: Int? = null
    )

    @Query("UPDATE tags SET name = :newName WHERE id = :tagId")
    suspend fun renameTag(tagId: Int, newName: String)

    @Query("UPDATE record_tags SET tag_id = :toId WHERE tag_id = :fromId")
    suspend fun mergeTags(fromId: Int, toId: Int)

    @Query("DELETE FROM record_tags WHERE tag_id = :fromId AND record_id IN (SELECT record_id FROM record_tags WHERE tag_id = :toId)")
    suspend fun removeMergeConflicts(fromId: Int, toId: Int)

    @Query("DELETE FROM record_tags WHERE tag_id = :tagId")
    suspend fun removeAllRecordAssociations(tagId: Int)

    /** 记录被**彻底**删除时清掉它的标签关联（软删不清，回收站还原要能带回来）。 */
    @Query("DELETE FROM record_tags WHERE record_id = :recordId")
    suspend fun deleteAllForRecord(recordId: String)

    /** 一次性扫掉历史孤儿关联：记录已不存在，关联还留着。 */
    @Query("DELETE FROM record_tags WHERE record_id NOT IN (SELECT id FROM records)")
    suspend fun purgeOrphanLinks()

    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteTagTag(tagId: Int)

    @Query("SELECT * FROM tags ORDER BY usage_count DESC")
    fun getAllTags(): Flow<List<Tag>>

    /** 某标签组下的成员标签。 */
    @Query("SELECT * FROM tags WHERE group_id = :groupId ORDER BY usage_count DESC")
    suspend fun getTagsByGroup(groupId: Int): List<Tag>

    /** 未分组标签。 */
    @Query("SELECT * FROM tags WHERE group_id IS NULL ORDER BY usage_count DESC")
    suspend fun getUngroupedTags(): List<Tag>

    /** 设置标签所属分组（null = 未分组）。 */
    @Query("UPDATE tags SET group_id = :groupId WHERE id = :tagId")
    suspend fun setTagGroup(tagId: Int, groupId: Int?)

    @Query("SELECT COUNT(*) FROM record_tags WHERE tag_id = :tagId")
    suspend fun getUsageCount(tagId: Int): Int

    @Query("UPDATE tags SET usage_count = (SELECT COUNT(*) FROM record_tags rt JOIN records r ON rt.record_id = r.id WHERE rt.tag_id = :tagId AND r.is_deleted = 0) WHERE id = :tagId")
    suspend fun refreshUsageCount(tagId: Int)

    @Query("UPDATE tags SET usage_count = (SELECT COUNT(*) FROM record_tags rt JOIN records r ON rt.record_id = r.id WHERE rt.tag_id = tags.id AND r.is_deleted = 0)")
    suspend fun refreshAllUsageCounts()
    data class TagCount(val name: String, val n: Int)
}
