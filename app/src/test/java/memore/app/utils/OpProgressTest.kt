package memore.app.utils

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import memore.app.data.local.AppDatabase
import memore.app.data.repository.RecordRepository
import memore.app.data.repository.TagRepository
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
 * 导出/导入进度：模型算术 + 一条"**进度框不会卡死**"的回归。
 *
 * 进度是纯观察性的，但它有个很容易踩的坑：**任何提前返回/失败路径忘了清空进度，
 * 模态进度框就会永远挂在屏幕上**（因为没有取消按钮）。这里专门钉住那条路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpProgressTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- 模型算术 ----

    @Test
    fun totalMissing_meansIndeterminate() {
        val p = OpProgress("正在整理数据…")
        assertTrue("没有分母就该是不定量，不能假装有百分比", p.indeterminate)
        assertEquals(0f, p.fraction, 0.0001f)
        assertNull("不定量且无 detail 时不该有副标题", p.subtitle())
    }

    /** 预统计是估算：万一偏小，也绝不能显示超过 100%。 */
    @Test
    fun fraction_isClampedAtUpperBound() {
        assertEquals(1f, OpProgress("写包", done = 120, total = 100).fraction, 0.0001f)
        assertEquals(0.5f, OpProgress("写包", done = 50, total = 100).fraction, 0.0001f)
    }

    @Test
    fun subtitlePrefersDetail_overRawCounts() {
        assertEquals("已还原 183 MB / 267 MB", OpProgress("还原", 1, 2, "已还原 183 MB / 267 MB").subtitle())
        assertEquals("120/240", OpProgress("导入记录", 120, 240).subtitle())
    }

    // ---- 进度框不会卡死（关键回归） ----

    @Test
    fun exportThatFailsEarly_stillClearsProgress() = runBlocking {
        assertNull("空闲时不该有进度", ExportEngine.progress.value)

        // 空库导出：会在"没有可导出的记录"这条**提前返回**上退出 —— 正是最容易忘记清空进度的那条路
        val recordRepo = RecordRepository(
            recordDao = db.recordDao(),
            versionDao = db.versionDao(),
            attachmentDao = db.attachmentDao(),
            taskDao = db.aiTaskDao(),
            db = db
        )

        val result = ExportEngine.export(
            context = context,
            recordRepo = recordRepo,
            tagRepo = TagRepository(db.tagDao()),
            format = ExportFormat.JSON_ONLY
        )

        assertTrue("空库导出应当失败", result.isFailure)
        assertNull(
            "提前返回/失败路径必须清空进度 —— 否则模态进度框会永远挂在屏幕上（它没有取消按钮）",
            ExportEngine.progress.value
        )
    }
}
