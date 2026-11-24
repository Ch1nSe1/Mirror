package memore.app.utils

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import memore.app.R
import kotlin.math.roundToInt

private const val TOAST_DEDUP_MS = 2000L
private var lastMessage: String? = null
private var lastShownAt = 0L

fun Context.memoreToast(msg: String, duration: Int = Toast.LENGTH_SHORT) {
    val now = SystemClock.elapsedRealtime()
    if (msg == lastMessage && now - lastShownAt < TOAST_DEDUP_MS) return
    lastMessage = msg
    lastShownAt = now

    val theme = ThemeManager.getCurrent()
    val layout = LayoutInflater.from(this).inflate(R.layout.memore_toast, null)
    val text = layout.findViewById<TextView>(android.R.id.message)
    text.text = msg
    text.setTextColor(theme.textPrimary.toArgb())
    val density = resources.displayMetrics.density
    layout.background = GradientDrawable().apply {
        cornerRadius = (12 * density).roundToInt().toFloat()
        setColor(theme.surfaceContainerHigh.toArgb())
        setStroke((1 * density).roundToInt(), theme.outlineVariant.toArgb())
    }
    val toast = Toast.makeText(this, "", duration)
    toast.view = layout
    toast.show()
}
