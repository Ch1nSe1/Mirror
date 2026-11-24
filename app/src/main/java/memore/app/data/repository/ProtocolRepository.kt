package memore.app.data.repository

import memore.app.data.local.dao.ProtocolDao
import memore.app.data.model.Protocol
import memore.app.data.model.RecordProtocol
import kotlinx.coroutines.flow.Flow

class ProtocolRepository(
    private val protocolDao: ProtocolDao
) {

    suspend fun getOrCreateProtocol(name: String): Protocol {
        return protocolDao.getOrCreateProtocol(name)
    }

    suspend fun addProtocolToRecord(recordId: String, protocolId: Int) {
        protocolDao.addProtocolToRecord(RecordProtocol(recordId, protocolId))
        protocolDao.refreshUsageCount(protocolId)
    }

    suspend fun removeProtocolFromRecord(recordId: String, protocolId: Int) {
        protocolDao.removeProtocolFromRecord(recordId, protocolId)
        protocolDao.refreshUsageCount(protocolId)
    }

    suspend fun getProtocolsForRecord(recordId: String): List<Protocol> {
        return protocolDao.getProtocolsForRecord(recordId)
    }

    /** 批量取协议（2026-11-19）：同 [TagRepository.getTagsForRecords]，给筛选/搜索的显示集补零件。 */
    suspend fun getProtocolsForRecords(recordIds: List<String>): Map<String, List<Protocol>> {
        if (recordIds.isEmpty()) return emptyMap()
        return protocolDao.getProtocolsForRecords(recordIds)
            .groupBy { it.recordId }
            .mapValues { (_, list) ->
                list.map {
                    Protocol(id = it.id, name = it.name, createdAt = it.createdAt, usageCount = it.usageCount)
                }
            }
    }

    suspend fun renameProtocol(protocolId: Int, newName: String): Result<Unit> {
        return try {
            protocolDao.renameProtocol(protocolId, newName)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun mergeProtocols(fromId: Int, toId: Int): Result<Unit> {
        return try {
            protocolDao.removeMergeConflicts(fromId, toId)
            protocolDao.mergeProtocols(fromId, toId)
            protocolDao.deleteProtocol(fromId)
            protocolDao.refreshUsageCount(toId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteProtocol(protocolId: Int): Result<Unit> {
        return try {
            protocolDao.removeAllRecordAssociations(protocolId)
            protocolDao.deleteProtocol(protocolId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getAllProtocols(): Flow<List<Protocol>> {
        return protocolDao.getAllProtocols()
    }

    suspend fun refreshAllCounts() {
        protocolDao.refreshAllUsageCounts()
    }

    suspend fun getUsageCount(protocolId: Int): Int {
        return protocolDao.getUsageCount(protocolId)
    }

    /** 一次性扫掉孤儿关联。 */
    suspend fun purgeOrphanLinks() {
        protocolDao.purgeOrphanLinks()
    }

    suspend fun getRecordsByProtocolId(protocolId: Int): List<memore.app.data.model.Record> {
        return protocolDao.getRecordsByProtocolId(protocolId)
    }
}
