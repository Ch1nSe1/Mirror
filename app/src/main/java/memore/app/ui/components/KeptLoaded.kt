package memore.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.Flow

/**
 * 首帧不闪空的取数缓存。
 *
 * ## 解决的是什么
 *
 * `collectAsState(initial = emptyList())` 的**第一帧一定是空**：Flow 的第一次发射
 * 要过一次数据库（常常还要 join 几张表），于是画面先画出"空列表"，一两百毫秒后
 * 数据才"啪"地出现——观感就是界面跳一下。
 *
 * 更糟的是**离开页面再回来**：composition 销毁，`remember` 全丢，回来又从空开始，
 * 正好和翻页动效重叠，像是"翻回来是空的，然后忽然满了"。
 *
 * ## 做法
 *
 * 把"上一次拿到的列表"按 key 存在 composition 之外（[KeptLoads]），下次进来
 * 首帧直接用它；Flow 真正到达后无感替换。进程重启时缓存为空，行为退回原样——正确。
 *
 * ## 用法
 *
 * ```kotlin
 * val tags = tagRepository.observeAll().collectKeptAsState("TagManage.tags")
 * ```
 *
 * key 要**全局唯一且稳定**（`"屏名.字段"`），否则两个屏会互相串数据。
 */
@Composable
fun <T> Flow<List<T>>.collectKeptAsState(key: String): List<T> {
    val state = collectAsState(initial = KeptLoads.get<List<T>>(key).orEmpty())
    LaunchedEffect(state.value) {
        if (state.value.isNotEmpty()) {
            KeptLoads.put(key, state.value)
        } else {
            // 2026-11-17：列表变空时必须**丢掉缓存**。
            // 原来这里只在非空时 put，于是"曾经有数据、后来清空"的列表会永远留着旧快照：
            // 时间线的 AI 任务列表（activeAiTasks）就是这样 —— 总结跑完、流已发空列表，
            // 缓存里还留着那条 PROCESSING，回到时间线首帧拿旧快照，
            // 转圈动画重播约一秒、右上角队列徽标也闪一下（用户报的就是这个）。
            KeptLoads.invalidate(key)
        }
    }
    return state.value
}

/**
 * 跨 composition 的列表缓存。主线程读写，不需要锁。
 *
 * 只存 List，不存别的形态：需要缓存的场景都是"一屏一列表"，
 * 存得越多越容易在换主题/换账号时留下陈旧数据。
 */
object KeptLoads {
    private val store = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = store[key] as? T

    fun put(key: String, value: Any) {
        store[key] = value
    }

    /** 数据被大幅修改（清空、导入、删除全部）时清掉某个 key，避免显示旧内容。 */
    fun invalidate(key: String) {
        store.remove(key)
    }
}
