package memore.app.data.repository

import memore.app.data.local.AppDatabase
import memore.app.data.model.Origin
import memore.app.data.model.Record
import kotlinx.coroutines.flow.Flow

/**
 * 来源（生产者）的读写与「自动识别」（2026-11-11）。
 *
 * 为什么需要自动识别：一次性给 243 条手标不现实，而来源的判据其实很稳
 * （长 + 分节 + "你…" 开头的多半是当时 AI 写的分析/转录）。
 * 识别的结果永远只是**建议**：界面上先给预览，确认后才写库。
 */
class OriginRepository(
    private val db: AppDatabase,
    private val recordRepository: RecordRepository
) {
    private val dao get() = db.originDao()

    fun observeAll(): Flow<List<Origin>> = dao.observeAll()

    suspend fun all(): List<Origin> = dao.all()

    /**
     * 首次安装时铺一遍内置来源。
     *
     * 2026-11-12 改：原来"缺哪条补哪条"，导致**用户删掉的内置来源每次进编辑页都会复活**
     * （EditRecordScreen 每次进入都调这里）。现在只在表为空时铺 —— 删掉就真的删掉了。
     */
    suspend fun ensureBuiltins() {
        val existing = dao.all()
        // 2026-11-23：新装库先被 ensureAgentAiOrigin() 塞进「AI」，表就不再为空，
        // 原来的 isEmpty 判断会跳过全部内置来源，最后只剩 AI。
        // 这里把"只有 AI"也视为未初始化，补回五条内置来源。
        if (existing.isEmpty() || existing.all { it.id == Origin.ID_AGENT_AI }) {
            dao.upsertAll(Origin.builtins())
        }
    }

    suspend fun upsert(origin: Origin) = dao.upsert(origin)

    /**
     * 2026-11-21：确保内置来源「AI」存在（老库里没有这条）。
     * 只补这一条，不复活用户删掉的其它内置来源。
     */
    suspend fun ensureAgentAiOrigin() {
        if (dao.byId(Origin.ID_AGENT_AI) != null) return
        Origin.builtins().firstOrNull { it.id == Origin.ID_AGENT_AI }?.let { dao.upsert(it) }
    }

    /**
     * 2026-11-21：AI 札记的来源回填到「AI」。
     * 只改“未标”或旧规则猜成「转录（AI）」的；显式设过的其他来源不动。幂等。
     */
    suspend fun backfillAiNoteOrigins() {
        dao.assignAiNoteOrigins(Origin.ID_AGENT_AI, Origin.ID_AI)
    }

    suspend fun update(origin: Origin) = dao.update(origin)

    /** 删除来源：它的记录变「未标」，不会丢记录。 */
    suspend fun delete(id: String) {
        dao.clearRecordsOf(id)
        dao.delete(id)
    }

    suspend fun countRecords(id: String): Int = dao.countRecords(id)

    /** 该来源下的记录（详情页核对用）。 */
    suspend fun recordsOf(id: String): List<Record> = dao.recordsOf(id)

    suspend fun countUnassigned(): Int = dao.countUnassigned()

    /** 所有未标记录归到指定来源（误删来源后一次性回填）。 */
    suspend fun assignAllUnassigned(originId: String) = dao.assignAllUnassigned(originId)

    /** 批量改来源；`originId == null` 表示批量清成未标。一条 SQL，不逐条写。 */
    suspend fun assign(recordIds: List<String>, originId: String?) {
        if (recordIds.isEmpty()) return
        if (originId == null) dao.clearForRecords(recordIds) else dao.assign(recordIds, originId)
    }

    suspend fun setOriginOf(recordId: String, originId: String?) {
        db.recordDao().setOrigin(recordId, originId)
    }

    // ===== 自动识别 =====

    data class Suggestion(val originId: String, val records: List<Record>) {
        val size: Int get() = records.size
    }

    data class ClassificationResult(
        val suggestions: List<Suggestion>,
        val unassigned: Int,
        val total: Int
    ) {
        fun countOf(originId: String): Int =
            suggestions.firstOrNull { it.originId == originId }?.size ?: 0
    }

    /**
     * 按规则给"还没有来源"的记录分类。不改库，只产出建议。
     *
     * 规则（从最特征化到最宽）：
     *  - 转录（AI）：≥600 字，且以「你/他/她」开头，或含「一、二、三、」这种中文分节
     *  - 转录：以「早期记录 / 早期总结 / 第X部分 / 修正记录 / 好的，X月」开头
     *  - 摘录再创作：以引号或书名号开头，或正文里出现「」引文但没有明显自述
     *  - 原创：其余
     */
    suspend fun classifyUnassigned(): ClassificationResult {
        val records = recordRepository.getAllActiveRecords()
        val buckets = linkedMapOf<String, MutableList<Record>>()
        var unassigned = 0
        for (r in records) {
            if (r.originId != null) continue
            // 2026-11-21：AI 札记不参与来源猜测（它是 agent 写的，默认来源固定为「AI」）。
            if (r.type == memore.app.data.model.RecordType.AI_NOTE) continue
            unassigned++
            buckets.getOrPut(guess(r)) { mutableListOf() }.add(r)
        }
        val order = listOf(Origin.ID_ORIGINAL, Origin.ID_DIALOGUE, Origin.ID_REWRITE, Origin.ID_TRANSCRIPT, Origin.ID_AI)
        val suggestions = order.mapNotNull { id ->
            buckets[id]?.takeIf { it.isNotEmpty() }?.let { Suggestion(id, it) }
        }
        return ClassificationResult(suggestions, unassigned, records.size)
    }

    /** 把一次分类建议整体写库。 */
    suspend fun applyClassification(result: ClassificationResult) {
        result.suggestions.forEach { s -> assign(s.records.map { it.id }, s.originId) }
    }

    /** 单条判定（供预览逐条改时复用）。 */
    fun guess(r: Record): String {
        val text = r.content.trim()
        if (text.isEmpty()) return Origin.ID_ORIGINAL
        if (isMixedDialogue(r)) return Origin.ID_DIALOGUE

        val head = text.take(24)
        val looksTranscript = Regex("^(早期记录|早期总结|修正记录|好的，\\d{1,2}月|20\\d{2}-\\d{1,2}-\\d{1,2})")
            .containsMatchIn(head) || Regex("^第[一二三四五六七八九十]+部分").containsMatchIn(head)
        if (looksTranscript) return Origin.ID_TRANSCRIPT

        val looksAi = text.length >= 600 &&
            (Regex("^(你|他|她)").containsMatchIn(text) || Regex("[一二三四五六七]、").containsMatchIn(text))
        if (looksAi) return Origin.ID_AI

        if (Regex("^[「\\\"“《]").containsMatchIn(text) && text.length < 400) return Origin.ID_REWRITE
        return Origin.ID_ORIGINAL
    }

    /** 对话录：正文里同时出现「我：」和 AI 一侧的标记（或本身就是 DIALOGUE 且够长）。 */
    private fun isMixedDialogue(r: Record): Boolean {
        val t = r.content
        val hasMe = t.contains("我：") || t.contains("用户：")
        val hasAi = t.contains("AI：") || t.contains("AI:")
        if (hasMe && hasAi) return true
        return r.type == memore.app.data.model.RecordType.DIALOGUE && t.length in 200..1500 &&
            (r.dialogueAiReply.isNotBlank() || hasMe)
    }
}
