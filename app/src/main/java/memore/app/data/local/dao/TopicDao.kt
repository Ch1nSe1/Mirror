package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import memore.app.data.model.RecordTopic
import memore.app.data.model.Topic
import memore.app.data.model.TopicOverride
import kotlinx.coroutines.flow.Flow

/**
 * 表盘汇总用的投影：某段时间内每条记录属于哪些主题（2026-11-18 接真主题）。
 */
data class TopicSliceRow(val recordId: String, val name: String)

@Dao
interface TopicDao {

    /** 顶层列表只返回母题（2026-11-13 两级化）。子话题用 [childrenOf]。 */
    @Query("SELECT * FROM topics WHERE parent_id IS NULL ORDER BY member_count DESC")
    fun observeTopics(): Flow<List<Topic>>

    @Query("SELECT * FROM topics WHERE parent_id = :parentId ORDER BY member_count DESC")
    suspend fun childrenOf(parentId: String): List<Topic>

    @Query("SELECT * FROM topics ORDER BY level, member_count DESC")
    suspend fun topics(): List<Topic>

    @Query("SELECT * FROM topics WHERE id = :id")
    suspend fun topic(id: String): Topic?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTopics(list: List<Topic>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinks(list: List<RecordTopic>)

    @Query("DELETE FROM topics")
    suspend fun clearTopics()

    @Query("DELETE FROM record_topics")
    suspend fun clearLinks()

    /** 两级化后：母题自己没有归属行，记录挂在子话题上 —— 所以这里要把子话题的行并进来。 */
    @Query("SELECT record_id FROM record_topics WHERE topic_id = :topicId " +
        "UNION SELECT rt.record_id FROM record_topics rt JOIN topics t ON rt.topic_id = t.id WHERE t.parent_id = :topicId")
    suspend fun recordIdsOf(topicId: String): List<String>

    @Query("SELECT topic_id FROM record_topics WHERE record_id = :recordId ORDER BY score DESC LIMIT 1")
    suspend fun topicIdOf(recordId: String): String?

    @Query("SELECT * FROM topic_overrides")
    suspend fun overrides(): List<TopicOverride>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOverride(override: TopicOverride)

    @Query("DELETE FROM topic_overrides WHERE centroid_hash = :hash")
    suspend fun deleteOverride(hash: String)

    /** 表盘汇总：区间内记录 → 主题名（只取未删除记录）。 */
    @Query("""
        SELECT rt.record_id AS recordId, t.name AS name
        FROM record_topics rt
        JOIN topics t ON t.id = rt.topic_id
        JOIN records r ON r.id = rt.record_id
        WHERE r.created_at >= :start AND r.created_at < :end AND r.is_deleted = 0
    """)
    suspend fun topicSliceRows(start: Long, end: Long): List<TopicSliceRow>
}
