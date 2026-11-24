package memore.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import memore.app.MemoreApp
import memore.app.data.agent.MirrorActionService
import memore.app.data.model.Record
import memore.app.ui.components.MemorePage
import memore.app.ui.components.mirrorHairlineColor
import memore.app.ui.theme.MemoreType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 相关记录（2026-11-15）。
 *
 * 从「信」里某一个动作点「相关记录」进来：**按动作分组**摊开它到底动了哪几条。
 * 为什么单独一屏：信本身要能一眼扫完待办，把原文摊在主列表里会把信撑成一堵墙。
 */
@Composable
fun ActionRecordsScreen(
    context: Context,
    actionId: String,
    onBack: () -> Unit
) {
    val app = context.applicationContext as MemoreApp
    val service = remember { MirrorActionService(app) }
    var title by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<Pair<String, List<Record>>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(actionId) {
        val (loadedTitle, steps) = withContext(Dispatchers.IO) {
            service.titleOf(actionId) to service.stepsForId(actionId)
        }
        title = loadedTitle
        val ids = steps.flatMap { it.recordIds }.distinct()
        val records = withContext(Dispatchers.IO) {
            runCatching { app.container.recordRepository.getRecordsByIds(ids) }.getOrDefault(emptyList())
        }.associateBy { it.id }
        groups = steps
            .filter { it.recordIds.isNotEmpty() }
            .map { step -> step.label to step.recordIds.mapNotNull { records[it] } }
            .filter { it.second.isNotEmpty() }
        loading = false
    }

    MemorePage(title = "相关记录", onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))
            Text(title.ifBlank { "这个动作" }, style = MemoreType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))

            if (!loading && groups.isEmpty()) {
                Text("没有相关记录", style = MemoreType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            groups.forEach { (label, records) ->
                Text(label, style = MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                records.forEach { r ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(
                            formatLetterDate(r.createdAt),
                            style = MemoreType.micro,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            r.content.replace(Regex("\\s+"), " ").take(120),
                            style = MemoreType.body,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Box(Modifier.fillMaxWidth().padding(end = 0.dp).height(1.dp).background(mirrorHairlineColor()))
                }
                Spacer(Modifier.height(28.dp))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun formatLetterDate(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
