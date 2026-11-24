package memore.app.data.vector

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import memore.app.data.local.AppDatabase
import memore.app.data.repository.RecordRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 同月重名合并（2026-11-19）。
 *
 * 背景：命名原来是一簇一次调用，模型看不到同月的其它簇，会给出两个一样的名字；
 * 加了命名锚之后更严重（多个簇都判定"我就是关系演变这条线"）。
 *
 * 现在的处理：一次发整月（提示词里要求名字不重复）+ 仍然重名时合并成一簇。
 * 这里钉住合并规则：记录合并去重、条数相加、名字保留第一个、顺序不变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MonthlyTopicsMergeTest {

    private lateinit var db: AppDatabase
    private lateinit var svc: MonthlyTopicsService

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val repo = RecordRepository(
            recordDao = db.recordDao(),
            versionDao = db.versionDao(),
            attachmentDao = db.attachmentDao(),
            taskDao = db.aiTaskDao(),
            db = db
        )
        svc = MonthlyTopicsService(ctx, repo, db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `同名簇合并记录去重且条数相加`() {
        val month = MonthlyTopicsService.NamedMonth(
            "2026-10",
            listOf(
                MonthlyTopicsService.NamedCluster("自我迭代", 3, listOf("r1", "r2", "r3"), summary = "第一种", keywords = "自省"),
                MonthlyTopicsService.NamedCluster("阅读与解构", 2, listOf("r9", "r10"), summary = "别的"),
                MonthlyTopicsService.NamedCluster("自我迭代", 2, listOf("r3", "r4"), summary = "第二种")
            )
        )
        val merged = svc.mergeSameNameClusters(month).clusters

        assertEquals("三条变两条", 2, merged.size)
        val self = merged.first { it.name == "自我迭代" }
        assertEquals("条数相加", 5, self.count)
        assertEquals("记录合并去重", listOf("r1", "r2", "r3", "r4"), self.recordIds)
        assertEquals("保留第一次出现的代表句", "第一种", self.summary)
    }

    @Test
    fun `没有重名时原样返回`() {
        val month = MonthlyTopicsService.NamedMonth(
            "2026-07",
            listOf(
                MonthlyTopicsService.NamedCluster("关系演变", 1, listOf("a")),
                MonthlyTopicsService.NamedCluster("追星与梦", 1, listOf("b"))
            )
        )
        assertEquals(month, svc.mergeSameNameClusters(month))
    }

    @Test
    fun `合并后顺序按首次出现`() {
        val month = MonthlyTopicsService.NamedMonth(
            "2026-08",
            listOf(
                MonthlyTopicsService.NamedCluster("甲", 1, listOf("1")),
                MonthlyTopicsService.NamedCluster("乙", 1, listOf("2")),
                MonthlyTopicsService.NamedCluster("甲", 1, listOf("3"))
            )
        )
        assertEquals(listOf("甲", "乙"), svc.mergeSameNameClusters(month).clusters.map { it.name })
    }
}
