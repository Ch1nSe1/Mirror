package memore.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import memore.app.ui.theme.MemoreType
import memore.app.utils.OpProgress

/**
 * 长任务（导出 / 导入）的模态进度（2026-11-06 改版，用户选的 A 版）。
 *
 * 只有三样东西：**波形（四点竖条）+ 居中的标题**。没有阶段小字、没有进度条、没有按钮。
 * `progress == null` 时什么都不渲染——引擎任务结束会自己清空。
 *
 * 刻意不可取消：点外部、按返回都不关。
 */
@Composable
fun OpProgressDialog(title: String, progress: OpProgress?) {
    if (progress == null) return
    Dialog(onDismissRequest = { /* 不可取消 */ }) {
        Surface(
            shape = MirrorShapes.card,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = mirrorHairline(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MirrorLoading()
                Spacer(Modifier.height(4.dp))
                Text(
                    title,
                    style = MemoreType.pageTitleSans,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    if (progress.phase.isBlank()) "" else "",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 无进度的忙碌弹窗（2026-11-17）。
 *
 * 与 [OpProgressDialog] 同一外观（波形 + 居中标题、不可取消），
 * 但不需要 OpProgress：主题刷新 / 整理线索这类“一段不确定时长”的任务直接用它。
 */
@Composable
fun BusyDialog(title: String) {
    Dialog(onDismissRequest = { /* 不可取消 */ }) {
        Surface(
            shape = MirrorShapes.card,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = mirrorHairline(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MirrorLoading()
                Spacer(Modifier.height(4.dp))
                Text(
                    title,
                    style = MemoreType.pageTitleSans,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}