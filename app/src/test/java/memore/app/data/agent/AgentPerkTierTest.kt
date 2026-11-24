package memore.app.data.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * agent 写权限分级（2026-11-21，同日晚间调整）。
 *
 * 用户先要求删边免批，后来又收回：**删边是破坏性的，必须手机批**。
 * 现在的约定：建边一级、删边二级、驳回/改类型二级、锁迁移永远禁止。
 */
class AgentPerkTierTest {

    @Test
    fun `建边一级免批、删边要手机批`() {
        assertEquals(1, MirrorActionService.tierOf("relation.link"))
        assertEquals(2, MirrorActionService.tierOf("relation.unlink"))
    }

    @Test
    fun `驳回和改类型仍然要手机批准`() {
        assertEquals(2, MirrorActionService.tierOf("relation.reject"))
        assertEquals(2, MirrorActionService.tierOf("relation.retype"))
        assertEquals(2, MirrorActionService.tierOf("relation.restore"))
    }

    @Test
    fun `锁迁移永远禁止`() {
        assertEquals(3, MirrorActionService.tierOf("lock.migrate"))
    }

    @Test
    fun `未知动词默认按二级处理`() {
        assertEquals(2, MirrorActionService.tierOf("something.unknown"))
    }

    @Test
    fun `别名映射和删概念都要手机批准`() {
        listOf("alias.add", "alias.update", "alias.delete", "alias.merge", "concept.delete").forEach {
            assertEquals("$it 应走二级", 2, MirrorActionService.tierOf(it))
        }
    }

    @Test
    fun `policy 里删边在 tier2、不在 tier1`() {
        val t1 = MirrorActionService.policy().getJSONArray("tier1").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        val t2 = MirrorActionService.policy().getJSONArray("tier2").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        assert(!t1.contains("relation.unlink"))
        assert(t2.contains("relation.unlink"))
    }
}
