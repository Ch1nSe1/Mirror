package memore.app.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder

/**
 * 转场（设计法 8.2）：**全项目只允许在这里出现 `AnimatedContent`。**
 *
 * ## 两次做错，留档
 *
 * 1. 最初 120ms 纯交叉淡入 → "没有动画"。同一张米色底上，纯淡入淡出几乎没有
 *    可感知的中间态（60fps 下 120ms = 7 帧），大脑判成"闪了一下"。
 * 2. 改成「淡入 + 1/50 屏高的竖向微移」→ 仍然不是翻页。**方向错了**：
 *    竖向位移表达的是"换页签"，横向位移表达的才是"进/退一层"。
 *
 * ## 现在的规则
 *
 * 前进（压栈）：新页从**右侧 1/4 屏宽**推入，旧页向**左 1/6** 退并淡出。
 * 返回（弹栈）：反向。
 *
 * 方向不是猜的——由调用方把栈的动作如实报进来（[NavDirection]）。
 * 没有方向的"翻页"等于每秒都在往同一个方向滑，返回时会变成"又从右边进来一次"，
 * 比硬切更糟，所以第一次我才退成了竖向。
 */
enum class NavTransition(val inMs: Int, val outMs: Int) {
    None(0, 0),
    Page(260, 200)
}

/** 这一次换屏是"进一层"还是"退一层"，由导航栈给出。 */
enum class NavDirection { Forward, Backward }

/**
 * 导航宿主：唯一渲染当前屏的地方，也是唯一有转场的地方。
 *
 * @param targetId 当前屏 id；变化时触发转场
 * @param direction 这次变化的方向（由栈在跳转处设置）
 * @param transition 用哪种转场，默认翻页
 * @param content 给定屏 id 渲染那一屏
 */
@Composable
fun MemoreNavHost(
    targetId: Int,
    direction: NavDirection = NavDirection.Forward,
    transition: NavTransition = NavTransition.Page,
    content: @Composable (Int) -> Unit
) {
    // 2026-11-15：**每一屏的 rememberSaveable 状态要跟着屏走**。
    // 不加这一层时，从 A 进 B 再返回 A，A 里所有 rememberSaveable 都会重新初始化——
    // 表现出来就是"展开的列表收起来了、填了一半的输入清空了"。全 App 都受这一处影响，
    // 所以修在这里，而不是让每个屏自己存一份。
    val stateHolder = rememberSaveableStateHolder()

    AnimatedContent(
        targetState = targetId,
        transitionSpec = {
            when (transition) {
                NavTransition.None -> EnterTransition.None togetherWith ExitTransition.None
                NavTransition.Page -> {
                    val forward = direction == NavDirection.Forward
                    val enter = fadeIn(tween(transition.inMs)) +
                        slideInHorizontally(tween(transition.inMs)) { w ->
                            if (forward) w / 4 else -w / 4
                        }
                    val exit = fadeOut(tween(transition.outMs)) +
                        slideOutHorizontally(tween(transition.outMs)) { w ->
                            if (forward) -w / 6 else w / 6
                        }
                    enter togetherWith exit
                }
            }
        },
        label = "memore-nav"
    ) { id ->
        stateHolder.SaveableStateProvider(id) { content(id) }
    }
}
