package memore.app.data.worker

import android.content.Context
import memore.app.MemoreApp
import memore.app.data.model.RecordType
import memore.app.utils.LogUtil
import memore.app.utils.UiSoundPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object AiAnalysisWorker {

    suspend fun runOnce(context: Context) {
        // 记录当前处理的任务，异常时用于回退状态，避免死任务
        var pendingTask: memore.app.data.model.AiTask? = null
        val app = context.applicationContext as MemoreApp
        val taskDao = app.container.aiTaskDao
        val recordRepository = app.container.recordRepository
        try {
            LogUtil.i("AI-Worker 开始检查队列")

            val prefs = context.getSharedPreferences("mirror_prefs", Context.MODE_PRIVATE)
            val endpoint = prefs.getString("ai_endpoint", "") ?: ""
            val model = prefs.getString("ai_model", "qwen2.5:7b") ?: "qwen2.5:7b"
            val apiKey = prefs.getString("ai_api_key", "") ?: ""
            if (endpoint.isBlank()) return

            val task = taskDao.getNextPendingTask() ?: return
            pendingTask = task
            LogUtil.i("AI-Worker 取到任务: recordId=${task.recordId}")

            taskDao.updateTask(task.copy(status = "PROCESSING", lastAttemptAt = System.currentTimeMillis()))

            val health = serverHealth(endpoint, apiKey)
            if (health == null) {
                taskDao.updateTask(task.copy(status = "WAITING_SERVER"))
                LogUtil.i("AI-Worker AI 服务不可达, 标记 WAITING_SERVER（下次轮询会重试）")
                return
            }
            if (health == 401 || health == 403) {
                markFailed(taskDao, task, "API Key 无效或未授权", context)
                LogUtil.i("AI-Worker API Key 无效, 标记 FAILED")
                return
            }
            if (health != 200) {
                taskDao.updateTask(task.copy(status = "WAITING_SERVER"))
                LogUtil.i("AI-Worker 服务未就绪 (HTTP $health), 标记 WAITING_SERVER")
                return
            }

            val record = recordRepository.getRecordById(task.recordId) ?: run {
                markFailed(taskDao, task, "记录不存在", context)
                return
            }

            // 锁定即只读：已加密记录跳过 AI 分析，避免读取清空后的内容或写回明文分析
            if (record.isLocked) {
                markFailed(taskDao, task, "记录已加密，跳过 AI 分析", context)
                LogUtil.i("AI-Worker 记录已加密，跳过: recordId=${task.recordId}")
                return
            }

            val amends = recordRepository.getAmendmentsForRecord(task.recordId)
            val amendText = amends.joinToString("\n\n") { amend ->
                if (record.type == RecordType.DIALOGUE) "我：\n${amend.content}"
                else amend.content
            }
            // 与提示词的【输入结构】保持一致：没有增修时也标【正文】
            val fullContent = if (amendText.isNotBlank()) {
                "【正文】\n${record.content}\n\n【后续增修】\n$amendText"
            } else "【正文】\n${record.content}"
            val result = recordRepository.analyzeRecordAndSave(
                task.recordId, endpoint, model, fullContent, apiKey
            )

            // 用户指令：所有 AI 功能不准有重试。失败直接标记 FAILED，不递增重试、不回退 PENDING。
            if (result.isSuccess) {
                taskDao.updateTask(task.copy(status = "SUCCESS"))
                LogUtil.i("AI-Worker 任务完成: recordId=${task.recordId}")
                UiSoundPlayer.playSummaryComplete(context)
            } else {
                markFailed(taskDao, task, result.exceptionOrNull()?.message, context)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 被取消不是失败：别把"协程被掐断"写成 FAILED —— 那会污染任务状态，还会播错误音
            throw e
        } catch (e: Exception) {
            LogUtil.e("AI-Worker", "runOnce", e)
            // 异常时：直接标记 FAILED（不做重试），避免死任务占住队列
            val stuck = pendingTask
            if (stuck != null) {
                try {
                    markFailed(taskDao, stuck, e.message, context)
                    LogUtil.i("AI-Worker 异常标记 FAILED: recordId=${stuck.recordId}")
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun markFailed(taskDao: memore.app.data.local.dao.AiTaskDao, task: memore.app.data.model.AiTask, message: String?, context: Context) {
        taskDao.updateTask(task.copy(status = "FAILED", errorMessage = message, lastAttemptAt = System.currentTimeMillis()))
        LogUtil.i("AI-Worker 任务失败: recordId=${task.recordId}, err=$message")
        UiSoundPlayer.playError(context)
    }

    private suspend fun serverHealth(endpoint: String, apiKey: String): Int? = withContext(Dispatchers.IO) {
        try {
            val url = URL(memore.app.core.ai.AiProtocol.healthUrl(endpoint))
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            // 云端健康检查需带 Authorization 头（否则智谱 /models 返回 401 被误判不可达）
            memore.app.core.ai.AiProtocol.applyAuth(conn, apiKey)
            conn.responseCode
        } catch (_: Exception) {
            null
        }
    }
}
