package memore.app.data.repository

import memore.app.data.local.AppDatabase
import memore.app.data.model.BuiltinZones
import memore.app.data.model.Record
import memore.app.data.model.Zone
import org.json.JSONArray
import org.json.JSONObject

/**
 * 分区条件。一个对象吃下所有筛选：
 * 锚点 = anchored、AI 札记 = types、自定义 = 任意组合。
 */
data class ZoneCondition(
    val anchored: Boolean = false,
    val types: List<String> = emptyList(),
    val originIds: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val emotions: List<String> = emptyList(),
    val protocols: List<String> = emptyList(),
    val concepts: List<String> = emptyList(),
    val from: Long? = null,
    val to: Long? = null,
    val keyword: String = "",
    /** 手动型分区（2026-11-14）：没有筛选条件，靠往里塞记录。新建的分区就是这个。 */
    val manual: Boolean = false
) {
    fun isEmpty(): Boolean =
        !anchored && types.isEmpty() && originIds.isEmpty() && tags.isEmpty() &&
            emotions.isEmpty() && protocols.isEmpty() && concepts.isEmpty() &&
            from == null && to == null && keyword.isBlank()

    fun toJson(): String = JSONObject()
        .put("anchored", anchored)
        .put("types", JSONArray(types))
        .put("originIds", JSONArray(originIds))
        .put("tags", JSONArray(tags))
        .put("emotions", JSONArray(emotions))
        .put("protocols", JSONArray(protocols))
        .put("concepts", JSONArray(concepts))
        .put("from", from ?: 0L)
        .put("to", to ?: 0L)
        .put("keyword", keyword)
        .put("manual", manual)
        .toString()

    companion object {
        fun of(raw: String): ZoneCondition = runCatching {
            if (raw.isBlank()) return ZoneCondition()
            val o = JSONObject(raw)
            ZoneCondition(
                anchored = o.optBoolean("anchored"),
                types = o.list("types"),
                originIds = o.list("originIds"),
                tags = o.list("tags"),
                emotions = o.list("emotions"),
                protocols = o.list("protocols"),
                concepts = o.list("concepts"),
                from = o.optLong("from").takeIf { it > 0 },
                to = o.optLong("to").takeIf { it > 0 },
                keyword = o.optString("keyword"),
                manual = o.optBoolean("manual")
            )
        }.getOrDefault(ZoneCondition())

        private fun JSONObject.list(key: String): List<String> {
            val a = optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
        }
    }
}

/**
 * 分区仓库（2026-11-14）。
 *
 * 内置两个（锚点、札记）不查表，直接由代码给出——它们不能改名、不能删，
 * 存进表里只会多一个"状态可能被改坏"的口子。
 */
class ZoneRepository(private val db: AppDatabase) {

    suspend fun zones(): List<Zone> {
        val custom = runCatching { db.zoneDao().all() }.getOrDefault(emptyList())
            .filter { !it.builtin }
            .sortedBy { it.sort }
        return BuiltinZones.all + custom
    }

    suspend fun recordIdsFor(zone: Zone): List<String> = recordsFor(zone).map { it.id }

    /** 一个分区里的记录。内置走各自的条件，自定义走条件 JSON。 */
    suspend fun recordsFor(zone: Zone): List<Record> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val all = runCatching { db.recordDao().getAllActiveLight() }.getOrDefault(emptyList())
            .filter { !it.isLocked }
        // 2026-11-19：所有分支统一按时间倒序。成员分支原来直接按 zone_records 的插入顺序返回，
        // 于是"进区域的第一条"是当初第一个被加进去的，而不是最新的一条。
        (when (zone.id) {
            BuiltinZones.ANCHOR -> anchoredRecords()
            BuiltinZones.AI_NOTE -> fullRecords(all).filter { it.type.name == "AI_NOTE" }
            else -> {
                val cond = ZoneCondition.of(zone.condition)
                // 2026-11-14 修：先看成员表，再看条件。
                // 起因：「创作」是加 manual 标记之前建的，condition 里没有 manual，
                // 于是明明有成员却走"空条件 = 空分区"那条分支 → 点进去永远是空的。
                val memberIds = runCatching { db.zoneDao().memberIds(zone.id) }.getOrDefault(emptyList())
                when {
                    memberIds.isNotEmpty() -> {
                        val byId = fullRecords(all).associateBy { it.id }
                        memberIds.mapNotNull { byId[it] }
                    }
                    cond.manual || cond.isEmpty() -> emptyList()
                    else -> filter(cond, fullRecords(all))
                }
            }
        }).sortedByDescending { it.createdAt }
    }

    private suspend fun anchoredRecords(): List<Record> =
        runCatching { db.recordDao().getAnchoredLight() }.getOrDefault(emptyList())

    private suspend fun fullRecords(light: List<memore.app.data.model.LightRecord>): List<Record> {
        if (light.isEmpty()) return emptyList()
        return runCatching { db.recordDao().getRecordsByIds(light.map { it.id }) }.getOrDefault(emptyList())
    }

    private suspend fun filter(c: ZoneCondition, records: List<Record>): List<Record> {
        var out = records
        if (c.anchored) out = out.filter { it.isAnchored }
        if (c.types.isNotEmpty()) out = out.filter { it.type.name in c.types }
        if (c.originIds.isNotEmpty()) out = out.filter { it.originId in c.originIds }
        if (c.from != null) out = out.filter { it.createdAt >= c.from }
        if (c.to != null) out = out.filter { it.createdAt <= c.to }
        if (c.keyword.isNotBlank()) out = out.filter { it.content.contains(c.keyword, ignoreCase = true) }
        if (c.tags.isNotEmpty()) {
            val matched = c.tags.flatMap { name ->
                runCatching { db.recordDao().recordIdsByTagName(name) }.getOrDefault(emptyList())
            }.toSet()
            out = out.filter { it.id in matched }
        }
        if (c.emotions.isNotEmpty()) {
            out = out.filter { r ->
                val raw = r.emotionalTags
                c.emotions.any { raw.contains("\"$it\"") }
            }
        }
        return out.sortedByDescending { it.createdAt }
    }

    suspend fun create(name: String, condition: ZoneCondition): Zone {
        val zone = Zone(
            id = "zone_" + java.util.UUID.randomUUID().toString().take(8),
            name = name,
            condition = condition.toJson(),
            sort = runCatching { db.zoneDao().maxSort() }.getOrDefault(0) + 1
        )
        db.zoneDao().upsert(zone)
        return zone
    }

    suspend fun rename(id: String, name: String) = db.zoneDao().renameCustom(id, name)

    /** 「前移」：排到自定义分区最前面（锚点、札记固定占前两个，不参与，2026-11-14）。 */
    suspend fun moveToFront(id: String) {
        val min = runCatching { db.zoneDao().minSort() }.getOrDefault(1)
        runCatching { db.zoneDao().setSort(id, min - 1) }
    }

    suspend fun delete(id: String) {
        runCatching { db.zoneDao().clearMembers(id) }
        db.zoneDao().deleteCustom(id)
    }

    // ---- 分区成员（2026-11-14）----

    /** 把一条记录加进一个自定义分区（已在里面就覆盖时间，等于"最近加入"）。 */
    suspend fun addToZone(zoneId: String, recordId: String) {
        if (zoneId == BuiltinZones.AI_NOTE) return // 札记只收 agent 写的，人工不进
        db.zoneDao().addMember(memore.app.data.model.ZoneRecord(zoneId = zoneId, recordId = recordId))
    }

    suspend fun removeFromZone(zoneId: String, recordId: String) = db.zoneDao().removeMember(zoneId, recordId)

    /** 一条记录所在的自定义分区 id（不含锚点）。 */
    suspend fun zoneIdsOf(recordId: String): Set<String> =
        runCatching { db.zoneDao().zoneIdsOf(recordId).toSet() }.getOrDefault(emptySet())

    /** 记录 id → 所在分区 id 集合。列表页一次取全，避免每张卡查一次库。 */
    suspend fun membershipsByRecord(): Map<String, Set<String>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            db.zoneDao().allMembers().groupBy({ it.recordId }, { it.zoneId })
                .mapValues { it.value.toSet() }
        }.getOrDefault(emptyMap())
    }
}
