package memore.app.core.ai

import org.json.JSONArray
import org.json.JSONObject
import memore.app.utils.LogUtil
import java.net.HttpURLConnection

/**
 * AI API 协议适配层。
 *
 * 支持两种协议：
 *  - Ollama:  POST {endpoint}/api/generate, 请求体 {model, prompt, stream, format, options}, 响应取 response 字段
 *  - OpenAI: POST {endpoint}/chat/completions (endpoint 通常以 /v1 结尾), 请求体 {model, messages}, 响应取 choices[0].message.content
 *
 * 协议由端点 URL 自动检测：URL 含 "/v1" 视为 OpenAI，否则视为 Ollama。
 */
object AiProtocol {
    const val OLLAMA = "ollama"
    const val OPENAI = "openai"

    /** 思考模式下 max_tokens 的放大倍数（思考 + 答案需要更多空间；服务器 n_ctx=8192 会自然截断）。 */
    private const val THINKING_MAX_TOKENS_MULTIPLIER = 8

    /**
     * 全局思考开关（仅影响 OpenAI 协议）。
     * true = 开启思考（默认）；false = 关闭思考（reasoning_effort: "none" + enable_thinking: false）。
     * 由设置页通过 [setEnableThinking] 更新，无需重启 server。
     */
    @Volatile
    private var enableThinking: Boolean = true

    fun setEnableThinking(enabled: Boolean) {
        enableThinking = enabled
    }

    fun isThinkingEnabled(): Boolean = enableThinking

    /** 云端思考强度（reasoning_effort）："high" / "max"，默认 high。由设置页更新。 */
    @Volatile
    private var thinkingStrength: String = "high"

    fun setThinkingStrength(strength: String) {
        thinkingStrength = strength
    }

    /** 思考参数格式：auto / thinking_type / reasoning_effort / none。由预设选中时设置。 */
    @Volatile
    private var thinkingControl: String = "auto"

    fun setThinkingControl(control: String) {
        thinkingControl = control
    }

    /** 云端 max_tokens 封顶（默认 0 = 不封顶用 ×4；预设高级里设 >0 才封顶。429 靠重试兜底）。 */
    @Volatile
    private var cloudMaxTokensCap: Int = 0

    fun setCloudMaxTokensCap(cap: Int) {
        cloudMaxTokensCap = cap
    }

    /** 判断思考参数是否走 thinking.type 格式（DeepSeek/智谱 需要；OpenAI/Kimi/通用 用 reasoning_effort）。 */
    private fun useThinkingType(endpoint: String): Boolean = when (thinkingControl) {
        "thinking_type" -> true
        "reasoning_effort", "none" -> false
        else -> { // auto：按端点域名识别
            val e = endpoint.lowercase()
            e.contains("deepseek") || e.contains("bigmodel") || e.contains("z.ai") || e.contains("zhipu")
        }
    }

    /** 云端判定：OpenAI 兼容云端 API 必为 https；本地 llama-server 是 http://IP。 */
    fun isCloudEndpoint(endpoint: String): Boolean = endpoint.trimStart().startsWith("https://")

    /** 云端鉴权：配置了 API Key 时加 Authorization 头（本地 llama 无 key 不发）。 */
    fun applyAuth(conn: HttpURLConnection?, apiKey: String?) {
        if (conn != null && !apiKey.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
    }

    /** 根据端点 URL 判断协议。 */
    fun detect(endpoint: String): String {
        val e = endpoint.trimEnd('/')
        // 云端 OpenAI 兼容：任何 https 端点视为 OpenAI（DeepSeek /v1、智谱 /api/paas/v4 等）
        if (e.startsWith("https://")) return OPENAI
        // 本地：含 /v1 视为 OpenAI（llama-server /v1/chat/completions），否则 Ollama
        return if (e.endsWith("/v1") || e.contains("/v1/")) OPENAI else OLLAMA
    }

    /** 构建完整的补全(生成)请求 URL。 */
    fun completionUrl(endpoint: String): String {
        val base = endpoint.trimEnd('/')
        return when (detect(endpoint)) {
            OPENAI -> if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            else -> if (base.endsWith("/api/generate")) base else "$base/api/generate"
        }
    }

    /** 构建协议对应的健康检查 URL（用于检测服务是否存活）。 */
    fun healthUrl(endpoint: String): String {
        val base = endpoint.trimEnd('/')
        return when (detect(endpoint)) {
            OPENAI -> "$base/models"
            else -> "$base/api/tags"
        }
    }

    /**
     * 构建协议对应的请求体 JSON 字符串。
     *
     * @param model     模型名称
     * @param prompt    提示文本
     * @param temperature 温度
     * @param numPredict 最大生成 token 数
     * @param numCtx    上下文长度（仅 Ollama 使用；OpenAI 由服务端决定）
     * @param formatJson 是否要求 JSON 输出（仅 Ollama 支持；OpenAI 不需要，但保留参数以对齐调用）
     */
    fun buildBody(
        endpoint: String,
        model: String,
        prompt: String,
        temperature: Double,
        numPredict: Int,
        numCtx: Int,
        formatJson: Boolean,
        forceNonThinking: Boolean = false,
        forceThinking: Boolean = false
    ): String {
        // 一次性读取思考开关，避免 TOCTOU；forceNonThinking 用于固定非思考的任务（如 AI 整理）
        val thinking = !forceNonThinking && (forceThinking || enableThinking)
        return when (detect(endpoint)) {
            OPENAI -> JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                // 智谱/DeepSeek 思考模式下不传 temperature：实测 temperature=0.1 曾导致超时/content 为空，
                // 该行为已保留为正式兼容策略；如后续复测确认与 temperature 无关，可恢复无条件传参。
                if (!(isCloudEndpoint(endpoint) && thinking && useThinkingType(endpoint))) {
                    put("temperature", temperature)
                }
                // 2026-11-21（用户定）：云端不传 max_tokens —— 输出长度由模型自己管，
                // 传大了反而可能被 400 拒（DeepSeek 上限 8K）。只有本地模型需要设上限。
                if (!isCloudEndpoint(endpoint)) {
                    val maxOut = if (thinking) numPredict * THINKING_MAX_TOKENS_MULTIPLIER else numPredict
                    put("max_tokens", maxOut)
                }
                put("stream", false)
                if (isCloudEndpoint(endpoint)) {
                    // 云端思考参数按预设的 thinkingControl 分路（冗余交给用户，预设高级里可改）
                    when {
                        thinkingControl == "none" -> { /* 不发送任何思考参数，交给模型默认 */ }
                        useThinkingType(endpoint) -> {
                            // DeepSeek/智谱 格式：thinking.type 控制开关；关闭时勿同时发 reasoning_effort
                            if (thinking) {
                                put("thinking", JSONObject().put("type", "enabled"))
                                put("reasoning_effort", thinkingStrength)
                            } else {
                                put("thinking", JSONObject().put("type", "disabled"))
                            }
                        }
                        else -> {
                            // OpenAI/Kimi/通用：reasoning_effort 控制
                            put("reasoning_effort", if (thinking) thinkingStrength else "none")
                        }
                    }
                } else {
                    // 本地 llama-server：chat_template_kwargs.enable_thinking（实测生效）
                    // 注：旧参数 reasoning_budget_tokens 已被验证无效并移除；思考长度由 max_tokens(×4) 兜底
                    if (thinking) {
                        put("chat_template_kwargs", JSONObject().put("enable_thinking", true))
                    } else {
                        put("reasoning_effort", "none")
                        put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                    }
                }
            }.toString()

            else -> JSONObject().apply {
                put("model", model)
                put("prompt", prompt)
                put("stream", false)
                if (formatJson) put("format", "json")
                put("options", JSONObject().apply {
                    put("temperature", temperature)
                    put("num_predict", numPredict)
                    put("num_ctx", numCtx)
                })
            }.toString()
        }
    }

    /**
     * 从协议响应中提取实际生成文本。
     *
     * Ollama: 返回 response 字段。
     * OpenAI: 返回 choices[0].message.content；若为空则回退到 reasoning_content，再回退到原始文本。
     */
    fun extractText(rawResponse: String, endpoint: String): String {
        return try {
            val obj = JSONObject(rawResponse)
            when (detect(endpoint)) {
                OPENAI -> {
                    val choices = obj.optJSONArray("choices")
                    val message = choices?.optJSONObject(0)?.optJSONObject("message")
                    val content = message?.optString("content", "").orEmpty().trim()
                    if (content.isNotEmpty()) {
                        content
                    } else {
                        // content 空时回退 reasoning_content：思考流里常嵌着最终 JSON，
                        // JsonCleaner 会从混合文本中抽出 JSON 数组——不能砍掉这条路径
                        val reasoning = message?.optString("reasoning_content", "").orEmpty().trim()
                        if (reasoning.isNotEmpty()) reasoning else rawResponse
                    }
                }
                else -> obj.optString("response", rawResponse).trim()
            }
        } catch (e: Exception) {
            LogUtil.e("AiProtocol", "extractText 解析失败，返回原始文本", e.message ?: "")
            rawResponse
        }
    }
}
