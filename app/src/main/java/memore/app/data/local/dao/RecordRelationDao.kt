package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import memore.app.data.model.RecordRelation

@Dao
interface RecordRelationDao {

    /** 同一条边（同 from/to/type）重复建立时静默忽略 —— 唯一索引兜底。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(relation: RecordRelation)

    /**
     * 反向边是否已存在（2026-11-21，照 LightRAG 的「关系按无向处理」）：
     * A→B 存在时不再建 B→A，同一对记录只留一条，避免关联页两边各显示一次。
     */
    @Query("SELECT COUNT(*) FROM record_relations " +
        "WHERE from_id = :toId AND to_id = :fromId AND status = 'ACTIVE' AND valid_to IS NULL")
    suspend fun reverseActiveCount(fromId: String, toId: String): Int

    /**
     * 复燃一条已失效的边（2026-11-19）。
     *
     * 为什么需要：表上有 (from_id,to_id,type) 唯一索引，重跑分析时旧边只是标了 valid_to，
     * 再 INSERT 会被 IGNORE 掉，这条边就永远"死"了。所以先 UPDATE 复活；返回 0 才插入。
     */
    @Query("UPDATE record_relations SET valid_to = NULL, valid_from = :now, " +
        "why = :why, quote_a = :quoteA, quote_b = :quoteB, score = :score, status = 'ACTIVE' " +
        "WHERE from_id = :fromId AND to_id = :toId AND type = :type AND source = 'AI'")
    suspend fun revive(
        fromId: String, toId: String, type: String, now: Long,
        why: String, quoteA: String, quoteB: String, score: Double
    ): Int

    /** 出向：我引用了/补充了/更正了谁。 */
    @Query("SELECT * FROM record_relations WHERE from_id = :recordId ORDER BY created_at DESC")
    suspend fun from(recordId: String): List<RecordRelation>

    /** 入向：谁引用/补充/更正了我。 */
    @Query("SELECT * FROM record_relations WHERE to_id = :recordId ORDER BY created_at DESC")
    suspend fun to(recordId: String): List<RecordRelation>

    @Query("DELETE FROM record_relations WHERE from_id = :fromId AND to_id = :toId")
    suspend fun deletePair(fromId: String, toId: String)

    /**
     * 2026-11-21：按「无向对」删除（两个方向一起）。
     * 读取侧本来就把 A→B / B→A 当成同一条边；只删单向会留下反向边，看起来像没删掉。
     */
    @Query("DELETE FROM record_relations WHERE (from_id = :a AND to_id = :b) OR (from_id = :b AND to_id = :a)")
    suspend fun deletePairBothWays(a: String, b: String): Int

    /** 记录被永久删除时清干净，避免留下悬空边。 */
    @Query("DELETE FROM record_relations WHERE from_id = :recordId OR to_id = :recordId")
    suspend fun deleteAllFor(recordId: String)

    @Query("SELECT * FROM record_relations")
    suspend fun all(): List<RecordRelation>

    @Query("SELECT COUNT(*) FROM record_relations")
    suspend fun count(): Int

    /** 清空全部关联边（2026-11-21：整库重算前用）。返回删除行数。 */
    @Query("DELETE FROM record_relations")
    suspend fun deleteAllNow(): Int
    // ===== 关联边（LINK）=====

    /** 某条记录所有 ACTIVE 的关联边（双向），最新的在前。 */
    @Query(
        "SELECT * FROM record_relations " +
        "WHERE type = 'LINK' AND status = 'ACTIVE' AND valid_to IS NULL " +
        "AND (from_id = :recordId OR to_id = :recordId) " +
        "ORDER BY created_at DESC"
    )
    suspend fun linksOf(recordId: String): List<RecordRelation>

    /**
     * 重建前把这一条作为发起方的旧关联边**标记失效**（2026-11-19）：
     * 不删除，保留历史；REJECTED 的仍然保留当负样本。
     */
    @Query("UPDATE record_relations SET valid_to = :now " +
        "WHERE from_id = :recordId AND status = 'ACTIVE' AND valid_to IS NULL AND source = 'AI'")
    suspend fun expireActiveLinksFrom(recordId: String, now: Long)

    /** 用户否掉一条：标 REJECTED（不删，留作负样本）。类型不限于 LINK——锚点视图里也能拒绝类型边。 */
    @Query("UPDATE record_relations SET status = 'REJECTED' WHERE from_id = :fromId AND to_id = :toId AND valid_to IS NULL")
    suspend fun rejectLink(fromId: String, toId: String)

    /** 改一条边的类型（2026-11-20，agent 审边用）。唯一索引 (from,to,type) 冲突时由调用方回错误。 */
    @Query("UPDATE record_relations SET type = :type " +
        "WHERE from_id = :fromId AND to_id = :toId AND valid_to IS NULL")
    suspend fun setType(fromId: String, toId: String, type: String)

    /** 撤销时把一条边恢复到快照里的字段（type/why/引文/来源/状态）。2026-11-20 */
    @Query("UPDATE record_relations SET type = :type, why = :why, quote_a = :quoteA, quote_b = :quoteB, " +
        "source = :source, status = 'ACTIVE', valid_to = NULL " +
        "WHERE from_id = :fromId AND to_id = :toId AND valid_to IS NULL")
    suspend fun restoreFields(
        fromId: String, toId: String, type: String, why: String,
        quoteA: String, quoteB: String, source: String
    )

    /** 把一条被拒绝的边恢复为 ACTIVE（2026-11-20，审边纠错用）。 */
    @Query("UPDATE record_relations SET status = 'ACTIVE' WHERE from_id = :fromId AND to_id = :toId AND valid_to IS NULL")
    suspend fun restoreStatus(fromId: String, toId: String)

    /** 按 from/to 找一条当前有效的边（信里展示证据用）。 */
    @Query("SELECT * FROM record_relations WHERE from_id = :fromId AND to_id = :toId AND valid_to IS NULL LIMIT 1")
    suspend fun byPair(fromId: String, toId: String): RecordRelation?

    /** 按记录取所有类型的有效边（审边端点用；linksOf 只查 LINK）。 */
    @Query("SELECT * FROM record_relations WHERE valid_to IS NULL AND status = 'ACTIVE' " +
        "AND (from_id = :recordId OR to_id = :recordId) ORDER BY created_at DESC")
    suspend fun activeForRecord(recordId: String): List<RecordRelation>

    /** 已被否掉的边（重建时跳过，任何类型都算）。 */
    @Query("SELECT * FROM record_relations WHERE status = 'REJECTED' AND from_id = :recordId")
    suspend fun rejectedFrom(recordId: String): List<RecordRelation>
}
