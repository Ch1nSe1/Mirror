package memore.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import memore.app.data.local.AppDatabase
import memore.app.data.model.Record
import memore.app.data.model.RecordType
import memore.app.data.vector.MonthlyTopicsService
import memore.app.data.vector.TopicThreads
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 表盘「主导主题」＝跨月线索的计数（2026-11-18 接真主题）。
 *
 * 钉死这几条：
 * ① **计数只取表盘区间内的记录**——表盘结束之后创建的记录，哪怕和区间内记录同属一个簇，
 *    也一条都不许算进来（用户 2026-11-18 明确要求：时间语义必须对）；
 * ② 进行中的表盘（endMs = null）算到"现在"；
 * ③ 线索成员 → 线索名；同一记录同一线索只算一次；
 * ④ 没被线索覆盖的月度主题，退回用自己的名字兜底，不丢记录；
 * ⑤ 没缓存时不崩、返回空。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DialThemeCountingTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DialRepository

    private val start = 1_000_000L
    private val end = 2_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun record(id: String, at: Long): Record =
        Record(id = id, type = RecordType.ESSAY, content = "内容$id", createdAt = at, updatedAt = at)

    private fun snapshot(vararg clusters: MonthlyTopicsService.NamedCluster): MonthlyTopicsService.Snapshot =
        MonthlyTopicsService.Snapshot(
            fingerprint = "fp",
            months = listOf(MonthlyTopicsService.NamedMonth(month = "2026-07", clusters = clusters.toList()))
        )

    private fun buildRepo(
        snap: MonthlyTopicsService.Snapshot?,
        threads: List<TopicThreads.Thread> = emptyList()
    ) {
        repo = DialRepository(
            dialDao = db.dialDao(),
            tickDao = db.tickDao(),
            recordDao = db.recordDao(),
            themeSnapshot = { snap },
            threadList = { threads }
        )
    }

    // ---- ① 时间边界 ----

    @Test
    fun `已结束的表盘不把区间之后的记录算进主题`() = runBlocking {
        val inside = "r-in"
        val after = "r-after"
        db.recordDao().insertRecord(record(inside, start + 10))
        db.recordDao().insertRecord(record(after, end + 10))

        // 两条记录同属一个簇（真实场景：月度簇把整月的记录归在一起）
        buildRepo(
            snap = snapshot(MonthlyTopicsService.NamedCluster("关系结构动力", 2, listOf(inside, after))),
            threads = listOf(TopicThreads.Thread("关系依恋", listOf("关系结构动力")))
        )

        val counts = repo.themeNamesByRecord(dial(endMs = end))
            .flatMap { (_, names) -> names }
            .groupingBy { it }.eachCount()

        assertEquals("只该数区间内那一条", 1, counts["关系依恋"] ?: 0)
    }

    @Test
    fun `进行中的表盘算到现在`() = runBlocking {
        val inside = "r-inside"
        val far = "r-far"
        // 两条都在 start 之后，但 end 不传（进行中）→ 两条都算
        db.recordDao().insertRecord(record(inside, start + 10))
        db.recordDao().insertRecord(record(far, end + 10))

        buildRepo(
            snap = snapshot(MonthlyTopicsService.NamedCluster("关系结构动力", 2, listOf(inside, far))),
            threads = listOf(TopicThreads.Thread("关系依恋", listOf("关系结构动力")))
        )

        val counts = repo.themeNamesByRecord(dial(endMs = null))
            .flatMap { (_, names) -> names }
            .groupingBy { it }.eachCount()

        assertEquals(2, counts["关系依恋"] ?: 0)
    }

    // ---- ③ 线索映射 ----

    @Test
    fun `线索成员算出线索名`() = runBlocking {
        db.recordDao().insertRecord(record("r1", start + 10))
        buildRepo(
            snap = snapshot(MonthlyTopicsService.NamedCluster("关系结构动力", 1, listOf("r1"))),
            threads = listOf(TopicThreads.Thread("关系演变", listOf("关系结构动力")))
        )
        assertEquals(mapOf("r1" to listOf("关系演变")), repo.themeNamesByRecord(dial(endMs = end)))
    }

    @Test
    fun `一条记录同时属于两条线索时都保留且去重`() = runBlocking {
        db.recordDao().insertRecord(record("r1", start + 10))
        buildRepo(
            snap = snapshot(
                MonthlyTopicsService.NamedCluster("关系结构动力", 1, listOf("r1")),
                MonthlyTopicsService.NamedCluster("追星与自我认同", 1, listOf("r1"))
            ),
            threads = listOf(
                TopicThreads.Thread("关系演变", listOf("关系结构动力")),
                TopicThreads.Thread("追星与梦", listOf("追星与自我认同"))
            )
        )
        val names = repo.themeNamesByRecord(dial(endMs = end))["r1"].orEmpty()
        assertEquals(listOf("关系演变", "追星与梦").sorted(), names.sorted())
        assertEquals("去重后不应重复", names.distinct(), names)
    }

    // ---- ④ 兜底 ----

    @Test
    fun `没被线索覆盖的主题退回自己的名字`() = runBlocking {
        db.recordDao().insertRecord(record("r1", start + 10))
        buildRepo(
            snap = snapshot(MonthlyTopicsService.NamedCluster("散落的主题", 1, listOf("r1"))),
            threads = listOf(TopicThreads.Thread("关系演变", listOf("关系结构动力")))
        )
        assertEquals(mapOf("r1" to listOf("散落的主题")), repo.themeNamesByRecord(dial(endMs = end)))
    }

    // ---- ⑤ 空数据 ----

    @Test
    fun `没有主题缓存时返回空而不是崩`() = runBlocking {
        db.recordDao().insertRecord(record("r1", start + 10))
        buildRepo(snap = null)
        assertTrue(repo.themeNamesByRecord(dial(endMs = end)).isEmpty())
    }

    @Test
    fun `区间里没有记录时返回空`() = runBlocking {
        buildRepo(
            snap = snapshot(MonthlyTopicsService.NamedCluster("关系结构动力", 1, listOf("r-x"))),
            threads = listOf(TopicThreads.Thread("关系演变", listOf("关系结构动力")))
        )
        assertTrue(repo.themeNamesByRecord(dial(endMs = end)).isEmpty())
    }

    private fun dial(endMs: Long?): memore.app.data.model.Dial =
        memore.app.data.model.Dial(id = "d1", name = "测试期", startMs = start, endMs = endMs, createdAt = start, updatedAt = start)
}
