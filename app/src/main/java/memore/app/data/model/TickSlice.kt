package memore.app.data.model

/** 切片里的一项：名称 + 衰减权重 + 出现次数。 */
data class SliceEntry(
    val name: String,
    val weight: Double = 0.0,
    val count: Int = 0
)

/**
 * 一个刻度的"定格切片"：终结刻度在最终化时，用自身时间段内的记录 + 以自身终点为衰减基准，算一次并锁存。
 * 之后时间再推移都不变；只有改时间段才会重算重新定格。
 * frozenAt = 计算时的基准时刻（终结刻度=endMs；进行中=now，仅实时使用、不存）。
 */
data class TickSlice(
    val frozenAt: Long,
    val emotion: List<SliceEntry> = emptyList(),
    val nodes: List<SliceEntry> = emptyList()
)
