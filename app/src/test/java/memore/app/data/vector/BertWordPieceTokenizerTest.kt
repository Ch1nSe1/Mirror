package memore.app.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 分词器单测。
 *
 * 两套：
 *  - 小词表（合成）验证算法细节：续接 ##、未知词、标点、大小写、中文逐字
 *  - 真词表 + golden（由 HF tokenizers 在 Python 端生成）验证与上线模型一致
 */
class BertWordPieceTokenizerTest {

    // ── 合成词表：只测算法 ────────────────────────────────────

    private fun tinyTokenizer(doLowerCase: Boolean = false): BertWordPieceTokenizer {
        val vocab = buildList {
            add("[PAD]"); add("[UNK]"); add("[CLS]"); add("[SEP]")  // 0..3
            add("play"); add("##ing"); add("hello"); add("world"); add("##s")  // 4..8
            add("!"); add("，")  // 9,10
        }
        // 需要把 [UNK]/[CLS]/[SEP] 放到模型真实的 id 上
        val map = HashMap<String, Int>()
        vocab.forEachIndexed { i, t -> map[t] = i }
        map["[UNK]"] = 100; map["[CLS]"] = 101; map["[SEP]"] = 102
        return BertWordPieceTokenizer(map, maxLength = 32, doLowerCase = doLowerCase)
    }

    @Test
    fun `首尾是 CLS 与 SEP`() {
        val ids = tinyTokenizer().encode("hello")
        assertEquals(101L, ids.first())
        assertEquals(102L, ids.last())
    }

    @Test
    fun `续接片段加 ## 前缀`() {
        val ids = tinyTokenizer().encode("playing")
        // play + ##ing
        assertEquals(listOf(101L, 4L, 5L, 102L), ids.toList())
    }

    @Test
    fun `复数后缀也能拼回去`() {
        val ids = tinyTokenizer().encode("worlds")
        assertEquals(listOf(101L, 7L, 8L, 102L), ids.toList())
    }

    @Test
    fun `未知词整词变 UNK`() {
        val ids = tinyTokenizer().encode("zzzz")
        assertEquals(listOf(101L, 100L, 102L), ids.toList())
    }

    @Test
    fun `标点单独成词`() {
        val ids = tinyTokenizer().encode("hello!")
        assertEquals(listOf(101L, 6L, 9L, 102L), ids.toList())
    }

    @Test
    fun `默认不做小写`() {
        // "Hello" 不在词表（只有 hello），cased 时应为 UNK
        val ids = tinyTokenizer(doLowerCase = false).encode("Hello")
        assertEquals(listOf(101L, 100L, 102L), ids.toList())
        // 开启小写后能命中
        val lowered = tinyTokenizer(doLowerCase = true).encode("Hello")
        assertEquals(listOf(101L, 6L, 102L), lowered.toList())
    }

    @Test
    fun `超长文本被截断到 maxLength`() {
        val long = "hello ".repeat(200)
        val ids = tinyTokenizer().encode(long)
        assertTrue("长度 ${ids.size}", ids.size <= 32)
        assertEquals(102L, ids.last())
    }

    // ── 真词表 + golden ───────────────────────────────────────

    private fun realTokenizerOrSkip(): BertWordPieceTokenizer {
        val vocab = File("src/main/assets/models/vocab.txt")
        assumeTrue("缺少 vocab.txt，跳过真词表测试", vocab.exists())
        return BertWordPieceTokenizer.fromLines(vocab.readLines(), maxLength = 512)
    }

    private fun goldenOrSkip(): List<Pair<String, List<Long>>> {
        val f = File("src/test/resources/golden_tokens.json")
        assumeTrue("缺少 golden_tokens.json", f.exists())
        val text = f.readText()
        val arr = org.json.JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val idsArr = o.getJSONArray("ids")
            o.getString("text") to (0 until idsArr.length()).map { idsArr.getLong(it) }
        }
    }

    @Test
    fun `与 HuggingFace tokenizer 的 golden 一致`() {
        val tok = realTokenizerOrSkip()
        val golden = goldenOrSkip()
        assertTrue(golden.isNotEmpty())
        val mismatches = mutableListOf<String>()
        for ((text, expected) in golden) {
            val actual = tok.encode(text).toList()
            if (actual != expected) {
                mismatches += "文本「$text」\n  期望 ${expected}\n  实际 $actual"
            }
        }
        assertTrue("分词与 golden 不一致：\n${mismatches.joinToString("\n")}", mismatches.isEmpty())
    }

    @Test
    fun `中文按单字切分`() {
        val tok = realTokenizerOrSkip()
        val text = "今天很累"
        val ids = tok.encode(text)
        // 4 个汉字 → CLS + 4 + SEP
        assertEquals("「$text」应为 6 个 id，实际 ${ids.toList()}", 6, ids.size)
    }

    @Test
    fun `真实词表里常见中文不算未知`() {
        val tok = realTokenizerOrSkip()
        val ids = tok.encode("今天的天气很好").toList()
        assertTrue("出现了 UNK：$ids", 100L !in ids.drop(1).dropLast(1))
    }

    @Test
    fun `上下文长度上限是 512`() {
        val tok = realTokenizerOrSkip()
        val ids = tok.encode("记忆".repeat(1000))
        assertEquals(512, ids.size)
        assertEquals(102L, ids.last())
    }
}
