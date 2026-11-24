package memore.app.core.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class EvTtsProvider(
    private val endpoint: String = "http://127.0.0.1:1899"
) : TtsProvider {

    override suspend fun listVoices(): List<VoiceInfo> = withContext(Dispatchers.IO) {
        val conn = (URL("$endpoint/voices").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
        }
        try {
            if (conn.responseCode != 200) throw RuntimeException("voices HTTP ${conn.responseCode}")
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                VoiceInfo(
                    id = o.optString("id", ""),
                    name = o.optString("name", o.optString("id", ""))
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun synthesize(
        text: String,
        voice: String,
        lang: String,
        temperature: Float?,
        speed: Float?,
        seed: Int?,
        topP: Float?,
        topK: Int?,
        repetitionPenalty: Float?,
        maxTokens: Int?,
        model: String?
    ): ByteArray =
        withContext(Dispatchers.IO) {
            val conn = (URL("$endpoint/tts").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 60000
                readTimeout = 300000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val body = JSONObject().apply {
                put("text", text)
                put("voice", voice)
                put("lang", lang)
                temperature?.let { put("temperature", it) }
                speed?.let { put("speed", it) }
                seed?.let { put("seed", it) }
                topP?.let { put("top_p", it) }
                topK?.let { put("top_k", it) }
                repetitionPenalty?.let { put("rep_pen", it) }
                maxTokens?.let { put("max_tokens", it) }
                model?.let { put("model", it) }
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            try {
                if (conn.responseCode != 200) throw RuntimeException("tts HTTP ${conn.responseCode}")
                ByteArrayOutputStream().use { out ->
                    conn.inputStream.copyTo(out)
                    out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        }
}
