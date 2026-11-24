package memore.app.ui.screens

import memore.app.data.model.Record

/**
 * 时间线**附加筛选维度**的判定：时间范围 / 有没有附件 / 加密状态。
 *
 * 纯函数、不碰数据库 —— 标签与协议那两维由仓库的查询负责（`getRecordsByTagIds` 等），
 * 这里只处理"查回来之后再按属性过一遍"的部分。
 *
 * 约定：
 * - `null` = 该维度不限；
 * - 时间两端都存**所选日期的本地零点**，判定时**两端都含当天**（选 8/1 ~ 8/31 要能筛出 8/31 晚上写的记录）；
 * - "加密"看的是记录的锁定标记（`isLocked`），与列表上「已加密」徽标同源。
 */
object TimelineFilter {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun matches(
        record: Record,
        start: Long?,
        end: Long?,
        hasAttachment: Boolean?,
        locked: Boolean?
    ): Boolean {
        if (start != null && record.createdAt < start) return false
        // 止日按"含当天"处理：把边界推到次日零点
        if (end != null && record.createdAt >= end + DAY_MS) return false
        if (hasAttachment != null && (record.attachmentsCount > 0) != hasAttachment) return false
        if (locked != null && record.isLocked != locked) return false
        return true
    }

    /** 生效中的维度条数（用于提示"筛了 N 项"）。 */
    fun activeDimensionCount(start: Long?, end: Long?, hasAttachment: Boolean?, locked: Boolean?): Int =
        (if (start != null || end != null) 1 else 0) +
            (if (hasAttachment != null) 1 else 0) +
            (if (locked != null) 1 else 0)
}
