package memore.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import memore.app.MainActivity
import memore.app.MemoreApp

/**
 * 让本地服务活着（2026-11-13）。
 *
 * 为什么必须前台服务：MIUI/ColorOS 会杀普通后台进程，服务一死，agent 那边就"断线"，
 * 而用户完全看不出来——这正是「Agent 连接」页要显示"运行 X 分钟"的原因。
 *
 * 通知文案只写状态（不做解释性小字，按项目规矩）。
 */
class AgentServerService : Service() {

    private var server: AgentServer? = null

    /** 网络变化时重广播 mDNS（2026-11-20）：换 Wi-Fi/热点后旧注册可能失效。 */
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                runCatching { server?.rebindMdns() }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { netCallback = cb }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        netCallback = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        if (server == null) {
            val app = application as MemoreApp
            val prefs = app.preferences
            if (prefs.getString("agent_token", null).isNullOrBlank()) {
                prefs.edit().putString("agent_token", AgentServer.newToken()).apply()
            }
            server = AgentServer(app).also {
                runCatching { it.start() }
                    .onFailure { e -> AgentServerState.lastError = e.message }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        runCatching { server?.stop() }
        server = null
        AgentServerState.running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent 服务", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Memore 服务运行中")
            .setContentText("端口 ${AgentServerState.PORT}")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "memore_agent_server"
        private const val NOTIF_ID = 4101
        const val ACTION_STOP = "memore.app.AGENT_SERVER_STOP"

        fun start(context: Context) {
            val intent = Intent(context, AgentServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentServerService::class.java))
        }
    }
}
