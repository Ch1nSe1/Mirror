package memore.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 质疑 / 讨论的 AI 输出解析（JSON 数组 → 卡片）。
 *
 * 为什么容错要这么宽：模型经常前后带话、包一层对象、或者选项少给一个 ——
 * 一条解析不了就整轮作废太浪费，但**没有选项的条目必须丢掉**（那种卡片点不了）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DraftAskTest {

    @Test
    fun parsesQuestionsWithOptions() {
        val raw = """[{"question":"核心想说什么？","options":["A","B"]},{"question":"谁能看？","options":["自己","别人","都行"]}]"""
        val items = parseDraftAskItems(raw)
        assertEquals(2, items.size)
        assertEquals("核心想说什么？", items[0].text)
        assertEquals(listOf("A", "B"), items[0].options)
        assertEquals(listOf("自己", "别人", "都行"), items[1].options)
    }

    /** 讨论用的是 advice 键，同一张卡。 */
    @Test
    fun parsesAdviceKeyToo() {
        val raw = """[{"advice":"开头换个顺序试试","options":["采纳","不采纳"]}]"""
        val items = parseDraftAskItems(raw)
        assertEquals(1, items.size)
        assertEquals("开头换个顺序试试", items[0].text)
    }

    /** 前后带话、包着 markdown 代码块也要能解析。 */
    @Test
    fun toleratesWrappingAndProse() {
        val raw = "好的，这是问题：\n```json\n[{\"question\":\"Q\",\"options\":[\"x\",\"y\"]}]\n```\n希望有帮助"
        val items = parseDraftAskItems(raw)
        assertEquals(1, items.size)
        assertEquals("Q", items[0].text)
    }

    @Test
    fun dropsItemsWithoutOptionsOrText() {
        val raw = """[{"options":["没有题干"]},{"question":"没有选项","options":[]},{"question":"好的","options":["  ","有效"]}]"""
        val items = parseDraftAskItems(raw)
        assertEquals("只剩一条有题干也有选项的", 1, items.size)
        assertEquals(listOf("有效"), items[0].options)
    }

    @Test
    fun capsAtFiveItemsAndFourOptions() {
        val many = (1..8).joinToString(",") { """{"question":"Q$it","options":["1","2","3","4","5"]}""" }
        val items = parseDraftAskItems("[$many]")
        assertEquals("用户定的是 2~5 个", 5, items.size)
        assertEquals("选项最多 4 个", 4, items[0].options.size)
    }

    @Test
    fun garbageInEmptyOut() {
        assertTrue(parseDraftAskItems("").isEmpty())
        assertTrue(parseDraftAskItems("模型今天不想说话").isEmpty())
        assertTrue(parseDraftAskItems("{not json at all").isEmpty())
    }

    /** 存进草稿的成文格式：问/答、建议/回应；跳过时写"（跳过）"。 */
    @Test
    fun blockFormats() {
        val q = DraftAskItem("这句什么意思？", listOf("A", "B"))
        assertEquals("问：这句什么意思？\n答：A", formatAskBlock(q, "A"))
        assertEquals("问：这句什么意思？\n答：（跳过）", formatAskBlock(q, null))
        assertEquals("讨论：换个开头\n回应：采纳", formatSuggestBlock(DraftAskItem("换个开头", listOf("采纳")), "采纳"))
    }
}
