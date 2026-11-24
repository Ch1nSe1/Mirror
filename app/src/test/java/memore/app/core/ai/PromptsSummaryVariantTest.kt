package memore.app.core.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分析提示词两套变体的契约测试（2026-11-09 回归）。
 *
 * 事故：默认模板里删掉了 `"summary": ""` 之后，「带总结」那套靠 replace 派生，
 * 锚点消失 → replace 静默失效 → 两套变成一模一样，用户选「输出总结」却拿不到总结。
 *
 * 这里锁住三件事，以后谁再改默认模板都会当场失败：
 *  1. 两套必须不同
 *  2. 只有带总结那套出现 summary 字段与写作要求
 *  3. 两套都保留 emotion / links 的要求
 */
class PromptsSummaryVariantTest {

    private val variants = Prompts.analyzePromptVariantsForTest()
    private val plain: String = variants.first
    private val summary: String = variants.second

    @Test
    fun variantsAreDifferent() {
        assertNotEquals(plain, summary)
    }

    @Test
    fun onlySummaryVariantAsksForSummary() {
        assertTrue(summary.contains("\"summary\":"))
        assertTrue(summary.contains("分析性回应"))
        assertFalse(plain.contains("\"summary\":"))
    }

    @Test
    fun bothKeepEmotionAndLinks() {
        // v8（2026-11-21）起不再输出 meta，也没有 {prompt_version} 占位；三条内容段必须都在。
        for (p in listOf(plain, summary)) {
            assertTrue("缺少 emotion 段", p.contains("emotion"))
            assertTrue("缺少 anchors 段", p.contains("anchors"))
            assertTrue("缺少 links 段", p.contains("links"))
            assertFalse("v8 不再让模型输出 meta", p.contains("\"meta\""))
        }
    }

    @Test
    fun promptsNoLongerMentionMemoryNodes() {
        // 2026-11-19：概念/节点系统已整体下线。提示词里**不再提这个字段**——
        // 不要求就不输出，比写一句“已废弃、输出会被忽略”干净（用户原话）。
        for (p in listOf(plain, summary)) {
            assertFalse("提示词里不该再出现 memory_nodes", p.contains("memory_nodes"))
            assertFalse("提示词里不该再出现“已废弃”这类寿星文案", p.contains("已废弃"))
        }
    }
}
