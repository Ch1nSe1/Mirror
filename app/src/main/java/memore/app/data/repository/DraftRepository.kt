package memore.app.data.repository

import memore.app.core.ai.AiProtocol
import memore.app.core.ai.Prompts
import memore.app.data.local.dao.DraftBlockDao
import memore.app.data.local.dao.DraftDao
import memore.app.data.model.Draft
import memore.app.data.model.DraftAskItem
import memore.app.data.model.DraftBlock
import memore.app.data.model.DraftKind
import memore.app.data.model.parseDraftAskItems
import memore.app.utils.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 草稿仓库（多本）。
 *
 * 刻意**不做**的事：不碰 records、不产生关系边、不触发 AI 任务队列 ——
 * 草稿就是草稿，它平行于随笔 / 对话录，但不是记录。出成品之后草稿仍然留着
 *（不想记进正式版的原文就存在这里），要收起来用归档、不删。
 *
 * 一本 = 一条 [Draft]；块 = 若干 [DraftBlock]（都带 draftId）。
 * 四类块（见 [DraftKind]）的分工：
 * - **三个功能（整理 / 质疑 / 讨论）送进去的是同一份材料**：除 AI 产出的正文外，所有块按顺序拼成一段
 *   （[buildAiInput]），**不贴 kind 标签** —— 哪段是正文、哪段是问答、哪段是讨论，由 AI 自己判断
 *   （用户 2026-10-14：「让 AI 判断哪些是正文哪些是讨论和质疑就行了，不然讨论和质疑给的内容容易重叠」）；
 * - 之前"质疑只看正文 + 只看历次问答、讨论只看正文"是**想错了**：讨论看不到质疑、质疑看不到讨论，
 *   而且同一份内容会被拆成 input 与 history 两处送两遍。
 */
class DraftRepository(
    private val draftDao: DraftDao,
    private val dao: DraftBlockDao
) {

    // ================= 草稿本 =================

    fun observeDrafts(): Flow<List<Draft>> = draftDao.observeActive()

    fun observeArchivedDrafts(): Flow<List<Draft>> = draftDao.observeArchived()

    suspend fun getDraft(id: Long): Draft? = draftDao.get(id)

    /** 全部草稿（含归档与置顶）—— 导出用，顺序按 "置顶 + 最近动过"。 */
    suspend fun allDrafts(): List<Draft> = draftDao.all()

    /**
     * 还原一本草稿（导入用）：**id 全部重建**（草稿之间没有互相引用），
     * 名字 / 创建时间 / 最后动过时间 / 归档 / 置顶都按包里的原样写回 ——
     * 时间必须原样，否则抽屉里的「今天 / 昨天 / 7 天内 / 更早」分组会全乱。
     *
     * 一个块都没有的空本不还原（本机也有"空草稿不许堆"的规矩）。
     */
    suspend fun importDraft(draft: Draft, blocks: List<DraftBlock>): Long? {
        val kept = blocks.filter { it.content.isNotBlank() }
        if (kept.isEmpty()) return null
        val newId = draftDao.insert(draft.copy(id = 0))
        kept.forEach { dao.insert(it.copy(id = 0, draftId = newId)) }
        return newId
    }

    /** 新建一本（名字由用户自己起；空白名字兜一个占位，方便之后再改）。 */
    suspend fun createDraft(name: String): Long {
        val trimmed = name.trim().ifBlank { "未命名" }
        return draftDao.insert(Draft(name = trimmed))
    }

    suspend fun renameDraft(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        draftDao.rename(id, trimmed, System.currentTimeMillis())
    }

    /** 置顶 / 取消置顶（抽屉里排最上面）。 */
    suspend fun setDraftPinned(id: Long, pinned: Boolean) =
        draftDao.setPinned(id, pinned, System.currentTimeMillis())

    /** 归档 / 取消归档：只是收起来，内容一个不删。 */
    suspend fun setDraftArchived(id: Long, archived: Boolean) =
        draftDao.setArchived(id, archived, System.currentTimeMillis())

    /** 删除一本：连它的块一起删（手动级联，避免给这张小表加外键）。 */
    suspend fun deleteDraft(id: Long) {
        dao.deleteByDraft(id)
        draftDao.delete(id)
    }

    /**
     * 清掉"空的草稿"（一块都没有的），但保留 [keepId]。
     *
     * 用户的要求：**新建之后没输入就退出，那就删了，防止乱堆**。
     * 放在"进入草稿页"时跑一次 —— 这样连"直接杀进程"留下的空本也能在下次进来时收掉。
     */
    suspend fun pruneEmptyDrafts(keepId: Long?) {
        draftDao.all().forEach { d ->
            if (d.id != keepId && dao.countBlocks(d.id) == 0) deleteDraft(d.id)
        }
    }

    /** 离开 / 切换到别的本时：这本要是空的就删掉（防空草稿越堆越多）。 */
    suspend fun deleteIfEmpty(draftId: Long) {
        if (dao.countBlocks(draftId) == 0) deleteDraft(draftId)
    }
    // ================= 块 =================

    fun observeBlocks(draftId: Long): Flow<List<DraftBlock>> = dao.observeByDraft(draftId)

    /** 一次性读某一本的块（按 id 精确取）—— 抽屉里对**指定的那一本**发起讨论时用。 */
    suspend fun blocksOf(draftId: Long): List<DraftBlock> = dao.getByDraft(draftId)

    fun observeBlockCount(draftId: Long): Flow<Int> = dao.observeCountByDraft(draftId)

    fun observeCount(): Flow<Int> = dao.observeCount()

    /** 最后编辑时间（所有草稿里最近的一次）—— 给时间线「新建」菜单上的提示用。 */
    fun observeLastEditedAt(): Flow<Long?> = dao.observeLastEditedAt()

    /** 定格一块（追加到这本的末尾）。内容为空时不写（UI 也会禁用按钮，双保险）。 */
    suspend fun add(draftId: Long, content: String, kind: String = DraftKind.MINE): Long? {
        val text = content.trim()
        if (text.isEmpty()) return null
        val id = dao.insert(DraftBlock(content = text, kind = kind, draftId = draftId))
        draftDao.touch(draftId, System.currentTimeMillis())
        return id
    }

    /** 编辑已有块：只改正文与改动时间，**顺序与类型都不变**。 */
    suspend fun edit(draftId: Long, id: Long, content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        dao.updateContent(id, text, System.currentTimeMillis())
        draftDao.touch(draftId, System.currentTimeMillis())
    }

    suspend fun remove(id: Long) = dao.delete(id)

    // ================= 送 AI 的输入 =================

    /**
     * 送 AI 的草稿材料：**除 [DraftKind.AI] 之外的所有块**，按写下的顺序拼成一段纯文本。
     *
     * - 作者自己写的块编号（1. 2. 3.…），顺序 = 他写下的顺序；
     * - 问答 / 讨论**不贴 kind 标签**（它们本身就带「问：/答：」「讨论：/回应：」），
     *   **哪段是正文、哪段是问答、哪段是讨论，交给 AI 自己判断** ——
     *   用户 2026-10-14：「让 AI 判断哪些是正文哪些是讨论和质疑就行了，
     *   不然讨论和质疑给的内容容易重叠」（贴标签 = 同一份内容被说两遍）；
     * - [DraftKind.AI] 的产出不进：它是从这些材料里长出来的，送回去等于反复回炉、而且和正文重复。
     */
    fun buildAiInput(blocks: List<DraftBlock>): String {
        var n = 0
        val parts = ArrayList<String>(blocks.size)
        blocks.forEach { b ->
            when (b.kind) {
                // AI 稿和手动拼接稿都不再作为"材料"：它们是产物，送回去会反复回炉。
                DraftKind.AI, DraftKind.RAW -> Unit
                DraftKind.MINE -> {
                    n++
                    parts.add("$n. ${b.content.trim()}")
                }
                else -> stripSkippedAnswers(b.content)?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            }
        }
        return parts.joinToString("\n\n").trim()
    }

    /**
     * 质疑 / 讨论里的「已问过的 / 已谈过的」占位。
     *
     * 历次问答与讨论**已经按原顺序并进 `{input}`** 了，所以这里只指个路、不重复送内容
     * （用户要的就是"别把同一份内容送两遍"）。
     */
    private val historyHint = "（历次问答与讨论已按原顺序并入下面的草稿，请自行辨认，不要重复同一个方向）"

    // ================= AI 调用 =================

    /** 草稿整理（AI 是**作者**，不是码字员）。输入 = 正文 + 问答 + 讨论；思考与否跟随系统设置。 */
    suspend fun tidy(
        blocks: List<DraftBlock>,
        endpoint: String,
        modelName: String,
        apiKey: String = ""
    ): Result<String> = callTextAi(
        blocks = blocks,
        promptKey = Prompts.KEY_DRAFT_TIDY,
        endpoint = endpoint, modelName = modelName, apiKey = apiKey,
        logTag = "DraftTidy"
    )

    /** 质疑：让 AI 就这本草稿提 2~5 个问题（每个带选项）。输入含历次问答与讨论，避免重复问。 */
    suspend fun probe(
        blocks: List<DraftBlock>,
        endpoint: String,
        modelName: String,
        apiKey: String = ""
    ): Result<List<DraftAskItem>> {
        if (blocks.none { it.kind == DraftKind.MINE }) return Result.failure(Exception("这本草稿还是空的"))
        val prompt = Prompts.replacePrompt(
            Prompts.getPrompt(Prompts.KEY_DRAFT_PROBE, AiProtocol.isThinkingEnabled()),
            mapOf(
                "input" to buildAiInput(blocks),
                "history" to historyHint
            )
        )
        return callJsonAi(prompt, endpoint, modelName, apiKey, "DraftProbe")
    }

    /** 讨论：让 AI 就"这篇该怎么改、应该怎么写"给建议。输入同样含历次问答与讨论。 */
    suspend fun discuss(
        blocks: List<DraftBlock>,
        endpoint: String,
        modelName: String,
        apiKey: String = ""
    ): Result<List<DraftAskItem>> {
        if (blocks.none { it.kind == DraftKind.MINE }) return Result.failure(Exception("这本草稿还是空的"))
        val prompt = Prompts.replacePrompt(
            Prompts.getPrompt(Prompts.KEY_DRAFT_DISCUSS, AiProtocol.isThinkingEnabled()),
            mapOf(
                "input" to buildAiInput(blocks),
                "history" to historyHint
            )
        )
        return callJsonAi(prompt, endpoint, modelName, apiKey, "DraftDiscuss")
    }

    private suspend fun callTextAi(
        blocks: List<DraftBlock>,
        promptKey: String,
        endpoint: String,
        modelName: String,
        apiKey: String,
        logTag: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val input = buildAiInput(blocks)
        if (input.isBlank()) return@withContext Result.failure(Exception("这本草稿还是空的"))
        if (endpoint.isBlank()) return@withContext Result.failure(Exception("还没有配置 AI 端点"))
        val prompt = Prompts.replacePrompt(
            Prompts.getPrompt(promptKey, AiProtocol.isThinkingEnabled()),
            mapOf("input" to input)
        )
        post(prompt, endpoint, modelName, apiKey, formatJson = false, logTag = logTag)
            .map { stripWrapping(it) }
            .mapCatching { if (it.isBlank()) throw Exception("AI 没有返回内容") else it }
    }

    private suspend fun callJsonAi(
        prompt: String,
        endpoint: String,
        modelName: String,
        apiKey: String,
        logTag: String
    ): Result<List<DraftAskItem>> {
        if (endpoint.isBlank()) return Result.failure(Exception("还没有配置 AI 端点"))
        return post(prompt, endpoint, modelName, apiKey, formatJson = true, logTag = logTag)
            .mapCatching { raw ->
                val items = parseDraftAskItems(raw)
                if (items.isEmpty()) throw Exception("AI 没有给出可用的问题")
                items
            }
    }

    private suspend fun post(
        prompt: String,
        endpoint: String,
        modelName: String,
        apiKey: String,
        formatJson: Boolean,
        logTag: String
    ): Result<String> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val body = AiProtocol.buildBody(
                endpoint, modelName, prompt,
                temperature = 0.2, numPredict = 4096, numCtx = 8192, formatJson = formatJson
            )
            val url = URL(AiProtocol.completionUrl(endpoint))
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 300000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            AiProtocol.applyAuth(conn, apiKey)
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }
            val code = conn.responseCode
            if (code == 404) return@withContext Result.failure(Exception("未找到模型，请确认已下载指定模型"))
            if (code == 500) return@withContext Result.failure(Exception("AI 服务内部错误"))
            if (code != 200) return@withContext Result.failure(Exception("AI 服务返回错误 (HTTP $code)"))
            val responseText = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            Result.success(AiProtocol.extractText(responseText, endpoint))
        } catch (e: Exception) {
            LogUtil.e(logTag, "post", e)
            Result.failure(Exception(e.message ?: "AI 调用失败"))
        } finally {
            conn?.disconnect()
        }
    }

    /** 剥掉模型偶尔自带的包装（代码块、首尾引号）。只做这一层，**不做任何改写**。 */
    private fun stripWrapping(raw: String): String {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```").let { if (it.startsWith("text")) it.removePrefix("text") else it }
            if (t.endsWith("```")) t = t.removeSuffix("```")
            t = t.trim()
        }
        if (t.length >= 2 && t.first() == '"' && t.last() == '"') t = t.substring(1, t.length - 1).trim()
        return t
    }
}

/**
 * 决定"该打开哪一本"（纯函数，可测）。**优先级：当前 → 进来的意图 → 最近一本**。
 *
 * ⚠️ 这里踩过三个坑，规则是被它们逼出来的：
 * ① 用户 2026-10-10 报"每次进入都会新建一个未命名"—— 列表 Flow 的**首帧是空列表**（还没读到数据），
 *    我把它当成了"一本都没有"→ 每次进页面都新建一本 ✗。
 *    所以调用方必须**先区分"还没读到"（null）与"读到了但确实为空"（emptyList）**：
 *    只有后者才该新建（本函数返回 null 即代表"没有可用的，请新建"）。
 * ② 用户 2026-10-11 报"**在归档的草稿里用完功能，结束问答后跳进了最新的草稿**"——
 *    根因：[archived] 那几本不在活动列表里，指定了它们却被当成"不存在"，掉了兜底分支
 *    （挑最近一本）把用户从归档那一本上拽走 ✗。所以归档的草稿**也是合法的当前本**。
 * ③ 同一天他提到"这种导航问题应该不少" —— 顺藤摸到的真凶是**"指定优先"本身**：
 *    抽屉里切到别本之后，任何一次列表刷新（写一块就会 touch 更新 updated_at）都会让
 *    "进来时那一本"再次胜出，**把用户从正在写的草稿上拽回去** ✗。
 *    所以现在 **当前这本只要还在（活动或归档），就绝不换**；"进来的意图"只在还没定下来时才用。
 */
fun pickDraftId(
    wanted: Long?,
    loaded: List<Draft>,
    current: Long?,
    archived: List<Draft> = emptyList()
): Long? {
    fun exists(id: Long?): Boolean =
        id != null && (loaded.any { it.id == id } || archived.any { it.id == id })
    return when {
        exists(current) -> current
        exists(wanted) -> wanted
        else -> loaded.firstOrNull()?.id ?: archived.firstOrNull()?.id
    }
}

/**
 * 「指定的那一本还没出现在列表里，要不要等？」
 *
 * 要等 = **别走 [pickDraftId] 的兜底（挑列表第一本）**。为什么需要这一条：
 * 新建一本时是"先插库、再切页面"，列表 Flow 可能还差一帧才带上它；那一帧里 wanted 不在列表，
 * 兜底会挑第一本，而 `current` 一旦被设上，"当前优先"就永远赢了 —— 人再也回不到刚建的那本 ✗
 *（用户 2026-10-14 报的「进入之后进到第一个草稿」就是这个兜底）。
 *
 * [existsInDb] 为 false 表示它**真的没了**（比如从记录点进来的那本已被删）→ 才允许兜底。
 */
fun shouldWaitForWanted(
    wanted: Long?,
    inList: Boolean,
    existsInDb: Boolean,
    resolved: Boolean
): Boolean = wanted != null && wanted > 0 && !resolved && !inList && existsInDb

/**
 * 去掉"被跳过"的问答对（2026-11-20）。
 *
 * 背景：质疑/讨论里作者点「跳过」时，块里会留下「问：…／答：（跳过）」。
 * 以前整块送进 AI，整理稿里会冒出"这里我刻意跳过了"——把省略当成了素材。
 * 规则：答案里带（跳过）的，连同它的问题一起删；其余原样保留。
 */
internal fun stripSkippedAnswers(text: String): String {
    val out = ArrayList<String>()
    var pending = ArrayList<String>()
    fun flush() {
        if (pending.isNotEmpty()) { out.addAll(pending); pending = ArrayList() }
    }
    for (line in text.lines()) {
        val t = line.trim()
        when {
            t.startsWith("问：") || t.startsWith("讨论：") -> {
                flush()
                pending.add(line)
            }
            t.startsWith("答：") || t.startsWith("回应：") -> {
                val skipped = t.contains("（跳过）") || t.contains("(跳过)")
                if (skipped) {
                    pending = ArrayList() // 问题一起丢掉
                } else {
                    pending.add(line)
                    flush()
                }
            }
            else -> {
                if (pending.isNotEmpty()) pending.add(line) else out.add(line)
            }
        }
    }
    flush()
    return out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
}
