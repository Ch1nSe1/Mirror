package memore.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

private const val DAY = 24L * 60 * 60 * 1000

/**
 * 情绪折线图的聚合：分桶、密度、空白期、粒度选择、权重归一。
 *
 * 全是纯函数（时间范围由参数给），所以不依赖当前时间、可复现。
 */
class EmotionSeriesTest {

    private fun at(daysAgo: Int): Long = System.currentTimeMillis() - daysAgo * DAY

    private fun record(id: String, createdAt: Long, vararg emotions: Pair<String, Float>) = Record(
        id = id,
        type = RecordType.ESSAY,
        content = "c-$id",
        createdAt = createdAt,
        updatedAt = createdAt,
        analysis = AnalysisData(emotion = emotions.map { EmotionTagEntry(it.first, it.second) })
    )

    // ---- 粒度选择 ----

    /** 用户定的粒度口径：≤30 天按天、30 天以上按周、一年以上按月。 */
    @Test
    fun bucketIsChosenByRangeLength() {
        assertEquals(TrendBucket.DAY, EmotionSeriesBuilder.bucketFor(7 * DAY))
        assertEquals(TrendBucket.DAY, EmotionSeriesBuilder.bucketFor(30 * DAY))
        assertEquals(TrendBucket.WEEK, EmotionSeriesBuilder.bucketFor(31 * DAY))
        assertEquals(TrendBucket.WEEK, EmotionSeriesBuilder.bucketFor(180 * DAY))
        assertEquals(TrendBucket.WEEK, EmotionSeriesBuilder.bucketFor(365 * DAY))
        assertEquals(TrendBucket.MONTH, EmotionSeriesBuilder.bucketFor(366 * DAY))
    }

    /** 桶起点对齐：天→当天零点；周→周一零点；月→1 号零点。 */
    @Test
    fun bucketStartAlignment() {
        val t = System.currentTimeMillis()

        val day = Calendar.getInstance().apply { timeInMillis = EmotionSeriesBuilder.bucketStartOf(t, TrendBucket.DAY) }
        assertEquals(0, day.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, day.get(Calendar.MINUTE))
        assertEquals(0, day.get(Calendar.MILLISECOND))

        val week = Calendar.getInstance().apply { timeInMillis = EmotionSeriesBuilder.bucketStartOf(t, TrendBucket.WEEK) }
        assertEquals("周桶应当落在周一", Calendar.MONDAY, week.get(Calendar.DAY_OF_WEEK))
        assertEquals(0, week.get(Calendar.HOUR_OF_DAY))

        val month = Calendar.getInstance().apply { timeInMillis = EmotionSeriesBuilder.bucketStartOf(t, TrendBucket.MONTH) }
        assertEquals("月桶应当落在 1 号", 1, month.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, month.get(Calendar.HOUR_OF_DAY))
    }

    // ---- 密度 ----

    /**
     * 密度 = 该桶内该情绪权重之和 ÷ 该桶内记录数。
     * 这是"占这段时间多少"，不是"出现时多强" —— 后者会让只出现一次的极端封顶。
     */
    @Test
    fun densityDividesByRecordCountInBucket() {
        val now = System.currentTimeMillis()
        val records = listOf(
            record("a", now, "焦虑" to 0.8f),           // 今天：焦虑 0.8
            record("b", now, "平静" to 0.4f),           // 今天：只提平静
            record("c", now - DAY, "焦虑" to 0.6f)      // 昨天
        )
        val series = EmotionSeriesBuilder.build(
            records = records, names = listOf("焦虑"),
            startMs = now - 2 * DAY, endMs = now, bucket = TrendBucket.DAY
        ).single()

        // 今天这一桶有 2 条记录，焦虑权重和 0.8 → 0.4（而不是 0.8）
        val today = series.points.last()
        assertEquals(0.4f, today.density, 0.0001f)
        assertTrue(today.hasRecord)
    }

    /** 没记录的那一桶：密度 0、`hasRecord=false`、`hasValue=false`（画图时跳过，不做假低谷）。 */
    @Test
    fun bucketWithoutRecordsIsMarkedAsGap() {
        val now = System.currentTimeMillis()
        val records = listOf(record("a", now, "焦虑" to 0.9f))   // 只有今天
        val series = EmotionSeriesBuilder.build(
            records = records, names = listOf("焦虑"),
            startMs = now - 3 * DAY, endMs = now, bucket = TrendBucket.DAY
        ).single()

        assertEquals(4, series.points.size)
        val empty = series.points.dropLast(1)
        assertTrue("中间那几天没有记录", empty.all { !it.hasRecord })
        assertTrue("没有记录时密度为 0", empty.all { it.density == 0f })
        assertTrue("默认不平滑 → 没记录就没有值（不会落到地板上）", empty.all { !it.hasValue })
        assertTrue("最后一天有记录", series.points.last().hasRecord)
    }

    /** 只统计请求范围内的记录（范围外的记录不该影响任何桶）。 */
    @Test
    fun recordsOutsideRangeAreIgnored() {
        val now = System.currentTimeMillis()
        val records = listOf(
            record("in", now, "焦虑" to 1.0f),
            record("out", now - 100 * DAY, "焦虑" to 1.0f)
        )
        val series = EmotionSeriesBuilder.build(
            records = records, names = listOf("焦虑"),
            startMs = now - 2 * DAY, endMs = now, bucket = TrendBucket.DAY
        ).single()
        // 范围内只有 1 条 → 密度就是 1.0（若范围外那条被算进来会变成 0.5）
        assertEquals(1.0f, series.points.last().density, 0.0001f)
    }

    /** 同一条记录里同名情绪取最大权重（别名归一后可能出现"开心+喜悦"）。 */
    @Test
    fun sameEmotionTwiceInOneRecordUsesMaxWeight() {
        val now = System.currentTimeMillis()
        val records = listOf(record("a", now, "焦虑" to 0.3f, "焦虑" to 0.9f))
        val series = EmotionSeriesBuilder.build(
            records = records, names = listOf("焦虑"),
            startMs = now - DAY, endMs = now, bucket = TrendBucket.DAY
        ).single()
        assertEquals(0.9f, series.points.last().density, 0.0001f)
    }

    /** AI 若给 0~10 的分数，按观测最大值归一，避免把图撑爆。 */
    @Test
    fun weightsAboveOneAreNormalized() {
        val now = System.currentTimeMillis()
        val records = listOf(
            record("a", now, "焦虑" to 8f),
            record("b", now, "平静" to 4f)
        )
        val series = EmotionSeriesBuilder.build(
            records = records, names = listOf("焦虑", "平静"),
            startMs = now - DAY, endMs = now, bucket = TrendBucket.DAY
        )
        // 焦虑 8→1.0、平静 4→0.5；同一桶 2 条记录 → 0.5 / 0.25
        assertEquals(0.5f, series[0].points.last().density, 0.0001f)
        assertEquals(0.25f, series[1].points.last().density, 0.0001f)
        assertTrue(series.all { s -> s.points.all { it.density <= 1f } })
    }

    @Test
    fun emptyNamesOrBadRangeYieldsNothing() {
        val now = System.currentTimeMillis()
        // 没要求任何情绪 → 没有线
        assertTrue(EmotionSeriesBuilder.build(emptyList(), emptyList(), now - DAY, now, TrendBucket.DAY).isEmpty())
        // 范围非法（终点不晚于起点）→ 没有线
        assertTrue(EmotionSeriesBuilder.build(emptyList(), listOf("焦虑"), now, now - DAY, TrendBucket.DAY).isEmpty())
        assertTrue(EmotionSeriesBuilder.build(emptyList(), listOf("焦虑"), now, now, TrendBucket.DAY).isEmpty())
    }

    /** 颜色分配：优先用没人占的下标（颜色跟着情绪走）；占满时回到 0，不崩。 */
    @Test
    fun paletteIndexPicksAnUnusedColor() {
        assertEquals(0, EmotionSeriesBuilder.pickPaletteIndex(emptyList(), 5))
        assertEquals(2, EmotionSeriesBuilder.pickPaletteIndex(listOf(0, 1, 3), 5))
        assertEquals(0, EmotionSeriesBuilder.pickPaletteIndex(listOf(0, 1, 2, 3, 4), 5))
        assertEquals(0, EmotionSeriesBuilder.pickPaletteIndex(emptyList(), 0))
    }

    /** 空范围里没有任何记录时：线还是要画出来（全长 0、全 gap），页面才好显示坐标轴。 */
    @Test
    fun noRecordsStillProducesFlatSeriesWithGaps() {
        val now = System.currentTimeMillis()
        val series = EmotionSeriesBuilder.build(
            records = emptyList(), names = listOf("焦虑"),
            startMs = now - 2 * DAY, endMs = now, bucket = TrendBucket.DAY
        ).single()
        assertTrue(series.points.isNotEmpty())
        assertTrue(series.points.all { !it.hasRecord && it.density == 0f })
        assertFalse(series.points.any { it.hasRecord })
    }

    // ---- 连段（决定实线/虚线，以及**空白桶不落点**）----

    private fun series(vararg d: Pair<Float, Boolean>) = EmotionSeries(
        name = "焦虑",
        points = d.mapIndexed { i, (density, hasData) ->
            TrendPoint(bucketStart = i * DAY, density = density, hasRecord = hasData)
        }
    )

    /** 桶桶都有记录 → 每两个相邻桶一段，全是实线。 */
    @Test
    fun segmentsAllAdjacentAreSolid() {
        val segs = series(0.2f to true, 0.4f to true, 0.3f to true).segments()
        assertEquals(2, segs.size)
        assertEquals(TrendSegment(0, 1, false), segs[0])
        assertEquals(TrendSegment(1, 2, false), segs[1])
        assertTrue(segs.none { it.crossesGap })
    }

    /**
     * 中间有空白桶 → **不产生"真点→0"和"0→真点"两段**，而是真点之间直接一段虚线。
     * （这就是"从最底部往上拉"那个 bug 的回归测试。）
     */
    @Test
    fun segmentsSkipEmptyBucketsInsteadOfDroppingToZero() {
        val segs = series(
            0.6f to true,
            0f to false,     // 空白
            0f to false,     // 空白
            0.2f to true
        ).segments()
        assertEquals("空桶不该成为折点", 1, segs.size)
        assertEquals(TrendSegment(0, 3, true), segs[0])
        assertTrue("跨空白要画虚线", segs[0].crossesGap)
    }

    /** 开头/结尾的空白桶不产生任何段（前面/后面没有真点可连）。 */
    @Test
    fun segmentsIgnoreLeadingAndTrailingGaps() {
        val segs = series(
            0f to false,     // 范围开头还没记录
            0.5f to true,
            0.5f to true,
            0f to false      // 范围末尾之后没记录
        ).segments()
        assertEquals(1, segs.size)
        assertEquals(TrendSegment(1, 2, false), segs[0])
    }

    /** 只有一个真点（或一个都没有）→ 没有段，全靠底部灰条表达"哪天有记录"。 */
    @Test
    fun segmentsNeedAtLeastTwoRealPoints() {
        assertTrue(series(0f to false, 0.3f to true, 0f to false).segments().isEmpty())
        assertTrue(series(0f to false, 0f to false).segments().isEmpty())
        assertTrue(series().segments().isEmpty())
    }

    /** 两段真数据 + 一段空白：实线 → 虚线，且虚线的两端都是真点。 */
    @Test
    fun segmentsMixSolidAndDashed() {
        val s = series(0.1f to true, 0.2f to true, 0f to false, 0.9f to true)
        val segs = s.segments()
        assertEquals(listOf(TrendSegment(0, 1, false), TrendSegment(1, 3, true)), segs)
        segs.forEach {
            assertTrue("段的端点必须都有值", s.points[it.fromIndex].hasValue && s.points[it.toIndex].hasValue)
        }
    }

    // ---- 平滑（按天档专治"一天只有 1~3 条 → 密度在 0 和 1 之间跳"）----

    /** 窗口：最多 7 天、最少 1 天（1 = 不平滑），且最多吃掉整个范围的 1/3；周/月桶不滑。 */
    @Test
    fun smoothingWindowShrinksWithRange() {
        fun w(days: Int) = EmotionSeriesBuilder.smoothingWindowFor(days * DAY, TrendBucket.DAY)
        assertEquals(7, w(30))
        assertEquals(7, w(21))
        assertEquals(5, w(20))
        assertEquals(5, w(15))
        assertEquals(3, w(12))
        assertEquals(3, w(9))
        assertEquals(1, w(8))
        assertEquals(1, w(3))
        assertEquals(1, EmotionSeriesBuilder.smoothingWindowFor(180 * DAY, TrendBucket.WEEK))
        assertEquals(1, EmotionSeriesBuilder.smoothingWindowFor(400 * DAY, TrendBucket.MONTH))
    }

    /**
     * 平滑 = 窗口内的密度（分子分母一起放大）。
     * 今天只写一条、标了焦虑 1.0 → 不平滑就是假尖峰 1.0；套 5 天窗后被邻居摊下来。
     */
    @Test
    fun smoothingUsesNeighbouringBuckets() {
        val now = System.currentTimeMillis()
        val records = listOf(
            record("a", now, "焦虑" to 1.0f),
            record("b", now - DAY, "平静" to 0.5f),
            record("c", now - 2 * DAY, "平静" to 0.5f)
        )
        val raw = EmotionSeriesBuilder.build(
            records, listOf("焦虑"), now - 2 * DAY, now, TrendBucket.DAY
        ).single().points.last()
        assertEquals("不平滑时是假尖峰", 1.0f, raw.density, 0.0001f)

        val smooth = EmotionSeriesBuilder.build(
            records, listOf("焦虑"), now - 2 * DAY, now, TrendBucket.DAY, windowBuckets = 5
        ).single().points.last()
        // 5 天窗里 3 条记录、焦虑权重和 1.0 → 1/3
        assertEquals(0.3333f, smooth.density, 0.001f)
    }

    /**
     * 边界那几天的窗口要用**范围外**的记录来填 —— 否则范围边缘的窗口只有半截，
     * 分母又变小，等于"边界天生更抖"。
     */
    @Test
    fun smoothingFillsEdgeWindowWithRecordsOutsideRange() {
        val now = System.currentTimeMillis()
        // 用"当天零点"当范围，避免测试结果随"现在几点"变（范围里必须只有一个桶）
        val day = EmotionSeriesBuilder.bucketStartOf(now, TrendBucket.DAY)
        val records = listOf(
            record("in", day, "焦虑" to 1.0f),           // 范围内（就这一天）
            record("before", day - DAY, "平静" to 1.0f),  // 范围外：起点前一天
            record("after", day + DAY, "平静" to 1.0f)    // 范围外：终点后一天
        )
        val s = EmotionSeriesBuilder.build(
            records, listOf("焦虑"), day, day + 12 * 60 * 60 * 1000L, TrendBucket.DAY, windowBuckets = 3
        ).single()

        assertEquals(1, s.points.size)
        assertEquals("窗口要用范围外的记录填满（否则会算成 1.0）", 0.3333f, s.points.first().density, 0.001f)
    }

    /** 平滑后"这天自己没写"和"这个点没值"是两件事：线要连续，底部灰条要老实。 */
    @Test
    fun smoothedPointSeparatesHasRecordFromHasValue() {
        val now = System.currentTimeMillis()
        val records = listOf(record("a", now - DAY, "焦虑" to 0.6f))   // 只有昨天写了
        val s = EmotionSeriesBuilder.build(
            records, listOf("焦虑"), now - 2 * DAY, now, TrendBucket.DAY, windowBuckets = 3
        ).single()
        val today = s.points.last()

        assertFalse("今天自己没写", today.hasRecord)
        assertTrue("但窗口里有数据 → 有值可画（线不断）", today.hasValue)
        assertEquals(0.6f, today.density, 0.0001f)
    }

    /** 平滑也救不了"整窗都没记录"：连续一周多没写，线才真的断（跨空白 → 虚线）。 */
    @Test
    fun longEmptyStretchStillBreaksTheLine() {
        val now = System.currentTimeMillis()
        val records = listOf(
            record("a", now - 20 * DAY, "焦虑" to 0.8f),
            record("b", now, "焦虑" to 0.2f)          // 中间空了 19 天
        )
        val s = EmotionSeriesBuilder.build(
            records, listOf("焦虑"), now - 20 * DAY, now, TrendBucket.DAY, windowBuckets = 7
        ).single()

        assertTrue("中间那些天连窗口都是空的 → 没有值", s.points.drop(5).dropLast(5).all { !it.hasValue })
        val segs = s.segments()
        assertEquals("其余都是相邻实线段", 7, segs.size)
        val gap = segs.single { it.crossesGap }
        assertEquals(3, gap.fromIndex)
        assertEquals(17, gap.toIndex)
        assertTrue(
            "虚线两端都是有值的点",
            s.points[gap.fromIndex].hasValue && s.points[gap.toIndex].hasValue
        )
        assertEquals(
            "但真正写了记录的只有首尾两天（中间的'值'是平滑出来的）",
            listOf(0, 20),
            s.points.withIndex().filter { it.value.hasRecord }.map { it.index }
        )
    }
}
