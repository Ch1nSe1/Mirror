package memore.app.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ② 对齐输入升级（2026-11-19）。
 *
 * 钉住两件事：
 * ① 送给对齐模型的提示词里，**必须带每簇的关键词和代表句**——否则模型只能按字面猜同义；
 * ② 解析规则不变：成员只能取自给定名字（模型新增的名字一律丢弃），少于两条线索视为失败。
 *
 * 纯 JVM 测试，不需要 Android / 网络。
 */
class TopicThreadsPromptTest {

    private fun cluster(name: String, count: Int, ids: List<String>, summary: String = "", keywords: String = ""): MonthlyTopicsService.NamedCluster =
        MonthlyTopicsService.NamedCluster(name = name, count = count, recordIds = ids, summary = summary, keywords = keywords)

    private fun months() = listOf(
        MonthlyTopicsService.NamedMonth(
            month = "2026-07",
            clusters = listOf(
                cluster("关系结构动力", 12, listOf("r1", "r2"), summary = "我发现我总在关系里先把对方推远。", keywords = "关系/依附"),
                cluster("追星与自我认同", 5, listOf("r3"), summary = "看着舞台上的她，我想知道自己要什么。", keywords = "追星/认同")
            )
        ),
        MonthlyTopicsService.NamedMonth(
            month = "2026-08",
            clusters = listOf(
                cluster("依恋与自我剖析", 9, listOf("r4"), summary = "我在亲密关系里反复试探边界。", keywords = "依恋/关系")
            )
        )
    )

    @Test
    fun `对齐提示词带上关键词和代表句`() {
        val prompt = TopicThreads.buildAlignPrompt(months())
        assertTrue("要有关键词", prompt.contains("关键词"))
        assertTrue("要有代表句", prompt.contains("代表句"))
        assertTrue("要有条数", prompt.contains("12条"))
        assertTrue(prompt.contains("关系/依附"))
        assertTrue(prompt.contains("我发现我总在关系里先把对方推远。"))
        assertTrue(prompt.contains("依恋与自我剖析"))
    }

    @Test
    fun `对齐提示词要求按内容判断而不是字面`() {
        val prompt = TopicThreads.buildAlignPrompt(months())
        assertTrue(prompt.contains("判断依据是"))
        assertTrue(prompt.contains("不是字面"))
        assertTrue(prompt.contains("只能使用给定名字"))
    }

    @Test
    fun `没有代表句的簇不会输出空引号`() {
        val prompt = TopicThreads.buildAlignPrompt(
            listOf(MonthlyTopicsService.NamedMonth("2026-09", listOf(cluster("独自", 1, listOf("r9")))))
        )
        assertFalse("空代表句不该出现", prompt.contains("代表句「」"))
    }

    @Test
    fun `解析只接受给定名字作为成员`() {
        val text = """{"threads":[
            {"name":"关系演变","members":["关系结构动力","依恋与自我剖析","模型凭空造的名字"]},
            {"name":"追星与梦","members":["追星与自我认同"]}
        ]}"""
        val threads = TopicThreads.parse(text, months())
        assertEquals(2, threads?.size)
        assertEquals(listOf("关系结构动力", "依恋与自我剖析"), threads!![0].members)
        assertFalse(threads[0].members.contains("模型凭空造的名字"))
    }

    @Test
    fun `少于两条线索视为对齐失败`() {
        val text = """{"threads":[{"name":"关系演变","members":["关系结构动力"]}]}"""
        assertNull(TopicThreads.parse(text, months()))
    }

    @Test
    fun `成员全被过滤掉时该条线索丢弃`() {
        val text = """{"threads":[
            {"name":"编的","members":["根本不存在的名字"]},
            {"name":"追星与梦","members":["追星与自我认同"]}
        ]}"""
        assertNull("只剩一条有效线索 → 失败", TopicThreads.parse(text, months()))
    }

    @Test
    fun `坏 JSON 返回 null 而不是抛异常`() {
        assertNull(TopicThreads.parse("我觉得应该这样分……", months()))
    }
}
