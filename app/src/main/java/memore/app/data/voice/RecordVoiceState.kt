package memore.app.data.voice

enum class RecordVoiceStatus {
    NONE,
    TRANSLATED,
    AUDIO_READY
}

data class RecordVoiceState(
    val recordId: String = "",
    val translation: String? = null,
    val audioPath: String? = null,
    val status: RecordVoiceStatus = RecordVoiceStatus.NONE
)
