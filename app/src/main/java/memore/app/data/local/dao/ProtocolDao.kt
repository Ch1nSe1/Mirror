package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import memore.app.data.model.Protocol
import memore.app.data.model.Record
import memore.app.data.model.RecordProtocol
import kotlinx.coroutines.flow.Flow

@Dao
interface ProtocolDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProtocol(protocol: Protocol): Long

    @Query("SELECT p.name AS name, COUNT(rp.record_id) AS n FROM protocols p LEFT JOIN record_protocols rp ON p.id = rp.protocol_id GROUP BY p.id ORDER BY n DESC")
    suspend fun protocolCounts(): List<ProtocolCount>

    @Query("SELECT * FROM protocols WHERE name = :name")
    suspend fun getProtocolByName(name: String): Protocol?

    @Query("SELECT * FROM protocols WHERE id = :id")
    suspend fun getProtocolById(id: Int): Protocol?

    @Transaction
    suspend fun getOrCreateProtocol(name: String): Protocol {
        val existing = getProtocolByName(name)
        if (existing != null) return existing
        val protocol = Protocol(name = name)
        val id = insertProtocol(protocol)
        if (id > 0) return Protocol(id = id.toInt(), name = name, createdAt = protocol.createdAt)
        return getProtocolByName(name)!!
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addProtocolToRecord(crossRef: RecordProtocol)

    @Query("DELETE FROM record_protocols WHERE record_id = :recordId AND protocol_id = :protocolId")
    suspend fun removeProtocolFromRecord(recordId: String, protocolId: Int)

    @Query("SELECT p.* FROM protocols p JOIN record_protocols rp ON p.id = rp.protocol_id WHERE rp.record_id = :recordId")
    suspend fun getProtocolsForRecord(recordId: String): List<Protocol>

    /** 批量查询多个记录的协议，返回 record_id → protocols 映射。 */
    @Query("SELECT p.*, rp.record_id AS _record_id FROM protocols p JOIN record_protocols rp ON p.id = rp.protocol_id WHERE rp.record_id IN (:recordIds)")
    suspend fun getProtocolsForRecords(recordIds: List<String>): List<ProtocolWithRecordId>

    data class ProtocolWithRecordId(
        @androidx.room.ColumnInfo(name = "_record_id") val recordId: String,
        @androidx.room.ColumnInfo(name = "id") val id: Int,
        @androidx.room.ColumnInfo(name = "name") val name: String,
        @androidx.room.ColumnInfo(name = "created_at") val createdAt: Long,
        @androidx.room.ColumnInfo(name = "usage_count") val usageCount: Int
    )

    @Query("UPDATE protocols SET name = :newName WHERE id = :protocolId")
    suspend fun renameProtocol(protocolId: Int, newName: String)

    @Query("UPDATE record_protocols SET protocol_id = :toId WHERE protocol_id = :fromId")
    suspend fun mergeProtocols(fromId: Int, toId: Int)

    @Query("DELETE FROM record_protocols WHERE protocol_id = :fromId AND record_id IN (SELECT record_id FROM record_protocols WHERE protocol_id = :toId)")
    suspend fun removeMergeConflicts(fromId: Int, toId: Int)

    @Query("DELETE FROM record_protocols WHERE protocol_id = :protocolId")
    suspend fun removeAllRecordAssociations(protocolId: Int)

    /** 记录被**彻底**删除时清掉它的协议关联（软删不清，回收站还原要能带回来）。 */
    @Query("DELETE FROM record_protocols WHERE record_id = :recordId")
    suspend fun deleteAllForRecord(recordId: String)

    /** 一次性扫掉历史孤儿关联。 */
    @Query("DELETE FROM record_protocols WHERE record_id NOT IN (SELECT id FROM records)")
    suspend fun purgeOrphanLinks()

    @Query("DELETE FROM protocols WHERE id = :protocolId")
    suspend fun deleteProtocol(protocolId: Int)

    @Query("SELECT * FROM protocols ORDER BY usage_count DESC")
    fun getAllProtocols(): Flow<List<Protocol>>

    @Query("SELECT COUNT(*) FROM record_protocols WHERE protocol_id = :protocolId")
    suspend fun getUsageCount(protocolId: Int): Int

    @Query("UPDATE protocols SET usage_count = (SELECT COUNT(*) FROM record_protocols rp JOIN records r ON rp.record_id = r.id WHERE rp.protocol_id = :protocolId AND r.is_deleted = 0) WHERE id = :protocolId")
    suspend fun refreshUsageCount(protocolId: Int)

    @Query("UPDATE protocols SET usage_count = (SELECT COUNT(*) FROM record_protocols rp JOIN records r ON rp.record_id = r.id WHERE rp.protocol_id = protocols.id AND r.is_deleted = 0)")
    suspend fun refreshAllUsageCounts()

    @Query("""
        SELECT r.* FROM records r
        JOIN record_protocols rp ON r.id = rp.record_id
        WHERE rp.protocol_id = :protocolId AND r.is_deleted = 0
        ORDER BY r.created_at DESC
    """)
    suspend fun getRecordsByProtocolId(protocolId: Int): List<Record>
    data class ProtocolCount(val name: String, val n: Int)
}
