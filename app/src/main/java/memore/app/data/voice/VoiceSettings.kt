package memore.app.data.voice

data class VoiceSettings(
    val ttsEndpoint: String = "http://127.0.0.1:1899",
    val voiceId: String = "",
    val language: String = "english",
    val maxChars: Int = 300,
    val temperature: Float = 0.9f,
    val speed: Float = 1.0f,
    val seed: Int = 0,
    val topP: Float = 0.9f,
    val topK: Int = 50,
    val repetitionPenalty: Float = 1.0f,
    val maxTokens: Int = 2048,
    val model: String = "1.7b",
    val cacheEnabled: Boolean = true,
    val soundsEnabled: Boolean = true
)
