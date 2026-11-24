package memore.app.core.voice

data class VoiceInfo(val id: String, val name: String)

interface TtsProvider {
    suspend fun listVoices(): List<VoiceInfo>
    suspend fun synthesize(
        text: String,
        voice: String,
        lang: String,
        temperature: Float? = null,
        speed: Float? = null,
        seed: Int? = null,
        topP: Float? = null,
        topK: Int? = null,
        repetitionPenalty: Float? = null,
        maxTokens: Int? = null,
        model: String? = null
    ): ByteArray
}
