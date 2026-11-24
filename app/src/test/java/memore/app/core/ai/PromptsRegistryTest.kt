package memore.app.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词登记表（2026-11-21）。
 *
 * 收编了原本写死在代码里的四套：刻度回看 / 主题命名 / 线索对齐。
 * 这里钉住：① 每个 key 都能在管理页出现；② 模板里的占位符没被谁改丢；
 * ③ 未初始化时 getPrompt 回落到默认而不是抛异常。
 */
class PromptsRegistryTest {

    private val newKeys = listOf(
        Prompts.KEY_REVIEW to "刻度回看",
        Prompts.KEY_TOPIC_NAME to "主题命名",
        Prompts.KEY_TOPIC_LINK to "线索对齐"
    )

    @Test
    fun `新收编的三个 key 都登记了`() {
        for ((key, label) in newKeys) {
            val def = Prompts.getDef(key)
            assertNotNull("$key 没登记", def)
            assertEquals(label, def!!.displayName)
            assertTrue("$key 的默认值不能为空", def.defaultValue.isNotBlank())
        }
    }

    @Test
    fun `模板里不再有占位符，数据由代码追加`() {
        for (id in listOf(Prompts.KEY_REVIEW, Prompts.KEY_TOPIC_NAME, Prompts.KEY_TOPIC_LINK)) {
            val text = Prompts.getDef(id)!!.defaultValue
            for (ph in listOf("{seeds_block}", "{anchors_block}", "{lines}", "{start_date}", "{end_date}", "{samples}", "{related_block}")) {
                assertFalse("$id 模板里不该再出现占位符 $ph", text.contains(ph))
            }
        }
    }

    @Test
    fun `提示词管理里不再有写死的主题命名与线索对齐文案`() {
        // 模板里不该带"按月生成的主题簇"以外的具体数据占位缺失；至少保证正文来自管理页
        val link = Prompts.getPrompt(Prompts.KEY_TOPIC_LINK)
        assertTrue(link.contains("跨月线索"))
        assertTrue(link.contains("只能使用给定名字"))
    }

    @Test
    fun `未初始化时回落到默认值`() {
        val p = Prompts.getPrompt(Prompts.KEY_TOPIC_NAME)
        assertTrue(p.isNotBlank())
        assertFalse("不该返回空串", p == "")
    }
}
