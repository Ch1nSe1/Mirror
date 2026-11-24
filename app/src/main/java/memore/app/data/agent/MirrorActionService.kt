package memore.app.data.agent

import memore.app.MemoreApp
import memore.app.data.model.AgentAction
import memore.app.data.model.AgentCommand
import memore.app.service.AgentCommandBus
import org.json.JSONArray
import org.json.JSONObject

/**
 * 动作层（2026-11-15，agent 连接 v2 第一步）。
 *
 * ## 为什么要有它
 *
 * 旧模型里"一次写入"就是一条命令：批准一次、执行一条。于是"把 7 条归到一起"
 * 要点 7 次批准，或者一次"全部批准"（等于没审查）。而人真正做决策的单位是**动作**。
 *
 * 这一层的职责只有三件：
 *   1. 把一组动词收成一个动作（`propose`）
 *   2. 批准时**先拍快照再执行**（`approve`）——撤回要靠这份快照
 *   3. 给接口层提供状态（`get` / `pending`）
 *
 * ## 现在还不是"真动词"
 *
 * 第一步先让动作能装"现有的 kind"，这样能立即验证"一次批准 / 一次回执"，
 * 而不去动执行器。动词表（record.note / tag.add …）在下一步映射过来。
 *
 * ## 注意
 *
 * 快照存的是**受影响行的前状态**（整行 JSON，简单优先）。
 * 撤回逻辑还没有实现——快照先攒起来，第二步再用。
 */
class MirrorActionService(private val app: MemoreApp) {

    private val actions get() = app.container.database.agentActionDao()
    private val commands get() = app.container.database.agentCommandDao()
    private val bus = AgentCommandBus(app)

    /**
     * 提一个动作。
     *
     * @param verbs 形如 [{ "kind": "tag_record", "payload": {...} }, …]
     * @return { ok, id, status, commands: n }
     */
    fun propose(id: String, title: String, actor: String, verbs: JSONArray, requiresConfirm: Boolean): JSONObject {
        if (id.isBlank()) return JSONObject().put("ok", false).put("error", "缺 action id（用于去重）")
        if (verbs.length() == 0) return JSONObject().put("ok", false).put("error", "空动作")
        if (verbs.length() > MAX_VERBS) {
            return JSONObject().put("ok", false).put("error", "一个动作最多 $MAX_VERBS 步")
        }

        // 2026-11-15：**提交时就校验参数**，不要等用户点完批准再告诉他写错了。
        // 校验失败一个字节都不落库。
        //
        // 2026-11-15 第二步：外面收**语义动词**（tag.add / record.note …），
        // 内部翻译成执行器认的 kind。老调用方传 kind 也继续能用（过渡期）。
        val normalized = ArrayList<Pair<String, JSONObject>>() // kind to payload
        val names = ArrayList<String>()
        for (i in 0 until verbs.length()) {
            val verb = verbs.optJSONObject(i) ?: run {
                return JSONObject().put("ok", false).put("error", "第 ${i + 1} 步不是对象")
            }
            val name = verb.optString("verb").ifBlank { verb.optString("kind") }
            val rawPayload = verb.optJSONObject("payload") ?: JSONObject()
            // 三级先拒：不要先报"参数不对"，让人误以为补参数就能做。
            if (tierOf(name) == 3) {
                return JSONObject().put("ok", false)
                    .put("error", "三级动作禁止执行：$name")
                    .put("code", "forbidden_verb")
            }
            val mapped = mapVerb(name, rawPayload)
                ?: return JSONObject().put("ok", false)
                    .put("error", "第 ${i + 1} 步：不认识的动词 $name（支持：${VERBS.joinToString(" / ")}）")
            validatePayload(mapped.first, mapped.second)?.let { why ->
                return JSONObject().put("ok", false).put("error", "第 ${i + 1} 步：$why")
            }
            normalized += mapped
            names += name
        }

        // 2026-11-20：三档权限（服务端判定，客户端传的 requires_confirm 不作数）。
        // 一级免批直接执行；二级入队等批；三级禁止。
        val tiers = names.map { tierOf(it) }
        val forbidden = names.filterIndexed { i, _ -> tiers[i] == 3 }
        if (forbidden.isNotEmpty()) {
            return JSONObject().put("ok", false)
                .put("error", "三级动作禁止执行：${forbidden.joinToString(" / ")}")
                .put("code", "forbidden_verb")
        }
        val mode = AgentCommandBus(app).mode()
        if (mode == AgentCommandBus.Mode.READ_ONLY) {
            return JSONObject().put("ok", false).put("error", "只读模式：agent 不能写入").put("code", "read_only")
        }
        val maxTier = tiers.maxOrNull() ?: 1

        val inserted = kotlinx.coroutines.runBlocking {
            actions.insert(
                AgentAction(
                    id = id,
                    actor = actor,
                    title = title,
                    status = AgentAction.STATUS_PROPOSED,
                    requiresConfirm = requiresConfirm
                )
            )
        }
        if (inserted == -1L) {
            // 幂等：同一个动作重复提交只写一次
            return JSONObject().put("ok", true).put("duplicate", true).put("id", id)
        }

        val ids = mutableListOf<String>()
        normalized.forEachIndexed { i, (kind, payload) ->
            val cmdId = "$id#$i"
            kotlinx.coroutines.runBlocking {
                commands.insert(AgentCommand(id = cmdId, kind = kind, payload = payload.toString(), batchId = id))
            }
            ids += cmdId
        }

        kotlinx.coroutines.runBlocking {
            // 2026-11-15 修：把步骤 id 回写到动作行。不回写的话
            // 批准还能靠 batch_id 兜底，但"相关记录/步骤"永远是空的。
            actions.setCommandIds(id, JSONArray(ids).toString())
            actions.setSnapshot(id, "") // 占位：批准时才拍
        }
        if (maxTier == 1) {
            // 一级免批：直接执行。失败会返回 failed + note，不静默吞掉。
            val res = approve(id)
            return JSONObject()
                .put("ok", res.optBoolean("ok"))
                .put("id", id)
                .put("status", res.optString("status").ifBlank { "failed" })
                .put("auto", true)
                .put("tier", 1)
                .put("steps", ids.size)
                .put("result", res)
        }
        return JSONObject()
            .put("ok", true)
            .put("id", id)
            .put("status", AgentAction.STATUS_PROPOSED)
            .put("tier", maxTier)
            .put("steps", ids.size)
            .put("commands", JSONArray(ids))
    }

    /** 批准并执行：先拍快照，再逐条执行内部命令。 */
    fun approve(id: String, confirmed: Boolean = false): JSONObject {
        val action = kotlinx.coroutines.runBlocking { actions.byId(id) }
            ?: return JSONObject().put("ok", false).put("error", "没有这个动作")
        if (action.status != AgentAction.STATUS_PROPOSED) {
            return JSONObject().put("ok", false).put("error", "这个动作已经是 ${action.status}")
        }
        // 2026-11-15：高危动作的二次确认**在这里强制**，不靠界面自觉。
        // 没确认就原样退回 proposed，什么也不写。
        if (action.requiresConfirm && !confirmed) {
            return JSONObject()
                .put("ok", false)
                .put("need_confirm", true)
                .put("status", AgentAction.STATUS_PROPOSED)
                .put("error", "这个动作要确认两次：再调一次 approve 并带 confirmed=true")
        }

        val snapshot = captureSnapshot(id)
        // 快照上限（I10）：一个动作超过 50 行就不许执行——大动作要拆
        val touched = snapshot.optJSONArray("records")?.length() ?: 0
        if (touched > MAX_SNAPSHOT_ROWS) {
            return JSONObject().put("ok", false)
                .put("error", "这个动作动了 $touched 条记录，超过单动作上限 $MAX_SNAPSHOT_ROWS 条，请拆成几个动作")
        }
        kotlinx.coroutines.runBlocking { actions.setSnapshot(id, snapshot.toString()) }

        var done = 0
        val failures = JSONArray()
        val list = runCatching { JSONArray(action.commandIds.ifBlank { "[]" }) }.getOrDefault(JSONArray())
        val ids = if (list.length() > 0) {
            (0 until list.length()).map { list.getString(it) }
        } else {
            kotlinx.coroutines.runBlocking { commands.pendingOfBatch(id) }.map { it.id }
        }
        for (cmdId in ids) {
            val r = bus.approve(cmdId)
            val inner = r.optJSONObject("result") ?: r
            if (inner.optBoolean("ok")) done++ else failures.put(inner.optString("error"))
        }

        val ok = failures.length() == 0
        kotlinx.coroutines.runBlocking {
            actions.finish(
                id = id,
                status = if (ok) AgentAction.STATUS_APPLIED else AgentAction.STATUS_FAILED,
                note = if (ok) "已执行 $done 步" else "执行 $done 步，失败 ${failures.length()} 步：${failures.optString(0)}",
                executedAt = System.currentTimeMillis()
            )
        }
        return JSONObject()
            .put("ok", ok)
            .put("status", if (ok) AgentAction.STATUS_APPLIED else AgentAction.STATUS_FAILED)
            .put("done", done)
            .put("failed", failures.length())
    }

    fun reject(id: String, why: String = "用户拒绝"): JSONObject {
        val action = kotlinx.coroutines.runBlocking { actions.byId(id) }
            ?: return JSONObject().put("ok", false).put("error", "没有这个动作")
        if (action.status != AgentAction.STATUS_PROPOSED) {
            return JSONObject().put("ok", false).put("error", "这个动作已经是 ${action.status}")
        }
        val list = runCatching { JSONArray(action.commandIds.ifBlank { "[]" }) }.getOrDefault(JSONArray())
        val ids = if (list.length() > 0) (0 until list.length()).map { list.getString(it) }
        else kotlinx.coroutines.runBlocking { commands.pendingOfBatch(id) }.map { it.id }
        ids.forEach { bus.reject(it) }
        kotlinx.coroutines.runBlocking { actions.finish(id, AgentAction.STATUS_REJECTED, why) }
        return JSONObject().put("ok", true).put("status", AgentAction.STATUS_REJECTED)
    }

    fun get(id: String): JSONObject? {
        val action = kotlinx.coroutines.runBlocking { actions.byId(id) } ?: return null
        return JSONObject()
            .put("id", action.id)
            .put("actor", action.actor)
            .put("title", action.title)
            .put("status", action.status)
            .put("requires_confirm", action.requiresConfirm)
            .put("note", action.note)
            .put("created_at", action.createdAt)
            .put("executed_at", action.executedAt)
            .put("reverted_at", action.revertedAt)
            .put("has_snapshot", action.snapshot.isNotBlank())
            // 2026-11-20：待批列表要能一眼看出"这一步做什么、影响几条"，
            // 否则 agent 得对每条再 GET /actions/{id}（N+1）。
            .put("steps", stepsOf(action))
    }

    /** 动作内部每个执行步骤的摘要：kind + 影响条数。 */
    fun stepsOf(action: AgentAction): JSONArray {
        val ids = runCatching { JSONArray(action.commandIds.ifBlank { "[]" }) }.getOrDefault(JSONArray())
        val arr = JSONArray()
        for (i in 0 until ids.length()) {
            val cmd = kotlinx.coroutines.runBlocking { commands.byId(ids.getString(i)) } ?: continue
            val p = runCatching { JSONObject(cmd.payload) }.getOrDefault(JSONObject())
            val rows = p.optJSONArray("record_ids")?.length()
                ?: if (p.optString("parent_id").isNotBlank() || p.optString("from_id").isNotBlank()) 1 else 0
            // 对外只给语义动词，不给执行器 kind（kind 是实现细节，2026-11-20）。
            arr.put(JSONObject().put("verb", verbOf(cmd.kind, p)).put("records", rows))
        }
        return arr
    }

    /** 执行器 kind → 对外语义动词。加/减两用的动词看 payload 里 add/remove 哪边有值。 */
    private fun verbOf(kind: String, p: JSONObject): String {
        fun addFirst(): Boolean {
            val add = p.optJSONArray("add")?.length() ?: 0
            val remove = p.optJSONArray("remove")?.length() ?: 0
            return !(remove > 0 && add == 0)
        }
        return when (kind) {
            AgentCommand.KIND_CREATE -> "record.note"
            AgentCommand.KIND_AMEND -> "record.amend"
            AgentCommand.KIND_TAG -> if (addFirst()) "tag.add" else "tag.remove"
            AgentCommand.KIND_TAG_MERGE -> "tag.merge"
            AgentCommand.KIND_TAG_DELETE -> "tag.delete"
            AgentCommand.KIND_PROTOCOL -> if (addFirst()) "protocol.add" else "protocol.remove"
            AgentCommand.KIND_ORIGIN -> "origin.assign"
            AgentCommand.KIND_RELATE -> "relation.link"
            AgentCommand.KIND_UNLINK -> "relation.unlink"
            AgentCommand.KIND_REJECT_REL -> "relation.reject"
            AgentCommand.KIND_RETYPE_REL -> "relation.retype"
            AgentCommand.KIND_RESTORE_REL -> "relation.restore"
            AgentCommand.KIND_DRAFT_CREATE -> "draft.create"
            AgentCommand.KIND_DRAFT_APPEND -> "draft.append"
            AgentCommand.KIND_AI_ANALYZE -> "ai.analyze"
            AgentCommand.KIND_TAG_RENAME -> "tag.rename"
            AgentCommand.KIND_PROTOCOL_MERGE -> "protocol.merge"
            AgentCommand.KIND_ZONE_RENAME -> "zone.rename"
            AgentCommand.KIND_ZONE_DELETE -> "zone.delete"
            AgentCommand.KIND_TRASH -> "record.trash"
            AgentCommand.KIND_RESTORE -> "record.restore"
            AgentCommand.KIND_LOCK -> "record.lock"
            AgentCommand.KIND_UNLOCK -> "record.unlock"
            AgentCommand.KIND_ZONE -> if (p.optString("op").equals("remove", ignoreCase = true)) "zone.removeMember" else "zone.addMember"
            AgentCommand.KIND_ZONE_CREATE -> "zone.create"
            AgentCommand.KIND_TICK -> "tick.edit"
            AgentCommand.KIND_DIAL -> "dial.edit"
            AgentCommand.KIND_LOCK_MIGRATE -> "lock.migrate"
            else -> "unknown"
        }
    }

    fun pending(): List<JSONObject> =
        kotlinx.coroutines.runBlocking { actions.byStatus(AgentAction.STATUS_PROPOSED) }
            .mapNotNull { get(it.id) }

    /** 界面上要用的形态：直接给实体（信 / Agent 连接页按动作渲染）。 */
    fun pendingActions(): List<AgentAction> =
        kotlinx.coroutines.runBlocking { actions.byStatus(AgentAction.STATUS_PROPOSED) }

    /** 最近执行完的动作（信里"可撤回"那一段）。 */
    fun recentActions(limit: Int = 5): List<AgentAction> =
        kotlinx.coroutines.runBlocking { actions.recent(limit) }
            .filter { it.status == AgentAction.STATUS_APPLIED }

    /** 动作影响的记录 id（从内部命令里解析，用于"影响哪几条"）。 */
    fun affectedRecordIds(action: AgentAction): List<String> {
        val ids = LinkedHashSet<String>()
        val list = runCatching { JSONArray(action.commandIds.ifBlank { "[]" }) }.getOrDefault(JSONArray())
        for (i in 0 until list.length()) {
            val cmd = kotlinx.coroutines.runBlocking { commands.byId(list.getString(i)) } ?: continue
            runCatching {
                val p = JSONObject(cmd.payload)
                p.optJSONArray("record_ids")?.let { a -> (0 until a.length()).forEach { ids += a.getString(it) } }
                p.optString("parent_id").takeIf { it.isNotBlank() }?.let { ids += it }
                p.optString("from_id").takeIf { it.isNotBlank() }?.let { ids += it }
                p.optString("to_id").takeIf { it.isNotBlank() }?.let { ids += it }
            }
        }
        return ids.toList()
    }

    /** 动作影响几条记录。 */
    fun affectedCount(action: AgentAction): Int = affectedRecordIds(action).size

    /** 一个动作里的单步（"相关记录"页按步骤分组用）。 */
    data class ActionStep(val label: String, val recordIds: List<String>)

    /** 把动作拆成可读的步骤：每步一个动词名 + 它动到的记录。 */
    fun stepsView(action: AgentAction): List<ActionStep> {
        val out = ArrayList<ActionStep>()
        val list = runCatching { JSONArray(action.commandIds.ifBlank { "[]" }) }.getOrDefault(JSONArray())
        for (i in 0 until list.length()) {
            val cmd = kotlinx.coroutines.runBlocking { commands.byId(list.getString(i)) } ?: continue
            val ids = LinkedHashSet<String>()
            runCatching {
                val p = JSONObject(cmd.payload)
                p.optJSONArray("record_ids")?.let { a -> (0 until a.length()).forEach { ids += a.getString(it) } }
                p.optString("parent_id").takeIf { it.isNotBlank() }?.let { ids += it }
                p.optString("from_id").takeIf { it.isNotBlank() }?.let { ids += it }
                p.optString("to_id").takeIf { it.isNotBlank() }?.let { ids += it }
            }
            out += ActionStep(label = verbLabel(cmd), recordIds = ids.toList())
        }
        return out
    }

    private fun verbLabel(cmd: AgentCommand): String = runCatching {
        val p = JSONObject(cmd.payload)
        when (cmd.kind) {
            AgentCommand.KIND_TAG -> if (p.optJSONArray("add")?.length() ?: 0 > 0) "打标签" else "去掉标签"
            AgentCommand.KIND_AMEND -> "加增修"
            AgentCommand.KIND_CREATE -> "新建记录"
            AgentCommand.KIND_ORIGIN -> "归来源"
            AgentCommand.KIND_RELATE -> "建关联"
            AgentCommand.KIND_UNLINK -> "断开关联"
            AgentCommand.KIND_REJECT_REL -> "拒绝关联"
            AgentCommand.KIND_RETYPE_REL -> "改关联类型"
            AgentCommand.KIND_RESTORE_REL -> "恢复关联"
            AgentCommand.KIND_DRAFT_CREATE -> "新建草稿"
            AgentCommand.KIND_DRAFT_APPEND -> "追加草稿"
            AgentCommand.KIND_AI_ANALYZE -> "重新分析"
            AgentCommand.KIND_TAG_RENAME -> "标签改名"
            AgentCommand.KIND_PROTOCOL_MERGE -> "合并协议"
            AgentCommand.KIND_ZONE_RENAME -> "区域改名"
            AgentCommand.KIND_ZONE_DELETE -> "删除区域"
            AgentCommand.KIND_TAG_MERGE -> "合并标签"
            AgentCommand.KIND_TAG_DELETE -> "删除标签"
            AgentCommand.KIND_PROTOCOL -> if (p.optJSONArray("add")?.length() ?: 0 > 0) "加协议" else "去协议"
            AgentCommand.KIND_TRASH -> "放进回收站"
            AgentCommand.KIND_RESTORE -> "从回收站恢复"
            AgentCommand.KIND_LOCK -> "锁定"
            AgentCommand.KIND_UNLOCK -> "解锁"
            AgentCommand.KIND_ZONE -> if (p.optString("op").equals("remove", true)) "移出区域" else "放进区域"
            AgentCommand.KIND_ZONE_CREATE -> "新建区域"
            else -> cmd.kind
        }
    }.getOrDefault(cmd.kind)

    /** 单步的可见证据（2026-11-20，「信」里展开看，不必跳页）。 */
    data class StepEvidence(
        val label: String,
        val fromId: String = "",
        val toId: String = "",
        val fromSnippet: String = "",
        val toSnippet: String = "",
        val quoteA: String = "",
        val quoteB: String = "",
        val why: String = ""
    )

    /** 按动作 id 给出每一步的证据：目标记录摘句 + 关联类的 why/双向引文。 */
    fun stepEvidences(actionId: String): List<StepEvidence> {
        val a = kotlinx.coroutines.runBlocking { actions.byId(actionId) } ?: return emptyList()
        val out = ArrayList<StepEvidence>()
        val list = runCatching { JSONArray(a.commandIds.ifBlank { "[]" }) }.getOrDefault(JSONArray())
        val repo = app.container.recordRepository
        val relDao = app.container.database.recordRelationDao()
        fun snip(id: String): String {
            if (id.isBlank()) return ""
            val r = kotlinx.coroutines.runBlocking { repo.getRecordById(id) } ?: return ""
            return r.content.replace("\n", " ").trim().take(26)
        }
        for (i in 0 until list.length()) {
            val cmd = kotlinx.coroutines.runBlocking { commands.byId(list.getString(i)) } ?: continue
            val label = verbLabel(cmd)
            val p = runCatching { JSONObject(cmd.payload) }.getOrDefault(JSONObject())
            val from = p.optString("from_id")
            val to = p.optString("to_id")
            if (from.isNotBlank() && to.isNotBlank()) {
                val rel = runCatching { kotlinx.coroutines.runBlocking { relDao.byPair(from, to) } }.getOrNull()
                out += StepEvidence(
                    label = label,
                    fromId = from,
                    toId = to,
                    fromSnippet = snip(from),
                    toSnippet = snip(to),
                    quoteA = rel?.quoteA.orEmpty(),
                    quoteB = rel?.quoteB.orEmpty(),
                    why = rel?.why.orEmpty()
                )
            } else {
                val ids = ArrayList<String>()
                p.optJSONArray("record_ids")?.let { arr -> (0 until arr.length()).take(3).forEach { ids += arr.getString(it) } }
                p.optString("parent_id").takeIf { it.isNotBlank() }?.let { ids += it }
                val first = ids.firstOrNull().orEmpty()
                out += StepEvidence(
                    label = label,
                    fromId = first,
                    fromSnippet = ids.joinToString(" · ") { snip(it) }.take(60)
                )
            }
        }
        return out
    }

    /** 动作的授权档位：所有步骤里最高的那一档（2026-11-20）。 */
    fun tierOfAction(actionId: String): Int {
        val a = kotlinx.coroutines.runBlocking { actions.byId(actionId) } ?: return 2
        val list = runCatching { JSONArray(a.commandIds.ifBlank { "[]" }) }.getOrDefault(JSONArray())
        var max = 1
        for (i in 0 until list.length()) {
            val cmd = kotlinx.coroutines.runBlocking { commands.byId(list.getString(i)) } ?: continue
            val p = runCatching { JSONObject(cmd.payload) }.getOrDefault(JSONObject())
            val tier = tierOf(verbOf(cmd.kind, p))
            if (tier > max) max = tier
        }
        return max
    }

    /** 给界面用：按动作 id 拿标题与步骤（"相关记录"页）。 */
    fun titleOf(actionId: String): String =
        kotlinx.coroutines.runBlocking { actions.byId(actionId) }?.title.orEmpty()

    fun stepsForId(actionId: String): List<ActionStep> {
        val a = kotlinx.coroutines.runBlocking { actions.byId(actionId) } ?: return emptyList()
        return stepsView(a)
    }

    /** 这个动作里有哪些 kind（撤回前判断"能不能撤"用，2026-11-15）。 */
    private fun kindsOf(action: AgentAction): List<String> {
        val list = runCatching { JSONArray(action.commandIds.ifBlank { "[]" }) }.getOrDefault(JSONArray())
        val out = ArrayList<String>()
        for (i in 0 until list.length()) {
            val cmd = kotlinx.coroutines.runBlocking { commands.byId(list.getString(i)) } ?: continue
            out += cmd.kind
        }
        return out
    }

    fun recent(limit: Int = 20): List<JSONObject> =
        kotlinx.coroutines.runBlocking { actions.recent(limit) }.mapNotNull { get(it.id) }

    /**
     * 拍快照：把动作涉及的记录行前状态存下来（撤回要用）。
     * 现在只收记录行本身；标签/关联/区域成员的快照在第二步补。
     */
    private fun captureSnapshot(actionId: String): JSONObject {
        val cmds = kotlinx.coroutines.runBlocking { commands.pendingOfBatch(actionId) }
        val ids = LinkedHashSet<String>()
        cmds.forEach { cmd ->
            runCatching {
                val p = JSONObject(cmd.payload)
                p.optJSONArray("record_ids")?.let { a -> (0 until a.length()).forEach { ids += a.getString(it) } }
                p.optString("parent_id").takeIf { it.isNotBlank() }?.let { ids += it }
                p.optString("from_id").takeIf { it.isNotBlank() }?.let { ids += it }
                p.optString("to_id").takeIf { it.isNotBlank() }?.let { ids += it }
            }
        }
        val rows = runCatching {
            kotlinx.coroutines.runBlocking {
                app.container.recordRepository.getRecordsByIds(ids.toList())
            }
        }.getOrDefault(emptyList())

        // 删标签要额外记名字（2026-11-15）：删掉的 tag 行没有正文可快照，
        // 撤回时只能按名字重建。
        val deletedTags = JSONArray()
        cmds.filter { it.kind == AgentCommand.KIND_TAG_DELETE }.forEach { cmd ->
            runCatching {
                val a = JSONObject(cmd.payload).optJSONArray("names") ?: return@runCatching
                (0 until a.length()).forEach { deletedTags.put(a.getString(it)) }
            }
        }

        // 刻度/表盘（2026-11-15）：记下动作涉及对象的**动作前状态**。
        // exists=false 表示"动作要新建它"——撤回时就按这个 id 删掉。
        val tickSnap = JSONArray()
        val dialSnap = JSONArray()
        cmds.forEach { cmd ->
            val p = runCatching { JSONObject(cmd.payload) }.getOrNull() ?: return@forEach
            when (cmd.kind) {
                AgentCommand.KIND_TICK -> {
                    val id = p.optString("id").trim()
                    if (id.isNotBlank()) {
                        val t = runCatching { kotlinx.coroutines.runBlocking { app.container.tickRepository.getById(id) } }.getOrNull()
                        tickSnap.put(
                            JSONObject().put("id", id).put("exists", t != null).apply {
                                if (t != null) put("name", t.name).put("start_ms", t.startMs)
                                    .put("end_ms", t.endMs ?: JSONObject.NULL).put("summary", t.summary)
                            }
                        )
                    }
                }
                AgentCommand.KIND_DIAL -> {
                    val id = (p.optString("dial_id").ifBlank { p.optString("id") }).trim()
                    if (id.isNotBlank()) {
                        val d = runCatching { kotlinx.coroutines.runBlocking { app.container.dialRepository.getDial(id) } }.getOrNull()
                        dialSnap.put(
                            JSONObject().put("id", id).put("exists", d != null).apply {
                                if (d != null) put("name", d.name).put("start_ms", d.startMs)
                                    .put("end_ms", d.endMs ?: JSONObject.NULL).put("summary", d.summary)
                            }
                        )
                    }
                }
            }
        }

        val arr = JSONArray()
        rows.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("content", r.content)
                    .put("type", r.type.name)
                    .put("origin_id", r.originId)
                    .put("is_anchored", r.isAnchored)
                    .put("is_locked", r.isLocked)
                    // 2026-11-15：软删除也要快照，否则"进回收站"撤不回来
                    .put("is_deleted", r.isDeleted)
                    .put("updated_at", r.updatedAt)
            )
        }

        // 2026-11-15：除了记录行本身，还要记"关系网"——否则撤回只能把正文放回去，
        // 标签/协议/关联/区域成员会留在原地，"撤回"就成了半件事。
        val tags = JSONArray()
        val tagRepo = app.container.tagRepository
        ids.forEach { rid ->
            runCatching {
                kotlinx.coroutines.runBlocking { tagRepo.getTagsForRecord(rid) }
            }.getOrDefault(emptyList()).forEach { t ->
                tags.put(JSONObject().put("record_id", rid).put("tag_id", t.id).put("tag_name", t.name))
            }
        }

        val relations = JSONArray()
        val protocols = JSONArray()
        val protoRepo = app.container.protocolRepository
        ids.forEach { rid ->
            runCatching { kotlinx.coroutines.runBlocking { protoRepo.getProtocolsForRecord(rid) } }
                .getOrDefault(emptyList()).forEach { pr ->
                    protocols.put(JSONObject().put("record_id", rid).put("protocol_id", pr.id).put("protocol_name", pr.name))
                }
        }

        val relDao = app.container.database.recordRelationDao()
        val allLinks = runCatching { kotlinx.coroutines.runBlocking { relDao.all().filter { it.validTo == null } } }
            .getOrDefault(listOf<memore.app.data.model.RecordRelation>())
        allLinks.forEach { link ->
            if (link.fromId in ids && link.toId in ids) {
                relations.put(
                    JSONObject().put("from", link.fromId).put("to", link.toId)
                        .put("type", link.type).put("why", link.why).put("source", link.source)
                        .put("quote_a", link.quoteA).put("quote_b", link.quoteB)
                )
            }
        }

        val zones = JSONArray()
        val zoneRepo = app.container.zoneRepository
        ids.forEach { rid ->
            runCatching { kotlinx.coroutines.runBlocking { zoneRepo.zoneIdsOf(rid) } }.getOrDefault(emptySet()).forEach { zid ->
                zones.put(JSONObject().put("record_id", rid).put("zone_id", zid))
            }
        }

        // 2026-11-21：别名映射组 + 要删的锚点行，各拍一份（原来只拍记录世界，
        // alias.* / concept.delete 撤不回来）。
        var mappingsSnap: String? = null
        if (cmds.any { it.kind == AgentCommand.KIND_ALIAS }) {
            mappingsSnap = runCatching {
                kotlinx.coroutines.runBlocking {
                    com.google.gson.Gson().toJson(app.container.aliasMappingRepo.load())
                }
            }.getOrNull()
        }
        val entitiesSnap = JSONArray()
        cmds.filter { it.kind == AgentCommand.KIND_CONCEPT_DELETE }.forEach { cmd ->
            runCatching {
                val name = JSONObject(cmd.payload).optString("name").trim()
                if (name.isEmpty()) return@runCatching
                kotlinx.coroutines.runBlocking { app.container.anchorRepository.entitiesOfConcept(name) }
                    .forEach { e ->
                        entitiesSnap.put(
                            JSONObject()
                                .put("record_id", e.recordId)
                                .put("name", e.name)
                                .put("kind", e.kind)
                                .put("quote", e.quote)
                                .put("created_at", e.createdAt)
                        )
                    }
            }
        }

        return JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("records", arr)
            .put("tags", tags)
            .put("protocols", protocols)
            .put("relations", relations)
            .put("zones", zones)
            .put("deleted_tags", deletedTags)
            .put("ticks", tickSnap)
            .put("dials", dialSnap)
            .put("mappings", mappingsSnap ?: JSONObject.NULL)
            .put("entities", entitiesSnap)
    }

    /**
     * 撤回一个已执行的动作（2026-11-15）。
     *
     * 顺序：先查冲突，再逐类回滚。
     * **冲突就整批拒绝**——只回滚一半会留下比"没撤回"更难理解的状态。
     */
    fun undo(id: String): JSONObject {
        val action = kotlinx.coroutines.runBlocking { actions.byId(id) }
            ?: return JSONObject().put("ok", false).put("error", "没有这个动作")
        if (action.status != AgentAction.STATUS_APPLIED) {
            return JSONObject().put("ok", false).put("error", "只有已执行的动作能撤回（现在是 ${action.status}）")
        }
        if (action.snapshot.isBlank()) {
            return JSONObject().put("ok", false).put("error", "这个动作没有快照，不能撤回")
        }
        val snap = runCatching { JSONObject(action.snapshot) }
            .getOrElse { return JSONObject().put("ok", false).put("error", "快照损坏") }

        // 2026-11-15：有些动作**撤不了**，要在动数据之前就拒绝：
        //   · record.lock：正文已被密码加密，回滚要密码
        //   · zone.create：新建的区域行不在快照里
        val kinds = kindsOf(action)
        if (kinds.contains(AgentCommand.KIND_LOCK) || kinds.contains(AgentCommand.KIND_UNLOCK)) {
            return JSONObject().put("ok", false)
                .put("error", "加密/解密动作不能自动撤回：正文已按密码处理过，要撤回得再给一次密码")
        }
        if (kinds.contains(AgentCommand.KIND_ZONE_CREATE)) {
            return JSONObject().put("ok", false)
                .put("error", "新建的区域不能靠撤回删掉：去「区域」页手动删")
        }
        if (kinds.contains(AgentCommand.KIND_LOCK_MIGRATE)) {
            return JSONObject().put("ok", false)
                .put("error", "v5 迁移是单向的：内容已经回到明文，撤回没有意义")
        }

        val repo = app.container.recordRepository
        val snapRecords = snap.optJSONArray("records") ?: JSONArray()

        // 快照里出现的所有记录 id。
        // **撤回必须按这个集合遍历**，不能只按"快照里有标签的那些"——
        // 动作执行前本来没有标签的记录，不会出现在 tags 数组里，
        // 只遍历 tags 就会漏掉"新加上的标签"（2026-11-15 实测踩到）。
        val snapIds = LinkedHashSet<String>()
        for (i in 0 until snapRecords.length()) snapIds += snapRecords.getJSONObject(i).optString("id")

        // ① 冲突检查：动作执行之后，这些行有没有被别的动作改过
        val conflicts = mutableListOf<String>()
        for (i in 0 until snapRecords.length()) {
            val s = snapRecords.getJSONObject(i)
            val rid = s.optString("id")
            val now = runCatching { kotlinx.coroutines.runBlocking { repo.getRecordById(rid) } }.getOrNull()
            if (now == null) continue
            val before = s.optLong("updated_at")
            // 判据抽到 ActionDiffs（有单测）：时间变新 **且** 正文变了才算冲突。
            // 打标签只刷新 updated_at，不算改正文。
            val laterTouched = ActionDiffs.contentConflict(
                snapshotUpdatedAt = before,
                snapshotContent = s.optString("content"),
                currentUpdatedAt = now.updatedAt,
                currentContent = now.content
            )
            if (laterTouched) conflicts += rid.take(8)
        }
        if (conflicts.isNotEmpty()) {
            return JSONObject().put("ok", false)
                .put("error", "撤回被拒：这些记录在这次动作之后又被改过（${conflicts.joinToString("、")}），"
                    + "整批回滚会把后来的改动一起抹掉")
        }

        // ② 记录行
        var restored = 0
        // getRecordById 只查未删除的行（`is_deleted = 0`），所以软删过的记录
        // 在它眼里"不存在"——要单独从回收站里捞（2026-11-15 实测踩到）。
        val trashed = runCatching { kotlinx.coroutines.runBlocking { repo.getTrashedRecords() } }
            .getOrDefault(emptyList()).associateBy { it.id }
        // 反向：动作把记录从回收站**捞回来**了，撤回就要再放回去
        val toTrashAgain = mutableListOf<String>()
        for (i in 0 until snapRecords.length()) {
            val s = snapRecords.getJSONObject(i)
            val rid = s.optString("id")
            if (s.optBoolean("is_deleted", false) == false && rid in trashed) {
                runCatching { kotlinx.coroutines.runBlocking { repo.restoreRecord(rid) } }
                    .onSuccess { restored++ }
            }
            if (s.optBoolean("is_deleted", false) && rid !in trashed) {
                toTrashAgain += rid
            }
            val now = runCatching { kotlinx.coroutines.runBlocking { repo.getRecordById(rid) } }.getOrNull() ?: continue
            val back = now.copy(
                content = s.optString("content", now.content),
                type = runCatching { memore.app.data.model.RecordType.valueOf(s.optString("type")) }
                    .getOrDefault(now.type),
                originId = s.optString("origin_id").ifBlank { now.originId },
                isAnchored = s.optBoolean("is_anchored", now.isAnchored),
                isLocked = s.optBoolean("is_locked", now.isLocked)
            )
            runCatching { kotlinx.coroutines.runBlocking { repo.updateRecord(back) } }.onSuccess { restored++ }
        }
        if (toTrashAgain.isNotEmpty()) {
            runCatching { kotlinx.coroutines.runBlocking { repo.softDeleteRecords(toTrashAgain) } }
        }

        // ③ 标签：快照里有、现在没有的补回；现在有、快照里没有的去掉
        val tagRepo = app.container.tagRepository
        val snapTags = snap.optJSONArray("tags") ?: JSONArray()
        val wanted = HashMap<String, MutableSet<String>>() // recordId -> tagIds
        for (i in 0 until snapTags.length()) {
            val t = snapTags.getJSONObject(i)
            wanted.getOrPut(t.optString("record_id")) { mutableSetOf() }.add(t.optString("tag_id"))
        }
        // 快照里每条记录都要有一项（哪怕是空集）：动作**新加的标签**要靠空集被摘掉
        snapIds.forEach { rid -> wanted.getOrPut(rid) { mutableSetOf() } }
        var tagsFixed = 0
        wanted.forEach { (rid, ids) ->
            // 快照里 tag_id 存的是字符串（JSON），这里统一成字符串再比
            val current: Set<String> = runCatching { kotlinx.coroutines.runBlocking { tagRepo.getTagsForRecord(rid) } }
                .getOrDefault(listOf<memore.app.data.model.Tag>()).map { it.id.toString() }.toSet()
            // 集合差走 ActionDiffs（有单测）：补回动作去掉的、摘掉动作新加的
            val diff = ActionDiffs.setDiff(wanted = ids, current = current)
            diff.toAdd.forEach { tid ->
                runCatching { kotlinx.coroutines.runBlocking { tagRepo.addTagToRecord(rid, tid.toInt()) } }
                    .onSuccess { tagsFixed++ }
            }
            diff.toRemove.forEach { tid ->
                runCatching { kotlinx.coroutines.runBlocking { tagRepo.removeTagFromRecord(rid, tid.toInt()) } }
                    .onSuccess { tagsFixed++ }
            }
        }
        runCatching { kotlinx.coroutines.runBlocking { tagRepo.refreshAllCounts() } }

        // ③b 删掉的空标签：按名字重建（2026-11-15）。id 会变，但因为删的时候保证"无引用"，
        // 没有别的东西指向旧 id，重建即还原。
        val deletedTags = snap.optJSONArray("deleted_tags") ?: JSONArray()
        var tagsRebuilt = 0
        for (i in 0 until deletedTags.length()) {
            val name = deletedTags.optString(i).trim()
            if (name.isBlank()) continue
            runCatching { kotlinx.coroutines.runBlocking { tagRepo.getOrCreateTag(name) } }
                .onSuccess { tagsRebuilt++ }
        }

        // ③c 协议：与标签同一套集合差（2026-11-15）。协议是"类/体裁"，撤回必须一并还原，
        // 否则撤回后记录还挂着动作加上的协议。
        val protoRepo = app.container.protocolRepository
        val snapProtocols = snap.optJSONArray("protocols") ?: JSONArray()
        val wantedProto = HashMap<String, MutableSet<String>>()
        for (i in 0 until snapProtocols.length()) {
            val pr = snapProtocols.getJSONObject(i)
            wantedProto.getOrPut(pr.optString("record_id")) { mutableSetOf() }.add(pr.optString("protocol_id"))
        }
        snapIds.forEach { rid -> wantedProto.getOrPut(rid) { mutableSetOf() } }
        wantedProto.forEach { (rid, ids) ->
            val current: Set<String> = runCatching {
                kotlinx.coroutines.runBlocking { protoRepo.getProtocolsForRecord(rid) }
            }.getOrDefault(emptyList()).map { it.id.toString() }.toSet()
            val diff = ActionDiffs.setDiff(wanted = ids, current = current)
            diff.toAdd.forEach { pid ->
                runCatching { kotlinx.coroutines.runBlocking { protoRepo.addProtocolToRecord(rid, pid.toInt()) } }
            }
            diff.toRemove.forEach { pid ->
                runCatching { kotlinx.coroutines.runBlocking { protoRepo.removeProtocolFromRecord(rid, pid.toInt()) } }
            }
        }
        runCatching { kotlinx.coroutines.runBlocking { protoRepo.refreshAllCounts() } }

        // ④ 关联：快照里有、现在没有的补回；现在多出来的删掉
        val relDao = app.container.database.recordRelationDao()
        val snapRel = snap.optJSONArray("relations") ?: JSONArray()
        val wantedRels = LinkedHashMap<Pair<String, String>, JSONObject>()
        for (i in 0 until snapRel.length()) {
            val r = snapRel.getJSONObject(i)
            wantedRels[r.optString("from") to r.optString("to")] = r
        }
        // 同样按 snapIds 判"涉及哪几条"，否则动作**新建的关联**不会被删掉
        val touched = snapIds
        val nowLinks = runCatching { kotlinx.coroutines.runBlocking { relDao.all().filter { it.validTo == null } } }
            .getOrDefault(listOf<memore.app.data.model.RecordRelation>())
            .filter { it.fromId in touched && it.toId in touched }
            .associateBy { it.fromId to it.toId }
        // 集合差走 ActionDiffs：动作新建的关联删掉、动作删掉的关联补回
        val relDiff = ActionDiffs.pairDiff(wanted = wantedRels.keys, current = nowLinks.keys)
        relDiff.toRemove.forEach { key ->
            val parts = key.split("\u0000")
            if (parts.size == 2) {
                runCatching { kotlinx.coroutines.runBlocking { relDao.deletePair(parts[0], parts[1]) } }
            }
        }
        relDiff.toAdd.forEach { key ->
            val parts = key.split("\u0000")
            if (parts.size == 2) {
                val json = wantedRels[parts[0] to parts[1]]
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        relDao.insert(
                            memore.app.data.model.RecordRelation(
                                fromId = parts[0],
                                toId = parts[1],
                                type = json?.optString("type").orEmpty().ifBlank { "RELATED" },
                                why = json?.optString("why").orEmpty(),
                                quoteA = json?.optString("quote_a").orEmpty(),
                                quoteB = json?.optString("quote_b").orEmpty(),
                                source = json?.optString("source").orEmpty().ifBlank { "AI" }
                            )
                        )
                    }
                }
            }
        }
        // 2026-11-20：集合差只按 (from,to) 判，改类型 / 拒绝这类“同一个键、字段变了”的动作
        // 撤销时会被当成“没变化”。这里补一层字段对账：快照里有、当前也在的边，
        // 若 type/status/why/引文与快照不同，按快照恢复（含 status 回到 ACTIVE）。
        wantedRels.forEach { (key, want) ->
            val cur = nowLinks[key] ?: return@forEach
            val wantType = want.optString("type").ifBlank { cur.type }
            val wantWhy = want.optString("why")
            val wantSource = want.optString("source").ifBlank { cur.source }
            val wantQuoteA = want.optString("quote_a")
            val wantQuoteB = want.optString("quote_b")
            if (cur.type != wantType || cur.status != "ACTIVE" || cur.why != wantWhy ||
                cur.quoteA != wantQuoteA || cur.quoteB != wantQuoteB || cur.source != wantSource
            ) {
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        relDao.restoreFields(key.first, key.second, wantType, wantWhy, wantQuoteA, wantQuoteB, wantSource)
                    }
                }
            }
        }

        // ⑤ 区域成员
        val zoneRepo = app.container.zoneRepository
        val snapZones = snap.optJSONArray("zones") ?: JSONArray()
        val wantedZones = HashMap<String, MutableSet<String>>()
        for (i in 0 until snapZones.length()) {
            val z = snapZones.getJSONObject(i)
            wantedZones.getOrPut(z.optString("record_id")) { mutableSetOf() }.add(z.optString("zone_id"))
        }
        // 同标签：动作**新加进区域**的，要靠空集被移出去
        snapIds.forEach { rid -> wantedZones.getOrPut(rid) { mutableSetOf() } }
        wantedZones.forEach { (rid, zids) ->
            val current = runCatching { kotlinx.coroutines.runBlocking { zoneRepo.zoneIdsOf(rid) } }
                .getOrDefault(emptySet<String>())
            val diff = ActionDiffs.setDiff(wanted = zids, current = current)
            diff.toAdd.forEach { zid ->
                runCatching { kotlinx.coroutines.runBlocking { zoneRepo.addToZone(zid, rid) } }
            }
            diff.toRemove.forEach { zid ->
                runCatching { kotlinx.coroutines.runBlocking { zoneRepo.removeFromZone(zid, rid) } }
            }
        }

        // ⑥ 刻度 / 表盘（2026-11-15）：动作前存在 → 恢复字段；动作前不存在 → 删掉动作建的。
        val tickRepo = app.container.tickRepository
        val dialRepo = app.container.dialRepository
        val snapTicks = snap.optJSONArray("ticks") ?: JSONArray()
        for (i in 0 until snapTicks.length()) {
            val t = snapTicks.getJSONObject(i)
            val tid = t.optString("id")
            if (!t.optBoolean("exists")) {
                runCatching { kotlinx.coroutines.runBlocking { tickRepo.delete(tid) } }
                continue
            }
            runCatching {
                kotlinx.coroutines.runBlocking {
                    tickRepo.rename(tid, t.optString("name"))
                    tickRepo.updateBounds(
                        tid,
                        t.optLong("start_ms"),
                        if (t.isNull("end_ms")) null else t.optLong("end_ms")
                    )
                    if (t.has("summary")) tickRepo.updateSummary(tid, t.optString("summary"))
                }
            }
        }
        val snapDials = snap.optJSONArray("dials") ?: JSONArray()
        for (i in 0 until snapDials.length()) {
            val d = snapDials.getJSONObject(i)
            val did = d.optString("id")
            if (!d.optBoolean("exists")) {
                runCatching { kotlinx.coroutines.runBlocking { dialRepo.deleteDial(did) } }
                continue
            }
            runCatching {
                kotlinx.coroutines.runBlocking {
                    dialRepo.renameDial(did, d.optString("name"))
                    dialRepo.updateDialBounds(
                        did,
                        d.optLong("start_ms"),
                        if (d.isNull("end_ms")) null else d.optLong("end_ms")
                    )
                    if (d.has("summary")) dialRepo.updateDialSummary(did, d.optString("summary"))
                }
            }
        }

        // ⑦ 2026-11-21：别名映射组 + 删掉的锚点行（alias.* / concept.delete 的撤回）。
        val snapMappings = snap.optString("mappings", "")
        if (snapMappings.isNotBlank()) {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    val arr = com.google.gson.Gson().fromJson(
                        snapMappings,
                        Array<memore.app.data.model.AliasGroup>::class.java
                    ).toList()
                    app.container.aliasMappingRepo.save(arr)
                }
            }
        }
        val snapEntities = snap.optJSONArray("entities") ?: JSONArray()
        if (snapEntities.length() > 0) {
            val rows = ArrayList<memore.app.data.model.RecordEntity>()
            for (i in 0 until snapEntities.length()) {
                val e = snapEntities.getJSONObject(i)
                rows += memore.app.data.model.RecordEntity(
                    recordId = e.optString("record_id"),
                    name = e.optString("name"),
                    kind = e.optString("kind", "OTHER"),
                    quote = e.optString("quote", ""),
                    createdAt = e.optLong("created_at", System.currentTimeMillis())
                )
            }
            runCatching {
                kotlinx.coroutines.runBlocking {
                    app.container.database.recordEntityDao().insertAll(rows)
                }
            }
        }

        kotlinx.coroutines.runBlocking {
            actions.finish(
                id = id,
                status = AgentAction.STATUS_REVERTED,
                note = "已撤回：$restored 条记录、$tagsFixed 处标签",
                revertedAt = System.currentTimeMillis()
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("status", AgentAction.STATUS_REVERTED)
            .put("records", restored)
            .put("tags", tagsFixed)
    }

    /**
     * 提交时校验一步的参数（2026-11-15）。
     *
     * 目的：**别让错误等到用户点完"批准"才出现**。错在哪一步、缺哪个字段，
     * 提交时就回给 agent，它自己改。
     *
     * @return 错误说明；为空 = 通过
     */
    private fun validatePayload(kind: String, payload: JSONObject): String? {
        fun ids(): Int = payload.optJSONArray("record_ids")?.length() ?: 0
        return when (kind) {
            AgentCommand.KIND_CREATE ->
                if (payload.optString("content").isBlank()) "content 不能为空" else null
            AgentCommand.KIND_AMEND -> when {
                payload.optString("parent_id").isBlank() -> "parent_id 不能为空"
                payload.optString("content").isBlank() -> "content 不能为空"
                else -> null
            }
            AgentCommand.KIND_TAG -> when {
                ids() == 0 -> "record_ids 不能为空"
                (payload.optJSONArray("add")?.length() ?: 0) == 0 &&
                    (payload.optJSONArray("remove")?.length() ?: 0) == 0 -> "add 和 remove 至少要有一个"
                else -> null
            }
            AgentCommand.KIND_ORIGIN -> when {
                ids() == 0 -> "record_ids 不能为空"
                payload.optString("origin_id").isBlank() -> "origin_id 不能为空"
                else -> null
            }
            AgentCommand.KIND_RELATE -> when {
                payload.optString("from_id").isBlank() -> "from_id 不能为空"
                payload.optString("to_id").isBlank() -> "to_id 不能为空"
                else -> null
            }
            AgentCommand.KIND_REJECT_REL, AgentCommand.KIND_RETYPE_REL, AgentCommand.KIND_RESTORE_REL -> when {
                payload.optString("from_id").isBlank() -> "from_id 不能为空"
                payload.optString("to_id").isBlank() -> "to_id 不能为空"
                kind == AgentCommand.KIND_RETYPE_REL &&
                    payload.optString("type").uppercase() !in setOf("LINK", "CITE", "SUPPLEMENT", "CORRECT") ->
                    "type 只能是 LINK / CITE / SUPPLEMENT / CORRECT"
                else -> null
            }
            AgentCommand.KIND_DRAFT_APPEND -> when {
                (payload.optString("draft_id").toLongOrNull() ?: 0L) <= 0L -> "draft_id 不能为空"
                payload.optString("content").isBlank() -> "content 不能为空"
                else -> null
            }
            AgentCommand.KIND_DRAFT_CREATE -> null
            AgentCommand.KIND_AI_ANALYZE -> if (ids() == 0) "record_ids 不能为空" else null
            AgentCommand.KIND_TAG_RENAME -> when {
                payload.optString("from_name").isBlank() -> "from_name 不能为空"
                payload.optString("to_name").isBlank() -> "to_name 不能为空"
                else -> null
            }
            AgentCommand.KIND_PROTOCOL_MERGE -> when {
                payload.optString("from_name").isBlank() -> "from_name 不能为空"
                payload.optString("to_name").isBlank() -> "to_name 不能为空"
                else -> null
            }
            AgentCommand.KIND_ZONE_RENAME -> when {
                payload.optString("zone_id").isBlank() -> "zone_id 不能为空"
                payload.optString("name").isBlank() -> "name 不能为空"
                else -> null
            }
            AgentCommand.KIND_ZONE_DELETE -> if (payload.optString("zone_id").isBlank()) "zone_id 不能为空" else null
            AgentCommand.KIND_TAG_MERGE -> when {
                payload.optString("from_name").isBlank() -> "from_name 不能为空"
                payload.optString("to_name").isBlank() -> "to_name 不能为空"
                else -> null
            }
            AgentCommand.KIND_TAG_DELETE -> {
                val n = payload.optJSONArray("names")
                if (n == null || n.length() == 0) "names 不能为空（只删空标签）" else null
            }
            AgentCommand.KIND_PROTOCOL -> when {
                ids() == 0 -> "record_ids 不能为空"
                (payload.optJSONArray("add")?.length() ?: 0) == 0 &&
                    (payload.optJSONArray("remove")?.length() ?: 0) == 0 -> "add / remove 至少给一个"
                else -> null
            }
            AgentCommand.KIND_TICK, AgentCommand.KIND_DIAL -> when (payload.optString("op").lowercase()) {
                "create" -> when {
                    payload.optString("id").isBlank() -> "create 缺 id"
                    payload.optString("name").isBlank() -> "name 不能为空"
                    else -> null
                }
                "rename", "summary", "delete", "restore", "review_clear" ->
                    if (payload.optString("id").isBlank()) "id 不能为空" else null
                "bounds" -> when {
                    payload.optString("id").isBlank() -> "id 不能为空"
                    payload.optLong("start_ms", 0L) <= 0L -> "start_ms 不能为空"
                    else -> null
                }
                "link", "unlink" ->
                    if (payload.optString("tick_id").isBlank()) "tick_id 不能为空" else null
                else -> "不认识的刻度/表盘操作"
            }
            AgentCommand.KIND_LOCK_MIGRATE ->
                if (payload.optString("password").isBlank()) "password 不能为空（旧密码）" else null
            // 2026-11-15 补的五种
            AgentCommand.KIND_UNLINK -> when {
                payload.optString("from_id").isBlank() -> "from_id 不能为空"
                payload.optString("to_id").isBlank() -> "to_id 不能为空"
                else -> null
            }
            AgentCommand.KIND_TRASH -> if (ids() == 0) "record_ids 不能为空" else null
            AgentCommand.KIND_RESTORE -> if (ids() == 0) "record_ids 不能为空" else null
            AgentCommand.KIND_LOCK -> when {
                ids() == 0 -> "record_ids 不能为空"
                payload.optString("password").isBlank() -> "password 不能为空（加密要当场给密码）"
                else -> null
            }
            AgentCommand.KIND_UNLOCK -> when {
                ids() == 0 -> "record_ids 不能为空"
                payload.optString("password").isBlank() -> "password 不能为空（解密要当场给密码）"
                else -> null
            }
            AgentCommand.KIND_ZONE -> when {
                ids() == 0 -> "record_ids 不能为空"
                payload.optString("zone_id").isBlank() -> "zone_id 不能为空"
                else -> null
            }
            AgentCommand.KIND_ZONE_CREATE ->
                if (payload.optString("name").isBlank()) "name 不能为空" else null
            else -> null
        }
    }

    /**
     * 语义动词 → 执行器认的 kind（2026-11-15）。
     *
     * 对外只该说"给这几条打标签"（`tag.add`），不该说"tag_record"——
     * 后者是执行器的实现细节。**老客户端的 kind 也还能用**，过渡期双轨。
     *
     * `tag.add` 的 payload 可以写 `tags: [...]`（语义写法）或 `add: [...]`（老写法）。
     */
    /**
     * 刻度/表盘的公共整形（2026-11-15）：把语义动词转成 {op, id, ...}。
     * `newId=true` 时在这里生成 id——**发起时就定 id**，撤回才能按 id 删掉。
     */
    private fun tickOp(payload: JSONObject, op: String, newId: Boolean = false): JSONObject =
        JSONObject(payload.toString()).apply {
            put("op", op)
            if (newId) {
                if (!has("id") || optString("id").isBlank()) {
                    put("id", java.util.UUID.randomUUID().toString())
                }
            }
            if (has("summary") && op != "summary") remove("summary")
            if (has("name") && op !in listOf("create", "rename")) remove("name")
        }

    private fun mapVerb(name: String, payload: JSONObject): Pair<String, JSONObject>? = when (name) {
        "record.note" -> AgentCommand.KIND_CREATE to payload
        "record.amend" -> AgentCommand.KIND_AMEND to payload
        "tag.add" -> AgentCommand.KIND_TAG to JSONObject(payload.toString()).apply {
            if (!has("add")) optJSONArray("tags")?.let { put("add", it) }
            remove("tags")
        }
        "tag.remove" -> AgentCommand.KIND_TAG to JSONObject(payload.toString()).apply {
            if (!has("remove")) optJSONArray("tags")?.let { put("remove", it) }
            remove("tags")
        }
        "tag.merge" -> AgentCommand.KIND_TAG_MERGE to payload
        "tag.delete" -> AgentCommand.KIND_TAG_DELETE to payload
        "protocol.add" -> AgentCommand.KIND_PROTOCOL to JSONObject(payload.toString()).apply {
            if (!has("add")) optJSONArray("protocols")?.let { put("add", it) }
            if (has("protocols")) remove("protocols")
        }
        "protocol.remove" -> AgentCommand.KIND_PROTOCOL to JSONObject(payload.toString()).apply {
            if (!has("remove")) optJSONArray("protocols")?.let { put("remove", it) }
            if (has("protocols")) remove("protocols")
        }
        // 2026-11-15：刻度 / 表盘。create 在这里就把 id 定下来，撤回才能精确删掉。
        "tick.create" -> AgentCommand.KIND_TICK to tickOp(payload, "create", newId = true)
        "tick.rename" -> AgentCommand.KIND_TICK to tickOp(payload, "rename")
        "tick.bounds" -> AgentCommand.KIND_TICK to tickOp(payload, "bounds")
        "tick.summary" -> AgentCommand.KIND_TICK to tickOp(payload, "summary")
        "tick.review.clear" -> AgentCommand.KIND_TICK to tickOp(payload, "review_clear")
        "tick.delete" -> AgentCommand.KIND_TICK to tickOp(payload, "delete")
        "tick.restore" -> AgentCommand.KIND_TICK to tickOp(payload, "restore")
        "dial.create" -> AgentCommand.KIND_DIAL to tickOp(payload, "create", newId = true)
        "dial.rename" -> AgentCommand.KIND_DIAL to tickOp(payload, "rename")
        "dial.bounds" -> AgentCommand.KIND_DIAL to tickOp(payload, "bounds")
        "dial.summary" -> AgentCommand.KIND_DIAL to tickOp(payload, "summary")
        "dial.delete" -> AgentCommand.KIND_DIAL to tickOp(payload, "delete")
        "dial.link" -> AgentCommand.KIND_DIAL to tickOp(payload, "link")
        "dial.unlink" -> AgentCommand.KIND_DIAL to tickOp(payload, "unlink")
        // 2026-11-16：v5 迁移（单向，不可撤回）
        "lock.migrate" -> AgentCommand.KIND_LOCK_MIGRATE to payload
        "origin.assign" -> AgentCommand.KIND_ORIGIN to payload
        "relation.link" -> AgentCommand.KIND_RELATE to payload
        // 2026-11-20：审边用（拒绝留负样本 / 改类型）
        "relation.reject" -> AgentCommand.KIND_REJECT_REL to payload
        "relation.retype" -> AgentCommand.KIND_RETYPE_REL to payload
        "relation.restore" -> AgentCommand.KIND_RESTORE_REL to payload
        "draft.create" -> AgentCommand.KIND_DRAFT_CREATE to payload
        "draft.append" -> AgentCommand.KIND_DRAFT_APPEND to payload
        "ai.analyze" -> AgentCommand.KIND_AI_ANALYZE to payload
        "tag.rename" -> AgentCommand.KIND_TAG_RENAME to payload
        "protocol.merge" -> AgentCommand.KIND_PROTOCOL_MERGE to payload
        "zone.rename" -> AgentCommand.KIND_ZONE_RENAME to payload
        "zone.delete" -> AgentCommand.KIND_ZONE_DELETE to payload
        // 2026-11-15 补的五种"减/移"动作
        "relation.unlink" -> AgentCommand.KIND_UNLINK to payload
        "record.trash" -> AgentCommand.KIND_TRASH to payload
        "record.restore" -> AgentCommand.KIND_RESTORE to payload
        "record.lock" -> AgentCommand.KIND_LOCK to payload
        "record.unlock" -> AgentCommand.KIND_UNLOCK to payload
        "zone.addMember" -> AgentCommand.KIND_ZONE to JSONObject(payload.toString()).apply { put("op", "add") }
        "zone.removeMember" -> AgentCommand.KIND_ZONE to JSONObject(payload.toString()).apply { put("op", "remove") }
        "zone.create" -> AgentCommand.KIND_ZONE_CREATE to payload
        // 2026-11-21：别名映射管理 + 删概念（都是结构级改动，二级批）
        "alias.add" -> AgentCommand.KIND_ALIAS to JSONObject(payload.toString()).apply { put("op", "add") }
        "alias.update" -> AgentCommand.KIND_ALIAS to JSONObject(payload.toString()).apply { put("op", "update") }
        "alias.delete" -> AgentCommand.KIND_ALIAS to JSONObject(payload.toString()).apply { put("op", "delete") }
        "alias.merge" -> AgentCommand.KIND_ALIAS to JSONObject(payload.toString()).apply { put("op", "merge") }
        "concept.delete" -> AgentCommand.KIND_CONCEPT_DELETE to payload
        // 过渡期：直接传 kind 的老写法
        in AgentCommand.KINDS -> name to payload
        else -> null
    }

    companion object {
        /** 一个动作最多多少步：再多就没法在手机上逐条看。 */
        const val MAX_VERBS = 20

        /** 一个动作最多动多少条记录（I10）：超过就要拆。 */
        const val MAX_SNAPSHOT_ROWS = 50

        /**
         * 三档权限（2026-11-20）：一级免批、二级需批、三级禁止。
         * 判定在服务端，客户端传什么都改变不了。
         */
        // 2026-11-21：relation.unlink 从一级挪到二级 —— 删边是破坏性的，必须用户在手机上批。
        val TIER1 = setOf("record.note", "tag.add", "protocol.add", "zone.addMember", "relation.link")
        val TIER2 = setOf(
            "record.amend", "record.trash", "record.restore", "record.lock", "record.unlock",
            "tag.remove", "tag.merge", "tag.delete",
            "protocol.remove", "origin.assign",
            "relation.unlink", "relation.reject", "relation.retype", "relation.restore",
            "zone.create", "zone.removeMember",
            "tick.create", "tick.rename", "tick.bounds", "tick.summary", "tick.delete", "tick.restore", "tick.review.clear",
            "dial.create", "dial.rename", "dial.bounds", "dial.summary", "dial.delete",
            "dial.link", "dial.unlink",
            "draft.create", "draft.append", "ai.analyze",
            "tag.rename", "protocol.merge", "zone.rename", "zone.delete",
            // 2026-11-21：别名映射 / 删概念 —— 会改全库结构，必须手机批
            "alias.add", "alias.update", "alias.delete", "alias.merge", "concept.delete"
        )
        val TIER3 = setOf("lock.migrate")

        fun tierOf(verb: String): Int = when (verb) {
            in TIER1 -> 1
            in TIER3 -> 3
            else -> 2
        }

        /** 给 /health 公布策略。 */
        fun policy(): JSONObject = JSONObject()
            .put("tier1", JSONArray(TIER1))
            .put("tier2", JSONArray(TIER2))
            .put("tier3", JSONArray(TIER3))
            .put("rule", "一级免批直接执行；二级入队等批准；三级拒绝。批准/拒绝/撤回只能在手机上操作。")

        /** 对外支持的语义动词（错误信息里要列出来，让 agent 能自己改）。 */
        val VERBS = listOf(
            "record.note", "record.amend", "record.trash", "record.restore", "record.lock", "record.unlock",
            "tag.add", "tag.remove", "tag.merge", "tag.delete",
            "protocol.add", "protocol.remove",
            "origin.assign",
            "relation.link", "relation.unlink", "relation.reject", "relation.retype", "relation.restore",
            "draft.create", "draft.append", "ai.analyze",
            "tag.rename", "protocol.merge", "zone.rename", "zone.delete",
            "zone.create", "zone.addMember", "zone.removeMember",
            "tick.create", "tick.rename", "tick.bounds", "tick.summary", "tick.delete", "tick.restore", "tick.review.clear",
            "dial.create", "dial.rename", "dial.bounds", "dial.summary", "dial.delete",
            "dial.link", "dial.unlink",
            "alias.add", "alias.update", "alias.delete", "alias.merge", "concept.delete",
            "lock.migrate"
        )
    }
}
