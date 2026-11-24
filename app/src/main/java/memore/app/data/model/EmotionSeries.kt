package memore.app.data.model

import java.util.Calendar

/** 折线图的时间粒度（按所选范围自动决定）。 */
enum class TrendBucket(val label: String) {
    DAY("天"), WEEK("周"), MONTH("月")
}

/** 一个时间桶上的一个点。 */
data class TrendPoint(
    val bucketStart: Long,
    /**
     * **密度** = 窗口内这个情绪的权重之和 ÷ 窗口内的记录数（0..1）。
     *
     * 用密度而不是"出现时的强度"：两个时段记录数不同也能公平比较，
     * 而且它同时反映"出现没出现"和"有多强"。
     * （若用强度，某周只写一条、焦虑 0.9 就会顶到最高，图会变成"谁最极端"。）
     *
     * "窗口"是什么：未平滑时窗口就是这个桶自己；按天且范围够长时窗口是"这天前后各若干天"
     * （见 [EmotionSeriesBuilder.smoothingWindowFor]）—— 因为一天只有 1~3 条记录，
     * 分母一小，密度就在 0 和 1 之间跳，相邻两天比高低，比出来的其实是"那天写了几条"。
     */
    val density: Float,
    /** 这个桶里**自己有没有记录** —— 决定底部"有记录"灰条是否点亮。 */
    val hasRecord: Boolean,
    /**
     * 这个桶**有没有值可画**（口径：这个桶的平滑窗口里有没有记录）。
     *
     * 未平滑时它就等于 [hasRecord]；平滑之后不一样了 —— 那天没写、但前后几天写了，
     * 窗口里仍有数据，所以线要连续画过去（这正是平滑的意义）。
     * 折线的成段（实线/虚线/断口）看的是它，**不是** [hasRecord]。
     */
    val hasValue: Boolean = hasRecord
)

/** 一条情绪折线。 */
data class EmotionSeries(val name: String, val points: List<TrendPoint>)

/**
 * 折线里真正要画的一段（两个**有值**的桶之间的连线）。
 *
 * 为什么要单独抽出来：**没有记录的桶密度只能是 0**，若把它当折点照画，
 * 线每跨过一次空白就会俯冲到地板再爬回来 —— 按天看 30 天时整张图会变成
 * 一堆 V 字形（曾经的 bug）。所以没有值的桶**不参与成段**，由前后两个真点直接连过去。
 */
data class TrendSegment(
    /** 起点在 `points` 里的下标（一定 `hasValue == true`）。 */
    val fromIndex: Int,
    /** 终点在 `points` 里的下标（一定 `hasValue == true`）。 */
    val toIndex: Int,
    /** 两个真点之间夹着"完全没有值"的桶 → 这一段画虚线。 */
    val crossesGap: Boolean
)

/**
 * 把一条情绪折线拆成"真点之间"的连线。
 *
 * - 相邻两桶都有值 → 实线（`crossesGap = false`）
 * - 中间夹着没值的桶 → 虚线（`crossesGap = true`），**坡度是真点之间的自然过渡，不下探到 0**
 * - 开头/结尾没有值的桶 → 不产生任何段（前面/后面没有真点可连）
 * - 整条线只有 0 或 1 个真点 → 没有任何段（"哪天有记录"由底部灰条表达）
 */
fun EmotionSeries.segments(): List<TrendSegment> {
    val out = ArrayList<TrendSegment>()
    var last = -1
    points.forEachIndexed { i, p ->
        if (!p.hasValue) return@forEachIndexed
        if (last >= 0) out.add(TrendSegment(last, i, crossesGap = i - last > 1))
        last = i
    }
    return out
}

/**
 * 把记录聚合成"按时间分桶的情绪密度序列"。
 *
 * 纯函数：不碰数据库、不读当前时间（范围由调用方传入）→ 可测、可复现。
 */
object EmotionSeriesBuilder {

    /** 同时最多画几条线（再多就糊了）。 */
    const val MAX_SERIES = 5

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * 粒度按跨度自动选（用户定的口径）：
     * **≤30 天按天；30 天以上按周；一年以上按月**（"年以上"暂时用不到，但规则留着 ——
     * 数据早晚会跨过一年）。
     */
    fun bucketFor(rangeMs: Long): TrendBucket = when {
        rangeMs <= 30L * DAY_MS -> TrendBucket.DAY
        rangeMs <= 365L * DAY_MS -> TrendBucket.WEEK
        else -> TrendBucket.MONTH
    }

    /**
     * 按天时的**居中平滑窗口**（桶数，奇数；1 = 不平滑）。周/月桶分母已经够大，不滑。
     *
     * 为什么需要：一天只有 1~3 条记录 —— 分母一小，"密度"就在 0 和 1 之间跳，
     * 相邻两天比高低，比的其实是"那天写了几条"。把一个点换成"以它为中心的若干天窗口的密度"
     * （分子分母一起放大），线才是走势。**不换公式，只换分母的取法。**
     *
     * 窗口最多吃掉整个范围的 1/3，最多 7 天、最少 1 天：
     * 30 天 → 7；21 天 → 7；20 天 → 5；15 天 → 5；12 天 → 3；9 天 → 3；不足 9 天 → 1。
     * （短范围本来就该看当天真值，窗口一大反而把整张图抹平。）
     */
    fun smoothingWindowFor(rangeMs: Long, bucket: TrendBucket): Int {
        if (bucket != TrendBucket.DAY) return 1
        val days = (rangeMs / DAY_MS).coerceAtLeast(1L)
        val raw = days / 3
        val odd = if (raw % 2L == 0L) raw - 1 else raw
        return odd.coerceIn(1L, 7L).toInt()
    }

    /** 把某时刻对齐到所属桶的起点（本地时区；周以**周一**为首、月以 1 号为首）。 */
    fun bucketStartOf(ms: Long, bucket: TrendBucket): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        when (bucket) {
            TrendBucket.DAY -> {}
            TrendBucket.WEEK -> {
                // Calendar.MONDAY == 2；DAY_OF_WEEK 是 1=周日 … 7=周六
                val delta = (c.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
                c.add(Calendar.DAY_OF_MONTH, -delta)
            }
            TrendBucket.MONTH -> c.set(Calendar.DAY_OF_MONTH, 1)
        }
        return c.timeInMillis
    }

    /** 把桶起点前后挪 delta 个桶（delta 可为负）。用 Calendar 加减，跨夏令时也安全。 */
    fun shiftBucketStart(from: Long, bucket: TrendBucket, delta: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = from
        when (bucket) {
            TrendBucket.DAY -> c.add(Calendar.DAY_OF_MONTH, delta)
            TrendBucket.WEEK -> c.add(Calendar.DAY_OF_MONTH, 7 * delta)
            TrendBucket.MONTH -> c.add(Calendar.MONTH, delta)
        }
        return c.timeInMillis
    }

    /** 下一个桶的起点。 */
    fun nextBucketStart(from: Long, bucket: TrendBucket): Long = shiftBucketStart(from, bucket, 1)

    /**
     * @param records 参与统计的记录。**可以多给**：平滑窗口需要范围外那几天的记录来把
     *                边缘的窗口填满（多给的记录只进分母分子，不会多出桶）。
     * @param names   要画哪些情绪（顺序即图例顺序）
     * @param startMs 范围起点
     * @param endMs   范围终点
     * @param windowBuckets 居中平滑窗口的宽度（桶数，奇数；1 = 不平滑）—— 见 [smoothingWindowFor]
     */
    fun build(
        records: List<Record>,
        names: List<String>,
        startMs: Long,
        endMs: Long,
        bucket: TrendBucket,
        windowBuckets: Int = 1
    ): List<EmotionSeries> {
        if (names.isEmpty() || endMs <= startMs) return emptyList()

        val half = windowBuckets.coerceAtLeast(1) / 2

        val perRecord = records.map { EmotionStats.emotionEntries(it) }

        // 权重归一：万一 AI 给的是 0~10 而不是 0~1，按观测到的最大值缩回去，
        // 否则折线会被顶出画布（这不是"数据错"，只是不让它把图撑爆）。
        var maxWeight = 0f
        perRecord.forEach { entries -> entries.forEach { if (it.weight > maxWeight) maxWeight = it.weight } }
        val scale = if (maxWeight > 1f) 1f / maxWeight else 1f

        // 要显示的桶
        val bounds = ArrayList<Long>()
        var t = bucketStartOf(startMs, bucket)
        var guard = 0
        while (t <= endMs && guard < 2000) {
            bounds.add(t)
            t = nextBucketStart(t, bucket)
            guard++
        }
        if (bounds.isEmpty()) return emptyList()

        // 统计用的桶：前后各扩 half 个 —— 平滑窗口要"这天前后各若干天"，
        // 恰好在范围边缘的那几天窗口缺一半 → 分母又变小 → 又变回噪声。
        val total = bounds.size + 2 * half
        val statBounds = ArrayList<Long>(total)
        var s = shiftBucketStart(bounds.first(), bucket, -half)
        for (i in 0 until total) {
            statBounds.add(s)
            s = nextBucketStart(s, bucket)
        }
        val lastEnd = nextBucketStart(statBounds.last(), bucket)

        val counts = IntArray(total)
        val sums = Array(total) { FloatArray(names.size) }
        val indexOf = names.withIndex().associate { (i, n) -> n to i }

        records.forEachIndexed { ri, r ->
            val ts = r.createdAt
            if (ts < statBounds.first() || ts >= lastEnd) return@forEachIndexed
            val bi = bucketIndexOf(statBounds, ts)
            if (bi < 0) return@forEachIndexed
            counts[bi]++
            // 同一条记录里同名情绪取最大权重 —— 与 EmotionStats.parse 的口径一致
            perRecord[ri]
                .groupBy { it.name }
                .forEach { (name, entries) ->
                    val i = indexOf[name] ?: return@forEach
                    val w = (entries.maxByOrNull { it.weight }?.weight ?: 0f) * scale
                    sums[bi][i] += w.coerceAtLeast(0f)
                }
        }

        return names.mapIndexed { i, name ->
            EmotionSeries(
                name = name,
                points = bounds.mapIndexed { j, b ->
                    val center = j + half
                    var winCount = 0
                    var winSum = 0f
                    for (k in (center - half).coerceAtLeast(0)..(center + half).coerceAtMost(total - 1)) {
                        winCount += counts[k]
                        winSum += sums[k][i]
                    }
                    TrendPoint(
                        bucketStart = b,
                        density = if (winCount > 0) (winSum / winCount).coerceIn(0f, 1f) else 0f,
                        hasRecord = counts[center] > 0,
                        hasValue = winCount > 0
                    )
                }
            )
        }
    }

    /** bounds 升序且不重叠 → 二分找出 ts 属于哪个桶（返回最后一个 <= ts 的下标）。 */
    private fun bucketIndexOf(bounds: List<Long>, ts: Long): Int {
        var lo = 0
        var hi = bounds.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (bounds[mid] <= ts) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /**
     * 给新加入的情绪挑一个调色板下标：**优先用当前没人占的颜色**，占满了才回到 0。
     *
     * 调用方把结果记在"情绪 → 下标"的表里，于是**颜色跟着情绪走**、不跟着选中的顺序走 ——
     * 否则取消一条，剩下几条会整体换色，反而更难认（这正是"辨识度不够"的一半原因）。
     */
    fun pickPaletteIndex(usedIndices: Collection<Int>, paletteSize: Int): Int {
        if (paletteSize <= 0) return 0
        for (i in 0 until paletteSize) if (i !in usedIndices) return i
        return 0
    }
}
