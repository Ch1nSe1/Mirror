package memore.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 新建记录的「预设」（2026-11-17）。
 *
 * 存的是**新建时能一键套用的五样**：类型、来源、标签、协议、区域。
 * 不存附件（文件不该被预设复制）、不存正文、不存情绪（情绪是写完才知道的）。
 *
 * 排序规则（用户口径）：**哪个后执行就排最前** —— 用 last_used_at 降序、
 * 同刻用 created_at 降序，所以"刚用过的"和"刚建的"都会浮到最前面。
 */
@Entity(tableName = "record_presets")
data class RecordPreset(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    /** RecordType.name（ESSAY / DIALOGUE / TRANSCRIPT …）。 */
    val type: String,

    @ColumnInfo(name = "origin_id")
    val originId: String? = null,

    /** 标签 id 的 JSON 数组（如 "[3,7]"）。存 id 而不是名字：改名后仍然指向同一个标签。 */
    @ColumnInfo(name = "tag_ids")
    val tagIds: String = "[]",

    /** 协议 id 的 JSON 数组。 */
    @ColumnInfo(name = "protocol_ids")
    val protocolIds: String = "[]",

    /** 手动型区域 id 的 JSON 数组（2026-11-17 用户要求：预设也要带区域）。 */
    @ColumnInfo(name = "zone_ids")
    val zoneIds: String = "[]",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /** 最近一次被套用的时间（0 = 从没用过）。排序用。 */
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long = 0L
)
