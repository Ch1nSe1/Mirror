package memore.app.core.ai

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输出长度上限的归属（2026-11-21 用户定）。
 *
 * · 云端模型：**不传 `max_tokens`** —— 输出多长由模型自己管；传大了还可能被 400 拒。
 * · 本地模型（llama-server）：必须传 `num_predict`，否则本地没有默认上限。
 */
class AiProtocolMaxTokensTest {

    private fun body(endpoint: String): JSONObject = JSONObject(
        AiProtocol.buildBody(
            endpoint = endpoint,
            model = "test-model",
            prompt = "hi",
            temperature = 0.3,
            numPredict = 4096,
            numCtx = 8192,
            formatJson = true
        )
    )

    @Test
    fun `云端请求不带 max_tokens`() {
        val b = body("https://api.deepseek.com")
        assertFalse("云端不该发 max_tokens: $b", b.has("max_tokens"))
    }

    @Test
    fun `本地请求仍然带 num_predict`() {
        val b = body("http://192.168.1.28:11434/api/generate")
        assertTrue("本地必须给上限", b.has("options"))
        assertTrue(b.getJSONObject("options").has("num_predict"))
    }
}
