package memore.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 应用升级后自动把 agent 服务拉起来（2026-11-14）。
 *
 * 起因：出门断开 WLAN 后连不上，查出来是 `agent_should_run = false` 且没有开机自启——
 * 服务一旦被系统清掉，就再也没有东西把它拉回来。
 */
class AgentBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val prefs = context.getSharedPreferences("memore_prefs", Context.MODE_PRIVATE)
        // 只看有没有 token：App 打开过就算"该跑"（2026-11-14）
        if (prefs.getString("agent_token", null).isNullOrBlank()) return
        runCatching { AgentServerService.start(context) }
    }
}
