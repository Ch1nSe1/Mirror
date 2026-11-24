package memore.app.utils

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val fullFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
private val dateOnlyFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
private val isoZParser = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
private val isoZFormatter = isoZParser.withZone(ZoneId.systemDefault())
private val fileTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US).withZone(ZoneId.systemDefault())

fun formatTimestamp(timestamp: Long): String {
    return fullFormatter.format(Instant.ofEpochMilli(timestamp))
}

fun formatDateOnly(timestamp: Long): String {
    return dateOnlyFormatter.format(Instant.ofEpochMilli(timestamp))
}

fun formatIsoZ(timestamp: Long? = null): String {
    return isoZFormatter.format(if (timestamp == null) Instant.now() else Instant.ofEpochMilli(timestamp))
}

fun formatFileTimestamp(): String {
    return fileTimestampFormatter.format(Instant.now())
}

/**
 * 日期选择器 → 本地零点。
 *
 * Compose 的 `DatePicker` 返回的是"所选日历日在 **UTC** 的零点"毫秒数。
 * 直接把它当本地时间用，在 UTC+8 下就变成当天早上 08:00 ——
 * 界面上 `formatDateOnly` 显示的日期是对的，所以完全看不出来，但边界实际偏了 8 小时
 * （选"9月24日"当刻度终点，9/24 零点到早八点的记录仍算上一段）。
 * 这里取出"UTC 的那一天"，再按本机时区重新取当天零点。
 * 刻度起止、导出时间范围都必须过这一步。
 */
fun datePickerToLocalStartOfDay(pickedUtcMidnightMillis: Long): Long =
    Instant.ofEpochMilli(pickedUtcMidnightMillis)
        .atZone(ZoneOffset.UTC).toLocalDate()
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * 本地时间戳 → 日期选择器初始值（该日历日在 UTC 的零点），与上一步互逆。
 *
 * 不转换会有一个更隐蔽的问题：`DatePicker` 把初始值当 UTC 解读，
 * 所以本地时间在 00:00–08:00（UTC+8）时，选择器会**开在前一天**。
 */
fun localMillisToDatePicker(localMillis: Long): Long =
    Instant.ofEpochMilli(localMillis)
        .atZone(ZoneId.systemDefault()).toLocalDate()
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun parseIsoZ(text: String): Long? {
    return try {
        LocalDateTime.parse(text, isoZParser).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

fun formatDuration(ms: Long): String {
    val sec = ms / 1000
    val min = sec / 60
    val s = sec % 60
    return "%02d:%02d".format(min, s)
}

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    }
}
