package memore.app.data.agent

/**
 * 撤回时用到的纯计算（2026-11-15）。
 *
 * 为什么要单独拎出来：撤回的错只有两种——**漏删**（动作新加的没去掉）和
 * **误删**（动作之前就有的被抹掉）。这两种都是集合差，不需要数据库就能验，
 * 所以放在这里、用单测盖住，而不是靠真机点一遍。
 */
object ActionDiffs {

    /** 一个集合的回滚方向：该补什么、该去什么。 */
    data class Diff(val toAdd: Set<String>, val toRemove: Set<String>) {
        val isEmpty: Boolean get() = toAdd.isEmpty() && toRemove.isEmpty()
    }

    /**
     * 当前集合 → 目标集合 需要做的两件事。
     *
     * @param wanted 动作执行**之前**的状态（快照）
     * @param current 现在的状态
     */
    fun setDiff(wanted: Set<String>, current: Set<String>): Diff =
        Diff(toAdd = wanted - current, toRemove = current - wanted)

    /** 关联是 (from,to) 对，规则同上。 */
    fun pairDiff(
        wanted: Set<Pair<String, String>>,
        current: Set<Pair<String, String>>
    ): Diff {
        val add = wanted - current
        val remove = current - wanted
        return Diff(
            toAdd = add.map { it.first + "\u0000" + it.second }.toSet(),
            toRemove = remove.map { it.first + "\u0000" + it.second }.toSet()
        )
    }

    /**
     * 撤回的冲突判据：**这条记录在这次动作之后又被改过正文**。
     *
     * 只看"时间变了"不够——标签也会刷新 updated_at，那不算改正文。
     * 两个条件同时成立才算冲突：更新时间变新 **且** 正文变了。
     */
    fun contentConflict(
        snapshotUpdatedAt: Long,
        snapshotContent: String,
        currentUpdatedAt: Long,
        currentContent: String
    ): Boolean = currentUpdatedAt > snapshotUpdatedAt && currentContent != snapshotContent
}
