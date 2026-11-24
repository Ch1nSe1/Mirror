package memore.app.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

object UiSoundPlayer {

    const val SOUND_SAVE = "sounds/save_success.wav"
    const val SOUND_FORGOT = "sounds/forgot.wav"
    const val SOUND_UNLOCK = "sounds/unlock.wav"
    const val SOUND_COMPLETE = "sounds/complete.wav"
    const val SOUND_DELETE = "sounds/delete.wav"

    // 删除音组：单条/批量删除 3 选 1（旧音 + 前两个新音）
    private val DELETE_SOUNDS = listOf(
        SOUND_DELETE,
        "sounds/delete_1.wav",
        "sounds/delete_2.wav",
        SOUND_COMPLETE
    )
    // 清空回收站音组：后三个新音 3 选 1
    private val CLEAR_SOUNDS = listOf(
        "sounds/clear_1.wav",
        "sounds/clear_2.wav",
        "sounds/clear_3.wav"
    )
    // 启动/解锁音组：原 unlock + 两个新音，3 选 1
    private val STARTUP_SOUNDS = listOf(
        SOUND_UNLOCK,
        "sounds/startup_1.wav",
        "sounds/startup_2.wav"
    )
    // 保存音组：原保存音 + 三个新音，4 选 1
    private val SAVE_SOUNDS = listOf(
        SOUND_SAVE,
        "sounds/save_1.wav",
        "sounds/save_2.wav",
        "sounds/save_3.wav"
    )
    private val TRANSLATE_SOUNDS = listOf(
        "sounds/translate_1.wav",
        "sounds/translate_2.wav",
        "sounds/translate_3.wav"
    )
    private val SYNTH_SOUNDS = listOf(
        "sounds/synth_1.wav",
        "sounds/synth_2.wav",
        "sounds/synth_3.wav",
        "sounds/synth_4.wav"
    )
    private val ATTACH_SOUNDS = listOf(
        "sounds/attach_1.wav",
        "sounds/attach_2.wav",
        "sounds/attach_3.wav"
    )
    private val MERGE_SOUNDS = listOf(
        "sounds/merge_1.wav",
        "sounds/merge_2.wav"
    )
    private val ENCRYPT_SOUNDS = listOf(
        "sounds/encrypt_1.wav",
        "sounds/encrypt_2.wav",
        "sounds/encrypt_3.wav"
    )
    private val DECRYPT_SOUNDS = listOf(
        "sounds/decrypt_1.wav",
        "sounds/decrypt_2.wav"
    )
    private const val PASSWORD_ERROR_SOUND = "sounds/password_error.wav"
    private val SUMMARY_SOUNDS = listOf(
        "sounds/summary_1.wav",
        "sounds/summary_2.wav",
        "sounds/summary_3.wav",
        "sounds/summary_4.wav",
        "sounds/summary_5.wav",
        "sounds/summary_6.wav",
        "sounds/summary_7.wav",
        "sounds/summary_8.wav"
    )
    private val ERROR_SOUNDS = listOf(
        "sounds/error_1.wav",
        "sounds/error_2.wav",
        "sounds/error_3.wav",
        "sounds/error_4.wav",
        "sounds/error_5.wav"
    )
    private val DAOZUO_DONE_SOUNDS = listOf(
        "sounds/daozuo_done_1.wav",
        "sounds/daozuo_done_2.wav"
    )
    private val EXPORT_DONE_SOUNDS = listOf(
        "sounds/export_done_1.wav",
        "sounds/export_done_2.wav"
    )
    private val AI_CLEAN_DONE_SOUNDS = listOf(
        "sounds/ai_clean_done_1.wav",
        "sounds/ai_clean_done_2.wav"
    )
    private val HIDDEN_ENCRYPT_SOUNDS = listOf(
        "sounds/hidden_encrypt_1.wav"
    )
    private val TUTORIAL_SOUNDS = listOf(
        "sounds/tutorial_1.wav",
        "sounds/tutorial_2.wav",
        "sounds/tutorial_3.wav"
    )
    private val ANCHOR_SET_SOUNDS = listOf(
        "sounds/anchor_set_1.wav",
        "sounds/anchor_set_2.wav",
        "sounds/anchor_set_3.wav"
    )
    private val ANCHOR_CLEAR_SOUNDS = listOf(
        "sounds/anchor_clear_1.wav",
        "sounds/anchor_clear_2.wav"
    )
    private val THINKING_ON_SOUNDS = listOf(
        "sounds/thinking_on_1.wav",
        "sounds/thinking_on_2.wav"
    )
    private val THINKING_OFF_SOUNDS = listOf(
        "sounds/thinking_off_1.wav",
        "sounds/thinking_off_2.wav"
    )
    private val THEME_SOUNDS: Map<String, List<String>> = mapOf(
        "graphite" to listOf("sounds/theme_graphite_1.wav"),
        "paper" to listOf("sounds/theme_paper_1.wav", "sounds/theme_paper_2.wav"),
        "midnight" to listOf("sounds/theme_midnight_1.wav", "sounds/theme_midnight_2.wav"),
        "cream" to listOf("sounds/theme_cream_1.wav", "sounds/theme_cream_2.wav"),
        "night" to listOf("sounds/theme_night_1.wav"),
        "amber" to listOf("sounds/theme_amber_1.wav")
    )
    private val FOOTNOTE_ADD_SOUNDS = listOf(
        "sounds/footnote_add_1.wav",
        "sounds/footnote_add_2.wav",
        "sounds/footnote_add_3.wav",
        "sounds/footnote_add_4.wav"
    )
    private val FOOTNOTE_DELETE_SOUNDS = listOf(
        "sounds/footnote_delete_1.wav",
        "sounds/footnote_delete_2.wav",
        "sounds/footnote_delete_3.wav"
    )
    private val ALBUM_ENTER_SOUNDS = listOf(
        "sounds/album_enter_1.wav",
        "sounds/album_enter_2.wav",
        "sounds/album_enter_3.wav",
        "sounds/album_enter_4.wav"
    )
    private val IMPORT_DONE_SOUNDS = listOf(
        "sounds/import_done_1.wav",
        "sounds/import_done_2.wav",
        "sounds/import_done_3.wav",
        "sounds/import_done_4.wav"
    )
    private val AMENDMENT_DONE_SOUNDS = listOf(
        "sounds/amendment_done_1.wav",
        "sounds/amendment_done_2.wav",
        "sounds/amendment_done_3.wav",
        "sounds/amendment_done_4.wav"
    )
    private val RESTORE_DONE_SOUNDS = listOf(
        "sounds/restore_done_1.wav",
        "sounds/restore_done_2.wav",
        "sounds/restore_done_3.wav",
        "sounds/restore_done_4.wav"
    )
    private val GREETING_MORNING_SOUNDS = listOf(
        "sounds/greeting_morning_1.wav",
        "sounds/greeting_morning_2.wav",
        "sounds/greeting_morning_3.wav"
    )
    private val GREETING_NOON_SOUNDS = listOf(
        "sounds/greeting_noon_1.wav",
        "sounds/greeting_noon_2.wav",
        "sounds/greeting_noon_3.wav"
    )
    private val GREETING_EVENING_SOUNDS = listOf(
        "sounds/greeting_evening_1.wav",
        "sounds/greeting_evening_2.wav",
        "sounds/greeting_evening_3.wav"
    )
    private val GREETING_NIGHT_SOUNDS = listOf(
        "sounds/greeting_night_1.wav",
        "sounds/greeting_night_2.wav",
        "sounds/greeting_night_3.wav",
        "sounds/greeting_night_4.wav",
        "sounds/greeting_night_5.wav"
    )
    // 旧启动/解锁音作为通用问候层，任何时段都可能随机到。
    private val GREETING_COMMON_SOUNDS = listOf(
        "sounds/greeting_common_1.wav",
        "sounds/greeting_common_2.wav",
        "sounds/greeting_common_3.wav"
    )
    private val BATCH_ENTER_SOUNDS = listOf(
        "sounds/batch_enter_1.wav",
        "sounds/batch_enter_2.wav",
        "sounds/batch_enter_3.wav"
    )
    private val BATCH_EXIT_SOUNDS = listOf(
        "sounds/batch_exit_1.wav",
        "sounds/batch_exit_2.wav",
        "sounds/batch_exit_3.wav"
    )
    private val TAG_CREATE_SOUNDS = listOf(
        "sounds/tag_create_1.wav",
        "sounds/tag_create_2.wav",
        "sounds/tag_create_3.wav"
    )
    private val TAG_USE_SOUNDS = listOf(
        "sounds/tag_use_1.wav",
        "sounds/tag_use_2.wav",
        "sounds/tag_use_3.wav"
    )
    private val PROTOCOL_CREATE_SOUNDS = listOf(
        "sounds/protocol_create_1.wav",
        "sounds/protocol_create_2.wav",
        "sounds/protocol_create_3.wav"
    )
    private val PROTOCOL_USE_SOUNDS = listOf(
        "sounds/protocol_use_1.wav",
        "sounds/protocol_use_2.wav",
        "sounds/protocol_use_3.wav"
    )
    private val PRESET_SAVE_SOUNDS = listOf(
        "sounds/preset_save_1.wav",
        "sounds/preset_save_2.wav",
        "sounds/preset_save_3.wav",
        "sounds/preset_save_4.wav"
    )
    private val API_PRESET_SOUNDS = listOf(
        "sounds/api_preset_1.wav",
        "sounds/api_preset_2.wav",
        "sounds/api_preset_3.wav",
        "sounds/api_preset_4.wav"
    )
    private val FILTER_DONE_SOUNDS = listOf(
        "sounds/filter_done_1.wav",
        "sounds/filter_done_2.wav",
        "sounds/filter_done_3.wav",
        "sounds/filter_done_4.wav"
    )
    private val TAG_DELETE_SOUNDS = listOf(
        "sounds/tag_delete_1.wav",
        "sounds/tag_delete_2.wav"
    )
    private val PROTOCOL_DELETE_SOUNDS = listOf(
        "sounds/protocol_delete_1.wav",
        "sounds/protocol_delete_2.wav"
    )
    private val CLEANUP_DONE_SOUNDS = listOf(
        "sounds/cleanup_done_1.wav",
        "sounds/cleanup_done_2.wav",
        "sounds/cleanup_done_3.wav"
    )
    private val REDACTION_ON_SOUNDS = listOf(
        "sounds/redaction_on_1.wav",
        "sounds/redaction_on_2.wav",
        "sounds/redaction_on_3.wav",
        "sounds/redaction_on_4.wav"
    )
    private val REDACTION_OFF_SOUNDS = listOf(
        "sounds/redaction_off_1.wav",
        "sounds/redaction_off_2.wav",
        "sounds/redaction_off_3.wav",
        "sounds/redaction_off_4.wav"
    )

    // MediaPlayer 缓存：与微信/网易云同走标准媒体会话，系统音效和应用内 EQ 都生效。
    private const val PLAYER_CACHE_MAX = 24
    private val players = java.util.LinkedHashMap<String, MediaPlayer>(PLAYER_CACHE_MAX, 0.75f, true)
    private var activePlayer: MediaPlayer? = null
    private val random = java.util.Random()
    private val lastRandom = mutableMapOf<String, String>()
    @Volatile
    private var enabled = true
    @Volatile
    private var store: SoundConfigStore? = null

    /** 注入配置存储（设置页初始化时调用一次；用于读取用户对音效位置的覆盖）。 */
    fun init(context: Context) {
        if (store == null) {
            store = SoundConfigStore(context)
            // 启动兜底清理：移除/重置后残留的孤导入音频（上个会话取消导入等）。
            store?.cleanupImportOrphans()
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** 按「音效位置」播放：从配置存储解析该位置实际绑定的音频（用户覆盖优先，否则默认），随机挑一个。 */
    @Synchronized
    fun playEvent(context: Context, event: SoundEvent, themeName: String? = null) {
        if (!enabled) return
        // 用户覆盖优先（含清空=静音）：一旦某位置被覆盖（无论自定义还是清空），一律用覆盖结果，不再落默认。
        val overridden = store?.isOverridden(event.name) == true
        if (overridden) {
            val assets = store?.resolveAssets(event) ?: emptyList()
            if (assets.isNotEmpty()) playRandomInternal(context, event.name, assets)
            return
        }
        if (event == SoundEvent.THEME) {
            // 未覆盖：主题切按用主题专属音；预览（无主题名）用默认主题组合一组。
            val themed = THEME_SOUNDS[themeName ?: ""] ?: event.defaultAssets
            if (themed.isNotEmpty()) playRandomInternal(context, event.name, themed)
            return
        }
        if (event == SoundEvent.GREETING) {
            // 未覆盖：启动问候按时段细分（保持原有行为），并混入通用启动句。
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val hourSounds = when (hour) {
                in 5..10 -> GREETING_MORNING_SOUNDS
                in 11..16 -> GREETING_NOON_SOUNDS
                in 17..20 -> GREETING_EVENING_SOUNDS
                else -> GREETING_NIGHT_SOUNDS
            } + GREETING_COMMON_SOUNDS
            playRandomInternal(context, event.name, hourSounds)
            return
        }
        val assets = store?.resolveAssets(event) ?: event.defaultAssets
        playRandomInternal(context, event.name, assets)
    }

    private fun playRandomInternal(context: Context, groupKey: String, paths: List<String>) {
        if (!enabled || paths.isEmpty()) return
        val last = lastRandom[groupKey]
        val candidates = if (paths.size > 1 && last != null) paths.filter { it != last } else paths
        val chosen = candidates[random.nextInt(candidates.size)]
        lastRandom[groupKey] = chosen
        play(context, chosen)
    }

    @Synchronized
    fun preload(context: Context, assetPath: String) {
        if (players.containsKey(assetPath)) return
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            AudioEnhancer.applyToSession(mp.audioSessionId)
            if (assetPath.startsWith("/") || assetPath.startsWith("file://")) {
                // 自定义导入的音频：绝对文件路径 / file:// 形式。
                val path = if (assetPath.startsWith("file://")) android.net.Uri.parse(assetPath).path ?: assetPath else assetPath
                mp.setDataSource(path)
            } else {
                // 内置 assets/sounds 下的 wav。
                val fd = context.assets.openFd(assetPath)
                try { mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) } finally { fd.close() }
            }
            mp.prepare()
            mp.setOnCompletionListener { finished ->
                synchronized(this) {
                    if (activePlayer === finished) activePlayer = null
                }
            }
            players[assetPath] = mp
            while (players.size > PLAYER_CACHE_MAX) {
                val victim = players.entries.firstOrNull { it.value !== activePlayer } ?: break
                try { victim.value.release() } catch (_: Exception) {}
                players.remove(victim.key)
            }
        } catch (e: Exception) {
            LogUtil.e("UiSoundPlayer", "preload: $assetPath", e)
        }
    }

    @Synchronized
    fun play(context: Context, assetPath: String) {
        if (!enabled) return
        preload(context, assetPath)
        val mp = players[assetPath] ?: return
        activePlayer?.takeIf { it !== mp }?.let { old ->
            // 旧音停后必须释放并从缓存移除：MediaPlayer stop() 后进入 Stopped 态，
            // 缓存里复用直接 seekTo/start() 会抛 IllegalState，表现为"该音失效/时有时无"。
            try { old.stop() } catch (_: Exception) {}
            try { old.release() } catch (_: Exception) {}
            players.entries.removeAll { it.value === old }
        }
        activePlayer = mp
        try {
            mp.seekTo(0)
            mp.start()
        } catch (e: Exception) {
            LogUtil.e("UiSoundPlayer", "play: $assetPath", e)
            try { players.remove(assetPath)?.release() } catch (_: Exception) {}
            activePlayer = null
        }
    }

    @Synchronized
    private fun playRandom(context: Context, groupKey: String, paths: List<String>) {
        if (!enabled || paths.isEmpty()) return
        val last = lastRandom[groupKey]
        val candidates = if (paths.size > 1 && last != null) paths.filter { it != last } else paths
        val chosen = candidates[random.nextInt(candidates.size)]
        lastRandom[groupKey] = chosen
        play(context, chosen)
    }

    fun playError(context: Context) = playEvent(context, SoundEvent.ERROR)

    fun playDaozuoDone(context: Context) = playEvent(context, SoundEvent.DAOZUO_DONE)

    fun playExportDone(context: Context) = playEvent(context, SoundEvent.EXPORT_DONE)

    fun playAiCleanDone(context: Context) = playEvent(context, SoundEvent.AI_CLEAN_DONE)

    fun playHiddenEncrypt(context: Context) = playEvent(context, SoundEvent.HIDDEN_ENCRYPT)

    fun playTutorialOpen(context: Context) = playEvent(context, SoundEvent.TUTORIAL)

    fun playAnchorSet(context: Context) = playEvent(context, SoundEvent.ANCHOR_SET)

    fun playAnchorClear(context: Context) = playEvent(context, SoundEvent.ANCHOR_CLEAR)

    fun playThinkingOn(context: Context) = playEvent(context, SoundEvent.THINKING_ON)

    fun playThinkingOff(context: Context) = playEvent(context, SoundEvent.THINKING_OFF)

    fun playThemeVoice(context: Context, themeName: String) = playEvent(context, SoundEvent.THEME, themeName)

    fun playFootnoteAdd(context: Context) = playEvent(context, SoundEvent.FOOTNOTE_ADD)

    fun playFootnoteDelete(context: Context) = playEvent(context, SoundEvent.FOOTNOTE_DELETE)

    fun playAlbumEnter(context: Context) = playEvent(context, SoundEvent.ALBUM_ENTER)

    fun playBatchEnter(context: Context) = playEvent(context, SoundEvent.BATCH_ENTER)

    fun playBatchExit(context: Context) = playEvent(context, SoundEvent.BATCH_EXIT)

    fun playTagCreate(context: Context) = playEvent(context, SoundEvent.TAG_CREATE)

    fun playTagUse(context: Context) = playEvent(context, SoundEvent.TAG_USE)

    fun playProtocolCreate(context: Context) = playEvent(context, SoundEvent.PROTOCOL_CREATE)

    fun playProtocolUse(context: Context) = playEvent(context, SoundEvent.PROTOCOL_USE)

    fun playImportDone(context: Context) = playEvent(context, SoundEvent.IMPORT_DONE)

    fun playAmendmentDone(context: Context) = playEvent(context, SoundEvent.AMENDMENT_DONE)

    fun playRestoreDone(context: Context) = playEvent(context, SoundEvent.RESTORE_DONE)

    /** 启动问候：时段专属句 + 通用启动句混合随机。早 5-10 / 中 11-16 / 晚 17-20 / 夜 21-4。 */
    fun playGreeting(context: Context) = playEvent(context, SoundEvent.GREETING)

    fun playDelete(context: Context) = playEvent(context, SoundEvent.DELETE)

    fun playClearTrash(context: Context) = playEvent(context, SoundEvent.CLEAR_TRASH)

    fun playUnlock(context: Context) = playEvent(context, SoundEvent.UNLOCK)

    fun playSave(context: Context) = playEvent(context, SoundEvent.SAVE)

    fun playTranslateComplete(context: Context) = playEvent(context, SoundEvent.TRANSLATE)

    fun playSynthComplete(context: Context) = playEvent(context, SoundEvent.SYNTH)

    fun playAttachComplete(context: Context) = playEvent(context, SoundEvent.ATTACH)

    fun playMergeComplete(context: Context) = playEvent(context, SoundEvent.MERGE)

    fun playEncryptComplete(context: Context) = playEvent(context, SoundEvent.ENCRYPT)

    fun playDecryptComplete(context: Context) = playEvent(context, SoundEvent.DECRYPT)

    fun playPasswordError(context: Context) = playEvent(context, SoundEvent.PASSWORD_ERROR)

    fun playSummaryComplete(context: Context) = playEvent(context, SoundEvent.SUMMARY)

    // ===== 新增场景音播放方法 =====
    fun playPresetSave(context: Context) = playEvent(context, SoundEvent.PRESET_SAVE)
    fun playApiPresetSwitch(context: Context) = playEvent(context, SoundEvent.API_PRESET_SWITCH)
    fun playFilterDone(context: Context) = playEvent(context, SoundEvent.FILTER_DONE)
    fun playTagDelete(context: Context) = playEvent(context, SoundEvent.TAG_DELETE)
    fun playProtocolDelete(context: Context) = playEvent(context, SoundEvent.PROTOCOL_DELETE)
    fun playCleanupDone(context: Context) = playEvent(context, SoundEvent.CLEANUP_DONE)
    fun playRedactionOn(context: Context) = playEvent(context, SoundEvent.REDACTION_ON)
    fun playRedactionOff(context: Context) = playEvent(context, SoundEvent.REDACTION_OFF)

    // ===== 第二批场景音（8.31 新增）=====
    fun playEncTempDec(context: Context) = playEvent(context, SoundEvent.ENC_TEMP_DEC)
    fun playEncTempUndec(context: Context) = playEvent(context, SoundEvent.ENC_TEMP_UNDEC)
    fun playTickCreate(context: Context) = playEvent(context, SoundEvent.TICK_CREATE)
    fun playTickDelete(context: Context) = playEvent(context, SoundEvent.TICK_DELETE)
    fun playCatCreate(context: Context) = playEvent(context, SoundEvent.CAT_CREATE)
    fun playCatDelete(context: Context) = playEvent(context, SoundEvent.CAT_DELETE)
    fun playAlbumStar(context: Context) = playEvent(context, SoundEvent.ALBUM_STAR)
    fun playExportRuleOn(context: Context) = playEvent(context, SoundEvent.EXPORT_RULE_ON)
    fun playExportRuleOff(context: Context) = playEvent(context, SoundEvent.EXPORT_RULE_OFF)
    fun playLockOn(context: Context) = playEvent(context, SoundEvent.LOCK_ON)
    fun playLockOff(context: Context) = playEvent(context, SoundEvent.LOCK_OFF)
    fun playLockReset(context: Context) = playEvent(context, SoundEvent.LOCK_RESET)
    fun playImportBlockOn(context: Context) = playEvent(context, SoundEvent.IMPORT_BLOCK_ON)
    fun playImportBlockOff(context: Context) = playEvent(context, SoundEvent.IMPORT_BLOCK_OFF)
    fun playSoundEditDelete(context: Context) = playEvent(context, SoundEvent.SOUND_EDIT_DELETE)
    fun playSoundEditImport(context: Context) = playEvent(context, SoundEvent.SOUND_EDIT_IMPORT)
    fun playSoundEditClear(context: Context) = playEvent(context, SoundEvent.SOUND_EDIT_CLEAR)
    fun playSoundEditReset(context: Context) = playEvent(context, SoundEvent.SOUND_EDIT_RESET)

    fun preloadSave(context: Context) {
        store?.resolveAssets(SoundEvent.SAVE)?.forEach { preload(context, it) }
    }
}
