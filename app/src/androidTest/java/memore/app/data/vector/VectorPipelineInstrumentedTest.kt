package memore.app.data.vector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import memore.app.di.AppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机上的「回填 → 聚类」端到端验收（P1 + P3 的验收线）。
 *
 * 注意：跑之前先 `adb shell am force-stop memore.app`，避免两个 Room 实例同时开库。
 * 这条测试**真的会写库**（把现有记录补上向量、重建主题），属于一次性初始化操作。
 */
@RunWith(AndroidJUnit4::class)
class VectorPipelineInstrumentedTest {

    @Test
    fun 回填与聚类都能跑通() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val container = AppContainer(ctx)

        val indexed = container.vectorIndexService.syncMissing()
        val stats = container.vectorIndexService.stats()
        println("[PIPELINE] 本次处理=$indexed stats=$stats")
        assertTrue("回填后应有至少 6 条记录被索引：$stats", stats.indexed >= 6)

        val topics = container.topicService.rebuild()
        println("[PIPELINE] 主题数=$topics")
        assertTrue("应生成至少 1 个主题", topics >= 1)

        val list = container.topicService.observeTopics().first()
        assertTrue("主题列表不应为空", list.isNotEmpty())

        // 相似检索：取主题里的一条记录，要求能找出邻居且不含自己
        val firstTopic = list[0]
        val anyId = container.topicService.recordsOf(firstTopic.id).firstOrNull()?.id
        if (anyId != null) {
            val similar = container.retrievalService.similar(anyId, 5)
            println("[PIPELINE] 相似=${similar.map { it.record.content.take(12) }}")
            assertTrue("相似结果不应包含自己", similar.none { it.record.id == anyId })
        }
    }
}
