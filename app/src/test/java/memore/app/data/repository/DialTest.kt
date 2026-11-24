package memore.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import memore.app.data.local.AppDatabase
import memore.app.data.model.Dial
import memore.app.data.model.Record
import memore.app.data.model.RecordType
import memore.app.data.model.Tick
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 表盘 = **一段时期的框**（包络刻度，多父）。它是独立的一层：
 * 不改刻度的任何语义（不写 `ticks` 表、不参与记录归属、没有权重），也没有定格 / 切片。
 *
 * 钉住这几条：
 * ① 删表盘只删框：刻度与记录一个不动；
 * ② 一个刻度可以被多个表盘框住；框里的刻度按**起点新的在前**；
 * ③ 后记留一版；
 * ④ A1 分段：按框里的刻度分节 + 末尾「未归属」，重叠刻度"起点更晚者优先"、一条只出现一次；
 * ⑤ 迁移 46→47→48：建表 / 删掉定格两列且**数据不丢**，最终形状与实体一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DialTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DialRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = DialRepository(db.dialDao(), db.tickDao(), db.recordDao())
    }

    @After
    fun tearDown() = db.close()

    private fun record(id: String, at: Long): Record =
        Record(id = id, type = RecordType.ESSAY, content = "内容$id", createdAt = at, updatedAt = at)

    private fun tick(id: String, name: String, start: Long, end: Long?): Tick =
        Tick(id = id, name = name, startMs = start, endMs = end, createdAt = start, updatedAt = start)

    private suspend fun seedTick(t: Tick) = db.tickDao().insert(t)

    // ---- 基本 ----

    @Test
    fun createDialAndLinkTicks() = runBlocking {
        seedTick(tick("t1", "九月", 1000L, 2000L))
        seedTick(tick("t2", "十月", 2000L, 3000L))
        val dial = repo.createDial("今年这十一个月", 1000L, null)

        repo.linkTick(dial.id, "t1")
        repo.linkTick(dial.id, "t2")
        repo.linkTick(dial.id, "t2")   // 幂等

        assertEquals("框里的刻度按起点新的在前", listOf("t2", "t1"), repo.ticksOfDial(dial.id).map { it.id })
        assertEquals(setOf(dial.id), repo.dialIdsByTick()["t2"])
        assertNull("进行中 = 没有终点", repo.getDial(dial.id)!!.endMs)
    }

    @Test
    fun oneTickCanBelongToTwoDials() = runBlocking {
        seedTick(tick("t1", "九月", 1000L, 2000L))
        val year = repo.createDial("今年", 1000L, null)
        val exam = repo.createDial("考研", 1500L, 2500L)

        repo.linkTick(year.id, "t1")
        repo.linkTick(exam.id, "t1")

        assertEquals("同一个刻度被两个时期同时看见", setOf(year.id, exam.id), repo.dialIdsByTick()["t1"])
        assertEquals(1, repo.ticksOfDial(year.id).size)
        assertEquals(1, repo.ticksOfDial(exam.id).size)
    }

    @Test
    fun deleteDialKeepsTicksAndRecords() = runBlocking {
        seedTick(tick("t1", "九月", 1000L, 2000L))
        db.recordDao().insertRecord(record("r1", 1500L))
        val a = repo.createDial("A", 1000L, null)
        val b = repo.createDial("B", 1000L, null)
        repo.linkTick(a.id, "t1")
        repo.linkTick(b.id, "t1")

        repo.deleteDial(a.id)

        assertNull("框没了", repo.getDial(a.id))
        assertEquals("刻度还在", 1, db.tickDao().getActive().size)
        assertEquals("记录还在", 1, db.recordDao().getActiveRecordsInRange(0L, 3000L).size)
        assertEquals("另一个框不受影响", setOf(b.id), repo.dialIdsByTick()["t1"])
    }

    /** 表盘不写 ticks：加/删框之后刻度表的字段一个都没变。 */
    @Test
    fun dialsNeverTouchTheTickTable() = runBlocking {
        val t = tick("t1", "九月", 1000L, 2000L)
        seedTick(t)
        val before = db.tickDao().getById("t1")!!
        val dial = repo.createDial("今年", 1000L, null)
        repo.linkTick(dial.id, "t1")
        repo.deleteDial(dial.id)
        assertEquals("刻度一个字段都不该被表盘改过", before, db.tickDao().getById("t1"))
    }

    @Test
    fun summaryKeepsOnePreviousVersion() = runBlocking {
        val dial = repo.createDial("这一段", 1000L, null)
        repo.updateDialSummary(dial.id, "第一版")
        repo.updateDialSummary(dial.id, "第二版")

        val d = repo.getDial(dial.id)!!
        assertEquals("第二版", d.summary)
        assertEquals("第一版", d.summaryPrev)

        repo.swapDialSummary(dial.id)
        assertEquals("对调回来", "第一版", repo.getDial(dial.id)!!.summary)
    }

    @Test
    fun emptyNameFallsBackToPlaceholderAndBoundsAreEditable() = runBlocking {
        val dial = repo.createDial("   ", 1000L, null)
        assertEquals("未命名", dial.name)
        repo.renameDial(dial.id, "正式名字")
        repo.updateDialBounds(dial.id, 500L, 9000L)
        val d = repo.getDial(dial.id)!!
        assertEquals("正式名字", d.name)
        assertEquals(500L, d.startMs)
        assertEquals(9000L, d.endMs)
    }

    // ---- A1 分段 ----

    private fun sections(records: List<Record>, ticks: List<Tick>) = groupRecordsByTick(records, ticks)

    @Test
    fun sectionsFollowTicksAndPutUnassignedLast() {
        val sep = tick("t1", "九月", 1000L, 2000L)
        val oct = tick("t2", "十月", 2000L, 3000L)
        val records = listOf(
            record("a", 1500L),
            record("b", 2500L),
            record("c", 500L)
        )
        val out = sections(records, listOf(oct, sep))   // 故意乱序传入

        assertEquals("新的在前（和 App 别处一致），未归属永远最后", listOf("十月", "九月", "未归属"), out.map { it.title })
        assertEquals(listOf("b"), out[0].records.map { it.id })
        assertEquals(listOf("a"), out[1].records.map { it.id })
        assertEquals(listOf("c"), out[2].records.map { it.id })
        assertNull("最后一节没有刻度", out.last().tick)
    }

    @Test
    fun emptyTicksDoNotTakeASection() {
        val out = sections(listOf(record("c", 500L)), listOf(tick("t1", "九月", 1000L, 2000L)))
        assertEquals(listOf("未归属"), out.map { it.title })
    }

    @Test
    fun overlappingTicksAttributeToTheLaterStartingOne() {
        val sep = tick("t1", "九月", 1000L, 3000L)
        val oct = tick("t2", "十月", 2000L, 4000L)
        val out = sections(listOf(record("x", 2500L)), listOf(sep, oct))

        assertEquals("一条记录只出现一次", 1, out.size)
        assertEquals("归起点更晚的那个（和导出同一口径）", "十月", out[0].title)
    }

    @Test
    fun recordsOfTicksNotInThisDialCountAsUnassigned() {
        val inDial = tick("t1", "九月", 1000L, 2000L)
        val otherDial = tick("t2", "十月", 2000L, 3000L)
        val out = sections(listOf(record("a", 1500L), record("b", 2500L)), listOf(inDial))

        assertEquals(listOf("九月", "未归属"), out.map { it.title })
        assertEquals(listOf("b"), out.last().records.map { it.id })
        assertTrue(out.none { it.tick?.id == otherDial.id })
    }

    @Test
    fun newestFirstInsideASection() {
        val out = sections(listOf(record("old", 1200L), record("new", 4000L)), listOf(tick("t1", "九月", 1000L, 5000L)))
        assertEquals(listOf("new", "old"), out[0].records.map { it.id })
    }

    // ---- 汇总（A：统计型） ----

    @Test
    fun aggregateCountsPerSegmentAndIgnoresUnassigned() {
        val newer = tick("t2", "十月", 2000L, 3000L)
        val older = tick("t1", "九月", 1000L, 2000L)
        val sections = groupRecordsByTick(
            listOf(
                record("a", 1500L).copy(emotionalTags = """[{"name":"平静"}]"""),
                record("b", 2500L).copy(emotionalTags = """[{"name":"平静"}]"""),
                record("c", 2600L).copy(emotionalTags = """[{"name":"焦虑"}]"""),
                record("d", 500L).copy(emotionalTags = """[{"name":"平静"}]""")   // 未归属
            ),
            listOf(newer, older)
        )
        val agg = buildDialAggregate(
            sections = sections,
            nodeNamesByRecord = mapOf(
                "a" to listOf("示例"),
                "b" to listOf("示例", "Mirror"),
                "c" to listOf("示例")
            )
        )

        assertEquals("列 = 各段（新的在前）", listOf("十月", "九月"), agg.segments.map { it.name })

        val calm = agg.emotions.first { it.name == "平静" }
        assertEquals("新段 1 次、老段 1 次；未归属那 1 次不进表", listOf(1, 1), calm.counts)
        assertEquals(2, calm.total)
        assertEquals(2, calm.spread)

        val anxiety = agg.emotions.first { it.name == "焦虑" }
        assertEquals("只在最新那段出现", listOf(1, 0), anxiety.counts)
        assertEquals(1, anxiety.spread)

        val themeSample = agg.themes.first { it.name == "示例" }
        assertEquals(listOf(2, 1), themeSample.counts)
        assertEquals("示例 贯穿两段", 2, themeSample.spread)

        // 每段的主导情绪（时间卷上那一行「主导 · 矛盾 N」）；同数按名字排，保证稳定
        assertEquals(listOf("平静 1", "平静 1"), agg.dominantEmotions)
    }

    @Test
    fun aggregateRanksByTotalThenSpreadThenName() {
        val seg = tick("t1", "九月", 1000L, 2000L)
        val sections = groupRecordsByTick(
            listOf(
                record("a", 1500L).copy(emotionalTags = """[{"name":"甲"},{"name":"乙"}]"""),
                record("b", 1600L).copy(emotionalTags = """[{"name":"甲"}]""")
            ),
            listOf(seg)
        )
        val agg = buildDialAggregate(sections, emptyMap())
        assertEquals("总次数高的在前", listOf("甲", "乙"), agg.emotions.map { it.name })
        assertTrue("没有主题时主题表为空", agg.themes.isEmpty())
    }

    @Test
    fun aggregateRespectsTopLimits() {
        val seg = tick("t1", "九月", 1000L, 2000L)
        val recs = (1..10).map { i ->
            record("r$i", 1000L + i).copy(emotionalTags = """[{"name":"emo$i"}]""")
        }
        val agg = buildDialAggregate(groupRecordsByTick(recs, listOf(seg)), emptyMap(), topEmotions = 3)
        assertEquals("只留前 N 行（长尾对小屏没意义）", 3, agg.emotions.size)
    }

    @Test
    fun aggregateIsEmptyWhenNoSegments() {
        val agg = buildDialAggregate(listOf(DialSection(null, listOf(record("x", 500L)))), emptyMap())
        assertTrue("只有未归属、没有段 → 没有汇总", agg.isEmpty)
    }

    // ---- 迁移 46→47→48 ----

    @Test
    fun migrations46to48CreateDialTablesAndKeepData() = runBlocking {
        val wdb = db.openHelper.writableDatabase
        wdb.execSQL("DROP TABLE IF EXISTS dial_ticks")
        wdb.execSQL("DROP TABLE IF EXISTS dials")
        seedTick(tick("old-tick", "老刻度", 1000L, 2000L))

        AppDatabase.MIGRATION_46_47.migrate(wdb)
        // 47 版还在的那两列里放点东西，验证 47→48 是"删列但保留行"
        wdb.execSQL(
            "INSERT INTO dials (id,name,start_ms,end_ms,frozen_at,slice_snapshot,summary,summary_prev,created_at,updated_at) " +
                "VALUES ('d-old','老框',1000,2000,1500,'{\"x\":1}','旧后记','',1000,1000)"
        )
        wdb.execSQL("INSERT INTO dial_ticks (dial_id,tick_id) VALUES ('d-old','old-tick')")

        AppDatabase.MIGRATION_47_48.migrate(wdb)
        AppDatabase.MIGRATION_47_48.migrate(wdb)   // 幂等

        val kept = repo.getDial("d-old")
        assertEquals("47→48 的行要留住", "老框", kept?.name)
        assertEquals("后记不丢", "旧后记", kept?.summary)
        assertEquals("关联不丢", listOf("old-tick"), repo.ticksOfDial("d-old").map { it.id })
        assertEquals("老刻度一条不少", 1, db.tickDao().getActive().size)

        // 形状必须与 Room 按实体期望的完全一致（否则真机上开 App 就崩）
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
        listOf("dials", "dial_ticks", "ticks").forEach { table ->
            assertEquals(
                "迁移出来的 $table 必须与实体完全一致",
                tableInfo(expected.openHelper.readableDatabase, table),
                tableInfo(wdb, table)
            )
        }
        expected.close()

        val d = repo.createDial("新的", 1000L, null)
        repo.linkTick(d.id, "old-tick")
        assertEquals(listOf("old-tick"), repo.ticksOfDial(d.id).map { it.id })
    }
}
