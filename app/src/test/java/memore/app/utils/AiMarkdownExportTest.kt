package memore.app.utils

import memore.app.data.model.EmotionTagEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「导出给 AI」Markdown 契约测试（2026-11-09）。
 *
 * 锁住三件事——它们正是它和 JSON 导出的区别：
 *  1. 增修**就地合并**进父记录，不单独成段
 *  2. 情绪标签带**依据原文**
 *  3. 关联边带**引用句**与目标时间
 */
class AiMarkdownExportTest {

    private fun record(
        id: String,
        ts: String,
        body: String,
        correctionOf: String? = null,
        emotion: List<EmotionTagEntry>? = null,
        out: List<ExportedRelationOut>? = null,
        tags: List<String> = emptyList()
    ) = ExportRecord(
        id = id,
        type = "随笔",
        content = body,
        tags = tags,
        timestamp = ts,
        starred = false,
        deleted = false,
        correction_of_id = correctionOf,
        analysis = emotion?.let {
            memore.app.data.model.AnalysisData(emotion = it)
        },
        relations_out = out
    )

    private fun data(records: List<ExportRecord>) = ExportData(
        version = "1",
        export_time = "2026-11-09T00:00:00Z",
        count = records.size,
        records = records
    )

    @Test
    fun amendmentIsMergedIntoParent() {
        val md = ExportEngine.buildAiMarkdown(
            data(
                listOf(
                    record("p", "2026-08-01 10:00", "正文第一段"),
                    record("c", "2026-08-02 11:00", "后来补充的话", correctionOf = "p")
                )
            )
        )
        // 只有父记录成段
        assertTrue(md.contains("## 2026-08-01 10:00"))
        assertFalse(md.contains("## 2026-08-02 11:00"))
        // 增修以标注形式出现在父记录内
        assertTrue(md.contains("【增修 · 2026-08-02 11:00】"))
        assertTrue(md.contains("后来补充的话"))
    }

    @Test
    fun emotionCarriesEvidence() {
        val md = ExportEngine.buildAiMarkdown(
            data(listOf(record("r", "2026-08-01 10:00", "血肉里结出冰晶针刺神经",
                emotion = listOf(EmotionTagEntry("恐惧", 0.7f, null, "血肉里结出冰晶针刺神经")))))
        )
        assertTrue(md.contains("恐惧"))
        assertTrue(md.contains("「血肉里结出冰晶针刺神经」"))
    }

    @Test
    fun relationShowsTargetTimeAndQuote() {
        val md = ExportEngine.buildAiMarkdown(
            data(
                listOf(
                    record("a", "2026-08-01 10:00", "我开始了",
                        out = listOf(ExportedRelationOut("b", "LINK", "[同一个模式] 又一次开始"))),
                    record("b", "2026-09-01 10:00", "又开始了")
                )
            )
        )
        assertTrue(md.contains("关联 → 2026-09-01 10:00"))
        assertTrue(md.contains("同一个模式"))
    }

    @Test
    fun headerStatesReadOnly() {
        val md = ExportEngine.buildAiMarkdown(data(listOf(record("r", "2026-08-01 10:00", "x"))))
        assertTrue(md.startsWith("# 记忆记录"))
        assertTrue(md.contains("不可导回"))
    }
}
