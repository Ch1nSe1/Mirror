package memore.app.utils

import android.media.audiofx.Equalizer

/**
 * 给标准音频会话附加轻量高频提亮，只在应用内生效，不改动 wav 文件。
 * 微信/网易云走的是系统默认媒体会话，本身会吃到系统音效；这里给 App 自己的会话补回相近的听感。
 */
object AudioEnhancer {

    private const val BOOST_MB = 500

    private val effects = mutableListOf<Equalizer>()

    fun applyToSession(sessionId: Int) {
        if (sessionId <= 0) return
        try {
            val eq = Equalizer(0, sessionId)
            val bands = eq.numberOfBands
            if (bands <= 0) return
            val start = maxOf(0, bands - 3)
            for (band in start until bands) {
                eq.setBandLevel(band.toShort(), BOOST_MB.toShort())
            }
            effects.add(eq)
        } catch (_: Exception) {
            // 设备不支持 Equalizer 时静默跳过，不影响播放
        }
    }
}
