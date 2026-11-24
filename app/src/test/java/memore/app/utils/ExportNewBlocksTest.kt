package memore.app.utils

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-20：整体备份新增的四个块必须真的写进 JSON。
 * 之前这些结构（锚点 / 分析版本 / agent 历史 / 边的证据）不在包里，导一次就丢。
 */
class ExportNewBlocksTest {

    @Test
    fun `新块写入 JSON`() {
        val data = ExportData(
            version = "2.5",
            export_time = "2026-11-20T00:00:00Z",
            count = 0,
            records = emptyList(),
            anchors = listOf(ExportedAnchor("r1", "测试甲", "PERSON", "任何东西我都会联想到测试甲", 1L)),
            analysis_versions = listOf(
                ExportedAnalysisVersion("r1", "m", "6", "hash", "总结", "[]", "OK", "", 2L)
            ),
            agent_actions = listOf(
                ExportedAgentAction("a1", "dsh", "测试动作", "[]", "applied", false, "ok", 3L, 4L, 0L)
            ),
            agent_commands = listOf(
                ExportedAgentCommand("a1#0", "tag_record", "{}", "done", "note", 5L, 6L, "a1")
            )
        )
        val json = ExportEngine.buildOrderedJson(data)
        assertTrue(json.contains("\"anchors\""))
        assertTrue(json.contains("\"analysis_versions\""))
        assertTrue(json.contains("\"agent_actions\""))
        assertTrue(json.contains("\"agent_commands\""))
        assertTrue(json.contains("任何东西我都会联想到测试甲"))
    }

    @Test
    fun `关系边带引文与来源`() {
        val data = ExportData(
            version = "2.5",
            export_time = "2026-11-20T00:00:00Z",
            count = 0,
            records = listOf(
                ExportRecord(
                    id = "r1",
                    type = "ESSAY",
                    content = "x",
                    tags = emptyList(),
                    timestamp = "2026-11-20T00:00:00Z",
                    starred = false,
                    deleted = false,
                    relations_out = listOf(
                        ExportedRelationOut(
                            to_id = "r2", type = "LINK", why = "同一条线",
                            quote_a = "左边原句", quote_b = "右边原句", source = "AGENT",
                            valid_from = 1L, valid_to = null
                        )
                    )
                )
            )
        )
        val json = ExportEngine.buildOrderedJson(data)
        assertTrue(json.contains("quote_a"))
        assertTrue(json.contains("AGENT"))
    }
}
