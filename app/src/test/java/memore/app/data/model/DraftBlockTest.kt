package memore.app.data.model

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import memore.app.data.local.AppDatabase
import memore.app.data.repository.DraftRepository
import memore.app.data.repository.pickDraftId
import memore.app.data.repository.shouldWaitForWanted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 真草稿（多本 + 定格块）的存储规则。
 *
 * 钉住的关键规则：
 * ① 一本里的顺序 = 插入顺序 → 屏幕上给 [DraftKind.MINE] 的编号稳定；
 * ② 编辑只改正文与"改"时间，不改顺序、不改类型；
 * ③ 空白不落库；草稿**独立于记录**（不往 records 里塞东西）；
 * ④ 送 AI 整理的输入 = MINE + 问答 + 讨论，**不含 AI 自己的产出**；
 * ⑤ **多本互不串**：块只出现在自己那本里；删一本连它的块一起删；归档只是收起来、不删。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DraftBlockTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DraftRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = DraftRepository(db.draftDao(), db.draftBlockDao())
    }

    @After
    fun tearDown() = db.close()

    private fun recordRowCount(): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM records").use { c ->
            c.moveToFirst(); c.getInt(0)
        }

    // ---- 一本之内 ----

    @Test
    fun blocksKeepInsertionOrder() = runBlocking {
        val d = repo.createDraft("我的草稿")
        repo.add(d, "第一块")
        repo.add(d, "第二块")
        repo.add(d, "第三块")
        assertEquals(listOf("第一块", "第二块", "第三块"), repo.observeBlocks(d).first().map { it.content })
    }

    @Test
    fun editingKeepsPositionAndStampsUpdatedAt() = runBlocking {
        val d = repo.createDraft("d")
        repo.add(d, "A")
        repo.add(d, "B")
        repo.add(d, "C")
        val middle = repo.observeBlocks(d).first()[1]
        assertNull("没改过 → 没有改动时间", middle.updatedAt)

        repo.edit(d, middle.id, "B 改了")

        val after = repo.observeBlocks(d).first()
        assertEquals("顺序不变（编号因此也不变）", listOf("A", "B 改了", "C"), after.map { it.content })
        assertEquals("类型不变", DraftKind.MINE, after[1].kind)
    }

    @Test
    fun blankContentIsNotStored() = runBlocking {
        val d = repo.createDraft("d")
        assertNull(repo.add(d, "   "))
        assertNull(repo.add(d, "\n\n"))
        assertEquals(0, repo.observeBlockCount(d).first())
        repo.add(d, "有效内容")
        val block = repo.observeBlocks(d).first().single()
        repo.edit(d, block.id, "   ")
        assertEquals("编辑成空白不落库（保留原文）", "有效内容", repo.observeBlocks(d).first().single().content)
    }

    @Test
    fun draftsNeverTouchRecordsTable() = runBlocking {
        assertEquals(0, recordRowCount())
        val d = repo.createDraft("d")
        repo.add(d, "草稿不该变成记录")
        assertEquals("写草稿不能往 records 里塞东西", 0, recordRowCount())
    }

    // ---- 多本 ----

    @Test
    fun blocksAreIsolatedPerDraft() = runBlocking {
        val a = repo.createDraft("A 本")
        val b = repo.createDraft("B 本")
        repo.add(a, "A 的块")
        repo.add(b, "B 的块")

        assertEquals(listOf("A 的块"), repo.observeBlocks(a).first().map { it.content })
        assertEquals(listOf("B 的块"), repo.observeBlocks(b).first().map { it.content })
        assertEquals(1, repo.observeBlockCount(a).first())
        assertEquals(2, repo.observeCount().first())
    }

    /**
     * 抽屉里对"某一本"发起讨论时，取块必须**精确到那一本**。
     *
     * 用户原话：「送过去的内容只有这一份草稿，精确一下」—— 屏幕上开着的是另一本，
     * 所以这条路走 [DraftRepository.blocksOf]（按 draftId 现取），不许拿屏幕上的那份。
     */
    @Test
    fun blocksOfReturnsExactlyThatDraft() = runBlocking {
        val a = repo.createDraft("A")
        val b = repo.createDraft("B")
        repo.add(a, "A 的一句")
        repo.add(b, "B 的一句")
        repo.add(b, "问：B 的问答\n答：……", kind = DraftKind.QA)

        assertEquals(listOf("A 的一句"), repo.blocksOf(a).map { it.content })
        assertEquals(listOf("B 的一句", "问：B 的问答\n答：……"), repo.blocksOf(b).map { it.content })
        assertTrue("A 的输入里不许出现 B 的字", !repo.buildAiInput(repo.blocksOf(a)).contains("B 的一句"))
    }

    @Test
    fun emptyNameFallsBackToPlaceholder() = runBlocking {
        val d = repo.createDraft("   ")
        assertEquals("未命名", repo.getDraft(d)?.name)
        repo.renameDraft(d, "起个名字")
        assertEquals("起个名字", repo.getDraft(d)?.name)
    }

    @Test
    fun archiveHidesFromActiveListButKeepsEverything() = runBlocking {
        val d = repo.createDraft("要归档的")
        repo.add(d, "内容")
        repo.setDraftArchived(d, true)

        assertTrue("归档后不在活列表里", repo.observeDrafts().first().none { it.id == d })
        assertEquals("但归档列表里有", 1, repo.observeArchivedDrafts().first().size)
        assertEquals("内容一个不删", 1, repo.observeBlockCount(d).first())

        repo.setDraftArchived(d, false)
        assertEquals(1, repo.observeDrafts().first().size)
    }

    @Test
    fun deletingDraftRemovesItsBlocksOnly() = runBlocking {
        val a = repo.createDraft("A")
        val b = repo.createDraft("B")
        repo.add(a, "A 的块")
        repo.add(b, "B 的块")

        repo.deleteDraft(a)

        assertTrue(repo.observeDrafts().first().none { it.id == a })
        assertEquals("只删了这一本的块", 1, repo.observeCount().first())
        assertEquals(listOf("B 的块"), repo.observeBlocks(b).first().map { it.content })
    }

    // ---- "谁进 AI" 的规则 ----

    /**
     * 三个功能（整理 / 质疑 / 讨论）送的是**同一份材料**：除 AI 产出外所有块按原顺序拼一段，
     * 作者自己的块编号，问答 / 讨论**不贴 kind 标签**（自己就带「问：/答：」「讨论：/回应：」）——
     * 用户 2026-10-14：「让 AI 判断哪些是正文哪些是讨论和质疑就行了，不然讨论和质疑给的内容容易重叠」。
     */
    @Test
    fun aiInputMergesEverythingButAiOutputWithoutKindLabels() = runBlocking {
        val d = repo.createDraft("d")
        repo.add(d, "我写的第一句")
        repo.add(d, "AI 整理出来的东西", kind = DraftKind.AI)
        repo.add(d, "问：这句什么意思？\n答：意思是A", kind = DraftKind.QA)
        repo.add(d, "建议：开头换一下\n回应：采纳", kind = DraftKind.DISCUSS)
        repo.add(d, "我写的第二句")
        val input = repo.buildAiInput(repo.observeBlocks(d).first())

        assertTrue("作者自己的块要被编号", input.contains("1. 我写的第一句"))
        assertTrue("编号连续（AI 块不占号）", input.contains("2. 我写的第二句"))
        assertTrue("问答的内容要在（原样，不贴标签）", input.contains("问：这句什么意思？\n答：意思是A"))
        assertTrue("讨论的内容要在（原样，不贴标签）", input.contains("建议：开头换一下\n回应：采纳"))
        assertFalse("不该再贴 【问答】 标签（内容自己就说明了）", input.contains("【问答】"))
        assertFalse("不该再贴 【讨论】 标签", input.contains("【讨论】"))
        assertTrue("AI 的产出不能再送回去（否则反复回炉、还和正文重复）", !input.contains("AI 整理出来的东西"))
        assertEquals("顺序 = 块顺序", 4, input.split("\n\n").size)
    }

    @Test
    fun aiInputIsBlankWhenOnlyAiBlocksExist() = runBlocking {
        val d = repo.createDraft("d")
        repo.add(d, "只有 AI 写的", kind = DraftKind.AI)
        assertEquals("", repo.buildAiInput(repo.observeBlocks(d).first()))
    }

    // ---- 进来的那一本还没出现在列表里时要等（用户 2026-10-14 报的"进到第一个草稿"）----

    @Test
    fun waitForWantedWhileItIsNotInTheListYet() {
        assertTrue(
            "刚新建/刚导入：库里在、列表还没刷出来 → 等，别挑别本",
            shouldWaitForWanted(wanted = 9, inList = false, existsInDb = true, resolved = false)
        )
        assertFalse(
            "已经认下来了 → 之后各次列表刷新都不再等（用户切走也不许被拽回来）",
            shouldWaitForWanted(wanted = 9, inList = false, existsInDb = true, resolved = true)
        )
        assertFalse(
            "列表里有 → 不用等，直接认",
            shouldWaitForWanted(wanted = 9, inList = true, existsInDb = true, resolved = false)
        )
        assertFalse(
            "库里也没了（那本被删了）→ 才允许兜底",
            shouldWaitForWanted(wanted = 9, inList = false, existsInDb = false, resolved = false)
        )
        assertFalse(
            "没指定哪一本（≤0 / null）→ 不适用",
            shouldWaitForWanted(wanted = null, inList = false, existsInDb = false, resolved = false)
        )
    }

    // ---- 该打开哪一本（用户报过"每次进入都新建一个未命名"）----

    /**
     * 回归：**只有列表确实为空时才该新建**。
     * 当时的 bug 是把 Flow 首帧的空列表当成了"一本都没有"→ 每次进页面都建一本 ✗。
     * 这里把这条不变量钉死：只要列表非空，就一定能选出某本、绝不返回 null（= 不会新建）。
     */
    @Test
    fun pickDraftIdOnlyReturnsNullWhenListIsTrulyEmpty() {
        val a = Draft(id = 3, name = "a")
        val b = Draft(id = 5, name = "b")
        val loaded = listOf(a, b)

        assertEquals(3L, pickDraftId(wanted = 5, loaded = loaded, current = 3))   // 当前优先
        assertEquals(5L, pickDraftId(wanted = null, loaded = loaded, current = 5)) // 当前还在 → 留当前
        assertEquals(5L, pickDraftId(wanted = 99, loaded = loaded, current = 5))   // 指定不存在 → 留当前
        assertEquals(3L, pickDraftId(wanted = 99, loaded = loaded, current = null)) // 都没定 → 最近一本
        assertNull("列表为空才是「需要新建」", pickDraftId(wanted = null, loaded = emptyList(), current = null))

        assertEquals("非空列表 + 任何入参都必须选出某本（绝不新建）", 4, listOf(
            pickDraftId(5, loaded, 3), pickDraftId(null, loaded, 5),
            pickDraftId(99, loaded, 5), pickDraftId(99, loaded, null)
        ).count { it != null })
    }

    /**
     * 回归：**列表一刷新，不许把用户从正在写的那一本上拽回去**（用户 2026-10-11：
     * 「这种导航问题应该不少」）。
     *
     * 场景：从归档那一本进来（或者刚做完问答，pendingDraftId 指着它），在抽屉里切到别本开始写；
     * 写一块就会 `touch` 草稿 → 活动列表重新发射 → 页面重新解析"该开哪一本"。
     * 老规则里"进来时那一本优先"会在这一刻胜出 ✗，人就被拽回去了。
     */
    @Test
    fun pickDraftIdNeverYanksUserAwayFromCurrentDraft() {
        val entered = Draft(id = 5, name = "进来时那本")
        val switched = Draft(id = 3, name = "抽屉里切过去那本")
        val loaded = listOf(entered, switched)

        assertEquals(
            "当前这本还在 → 就算它是从别处切过来的，也必须留在它上面",
            3L, pickDraftId(wanted = 5, loaded = loaded, current = 3)
        )
        assertEquals(
            "当前那本被删了 → 才回落到进来的那一本",
            5L, pickDraftId(wanted = 5, loaded = listOf(entered), current = 3)
        )
    }

    /**
     * 回归：**指定/当前的草稿是归档的，也必须留在它上面**。
     *
     * 用户 2026-10-11 报的 bug：「在一个归档的草稿里使用功能，结束问答之后跳进了最新的草稿」——
     * 根因是归档的那几本不在活动列表里，被当成"不存在"，于是掉进兜底分支（挑最近一本）。
     */
    @Test
    fun pickDraftIdKeepsArchivedDraft() {
        val archivedOne = Draft(id = 7, name = "归档的", archived = true)
        val active = listOf(Draft(id = 3, name = "a"), Draft(id = 5, name = "b"))
        val archived = listOf(archivedOne)

        assertEquals(
            "指定了归档那一本 → 就留在它上面，不许跳到最新一本",
            7L, pickDraftId(wanted = 7, loaded = active, current = null, archived = archived)
        )
        assertEquals(
            "当前是归档那一本 → 重进页面还是它",
            7L, pickDraftId(wanted = null, loaded = active, current = 7, archived = archived)
        )
        assertEquals(
            "连活动列表都空了，只剩归档的 → 也应该开它，而不是新建",
            7L, pickDraftId(wanted = null, loaded = emptyList(), current = null, archived = archived)
        )
        assertEquals(
            "指定的和当前的都不存在 → 才回落到活动列表最近一本",
            3L, pickDraftId(wanted = 99, loaded = active, current = null, archived = archived)
        )
        assertNull(
            "两边都空才是「需要新建」",
            pickDraftId(wanted = 7, loaded = emptyList(), current = null, archived = emptyList())
        )
    }

    @Test
    fun pinnedDraftsComeFirst() = runBlocking {
        val older = repo.createDraft("老的")
        val newer = repo.createDraft("新的")
        assertEquals("默认按最后动过排序", newer, repo.observeDrafts().first().first().id)

        repo.setDraftPinned(older, true)
        assertEquals("置顶的排最前", older, repo.observeDrafts().first().first().id)
        assertTrue(repo.observeDrafts().first().first().pinned)
    }

    // ---- 空草稿不许堆（用户：新建后没输入就退出，那就删了）----

    @Test
    fun deleteIfEmptyRemovesOnlyEmptyDrafts() = runBlocking {
        val empty = repo.createDraft("空的")
        val used = repo.createDraft("有内容")
        repo.add(used, "一块")

        repo.deleteIfEmpty(empty)
        repo.deleteIfEmpty(used)

        assertEquals("空的被删、有内容的不动", listOf(used), repo.observeDrafts().first().map { it.id })
    }

    @Test
    fun pruneEmptyDraftsKeepsTheCurrentOne() = runBlocking {
        val a = repo.createDraft("空A")
        val b = repo.createDraft("空B")
        val c = repo.createDraft("有内容")
        repo.add(c, "一块")

        // 保留 a（当前正打开的这本），其余空的清掉
        repo.pruneEmptyDrafts(a)
        assertEquals(setOf(a, c), repo.observeDrafts().first().map { it.id }.toSet())

        // keepId = null：所有空的一起清
        repo.createDraft("空C")
        repo.pruneEmptyDrafts(null)
        assertEquals(listOf(c), repo.observeDrafts().first().map { it.id })
    }
    // ---- 备份还原（导出/导入用） ----

    /**
     * `importDraft`：**id 全部重建**，但名字 / 时间 / 归档 / 置顶 / 块的 kind 与顺序原样写回。
     * 时间必须原样 —— 否则抽屉里「今天 / 昨天 / 7 天内 / 更早」的分组会全乱。
     */
    @Test
    fun importDraftRebuildsIdsButKeepsEverythingElse() = runBlocking {
        val newId = repo.importDraft(
            draft = Draft(name = "还原的", createdAt = 1000L, updatedAt = 2000L, archived = true, pinned = true),
            blocks = listOf(
                DraftBlock(content = "第一块", createdAt = 1000L, kind = DraftKind.MINE),
                DraftBlock(content = "问：A？\n答：B", createdAt = 1500L, updatedAt = 1600L, kind = DraftKind.QA),
                DraftBlock(content = "   ", createdAt = 1700L, kind = DraftKind.MINE)   // 空白块不落库
            )
        )!!

        val drafted = repo.getDraft(newId)!!
        assertEquals("名字原样", "还原的", drafted.name)
        assertEquals("创建时间原样", 1000L, drafted.createdAt)
        assertEquals("最后动过时间原样", 2000L, drafted.updatedAt)
        assertTrue("归档状态要带上", drafted.archived)
        assertTrue("置顶状态要带上", drafted.pinned)

        val blocks = repo.observeBlocks(newId).first()
        assertEquals("空白块不要", 2, blocks.size)
        assertEquals(listOf("第一块", "问：A？\n答：B"), blocks.map { it.content })
        assertEquals("kind 要还原", listOf(DraftKind.MINE, DraftKind.QA), blocks.map { it.kind })
        assertEquals("块的创建时间原样", 1000L, blocks[0].createdAt)
        assertEquals("改过的时间原样", 1600L, blocks[1].updatedAt)
        assertTrue("块要挂在新本上", blocks.all { it.draftId == newId })
    }

    @Test
    fun importDraftSkipsEmptyDraft() = runBlocking {
        assertNull("一个块都没有的本不还原", repo.importDraft(Draft(name = "空的"), emptyList()))
        assertNull(repo.importDraft(Draft(name = "全空白"), listOf(DraftBlock(content = "  "))))
        assertEquals(0, repo.observeDrafts().first().size)
    }

    /** 还原出来的本要能正常用：继续写块、按更新时间排进列表。 */
    @Test
    fun importedDraftIsAFullyUsableDraft() = runBlocking {
        val id = repo.importDraft(
            draft = Draft(name = "还原的", createdAt = 1000L, updatedAt = 2000L),
            blocks = listOf(DraftBlock(content = "原有的一块", createdAt = 1000L))
        )!!
        repo.add(id, "新写的一块")
        assertEquals(listOf("原有的一块", "新写的一块"), repo.observeBlocks(id).first().map { it.content })
        assertEquals(listOf(id), repo.observeDrafts().first().map { it.id })
        assertTrue("还能置顶（列存在且默认可用）", !repo.observeDrafts().first().first().pinned)
    }

    // ---- 迁移 ----

    /** 迁移 42→43：drafts 加 pinned 列，旧数据默认不置顶。 */
    @Test
    fun migrationAddsPinnedColumn() = runBlocking {
        val wdb = db.openHelper.writableDatabase
        wdb.execSQL("DROP TABLE IF EXISTS drafts")
        wdb.execSQL(
            "CREATE TABLE drafts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, archived INTEGER NOT NULL DEFAULT 0)"
        )
        wdb.execSQL("INSERT INTO drafts (name, created_at, updated_at, archived) VALUES ('老本', 1000, 1000, 0)")

        AppDatabase.MIGRATION_42_43.migrate(wdb)

        val all = repo.observeDrafts().first()
        assertEquals(1, all.size)
        assertFalse("旧数据默认不置顶", all[0].pinned)
        repo.setDraftPinned(all[0].id, true)
        assertTrue(repo.observeDrafts().first()[0].pinned)
    }

    /** 从 38 升上来的老库会连跑 38→39→40→41→42→43。 */
    @Test
    fun migrationsBringOldInstallUpToDate() = runBlocking {
        val wdb = db.openHelper.writableDatabase
        wdb.execSQL("DROP TABLE IF EXISTS draft_blocks")
        wdb.execSQL("DROP TABLE IF EXISTS drafts")
        AppDatabase.MIGRATION_38_39.migrate(wdb)
        AppDatabase.MIGRATION_39_40.migrate(wdb)
        AppDatabase.MIGRATION_40_41.migrate(wdb)
        AppDatabase.MIGRATION_41_42.migrate(wdb)
        AppDatabase.MIGRATION_42_43.migrate(wdb)
        AppDatabase.MIGRATION_43_44.migrate(wdb)

        val d = repo.createDraft("新的")
        repo.add(d, "迁移后写入")
        assertEquals(listOf("迁移后写入"), repo.observeBlocks(d).first().map { it.content })
    }

    /**
     * 迁移 43→44：records 加**转录来源**两列（可空）。
     * 旧记录一律 NULL = 不是转录来的 → 卡片上不出现"回到草稿"那个图标。
     *
     * 为了真的模拟"43 版的表"，这里把实体建出来的 DDL 里那两列**抠掉**再建表
     * （内存库是按当前实体建的，直接用会一上来就有列，"迁移前"这一步就假了）。
     */
    @Test
    fun migrationAddsTranscribeSourceColumns() = runBlocking {
        val wdb = db.openHelper.writableDatabase
        val ddl = wdb.query("SELECT sql FROM sqlite_master WHERE type='table' AND name='records'").use { c ->
            c.moveToFirst(); c.getString(0)
        }
        val oldDdl = ddl
            .replace(Regex(",\\s*`?source_draft_id`?[^,)]*"), "")
            .replace(Regex(",\\s*`?source_block_id`?[^,)]*"), "")
        assertTrue("抠列失败，测试前提不成立", !oldDdl.contains("source_"))
        wdb.execSQL("DROP TABLE records")
        wdb.execSQL(oldDdl)
        // 插一条"老记录"：只填 NOT NULL 的列（其余列留 NULL 或空串），照实体建的表的约束来
        val notNull = wdb.query("PRAGMA table_info(records)").use { c ->
            val nameIdx = c.getColumnIndex("name"); val typeIdx = c.getColumnIndex("type"); val nnIdx = c.getColumnIndex("notnull")
            buildList {
                while (c.moveToNext()) {
                    if (c.getInt(nnIdx) == 1) add(c.getString(nameIdx) to c.getString(typeIdx).orEmpty())
                }
            }
        }
        val names = notNull.joinToString(", ") { it.first }
        val values = notNull.joinToString(", ") { (n, t) ->
            when {
                n == "id" -> "'r-old'"
                n == "type" -> "'ESSAY'"
                n == "content" -> "'老记录'"
                n == "created_at" || n == "updated_at" -> "1000"
                t.equals("TEXT", ignoreCase = true) -> "''"
                else -> "0"
            }
        }
        wdb.execSQL("INSERT INTO records ($names) VALUES ($values)")

        AppDatabase.MIGRATION_43_44.migrate(wdb)
        AppDatabase.MIGRATION_43_44.migrate(wdb)   // 幂等：重跑不炸

        val cols = wdb.query("PRAGMA table_info(records)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            val out = mutableSetOf<String>()
            while (c.moveToNext()) if (nameIdx >= 0) out.add(c.getString(nameIdx))
            out
        }
        assertTrue("source_draft_id", "source_draft_id" in cols)
        assertTrue("source_block_id", "source_block_id" in cols)

        assertNull("旧记录不是转录来的（列刚加上，值为 NULL）", db.recordDao().getRecordById("r-old")!!.sourceDraftId)

        // 转录出来的记录：值存得住
        db.recordDao().insertRecord(Record(id = "r-new", type = RecordType.ESSAY, content = "转录来的", sourceDraftId = 9L, sourceBlockId = 51L))
        val back = db.recordDao().getRecordById("r-new")!!
        assertEquals(9L, back.sourceDraftId)
        assertEquals(51L, back.sourceBlockId)
        assertEquals("老记录还在", "老记录", db.recordDao().getRecordById("r-old")!!.content)

        // ★ 关键一步：迁移后的表形状必须与 Room 按实体期望的形状**完全一致**，
        //   否则真机上迁移完 Room 校验不过 → 一开 App 就崩。
        fun tableInfo(database: androidx.sqlite.db.SupportSQLiteDatabase, table: String) =
            database.query("PRAGMA table_info($table)").use { c ->
                val n = c.getColumnIndex("name"); val t = c.getColumnIndex("type")
                val nn = c.getColumnIndex("notnull"); val pk = c.getColumnIndex("pk")
                buildList {
                    while (c.moveToNext()) add("${c.getString(n)}|${c.getString(t)}|${c.getInt(nn)}|${c.getInt(pk)}")
                }.sorted()
            }
        val expected = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val expectedInfo = tableInfo(expected.openHelper.readableDatabase, "records")
        expected.close()
        assertEquals("迁移出来的 records 表必须与实体完全一致", expectedInfo, tableInfo(wdb, "records"))
    }

    /**
     * 迁移 41→42：老库没有 drafts 表、draft_blocks 没有 draft_id。
     * 迁移后：建出 id=1 的那本老草稿（名字留空给用户自己起），老块全部挂到它下面。
     */
    @Test
    fun migrationCreatesFirstDraftAndAttachesOldBlocks() = runBlocking {
        val wdb = db.openHelper.writableDatabase
        wdb.execSQL("DROP TABLE IF EXISTS drafts")
        wdb.execSQL("DROP TABLE IF EXISTS draft_blocks")
        wdb.execSQL(
            "CREATE TABLE draft_blocks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "content TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER, " +
                "kind TEXT NOT NULL DEFAULT 'MINE')"
        )
        wdb.execSQL("INSERT INTO draft_blocks (content, created_at, updated_at, kind) VALUES ('老块一', 1000, NULL, 'MINE')")
        wdb.execSQL("INSERT INTO draft_blocks (content, created_at, updated_at, kind) VALUES ('老 AI 产出', 2000, NULL, 'AI')")

        AppDatabase.MIGRATION_41_42.migrate(wdb)
        // 真机上从 41 升上来还会接着跑 42→43（置顶列）——测试照真机路径连跑
        AppDatabase.MIGRATION_42_43.migrate(wdb)

        assertEquals("建出一本老草稿", 1, repo.observeDrafts().first().size)
        val first = repo.observeDrafts().first().first()
        assertEquals("名字留空，等用户自己起", "", first.name)
        assertFalse("默认不归档", first.archived)
        val blocks = repo.observeBlocks(first.id).first()
        assertEquals(2, blocks.size)
        assertEquals(listOf("老块一", "老 AI 产出"), blocks.map { it.content })
        assertEquals("类型也保住了", listOf(DraftKind.MINE, DraftKind.AI), blocks.map { it.kind })
    }
}
