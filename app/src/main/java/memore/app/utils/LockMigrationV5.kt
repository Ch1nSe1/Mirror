package memore.app.utils

import memore.app.data.local.dao.AttachmentDao
import memore.app.data.local.dao.RecordDao
import memore.app.data.local.dao.UnlockRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v5 迁移（2026-11-16）：把「逐条加密」的旧库整体改成「明文 + 门闩」。
 *
 * 只做三件事，顺序不能换：
 *   1. 用旧密码取回旧主密钥，把每一条仍带密文的记录解回明文
 *      （**保持 is_locked=1** —— 锁是用户的选择，不能因为迁移顺手给人解锁了）
 *   2. 把仍加密的附件原地解密（文件本体 + 文件名）
 *   3. 最后才把配置改写成 v5（只留 verifier）
 *
 * 任何一步失败都不写 v5 配置：宁可停在旧状态（还能用旧密码打开），
 * 也不能出现"配置是 v5、内容还是密文"的半死状态。
 */
object LockMigrationV5 {

    data class Report(
        val records: Int,
        val attachments: Int,
        val failures: List<String>
    ) {
        val ok: Boolean get() = failures.isEmpty()
    }

    suspend fun run(password: String, recordDao: RecordDao, attachmentDao: AttachmentDao): Result<Report> =
        withContext(Dispatchers.IO) {
            EncryptionManager.refreshConfigStatus()
            if (EncryptionManager.isV5()) {
                return@withContext Result.success(Report(0, 0, emptyList()))
            }

            // 1) 旧主密钥（v1/v2 现算、v3 解包裹）
            val keyResult = EncryptionManager.ensureKey(password)
            if (keyResult.isFailure) {
                return@withContext Result.failure(
                    Exception("旧密码不对：" + (keyResult.exceptionOrNull()?.message ?: "无法取回密钥"))
                )
            }
            val key = keyResult.getOrThrow()

            val failures = mutableListOf<String>()
            var recordCount = 0

            // 2) 记录：密文 → 明文，锁标记保留
            val cipherRecords = runCatching { recordDao.getCipherRecords() }.getOrDefault(emptyList())
            cipherRecords.forEach { r ->
                runCatching {
                    val json = EncryptionManager.decryptContent(
                        r.encryptedData ?: "", r.iv ?: "", r.authTag ?: "", key
                    )
                    val p = EncryptionManager.unpackRecordPayload(json)
                    val analysisJson = if (p.hasAnalysis) p.analysisJson else r.analysis?.let {
                        memore.app.utils.GsonInstance.gson.toJson(it)
                    }
                    recordDao.unlockRecordCascade(
                        listOf(
                            UnlockRow(
                                id = r.id,
                                content = p.content,
                                notes = p.notes,
                                dac = p.dialogueAiContent,
                                dialogueAiReply = p.dialogueAiReply,
                                analysis = analysisJson,
                                emotionalTags = if (p.hasEmotionalTags) p.emotionalTags else r.emotionalTags
                            )
                        )
                    )
                    // 迁移保留"锁"这件事：解回明文后立刻把标记放回去
                    if (r.isLocked) recordDao.markLocked(listOf(r.id))
                    recordCount++
                }.onFailure { failures += "记录 ${r.id.take(8)}：${it.message ?: "解密失败"}" }
            }

            // 3) 附件：文件本体 + 文件名
            var attachmentCount = 0
            val encryptedAtts = runCatching { attachmentDao.getEncrypted() }.getOrDefault(emptyList())
            encryptedAtts.forEach { att ->
                // 注意：这里不能用 runCatching{} 包 suspend 调用（会报"只能在协程里调用"）
                try {
                    val f = File(att.filePath)
                    if (f.exists()) {
                        EncryptionManager.decryptFileInPlace(f, att.iv, att.authTag, key)
                    }
                    val plainName = runCatching {
                        EncryptionManager.decryptFileName(att.encName, key)
                    }.getOrNull() ?: att.fileName
                    attachmentDao.decryptAttachment(att.id, plainName)
                    attachmentCount++
                } catch (e: Exception) {
                    failures += "附件 ${att.id}：${e.message ?: "解密失败"}"
                }
            }

            // 4) 只有"没有失败"才切配置；有失败就停在旧状态，可重试
            if (failures.isNotEmpty()) {
                return@withContext Result.success(Report(recordCount, attachmentCount, failures))
            }
            val setResult = EncryptionManager.setLockPassword(password)
            if (setResult.isFailure) {
                return@withContext Result.failure(
                    Exception("内容已解密但配置未切换：" + (setResult.exceptionOrNull()?.message ?: "未知"))
                )
            }
            Result.success(Report(recordCount, attachmentCount, emptyList()))
        }
}
