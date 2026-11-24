package memore.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import memore.app.MainActivity
import memore.app.R

/**
 * 「agent 想改你的记录」的通知（2026-11-15）。
 *
 * 为什么要有：写入默认是 **approve 模式**，命令会排队等人批。
 * 但在此之前，只有 App 内的「信」会变红——**你不打开 App 就永远不知道有人在等**。
 * 一条命令在队列里躺三小时，agent 那边就卡三小时，而这件事在手机上完全没有声音。
 *
 * 和「Agent 服务运行中」那条常驻通知（[AgentServerService]）分开两条通知、两个渠道：
 * 那条是状态（低优先级、常驻），这条是**要你动手的事**（默认优先级、可批准）。
 *
 * 文案只写事实（项目规矩：不写解释性小字）。
 */
object AgentCommandNotifier {

    // 2026-11-15：渠道 id 带版本号。Android 的渠道属性**创建后不可改**
    // （重要级别、声音都在用户设置里冻结），所以调级别只能换新 id，
    // 否则老用户手里那条 DEFAULT 渠道永远不会变成 HIGH。
    private const val CHANNEL_ID = "memore_agent_commands_v2"
    private const val NOTIF_ID = 4102

    const val ACTION_APPROVE = "memore.app.AGENT_APPROVE_PENDING"
    const val ACTION_REJECT = "memore.app.AGENT_REJECT_PENDING"

    /** 有没有通知权限（Android 13+ 要运行时申请；没权限就别白忙）。 */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 有待批准就发/更新一条；count 为 0 时撤掉。 */
    fun notifyPending(context: Context, count: Int) {
        if (count <= 0) {
            cancel(context)
            return
        }
        if (!canNotify(context)) return
        ensureChannel(context)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val open = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_LETTER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val approve = actionIntent(context, ACTION_APPROVE, 11)
        val reject = actionIntent(context, ACTION_REJECT, 12)

        manager.notify(
            NOTIF_ID,
            builder
                .setContentTitle("agent 想改 $count 条记录")
                .setContentText("点开看要改什么")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(Notification.Action.Builder(null, "批准", approve).build())
                .addAction(Notification.Action.Builder(null, "忽略", reject).build())
                .build()
        )
    }

    fun cancel(context: Context) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIF_ID)
        }
    }

    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AgentCommandActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "待批准", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "agent 提交的写入命令"
            }
        )
    }
}

