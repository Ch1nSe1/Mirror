package memore.app.core.ai

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分析提示词的字段契约（2026-11-19）。
 *
 * 背景：用户保存过自定义 analyze 提示词后，代码里的默认值不再生效；
 * 所以新字段（anchors / links.type）必须同时存在于默认模板和"附加补丁"里。
 * 这个测试防止哪天补丁被删、自定义用户又悄悄产不出锚点。
 */
class AnalyzePromptShapeTest {

    @Test
    fun `默认分析提示词包含锚点与边类型`() {
        val (normal, withSummary) = Prompts.analyzePromptVariantsForTest()
        for (p in listOf(normal, withSummary)) {
            assertTrue("缺少 anchors 字段", p.contains("\"anchors\""))
            assertTrue("缺少 type 字段", p.contains("\"type\": \"LINK\""))
            assertTrue("缺少锚点判据", p.contains("锚点"))
        }
    }

    @Test
    fun `附加补丁覆盖三种关系类型和引文要求`() {
        val s = Prompts.ANALYZE_FIELDS_SUPPLEMENT
        assertTrue(s.contains("anchors"))
        assertTrue(s.contains("CORRECT"))
        assertTrue(s.contains("SUPPLEMENT"))
        assertTrue(s.contains("CITE"))
        assertTrue(s.contains("quote"))
    }
}
