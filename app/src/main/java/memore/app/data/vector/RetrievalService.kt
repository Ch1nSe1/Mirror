package memore.app.data.vector

import memore.app.data.local.AppDatabase
import memore.app.data.model.Record
import memore.app.data.repository.RecordRepository
import kotlinx.coroutines.flow.first

/**
 * 检索服务（设计文档 §7）：向量 + FTS 双路召回，RRF 融合，再做 MMR 去重。
 *
 * 2026-11-21：删掉了「AI 短查询扩展」——手机端搜索是低频操作，不值得为它花 API。
 */
class RetrievalService(
    private val db: AppDatabase,
    private val recordRepository: RecordRepository,
    private val provider: EmbeddingProvider?
) {
    data class Hit(val record: Record, val score: Float, val snippet: String? = null)

    /** 搜索结果：记录列表 + 是否"只是联想"（分数太低）。 */
    data class Outcome(
        val records: List<Record>,
        val weak: Boolean
    )

    private data class Cache(val model: String, val byRecord: Map<String, List<FloatArray>>)

    @Volatile
    private var cache: Cache? = null

    val available: Boolean get() = provider != null

    fun invalidate() { cache = null }

    private suspend fun vectors(): Map<String, List<FloatArray>> {
        val p = provider ?: return emptyMap()
        cache?.let { if (it.model == p.model) return it.byRecord }
        val rows = db.vectorDao().allForModel(p.model)
        val grouped = rows.groupBy { it.recordId }
            .mapValues { (_, list) ->
                list.sortedBy { it.chunkIndex }.map { VectorIndexService.bytesToFloats(it.vec) }
            }
        cache = Cache(p.model, grouped)
        return grouped
    }

    /** 和某条记录相似的记录（排除自己、已删、加密），MMR 去重。 */
    suspend fun similar(recordId: String, k: Int = 5): List<Hit> {
        val p = provider ?: return emptyList()
        val all = vectors()
        val mine = all[recordId] ?: return emptyList()
        if (mine.isEmpty()) return emptyList()

        val scored = ArrayList<Triple<String, Float, Int>>()
        for ((id, chunks) in all) {
            if (id == recordId) continue
            var best = -1f
            var bestIdx = 0
            for (a in mine) for ((bi, b) in chunks.withIndex()) {
                val s = VectorMath.cosine(a, b)
                if (s > best) { best = s; bestIdx = bi }
            }
            if (best > 0f) scored += Triple(id, best, bestIdx)
        }
        if (scored.isEmpty()) return emptyList()

        val bestChunk = HashMap<String, Int>()
        val candidates = scored.sortedByDescending { it.second }.take(k * 3).map { (id, score, chunkIdx) ->
            bestChunk[id] = chunkIdx
            Ranking.Item(id = id, score = score, vec = all[id]?.getOrNull(chunkIdx))
        }
        val picked = Ranking.mmr(candidates, k)
        val records = recordRepository.getRecordsByIds(picked.map { it.id })
            .filter { !it.isDeleted && !it.isLocked && it.correctionOfId == null }
            .associateBy { it.id }
        return picked.mapNotNull { item ->
            records[item.id]?.let { rec ->
                val chunks = runCatching { TextChunker.chunk(rec.content) }.getOrDefault(emptyList())
                val snippet = chunks.getOrNull(bestChunk[item.id] ?: -1)?.takeIf { it.isNotBlank() }
                Hit(rec, item.score, snippet)
            }
        }
    }

    /**
     * 混合检索：先（可选）扩展查询，向量 Top20 + FTS Top20 → RRF → MMR → Top[k]。
     */
    suspend fun searchOutcome(query: String, k: Int = 10): Outcome {
        if (query.isBlank()) return Outcome(emptyList(), weak = false)
        val p = provider ?: return Outcome(emptyList(), weak = false)

        val queries = listOf(query)

        // 向量召回：多查询取每块最高分
        val chunkScores: Map<String, Float> = runCatching {
            val qVecs = p.embed(queries, isQuery = true)
            val all = vectors()
            val best = HashMap<String, Float>()
            for ((recordId, chunks) in all) {
                var bestScore = -1f
                for (c in chunks) {
                    for (q in qVecs) {
                        val s = VectorMath.cosine(q, c)
                        if (s > bestScore) bestScore = s
                    }
                }
                if (bestScore > 0f) best[recordId] = bestScore
            }
            best
        }.getOrDefault(emptyMap())

        val vectorRanked = chunkScores.entries.sortedByDescending { it.value }.take(20).map { it.key }
        val topScore = chunkScores.values.maxOrNull() ?: 0f
        val weak = topScore < Ranking.WEAK_SCORE

        // 排序（2026-11-06 定稿）：语义分 + 词法分。
        // 之前那版是「向量 + FTS 用 RRF 融合」，但 FTS4 不认中文（整句一个 token），
        // 一路从来没贡献过分；换成字符 bigram 后实测模糊型 R@1 0.55 → 0.72。
        val queryBigrams = CharacterBigram.bigrams(query)
        val vectorTop = chunkScores.entries.sortedByDescending { it.value }.take(60).map { it.key }

        // 词法召回也走**全库**（2026-11-13 修「搜原文，原记录却排五六名」）：
        // 原来词法只在"向量前 60 名"里重排，原文命中但向量分不高的记录根本进不了候选池。
        val lightAll = runCatching { recordRepository.getAllActiveLight() }
            .getOrDefault(emptyList())
            .filter { !it.isDeleted && !it.isLocked }
        val lexByAll = lightAll.associate { it.id to CharacterBigram.score(queryBigrams, it.content) }
        val lexicalTop = lexByAll.entries
            .filter { it.value > 0f }
            .sortedByDescending { it.value }
            .take(60)
            .map { it.key }
        val candidates = (vectorTop + lexicalTop).distinct()

        val texts = runCatching {
            recordRepository.getRecordsByIds(candidates)
        }.getOrDefault(emptyList()).filter { !it.isDeleted && !it.isLocked }.associate { it.id to it.content }
        val needle = query.trim()
        val ranked = candidates
            .map { id ->
                val sim = chunkScores[id] ?: 0f
                val doc = texts[id] ?: ""
                val lex = if (doc.isNotEmpty()) CharacterBigram.score(queryBigrams, doc) else 0f
                // 原文整段命中 = 强信号：+1.5，压过任何语义分（语义分在 0..1）
                val exact = if (needle.length >= 2 && doc.contains(needle, ignoreCase = true)) 1.5f else 0f
                id to (sim + CharacterBigram.WEIGHT * lex + exact)
            }
            .sortedByDescending { it.second }

        if (ranked.isEmpty()) {
            return Outcome(emptyList(), weak = true)
        }

        val all = vectors()
        // 近重复只跳过，不动顺序
        val kept = ArrayList<Pair<String, Float>>(ranked.size)
        for ((id, score) in ranked) {
            val v = all[id]?.firstOrNull()
            if (v == null) { kept += id to score; continue }
            val dup = kept.any { (kId, _) ->
                val kv = all[kId]?.firstOrNull()
                kv != null && VectorMath.cosine(kv, v) >= 0.95f
            }
            if (!dup) kept += id to score
        }

        val records = recordRepository.getRecordsByIds(kept.map { pair -> pair.first }).associateBy { it.id }
        val scoreById = kept.toMap()
        val ordered = collapseSeriesByScore(kept.mapNotNull { pair -> records[pair.first] }, scoreById)
        return Outcome(ordered.take(k), weak = weak)
    }

    suspend fun search(query: String, k: Int = 10): List<Hit> =
        searchOutcome(query, k).records.map { Hit(it, 0f) }
    /**
     * 同一系列的记录只留一条（2026-11-05）。
     *
     * 实测库里有「2026-7-10——2026-7-15 第一/二/三部分」这种连载，一次查询会把整段
     * 五条全返回，看起来像重复、也挤掉了别的结果。这里按标题前缀折叠，保留组里最长的。
     */
    private fun collapseSeries(records: List<Record>): List<Record> {
        if (records.size < 2) return records
        val keepers = runCatching {
            SeriesDetector.keepers(records.associate { it.id to it.content })
        }.getOrDefault(emptyMap())
        if (keepers.isEmpty()) return records
        val seen = HashSet<String>()
        val out = ArrayList<Record>(records.size)
        for (r in records) {
            val keepId = keepers[r.id] ?: r.id
            if (!seen.add(keepId)) continue
            out += records.firstOrNull { it.id == keepId } ?: r
        }
        return out
    }
    /** 系列折叠（按分数选代表）：保持顺序，组代表取**得分最高**的那条（不是最长）。 */
    private fun collapseSeriesByScore(records: List<Record>, scoreById: Map<String, Float>): List<Record> {
        if (records.size < 2) return records
        val keepers = runCatching {
            SeriesDetector.keepers(records.associate { it.id to it.content })
        }.getOrDefault(emptyMap())
        if (keepers.isEmpty()) return records
        // 组代表 = 组内分数最高者
        val bestOfGroup = HashMap<String, String>()
        for ((id, keep) in keepers) {
            val cand = id
            val cur = bestOfGroup[keep]
            if (cur == null || (scoreById[cand] ?: 0f) > (scoreById[cur] ?: 0f)) bestOfGroup[keep] = cand
        }
        val seen = HashSet<String>()
        val byId = records.associateBy { it.id }
        val out = ArrayList<Record>(records.size)
        for (r in records) {
            val keepId = keepers[r.id] ?: r.id
            val best = bestOfGroup[keepId] ?: keepId
            if (!seen.add(best)) continue
            out += byId[best] ?: r
        }
        return out
    }
}
