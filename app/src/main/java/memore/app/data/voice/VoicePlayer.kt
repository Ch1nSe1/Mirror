package memore.app.data.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import memore.app.utils.AudioEnhancer
import java.io.File

class VoicePlayer {

    private var player: MediaPlayer? = null

    fun play(file: File, onComplete: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        stop()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            AudioEnhancer.applyToSession(mp.audioSessionId)
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { onComplete?.invoke() }
            mp.setOnErrorListener { _, what, extra ->
                onError?.invoke("播放失败 ($what/$extra)")
                mp.release()
                if (player === mp) player = null
                true
            }
            mp.prepareAsync()
            mp.setOnPreparedListener { it.start() }
        } catch (e: Exception) {
            onError?.invoke(e.message ?: "播放失败")
            stop()
        }
    }

    fun stop() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    fun release() = stop()
}
