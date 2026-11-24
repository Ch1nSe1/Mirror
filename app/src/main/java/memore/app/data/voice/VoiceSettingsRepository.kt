package memore.app.data.voice

import android.content.Context

class VoiceSettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("voice_prefs", Context.MODE_PRIVATE)

    fun load(): VoiceSettings = VoiceSettings(
        ttsEndpoint = prefs.getString("tts_endpoint", VoiceSettings().ttsEndpoint) ?: VoiceSettings().ttsEndpoint,
        voiceId = prefs.getString("voice_id", VoiceSettings().voiceId) ?: VoiceSettings().voiceId,
        language = language(),
        maxChars = prefs.getInt("max_chars", VoiceSettings().maxChars),
        temperature = prefs.getFloat("temperature", VoiceSettings().temperature),
        speed = prefs.getFloat("speed", VoiceSettings().speed),
        seed = prefs.getInt("seed", VoiceSettings().seed),
        topP = prefs.getFloat("top_p", VoiceSettings().topP),
        topK = prefs.getInt("top_k", VoiceSettings().topK),
        repetitionPenalty = prefs.getFloat("rep_pen", VoiceSettings().repetitionPenalty),
        maxTokens = prefs.getInt("max_tokens", VoiceSettings().maxTokens),
        model = prefs.getString("model", VoiceSettings().model) ?: VoiceSettings().model,
        cacheEnabled = prefs.getBoolean("cache_enabled", VoiceSettings().cacheEnabled),
        soundsEnabled = prefs.getBoolean("sounds_enabled", VoiceSettings().soundsEnabled)
    )

    /** 2026-11-22：默认语言由日语改英语；旧安装里存着的 japanese 一次性迁到 english。 */
    private fun language(): String {
        val stored = prefs.getString("language", null)
        if (stored == "japanese") {
            prefs.edit().putString("language", "english").apply()
            return "english"
        }
        return stored ?: VoiceSettings().language
    }

    fun save(settings: VoiceSettings) {
        prefs.edit()
            .putString("tts_endpoint", settings.ttsEndpoint)
            .putString("voice_id", settings.voiceId)
            .putString("language", settings.language)
            .putInt("max_chars", settings.maxChars)
            .putFloat("temperature", settings.temperature)
            .putFloat("speed", settings.speed)
            .putInt("seed", settings.seed)
            .putFloat("top_p", settings.topP)
            .putInt("top_k", settings.topK)
            .putFloat("rep_pen", settings.repetitionPenalty)
            .putInt("max_tokens", settings.maxTokens)
            .putString("model", settings.model)
            .putBoolean("cache_enabled", settings.cacheEnabled)
            .putBoolean("sounds_enabled", settings.soundsEnabled)
            .apply()
    }
}
