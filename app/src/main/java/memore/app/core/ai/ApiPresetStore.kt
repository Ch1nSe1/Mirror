package memore.app.core.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 云端 API 预设存储。
 *
 * 预设 = 预置/自定义的厂商配置，含端点、模型、独立 API Key，以及高级思考参数。
 * 持久化于 SharedPreferences（`api_presets`，JSON 数组）。首次访问种子化 4 个默认厂商。
 */
data class ApiPreset(
    val name: String,
    val endpoint: String,
    val model: String,
    val apiKey: String = "",
    /** 思考参数格式：auto / thinking_type / reasoning_effort / none */
    val thinkingControl: String = "auto",
    /** reasoning_effort 值：none / low / high / max */
    val reasoningEffort: String = "high"
)

object ApiPresetStore {
    private const val PREFS = "mirror_prefs"
    private const val KEY = "api_presets"

    fun defaults(): List<ApiPreset> = listOf(
        ApiPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-v4-flash"),
        ApiPreset("千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        ApiPreset("智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-4.7-flash"),
        ApiPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o")
    )

    fun load(context: Context): List<ApiPreset> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, "") ?: ""
        if (raw.isBlank()) {
            val defs = defaults()
            save(context, defs)
            return defs
        }
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    ApiPreset(
                        name = o.getString("name"),
                        endpoint = o.optString("endpoint", ""),
                        model = o.optString("model", ""),
                        apiKey = o.optString("apiKey", ""),
                        thinkingControl = o.optString("thinkingControl", "auto"),
                        reasoningEffort = o.optString("reasoningEffort", "high")
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            defaults()
        }
    }

    fun save(context: Context, presets: List<ApiPreset>) {
        val arr = JSONArray()
        presets.forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("endpoint", p.endpoint)
                put("model", p.model)
                put("apiKey", p.apiKey)
                put("thinkingControl", p.thinkingControl)
                put("reasoningEffort", p.reasoningEffort)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** 恢复默认：保留用户自定义预设，只补回被删掉的默认厂商（非破坏性）。 */
    fun restoreDefaults(context: Context): List<ApiPreset> {
        val existing = load(context)
        val existingNames = existing.map { it.name }.toSet()
        val merged = existing + defaults().filter { it.name !in existingNames }
        save(context, merged)
        return merged
    }
}
