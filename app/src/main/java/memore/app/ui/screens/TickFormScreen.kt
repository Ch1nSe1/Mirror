package memore.app.ui.screens

import memore.app.ui.components.MirrorShapes

import memore.app.ui.theme.MemoreType
import memore.app.ui.components.MemoreLineInput
import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.utils.formatDateOnly
import java.util.Calendar

/**
 * 新建表盘 / 新建刻度（2026-10-24 由弹窗改独立页）。
 *
 * 两者字段完全一样：名称 + 起点 + 进行中 + 终点，所以共用这一套表单，
 * 只由调用方传标题和保存回调。日期点行选择（系统日期选择器），不再塞输入框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TickFormScreen(
    title: String,
    submitLabel: String,
    onSave: (name: String, startMs: Long, endMs: Long?) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var startMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var ongoing by remember { mutableStateOf(true) }
    var endMs by remember { mutableStateOf<Long?>(null) }

    fun pickDate(initial: Long, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply { timeInMillis = initial }
        DatePickerDialog(
            context,
            { _, y, m, d ->
                val c = Calendar.getInstance()
                c.set(y, m, d, 0, 0, 0)
                c.set(Calendar.MILLISECOND, 0)
                onPicked(c.timeInMillis)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    val canSubmit = name.isNotBlank() && (ongoing || endMs != null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MemoreType.pageTitleSans) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = { if (canSubmit) onSave(name.trim(), startMs, if (ongoing) null else endMs) },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).height(48.dp),
                    shape = MirrorShapes.card,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(submitLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Text(
                "名称",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
            )
            MemoreLineInput(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("例如「晚春」") },
                shape = MirrorShapes.menu,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )

            Text(
                "时间",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp, bottom = 2.dp)
            )
            Row(
                Modifier.fillMaxWidth().height(50.dp).clickable { pickDate(startMs) { startMs = it } },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("起点", fontSize = 14.5.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Text(
                    formatDateOnly(startMs),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(5.dp))
                Icon(Icons.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))

            Row(
                Modifier.fillMaxWidth().height(52.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("进行中", fontSize = 14.5.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = ongoing,
                    onCheckedChange = { ongoing = it; if (it) endMs = null },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    )
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))

            if (!ongoing) {
                Row(
                    Modifier.fillMaxWidth().height(50.dp)
                        .clickable { pickDate(endMs ?: startMs) { endMs = it } },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("终点", fontSize = 14.5.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    Text(
                        endMs?.let { formatDateOnly(it) } ?: "未选择",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
            }

            if (title.contains("表盘")) {
                Text(
                    "表盘是一个时间段的外框，落在它里面的刻度会归到它下面。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
        }
    }
}
