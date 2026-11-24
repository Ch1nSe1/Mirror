package memore.app.data.repository

import memore.app.data.local.dao.RecordDao
import memore.app.data.local.dao.TickDao
import memore.app.data.model.EmotionStats
import memore.app.data.model.Record
import memore.app.data.model.SliceEntry
import memore.app.data.model.Tick
import memore.app.data.model.TickSlice
import memore.app.utils.GsonInstance
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
/**
 * 两个时间区间是否重叠。区间语义与记录归属判断一致：`start <= t < end`，end 为 null 表示"至今"。
 *
 * 为什么需要它：重叠刻度会让**口径分裂** —— App 内两条刻度都会把重叠区的记录展示出来，
 * 而导出给外部 AI 时同一条记录只会归给起点更晚的那条（`ExportEngine` 用 firstOrNull 择一）。
 * 重叠本身允许存在，但必须让用户看得见。
 */
fun ticksOverlap(aStart: Long, aEnd: Long?, bStart: Long, bEnd: Long?): Boolean {
    // 零长度或反向区间是空集，不参与重叠（建/改刻度时会被校验拦掉，这里是兜底）
    if (aEnd != null && aEnd <= aStart) return false
    if (bEnd != null && bEnd <= bStart) return false
    val ae = aEnd ?: Long.MAX_VALUE
    val be = bEnd ?: Long.MAX_VALUE
    return aStart < be && bStart < ae
}

/**
 * 秒针 · 刻度仓库。两点核心：
 * 1) 权重：W1 纯时间衰减（半衰期 6 个月，下限 0.05），进行中用 now、终结用 endMs，不落库。
 * 2) 定格切片：终结刻度（endMs 非空）在最终化时，用自身时间区间记录 + 以自身终点为衰减基准算一次并锁存
 *    （slice_snapshot）；改时间段 → 重算重新定格；进行中刻度 → 实时算（now 为基准），不存。
 */
class TickRepository(
    private val tickDao: TickDao,
    private val recordDao: RecordDao
) {

    /** 权重半衰期 = 6 个月（按 30 天计）。 */
    private val HALF_LIFE_MS = 6L * 30L * 24L * 3600L * 1000L

    /** 切片内部衰减半衰期 = 6 个月（刻画"这段自我的整体特征"，不会因 now 塌缩）。 */
    private val SLICE_HALF_LIFE_MS = 6L * 30L * 24L * 3600L * 1000L

    /** 权重下限：老刻度逼近 0 但不归零（少影响≠割除）。 */
    private val WEIGHT_FLOOR = 0.05

    // 情绪强度：完全对齐 EmotionStats.computeTimeDecayedWeight（7 天半衰期 + λ=0.2 置信度）。
    private val DAY_MS = 24L * 3600_000L
    private val EMOTION_HALF_LIFE_DAYS = 7.0
    private val EMOTION_CONFIDENCE_LAMBDA = 0.2

    // ---- 查询 ----

    suspend fun getActive(): List<Tick> = tickDao.getActive()

    suspend fun getDeleted(): List<Tick> = tickDao.getDeleted()

    suspend fun getById(id: String): Tick? = tickDao.getById(id)

    suspend fun activeCount(): Int = tickDao.activeCount()

    /** 某刻度区间的活跃记录数（含增修）。 */
    suspend fun recordCountInRange(startMs: Long, endMs: Long?): Int {
        val end = endMs ?: System.currentTimeMillis()
        return recordDao.countActiveInRange(startMs, end)
    }

    /** 某刻度区间内的活跃记录列表（供刻度记录页）。 */
    suspend fun recordsInRange(startMs: Long, endMs: Long?): List<Record> {
        val end = endMs ?: System.currentTimeMillis()
        return recordDao.getActiveRecordsInRange(startMs, end)
    }

    // ---- 写 ----

    /** 新建刻度（默认无终点 = 进行中）；若直接给了终点则立即定格。 */
    suspend fun create(name: String, startMs: Long, endMs: Long? = null): Tick {
        val tick = Tick(id = java.util.UUID.randomUUID().toString(), name = name.trim(), startMs = startMs, endMs = endMs)
        tickDao.insert(tick)
        refreshSlice(tick.id)
        return tick
    }

    suspend fun rename(id: String, name: String) {
        val t = tickDao.getById(id) ?: return
        tickDao.update(t.copy(name = name.trim(), updatedAt = System.currentTimeMillis()))
    }

    /** 改起止：若变为终结（有终点）→ 重算重新定格；变为进行中 → 清除定格。 */
    suspend fun updateBounds(id: String, startMs: Long, endMs: Long?) {
        val t = tickDao.getById(id) ?: return
        tickDao.update(t.copy(startMs = startMs, endMs = endMs, updatedAt = System.currentTimeMillis()))
        refreshSlice(id)
    }

    /** 恢复已软删刻度。
     * 快照在软删除时是**刻意保留**的（见 [delete]），恢复时必须原样还回来 ——
     * 无条件重算会把"当时的自我"那份定格覆盖成现在，恢复就失去了意义。
     * 只有本来就缺快照（空/损坏）时才补算一次。
     */
    suspend fun restore(id: String) {
        tickDao.restore(id, System.currentTimeMillis())
        val t = tickDao.getById(id)
        if (t != null && t.sliceSnapshot.isNullOrBlank()) refreshSlice(id)
    }

    /** 彻底清除已软删刻度（不可逆），仅对 deleted_at 非空的刻度生效。 */
    suspend fun permanentlyDelete(id: String) {
        tickDao.clearDeleted(id)
    }

    /** 清空所有已软删刻度（不可逆）。 */
    suspend fun clearAllDeleted() {
        tickDao.clearAllDeleted()
    }

    /** 导入用：还原一个刻度（id 冲突则忽略）。 */
    suspend fun importTick(tick: Tick) {
        tickDao.insertIgnore(tick)
    }

    /**
     * 写/改刻度总结（后记）。
     * 走定点 UPDATE：不经过 [updateBounds]，**不会**触发重新定格，
     * 那份 slice_snapshot（"当时的自我"）原封不动。想改多少次改多少次。
     */
    suspend fun updateSummary(id: String, summary: String) {
        tickDao.updateSummary(id, summary)
    }

    /** 把总结换回上一版（两者对调，所以连按两次可以来回对比）。 */
    suspend fun swapSummary(id: String) {
        tickDao.swapSummary(id)
    }

    /** 回看：单独一列，重复保存直接覆盖。 */
    suspend fun updateReview(id: String, review: String) {
        tickDao.updateReview(id, review)
    }

    /**
     * 删除刻度：直接软删除（置 deleted_at），可在回收站恢复。不并刻度、不改起止。
     * 记录仍按各自 created_at 归属，删除只是移除该区间的注解。
     */
    suspend fun delete(id: String) {
        tickDao.softDelete(id, System.currentTimeMillis())
    }

    // ---- 权重 ----

    /**
     * W1 纯时间衰减：weight = max(下限, 0.5^(age/半衰期))。
     * 进行中刻度（endMs=null）用 now 作为终点 → age=0 → weight=1.0；终结刻度用 endMs。
     */
    fun computeWeight(tick: Tick, now: Long = System.currentTimeMillis()): Double {
        val effectiveEnd = tick.endMs ?: now
        val age = max(0L, now - effectiveEnd)
        val w = 0.5.pow(age / HALF_LIFE_MS.toDouble())
        return max(WEIGHT_FLOOR, w)
    }

    // ---- 定格切片 ----

    /**
     * 读取刻度的切片：终结刻度若有快照 → 返回定格快照（不再随 now 重算）；否则实时算。
     * 实时算时：终结刻度以 endMs 为衰减基准（稳定），进行中刻度以 now 为基准。
     */
    suspend fun loadSlice(tick: Tick): TickSlice {
        if (tick.endMs != null && !tick.sliceSnapshot.isNullOrBlank()) {
            return parseTickSlice(tick.sliceSnapshot)
        }
        val records = recordsInRange(tick.startMs, tick.endMs)
        val ref = tick.endMs ?: System.currentTimeMillis()
        return buildSlice(records, tick.startMs, tick.endMs, ref)
    }

    /** 根据当前起止重新定格（有终点→快照；进行中→清空快照）。 */
    suspend fun refreshSlice(id: String) {
        val tick = tickDao.getById(id) ?: return
        if (tick.endMs != null) {
            val records = recordsInRange(tick.startMs, tick.endMs)
            val slice = buildSlice(records, tick.startMs, tick.endMs, tick.endMs)
            tickDao.update(tick.copy(frozenAt = tick.endMs, sliceSnapshot = GsonInstance.gson.toJson(slice), updatedAt = System.currentTimeMillis()))
        } else {
            tickDao.update(tick.copy(frozenAt = null, sliceSnapshot = null, updatedAt = System.currentTimeMillis()))
        }
    }

    /** 一次性回填：重算所有已终结刻度的定格（算法升级后强制用新强度口径刷新，覆盖旧快照）。 */
    suspend fun backfillSlices() {
        getActive().filter { it.endMs != null }.forEach { refreshSlice(it.id) }
    }

    /** 用区间内记录 + 以 referenceMs 为衰减基准，算一次切片。 */
    private suspend fun buildSlice(records: List<Record>, startMs: Long, endMs: Long?, referenceMs: Long): TickSlice {
        val half = SLICE_HALF_LIFE_MS.toDouble()
        // 情绪：完全复刻 EmotionStats.computeTimeDecayedWeight（7 天半衰期 + 置信度），基准=referenceMs。
        // 强度 = 衰减加权均值 × 置信度，与情绪统计功能口径一致。
        val emotionMap = mutableMapOf<String, MutableList<Pair<Long, Float>>>()
        records.forEach { r ->
            EmotionStats.emotionEntries(r).forEach { entry ->
                emotionMap.getOrPut(entry.name) { mutableListOf() }.add(r.createdAt to entry.weight)
            }
        }
        val emotion = emotionMap.map { (name, list) ->
            val count = list.size
            var sumWeighted = 0.0
            var sumDecay = 0.0
            list.forEach { (ts, w) ->
                val daysAgo = ((referenceMs - ts).toDouble() / DAY_MS).coerceAtLeast(0.0)
                val decay = 0.5.pow(daysAgo / EMOTION_HALF_LIFE_DAYS)
                val ew = if (w > 0f) w.toDouble() else 0.5
                sumWeighted += ew * decay
                sumDecay += decay
            }
            val baseWeight = if (sumDecay > 0.0) sumWeighted / sumDecay else 0.5
            val confidence = 1.0 - exp(-EMOTION_CONFIDENCE_LAMBDA * count)
            SliceEntry(normalizeTitle(name), baseWeight * confidence, count)
        }.sortedByDescending { it.weight }
        // 节点已退役（2026-11-06）：切片只保留情绪，不再计算 nodes。
        return TickSlice(referenceMs, emotion, emptyList())
    }

    /** 切片名称规范化：对齐记录导出的别名归一（化名统一），保证切片与记录/分类按名能对上。 */
    private fun normalizeTitle(name: String): String = memore.app.utils.ExportEngine.normalizePersonNames(name)

    // ---- 概念网络切片：2026-10-11 随概念网络一起删除（切片里只留节点那一半） ----

    private fun parseTickSlice(json: String): TickSlice = try {
        GsonInstance.gson.fromJson(json, TickSlice::class.java)
    } catch (_: Exception) {
        TickSlice(System.currentTimeMillis())
    }
}