package memore.app.ui.screens

import androidx.compose.runtime.snapshots.Snapshot
import memore.app.data.repository.RecordRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 关联页的跨屏状态必须是 Compose 状态（2026-11-21）。
 *
 * 原来的 bug：`linksLoaded` / `links` 是普通 `var`，LaunchedEffect 加载完写进去
 * **不会触发重组**——第一次进关联页一直转圈，退出去再进才好（第二次组合读到已加载）。
 *
 * 这里不测界面，只钉住根因：对这几个字段的写入必须进入 Compose 快照系统
 * （快照被 apply 时有人收到通知 = Composable 能被唤醒）。普通 var 不会 apply 任何快照。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkListStateTest {

    /**
     * 在「可写快照」里改字段再 apply：只有 Snapshot 状态（mutableStateOf）的写入会被
     * 记录成变更并触发 apply 观察者；普通 var 什么都不会发生。
     */
    private fun assertSnapshotWrite(block: (LinkListUiState) -> Unit) {
        val state = LinkListUiState()
        var applied = 0
        val handle = Snapshot.registerApplyObserver { _, _ -> applied++ }
        val snap = Snapshot.takeMutableSnapshot()
        try {
            snap.enter<Unit> { block(state) }
            snap.apply()
        } finally {
            snap.dispose()
            handle.dispose()
        }
        assertTrue("这个字段的写入没有进入快照系统 → 界面不会重组", applied > 0)
    }

    private fun record(id: String) = memore.app.data.model.Record(
        id = id, type = memore.app.data.model.RecordType.ESSAY, content = "内容$id"
    )

    private val pair = RecordRepository.LinkPair(
        from = record("a"), to = record("b"), why = "测试边", createdAt = 0L
    )

    @Test
    fun `linksLoaded 的写入会触发快照`() = assertSnapshotWrite { it.linksLoaded = true }

    @Test
    fun `links 的写入会触发快照`() = assertSnapshotWrite { it.links = listOf(pair) }

    @Test
    fun `anchorsLoaded 的写入会触发快照`() = assertSnapshotWrite { it.anchorsLoaded = true }

    @Test
    fun `anchors 的写入会触发快照`() = assertSnapshotWrite { it.anchors = it.anchors + memore.app.data.repository.AnchorRepository.AnchorGroup("锚", emptyList(), emptyList(), emptyList()) }

    @Test
    fun `初始值是未加载`() {
        val state = LinkListUiState()
        assertEquals(false, state.linksLoaded)
        assertEquals(false, state.anchorsLoaded)
        assertTrue(state.links.isEmpty())
        assertTrue(state.anchors.isEmpty())
    }
}
