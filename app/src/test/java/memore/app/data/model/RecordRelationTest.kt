package memore.app.data.model

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import memore.app.data.local.AppDatabase
import memore.app.data.repository.RecordRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 记录之间的**有向带类型**关系。
 *
 * 这里钉住三件事：
 * ① 老数据（对称的 `linked_ids`）迁移成「更正」边时，**方向必须是"新的 → 老的"**；
 * ② 正反查各自独立、重复建立去重；
 * ③ 不允许产生**悬空边**（端点不存在的边一律拒绝）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordRelationTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: RecordRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // 注意这里传了 db —— 关系 API 依赖它
        repo = RecordRepository(
            recordDao = db.recordDao(),
            versionDao = db.versionDao(),
            attachmentDao = db.attachmentDao(),
            taskDao = db.aiTaskDao(),
            db = db
        )
    }

    @After
    fun tearDown() { db.close() }

    /** ① 迁移：老的对称 linked_ids → 「更正」边，方向 = 新记录更正老记录。 */
    @Test
    fun migration_convertsLegacyLinkedIds_intoCorrectEdge_newerToOlder() = runBlocking {
        db.recordDao().insertRecord(Record(id = "old-rec", type = RecordType.ESSAY, content = "老记录", createdAt = 1_000L, updatedAt = 1_000L))
        // 老的对称字段：两边互相包含
        db.recordDao().insertRecord(
            Record(id = "new-rec", type = RecordType.ESSAY, content = "新记录", createdAt = 2_000L, updatedAt = 2_000L,
                linkedIds = "[\"old-rec\"]")
        )
        db.recordDao().updateLinkedIds("old-rec", "[\"new-rec\"]")

        // 表已由 Room 建好，这里验的是迁移里那段转换 SQL 的**规则**（真实迁移跑的是同一段）
        AppDatabase.MIGRATION_34_35.migrate(db.openHelper.writableDatabase)

        val rels = db.recordRelationDao().all()
        assertEquals("双向的 linked_ids 只该产出**一条**边（去重）", 1, rels.size)
        assertEquals("from 应当是更新的那条", "new-rec", rels[0].fromId)
        assertEquals("to 应当是更老的那条", "old-rec", rels[0].toId)
        assertEquals(RecordRelationType.CORRECT, rels[0].type)
    }

    /** ② 正反查各自独立；同一条边重复建立不会变成两条。 */
    @Test
    fun relations_areDirected_andDeduplicated() = runBlocking {
        db.recordDao().insertRecord(Record(id = "a", type = RecordType.ESSAY, content = "A", createdAt = 1L, updatedAt = 1L))
        db.recordDao().insertRecord(Record(id = "b", type = RecordType.ESSAY, content = "B", createdAt = 2L, updatedAt = 2L))

        assertTrue(repo.addRelation("b", "a", RecordRelationType.CORRECT))
        assertTrue("重复建立同一条边应当被静默接受但不重复存", repo.addRelation("b", "a", RecordRelationType.CORRECT))

        assertEquals(1, repo.relationsFrom("b").size)   // b 更正了 a
        assertEquals("反查：a 这一侧应当没有出向边", 0, repo.relationsFrom("a").size)
        assertEquals(1, repo.relationsTo("a").size)     // a 被 b 更正
        assertEquals(0, repo.relationsTo("b").size)
        assertEquals(1, db.recordRelationDao().count())

        // 反向那条是独立的边，不因为对称而复现
        assertTrue(repo.addRelation("a", "b", RecordRelationType.CITE))
        assertEquals(2, db.recordRelationDao().count())
    }

    /** ③ 端点不存在的边一律拒绝 —— 不留悬空边。 */
    @Test
    fun addRelation_rejectsMissingEndpoints_andSelfLink() = runBlocking {
        db.recordDao().insertRecord(Record(id = "a", type = RecordType.ESSAY, content = "A", createdAt = 1L, updatedAt = 1L))

        assertFalse("指向不存在的记录必须拒绝", repo.addRelation("a", "ghost", RecordRelationType.CITE))
        assertFalse("指向方不存在也必须拒绝", repo.addRelation("ghost", "a", RecordRelationType.CITE))
        assertFalse("不能关联自己", repo.addRelation("a", "a", RecordRelationType.CITE))
        assertEquals(0, db.recordRelationDao().count())
    }

    /** 导出格式：一条边**同时**出现在两端的记录上 —— 关联(out) 与 被关联(in)。 */
    @Test
    fun inlineRelationMap_carriesBothDirections() {
        val rels = listOf(
            RecordRelation(fromId = "B", toId = "A", type = RecordRelationType.CORRECT),
            RecordRelation(fromId = "A", toId = "C", type = RecordRelationType.CITE)
        )
        val map = memore.app.utils.ExportEngine.buildRelationMap(rels, allowIds = null)

        // A：自己引用了 C（关联），同时被 B 更正（被关联）
        assertEquals(listOf(memore.app.utils.ExportedRelationOut("C", "CITE")), map["A"]!!.first)
        assertEquals(listOf(memore.app.utils.ExportedRelationIn("B", "CORRECT")), map["A"]!!.second)
        // B：更正了 A
        assertEquals(listOf(memore.app.utils.ExportedRelationOut("A", "CORRECT")), map["B"]!!.first)
        // C：被 A 引用
        assertEquals(listOf(memore.app.utils.ExportedRelationIn("A", "CITE")), map["C"]!!.second)
    }

    /** 普通导出只带两端都在范围内的边 —— 不给读数据的 AI 留悬空引用。 */
    @Test
    fun inlineRelationMap_filteredWhenScoped() {
        val rels = listOf(RecordRelation(fromId = "B", toId = "A", type = RecordRelationType.CORRECT))
        assertTrue(
            "只导出 A 时，B→A 这条边应当整条不带（否则 to/from 会指向文件外的记录）",
            memore.app.utils.ExportEngine.buildRelationMap(rels, allowIds = setOf("A")).isEmpty()
        )
        assertTrue(
            "不过滤时应当带上",
            memore.app.utils.ExportEngine.buildRelationMap(rels, allowIds = null).isNotEmpty()
        )
    }

    /**
     * **单边**老链接（只有老的那条写着对方、新的那条**没有回写**）也必须转出来。
     *
     * 早先的迁移只在"链接持有方"那一侧建边，于是这种整对丢掉 —— 真机上就漏了 5 对。
     * 这条测试正是补那个洞的（写成"任一侧持有链接就成对，方向再由时间定"之后才过）。
     */
    @Test
    fun migration_handlesOneSidedLegacyLink() = runBlocking {
        db.recordDao().insertRecord(
            Record(id = "old-rec", type = RecordType.ESSAY, content = "老记录", createdAt = 1_000L, updatedAt = 1_000L,
                linkedIds = "[\"new-rec\"]")
        )
        // 新的那条没有回写 —— 这就是单边
        db.recordDao().insertRecord(
            Record(id = "new-rec", type = RecordType.ESSAY, content = "新记录", createdAt = 2_000L, updatedAt = 2_000L)
        )

        AppDatabase.MIGRATION_34_35.migrate(db.openHelper.writableDatabase)

        val rels = db.recordRelationDao().all()
        assertEquals("单边链接也必须成一条边", 1, rels.size)
        assertEquals("方向仍然是 新的 → 老的", "new-rec", rels[0].fromId)
        assertEquals("old-rec", rels[0].toId)
    }

    /** 35→36 那次数据修补是**幂等**的：跑两遍不会多出边（已升到 35 的设备靠它自愈）。 */
    @Test
    fun migration35to36_isIdempotent() = runBlocking {
        db.recordDao().insertRecord(Record(id = "a2", type = RecordType.ESSAY, content = "A", createdAt = 1L, updatedAt = 1L))
        db.recordDao().insertRecord(
            Record(id = "b2", type = RecordType.ESSAY, content = "B", createdAt = 2L, updatedAt = 2L, linkedIds = "[\"a2\"]")
        )
        AppDatabase.MIGRATION_35_36.migrate(db.openHelper.writableDatabase)
        AppDatabase.MIGRATION_35_36.migrate(db.openHelper.writableDatabase)
        assertEquals(1, db.recordRelationDao().count())
    }

    /**
     * 增修相关的边必须是「**补充**」，方向是「增修 → 原记录」。
     * 34→35 / 35→36 那两次转换把老链接一律写成了「更正」，这条钉住 36→37 的归位。
     */
    @Test
    fun amendmentEdges_areSupplement_notCorrect() = runBlocking {
        db.recordDao().insertRecord(Record(id = "parent", type = RecordType.ESSAY, content = "原文", createdAt = 1_000L, updatedAt = 1_000L))
        db.recordDao().insertRecord(
            Record(id = "amend", type = RecordType.AMENDMENT, content = "增修", createdAt = 2_000L, updatedAt = 2_000L, correctionOfId = "parent")
        )
        // 模拟 34→35 的产物：一律写成「更正」
        db.recordRelationDao().insert(RecordRelation(fromId = "amend", toId = "parent", type = RecordRelationType.CORRECT))

        AppDatabase.MIGRATION_36_37.migrate(db.openHelper.writableDatabase)

        val rels = db.recordRelationDao().all()
        assertEquals(1, rels.size)
        assertEquals("方向应当是 增修 → 原记录", "amend", rels[0].fromId)
        assertEquals("parent", rels[0].toId)
        assertEquals("增修是在原文上追加，关系应当是「补充」", RecordRelationType.SUPPLEMENT, rels[0].type)

        // 幂等：再跑一遍不多出边
        AppDatabase.MIGRATION_36_37.migrate(db.openHelper.writableDatabase)
        assertEquals(1, db.recordRelationDao().count())
    }

    /** 原记录已被永久删除的增修，**不该**建边（否则留下悬空边 —— 真机上因此冒出 16 条）。 */
    @Test
    fun amendmentWithDeletedParent_producesNoEdge() = runBlocking {
        db.recordDao().insertRecord(
            Record(id = "amend-lost", type = RecordType.AMENDMENT, content = "增修", createdAt = 2_000L, updatedAt = 2_000L,
                correctionOfId = "已经不存在了")
        )

        AppDatabase.MIGRATION_36_37.migrate(db.openHelper.writableDatabase)

        assertEquals("原记录不存在 → 不该建边", 0, db.recordRelationDao().count())
    }

    /** 37→38：悬空边统一收口清掉，幂等。 */
    @Test
    fun danglingEdges_areCleanedUp() = runBlocking {
        db.recordDao().insertRecord(Record(id = "a3", type = RecordType.ESSAY, content = "A", createdAt = 1L, updatedAt = 1L))
        // 手工塞一条指向不存在记录的边（模拟 36→37 第一版的产物）
        db.recordRelationDao().insert(RecordRelation(fromId = "a3", toId = "ghost", type = RecordRelationType.CITE))

        AppDatabase.MIGRATION_37_38.migrate(db.openHelper.writableDatabase)

        assertEquals("悬空边应当被清掉", 0, db.recordRelationDao().count())
        // 幂等
        AppDatabase.MIGRATION_37_38.migrate(db.openHelper.writableDatabase)
        assertEquals(0, db.recordRelationDao().count())
    }

    /** 记录被永久删除时，相关边一起清掉（否则反查会撞到不存在的记录）。 */
    @Test
    fun permanentlyDelete_removesItsEdges() = runBlocking {
        db.recordDao().insertRecord(Record(id = "a", type = RecordType.ESSAY, content = "A", createdAt = 1L, updatedAt = 1L))
        db.recordDao().insertRecord(Record(id = "b", type = RecordType.ESSAY, content = "B", createdAt = 2L, updatedAt = 2L))
        assertTrue(repo.addRelation("b", "a", RecordRelationType.CORRECT))

        repo.permanentlyDeleteRecord("a")
        assertEquals("删掉端点后不该留下悬空边", 0, db.recordRelationDao().count())
        assertEquals(0, repo.relationsTo("a").size)
    }
}
