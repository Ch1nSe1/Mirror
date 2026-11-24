package memore.app.data.model

import memore.app.utils.EmotionNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotionStatsTest {

    private fun record(
        id: String,
        analysisEmotion: List<EmotionTagEntry> = emptyList(),
        legacyTags: String = "[]"
    ) = Record(
        id = id,
        type = RecordType.ESSAY,
        content = "content-$id",
        emotionalTags = legacyTags,
        analysis = if (analysisEmotion.isEmpty()) null else AnalysisData(emotion = analysisEmotion),
        updatedAt = System.currentTimeMillis()
    )

    @Test
    fun emotionEntriesPrefersAnalysisEmotion() {
        val legacy = """[{"name":"旧标签","weight":0.1}]"""
        val r = record("1", analysisEmotion = listOf(EmotionTagEntry("新标签", 0.9f)), legacyTags = legacy)
        assertEquals(listOf("新标签"), EmotionStats.emotionEntries(r).map { it.name })
    }

    @Test
    fun recordsWithEmotionTagMatchesAnalysisEmotion() {
        val records = listOf(
            record("1", analysisEmotion = listOf(EmotionTagEntry("喜悦", 0.9f))),
            record("2", analysisEmotion = listOf(EmotionTagEntry("平静", 0.5f)))
        )
        val result = EmotionStats.recordsWithEmotionTag(records, "喜悦")
        assertEquals(listOf("1"), result.map { it.id })
    }

    @Test
    fun recordsWithEmotionTagMatchesLegacyTags() {
        val legacy = """[{"name":"低落","weight":0.6}]"""
        val records = listOf(
            record("1", legacyTags = legacy),
            record("2", legacyTags = "[]")
        )
        val result = EmotionStats.recordsWithEmotionTag(records, "低落")
        assertEquals(listOf("1"), result.map { it.id })
    }

    @Test
    fun recordsWithEmotionTagMatchesLegacyStringArray() {
        val legacy = """["怀念","平静"]"""
        val records = listOf(
            record("1", legacyTags = legacy),
            record("2", legacyTags = "[]")
        )
        val result = EmotionStats.recordsWithEmotionTag(records, "怀念")
        assertEquals(listOf("1"), result.map { it.id })
    }

    @Test
    fun emotionNameIsNormalized() {
        val legacy = """[{"name":" 喜悦 ","weight":0.8}]"""
        val records = listOf(record("1", legacyTags = legacy))
        assertEquals(listOf("1"), EmotionStats.recordsWithEmotionTag(records, "喜悦").map { it.id })
    }

    @Test
    fun dedupeEmotionTagsKeepsHighestWeight() {
        EmotionNormalizer.rebuildCache(
            listOf(
                AliasGroup(standardName = "喜悦", aliases = listOf("开心"), kind = "emotion")
            )
        )
        val tags = listOf(
            EmotionTag("开心", 0.6f),
            EmotionTag("喜悦", 0.9f)
        )
        val result = EmotionStats.dedupeEmotionTags(tags)
        assertEquals(listOf("喜悦"), result.map { it.name })
        assertEquals(0.9f, result.single().weight ?: 0f, 0.001f)
    }

    @Test
    fun parseDedupesMergedAliasesPerRecord() {
        EmotionNormalizer.rebuildCache(
            listOf(
                AliasGroup(standardName = "喜悦", aliases = listOf("开心"), kind = "emotion")
            )
        )
        val legacy = """[{"name":"开心","weight":0.6},{"name":"喜悦","weight":0.9}]"""
        val records = listOf(record("1", legacyTags = legacy))
        val stats = EmotionStats.parse(records)
        assertEquals(1, stats.size)
        assertEquals("喜悦", stats.single().name)
        assertEquals(1, stats.single().count)
        // confidence(1) = 1 - exp(-0.2) ≈ 0.18127，单条 0.9 → weight ≈ 0.16314
        assertEquals(0.16314, stats.single().weight.toDouble(), 0.001)
    }

    /**
     * **时间口径**：情绪落在"写下的那一刻"（createdAt），不是"最后编辑时间"（updatedAt）。
     *
     * 改一次旧记录不该把它搬到今天 —— 否则情绪页的新鲜度/趋势会失真，
     * 而且和秒针切片（一直用 createdAt）互相矛盾。这条钉住那次口径统一。
     */
    @Test
    fun parseUsesCreatedAt_notUpdatedAt() {
        val now = System.currentTimeMillis()
        val sixtyDaysAgo = now - 60L * 24 * 60 * 60 * 1000
        val old = Record(
            id = "old",
            type = RecordType.ESSAY,
            content = "两个月前写的，昨天改过",
            createdAt = sixtyDaysAgo,
            updatedAt = now,                                    // ← 刚编辑过
            analysis = AnalysisData(emotion = listOf(EmotionTagEntry("低落", 0.9f)))
        )

        val stat = EmotionStats.parse(listOf(old)).single()

        assertEquals("最后出现时间应当是写下的那一刻", sixtyDaysAgo, stat.lastOccurrence)
        assertEquals("60 天前写的，不该算进近 30 天", 0, stat.last30DaysCount)
    }

    @Test
    fun recordsWithEmotionTagIgnoresBlankTag() {
        val records = listOf(record("1", analysisEmotion = listOf(EmotionTagEntry("喜悦"))))
        assertTrue(EmotionStats.recordsWithEmotionTag(records, "  ").isEmpty())
    }
}
