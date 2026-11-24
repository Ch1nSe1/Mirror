package memore.app.data.repository

import memore.app.data.local.AppDatabase
import memore.app.data.model.AliasGroup

/**
 * 按锚点聚合（2026-11-19）。
 *
 * 关联边缘现在只有"记录 → 记录"，没有父。锚点是正文里明确提到的人/作品/地点，
 * 天然可以当父：同一个锚点下的记录，和这些记录之间的边，构成一组。
 *
 * 别名：复用旧的别名映射表（kind != emotion 的组）。`Alice` / `alice` / `アリス` 归到同一个父，
 * 大小写不同的锚点也不再分成两行。
 */
class AnchorRepository(
    private val db: AppDatabase?,
    private val aliasRepository: AliasMappingRepository,
    private val recordRepository: RecordRepository
) {

    data class AnchorRecord(
        val id: String,
        val type: memore.app.data.model.RecordType,
        val date: String,
        val snippet: String
    )

    data class AnchorEdge(
        val fromId: String,
        val toId: String,
        val fromDate: String,
        val toDate: String,
        val type: String,
        val why: String,
        val quoteA: String,
        val quoteB: String,
        /** 2026-11-22：这条边两端共享的概念（显示用）。空 = 跨概念/无线索。 */
        val sharedConcepts: List<String> = emptyList()
    )

    data class AnchorGroup(
        val name: String,
        val variants: List<String>,
        val records: List<AnchorRecord>,
        val edges: List<AnchorEdge>,
        /** 类型（PERSON / WORK / ...）：只作信息，不参与分组（同名一律合并）。 */
        val kind: String = "",
        /** 列表唯一 key（2026-11-21：只按概念名，同名只出现一行）。 */
        val key: String = name.lowercase()
    )

    suspend fun groups(): List<AnchorGroup> {
        val database = db ?: return emptyList()
        val aliasGroups = runCatching { aliasRepository.load() }.getOrDefault(emptyList())
        val canonicalOf = canonicalNames(aliasGroups)

        val rows = runCatching {
            database.recordEntityDao().allWithRecord()
        }.getOrDefault(emptyList())

        // canonical key -> display name / variants / record ids
        data class Acc(
            var display: String = "",
            var kind: String = "",
            val variants: MutableSet<String> = LinkedHashSet(),
            val ids: MutableSet<String> = LinkedHashSet()
        )
        val acc = LinkedHashMap<String, Acc>()
        for (row in rows) {
            if (row.isDeleted || row.isLocked) continue
            // 2026-11-21：显示与匹配都用归一后的名字（去包裹引号/末尾句读），
            // 库里存的原样不动，只在读时统一。
            val raw = normalizeAnchorName(row.entityName)
            if (raw.isEmpty()) continue
            // 2026-11-21：候选键全部试一遍（原名/去空格/括号内外），
            // 这样 `アリス（Alice）` 能命中别名组里的 `Alice`，不再多出一个锚。
            val canonical = entityLookupKeys(raw).firstNotNullOfOrNull { canonicalOf[it] }
            // 2026-11-21：分组只看名字（类型不同也合并，避免两行同名「TestConcept」）。
            val key = anchorGroupKey(raw, canonicalOf)
            val a = acc.getOrPut(key) { Acc(display = canonical ?: raw) }
            if (a.kind.isEmpty()) a.kind = row.kind.trim().uppercase()
            if (canonical == null && a.display.isEmpty()) a.display = raw
            a.variants.add(raw)
            a.ids.add(row.recordId)
        }
        if (acc.isEmpty()) return emptyList()

        val all = recordRepository.getRecordsByIds(acc.values.flatMap { it.ids }.toList())
            .filter { !it.isDeleted && !it.isLocked }
            .associateBy { it.id }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)

        val rels = runCatching { database.recordRelationDao().all() }.getOrDefault(emptyList())
            .filter { it.validTo == null }

        val out = ArrayList<AnchorGroup>(acc.size)
        for ((_, a) in acc) {
            val ids = a.ids.filter { it in all }
            if (ids.isEmpty()) continue
            val records = ids.mapNotNull { all[it] }
                .sortedByDescending { it.createdAt }
                .map {
                    AnchorRecord(
                        id = it.id,
                        type = it.type,
                        date = fmt.format(java.util.Date(it.createdAt)),
                        snippet = it.content.replace(Regex("\\s+"), " ").trim().take(120)
                    )
                }
            val idSet = ids.toSet()
            // 同一对记录可能双向各有一条边（A→B、B→A），组里只显示一条：
            // 优先保留有 why/引文的那条，其次保留较新的。
            val edges = rels.filter { it.fromId in idSet && it.toId in idSet }
                .groupBy { if (it.fromId < it.toId) it.fromId + "|" + it.toId else it.toId + "|" + it.fromId }
                .values
                .map { candidates ->
                    candidates.sortedWith(
                        compareByDescending<memore.app.data.model.RecordRelation> { it.why.isNotBlank() || it.quoteA.isNotBlank() }
                            .thenByDescending { it.createdAt }
                    ).first()
                }
                .mapNotNull { r ->
                    val f = all[r.fromId] ?: return@mapNotNull null
                    val t = all[r.toId] ?: return@mapNotNull null
                    AnchorEdge(
                        fromId = r.fromId, toId = r.toId,
                        fromDate = fmt.format(java.util.Date(f.createdAt)),
                        toDate = fmt.format(java.util.Date(t.createdAt)),
                        type = r.type,
                        why = r.why,
                        quoteA = r.quoteA,
                        quoteB = r.quoteB,
                        // 2026-11-22：边不归某一个概念所有——把两端共享的概念都列出来，
                        // 界面就能显示「共享：测试甲 · testconcept」，而不是让人以为这条边只属于测试甲。
                        sharedConcepts = acc.entries
                            .filter { (_, x) -> r.fromId in x.ids && r.toId in x.ids }
                            .map { it.value.display }
                            .distinct()
                            .sorted()
                    )
                }
                .sortedByDescending { it.fromDate }
            out += AnchorGroup(
                name = a.display,
                variants = a.variants.toList().sortedBy { it.lowercase() },
                records = records,
                edges = edges,
                kind = a.kind,
                key = a.display.lowercase()
            )
        }
        return out.sortedWith(compareByDescending<AnchorGroup> { it.records.size }.thenByDescending { it.edges.size })
    }

    /**
     * 2026-11-22：两端**不共享任何概念**的边。
     *
     * 实测库里有 41% 的边属于这类（「同一种处境」而不是「同一个人」）。它们
     * 没法挂到任何概念下，在「按概念」页里会整体消失；单独拿出来，概念页才是诚实的。
     */
    suspend fun unanchoredEdges(): List<AnchorEdge> {
        val database = db ?: return emptyList()
        val aliasGroups = runCatching { aliasRepository.load() }.getOrDefault(emptyList())
        val canonicalOf = canonicalNames(aliasGroups)
        val rows = runCatching { database.recordEntityDao().allWithRecord() }.getOrDefault(emptyList())
        val conceptIds = HashMap<String, MutableSet<String>>()
        for (row in rows) {
            if (row.isDeleted || row.isLocked) continue
            val raw = normalizeAnchorName(row.entityName)
            if (raw.isEmpty()) continue
            val key = anchorGroupKey(raw, canonicalOf)
            conceptIds.getOrPut(key) { mutableSetOf() }.add(row.recordId)
        }
        val rels = runCatching { database.recordRelationDao().all() }.getOrDefault(emptyList())
            .filter { it.validTo == null && it.fromId != it.toId }
        val picked = rels
            .groupBy { if (it.fromId < it.toId) it.fromId + "|" + it.toId else it.toId + "|" + it.fromId }
            .values
            .map { c ->
                c.sortedWith(
                    compareByDescending<memore.app.data.model.RecordRelation> {
                        it.why.isNotBlank() || it.quoteA.isNotBlank()
                    }.thenByDescending { it.createdAt }
                ).first()
            }
            .filter { r -> conceptIds.values.none { r.fromId in it && r.toId in it } }
        if (picked.isEmpty()) return emptyList()
        val all = recordRepository.getRecordsByIds(picked.flatMap { listOf(it.fromId, it.toId) })
            .filter { !it.isDeleted && !it.isLocked }
            .associateBy { it.id }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
        return picked.mapNotNull { r ->
            val f = all[r.fromId] ?: return@mapNotNull null
            val t = all[r.toId] ?: return@mapNotNull null
            AnchorEdge(
                fromId = r.fromId, toId = r.toId,
                fromDate = fmt.format(java.util.Date(f.createdAt)),
                toDate = fmt.format(java.util.Date(t.createdAt)),
                type = r.type,
                why = r.why,
                quoteA = r.quoteA,
                quoteB = r.quoteB,
                sharedConcepts = emptyList()
            )
        }.sortedByDescending { it.fromDate }
    }

    /**
     * 清空全部锚点（2026-11-21，整库重算前用）：删 `record_entities`，别名映射不动。
     * 返回删掉的条数。
     */
    suspend fun clearAllAnchors(): Int {
        val dao = db?.recordEntityDao() ?: return 0
        val before = runCatching { kotlinx.coroutines.runBlocking { dao.count() } }.getOrDefault(0)
        runCatching { kotlinx.coroutines.runBlocking { dao.deleteAllNow() } }
        return before
    }

    /**
     * 2026-11-21：一个概念名归属于哪些实体行（含别名/标点变体）。
     * 纯读，给「删除概念」和 agent 快照共用。
     */
    suspend fun entitiesOfConcept(name: String): List<memore.app.data.model.RecordEntity> {
        val database = db ?: return emptyList()
        val aliasGroups = runCatching { aliasRepository.load() }.getOrDefault(emptyList())
        val canonicalOf = canonicalNames(aliasGroups)
        val target = anchorGroupKey(name, canonicalOf)
        val rows = runCatching { database.recordEntityDao().allRaw() }.getOrDefault(emptyList())
        return rows.filter { anchorGroupKey(it.name, canonicalOf) == target }
    }

    /**
     * 删除一个概念：只删 `record_entities` 里的锚点行，记录/关联/标签/来源全不动。
     * 返回删掉的行数。
     */
    suspend fun deleteConcept(name: String): Int {
        val database = db ?: return 0
        val rows = entitiesOfConcept(name)
        var n = 0
        rows.forEach { row ->
            runCatching {
                kotlinx.coroutines.runBlocking {
                    database.recordEntityDao().deleteOne(row.recordId, row.name, row.kind)
                }
            }.onSuccess { n++ }
        }
        return n
    }
}

/**
 * 别名归一（2026-11-19，2026-11-21 扩了写法拆分）。
 *
 * 输入别名组（概念/锚点，不含 emotion），输出 "小写写法 → 标准名"。
 * 纯函数，便于测试；同一别名出现在多个组时，先出现的组优先。
 *
 * 为什么还要拆写法：`アリス（Alice）` 这种「名字 + 括号注释」的写法不在别名组里时，
 * 只做 lowercase 永远对不上 `Alice`，界面上就会多出两个锚。这里把括号内外、
 * 去掉空白/间隔号之后的写法一起登记，能命中的都归到同一个标准名。
 */
internal fun canonicalNames(groups: List<AliasGroup>): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    for (g in groups) {
        if (g.kind == "emotion") continue
        val standard = g.standardName.trim()
        if (standard.isEmpty()) continue
        entityLookupKeys(standard).forEach { map.putIfAbsent(it, standard) }
        for (alias in g.aliases) {
            val a = alias.trim()
            if (a.isEmpty()) continue
            entityLookupKeys(a).forEach { map.putIfAbsent(it, standard) }
        }
    }
    return map
}

/**
 * 一个锚点名的所有候选查找键（都小写）：
 * 原名、去空白/间隔号、括号外的部分、括号内的部分。
 *
 * 例：`アリス（Alice）` → `アリス（Alice）`、`アリスAlice`、`アリス`、`Alice`
 */
internal fun entityLookupKeys(raw: String): List<String> {
    val s = normalizeAnchorName(raw).lowercase()
    if (s.isEmpty()) return emptyList()
    val out = LinkedHashSet<String>()
    out += s
    out += s.replace(Regex("[\\s·\\-]+"), "")
    val m = Regex("^(.*?)[（(](.*?)[）)]?$").find(s)
    if (m != null) {
        out += m.groupValues[1].trim()
        out += m.groupValues[2].trim()
    }
    return out.filter { it.isNotEmpty() }
}

/**
 * 锚点名归一（2026-11-21）：去掉**包住整个名字的**引号/括号，以及名字末尾的句读。
 *
 * 实测模型会吐 `「TestWork」`、`TestArtistB` 这类写法，同一个东西带不带标点
 * 会变成两个概念（映射表也对不上）。这里只处理两头，不碰名字内部的括号
 * （`测试词（testconcept）` 保持原样——它的括号是别名提示，不是包裹）。
 */
internal fun normalizeAnchorName(raw: String): String {
    var s = raw.trim()
    val pairs = listOf(
        "「" to "」", "『" to "』", "“" to "”", "\"" to "\"", "‘" to "’",
        "【" to "】", "（" to "）", "(" to ")", "[" to "]", "《" to "》"
    )
    var changed = true
    while (changed) {
        changed = false
        for ((open, close) in pairs) {
            if (s.length >= 2 && s.startsWith(open) && s.endsWith(close)) {
                s = s.substring(open.length, s.length - close.length).trim()
                changed = true
            }
        }
    }
    s = s.trimEnd('。', '．', '.', '！', '!', '？', '?', '、', '，', ',', '；', ';', '：', ':', '·', '…', ' ')
    return s.trim()
}

/**
 * 锚点噪音判定（2026-11-21）。
 *
 * 提示词里已经要求不要抽这些东西，但实测模型照样会吐；落库前再用代码挡一次：
 * 代词 / 纯数字 / 日期 / 长得像一整句话的 / 空白的，全部丢掉。
 */
internal fun isNoiseAnchor(raw: String): Boolean {
    val n = raw.trim()
    if (n.isEmpty()) return true
    if (n.length > 20) return true
    val pronouns = setOf(
        "我", "你", "您", "他", "她", "它", "我们", "你们", "他们", "她们", "它们",
        "大家", "自己", "别人", "有人", "某人"
    )
    if (n in pronouns) return true
    if (n.all { it.isDigit() || it.isWhitespace() }) return true
    // 2026年7月17日 / 2026-07-17 / 2026/7
    if (Regex("^\\d{4}\\s*[-年/.]\\s*\\d{1,2}(\\s*[-月/.]\\s*\\d{1,2}\\s*日?)?$").matches(n)) return true
    // 明显是一句话（带标点且偏长）
    if (n.length > 12 && n.any { it in "，。！？；、…" }) return true
    return false
}

/**
 * 锚点分组键（2026-11-21 第三版）：**只按概念名**，不再带类型。
 *
 * 之前按「名字 + 类型」分组，本意是防「テスト＝人名 / 作品」互相污染；实测结果是
 * 同一个 `testconcept` 被模型分别标成 PERSON 和 OTHER，视图里出现两行一模一样的「TestConcept」——
 * 用户判定这是 bug（按概念看本质是按名字聚合）。现在同名一律合并。
 */
internal fun anchorGroupKey(raw: String, canonicalOf: Map<String, String>): String {
    val canonical = entityLookupKeys(raw).firstNotNullOfOrNull { canonicalOf[it] } ?: raw
    return normalizeAnchorName(canonical).trim().lowercase()
}
