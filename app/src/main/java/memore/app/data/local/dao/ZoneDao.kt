package memore.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import memore.app.data.model.Zone
import memore.app.data.model.ZoneRecord

@Dao
interface ZoneDao {
    @Query("SELECT * FROM zones ORDER BY sort ASC, created_at ASC")
    suspend fun all(): List<Zone>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(zone: Zone)

    @Query("DELETE FROM zones WHERE id = :id AND builtin = 0")
    suspend fun deleteCustom(id: String)

    @Query("UPDATE zones SET name = :name WHERE id = :id AND builtin = 0")
    suspend fun renameCustom(id: String, name: String)

    @Query("SELECT COALESCE(MAX(sort), 1) FROM zones")
    suspend fun maxSort(): Int

    /** 自定义分区里最小排序值——「前移」用它减一（2026-11-14）。 */
    @Query("SELECT COALESCE(MIN(sort), 1) FROM zones WHERE builtin = 0")
    suspend fun minSort(): Int

    @Query("UPDATE zones SET sort = :sort WHERE id = :id AND builtin = 0")
    suspend fun setSort(id: String, sort: Int)

    // ---- 分区成员（2026-11-14）----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMember(member: ZoneRecord)

    @Query("DELETE FROM zone_records WHERE zone_id = :zoneId AND record_id = :recordId")
    suspend fun removeMember(zoneId: String, recordId: String)

    /** 一个分区里的记录 id，最近加入的排前面。 */
    @Query("SELECT record_id FROM zone_records WHERE zone_id = :zoneId ORDER BY added_at DESC")
    suspend fun memberIds(zoneId: String): List<String>

    /** 一条记录属于哪些自定义分区（锚点不在这张表里）。 */
    @Query("SELECT zone_id FROM zone_records WHERE record_id = :recordId")
    suspend fun zoneIdsOf(recordId: String): List<String>

    /** 批量取：所有成员关系（分区数很少，一次全取比按记录查省事）。 */
    @Query("SELECT * FROM zone_records")
    suspend fun allMembers(): List<ZoneRecord>

    @Query("DELETE FROM zone_records WHERE zone_id = :zoneId")
    suspend fun clearMembers(zoneId: String)
}
