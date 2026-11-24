package memore.app.core.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 锚点抽取提示词（2026-11-21）——按 LightRAG 的实体抽取提示词结构重写。
 *
 * 钉住来源里明确写的几条：
 * ① 类型表 + `OTHER` 兜底；
 * ② 禁用代词，且**包含 I / you / this article** 这类（之前只写了 他/她/它）；
 * ③ 命名一致（同一实体整篇一种写法）；
 * ④ 宁缺毋滥（`Output fewer rows if fewer high-value items are present`→ 这句是 LightRAG 原文，
 *    这里断言我们自己的中文表述）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnchorsPromptTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private val prompt: String = Prompts.getPrompt(Prompts.KEY_ANALYZE)

    @Test
    fun `提示词里有锚点段落和类型表`() {
        assertTrue(prompt.contains("anchors"))
        listOf("PERSON", "WORK", "PLACE", "EVENT", "OTHER").forEach {
            assertTrue("缺少类型 $it", prompt.contains(it))
        }
    }

    @Test
    fun `禁止代词包括我你和英文代词`() {
        listOf("我", "你", "大家", "自己", "this article", "I", "you", "he", "she").forEach {
            assertTrue("缺少代词禁用项 $it", prompt.contains(it))
        }
    }

    @Test
    fun `禁止日期和技术术语当锚`() {
        assertTrue(prompt.contains("日期"))
        assertTrue(prompt.contains("JSON"))
        assertTrue(prompt.contains("整句话"))
    }

    @Test
    fun `要求命名一致`() {
        assertTrue(prompt.contains("命名一致") || prompt.contains("只写一种写法"))
    }

    @Test
    fun `要求宁缺毋滥不凑数`() {
        assertTrue(prompt.contains("宁缺毋滥") || prompt.contains("不要为了凑数"))
    }
}
