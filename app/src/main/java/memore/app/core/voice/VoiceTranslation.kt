package memore.app.core.voice

import memore.app.core.ai.AiProtocol
import memore.app.core.ai.Prompts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object VoiceTranslation {

    suspend fun translate(
        text: String,
        endpoint: String,
        model: String,
        apiKey: String,
        maxChars: Int
    ): String = withContext(Dispatchers.IO) {
        val template = Prompts.getPrompt(Prompts.KEY_VOICE_TRANSLATE)
        val prompt = Prompts.replacePrompt(template, mapOf("input" to text, "max_chars" to maxChars.toString()))
        val body = AiProtocol.buildBody(
            endpoint, model, prompt,
            temperature = 0.3, numPredict = 512, numCtx = 4096, formatJson = false,
            forceThinking = true
        )
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(AiProtocol.completionUrl(endpoint)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 120000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            AiProtocol.applyAuth(conn!!, apiKey)
            conn!!.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn!!.responseCode != 200) throw RuntimeException("翻译失败 HTTP ${conn!!.responseCode}")
            extractPlainText(conn!!.inputStream.bufferedReader().readText(), endpoint)
        } finally {
            conn?.disconnect()
        }
    }

    /** 翻译只要纯文本：只取最终 content/response，过滤思维链和包裹符号，空结果直接失败。 */
    internal fun extractPlainText(rawResponse: String, endpoint: String): String {
        val obj = JSONObject(rawResponse)
        val raw = when (AiProtocol.detect(endpoint)) {
            "openai" -> {
                val message = obj.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                message?.optString("content", "").orEmpty()
            }
            else -> obj.optString("response", "")
        }
        var text = raw.trim()
        text = text.replace(Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        text = text.replace(Regex("^\\s*```(?:json)?\\s*", setOf(RegexOption.IGNORE_CASE)), "")
        text = text.replace(Regex("\\s*```\\s*$"), "")
        text = text.trim()
        if (text.length >= 2) {
            val first = text.first()
            val last = text.last()
            if ((first == '"' && last == '"') ||
                (first == '\u201C' && last == '\u201D') ||
                (first == '\u300C' && last == '\u300D') ||
                (first == '\u300E' && last == '\u300F')) {
                text = text.substring(1, text.length - 1).trim()
            }
        }
        if (text.isBlank()) throw RuntimeException("翻译结果为空")
        return text
    }
}
