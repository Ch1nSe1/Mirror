package memore.app.data.vector

import android.content.Context
import memore.app.data.local.AppDatabase
import memore.app.data.repository.RecordRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 按月主题的服务层（2026-11-07）。
 *
 * 职责：取记录 + 向量 → [MonthlyTopics.build] 聚类 → 命名 → **缓存到 files/monthly_topics.json**。
 *
 * 命名只跑一次（缓存按 [MonthlyTopics.fingerprint] 失效），所以第一次进地图会慢几秒，
 * 之后直接读缓存。UI 侧在首次生成时显示加载波形即可。
 *
 * 命名策略：优先 [namer]（接 AI 命名），失败或为空时回退到 [MonthlyTopics.fallbackKeyword]。
 */
class MonthlyTopicsService(
    private val context: Context,
    private val repository: RecordRepository,
    private val db: AppDatabase,
    /** 命名器：一次拿一个月的全部簇，返回与之一一对应的名字（批量，避免同月重名）。 */
    private val namer: (suspend (seeds: List<ClusterSeed>, anchors: List<String>) -> List<String>)? = null
) {

    /** 一次给一个簇的输入（批量命名用）。 */
    data class ClusterSeed(
        val samples: List<String>,
        val keywords: String,
        /** 2026-11-22：这一簇的概念（命名用）。 */
        val concepts: List<String> = emptyList()
    )

    private val cacheFile = File(context.filesDir, "monthly_topics.json")

    data class NamedCluster(
        val name: String,
        val count: Int,
        val recordIds: List<String>,
        /** 代表句：离质心最近那条记录的第一句（给线索对齐看内容用）。 */
        val summary: String = "",
        /** 关键词：簇内高频词（对齐时给模型看"这批在讲什么"）。 */
        val keywords: String = ""
    )
    data class NamedMonth(val month: String, val clusters: List<NamedCluster>)
    data class Snapshot(val fingerprint: String, val months: List<NamedMonth>)

    /** 读缓存；指纹不符或没有缓存时返回 null。 */
    fun cached(): Snapshot? = runCatching {
        if (!cacheFile.exists()) return null
        val root = JSONObject(cacheFile.readText())
        val fp = root.optString("fingerprint", "")
        val arr = root.optJSONArray("months") ?: return null
        val months = ArrayList<NamedMonth>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val cl = m.optJSONArray("clusters") ?: JSONArray()
            val clusters = ArrayList<NamedCluster>()
            for (j in 0 until cl.length()) {
                val c = cl.getJSONObject(j)
                val ids = c.optJSONArray("ids") ?: JSONArray()
                clusters += NamedCluster(
                    name = c.optString("name", ""),
                    count = c.optInt("count", 0),
                    recordIds = (0 until ids.length()).map { ids.getString(it) },
                    summary = c.optString("summary", ""),
                    keywords = c.optString("keywords", "")
                )
            }
            // 2026-11-19：老缓存里可能带着同月重名（命名是一簇一次时代的产物），
            // 读取时合并一次，用户不必为了去重而重新调用 AI。
            months += mergeSameNameClusters(NamedMonth(m.optString("month", ""), clusters))
        }
        Snapshot(fp, months)
    }.getOrNull()

    private fun fingerprintOf(records: List<WordRecord>): String {
        val sb = StringBuilder()
        // 叙事分量（2026-11-17）：权重变化也要让缓存失效，否则改完来源权重主题不重算
        records.groupBy { it.month }.toSortedMap().forEach { (m, l) ->
            sb.append(m).append(':').append(l.size).append('@').append("\"%.2f".format(l.sumOf { it.weight.toDouble() })).append('|')
        }
        return sb.toString()
    }

    data class WordRecord(val id: String, val month: String, val text: String, val vec: FloatArray, val weight: Float = 1f)

    /** 实际计算：聚类 + 命名 + 写缓存。没有向量数据时返回空表。 */
    suspend fun build(force: Boolean = false): Snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // 叙事分量（2026-11-17 来源系统）：权重 0 的来源不参与话题聚类；
        // 0<w<1 参与，但在加权均值里只按 w 的分量计入（cluster 质心与 k-means 都按它加权）。
        val narr = runCatching { repository.originNarrativeWeights() }.getOrDefault(emptyMap())
        val records = try { repository.getAllActiveRecords() } catch (e: Exception) { android.util.Log.e("MonthlyTopics", "getAllActiveRecords failed", e); emptyList() }
            .filter { !it.isLocked && it.content.length >= 20 }
            .filter { r -> (r.originId?.let { narr[it] } ?: 1f) > 0f }

        val points = ArrayList<WordRecord>(records.size)
        for (r in records) {
            val chunks = try { db.vectorDao().byRecord(r.id) } catch (e: Exception) { android.util.Log.e("MonthlyTopics", "byRecord failed " + r.id, e); emptyList() }
            if (chunks.isEmpty()) continue
            val vectors = chunks.mapNotNull { rv ->
                runCatching { VectorIndexService.bytesToFloats(rv.vec) }.getOrNull()
            }
            if (vectors.isEmpty()) continue
            val mean = MonthlyTopics.normalize(MonthlyTopics.mean(vectors))
            // 本地时区按 +8h 处理（与 App 其它地方一致）
            points += WordRecord(r.id, MonthlyTopics.monthKey(r.createdAt, 8 * 3_600_000L), r.content, mean,
                r.originId?.let { narr[it] } ?: 1f)
        }

        val conceptIndex = buildConceptIndex()

        val fp = fingerprintOf(points)
        if (!force) { val c = cached(); if (c != null && c.fingerprint == fp) return@withContext c }

        android.util.Log.i("MonthlyTopics", "records=" + records.size + " points=" + points.size)
        val built = try { MonthlyTopics.build(
            points.map { MonthlyTopics.RecordPoint(it.id, it.month, it.vec) },
            neighbors = buildNeighbors()
        ) } catch (e: Exception) { android.util.Log.e("MonthlyTopics", "build failed", e); emptyList() }
        android.util.Log.i("MonthlyTopics", "months=" + built.size + " clusters=" + built.sumOf { it.clusters.size })
        val textById = points.associate { it.id to it.text }
        val vecById = points.associate { it.id to it.vec }
        // ③ 命名加锚（2026-11-19）：把已有线索名带进命名，名字不再月月换
        val anchors = runCatching { TopicThreads.cached(context)?.second?.map { it.name } ?: emptyList() }
            .getOrDefault(emptyList())

        // 2026-11-17：AI 命名失败时**不写缓存**。
        // 旧行为把 fallbackKeyword 拼的名字永久写进按月缓存 —— 一次网络抖动就会让
        // 「关系演变分析」变成「关系/演变」，而且指纹没变就永远不重试。
        var aiFailed = false
        val named = built.map { mr ->
            // 2026-11-19：改为**按月批量命名**。原来一簇一次调用，模型看不到同月其它簇，
            // 于是能给出两个一样的名字（加上命名锚之后更严重）。一次发整月的簇，并在提示词里
            // 明确要求名字不得重复；模型仍重复时，按下文合并成一簇，不把重名留在界面上。
            val samplesOf = mr.clusters.map { c -> c.recordIds.take(6).mapNotNull { textById[it]?.take(300) } }
            val keywordsOf = samplesOf.map { smp ->
                MonthlyTopics.fallbackKeyword(
                    smp.ifEmpty { mr.clusters[0].recordIds.take(6).mapNotNull { textById[it] } }
                )
            }
            val seeds = samplesOf.mapIndexed { i, smp ->
                ClusterSeed(
                    samples = smp,
                    keywords = keywordsOf[i],
                    concepts = conceptNamesOf(mr.clusters[i].recordIds, conceptIndex)
                )
            }
            val aiNames = if (namer != null && seeds.any { it.samples.isNotEmpty() }) {
                runCatching { namer?.invoke(seeds, anchors) }.getOrNull().orEmpty()
            } else emptyList()
            if (namer != null && seeds.any { it.samples.isNotEmpty() } && aiNames.size != seeds.size) aiFailed = true

            val clusters = mr.clusters.mapIndexed { i, c ->
                val samples = samplesOf[i]
                val aiName = aiNames.getOrNull(i)?.trim().orEmpty()
                val name = aiName.ifBlank {
                    MonthlyTopics.fallbackKeyword(samples.ifEmpty { c.recordIds.mapNotNull { textById[it] } })
                }
                val nearest = MonthlyTopics.nearestMemberId(c.recordIds, vecById, c.centroid)
                val summary = nearest?.let { textById[it] }?.let { MonthlyTopics.firstSentence(it) }.orEmpty()
                val keywords = keywordsOf[i]
                NamedCluster(name, c.recordIds.size, c.recordIds, summary = summary, keywords = keywords)
            }
            mergeSameNameClusters(NamedMonth(mr.month, clusters))
        }

        val root = JSONObject()
        root.put("fingerprint", fp)
        val arr = JSONArray()
        named.forEach { m ->
            val mo = JSONObject().put("month", m.month)
            val cl = JSONArray()
            m.clusters.forEach { c ->
                cl.put(
                    JSONObject()
                        .put("name", c.name)
                        .put("count", c.count)
                        .put("ids", JSONArray(c.recordIds))
                        .put("summary", c.summary)
                        .put("keywords", c.keywords)
                )
            }
            mo.put("clusters", cl)
            arr.put(mo)
        }
        root.put("months", arr)
        if (aiFailed) {
            android.util.Log.w("MonthlyTopics", "AI 命名失败，未写缓存")
            throw IllegalStateException("AI 命名失败")
        }
        runCatching { cacheFile.writeText(root.toString()) }

        Snapshot(fp, named)
    }

    /** 显式刷新：跳过指纹直接重算（主题页的「刷新」按钮用）。 */
    suspend fun refresh(): Snapshot = build(force = true)

    /** 概念索引：记录 → 概念集合 + 文档频次，只用于命名。 */
    private data class ConceptIndex(
        val byRecord: Map<String, Set<String>>,
        val df: Map<String, Int>,
        val total: Int
    )

    /** 记录 → 概念（别名合并后），用于给每个簇挑概念。 */
    private suspend fun buildConceptIndex(): ConceptIndex {
        val canon = HashMap<String, String>()
        runCatching {
            memore.app.data.repository.AliasMappingRepository(context).load()
        }.getOrDefault(emptyList()).forEach { g ->
            val std = g.standardName.trim()
            if (std.isEmpty()) return@forEach
            canon[std.lowercase()] = std
            g.aliases.forEach { a -> a.trim().takeIf { it.isNotEmpty() }?.let { canon[it.lowercase()] = std } }
        }
        val byRecord = HashMap<String, MutableSet<String>>()
        runCatching {
            db.recordEntityDao().allWithRecord()
        }.getOrDefault(emptyList()).forEach { row ->
            if (row.isDeleted || row.isLocked) return@forEach
            val raw = row.entityName.trim()
            if (raw.isEmpty()) return@forEach
            val name = canon[raw.lowercase()] ?: raw
            byRecord.getOrPut(row.recordId) { mutableSetOf() }.add(name)
        }
        val df = HashMap<String, Int>()
        byRecord.values.forEach { names -> names.forEach { df[it] = (df[it] ?: 0) + 1 } }
        return ConceptIndex(byRecord.mapValues { it.value.toSet() }, df, byRecord.size.coerceAtLeast(1))
    }

    private fun conceptNamesOf(ids: List<String>, index: ConceptIndex): List<String> {
        val cnt = HashMap<String, Int>()
        ids.forEach { rid -> index.byRecord[rid]?.forEach { c -> cnt[c] = (cnt[c] ?: 0) + 1 } }
        val limit = index.total * 0.3
        return cnt.entries
            .filter { (index.df[it.key] ?: 0) <= limit }
            .sortedByDescending { e ->
                val df = (index.df[e.key] ?: 1).coerceAtLeast(1)
                kotlin.math.ln(1.0 + index.total.toDouble() / df) * e.value
            }
            .take(6)
            .map { it.key }
    }

    private suspend fun buildNeighbors(): Map<String, Set<String>> {
        val rels = runCatching { db.recordRelationDao().all() }.getOrDefault(emptyList())
            .filter { it.validTo == null }
        val map = HashMap<String, MutableSet<String>>()
        rels.forEach { r ->
            if (r.fromId == r.toId) return@forEach
            map.getOrPut(r.fromId) { mutableSetOf() }.add(r.toId)
            map.getOrPut(r.toId) { mutableSetOf() }.add(r.fromId)
        }
        return map.mapValues { it.value.toSet() }
    }

    /**
     * 同月重名合并（2026-11-19）：模型仍然给出重复名字时，把它们合成一簇。
     * 2026-11-22：判重时忽略空白（「Mirror项目 / Mirror 项目」算同一个）。
     */
    internal fun mergeSameNameClusters(month: NamedMonth): NamedMonth {
        val merged = LinkedHashMap<String, NamedCluster>()
        month.clusters.forEach { c ->
            // 2026-11-22：判重前去掉空白，避免「Mirror项目 / Mirror 项目」算两个。
            val key = c.name.replace(" ", "").replace("\u3000", "")
            val exist = merged[key]
            merged[key] = if (exist == null) c else exist.copy(
                count = exist.count + c.count,
                recordIds = (exist.recordIds + c.recordIds).distinct()
            )
        }
        return month.copy(clusters = merged.values.toList())
    }
}
