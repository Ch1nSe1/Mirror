package memore.app.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterBigramTest {

    @Test
    fun `中文切成二字片段`() {
        assertEquals(setOf("规矩", "矩那"), CharacterBigram.bigrams("规矩那"))
        assertEquals(setOf("跟A", "AI", "I定", "定规", "规矩"), CharacterBigram.bigrams("跟AI定规矩"))
    }

    @Test
    fun `空白不影响切分`() {
        assertEquals(CharacterBigram.bigrams("孤独的夜晚"), CharacterBigram.bigrams("孤独 的 夜晚"))
    }

    @Test
    fun `命中比例是 0 到 1`() {
        val q = CharacterBigram.bigrams("定规矩")
        assertEquals(1f, CharacterBigram.score(q, "跟 AI 定规矩那会儿"), 0.001f)
        assertEquals(0f, CharacterBigram.score(q, "今天天气不错"), 0.001f)
        val partial = CharacterBigram.score(q, "规矩")
        assertTrue(partial > 0f && partial < 1f)
    }

    @Test
    fun `空查询与单字不会崩`() {
        assertEquals(0f, CharacterBigram.score(emptySet(), "任何内容"), 0.001f)
        assertEquals(1f, CharacterBigram.score(CharacterBigram.bigrams("雨"), "下雨了"), 0.001f)
    }
}