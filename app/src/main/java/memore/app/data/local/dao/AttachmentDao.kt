package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import memore.app.data.model.Attachment

/** 记录 id → 附件数量（列表页用，避免 N+1）。 */
data class AttachmentCount(val recordId: String, val n: Int)

@Dao
interface AttachmentDao {

    @Insert
    suspend fun insert(attachment: Attachment)

    @Query("SELECT * FROM attachments WHERE record_id = :recordId ORDER BY created_at ASC")
    suspend fun getForRecord(recordId: String): List<Attachment>

    @Query("SELECT * FROM attachments WHERE record_id IN (:recordIds) ORDER BY record_id ASC, created_at ASC")
    suspend fun getForRecords(recordIds: List<String>): List<Attachment>

    @Query("SELECT record_id AS recordId, COUNT(*) AS n FROM attachments WHERE record_id IN (:recordIds) GROUP BY record_id")
    suspend fun countForRecords(recordIds: List<String>): List<AttachmentCount>

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM attachments WHERE record_id = :recordId")
    suspend fun deleteAllForRecord(recordId: String)

    @Query("SELECT COUNT(*) FROM attachments WHERE record_id = :recordId")
    suspend fun countForRecord(recordId: String): Int

    /**
     * 全库**加密附件**数量。
     * 用途只有一个：**整体备份前提醒** —— 未勾「明文解密」时加密附件不会进包，
     * 不能让用户以为"全量备份是全的"（用户 2026-10-11 要求"明确提示"）。
     */
    @Query("SELECT COUNT(*) FROM attachments")
    suspend fun countAll(): Int

    @Query("SELECT COALESCE(SUM(file_size), 0) FROM attachments")
    suspend fun totalBytes(): Long

    /** 全库附件索引（2026-11-15）：agent 之前完全不知道有哪些图/语音。 */
    @Query("SELECT * FROM attachments ORDER BY created_at DESC")
    suspend fun getAll(): List<Attachment>

    @Query("SELECT COUNT(*) FROM attachments WHERE is_encrypted = 1")
    suspend fun countEncrypted(): Int

    /** v5 迁移用（2026-11-16）：所有仍加密的附件。 */
    @Query("SELECT * FROM attachments WHERE is_encrypted = 1")
    suspend fun getEncrypted(): List<Attachment>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun getById(id: Long): Attachment?

    @Query("SELECT * FROM attachments WHERE record_id = :recordId AND file_name = :fileName LIMIT 1")
    suspend fun findByRecordIdAndFileName(recordId: String, fileName: String): Attachment?

    /** 加密附件：写 iv/tag/enc_name，file_name 换成唯一 token（不置空，避免唯一索引冲突）。 */
    @Query("UPDATE attachments SET iv=:iv, auth_tag=:tag, enc_name=:encName, file_name=:tokenName, is_encrypted=1 WHERE id=:id") // gitleaks:allow（SQL 命名参数，不是密钥）
    suspend fun encryptAttachment(id: Long, iv: String, tag: String, encName: String, tokenName: String)

    /** 解密附件：清空加密字段，file_name 还原为原始名。 */
    @Query("UPDATE attachments SET iv='', auth_tag='', enc_name='', is_encrypted=0, file_name=:fileName WHERE id=:id")
    suspend fun decryptAttachment(id: Long, fileName: String)
}
