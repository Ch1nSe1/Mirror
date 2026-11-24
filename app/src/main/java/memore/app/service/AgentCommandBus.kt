package memore.app.service

import memore.app.MemoreApp
import memore.app.data.local.dao.AgentCommandDao
import memore.app.data.model.AgentCommand
import memore.app.utils.LogUtil
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * agent 写命令的执行器（2026-11-13）。
 *
 * 设计要点：
 *  · **对外只有一个入口**（kind + payload），不给每个功能开接口
 *  · **执行走 repository**，不是裸 SQL——版本快照、FTS、计数才不出错
 *  · **幂等**：command id 由客户端给，重复提交只写一次
 *  · **模式**：readonly 拒绝 / approve 排队等批准 / auto 直接执行
 *
 * 新增一种命令 = 在 handle() 里加一个分支，别处不用动。
 */
class AgentCommandBus(private val app: MemoreApp) {

    private val dao: AgentCommandDao get() = app.container.database.agentCommandDao()

    enum class Mode(val value: String) {
        READ_ONLY("readonly"),
        APPROVE("approve"),
        AUTO("auto");

        companion object {
            fun of(raw: String?): Mode = entries.firstOrNull { it.value == raw } ?: APPROVE
        }
    }

    fun mode(): Mode = Mode.of(app.preferences.getString("agent_mode", null))

    fun setMode(mode: Mode) {
        app.preferences.edit().putString("agent_mode", mode.value).apply()
    }

    /** 接收到一条命令：按模式决定拒绝 / 排队 / 直接执行。 */
    fun submit(body: JSONObject): JSONObject {
        val mode = mode()
        if (mode == Mode.READ_ONLY) {
            return JSONObject().put("ok", false).put("error", "只读模式：agent 不能写入")
        }

        val id = body.optString("command_id").ifBlank {
            return JSONObject().put("ok", false).put("error", "缺 command_id（用于去重）")
        }
        val kind = body.optString("kind")
        if (kind !in AgentCommand.KINDS) {
            return JSONObject().put("ok", false).put("error", "不认识的命令: $kind")
        }
        val payload = body.optJSONObject("payload") ?: JSONObject()

        val cmd = AgentCommand(id = id, kind = kind, payload = payload.toString())
        val inserted = runBlocking { dao.insert(cmd) }
        if (inserted == -1L) {
            // 已经提交过：返回现状，不重复执行
            val existing = runBlocking { dao.byId(id) }
            return JSONObject()
                .put("ok", true)
                .put("duplicate", true)
                .put("status", existing?.status ?: "unknown")
        }

        return when (mode) {
            Mode.AUTO -> JSONObject().put("ok", true).put("status", "auto").put("result", execute(id))
            // 2026-11-15：排队了就要让手机响一声——否则命令躺在这儿等人翻设置
            Mode.APPROVE -> {
                syncNotification()
                JSONObject().put("ok", true).put("status", "pending").put("id", id)
            }
            Mode.READ_ONLY -> JSONObject().put("ok", false).put("error", "只读模式")
        }
    }

    /**
     * 批量提交（2026-11-13）：一次提一整批建议，共用一个 batchId。
     *
     * 为什么要批量：整理归并天然是"七条记录归到一处、再合并两个标签"这种成组的动作。
     * 一条一条批准会让人放弃使用——批准的成本必须低于收益。
     *
     * 数量上限 20：再多就没法逐条看了。
     */
    fun submitBatch(batchId: String, items: List<org.json.JSONArray>): JSONObject {
        val mode = mode()
        if (mode == Mode.READ_ONLY) {
            return JSONObject().put("ok", false).put("error", "只读模式：agent 不能写入")
        }
        if (items.isEmpty()) return JSONObject().put("ok", false).put("error", "空批次")
        if (items.size > 20) return JSONObject().put("ok", false).put("error", "一批最多 20 条")

        val ids = ArrayList<String>()
        for (item in items) {
            // item = [commandId, kind, payloadJson]
            val id = item.optString(0)
            val kind = item.optString(1)
            val payload = item.optString(2)
            if (id.isBlank() || kind !in AgentCommand.KINDS) {
                return JSONObject().put("ok", false).put("error", "批次里有非法命令")
            }
            val cmd = AgentCommand(id = id, kind = kind, payload = payload, batchId = batchId)
            val inserted = runBlocking { dao.insert(cmd) }
            if (inserted != -1L) ids += id
        }
        if (ids.isEmpty()) {
            return JSONObject().put("ok", true).put("duplicate", true).put("status", "pending")
        }
        return when (mode) {
            Mode.AUTO -> {
                ids.forEach { execute(it) }
                JSONObject().put("ok", true).put("status", "auto").put("count", ids.size)
            }
            else -> {
                syncNotification()
                JSONObject()
                    .put("ok", true)
                    .put("status", "pending")
                    .put("batch_id", batchId)
                    .put("count", ids.size)
            }
        }
    }

    /** 批准一整批：逐条执行，返回每条结果。 */
    fun approveBatch(batchId: String): JSONObject {
        val items = runBlocking { dao.pendingOfBatch(batchId) }
        if (items.isEmpty()) return JSONObject().put("ok", false).put("error", "没有待批准的批次")
        var ok = 0
        val failures = org.json.JSONArray()
        for (c in items) {
            val r = execute(c.id)
            if (r.optBoolean("ok")) ok++ else failures.put(r.optString("error"))
        }
        return JSONObject()
            .put("ok", true)
            .put("done", ok)
            .put("failed", failures.length())
            .put("errors", failures)
            .also { syncNotification() }
    }

    /** 用户在收件箱点「批准」。 */
    fun approve(id: String): JSONObject {
        val cmd = runBlocking { dao.byId(id) }
            ?: return JSONObject().put("ok", false).put("error", "没有这条命令")
        if (cmd.status != AgentCommand.STATUS_PENDING) {
            return JSONObject().put("ok", false).put("error", "这条已经是 ${cmd.status}")
        }
        return JSONObject().put("ok", true).put("result", execute(id)).also { syncNotification() }
    }

    /** 用户在收件箱点「忽略」。 */
    fun reject(id: String): JSONObject {
        runBlocking { dao.finish(id, AgentCommand.STATUS_REJECTED, "用户忽略") }
        syncNotification()
        return JSONObject().put("ok", true)
    }

    /**
     * 通知栏「批准」：把当前**所有**待批准的执行掉（2026-11-15）。
     * 逐条执行，返回成功/失败数——通知栏没有地方显示细节，所以只回计数，详情看收件箱。
     */
    fun approveAllPending(): JSONObject {
        val items = pending()
        if (items.isEmpty()) {
            AgentCommandNotifier.cancel(app)
            return JSONObject().put("ok", true).put("done", 0)
        }
        var ok = 0
        var failed = 0
        for (c in items) {
            if (execute(c.id).optBoolean("ok")) ok++ else failed++
        }
        return JSONObject().put("ok", true).put("done", ok).put("failed", failed)
    }

    /** 通知栏「忽略」：把所有待批准的标成 rejected。 */
    fun rejectAllPending(): JSONObject {
        val items = pending()
        items.forEach { reject(it.id) }
        return JSONObject().put("ok", true).put("ignored", items.size)
    }

    fun pending(): List<AgentCommand> = runBlocking {
        dao.byStatus(AgentCommand.STATUS_PENDING)
    }

    fun pendingCount(): Int = runBlocking { dao.pendingCount() }

    /**
     * 队列长度变了就更新那条「agent 想改 N 条记录」的通知（2026-11-15）。
     * 没有待批准就撤掉——通知栏不该留一条已经处理完的旧事。
     */
    private fun syncNotification() {
        runCatching {
            val count = pendingCount()
            if (count <= 0) AgentCommandNotifier.cancel(app)
            else AgentCommandNotifier.notifyPending(app, count)
        }
    }

    /**
     * AI 札记的隔离闸（2026-11-15）。
     *
     * 札记**不是"藏在界面上"，是接口层面就不存在**：它只能有正文 / 附件 / 加密 / 语音，
     * 不接受标签、协议、由来、情绪、增修、区域成员、锚点。所以这里在**执行前**按类型拦，
     * 而不是靠界面不显示——界面不显示但库里能写，就成了用户永远看不到的暗数据。
     *
     * @return 被拒的理由；不涉及札记时返回 null
     */
    private fun aiNoteRejection(cmd: AgentCommand): String? {
        val targets = when (cmd.kind) {
            AgentCommand.KIND_TAG, AgentCommand.KIND_PROTOCOL, AgentCommand.KIND_ORIGIN,
            AgentCommand.KIND_TRASH, AgentCommand.KIND_ZONE -> {
                runCatching {
                    org.json.JSONObject(cmd.payload).optJSONArray("record_ids")?.let { a ->
                        (0 until a.length()).map { a.getString(it) }
                    }.orEmpty()
                }.getOrDefault(emptyList())
            }
            AgentCommand.KIND_AMEND -> runCatching {
                org.json.JSONObject(cmd.payload).optString("parent_id").takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
            }.getOrDefault(emptyList())
            AgentCommand.KIND_RELATE, AgentCommand.KIND_UNLINK -> runCatching {
                val p = org.json.JSONObject(cmd.payload)
                listOf(p.optString("from_id"), p.optString("to_id")).filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
            // 加密对札记是允许的（它支持正文/附件/加密/语音），所以不拦
            else -> emptyList()
        }
        if (targets.isEmpty()) return null
        val records = runCatching {
            runBlocking { app.container.recordRepository.getRecordsByIds(targets) }
        }.getOrDefault(emptyList())
        val note = records.firstOrNull { it.type == memore.app.data.model.RecordType.AI_NOTE }
        if (note == null) return null
        // 软删除对札记也允许（札记可以被删）；区域/标签/增修/关联不允许。
        // 2026-11-21：**来源放行**——用户让它「把我说的记下来」时，agent 要把这条札记
        // 标成 原创/对话，而不是一律 AI。
        if (cmd.kind == AgentCommand.KIND_TRASH || cmd.kind == AgentCommand.KIND_ORIGIN) return null
        return "这条是 AI 札记：它只支持正文、附件、加密、语音，不支持${actionLabel(cmd.kind)}"
    }

    private fun actionLabel(kind: String): String = when (kind) {
        AgentCommand.KIND_TAG -> "标签"
        AgentCommand.KIND_ORIGIN -> "由来"
        AgentCommand.KIND_AMEND -> "增修"
        AgentCommand.KIND_RELATE, AgentCommand.KIND_UNLINK -> "关联"
        AgentCommand.KIND_ZONE -> "区域"
        AgentCommand.KIND_TRASH -> "删除"
        AgentCommand.KIND_LOCK -> "加密"
        AgentCommand.KIND_TAG_DELETE -> "删标签"
        AgentCommand.KIND_PROTOCOL -> "协议"
        AgentCommand.KIND_TICK -> "刻度"
        AgentCommand.KIND_DIAL -> "表盘"
        AgentCommand.KIND_LOCK_MIGRATE -> "迁移加密"
        AgentCommand.KIND_ALIAS -> "别名映射"
        AgentCommand.KIND_CONCEPT_DELETE -> "删概念"
        else -> "这个操作"
    }

    /**
     * 真正执行。每一步都走 repository，保证和 App 内操作同一条路。
     * 返回一句人话摘要（写进 note，收件箱和 agent 都能看到）。
     */
    private fun execute(id: String): JSONObject {
        val cmd = runBlocking { dao.byId(id) }
            ?: return JSONObject().put("ok", false).put("error", "没有这条命令")
        // 2026-11-15：札记隔离优先于一切（在动任何数据之前）
        aiNoteRejection(cmd)?.let { why -> return fail(id, why) }
        return try {
            val note = when (cmd.kind) {
                AgentCommand.KIND_TAG -> runTag(cmd)
                AgentCommand.KIND_CREATE -> runCreate(cmd)
                AgentCommand.KIND_AMEND -> runAmend(cmd)
                AgentCommand.KIND_ORIGIN -> runAssignOrigin(cmd)
                AgentCommand.KIND_RELATE -> runRelate(cmd)
                AgentCommand.KIND_TAG_MERGE -> runTagMerge(cmd)
                AgentCommand.KIND_TAG_DELETE -> runTagDelete(cmd)
                AgentCommand.KIND_PROTOCOL -> runProtocol(cmd)
                AgentCommand.KIND_TICK -> runTick(cmd)
                AgentCommand.KIND_DIAL -> runDial(cmd)
                AgentCommand.KIND_LOCK_MIGRATE -> runLockMigrate(cmd)
                AgentCommand.KIND_TRASH -> runTrash(cmd)
                AgentCommand.KIND_RESTORE -> runRestore(cmd)
                AgentCommand.KIND_LOCK -> runLock(cmd)
                AgentCommand.KIND_UNLOCK -> runUnlock(cmd)
                AgentCommand.KIND_UNLINK -> runUnlink(cmd)
                AgentCommand.KIND_REJECT_REL -> runRejectRelation(cmd)
                AgentCommand.KIND_RETYPE_REL -> runRetypeRelation(cmd)
                AgentCommand.KIND_RESTORE_REL -> runRestoreRelation(cmd)
                AgentCommand.KIND_DRAFT_CREATE -> runDraftCreate(cmd)
                AgentCommand.KIND_DRAFT_APPEND -> runDraftAppend(cmd)
                AgentCommand.KIND_AI_ANALYZE -> runAiAnalyze(cmd)
                AgentCommand.KIND_TAG_RENAME -> runTagRename(cmd)
                AgentCommand.KIND_PROTOCOL_MERGE -> runProtocolMerge(cmd)
                AgentCommand.KIND_ZONE_RENAME -> runZoneRename(cmd)
                AgentCommand.KIND_ZONE_DELETE -> runZoneDelete(cmd)
                AgentCommand.KIND_ZONE -> runZone(cmd)
                AgentCommand.KIND_ZONE_CREATE -> runZoneCreate(cmd)
                AgentCommand.KIND_ALIAS -> runAlias(cmd)
                AgentCommand.KIND_CONCEPT_DELETE -> runConceptDelete(cmd)
                else -> return fail(id, "这个命令还没实现：${cmd.kind}")
            }
            runBlocking { dao.finish(id, AgentCommand.STATUS_DONE, note) }
            JSONObject().put("ok", true).put("status", "done").put("note", note)
        } catch (e: Exception) {
            LogUtil.e("AgentCommand", "执行失败", e)
            fail(id, e.message ?: "执行失败")
        }
    }

    private fun fail(id: String, why: String): JSONObject {
        runBlocking { dao.finish(id, AgentCommand.STATUS_FAILED, why) }
        return JSONObject().put("ok", false).put("status", "failed").put("error", why)
    }

    // ── 2026-11-15 补的五种"减/移"动作 ─────────────────────────────

    /** 软删除：进回收站，可恢复。payload: { record_ids } */
    private fun runTrash(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val ids = p.optJSONArray("record_ids")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        if (ids.isEmpty()) error("record_ids 不能为空")
        if (ids.size > 50) error("一次最多 50 条")
        runBlocking { app.container.recordRepository.softDeleteRecords(ids) }
            .getOrElse { error("删除失败：" + (it.message ?: "未知")) }
        return "把 ${ids.size} 条放进了回收站"
    }

    /** 从回收站恢复（trash 的反向）。payload: { record_ids } */
    private fun runRestore(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val ids = p.optJSONArray("record_ids")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        if (ids.isEmpty()) error("record_ids 不能为空")
        var done = 0
        ids.forEach { rid ->
            runBlocking { app.container.recordRepository.restoreRecord(rid) }.onSuccess { done++ }
        }
        if (done == 0) error("没有可恢复的记录")
        return "从回收站恢复 $done 条"
    }

    /**
     * 加密：密码随动作来，验一次就用，用完即弃。
     * payload: { record_ids, password }
     */
    private fun runLock(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val ids = p.optJSONArray("record_ids")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        if (ids.isEmpty()) error("record_ids 不能为空")
        val password = p.optString("password")
        if (password.isBlank()) error("没密码，没干")
        // 2026-11-15：**必须走 ensureKey**，不能只 verifyPassword。
        // verifyPassword 只校验、不缓存密钥，而 lockRecord 依赖 getCachedKey()——
        // 只验不缓存的结果是"密码对、但一条都锁不上"。这是真机踩出来的。
        runBlocking { memore.app.utils.EncryptionManager.ensureKey(password) }
            .getOrElse { error("密码不对，没干（" + (it.message ?: "") + "）") }
        var done = 0
        ids.forEach { rid ->
            runBlocking { app.container.recordRepository.lockRecord(rid) }
                .onSuccess { done++ }
        }
        if (done == 0) error("一条都没锁上")
        return "加密了 $done 条"
    }

    /**
     * 解密：lock 的反向，密码同样当次给。
     * payload: { record_ids, password }
     */
    private fun runUnlock(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val ids = p.optJSONArray("record_ids")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        if (ids.isEmpty()) error("record_ids 不能为空")
        val password = p.optString("password")
        if (password.isBlank()) error("没密码，没干")
        var done = 0
        var firstError: String? = null
        ids.forEach { rid ->
            runBlocking { app.container.recordRepository.unlockRecord(rid, password) }
                .onSuccess { done++ }
                .onFailure { firstError = firstError ?: it.message }
        }
        if (done == 0) error(firstError ?: "一条都没解开")
        return "解密了 $done 条"
    }

    /** 断开关联。payload: { from_id, to_id } */
    private fun runUnlink(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val from = p.optString("from_id")
        val to = p.optString("to_id")
        if (from.isBlank() || to.isBlank()) error("from_id / to_id 不能为空")
        // 2026-11-21：两个方向一起删。读取侧把 A→B / B→A 当成同一条边，
        // 只删单向会留下反向边，界面上看起来像"没删掉"。
        val n = runBlocking {
            app.container.database.recordRelationDao().deletePairBothWays(from, to)
        }
        return "断开 $n 条关联"
    }

    /**
     * 别名映射管理（2026-11-21）。payload: { op, standard, aliases[], kind, from, to }
     * op = add / update / delete / merge
     */
    private fun runAlias(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val op = p.optString("op").lowercase()
        val repo = app.container.aliasMappingRepo
        val aliases = p.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.optString(it, "").trim() } }
            ?.filter { it.isNotEmpty() }.orEmpty()
        val kind = p.optString("kind", "concept").ifBlank { "concept" }
        return when (op) {
            "add" -> {
                val standard = p.optString("standard").trim()
                if (standard.isEmpty()) error("standard 不能为空")
                if (runBlocking { repo.byStandard(standard) } != null) {
                    error("已经有同名的组（$standard）：要并过去请用 merge")
                }
                runBlocking {
                    repo.addGroup(memore.app.data.model.AliasGroup(standardName = standard, aliases = aliases, kind = kind))
                }
                "新增映射组：$standard"
            }
            "update" -> {
                val standard = p.optString("standard").trim()
                if (standard.isEmpty()) error("standard 不能为空")
                val group = runBlocking { repo.byStandard(standard) } ?: error("没有这个组：$standard")
                runBlocking { repo.updateGroup(group.copy(aliases = aliases, kind = kind)) }
                "更新映射组：$standard"
            }
            "delete" -> {
                val standard = p.optString("standard").trim()
                if (standard.isEmpty()) error("standard 不能为空")
                val group = runBlocking { repo.byStandard(standard) } ?: error("没有这个组：$standard")
                runBlocking { repo.deleteGroup(group.id) }
                "删除映射组：$standard"
            }
            "merge" -> {
                val from = p.optString("from").trim()
                val to = p.optString("to").trim()
                if (from.isEmpty() || to.isEmpty()) error("from / to 不能为空")
                val merged = runBlocking { repo.mergeGroups(from, to) }
                    ?: error("合并失败：检查两个名字（不能相同、from 必须存在）")
                "已合并：$from → ${merged.standardName}"
            }
            else -> error("不支持的 op：$op（add / update / delete / merge）")
        }
    }

    /** 删除一个概念（只删锚点行）。payload: { name } */
    private fun runConceptDelete(cmd: AgentCommand): String {
        val name = JSONObject(cmd.payload).optString("name").trim()
        if (name.isEmpty()) error("name 不能为空")
        val n = runBlocking { app.container.anchorRepository.deleteConcept(name) }
        if (n == 0) error("没有这个概念：$name")
        return "删掉 $n 行锚点（概念：$name）"
    }

    /**
     * 删除**空标签**（2026-11-15）。payload: { names: [...] }
     *
     * 只清测试残留：有记录在用的标签一律拒绝——删它等于悄悄改掉那些记录的分类，
     * 不是"清理"，是数据变更。
     */
    private fun runTagDelete(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val names = p.optJSONArray("names")?.let { a ->
            (0 until a.length()).map { a.getString(it).trim() }
        }.orEmpty().filter { it.isNotBlank() }
        if (names.isEmpty()) error("names 不能为空")
        val db = app.container.database
        val tagRepo = app.container.tagRepository
        var done = 0
        val used = mutableListOf<String>()
        names.forEach { name ->
            val tag = runBlocking { db.tagDao().getTagByName(name) } ?: return@forEach
            val refs = runBlocking { db.recordDao().recordIdsByTagName(name) }
            if (refs.isNotEmpty()) {
                used += "$name(${refs.size})"
                return@forEach
            }
            runBlocking { tagRepo.deleteTag(tag.id) }.onSuccess { done++ }
        }
        if (used.isNotEmpty()) error("这些标签还有记录在用，没删：${used.joinToString("、")}")
        if (done == 0) error("没有可删的空标签")
        return "删掉 $done 个空标签"
    }

    /**
     * 区域成员加/移。payload: { record_ids, zone_id, op: "add"|"remove" }
     * zone_id = zone_anchor 时改的是 `records.is_anchored`（锚点是三种实现之一）。
     */
    private fun runZone(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val ids = p.optJSONArray("record_ids")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        if (ids.isEmpty()) error("record_ids 不能为空")
        val zoneId = p.optString("zone_id")
        if (zoneId.isBlank()) error("zone_id 不能为空")
        val remove = p.optString("op").equals("remove", ignoreCase = true)
        val zoneRepo = app.container.zoneRepository
        val repo = app.container.recordRepository
        var done = 0
        ids.forEach { rid ->
            if (zoneId == memore.app.data.model.BuiltinZones.ANCHOR) {
                // 锚点只有一个布尔位，加/移 = 当前状态不对就切
                val rec = runBlocking { repo.getRecordById(rid) } ?: return@forEach
                if (rec.isAnchored != remove) {
                    runBlocking { repo.toggleAnchor(rid) }.onSuccess { done++ }
                } else {
                    done++
                }
            } else {
                if (remove) {
                    runBlocking { zoneRepo.removeFromZone(zoneId, rid) }
                } else {
                    runBlocking { zoneRepo.addToZone(zoneId, rid) }
                }
                done++
            }
        }
        return (if (remove) "从区域移出 $done 条" else "放进区域 $done 条")
    }

    /** 新建自定义区域。payload: { name } */
    private fun runZoneCreate(cmd: AgentCommand): String {
        val name = JSONObject(cmd.payload).optString("name").trim()
        if (name.isBlank()) error("name 不能为空")
        runBlocking {
            app.container.zoneRepository.create(
                name = name,
                condition = memore.app.data.repository.ZoneCondition(manual = true)
            )
        }
        return "新建区域「$name」"
    }

    /**
     * 刻度编辑（2026-11-15）。payload: { op, id?, name?, start_ms?, end_ms?, summary? }
     *
     * id 由动作层在**提议时**生成并写进 payload——这样"新建刻度"的撤回能按 id 精确删掉，
     * 不靠"按名字找回来"这种不可靠的办法。
     */
    private fun runTick(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val op = p.optString("op").lowercase()
        val id = p.optString("id").trim()
        val repo = app.container.tickRepository
        return when (op) {
            "create" -> {
                val name = p.optString("name").trim()
                if (name.isBlank()) error("name 不能为空")
                if (id.isBlank()) error("缺 id（应由动作层生成）")
                val start = p.optLong("start_ms", System.currentTimeMillis())
                val end = if (p.has("end_ms") && !p.isNull("end_ms")) p.optLong("end_ms") else null
                runBlocking {
                    app.container.database.tickDao().insert(
                        memore.app.data.model.Tick(id = id, name = name, startMs = start, endMs = end)
                    )
                    repo.refreshSlice(id)
                }
                "新建刻度「$name」"
            }
            "rename" -> {
                if (id.isBlank()) error("id 不能为空")
                val name = p.optString("name").trim()
                if (name.isBlank()) error("name 不能为空")
                runBlocking { repo.rename(id, name) }
                "刻度改名为「$name」"
            }
            "bounds" -> {
                if (id.isBlank()) error("id 不能为空")
                val start = p.optLong("start_ms", 0L)
                if (start <= 0L) error("start_ms 不能为空")
                val end = if (p.has("end_ms") && !p.isNull("end_ms")) p.optLong("end_ms") else null
                runBlocking { repo.updateBounds(id, start, end) }
                "刻度起止已改"
            }
            "summary" -> {
                if (id.isBlank()) error("id 不能为空")
                val summary = p.optString("summary")
                if (summary.isBlank()) error("summary 不能为空")
                runBlocking { repo.updateSummary(id, summary) }
                "刻度总结已写（${summary.length} 字）"
            }
            "review_clear" -> {
                if (id.isBlank()) error("id 不能为空")
                runBlocking { repo.updateReview(id, "") }
                "刻度回看已删除"
            }
            "delete" -> {
                if (id.isBlank()) error("id 不能为空")
                runBlocking { repo.delete(id) }
                "刻度已删除（进回收站）"
            }
            "restore" -> {
                if (id.isBlank()) error("id 不能为空")
                runBlocking { repo.restore(id) }
                "刻度已恢复"
            }
            else -> error("不认识的刻度操作：$op")
        }
    }

    /**
     * v5 迁移（2026-11-16）。payload: { password }
     *
     * 单向操作：把旧的逐条密文解回明文，锁标记保留，配置切换成"只留 verifier"。
     * **没有撤回**：内容回到明文之后再加密回去是另一件事，不该混在 undo 里。
     */
    private fun runLockMigrate(cmd: AgentCommand): String {
        val password = JSONObject(cmd.payload).optString("password")
        if (password.isBlank()) error("password 不能为空（旧密码）")
        val db = app.container.database
        val report = runBlocking {
            memore.app.utils.LockMigrationV5.run(password, db.recordDao(), db.attachmentDao())
        }.getOrElse { error(it.message ?: "迁移失败") }
        if (!report.ok) {
            error("迁移未完成（配置仍是旧版，可重试）：" + report.failures.take(3).joinToString("；"))
        }
        return "已迁移到 v5：${report.records} 条记录、${report.attachments} 个附件解回明文"
    }

    /** 表盘编辑（2026-11-15）。payload: { op, id?, name?, start_ms?, end_ms?, summary?, tick_id?, dial_id? } */
    private fun runDial(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val op = p.optString("op").lowercase()
        val id = p.optString("id").trim()
        val repo = app.container.dialRepository
        return when (op) {
            "create" -> {
                val name = p.optString("name").trim()
                if (name.isBlank()) error("name 不能为空")
                if (id.isBlank()) error("缺 id（应由动作层生成）")
                val start = p.optLong("start_ms", System.currentTimeMillis())
                val end = if (p.has("end_ms") && !p.isNull("end_ms")) p.optLong("end_ms") else null
                runBlocking {
                    app.container.database.dialDao().insert(
                        memore.app.data.model.Dial(id = id, name = name, startMs = start, endMs = end)
                    )
                }
                "新建表盘「$name」"
            }
            "rename" -> {
                if (id.isBlank()) error("id 不能为空")
                val name = p.optString("name").trim()
                if (name.isBlank()) error("name 不能为空")
                runBlocking { repo.renameDial(id, name) }
                "表盘改名为「$name」"
            }
            "bounds" -> {
                if (id.isBlank()) error("id 不能为空")
                val start = p.optLong("start_ms", 0L)
                if (start <= 0L) error("start_ms 不能为空")
                val end = if (p.has("end_ms") && !p.isNull("end_ms")) p.optLong("end_ms") else null
                runBlocking { repo.updateDialBounds(id, start, end) }
                "表盘起止已改"
            }
            "summary" -> {
                if (id.isBlank()) error("id 不能为空")
                val summary = p.optString("summary")
                if (summary.isBlank()) error("summary 不能为空")
                runBlocking { repo.updateDialSummary(id, summary) }
                "表盘总结已写（${summary.length} 字）"
            }
            "delete" -> {
                if (id.isBlank()) error("id 不能为空")
                runBlocking { repo.deleteDial(id) }
                "表盘已删除"
            }
            "link" -> {
                val dialId = p.optString("dial_id").ifBlank { id }
                val tickId = p.optString("tick_id")
                if (dialId.isBlank() || tickId.isBlank()) error("dial_id / tick_id 不能为空")
                runBlocking { repo.linkTick(dialId, tickId) }
                "刻度已挂到表盘"
            }
            "unlink" -> {
                val dialId = p.optString("dial_id").ifBlank { id }
                val tickId = p.optString("tick_id")
                if (dialId.isBlank() || tickId.isBlank()) error("dial_id / tick_id 不能为空")
                runBlocking { repo.unlinkTick(dialId, tickId) }
                "刻度已从表盘摘下"
            }
            else -> error("不认识的表盘操作：$op")
        }
    }

    /**
     * 试点命令：打/去标签。
     *
     * 选它当第一个，因为它**不新增内容**——最坏情况是你手动去掉标签，
     * 而 `create_record` 写错了要删一整条记录。
     */
    private fun runTag(cmd: AgentCommand): String {
        val payload = JSONObject(cmd.payload)
        val ids = payload.optJSONArray("record_ids")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }.orEmpty()
        if (ids.isEmpty()) error("record_ids 不能为空")
        if (ids.size > 50) error("一次最多 50 条")

        val add = payload.optJSONArray("add")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }.orEmpty()
        val remove = payload.optJSONArray("remove")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }.orEmpty()

        val tagRepo = app.container.tagRepository
        var added = 0
        var removed = 0
        for (recordId in ids) {
            for (name in add) {
                val tag = runBlocking { tagRepo.getOrCreateTag(name.trim()) }
                runBlocking { tagRepo.addTagToRecord(recordId, tag.id) }
                added++
            }
            for (name in remove) {
                val tag = runBlocking { tagRepo.getOrCreateTag(name.trim()) }
                runBlocking { tagRepo.removeTagFromRecord(recordId, tag.id) }
                removed++
            }
        }
        runBlocking { tagRepo.refreshAllCounts() }
        return buildString {
            append("给 ${ids.size} 条记录")
            if (added > 0) append(" 加了 $added 个标签")
            if (removed > 0) append(" 去了 $removed 个标签")
        }
    }

    /**
     * 协议加/去（2026-11-15）。payload: { record_ids, add:[名字], remove:[名字] }
     *
     * 和标签同一套形状：协议是记录上的"类/体裁"标记，之前只有 App 内能改。
     */
    private fun runProtocol(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val ids = p.optJSONArray("record_ids")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        if (ids.isEmpty()) error("record_ids 不能为空")
        if (ids.size > 50) error("一次最多 50 条")
        val add = p.optJSONArray("add")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val remove = p.optJSONArray("remove")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        if (add.isEmpty() && remove.isEmpty()) error("add / remove 至少给一个")
        val protoRepo = app.container.protocolRepository
        var added = 0
        var removed = 0
        ids.forEach { rid ->
            add.forEach { name ->
                runBlocking {
                    val proto = protoRepo.getOrCreateProtocol(name.trim())
                    protoRepo.addProtocolToRecord(rid, proto.id)
                }
                added++
            }
            remove.forEach { name ->
                runBlocking {
                    val proto = protoRepo.getOrCreateProtocol(name.trim())
                    protoRepo.removeProtocolFromRecord(rid, proto.id)
                }
                removed++
            }
        }
        runCatching { runBlocking { protoRepo.refreshAllCounts() } }
        return buildString {
            append("给 ${ids.size} 条记录")
            if (added > 0) append(" 加了 $added 个协议")
            if (removed > 0) append(" 去了 $removed 个协议")
        }
    }

    /**
     * 新建一条记录。
     *
     * payload: { type: "ESSAY"|"DIALOGUE", content, origin_id?, tags?: [string] }
     * 走 recordRepository.addRecord —— 和 App 里手动新建同一条路。
     */
    private fun runCreate(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val content = p.optString("content").trim()
        if (content.isBlank()) error("content 不能为空")
        if (content.length > 20000) error("正文太长（上限 2 万字）")

        // agent 写的东西默认是「AI 札记」（2026-11-14）：
        // 它是并列的记录类型，界面上没有新建入口——只有 agent 能产出。
        val type = when (p.optString("type").uppercase()) {
            "DIALOGUE" -> memore.app.data.model.RecordType.DIALOGUE
            "ESSAY" -> memore.app.data.model.RecordType.ESSAY
            else -> memore.app.data.model.RecordType.AI_NOTE
        }
        // 2026-11-21：来源默认值按类型给——AI 自己写的札记 = 「AI」来源；
        // 用户口述/让 agent 代记（ESSAY / DIALOGUE）不该被标成 AI 来源。
        // 显式传 origin_id 时以它为准（agent 可以在用户要求下改）。
        val defaultOrigin = when (type) {
            memore.app.data.model.RecordType.AI_NOTE -> memore.app.data.model.Origin.ID_AGENT_AI
            memore.app.data.model.RecordType.DIALOGUE -> memore.app.data.model.Origin.ID_DIALOGUE
            memore.app.data.model.RecordType.ESSAY -> memore.app.data.model.Origin.ID_ORIGINAL
            else -> memore.app.data.model.Origin.ID_AGENT_AI
        }
        val originId = p.optString("origin_id").ifBlank { defaultOrigin }

        // 2026-11-22：**创建时挂标签会绕过札记隔离**。aiNoteRejection 只查已存在的
        // record_ids，而新记录在检查时还没插入；所以必须在插入前就把这条堵死。
        val tags = p.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        if (type == memore.app.data.model.RecordType.AI_NOTE && tags.isNotEmpty()) {
            error("AI 札记不支持标签（只能正文、附件、加密、语音）")
        }

        val record = memore.app.data.model.Record(
            id = java.util.UUID.randomUUID().toString(),
            type = type,
            content = content,
            originId = originId
        )
        val result = runBlocking { app.container.recordRepository.addRecord(record) }
        result.getOrElse { error("写入失败：" + (it.message ?: "未知")) }

        if (tags.isNotEmpty()) {
            val tagRepo = app.container.tagRepository
            tags.forEach { name ->
                val tag = runBlocking { tagRepo.getOrCreateTag(name.trim()) }
                runBlocking { tagRepo.addTagToRecord(record.id, tag.id) }
            }
            runBlocking { tagRepo.refreshAllCounts() }
        }
        return "新建 1 条" + if (tags.isEmpty()) "" else "，加了 ${tags.size} 个标签"
    }

    /**
     * 给一条记录加增修。
     *
     * payload: { parent_id, content }
     * 增修是 AMENDMENT 类型 + correction_of_id 指向父记录——这是 App 的既有规矩。
     */
    private fun runAmend(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val parentId = p.optString("parent_id")
        val content = p.optString("content").trim()
        if (parentId.isBlank()) error("parent_id 不能为空")
        if (content.isBlank()) error("content 不能为空")

        val parent = app.container.database.recordDao().let { dao ->
            runBlocking { dao.rawById(parentId) }
        } ?: error("父记录不存在")

        val record = memore.app.data.model.Record(
            id = java.util.UUID.randomUUID().toString(),
            type = memore.app.data.model.RecordType.AMENDMENT,
            content = content,
            originId = parent.originId ?: "origin_ai",
            correctionOfId = parentId
        )
        runBlocking { app.container.recordRepository.addRecord(record) }
            .getOrElse { error("写入失败：" + (it.message ?: "未知")) }
        return "给一条记录加了增修"
    }

    /** payload: { record_ids: [...], origin_id } */
    private fun runAssignOrigin(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val ids = p.optJSONArray("record_ids")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val originId = p.optString("origin_id").ifBlank { error("origin_id 不能为空") }
        if (ids.isEmpty()) error("record_ids 不能为空")
        if (ids.size > 200) error("一次最多 200 条")

        val known = runBlocking { app.container.database.originDao().all() }.any { it.id == originId }
        if (!known) error("没有这个来源：$originId")

        runBlocking { app.container.originRepository.assign(ids, originId) }
        return "把 ${ids.size} 条记录归到该来源"
    }

    /**
     * 合并两个标签：把 from 的记录全挪到 to，然后删掉 from。
     * payload: { from_name, to_name }
     */
    private fun runTagMerge(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val fromName = p.optString("from_name").trim()
        val toName = p.optString("to_name").trim()
        if (fromName.isBlank() || toName.isBlank()) error("from_name / to_name 不能为空")
        if (fromName == toName) error("两个名字一样，不用合并")

        val tagRepo = app.container.tagRepository
        val from = runBlocking { tagRepo.getOrCreateTag(fromName) }
        val to = runBlocking { tagRepo.getOrCreateTag(toName) }
        runBlocking { tagRepo.mergeTags(from.id, to.id) }.getOrElse { error("合并失败：" + (it.message ?: "未知")) }
        runBlocking { tagRepo.refreshAllCounts() }
        return "把「$fromName」并进「$toName」"
    }

    /**
     * 建立关联（2026-11-20 收紧）。
     *
     * 和 App 内的 AI 关联同一条纪律：**必须带两边原文引文**，且引文真的在各自正文里；
     * type 只认四种语义类型；source 记 `AGENT`，不冒充 AI（AI 边会被重跑分析失效，
     * agent 建的边不应被牵连，也不该污染 AI 统计）。
     *
     * payload: { from_id, to_id, type?, why?, quote_a, quote_b }
     */
    private fun runRelate(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val from = p.optString("from_id").trim()
        val to = p.optString("to_id").trim()
        if (from.isBlank() || to.isBlank()) error("from_id / to_id 不能为空")
        if (from == to) error("不能和自己建立关联")

        val type = p.optString("type").trim().uppercase().ifBlank { "LINK" }
        if (type !in RELATION_TYPES) {
            error("type 只能是 ${RELATION_TYPES.joinToString(" / ")}")
        }
        val quoteA = p.optString("quote_a").trim()
        val quoteB = p.optString("quote_b").trim()
        if (quoteA.isBlank() || quoteB.isBlank()) {
            error("quote_a / quote_b 不能为空：agent 建的关联必须带两边原句")
        }
        val repo = app.container.recordRepository
        val ra = runBlocking { repo.getRecordById(from) } ?: error("找不到记录 $from")
        val rb = runBlocking { repo.getRecordById(to) } ?: error("找不到记录 $to")
        if (quoteA !in ra.content) error("quote_a 不在这条记录正文里")
        if (quoteB !in rb.content) error("quote_b 不在这条记录正文里")

        val rel = memore.app.data.model.RecordRelation(
            fromId = from,
            toId = to,
            type = type,
            why = p.optString("why").trim(),
            quoteA = quoteA,
            quoteB = quoteB,
            source = memore.app.data.model.RecordRelation.SOURCE_AGENT,
            validFrom = System.currentTimeMillis(),
            status = "ACTIVE"
        )
        runBlocking { app.container.database.recordRelationDao().insert(rel) }
        return "建立 1 条 $type 关联（如已存在则忽略）"
    }

    /** 拒绝一条边：标 REJECTED，留作负样本，不删除。payload: { from_id, to_id } */
    private fun runRejectRelation(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val from = p.optString("from_id").trim()
        val to = p.optString("to_id").trim()
        if (from.isBlank() || to.isBlank()) error("from_id / to_id 不能为空")
        runBlocking { app.container.database.recordRelationDao().rejectLink(from, to) }
        return "已拒绝 1 条关联"
    }

    /** 改类型：payload: { from_id, to_id, type } */
    private fun runRetypeRelation(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val from = p.optString("from_id").trim()
        val to = p.optString("to_id").trim()
        if (from.isBlank() || to.isBlank()) error("from_id / to_id 不能为空")
        val type = p.optString("type").trim().uppercase()
        if (type !in RELATION_TYPES) error("type 只能是 ${RELATION_TYPES.joinToString(" / ")}")
        runCatching {
            runBlocking { app.container.database.recordRelationDao().setType(from, to, type) }
        }.getOrElse { e -> error("改类型失败（可能已有同类型边）：${e.message ?: "未知"}") }
        return "已改为 $type"
    }

    /** 恢复一条被拒绝的边。payload: { from_id, to_id } */
    private fun runRestoreRelation(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val from = p.optString("from_id").trim()
        val to = p.optString("to_id").trim()
        if (from.isBlank() || to.isBlank()) error("from_id / to_id 不能为空")
        runBlocking { app.container.database.recordRelationDao().restoreStatus(from, to) }
        return "已恢复 1 条关联"
    }

    // ---- 批次三（2026-11-20）：写作与结构化补全 ----

    private fun runDraftCreate(cmd: AgentCommand): String {
        val name = JSONObject(cmd.payload).optString("name").trim().ifBlank { "未命名" }
        val id = runBlocking { app.container.draftRepository.createDraft(name) }
        return "新建草稿 #$id"
    }

    private fun runDraftAppend(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val draftId = p.optString("draft_id").toLongOrNull() ?: error("draft_id 不能为空")
        val content = p.optString("content").trim()
        if (content.isBlank()) error("content 不能为空")
        val kind = p.optString("kind").trim().uppercase().ifBlank { memore.app.data.model.DraftKind.MINE }
        val allowed = setOf(memore.app.data.model.DraftKind.MINE, memore.app.data.model.DraftKind.QA, memore.app.data.model.DraftKind.DISCUSS)
        if (kind !in allowed) error("kind 只能是 MINE / QA / DISCUSS")
        runBlocking { app.container.draftRepository.add(draftId, content, kind) }
            ?: error("草稿不存在")
        return "已追加到草稿 #$draftId"
    }

    private fun runAiAnalyze(cmd: AgentCommand): String {
        val ids = JSONObject(cmd.payload).optJSONArray("record_ids")
            ?.let { a -> (0 until a.length()).take(50).map { a.getString(it) } }
            .orEmpty()
        if (ids.isEmpty()) error("record_ids 不能为空")
        val repo = app.container.recordRepository
        // 入队可能被静默跳过（锁定/已在队列/DONE 中）：只报真实增量。
        val before = runBlocking { runCatching { repo.getActiveTasks() }.getOrDefault(emptyList()) }.size
        ids.forEach { rid ->
            runCatching { runBlocking { repo.enqueueTask(rid) } }
        }
        val after = runBlocking { runCatching { repo.getActiveTasks() }.getOrDefault(emptyList()) }.size
        return "已入队 ${after - before} 条分析（请求 ${ids.size} 条）"
    }

    private fun runTagRename(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val from = p.optString("from_name").trim()
        val to = p.optString("to_name").trim()
        if (from.isBlank() || to.isBlank()) error("from_name / to_name 不能为空")
        val repo = app.container.tagRepository
        val tag = runBlocking { repo.getOrCreateTag(from) }
        runBlocking { repo.renameTag(tag.id, to) }.getOrElse { e -> error("改名失败：" + (e.message ?: "未知")) }
        runBlocking { repo.refreshAllCounts() }
        return "已把「$from」改名为「$to」"
    }

    private fun runProtocolMerge(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val from = p.optString("from_name").trim()
        val to = p.optString("to_name").trim()
        if (from.isBlank() || to.isBlank()) error("from_name / to_name 不能为空")
        val repo = app.container.protocolRepository
        val a = runBlocking { repo.getOrCreateProtocol(from) }
        val b = runBlocking { repo.getOrCreateProtocol(to) }
        runBlocking { repo.mergeProtocols(a.id, b.id) }.getOrElse { e -> error("合并失败：" + (e.message ?: "未知")) }
        return "已把协议「$from」并进「$to」"
    }

    private fun runZoneRename(cmd: AgentCommand): String {
        val p = JSONObject(cmd.payload)
        val zid = p.optString("zone_id").trim()
        val name = p.optString("name").trim()
        if (zid.isBlank() || name.isBlank()) error("zone_id / name 不能为空")
        runBlocking { app.container.zoneRepository.rename(zid, name) }
        return "区域已改名"
    }

    private fun runZoneDelete(cmd: AgentCommand): String {
        val zid = JSONObject(cmd.payload).optString("zone_id").trim()
        if (zid.isBlank()) error("zone_id 不能为空")
        runBlocking { app.container.zoneRepository.delete(zid) }
        return "区域已删除"
    }

    private companion object {
        val RELATION_TYPES = setOf("LINK", "CITE", "SUPPLEMENT", "CORRECT")
    }
}
