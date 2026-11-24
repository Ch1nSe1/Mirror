package memore.app.data.vector

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 按月主题（2026-11-07，B 方案：每月独立主题，不跨月连线）。
 *
 * 为什么不做跨月连线：实测（`tools/tmp-import/tracks_ab.txt`）里，两段不同时期的文字
 * 会被连在一起——只是向量距离近，不是同一件事。连续体数据没有天然边界，
 * 强行连线会让人误以为“这件事一路延续下来”。
 *
 * 所以：每月单独聚类、单独命名，地图只显示“这个月有哪几个主题、各多少条”。
 * 变化通过“每月主题构成的差异”看出来，而不是通过画线。
 *
 * 本文件只做**纯计算**（分桶、k-means、轮廓系数），不碰 IO、不调 AI，便于单测。
 */
object MonthlyTopics {

    data class RecordPoint(val id: String, val month: String, val vec: FloatArray, val weight: Float = 1f)

    data class Cluster(val recordIds: List<String>, val centroid: FloatArray)

    data class MonthResult(val month: String, val clusters: List<Cluster>)

    /** 月份键：yyyy-MM（按本地时区）。 */
    fun monthKey(epochMs: Long, zoneOffsetMs: Long = 0L): String {
        val days = (epochMs + zoneOffsetMs) / 86_400_000L
        var y = 1970
        var d = days
        while (true) {
            val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
            val len = if (leap) 366 else 365
            if (d < len) break
            d -= len; y++
        }
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        val lens = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var m = 0
        while (d >= lens[m]) { d -= lens[m]; m++ }
        return "%04d-%02d".format(y, m + 1)
    }

    fun mean(vectors: List<FloatArray>, weights: List<Float>? = null): FloatArray {
        if (vectors.isEmpty()) return FloatArray(0)
        val out = FloatArray(vectors[0].size)
        var sumW = 0f
        vectors.forEachIndexed { idx, v ->
            val w = weights?.getOrNull(idx)?.takeIf { it > 0f } ?: 1f
            for (i in out.indices) out[i] += v[i] * w
            sumW += w
        }
        val denom = if (sumW > 0f) sumW else vectors.size.toFloat()
        for (i in out.indices) out[i] = out[i] / denom
        return out
    }

    fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val len = Math.sqrt(n).toFloat().coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / len }
    }

    /** 去当月均值再归一：实测不去均值会让 7 月和 9 月的簇质心相似度变成 −0.68。 */
    fun center(points: List<RecordPoint>): List<FloatArray> {
        val m = mean(points.map { it.vec }, points.map { it.weight })
        return points.map { normalize(FloatArray(it.vec.size) { i -> it.vec[i] - m[i] }) }
    }

    /** k-means++（固定种子，结果可复现）。 */
    fun kmeans(points: List<FloatArray>, k: Int, seed: Long = 7L, iters: Int = 40, weights: FloatArray? = null): Pair<IntArray, List<FloatArray>> {
        if (points.isEmpty()) return IntArray(0) to emptyList()
        val kk = k.coerceIn(1, points.size)
        val rnd = java.util.Random(seed)
        val centroids = ArrayList<FloatArray>(kk)
        centroids += points[rnd.nextInt(points.size)].copyOf()
        while (centroids.size < kk) {
            // 2026-11-22 修：原来这里算了距离却永远取 points[0]（idx 恒 0），
            // 初始化中心全重复 → 真机每月常只剩 3~4 个非空簇。按距离加权抽取。
            val d = points.map { p -> centroids.minOf { c -> dist2(p, c) } }
            val sum = d.sumOf { it.toDouble() }.coerceAtLeast(1e-9)
            val r = rnd.nextDouble() * sum
            var idx = points.size - 1
            var acc = 0.0
            for (i in d.indices) {
                acc += d[i]
                if (acc >= r) { idx = i; break }
            }
            centroids += points[idx].copyOf()
        }
        val labels = IntArray(points.size)
        repeat(iters) {
            var changed = false
            for (i in points.indices) {
                var best = 0
                var bd = Float.MAX_VALUE
                for (j in centroids.indices) {
                    val dd = dist2(points[i], centroids[j])
                    if (dd < bd) { bd = dd; best = j }
                }
                if (labels[i] != best) { labels[i] = best; changed = true }
            }
            for (j in centroids.indices) {
                val memberIdx = points.indices.filter { labels[it] == j }
                if (memberIdx.isNotEmpty()) {
                    val m = mean(
                        memberIdx.map { points[it] },
                        weights?.let { w -> memberIdx.map { w[it] } }
                    )
                    centroids[j] = normalize(m)
                }
            }
            if (!changed) return labels to centroids
        }
        return labels to centroids
    }

    private fun dist2(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) { val d = a[i] - b[i]; s += d * d }
        return s
    }

    /** 轮廓系数（用于选 k）。样本多时抽样，避免 O(n²) 拖慢。 */
    fun silhouette(points: List<FloatArray>, labels: IntArray, sample: Int = 60): Float {
        val n = points.size
        if (n < 3 || labels.toSet().size < 2) return -1f
        val idx = if (n <= sample) points.indices.toList() else points.indices.shuffled(java.util.Random(3)).take(sample)
        val ks = labels.toSet()
        var total = 0.0
        var cnt = 0
        for (i in idx) {
            var sameSum = 0.0; var sameCnt = 0
            val otherSum = HashMap<Int, Double>()
            val otherCnt = HashMap<Int, Int>()
            for (j in points.indices) {
                if (i == j) continue
                val d = Math.sqrt(dist2(points[i], points[j]).toDouble())
                if (labels[j] == labels[i]) { sameSum += d; sameCnt++ }
                else { otherSum[labels[j]] = (otherSum[labels[j]] ?: 0.0) + d; otherCnt[labels[j]] = (otherCnt[labels[j]] ?: 0) + 1 }
            }
            val a = if (sameCnt > 0) sameSum / sameCnt else 0.0
            var b = Double.MAX_VALUE
            for (kk in ks) {
                if (kk == labels[i]) continue
                val c = otherCnt[kk] ?: 0
                if (c > 0) b = minOf(b, (otherSum[kk] ?: 0.0) / c)
            }
            if (b == Double.MAX_VALUE) continue
            if (max(a, b) > 0) total += (b - a) / max(a, b)
            cnt++
        }
        return if (cnt == 0) -1f else (total / cnt).toFloat()
    }

    /**
     * 每月一个结果：k 在 [2, 6] 内按轮廓系数择优（数据不足时退化到 2）。
     * 返回按月份升序。
     */
    /** 2026-11-22：主题最小条数。小于它不进主题，归「偶发」。 */
    const val MIN_CLUSTER_SIZE = 4

    /** 算法版本：改了 k 规则 / 边修正就要 +1，让缓存指纹失效。 */
    const val TOPIC_ALGO_VERSION = "2"

    /**
     * 每月一个结果。
     *
     * 2026-11-22 改：不再用轮廓系数选 k（52~91 条记录常被选成 2 簇），
     * 改成 k = clamp(round(n/12), 2, 6)，再用**关系边**修正簇归属
     * （PC 实测：簇内边 45/80→69/80、9/24→21/24），最后丢掉 <4 条的小簇。
     *
     * @param neighbors 记录 → 关联邻居（无向）；空表示不做边修正
     */
    fun build(
        records: List<RecordPoint>,
        maxK: Int = 6,
        neighbors: Map<String, Set<String>> = emptyMap()
    ): List<MonthResult> {
        val byMonth = records.groupBy { it.month }.toSortedMap()
        return byMonth.map { (month, list) ->
            val centered = center(list)
            val size = list.size
            val k = when {
                size < 8 -> 1
                else -> (size / 12.0).roundToInt()
                    .coerceIn(2, maxK.coerceAtMost(6))
                    .coerceAtMost((size / MIN_CLUSTER_SIZE).coerceAtLeast(1))
            }
            val weightArr = list.map { it.weight }.toFloatArray()
            // 2026-11-22：单次随机初始化偶尔会切出 1~2 条的小簇（真机 07 月只剩 3 个主题）。
            // 固定 k 后跑 6 个种子，选「过滤小簇后最均衡」的那次：最小保留簇最大者胜。
            var labels: IntArray
            if (k >= 2) {
                var bestLabels: IntArray? = null
                var bestKept = -1
                var bestMin = -1
                for (seed in 1..6) {
                    val cand = kmeans(centered, k, seed = seed.toLong(), weights = weightArr).first
                    val kept = cand.asList().groupingBy { it }.eachCount().values
                        .filter { it >= MIN_CLUSTER_SIZE }
                    // 先看「保留几个簇」，再看最小的那个有多大：簇数优先，不能被少量大簇骗走。
                    val keptCount = kept.size
                    val minSize = kept.minOrNull() ?: 0
                    if (keptCount > bestKept || (keptCount == bestKept && minSize > bestMin)) {
                        bestKept = keptCount
                        bestMin = minSize
                        bestLabels = cand
                    }
                }
                labels = bestLabels ?: kmeans(centered, k, seed = k.toLong(), weights = weightArr).first
            } else {
                labels = IntArray(size)
            }
            if (neighbors.isNotEmpty() && labels.isNotEmpty()) {
                // 2026-11-22 真机翻车：边修正把 6 簇并成 3 簇（链式图 + 贪心归并）。
                // 保留修正，但**不许把簇数砍掉一半以上**；砍太多就退回原聚类。
                val before = labels.toSet().size
                val refined = refineLabels(labels, list.map { it.id }, neighbors)
                val after = refined.toSet().size
                if (after * 2 >= before) {
                    labels = refined
                }
            }
            labels = mergeSmallClusters(labels, centered, list.map { it.weight }.toFloatArray())
            val clusters = (0 until (labels.maxOrNull()?.plus(1) ?: 0)).mapNotNull { j ->
                val idx = list.indices.filter { labels[it] == j }
                if (idx.size < MIN_CLUSTER_SIZE) null else Cluster(
                    recordIds = idx.map { list[it].id },
                    centroid = normalize(mean(idx.map { centered[it] }, idx.map { list[it].weight }))
                )
            }
            MonthResult(month, clusters)
        }
    }

    /**
     * 2026-11-22：小于 [MIN_CLUSTER_SIZE] 的簇不再直接丢，而是并进「最近的大簇」。
     * 真机 07 月 k=6，初始化切出 1~3 条的小簇，直接被下限吃掉，最后只剩 4 个主题。
     */
    internal fun mergeSmallClusters(
        labels: IntArray,
        points: List<FloatArray>,
        weights: FloatArray
    ): IntArray {
        val sizes = labels.asList().groupingBy { it }.eachCount()
        val big = sizes.filterValues { it >= MIN_CLUSTER_SIZE }.keys
        if (big.isEmpty() || big.size == sizes.size) return labels
        val centroidById = HashMap<Int, FloatArray>()
        big.forEach { j ->
            val idx = labels.indices.filter { labels[it] == j }
            centroidById[j] = mean(idx.map { points[it] }, idx.map { weights[it] })
        }
        val out = labels.copyOf()
        labels.indices.forEach { i ->
            if (labels[i] in big) return@forEach
            val p = points[i]
            out[i] = centroidById.minByOrNull { (_, c) -> dist2(p, c) }?.key ?: labels[i]
        }
        return out
    }

    /**
     * 用关系边修正簇归属：每条记录改判到「邻居最多的簇」。
     * 只动有邻居的记录；连续迭代到不再变化。
     */
    internal fun refineLabels(
        labels: IntArray,
        ids: List<String>,
        neighbors: Map<String, Set<String>>,
        iterations: Int = 6
    ): IntArray {
        val pos = ids.withIndex().associate { (i, id) -> id to i }
        val out = labels.copyOf()
        val counts = HashMap<Int, Int>()
        out.forEach { c -> counts[c] = (counts[c] ?: 0) + 1 }
        repeat(iterations) {
            var moved = 0
            for (i in ids.indices) {
                val score = HashMap<Int, Int>()
                neighbors[ids[i]]?.forEach { nb ->
                    val j = pos[nb] ?: return@forEach
                    val c = out[j]
                    score[c] = (score[c] ?: 0) + 1
                }
                if (score.isEmpty()) continue
                val best = score.maxByOrNull { it.value } ?: continue
                if (best.value <= (score[out[i]] ?: 0)) continue
                // 2026-11-22：不许把原簇搬空——否则边修正会把 6 簇并成 4 簇。
                if ((counts[out[i]] ?: 0) <= 1) continue
                val from = out[i]
                counts[from] = (counts[from] ?: 1) - 1
                counts[best.key] = (counts[best.key] ?: 0) + 1
                out[i] = best.key
                moved++
            }
            if (moved == 0) return out
        }
        return out
    }

    /** 关键词（仅作命名失败时的回退，实测质量一般，正式命名交给 AI）。 */
    fun fallbackKeyword(texts: List<String>): String {
        val stop = "的了是在我你她他它也就都而及与和着过被把让给对从到会有个这那些么呢吧啊吗不没很要又再才".toSet()
        val cnt = HashMap<String, Int>()
        texts.forEach { t ->
            val s = t.filter { it.isLetterOrDigit() }
            for (i in 0 until s.length - 1) {
                val g = s.substring(i, i + 2)
                if (g[0] in stop || g[1] in stop) continue
                cnt[g] = (cnt[g] ?: 0) + 1
            }
        }
        return cnt.entries.sortedByDescending { it.value }.take(2).joinToString("/") { it.key }
    }

    /** 供"是否值得重算"用：主题年月的指纹。 */
    fun fingerprint(records: List<RecordPoint>): String {
        val sb = StringBuilder()
        sb.append("v").append(TOPIC_ALGO_VERSION).append('|')
        records.groupBy { it.month }.toSortedMap().forEach { (m, l) ->
            sb.append(m).append(':').append(l.size).append('|')
        }
        return sb.toString()
    }

    /** 对齐阈值留空：B 方案不连线，这个常量只用于“同名主题”的软提示（暂未启用）。 */
    const val TRACK_ALIGN_HINT = 0.30f

    fun ln1(x: Double): Double = ln(x + 1.0)

    fun absInt(x: Int): Int = abs(x)

    /** 离质心最近的成员 id（代表句取它）。没有可算的向量时退回第一个。 */
    fun nearestMemberId(recordIds: List<String>, vecById: Map<String, FloatArray>, centroid: FloatArray): String? =
        recordIds.filter { vecById.containsKey(it) }
            .minByOrNull { dist2(vecById.getValue(it), centroid) }
            ?: recordIds.firstOrNull()

    /** 取一段正文的第一句（给线索对齐当"代表句"，限长）。 */
    fun firstSentence(text: String, maxChars: Int = 60): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return ""
        val cut = t.indexOfFirst { it == '。' || it == '！' || it == '？' || it == '\n' }
        val s = if (cut in 1 until t.length) t.substring(0, cut + 1) else t
        return if (s.length > maxChars) s.take(maxChars) else s
    }
}
