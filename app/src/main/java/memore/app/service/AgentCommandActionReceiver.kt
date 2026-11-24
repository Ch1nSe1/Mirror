package memore.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import memore.app.MemoreApp

/**
 * 通知栏上那两个按钮的接收者（2026-11-15）。
 *
 * 为什么必须用 Receiver 而不是 Activity：点「批准」时 App 很可能不在前台，
 * 甚至进程已被杀。广播能拉起进程，**不需要界面**——「不用打开 App 就能批」是这条功能的意义本身。
 *
 * 执行完立刻撤掉通知；下一次有新命令进来会重新发。
 */
class AgentCommandActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MemoreApp ?: return
        val pendingResult = goAsync()
        Thread {
            try {
                val bus = AgentCommandBus(app)
                when (intent.action) {
                    AgentCommandNotifier.ACTION_APPROVE -> bus.approveAllPending()
                    AgentCommandNotifier.ACTION_REJECT -> bus.rejectAllPending()
                }
            } catch (_: Exception) {
                // 失败不弹任何东西：下一次轮询/新命令会再发通知
            } finally {
                AgentCommandNotifier.cancel(context)
                pendingResult.finish()
            }
        }.start()
    }
}
