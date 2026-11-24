package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import memore.app.data.model.RecordEntity

/** 锚点 + 所属记录的状态（按锚点聚合用）。 */
data class AnchorRow(
    val entityName: String,
    val kind: String,
    val recordId: String,
    val isDeleted: Boolean,
    val isLocked: Boolean
)

/** 锚点聚合行（/anchors/summary 用）。 */
data class AnchorSummaryRow(
    val name: String,
    val kind: String,
    val total: Int,
    @androidx.room.ColumnInfo(name = "record_count") val recordCount: Int
)

@Dao
interface RecordEntityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RecordEntity>)

    @Query("DELETE FROM record_entities WHERE record_id = :recordId")
    suspend fun deleteFor(recordId: String)

    /** 2026-11-21：按主键删一行（概念删除用）。 */
    @Query("DELETE FROM record_entities WHERE record_id = :recordId AND name = :name AND kind = :kind")
    suspend fun deleteOne(recordId: String, name: String, kind: String)

    @Query("SELECT * FROM record_entities WHERE record_id = :recordId")
    suspend fun forRecord(recordId: String): List<RecordEntity>

    /** 已知的全部锚点名称（用于从新记录正文里认出旧锚点）。 */
    @Query("SELECT DISTINCT name FROM record_entities")
    suspend fun allNames(): List<String>

    /** 提到这些锚点的记录（不含自己）。 */
    @Query("SELECT DISTINCT record_id FROM record_entities WHERE name IN (:names) AND record_id != :excludeId")
    suspend fun recordsMentioning(names: List<String>, excludeId: String): List<String>

    @Query(
        "SELECT e.name AS entityName, e.kind AS kind, e.record_id AS recordId, " +
        "r.is_deleted AS isDeleted, r.is_locked AS isLocked " +
        "FROM record_entities e JOIN records r ON r.id = e.record_id"
    )
    suspend fun allWithRecord(): List<AnchorRow>

    @Query("SELECT COUNT(*) FROM record_entities")
    suspend fun count(): Int

    /** 清空全部锚点（2026-11-21：整库重算前用）。返回删除行数。 */
    @Query("DELETE FROM record_entities")
    suspend fun deleteAllNow(): Int

    /** 导出用：全部锚点（不过滤锁定/已删；备份是原样搬回）。 */
    @Query("SELECT * FROM record_entities")
    suspend fun allRaw(): List<RecordEntity>

    /** 给 agent 读的最近锚点（2026-11-20）。锁定/已删记录不出现——引文会泄露正文。 */
    @Query(
        "SELECT e.* FROM record_entities e JOIN records r ON r.id = e.record_id " +
        "WHERE r.is_deleted = 0 AND r.is_locked = 0 ORDER BY e.created_at DESC LIMIT :limit"
    )
    suspend fun all(limit: Int): List<RecordEntity>

    /** 单条记录的锚点（同样排除锁定/已删）。 */
    @Query(
        "SELECT e.* FROM record_entities e JOIN records r ON r.id = e.record_id " +
        "WHERE e.record_id = :recordId AND r.is_deleted = 0 AND r.is_locked = 0"
    )
    suspend fun forRecordUnlocked(recordId: String): List<RecordEntity>

    /** 按名称 + 类型聚合：谁反复出现、覆盖多少条记录。 */
    @Query(
        "SELECT e.name AS name, e.kind AS kind, COUNT(*) AS total, " +
        "COUNT(DISTINCT e.record_id) AS record_count " +
        "FROM record_entities e JOIN records r ON r.id = e.record_id " +
        "WHERE r.is_deleted = 0 AND r.is_locked = 0 " +
        "GROUP BY e.name, e.kind ORDER BY total DESC"
    )
    suspend fun summary(): List<AnchorSummaryRow>
}
