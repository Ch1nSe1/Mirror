package memore.app.ui.screens

import memore.app.ui.theme.MemoreType
import memore.app.ui.components.MemorePage
import memore.app.ui.components.MirrorStackDialog
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import memore.app.core.ai.AiProtocol
import memore.app.core.ai.ApiPreset
import memore.app.core.ai.ApiPresetStore
import memore.app.ui.components.MirrorShapes
import memore.app.utils.UiSoundPlayer
import memore.app.utils.memoreToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 配置（2026-10-23 从设置页内联块搬到独立页）。
 *
 * 为什么独立：这一块是**低频、高风险**的设置（填错全部 AI 功能失效），
 * 内联在设置页里既挤，也没有地方放"到底通不通"。
 *
 * 版面按方案 C：模式页签 → 端点 / 模型 / Key → 预设 / 思考 → 底部「保存并测试」。
 * 改动即时落盘（和设置页其它项一致），按钮只负责测连通性。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AiConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences("mirror_prefs", Context.MODE_PRIVATE) }

    var mode by remember { mutableStateOf(prefs.getString("ai_mode", "local") ?: "local") }
    var endpoint by remember { mutableStateOf(prefs.getString("ai_endpoint", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("ai_model", "") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("ai_api_key", "") ?: "") }
    var thinking by remember { mutableStateOf(prefs.getBoolean("ai_thinking", true)) }
    var strength by remember { mutableStateOf(prefs.getString("ai_thinking_strength", "high") ?: "high") }
    var selectedPreset by remember { mutableStateOf(prefs.getString("ai_selected_preset", "") ?: "") }
    var presets by remember { mutableStateOf(ApiPresetStore.load(context)) }
    var showApiKey by remember { mutableStateOf(false) }
    var presetOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ApiPreset?>(null) }
    var deleteMode by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newEndpoint by remember { mutableStateOf("") }
    var newModel by remember { mutableStateOf("") }
    var newKey by remember { mutableStateOf("") }
    var strengthOpen by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }

    fun saveEndpoint(v: String) {
        endpoint = v
        prefs.edit().putString("ai_endpoint", v).apply()
    }

    fun saveModel(v: String) {
        model = v
        prefs.edit().putString("ai_model", v).apply()
    }

    fun saveApiKey(v: String) {
        apiKey = v
        prefs.edit().putString("ai_api_key", v).apply()
    }

    /** 切换模式：离开的模式存进自己的槽位，进入的模式读回来并写回共享 pref。 */
    fun switchMode(target: String) {
        if (mode == target) return
        if (mode == "local") {
            prefs.edit().putString("ai_local_endpoint", endpoint).putString("ai_local_model", model).apply()
        } else {
            prefs.edit().putString("ai_api_endpoint", endpoint).putString("ai_api_model", model).apply()
        }
        if (target == "local") {
            endpoint = prefs.getString("ai_local_endpoint", "") ?: ""
            model = prefs.getString("ai_local_model", "") ?: ""
            selectedPreset = ""
            prefs.edit().putString("ai_endpoint", endpoint).putString("ai_model", model)
                .putString("ai_selected_preset", "").apply()
        } else {
            endpoint = prefs.getString("ai_api_endpoint", "") ?: ""
            model = prefs.getString("ai_api_model", "") ?: ""
            prefs.edit().putString("ai_endpoint", endpoint).putString("ai_model", model).apply()
        }
        mode = target
        prefs.edit().putString("ai_mode", target).apply()
        testResult = null
    }

    fun applyPreset(p: ApiPreset) {
        endpoint = p.endpoint
        model = p.model
        apiKey = p.apiKey
        strength = p.reasoningEffort
        selectedPreset = p.name
        prefs.edit()
            .putString("ai_endpoint", p.endpoint).putString("ai_model", p.model)
            .putString("ai_api_key", p.apiKey).putString("ai_selected_preset", p.name)
            .putString("ai_thinking_strength", p.reasoningEffort)
            .putString("ai_thinking_control", p.thinkingControl)
            .apply()
        AiProtocol.setThinkingStrength(p.reasoningEffort)
        AiProtocol.setThinkingControl(p.thinkingControl)
        UiSoundPlayer.playApiPresetSwitch(context)
        testResult = null
    }

    fun saveThinking(v: Boolean) {
        thinking = v
        prefs.edit().putBoolean("ai_thinking", v).apply()
        AiProtocol.setEnableThinking(v)
        if (v) UiSoundPlayer.playThinkingOn(context) else UiSoundPlayer.playThinkingOff(context)
    }

    fun saveStrength(v: String) {
        strength = v
        prefs.edit().putString("ai_thinking_strength", v).apply()
        AiProtocol.setThinkingStrength(v)
    }

    fun runTest() {
        testing = true
        testResult = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (mode == "local") {
                        val url = URL(AiProtocol.healthUrl(endpoint))
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 4000
                            readTimeout = 4000
                        }
                        val code = conn.responseCode
                        conn.disconnect()
                        if (code in 200..299) "本地服务在线" to true else "本地服务返回 $code" to false
                    } else {
                        val url = URL(AiProtocol.completionUrl(endpoint))
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            connectTimeout = 8000
                            readTimeout = 8000
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json")
                        }
                        AiProtocol.applyAuth(conn, apiKey)
                        val body = """{"model":"$model","messages":[{"role":"user","content":"ping"}],"max_tokens":1}"""
                        conn.outputStream.use { it.write(body.toByteArray()) }
                        val code = conn.responseCode
                        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                            ?.bufferedReader()?.use { it.readText() }?.take(120).orEmpty()
                        conn.disconnect()
                        if (code in 200..299) "连接正常（$model）" to true else "返回 $code：$text" to false
                    }
                }.getOrElse { (it.message ?: "连接失败") to false }
            }
            testResult = r.first
            testOk = r.second
            testing = false
        }
    }

    // 删除预设：二次确认
    pendingDelete?.let { p ->
        memore.app.ui.components.MirrorStackDialog(
            title = "删除预设",
            message = "确认删除「${p.name}」？删掉后要重新填一遍端点、模型和 Key。",
            confirmText = "删除",
            destructive = true,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                presets = presets.filter { it.name != p.name }
                ApiPresetStore.save(context, presets)
                if (selectedPreset == p.name) {
                    selectedPreset = ""
                    prefs.edit().putString("ai_selected_preset", "").apply()
                }
                UiSoundPlayer.playDelete(context)
                pendingDelete = null
            }
        )
    }
    MemorePage(title = "AI 配置", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ---- 模式页签（2026-11-22）：跟「关联」页同一套：
            // 上下各一条 0.28 发丝线，中间一条 1dp×18dp 竖线，竖线上下留空。
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)))
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("local" to "本地", "api" to "API").forEachIndexed { idx, (value, label) ->
                    if (idx > 0) {
                        Box(
                            Modifier.width(1.dp).height(18.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f))
                        )
                    }
                    val on = mode == value
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clickable { switchMode(value) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontSize = 13.sp,
                            color = if (on) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)))
            Spacer(Modifier.height(16.dp))

        // ---- 服务（A 版：三条横线，2026-11-06；2026-11-22 统一行距）----
        Text("服务", style = memore.app.ui.theme.MemoreType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
        memore.app.ui.components.MirrorLineField(
            value = endpoint,
            onValueChange = { saveEndpoint(it); testResult = null },
            placeholder = "https://…",
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri
        )
        memore.app.ui.components.MirrorLineField(
            value = model,
            onValueChange = { saveModel(it); testResult = null },
            placeholder = "模型名称"
        )
        if (mode == "api") {
            memore.app.ui.components.MirrorLineField(
                value = apiKey,
                onValueChange = { saveApiKey(it); testResult = null },
                placeholder = "API Key",
                password = !showApiKey,
                trailing = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            if (showApiKey) "隐藏 Key" else "显示 Key",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }

            Spacer(Modifier.height(18.dp))

            // ---- 预设（仅 API）----
            // 2026-11-22：这里原来有一条 AiRule，紧挨着 Key 输入框自己的底线，
            // 真机上看起来是「两条线夹一条空白」。去掉，只靠留白分组。
            if (mode == "api") {
                AiRow(
                    label = "预设",
                    value = selectedPreset.ifBlank { "未选" },
                    expanded = presetOpen,
                    onClick = {
                        strengthOpen = false
                        presetOpen = !presetOpen
                    }
                )
                if (presetOpen) {
                    AiPickerCard {
                        // 删除模式提示行
                        if (deleteMode) {
                            AiPickRow(
                                name = "点 ✕ 删除预设",
                                picked = false,
                                muted = true,
                                trailing = {
                                    Text(
                                        "完成",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                },
                                onClick = { deleteMode = false },
                                onLongClick = null
                            )
                            AiPickDivider()
                        }
                        presets.forEachIndexed { idx, p ->
                            AiPickRow(
                                name = p.name,
                                picked = p.name == selectedPreset,
                                onClick = {
                                    if (deleteMode) {
                                        deleteMode = false
                                    } else {
                                        applyPreset(p)
                                        presetOpen = false
                                    }
                                },
                                onLongClick = {
                                    deleteMode = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    context.memoreToast("长按进入删除模式，点 ✕ 删除", Toast.LENGTH_SHORT)
                                },
                                trailing = if (deleteMode) {
                                    {
                                        Text(
                                            "✕",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.clickable { pendingDelete = p }
                                        )
                                    }
                                } else null
                            )
                            if (idx < presets.lastIndex) AiPickDivider()
                        }
                        if (presets.isNotEmpty()) AiPickDivider()
                        AiPickRow(
                            name = if (creating) "取消新建" else "＋ 新建预设",
                            picked = false,
                            muted = true,
                            primary = !creating,
                            onClick = { creating = !creating },
                            onLongClick = null
                        )
                        if (creating) {
                            AiPickDivider()
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                AiField("预设名称", newName) { newName = it }
                                AiField("https://…", newEndpoint) { newEndpoint = it }
                                AiField("模型名称", newModel) { newModel = it }
                                AiField("可留空", newKey) { newKey = it }
                                Row(
                                    Modifier.fillMaxWidth().height(44.dp).clickable {
                                        val nm = newName.trim()
                                        if (nm.isBlank() || newEndpoint.isBlank()) {
                                            context.memoreToast("名称和端点不能为空", Toast.LENGTH_SHORT)
                                        } else {
                                            val np = ApiPreset(
                                                name = nm,
                                                endpoint = newEndpoint.trim(),
                                                model = newModel.trim(),
                                                apiKey = newKey.trim()
                                            )
                                            presets = presets + np
                                            ApiPresetStore.save(context, presets)
                                            applyPreset(np)
                                            newName = ""; newEndpoint = ""; newModel = ""; newKey = ""
                                            creating = false
                                            presetOpen = false
                                        }
                                    },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "保存这个预设",
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            // ---- 思考 ----
            AiRule()
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("思考", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = thinking,
                    onCheckedChange = { saveThinking(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    )
                )
            }
            if (mode == "api" && thinking) {
                AiRule()
                AiRow(
                    label = "思考强度",
                    value = if (strength == "max") "最高" else "高",
                    expanded = strengthOpen,
                    onClick = {
                        presetOpen = false
                        strengthOpen = !strengthOpen
                    }
                )
                if (strengthOpen) {
                    AiPickerCard {
                        val options = listOf("high" to "高", "max" to "最高")
                        options.forEachIndexed { idx, (value, label) ->
                            AiPickRow(
                                name = label,
                                picked = strength == value,
                                onClick = {
                                    saveStrength(value)
                                    strengthOpen = false
                                },
                                onLongClick = null
                            )
                            if (idx < options.lastIndex) AiPickDivider()
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            // ---- 测试结果 ----
            testResult?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = if (testOk) Color(0xFF6FAE8F) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { runTest() },
                enabled = !testing && endpoint.isNotBlank() && model.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MirrorShapes.card,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    if (testing) "测试中…" else "测试",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun AiField(label: String, value: String, onChange: (String) -> Unit) {
    memore.app.ui.components.MirrorLineField(value = value, onValueChange = onChange, placeholder = label, label = label)
}

/** 行：52 高；展开型用 ⌄/⌃，进入型才用 ›。 */
@Composable
private fun AiRow(
    label: String,
    value: String,
    expanded: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 行间发丝线：画在下一行上方，展开内容属于上一行时线不会被夹在中间。 */
@Composable
private fun AiRule() {
    Box(
        Modifier.fillMaxWidth().height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    )
}

/**
 * 2026-11-22：选择面板 = 一张小卡（12 圆角，surfaceContainerHigh，1dp 描边）。
 * 参考导出页「来源」的浮层：行 48、分隔线左 16 右 8、选中有勾。
 */
@Composable
private fun AiPickerCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(MirrorShapes.menu)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                MirrorShapes.menu
            )
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiPickRow(
    name: String,
    picked: Boolean,
    muted: Boolean = false,
    primary: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick ?: {})
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            fontSize = 14.sp,
            fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                primary -> MaterialTheme.colorScheme.primary
                muted -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        if (picked) {
            // 2026-11-22：勾统一用文本「✓」（和导出页/主题选择一致），不用 Material 实心图标。
            Text(
                "✓",
                style = MemoreType.body,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(16.dp)
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun AiPickDivider() {
    Box(
        Modifier.fillMaxWidth().height(1.dp).padding(start = 16.dp, end = 8.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    )
}

