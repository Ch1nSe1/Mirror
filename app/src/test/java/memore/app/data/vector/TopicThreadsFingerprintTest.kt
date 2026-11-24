package memore.app.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 线索缓存指纹（2026-11-18 评审补测）：同数据稳定、任一改动必须失效。 */
class TopicThreadsFingerprintTest {

    private fun month(name: String, count: Int): MonthlyTopicsService.NamedMonth =
        MonthlyTopicsService.NamedMonth(
            "2026-07",
            listOf(MonthlyTopicsService.NamedCluster(name, count, emptyList()))
        )

    @Test
    fun fingerprintIsStableForSameData() {
        val a = listOf(month("关系依恋", 32))
        val b = listOf(month("关系依恋", 32))
        assertEquals(TopicThreads.fingerprint(a), TopicThreads.fingerprint(b))
    }

    @Test
    fun fingerprintChangesWhenCountChanges() {
        val a = listOf(month("关系依恋", 32))
        val b = listOf(month("关系依恋", 31))
        assertNotEquals(TopicThreads.fingerprint(a), TopicThreads.fingerprint(b))
    }

    @Test
    fun fingerprintChangesWhenNameChanges() {
        val a = listOf(month("关系依恋", 32))
        val b = listOf(month("依恋与自我剖析", 32))
        assertNotEquals(TopicThreads.fingerprint(a), TopicThreads.fingerprint(b))
    }
}
