package memore.app.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导出后的**回读校验**。
 *
 * 参考 ArmsDex 的 `BackupExporter.verify`（它把 zip 里的 db 抽出来跑 `PRAGMA integrity_check`）——
 * 思路一致：**验的是「这份包能不能用」，不是「字节对不对」**。
 * Mirror 的包是 JSON，所以等价动作是：解出 `data.json` → 能解析 → `count` 与 `records` 数量一致。
 *
 * **刻意只读开头**：`data.json` 是 zip 的第一个条目（见 `ExportEngine` 的写入顺序），
 * 解析完立刻收工，后面的 `attachments/`、`voice_cache/`、`sound_custom/` **一个字节都不碰**。
 * 因此耗时与附件总量无关，**不会越用越慢**。
 *
 * 边界（写清楚，免得误以为它很严）：抓不到「附件少了一个」，只能抓
 * 「包坏了 / 写了一半 / 不是合法 zip / JSON 被截断」。要更严就得顺流走一遍数条目，
 * 代价随附件量线性增长，当前刻意不做。
 */
object ExportVerifier {

    sealed interface Result {
        /** @param version 包里的 version 字段 @param count 记录数 */
        data class Ok(val version: String?, val count: Int) : Result
        data class Failed(val reason: String) : Result
    }

    /** ZIP 包：解出第一个条目 `data.json` 再校验，**不遍历后面的条目**。 */
    suspend fun verifyZip(input: InputStream): Result = withContext(Dispatchers.IO) {
        try {
            var text: String? = null
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (text == null) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "data.json") {
                        text = zip.readBytes().toString(Charsets.UTF_8)
                    } else {
                        zip.closeEntry()
                    }
                }
            }
            val body = text ?: return@withContext Result.Failed("压缩包不完整：没有找到 data.json")
            checkJson(body)
        } catch (e: Exception) {
            Result.Failed("压缩包读取失败：${e.javaClass.simpleName}")
        }
    }

    /** 纯 JSON 包：直接解析。 */
    suspend fun verifyJson(input: InputStream): Result = withContext(Dispatchers.IO) {
        try {
            checkJson(input.buffered().readBytes().toString(Charsets.UTF_8))
        } catch (e: Exception) {
            Result.Failed("JSON 读取失败：${e.javaClass.simpleName}")
        }
    }

    /**
     * 「另存为」校验不过时，把目标文件删掉 —— 避免以后拿到一个坏包还以为是真的。
     * （ArmsDex 只提示"校验失败"，文件留在那儿；这一步我补上。）
     */
    fun deleteQuietly(context: Context, uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            LogUtil.e("ExportVerifier", "deleteDocument", e)
        }
    }

    private fun checkJson(text: String): Result = try {
        val obj = JsonParser.parseString(text).asJsonObject
        val version = obj.get("version")?.takeIf { !it.isJsonNull }?.asString
        val countEl = obj.get("count")
        val recordsEl = obj.get("records")
        when {
            countEl == null || countEl.isJsonNull -> Result.Failed("缺少 count 字段")
            recordsEl == null || !recordsEl.isJsonArray -> Result.Failed("缺少 records 数组")
            recordsEl.asJsonArray.size() != countEl.asInt ->
                Result.Failed("count=${countEl.asInt}，实际记录数 ${recordsEl.asJsonArray.size()}，不一致")
            else -> Result.Ok(version, countEl.asInt)
        }
    } catch (e: Exception) {
        Result.Failed("内容解析失败：${e.javaClass.simpleName}")
    }
}
