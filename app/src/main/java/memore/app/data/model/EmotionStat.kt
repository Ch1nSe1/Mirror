package memore.app.data.model

import memore.app.utils.GsonInstance
import memore.app.utils.EmotionNormalizer
import kotlin.math.exp
import kotlin.math.pow

data class EmotionStat(
    val name: String,
    val count: Int,
    val lastOccurrence: Long,
    val last30DaysCount: Int = 0,
    val weight: Float = 0f,
    val trend: String = "stable",
    val frequency: Double = 0.0
)

data class EmotionTagEntry(
    val name: String,
    val weight: Float = 0f,
    val trend: String? = null,
    /** 判定依据（从原文摘的一句），2026-11-08 起随分析一起落库。 */
    val why: String? = null
)

data class EmotionalProfileItem(
    val name: String,
    val intensity: Double,
    val frequency: Double = 0.0
)

object EmotionStats {

    private const val TREND_THRESHOLD = 0.1

    fun emotionEntries(record: Record): List<EmotionTagEntry> {
        record.analysis?.emotion?.takeIf { it.isNotEmpty() }?.let { entries ->
            return entries.map { it.copy(name = EmotionNormalizer.normalize(it.name)) }
        }
        val raw = record.emotionalTags
        if (raw.isBlank() || raw == "[]") return emptyList()
        return runCatching {
            GsonInstance.gson.fromJson(raw, Array<EmotionTagEntry>::class.java)
                ?.map { it.copy(name = EmotionNormalizer.normalize(it.name)) }
                ?: emptyList()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching {
                GsonInstance.gson.fromJson(raw, Array<String>::class.java)
                    ?.map { EmotionTagEntry(EmotionNormalizer.normalize(it)) }
                    ?: emptyList()
            }.getOrDefault(emptyList())
    }

    fun recordsWithEmotionTag(records: List<Record>, tag: String): List<Record> {
        val normalized = EmotionNormalizer.normalize(tag)
        if (normalized.isEmpty()) return emptyList()
        return records.filter { record ->
            emotionEntries(record).any { it.name == normalized }
        }
    }

    /**
     * 情绪统计。
     *
     * @param originWeight 来源的「叙事分量」（2026-11-17）：默认 1；0 = 这条记录的情绪不算，
     * 0<w<1 时按乘子压低它对该情绪权重的贡献。与半衰期/置信度公式配合——
     * 公式决定"时间与频次"，来源权重只决定"这条记录的分量"。
     */
    fun parse(records: List<Record>, originWeight: (Record) -> Float = { 1f }): List<EmotionStat> {
        val map = mutableMapOf<String, MutableList<Pair<Long, Float>>>()
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        records.forEach { record ->
            val w = originWeight(record).coerceIn(0f, 1f)
            if (w <= 0f) return@forEach
            emotionEntries(record)
                .groupBy { it.name }
                .forEach { (name, entries) ->
                    val best = entries.maxByOrNull { it.weight } ?: return@forEach
                    // ⚠️ 时间口径用 **createdAt**（写下它的那一刻），不是 updatedAt。
                    // 原来用 updatedAt 会让"改一次旧记录"把它当成今天发生的 ——
                    // 情绪页的新鲜度/趋势因此失真，而秒针切片用的是 createdAt，两边还会互相矛盾。
                    // 情绪属于"写下的那一刻"，编辑不该把它搬到今天。
                    map.getOrPut(name) { mutableListOf() }.add(Pair(record.createdAt, best.weight * w))
                }
        }
        val weightByEmotion = map.mapValues { (_, values) -> computeTimeDecayedWeight(values) }
        // 2026-11-22：频率不再用 weight 归一（那等于把强度除以常数，排序完全一样）。
        // 改成**出现次数占比**：某情绪出现次数 / 全部情绪出现次数。
        val totalCount = map.values.sumOf { it.size }.coerceAtLeast(1)
        return map.map { (name, values) ->
            val weight = weightByEmotion.getValue(name)
            val count = values.size
            EmotionStat(
                name = name,
                count = count,
                lastOccurrence = values.maxOf { it.first },
                last30DaysCount = values.count { it.first >= cutoff },
                weight = weight,
                trend = computeTrend(values),
                frequency = count.toDouble() / totalCount
            )
        }
    }

    fun dedupeEmotionTags(tags: List<EmotionTag>): List<EmotionTag> =
        tags.map { it.copy(name = EmotionNormalizer.normalize(it.name)) }
            .groupBy { it.name }
            .map { (name, list) -> list.maxByOrNull { it.weight ?: 0f }!!.copy(name = name) }

    private fun computeTrend(values: List<Pair<Long, Float>>): String {
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60 * 60 * 1000
        val recent7 = values.filter { now - it.first <= 7 * dayMs }
        val prev14 = values.filter {
            val age = now - it.first
            age > 7 * dayMs && age <= 21 * dayMs
        }
        if (recent7.isEmpty() && prev14.isEmpty()) return "stable"
        val recentW = if (recent7.isNotEmpty()) recent7.map { it.second.coerceAtLeast(0.1f) }.average().toFloat() else 0f
        val prevW = if (prev14.isNotEmpty()) prev14.map { it.second.coerceAtLeast(0.1f) }.average().toFloat() else 0f
        val diff = recentW - prevW
        return when {
            diff > TREND_THRESHOLD -> "up"
            diff < -TREND_THRESHOLD -> "down"
            else -> "stable"
        }
    }

    /**
     * 时间衰减强度均值（weight），已合并次数置信度因子。
     *
     * 1) 半衰期指数衰减：decay = 0.5^(daysAgo / WEIGHT_HALF_LIFE_DAYS)
     *    半衰期为 7 天：7 天前的情绪贡献今日的一半，14 天前四分之一，30 天前约 5%。
     *    比旧的 1/(days+1) 双曲线衰减更温和（旧公式 1 天即折半，过于激进）。
     * 2) 置信度因子：confidence(n) = 1 - e^(-λ·n)，n 为出现次数。
     *    避免"出现次数少但 AI 权重高"的情绪虚高排名——高频情绪在次数达到
     *    阈值（λ=0.2 时约 10 次即达 86% 置信度）后不再受惩罚，而低频高强度情绪被温和压低。
     *
     * weight 同时供排序、图表展示、导出 emotional_profile 使用，口径统一。
     */
    private const val WEIGHT_HALF_LIFE_DAYS = 7.0
    private const val WEIGHT_CONFIDENCE_LAMBDA = 0.2

    private fun computeTimeDecayedWeight(values: List<Pair<Long, Float>>): Float {
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60 * 60 * 1000
        var sumWeighted = 0.0
        var sumDecay = 0.0
        values.forEach { (ts, w) ->
            val daysAgo = ((now - ts).toDouble() / dayMs).coerceAtLeast(0.0)
            val decay = 0.5.pow(daysAgo / WEIGHT_HALF_LIFE_DAYS)
            val effectiveWeight = if (w > 0f) w.toDouble() else 0.5
            sumWeighted += effectiveWeight * decay
            sumDecay += decay
        }
        val baseWeight = if (sumDecay > 0.0) (sumWeighted / sumDecay).toFloat() else 0.5f
        val confidence = 1.0 - exp(-WEIGHT_CONFIDENCE_LAMBDA * values.size)
        return (baseWeight * confidence).toFloat()
    }

}
