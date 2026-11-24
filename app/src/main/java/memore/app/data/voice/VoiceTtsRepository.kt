package memore.app.data.voice

import android.content.Context
import memore.app.core.voice.EvTtsProvider
import memore.app.core.voice.TtsProvider
import memore.app.core.voice.VoiceInfo
import memore.app.core.voice.VoiceTranslation
import memore.app.utils.GsonInstance
import memore.app.utils.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class VoiceTtsRepository(
    private val context: Context,
    private val settingsRepository: VoiceSettingsRepository
) {

    private val player = VoicePlayer()

    /**
     * 仓库自己的 scope。
     * 翻译 / 合成的实际工作不再跑在页面的 `rememberCoroutineScope()` 上 ——
     * 那样一离开时间线页面协程就被取消：转圈（当时是内存状态）消失，
     * 而且工作被掐断在中间（译文/音频写了一半）。
     */
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _translatingIds = MutableStateFlow<Set<String>>(emptySet())
    /** 正在翻译的记录 id（**可多个**）。UI 观察它显示转圈。 */
    val translatingIds: StateFlow<Set<String>> = _translatingIds.asStateFlow()

    private val _synthesizingIds = MutableStateFlow<Set<String>>(emptySet())
    /** 正在合成（TTS）的记录 id（**可多个**）。 */
    val synthesizingIds: StateFlow<Set<String>> = _synthesizingIds.asStateFlow()

    private val _events = MutableSharedFlow<VoiceTtsEvent>(extraBufferCapacity = 16)
    /** 完成 / 失败事件（一次性）：UI 收到后重读该记录语音状态、播音、报错。 */
    val events: SharedFlow<VoiceTtsEvent> = _events.asSharedFlow()

    /** 翻译一条记录（异步、**不绑页面**）。 */
    fun startTranslate(recordId: String, text: String) {
        repoScope.launch {
            _translatingIds.update { it + recordId }
            try {
                translateForRecord(recordId, text)
                _events.emit(VoiceTtsEvent(recordId, VoiceTtsEvent.Kind.TRANSLATE, true))
            } catch (e: Exception) {
                LogUtil.e("VoiceTts", "startTranslate $recordId", e)
                _events.emit(VoiceTtsEvent(recordId, VoiceTtsEvent.Kind.TRANSLATE, false, e.message))
            } finally {
                _translatingIds.update { it - recordId }
            }
        }
    }

    /** 再次合成（异步、**不绑页面**）。 */
    fun startResynthesize(recordId: String) {
        repoScope.launch {
            _synthesizingIds.update { it + recordId }
            try {
                resynthesizeForRecord(recordId)
                _events.emit(VoiceTtsEvent(recordId, VoiceTtsEvent.Kind.SYNTHESIZE, true))
            } catch (e: Exception) {
                LogUtil.e("VoiceTts", "startResynthesize $recordId", e)
                _events.emit(VoiceTtsEvent(recordId, VoiceTtsEvent.Kind.SYNTHESIZE, false, e.message))
            } finally {
                _synthesizingIds.update { it - recordId }
            }
        }
    }
    private val cacheDir = File(context.filesDir, "tts_cache")
    private val voiceDir = File(context.filesDir, "voice_cache")

    fun provider(): TtsProvider = EvTtsProvider(settingsRepository.load().ttsEndpoint)

    suspend fun listVoices(): List<VoiceInfo> = provider().listVoices()

    suspend fun translate(text: String): String {
        val settings = settingsRepository.load()
        val mainPrefs = context.getSharedPreferences("mirror_prefs", Context.MODE_PRIVATE)
        val endpoint = mainPrefs.getString("ai_endpoint", "") ?: ""
        val model = mainPrefs.getString("ai_model", "") ?: ""
        val apiKey = mainPrefs.getString("ai_api_key", "") ?: ""
        return VoiceTranslation.translate(text, endpoint, model, apiKey, settings.maxChars)
    }

    suspend fun synthesizeAndPlay(text: String): String {
        val jp = translate(text)
        val file = synthesize(jp)
        player.play(file)
        return jp
    }

    private suspend fun synthesize(japaneseText: String): File = withContext(Dispatchers.IO) {
        val settings = settingsRepository.load()
        require(settings.voiceId.isNotBlank()) { "请先在语音实验室选择音色" }
        cacheDir.mkdirs()
        val hash = sha256(
            listOf(japaneseText, settings.voiceId, settings.language,
                settings.temperature, settings.speed, settings.seed,
                settings.topP, settings.topK, settings.repetitionPenalty,
                settings.maxTokens, settings.model).joinToString("|")
        )
        val file = File(cacheDir, "$hash.wav")
        if (settings.cacheEnabled && file.exists()) return@withContext file
        val bytes = provider().synthesize(
            japaneseText, settings.voiceId, settings.language,
            settings.temperature, settings.speed, settings.seed,
            settings.topP, settings.topK, settings.repetitionPenalty,
            settings.maxTokens, settings.model
        )
        file.writeBytes(bytes)
        file
    }

    fun stopPlayback() = player.stop()

    fun release() = player.release()

    suspend fun loadRecordVoice(recordId: String): RecordVoiceState = withContext(Dispatchers.IO) {
        val f = File(voiceDir, "$recordId.json")
        if (!f.exists()) return@withContext RecordVoiceState(recordId)
        try {
            GsonInstance.gson.fromJson(f.readText(), RecordVoiceState::class.java) ?: RecordVoiceState(recordId)
        } catch (_: Exception) {
            RecordVoiceState(recordId)
        }
    }

    /** 批量读取指定记录的语音状态：只读目录一次，避免列表每张卡片各自打开文件。 */
    suspend fun loadRecordVoiceBulk(ids: List<String>): Map<String, RecordVoiceState> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        val idSet = ids.toSet()
        val result = mutableMapOf<String, RecordVoiceState>()
        val files = voiceDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
        files.forEach { f ->
            val id = f.name.removeSuffix(".json")
            if (id in idSet) {
                val st = try {
                    GsonInstance.gson.fromJson(f.readText(), RecordVoiceState::class.java)
                } catch (_: Exception) { null }
                result[id] = st ?: RecordVoiceState(id)
            }
        }
        // 无状态记录也补默认态，保证调用方总能命中
        idSet.forEach { if (it !in result) result[it] = RecordVoiceState(it) }
        result
    }

    suspend fun translateForRecord(recordId: String, text: String): String {
        val existing = loadRecordVoice(recordId)
        if (existing.status == RecordVoiceStatus.TRANSLATED && !existing.translation.isNullOrBlank()) {
            return existing.translation
        }
        val jp = translate(text)
        saveRecordVoice(RecordVoiceState(recordId, jp, null, RecordVoiceStatus.TRANSLATED))
        return jp
    }

    suspend fun synthesizeForRecord(recordId: String, translation: String): File {
        val settings = settingsRepository.load()
        require(settings.voiceId.isNotBlank()) { "请先在语音实验室选择音色" }
        withContext(Dispatchers.IO) { voiceDir.mkdirs() }
        val file = File(voiceDir, "$recordId.wav")
        val bytes = provider().synthesize(
            translation, settings.voiceId, settings.language,
            settings.temperature, settings.speed, settings.seed,
            settings.topP, settings.topK, settings.repetitionPenalty,
            settings.maxTokens, settings.model
        )
        withContext(Dispatchers.IO) { file.writeBytes(bytes) }
        saveRecordVoice(RecordVoiceState(recordId, translation, file.absolutePath, RecordVoiceStatus.AUDIO_READY))
        return file
    }

    fun playAudioFile(path: String, onComplete: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        player.play(File(path), onComplete, onError)
    }

    suspend fun deleteRecordVoice(recordId: String) = withContext(Dispatchers.IO) {
        File(voiceDir, "$recordId.json").delete()
        File(voiceDir, "$recordId.wav").delete()
    }

    suspend fun resynthesizeForRecord(recordId: String): File {
        val state = loadRecordVoice(recordId)
        val translation = state.translation?.takeIf { it.isNotBlank() }
            ?: throw RuntimeException("没有可用译文，请先翻译")
        return synthesizeForRecord(recordId, translation)
    }

    private suspend fun saveRecordVoice(state: RecordVoiceState) = withContext(Dispatchers.IO) {
        voiceDir.mkdirs()
        File(voiceDir, "${state.recordId}.json").writeText(GsonInstance.gson.toJson(state))
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * 语音翻译 / 合成的一次性完成事件。
 * 工作跑在 [VoiceTtsRepository] 自己的 scope 上，UI 靠这个事件重读状态、播音、报错 ——
 * 不再由调用方在页面的协程里手动回写（那样离开页面就全丢了）。
 */
data class VoiceTtsEvent(
    val recordId: String,
    val kind: Kind,
    val ok: Boolean,
    val message: String? = null
) {
    enum class Kind { TRANSLATE, SYNTHESIZE }
}
