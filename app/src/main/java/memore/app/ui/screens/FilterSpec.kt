package memore.app.ui.screens

/**
 * 筛选的两种对象：标签 / 协议。
 *
 * 2026-11-12 从 `FilterSheet.kt` 搬出来。那个文件是底部抽屉时代的筛选面板，
 * 已被全屏 `FilterScreen` 取代（法条 4.3：底部抽屉不用于确认/菜单场景），
 * 但它里面顺手定义了这两个类型——删文件时不能连类型一起删。
 */
enum class FilterType { TAG, PROTOCOL }

/** 一次"应用筛选"要带走的全部维度（标签 / 协议 + 时间范围 + 附件 + 加密）。 */
data class FilterSpec(
    val type: FilterType,
    val ids: List<Int>,
    val start: Long?,
    val end: Long?,
    val hasAttachment: Boolean?,
    val locked: Boolean?
)
