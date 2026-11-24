package memore.app.core.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 提示词里**不许出现用户的私人正文**（2026-11-21）。
 *
 * 事故：默认分析提示词的 few-shot 示例直接用了用户的真实句子与人名
 * （具体串不写在这里——测试文件本身也在仓库里，写进来就是二次泄露）。
 *
 * 词表来自**仓库外**：环境变量 `PRIVATE_TERMS`（一行一个），
 * 本地由 `~/.dsh/private-terms.txt` 提供、CI 由 repo secret 提供；
 * 没有词表时跳过（本地开发不被阻塞，真门禁在 pre-commit 与 CI）。
 *
 * 另外留一条不依赖词表的结构断言：示例必须是中性占位。
 */
class PromptsPrivacyTest {

    private fun terms(): List<String> =
        (System.getenv("PRIVATE_TERMS") ?: "")
            .split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    @Test
    fun `默认与分析变体都不含词表里的私人内容`() {
        val list = terms()
        assumeTrue("没有 PRIVATE_TERMS，跳过（门禁在 pre-commit / CI）", list.isNotEmpty())
        val (normal, withSummary) = Prompts.analyzePromptVariantsForTest()
        for (text in listOf(normal, withSummary)) {
            for (term in list) {
                assertFalse("提示词里仍然出现私人内容: $term", text.contains(term))
            }
        }
    }

    @Test
    fun `附加补丁也不含词表里的私人内容`() {
        val list = terms()
        assumeTrue("没有 PRIVATE_TERMS，跳过（门禁在 pre-commit / CI）", list.isNotEmpty())
        for (term in list) {
            assertFalse("补丁里仍然出现私人内容: $term", Prompts.ANALYZE_FIELDS_SUPPLEMENT.contains(term))
        }
    }

    @Test
    fun `示例使用中性占位而不是具体人名`() {
        val (normal, _) = Prompts.analyzePromptVariantsForTest()
        assert(normal.contains("对象名"))
        // 中性占位检查：例子必须是占位串，不能是真名（v8 起 JSON 用带空格的写法）
        assertTrue("示例该用占位串", normal.contains("\"name\": \"对象名\"") || normal.contains("\"name\":\"对象名\""))
    }
}
