package memore.app.data.vector

import memore.app.data.local.AppDatabase
import memore.app.data.repository.RecordRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 上下文装配（2026-11-05）：把"找相关"收敛成一处，上层按用途取。
 *
 *  - 建关联边 → [linkCandidates]（时间分桶，保证"跨年"的候选能进来）
 *  - RAG（下一步）→ 同一份候选，拼成文本喂给 AI
 *
 * 为什么单独一层：RAG 和关联边是**同一次检索的两种用途**（一次是临时上下文，一次是持久结构），
 * 分散在两边写两套检索，迟早会出现"搜索准、建边不准"这种分裂。
 */
class ContextBuilder(
    private val db: AppDatabase,
    @Suppress("unused") private val recordRepository: RecordRepository,
    private val retrieval: RetrievalService
) {

    data class Candidate(
        val id: String,
        val text: String,
        val score: Float,
        val month: String
    )

    companion object {
        /** 每个月份桶最多取几条：2 条足够"同时期"的代表性，又不至于挤掉跨年的候选。 */
        const val PER_MONTH = 2
        /** 先扩大候选再分桶，否则分桶后可能凑不满。 */
        const val POOL = 24

        /**
         * 关联准入阈值的全局兜底（2026-11-17）。
         *
         * 真机数据校准：321 条记录、bge-small-zh，top8 相似度的中位数分别是
         * 原创 0.705 / 对话 0.748 / 转录 0.821 —— 旧代码**没有任何阈值**，
         * 等于"只要相似就算"，所以 703 条 AI 边里混着大量勉强相关。
         * 来源页可以按来源覆盖这个值（Origin.linkThreshold）；这里只兜底未标来源。
         */
        const val DEFAULT_LINK_THRESHOLD = 0.70f
    }

    private val monthFmt = SimpleDateFormat("yyyy-MM", Locale.CHINA)

    /**
     * 给某条记录找"可能相关"的旧记录。
     *
     * 步骤：向量 Top-24（已含 MMR 去重）→ 按月份分桶，每桶最多 [PER_MONTH] 条 → 取前 limit。
     * 时间分桶是这个方法存在的理由：不做的话 Top-N 常常全是同一天的连续段落，
     * 永远发现不了"三年前的同一件事"。
     */
    suspend fun linkCandidates(recordId: String, limit: Int = 8): List<Candidate> {
        val hits = runCatching { retrieval.similar(recordId, k = POOL) }.getOrDefault(emptyList())
        // 2026-11-17：来源系统改成两段——
        //   准入 = 相似度 ≥ 该来源的 linkThreshold（阈值，1.0 = 永不进）
        //   排序 = 相似度 × wLink（结构权重），并按有效分降序
        // 旧版只有 w<=0 的排除、没有任何门槛，等于"像就进"；来源的 0.8/0.5 只写进边、不参与筛选。
        val origins = runCatching { db.originDao().all().associateBy { it.id } }.getOrDefault(emptyMap())
        fun thresholdOf(record: memore.app.data.model.Record): Float =
            record.originId?.let { origins[it]?.linkThreshold } ?: DEFAULT_LINK_THRESHOLD
        fun linkWeight(record: memore.app.data.model.Record): Float =
            record.originId?.let { origins[it]?.wLink } ?: 1f

        // 先按"有效分"排好，再进分桶——顺序是筛选结果，不是检索原始序
        val ranked = hits.asSequence()
            .filter { it.record.id != recordId }
            .filter { !it.record.isDeleted && !it.record.isLocked }
            .mapNotNull { h ->
                val w = linkWeight(h.record)
                if (w <= 0f) return@mapNotNull null
                if (h.score < thresholdOf(h.record)) return@mapNotNull null
                h to (h.score * w)
            }
            .sortedByDescending { it.second }
            .toList()

        val perBucket = HashMap<String, Int>()
        val out = ArrayList<Candidate>(limit)
        // 同一系列只取一条候选：否则 8 个候选里可能有 4 条是同一天的连载
        val seriesSeen = HashSet<String>()
        for ((h, effective) in ranked) {
            val month = monthFmt.format(Date(h.record.createdAt))
            val used = perBucket[month] ?: 0
            if (used >= PER_MONTH) continue
            val sk = SeriesDetector.seriesKey(h.record.content)
            if (sk.isNotEmpty() && !seriesSeen.add(sk)) continue
            perBucket[month] = used + 1
            val text = (h.snippet?.takeIf { it.isNotBlank() } ?: h.record.content)
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.isEmpty()) continue
            out += Candidate(id = h.record.id, text = text.take(140), score = effective, month = month)
            if (out.size >= limit) break
        }

        // 锚点候选（2026-11-19）：当前正文里出现的已知锚点（人名/作品/地点/事件），
        // 把"提到同一个锚点"的旧记录补进候选。这是确定性信号，比向量相似度硬。
        if (out.size < limit) {
            runCatching {
                val dao = db.recordEntityDao()
                val current = recordRepository.getRecordById(recordId) ?: return@runCatching
                val known = dao.allNames().filter { it.length >= 2 && !it.contains('\n') }
                val lowerContent = current.content.lowercase()
                val matched = known.filter { lowerContent.contains(it.lowercase()) }.take(12)
                if (matched.isNotEmpty()) {
                    memore.app.utils.LogUtil.i("AI-Anchors 命中 " + matched.size + " 个锚点: " + matched.joinToString("、"))
                }
                if (matched.isEmpty()) return@runCatching
                val ids = dao.recordsMentioning(matched, recordId)
                if (ids.isEmpty()) return@runCatching
                val records = recordRepository.getRecordsByIds(ids)
                    .filter { !it.isDeleted && !it.isLocked }
                    .sortedByDescending { it.createdAt }
                for (r in records) {
                    if (out.size >= limit) break
                    if (out.any { it.id == r.id }) continue
                    val month = monthFmt.format(Date(r.createdAt))
                    if ((perBucket[month] ?: 0) >= PER_MONTH) continue
                    val sk = SeriesDetector.seriesKey(r.content)
                    if (sk.isNotEmpty() && !seriesSeen.add(sk)) continue
                    perBucket[month] = (perBucket[month] ?: 0) + 1
                    val text = r.content.replace(Regex("\\s+"), " ").trim()
                    if (text.isEmpty()) continue
                    out += Candidate(id = r.id, text = text.take(140), score = 0.99f, month = month)
                }
            }
        }
        return out
    }
    /**
     * 「回看」用：一段区间内的记录 + 区间外与之相关的旧记录。
     *
     * 为什么要有区间外的那部分：只把这段时间重新读一遍，AI 说不出任何新东西；
     * 只有把"和过去呼应的地方"摆出来，回看才有意义（这就是 RAG 相比"重新读一遍"的全部价值）。
     */
    data class ReviewContext(
        val inRange: List<Candidate>,
        val related: List<Candidate>,
        val totalInRange: Int
    )

    suspend fun reviewContext(startMs: Long, endMs: Long, sample: Int = 14): ReviewContext {
        val all = runCatching { recordRepository.getAllActiveRecords() }.getOrDefault(emptyList())
        val inRange = all
            .filter { it.createdAt in startMs..endMs && !it.isDeleted && !it.isLocked && it.correctionOfId == null }
            .sortedBy { it.createdAt }
        if (inRange.isEmpty()) return ReviewContext(emptyList(), emptyList(), 0)

        // 均匀抽样：首尾都要，中间等距（避免只剩最近几天）
        val picked = if (inRange.size <= sample) inRange else {
            (0 until sample).map { i ->
                inRange[(i.toLong() * (inRange.size - 1) / (sample - 1)).toInt()]
            }.distinctBy { it.id }
        }
        val inCandidates = picked.mapNotNull { r ->
            val text = r.content.replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) null
            else Candidate(r.id, text.take(160), 0f, monthFmt.format(Date(r.createdAt)))
        }

        // 来源（2026-11-17）：回顾的"区间外相关"同样按 准入阈值 + 结构权重 过滤/缩放。
        val reviewOrigins = runCatching { db.originDao().all().associateBy { it.id } }.getOrDefault(emptyMap())
        fun reviewWeight(r: memore.app.data.model.Record): Float =
            r.originId?.let { reviewOrigins[it]?.wLink } ?: 1f
        fun reviewThreshold(r: memore.app.data.model.Record): Float =
            r.originId?.let { reviewOrigins[it]?.linkThreshold } ?: DEFAULT_LINK_THRESHOLD

        // 区间外的相关记录：拿最近 3 条去检索，去重后取 4 条
        val idsInRange = inRange.map { it.id }.toSet()
        val related = LinkedHashMap<String, Candidate>()
        inRange.takeLast(3).forEach { seed ->
            runCatching { retrieval.similar(seed.id, k = 8) }.getOrDefault(emptyList()).forEach { hit ->
                val r = hit.record
                if (r.id in idsInRange || r.isDeleted || r.isLocked) return@forEach
                if (reviewWeight(r) <= 0f) return@forEach
                if (hit.score < reviewThreshold(r)) return@forEach
                if (related.containsKey(r.id)) return@forEach
                val text = (hit.snippet ?: r.content).replace(Regex("\\s+"), " ").trim()
                if (text.isEmpty()) return@forEach
                related[r.id] = Candidate(r.id, text.take(120), hit.score * reviewWeight(r), monthFmt.format(Date(r.createdAt)))
            }
        }
        // 加权后要重排：原来按检索顺序取前 4，缩放过的分数排不上去了
        return ReviewContext(inCandidates, related.values.sortedByDescending { it.score }.take(4), inRange.size)
    }
}
