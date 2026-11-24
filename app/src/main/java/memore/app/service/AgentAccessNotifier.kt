package memore.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import memore.app.MainActivity

/**
 * 「有电脑请求连接」的通知（2026-11-20）。
 *
 * 为什么要有：401 时只把对方加进 `pendingDevices`，只有主动打开「Agent 连接」页才看得见。
 * 电脑在等批准，手机上却一点声音都没有——用户只会觉得“连不上”。
 *
 * 和「服务运行中」（常驻、低优先级）、「agent 想改记录」（要批准）分开渠道：
 * 这是“有人在敲门”，不是“有活要干”。
 */
object AgentAccessNotifier {

    private const val CHANNEL_ID = "memore_agent_access_v1"
    private const val NOTIF_ID = 4103

    fun notifyPending(context: Context, label: String) {
        if (!AgentCommandNotifier.canNotify(context)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Agent 连接请求", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
        val open = PendingIntent.getActivity(
            context,
            20,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        manager.notify(
            NOTIF_ID,
            builder
                .setContentTitle("有电脑请求连接")
                .setContentText(label.ifBlank { "打开 App 允许" })
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIF_ID)
    }
}
