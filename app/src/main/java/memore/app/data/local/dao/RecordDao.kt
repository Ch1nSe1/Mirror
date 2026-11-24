package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import memore.app.data.model.Record
import memore.app.data.model.RecordWithDetails
import kotlinx.coroutines.flow.Flow

/** 加密锁定的一行：记录/增修统一经此原子提交。 */
data class LockRow(val id: String, val encData: String, val iv: String, val tag: String)

/** 解密解锁的一行：同时还原 analysis 与 emotional_tags。 */
data class UnlockRow(
    val id: String,
    val content: String,
    val notes: String,
    val dac: String,
    val dialogueAiReply: String,
    val analysis: String?,
    val emotionalTags: String
)

/** 图文集时间索引的一行：只取 id 与创建时间（月份选择器 / 跳转定位用）。 */
data class RecordTimeRow(val id: String, val createdAt: Long)

@Dao
interface RecordDao {

    @Query("SELECT * FROM records WHERE is_deleted = 0 AND correction_of_id IS NULL ORDER BY created_at DESC LIMIT :limit")
    fun getLatestRecords(limit: Int = 30): Flow<List<Record>>

    /** 联合查询：记录 + 标签 + 协议，Flow 首帧同步组装，避免异步"弹出"。 */
    @Transaction
    @Query("SELECT * FROM records WHERE is_deleted = 0 AND correction_of_id IS NULL ORDER BY created_at DESC LIMIT :limit")
    fun getLatestRecordsWithDetails(limit: Int = 30): Flow<List<RecordWithDetails>>

    @Query("SELECT * FROM records WHERE is_deleted = 0 AND is_anchored = 1 AND correction_of_id IS NULL ORDER BY updated_at DESC")
    fun getAnchoredRecords(): Flow<List<Record>>

    /** 联合查询：锚点记录 + 标签 + 协议。 */
    @Transaction
    @Query("SELECT * FROM records WHERE is_deleted = 0 AND is_anchored = 1 AND correction_of_id IS NULL ORDER BY updated_at DESC")
    fun getAnchoredRecordsWithDetails(): Flow<List<RecordWithDetails>>

    @Query("SELECT * FROM records WHERE is_deleted = 1 AND correction_of_id IS NULL ORDER BY created_at DESC")
    fun getDeletedRecords(): Flow<List<Record>>

    /** 回收站全量（供整体备份）：一次性取全部软删记录（不排除增修，保证"一件不少"）。 */
    @Query("SELECT * FROM records WHERE is_deleted = 1 ORDER BY created_at DESC")
    suspend fun getTrashedRecords(): List<Record>

    @Query("SELECT * FROM records WHERE is_deleted = 0 AND correction_of_id IS NULL AND created_at BETWEEN :startTs AND :endTs ORDER BY created_at DESC")
    fun searchByDateRange(startTs: Long, endTs: Long): Flow<List<Record>>

    @Query("SELECT * FROM records WHERE is_deleted = 0 AND correction_of_id IS NULL AND id IN (SELECT rt.record_id FROM record_tags rt JOIN tags t ON rt.tag_id = t.id WHERE t.name LIKE '%' || :q || '%') ORDER BY created_at DESC")
    fun searchByTagName(q: String): Flow<List<Record>>

    @Query("SELECT DISTINCT r.* FROM records r INNER JOIN record_tags rt ON r.id = rt.record_id WHERE rt.tag_id IN (:tagIds) AND r.is_deleted = 0 AND r.correction_of_id IS NULL ORDER BY r.created_at DESC")
    fun getRecordsByTagIds(tagIds: List<Int>): Flow<List<Record>>

    @Query("SELECT DISTINCT r.* FROM records r INNER JOIN record_protocols rp ON r.id = rp.record_id WHERE rp.protocol_id IN (:protocolIds) AND r.is_deleted = 0 AND r.correction_of_id IS NULL ORDER BY r.created_at DESC")
    fun getRecordsByProtocolIds(protocolIds: List<Int>): Flow<List<Record>>

    /**
     * 标签 **且** 协议（2026-11-15）。
     *
     * 之前筛选只能二选一（标签 or 协议），切一次还清空已选。这里用一条 JOIN 查"两者都满足"，
     * 避免把两张大结果集拉到内存再求交。
     */
    @Query("SELECT DISTINCT r.* FROM records r INNER JOIN record_tags rt ON r.id = rt.record_id INNER JOIN record_protocols rp ON r.id = rp.record_id WHERE rt.tag_id IN (:tagIds) AND rp.protocol_id IN (:protocolIds) AND r.is_deleted = 0 AND r.correction_of_id IS NULL ORDER BY r.created_at DESC")
    fun getRecordsByTagIdsAndProtocolIds(tagIds: List<Int>, protocolIds: List<Int>): Flow<List<Record>>

    @Query("SELECT * FROM records WHERE is_deleted = 0 AND correction_of_id IS NULL")
    suspend fun getAllActiveRecords(): List<Record>

    /**
     * 全部活跃增修（含**孤儿增修**：correction_of_id 指向的记录已不存在）。
     *
     * 2026-11-17：整体备份漏数据的根因 —— [getAllActiveRecords] 带 `correction_of_id IS NULL`，
     * 增修靠"父记录在范围内"补回来；父记录没了的孤儿增修永远补不回来，于是备份包静默少 20 条。
     */
    @Query("SELECT * FROM records WHERE is_deleted = 0 AND correction_of_id IS NOT NULL")
    suspend fun getAllActiveAmendments(): List<Record>

    /**
     * 增量同步投影（2026-11-15）：/changes?since= 用。
     * 只要四列——不用 Record 全量，避开 analysis 反序列化。
     */
    @Query("SELECT id, created_at, updated_at, is_deleted FROM records")
    suspend fun getChangeRows(): List<memore.app.data.model.ChangeRow>

    /**
     * 轻量投影（2026-11-13）：只取聚类要的列，**不反序列化 analysis**。
     * 之前用 getAllActiveRecords() 跑聚类，光是解析 analysis 就换来 20–25MB 的连续 GC。
     */
    /** Agent 服务用：一条记录的标签名（2026-11-13）。 */
    @Query("SELECT t.name FROM record_tags rt JOIN tags t ON t.id = rt.tag_id WHERE rt.record_id = :recordId ORDER BY t.name")
    suspend fun tagNamesOf(recordId: String): List<String>

    /** Agent 服务用：一条记录的协议名。 */
    @Query("SELECT p.name FROM record_protocols rp JOIN protocols p ON p.id = rp.protocol_id WHERE rp.record_id = :recordId ORDER BY p.name")
    suspend fun protocolNamesOf(recordId: String): List<String>

    /** 按协议名取记录 id（标签/协议/来源三兄弟共用同一个记录列表页，2026-11-13）。 */
    @Query("SELECT rp.record_id FROM record_protocols rp JOIN protocols p ON p.id = rp.protocol_id WHERE p.name = :name")
    suspend fun recordIdsByProtocolName(name: String): List<String>

    /** Agent 服务用：id + 情绪标签原始 JSON（/stats 要解析它）。 */
    /** 锁定或已删的记录 id：给读端点做过滤（2026-11-20）。 */
    @Query("SELECT id FROM records WHERE is_locked = 1 OR is_deleted = 1")
    suspend fun blockedRecordIds(): List<String>

    @Query("SELECT id, emotional_tags FROM records WHERE is_deleted = 0 AND is_locked = 0")
    suspend fun idAndEmotions(): List<IdEmotions>

    data class IdEmotions(val id: String, @androidx.room.ColumnInfo(name = "emotional_tags") val emotionalTags: String)

    /** Agent 服务用：按标签名取记录 id。 */
    @Query("SELECT rt.record_id FROM record_tags rt JOIN tags t ON t.id = rt.tag_id WHERE t.name = :name")
    suspend fun recordIdsByTagName(name: String): List<String>

    /** 导出筛选（2026-11-16）：按标签 id 取记录 id。 */
    @Query("SELECT DISTINCT record_id FROM record_tags WHERE tag_id IN (:tagIds)")
    suspend fun recordIdsByTagIds(tagIds: List<Int>): List<String>

    /** Agent 服务用：单条原始实体（含 analysis 等全字段）。 */
    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun rawById(id: String): Record?

    /** Agent 服务用：某条记录的增修。 */
    @Query("SELECT * FROM records WHERE correction_of_id = :id AND is_deleted = 0 ORDER BY created_at")
    suspend fun amendmentsOf(id: String): List<Record>

    /** Agent 服务用：每个来源多少条。 */
    @Query("SELECT origin_id AS originId, COUNT(*) AS n FROM records WHERE is_deleted = 0 GROUP BY origin_id")
    suspend fun originCounts(): List<OriginCount>

    /** 分区页的锚点区（2026-11-14）：轻量版，别把整条记录拖出来。 */
    @Query("SELECT * FROM records WHERE is_anchored = 1 AND is_deleted = 0 ORDER BY created_at DESC")
    suspend fun getAnchoredLight(): List<memore.app.data.model.Record>

    @Query("SELECT id, created_at, content, notes, origin_id, is_locked, is_deleted, correction_of_id, type FROM records WHERE is_deleted = 0 ORDER BY created_at DESC")
    suspend fun getAllActiveLight(): List<memore.app.data.model.LightRecord>

    /** 该 id 是否存在（**不过滤 is_deleted**：回收站里的记录，附件还得留着）。 */
    @Query("SELECT COUNT(*) FROM records WHERE id = :id")
    suspend fun countById(id: String): Int

    @Query("SELECT COUNT(*) FROM records WHERE is_deleted = 0 AND correction_of_id IS NULL")
    suspend fun countActiveRecords(): Int

    /** 可分析的记录：正文非空（空正文=附件/语音占位，分析无意义）；AI 札记排除（2026-11-21）。 */
    @Query("SELECT COUNT(*) FROM records WHERE is_deleted = 0 AND correction_of_id IS NULL AND TRIM(content) <> '' AND type <> 'AI_NOTE'")
    suspend fun countAnalyzableRecords(): Int

    /** 某时间区间 [start,end) 内的活跃（未删）记录数（含增修，供秒针刻度展示）。 */
    @Query("SELECT COUNT(*) FROM records WHERE is_deleted = 0 AND created_at >= :start AND created_at < :end")
    suspend fun countActiveInRange(start: Long, end: Long): Int

    /** 某时间区间 [start,end) 内的活跃（未删）记录，按创建时间降序（供刻度记录页）。 */
    @Query("SELECT * FROM records WHERE is_deleted = 0 AND created_at >= :start AND created_at < :end ORDER BY created_at DESC")
    suspend fun getActiveRecordsInRange(start: Long, end: Long): List<Record>

    @Query("SELECT * FROM records WHERE is_deleted = 0 AND correction_of_id IS NOT NULL")
    suspend fun getAllAmendments(): List<Record>

    /**
     * 2026-11-23：孤儿增修 —— 父记录已经彻底不存在（旧版清空回收站没级联删留下的）。
     * 启动时一次性清掉，避免它们以独立记录身份被搜出来/统计。
     */
    @Query(
        "SELECT * FROM records WHERE type = 'AMENDMENT' AND correction_of_id IS NOT NULL " +
            "AND correction_of_id != '' AND NOT EXISTS " +
            "(SELECT 1 FROM records p WHERE p.id = records.correction_of_id)"
    )
    suspend fun getOrphanAmendments(): List<Record>

    @Query("SELECT * FROM records WHERE is_deleted = 0 AND correction_of_id = :recordId ORDER BY created_at ASC")
    suspend fun getAmendmentsForRecord(recordId: String): List<Record>

    /**
     * 某条记录的**全部**增修（含已进回收站的）。
     * 彻底删除父记录时用它级联删除，避免造出孤儿增修（2026-11-18 修 bug）。
     */
    @Query("SELECT * FROM records WHERE correction_of_id = :recordId")
    suspend fun getAllAmendmentsOf(recordId: String): List<Record>

    /** 批量查询多个记录的增修（correction_of_id 指向这些记录）。 */
    @Query("SELECT * FROM records WHERE is_deleted = 0 AND correction_of_id IN (:recordIds) ORDER BY created_at ASC")
    suspend fun getAmendmentsForRecords(recordIds: List<String>): List<Record>

    /** 批量查询多个记录的增修（响应式 Flow 版，DB 变更时自动重新发射）。 */
    @Query("SELECT * FROM records WHERE is_deleted = 0 AND correction_of_id IN (:recordIds) ORDER BY created_at ASC")
    fun getAmendmentsForRecordsFlow(recordIds: List<String>): Flow<List<Record>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecord(record: Record)

    @Update
    suspend fun updateRecord(record: Record)

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun permanentlyDeleteRecord(id: String)

    @Query("DELETE FROM records WHERE is_deleted = 1")
    suspend fun clearTrash()

    @Query("UPDATE records SET is_deleted = 1, updated_at = :now WHERE id = :id")
    suspend fun softDeleteRecord(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE records SET is_deleted = 1, updated_at = :now WHERE id IN (:ids) AND is_deleted = 0")
    suspend fun softDeleteRecords(ids: List<String>, now: Long = System.currentTimeMillis())

    @Query("UPDATE records SET is_deleted = 0, updated_at = :now WHERE id = :id")
    suspend fun restoreRecord(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE records SET is_anchored = CASE WHEN is_anchored = 0 THEN 1 ELSE 0 END, updated_at = :now WHERE id = :id")
    suspend fun toggleAnchor(id: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun getRecordById(id: String): Record?

    @Query("SELECT * FROM records WHERE id IN (:ids)")
    suspend fun getRecordsByIds(ids: List<String>): List<Record>

    /** 按短/部分 ID 前缀匹配候选记录（短 ID = id.take(6)，用于关联输入时接受短 ID）。 */
    @Query("SELECT * FROM records WHERE id LIKE :prefix || '%' ORDER BY created_at DESC")
    suspend fun getRecordsByIdPrefix(prefix: String): List<Record>

    @Query("UPDATE records SET origin_id = :originId WHERE id = :recordId")
    suspend fun setOrigin(recordId: String, originId: String?)

    @Query("UPDATE records SET ai_status = :status WHERE id = :recordId")
    suspend fun updateAiStatus(recordId: String, status: String)

    @Query("UPDATE records SET emotional_tags = :tags WHERE id = :recordId")
    suspend fun updateEmotionalTags(recordId: String, tags: String)

    @Query("UPDATE records SET analysis = :analysisJson WHERE id = :recordId")
    suspend fun updateAnalysis(recordId: String, analysisJson: String?)

    @Query("SELECT * FROM records WHERE ai_status = :status AND is_deleted = 0")
    suspend fun getRecordsByAiStatus(status: String): List<Record>


    @Query("SELECT * FROM records WHERE ((emotional_tags IS NOT NULL AND emotional_tags != '' AND emotional_tags != '[]') OR analysis LIKE '%\"emotion\":%') AND is_deleted = 0 ORDER BY updated_at DESC")
    suspend fun getRecordsWithEmotion(): List<Record>

    @Query("""
        SELECT DISTINCT r.* FROM records r
        INNER JOIN attachments a ON a.record_id = r.id
        WHERE r.is_deleted = 0 AND r.is_locked = 0 AND a.type = 'IMAGE'
        ORDER BY r.created_at DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getRecordsWithImageAttachments(limit: Int, offset: Int): List<Record>

    @Query("SELECT COUNT(DISTINCT r.id) FROM records r INNER JOIN attachments a ON a.record_id = r.id WHERE r.is_deleted = 0 AND r.is_locked = 0 AND a.type = 'IMAGE'")
    suspend fun countImageRecords(): Int

    /**
     * 图文集全量时间索引（只取 id + 创建时间，顺序与 [getRecordsWithImageAttachments] 完全一致）。
     * 供月份选择器与跳转定位使用：月份列表不能依赖"已加载的那几页"，否则一开始只有最近两个月份。
     */
    @Query("""
        SELECT DISTINCT r.id AS id, r.created_at AS createdAt FROM records r
        INNER JOIN attachments a ON a.record_id = r.id
        WHERE r.is_deleted = 0 AND r.is_locked = 0 AND a.type = 'IMAGE'
        ORDER BY r.created_at DESC
    """)
    suspend fun getImageRecordTimes(): List<RecordTimeRow>

    @Query("UPDATE records SET analysis_input_hash = :hash WHERE id = :recordId")
    suspend fun updateAnalysisInputHash(recordId: String, hash: String)

    @Transaction
    suspend fun updateRecordWithAi(recordId: String, summary: String, tagsJson: String, analysisJson: String?) {
        updateAiStatus(recordId, "SUCCESS")
        updateEmotionalTags(recordId, tagsJson)
        updateAnalysis(recordId, analysisJson)
        val record = getRecordById(recordId)
        if (record == null) {
            android.util.Log.w("DB-DAO", "updateRecordWithAi: record $recordId not found after updates")
            return
        }
        updateRecord(record.copy(dialogueAiContent = summary))
    }

    // ---- 加密锁定/解锁 ----

    @Query("UPDATE records SET is_locked=1, encrypted_data=:encData, iv=:iv, auth_tag=:tag, enc_version=2, content='', notes='', dialogue_ai_content='', dialogue_ai_reply='', analysis=NULL, emotional_tags='[]', updated_at=:now WHERE id=:id")
    suspend fun lockRecord(id: String, encData: String, iv: String, tag: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE records SET is_locked=0, encrypted_data='', iv='', auth_tag='', enc_version=1, content=:content, notes=:notes, dialogue_ai_content=:dac, dialogue_ai_reply=:dar, analysis=:analysis, emotional_tags=:emotionalTags, updated_at=:now WHERE id=:id")
    suspend fun unlockRecord(id: String, content: String, notes: String, dac: String, dar: String, analysis: String?, emotionalTags: String, now: Long = System.currentTimeMillis())

    /** 级联锁定：父记录与其增修原子提交。 */
    @Transaction
    suspend fun lockRecordCascade(rows: List<LockRow>) {
        rows.forEach { lockRecord(it.id, it.encData, it.iv, it.tag) }
    }

    /** 级联解锁：父记录与其增修原子提交，还原 analysis/emotional_tags。 */
    @Transaction
    suspend fun unlockRecordCascade(rows: List<UnlockRow>) {
        rows.forEach { unlockRecord(it.id, it.content, it.notes, it.dac, it.dialogueAiReply, it.analysis, it.emotionalTags) }
    }

    // ---- v5 门闩式（2026-11-16）：只改标记，不碰正文 ----

    /**
     * 只置锁定标记（v5）：**不动 content、不写密文**。
     * 与 [lockRecord] 的区别是"数据层明文"这件事的全部实现。
     */
    @Query("UPDATE records SET is_locked=1, updated_at=:now WHERE id IN (:ids)")
    suspend fun markLocked(ids: List<String>, now: Long = System.currentTimeMillis())

    /** 只清锁定标记（v5）。 */
    @Query("UPDATE records SET is_locked=0, updated_at=:now WHERE id IN (:ids)")
    suspend fun markUnlocked(ids: List<String>, now: Long = System.currentTimeMillis())

    /** v5 迁移用：仍带密文的记录（正文只在 encrypted_data 里）。 */
    @Query("SELECT * FROM records WHERE encrypted_data IS NOT NULL AND encrypted_data <> ''")
    suspend fun getCipherRecords(): List<Record>

    /** v5 迁移进度：还剩多少条带密文。 */
    @Query("SELECT COUNT(*) FROM records WHERE encrypted_data IS NOT NULL AND encrypted_data <> ''")
    suspend fun countCipherRecords(): Int

    @Query("SELECT * FROM records WHERE is_locked=1 AND is_deleted=0")
    suspend fun getAllLockedRecords(): List<memore.app.data.model.Record>

    /**
     * **真正带密文**的锁定记录数。
     * 不含「待真锁」（导入时 `was_locked`：明文 + `is_locked=1` 但 `encrypted_data` 为空）——
     * 那不依赖任何旧密钥，新设备设新密码后由 `lockPendingEncryptionRecords` 补锁就行。
     */
    @Query("SELECT COUNT(*) FROM records WHERE is_locked = 1 AND encrypted_data != ''")
    suspend fun trulyEncryptedRecordCount(): Int

    /** 待真锁记录：标记为锁定但内容仍为明文、尚未用密钥加密（导入解密导出的"曾锁定"记录）。 */
    @Query("SELECT * FROM records WHERE is_locked=1 AND is_deleted=0 AND encrypted_data = '' AND content != ''")
    suspend fun getPendingLockedRecords(): List<memore.app.data.model.Record>

    @Query("UPDATE records SET encrypted_data=:encData, iv=:iv, auth_tag=:tag, enc_version=2, updated_at=:now WHERE id=:id")
    suspend fun updateEncryption(id: String, encData: String, iv: String, tag: String, now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM records WHERE is_locked=1 AND is_deleted=0")
    suspend fun lockedRecordCount(): Int

    /** 更新记录的双向关联字段 linked_ids（JSON 数组字符串）。 */
    @Query("UPDATE records SET linked_ids = :linked, updated_at = :now WHERE id = :id")
    suspend fun updateLinkedIds(id: String, linked: String, now: Long = System.currentTimeMillis())

    /** 原子更新一对记录的 linked_ids，保证 a↔b 双向一致。 */
    @Transaction
    suspend fun updateLinkedPair(aId: String, aLinked: String, bId: String, bLinked: String) {
        updateLinkedIds(aId, aLinked)
        updateLinkedIds(bId, bLinked)
    }

    /** 任意原生查询并映射为 Record（用于 FTS5 全文检索，FTS 表非 Room 实体）。 */
    @RawQuery(observedEntities = [Record::class])
    suspend fun rawSearch(query: SupportSQLiteQuery): List<Record>
    data class OriginCount(val originId: String?, val n: Int)
}
