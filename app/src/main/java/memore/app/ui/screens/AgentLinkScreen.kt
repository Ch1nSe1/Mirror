package memore.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import memore.app.MemoreApp
import memore.app.service.AgentCommandBus
import memore.app.service.AgentCommandNotifier
import memore.app.service.AgentServer
import memore.app.service.AgentServerService
import memore.app.service.AgentServerState
import memore.app.ui.components.MemoreLineInput
import memore.app.ui.components.MemorePage
import memore.app.ui.components.MirrorAlertDialog
import memore.app.ui.components.MirrorAnchoredMenu
import memore.app.ui.components.MirrorMenuAction
import memore.app.ui.components.MirrorShapes
import memore.app.ui.theme.MemoreType
import memore.app.utils.memoreToast
import kotlinx.coroutines.delay

/**
 * Agent 连接（2026-11-18 按「清单页」重排）。
 *
 * 这一页只回答一个问题：**电脑现在能不能连上我。**
 *
 *   · 抬头：状态点 +「运行中 / 已停止」，右端只有一个读数（运行时长）。
 *   · 「服务」：服务开关、写入模式、待批件数（点进「信」）。
 *   · 「这台手机」：本机连接串、令牌——每条右侧 ⋯，动作在 ⋯ 里。
 *   · 「已授权连接」：点行就地展开，能撤销某台电脑的访问权。
 *
 * 两条被返工过的硬规矩：
 *   1. 不写解释小字：没有「当前可用 / 复制或重置」这类副行，状态只有抬头那一个点。
 *   2. 空就是空：没配端点就什么都不显示，不写「未设置」。
 */
private const val PREFS = "memore_prefs"
private const val KEY_TOKEN = "agent_token"

@Composable
fun AgentLinkScreen(
    context: Context,
    onOpenLetter: () -> Unit,
    onOpenTutorial: (String) -> Unit = {},
    onBack: () -> Unit
) {
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var running by remember { mutableStateOf(AgentServerState.running) }
    var uptime by remember { mutableStateOf(0L) }
    var lastClientKind by remember { mutableStateOf(AgentServerState.lastClientKind) }
    var lastClientAt by remember { mutableStateOf(AgentServerState.lastClientAt) }
    var pendingDevices by remember { mutableStateOf<Set<String>>(emptySet()) }
    var approved by remember { mutableStateOf(AgentServerState.approvedDevices.toSet()) }
    var approvedOpen by remember { mutableStateOf(false) }
    var confirmingRevoke by remember { mutableStateOf<String?>(null) }
    var lastClientName by remember { mutableStateOf(AgentServerState.lastClientName) }
    var token by remember { mutableStateOf(prefs.getString(KEY_TOKEN, "") ?: "") }
    var error by remember { mutableStateOf(AgentServerState.lastError) }
    val bus = remember { AgentCommandBus(context.applicationContext as MemoreApp) }
    val actionService = remember {
        memore.app.data.agent.MirrorActionService(context.applicationContext as MemoreApp)
    }
    var writeMode by remember { mutableStateOf(bus.mode()) }
    var pendingCount by remember { mutableStateOf(0) }
    var modeMenu by remember { mutableStateOf(false) }
    var tokenMenu by remember { mutableStateOf(false) }
    var linkMenu by remember { mutableStateOf<Int?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    // 这台手机现在能被连到的地址（WLAN / 热点 / Tailscale），一条一行。
    // 只列真实存在的接口：拔了网线、关了 Wi-Fi，那一行就消失，不留空壳。
    val localAddrs = remember { localAddresses() }

    val notifyPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也能用：信里照样看得到，只是没有系统通知 */ }

    LaunchedEffect(Unit) {
        while (true) {
            running = AgentServerState.running
            uptime = if (running) (System.currentTimeMillis() - AgentServerState.startedAt) / 1000 else 0L
            lastClientKind = AgentServerState.lastClientKind
            lastClientAt = AgentServerState.lastClientAt
            pendingDevices = AgentServerState.pendingDevices.toSet()
            lastClientName = AgentServerState.lastClientName
            approved = AgentServerState.approvedDevices.toSet()
            error = AgentServerState.lastError
            writeMode = bus.mode()
            pendingCount = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    actionService.pendingActions().size
                }
            }.getOrDefault(0)
            delay(1000)
        }
    }

    fun setRunning(on: Boolean) {
        if (on) {
            prefs.edit().putBoolean("agent_should_run", true).apply()
            AgentServerService.start(context)
            if (token.isBlank()) {
                token = AgentServer.newToken()
                prefs.edit().putString(KEY_TOKEN, token).apply()
            }
            val needsNotifyPermission =
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                    !AgentCommandNotifier.canNotify(context)
            if (needsNotifyPermission) {
                notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            AgentServerService.stop(context)
        }
    }

    fun copy(text: String, label: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun linkFor(ip: String): String = "memore://$ip:${AgentServerState.PORT}?token=$token"

    MemorePage(
        title = "Agent 连接",
        onBack = onBack,
        actions = {
            IconButton(onClick = { onOpenTutorial("Agent 连接") }) {
                Icon(
                    Icons.Outlined.HelpOutline,
                    contentDescription = "使用教程",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(14.dp))

            // 抬头三态（2026-11-18）：只说「有没有人连着、连的是谁」，不再说「运行中」。
            //   已停止 / 等待连接 / 已连接 · <电脑名>；右端是路线（已连接）或运行时长（等待）。
            val connected = running && lastClientAt > 0 &&
                System.currentTimeMillis() - lastClientAt < 60_000
            val statusWord = when {
                !running -> "已停止"
                connected -> "已连接"
                else -> "等待连接"
            }
            val statusColor = when {
                !running -> MaterialTheme.colorScheme.error
                connected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (connected && lastClientName.isNotBlank()) "$statusWord · $lastClientName" else statusWord,
                    style = MemoreType.meta,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                when {
                    connected -> Text(
                        lastClientKind,
                        style = MemoreType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    running -> Text(
                        formatUptime(uptime),
                        style = MemoreType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MemoreType.label, color = MaterialTheme.colorScheme.error)
            }

            // ── 服务
            Spacer(Modifier.height(22.dp))
            GroupLine()
            GroupLabel("服务")
            Row(
                Modifier.fillMaxWidth().height(50.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("服务", style = MemoreType.body, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = running,
                    onCheckedChange = { setRunning(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
            RowDivider()
            // 最近一次谁连进来：只报「时间 + 类别」。出现「公网」= 库正被外网访问。
            Row(
                Modifier.fillMaxWidth().height(50.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("最近连接", style = MemoreType.body, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Text(
                    if (lastClientAt <= 0L) "—" else formatClock(lastClientAt) + " · " + lastClientKind,
                    style = MemoreType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            RowDivider()
            // 有电脑第一次连进来：显示「请求访问 + 地址 + 允许」。
            // 点允许后写进 prefs，下次不再问；令牌仍然可用的老客户端不受影响。
            pendingDevices.forEach { ip ->
                val pendingName = AgentServerState.pendingNames[ip].orEmpty()
                Row(
                    Modifier.fillMaxWidth().height(50.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("请求访问", style = MemoreType.body, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (pendingName.isBlank()) ip else pendingName,
                        style = MemoreType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "允许",
                        style = MemoreType.meta,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            AgentServerState.approve(prefs, ip, pendingName)
                            pendingDevices = AgentServerState.pendingDevices.toSet()
            lastClientName = AgentServerState.lastClientName
                        }
                    )
                }
                RowDivider()
            }
            MarkerRow(
                label = "写入",
                value = when (writeMode) {
                    AgentCommandBus.Mode.READ_ONLY -> "只读"
                    AgentCommandBus.Mode.AUTO -> "自动"
                    else -> "批准"
                },
                marker = {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "选择写入模式",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                },
                expanded = modeMenu,
                onExpandedChange = { modeMenu = it },
                actions = listOf(
                    MirrorMenuAction("只读", selected = writeMode == AgentCommandBus.Mode.READ_ONLY) {
                        bus.setMode(AgentCommandBus.Mode.READ_ONLY)
                        writeMode = AgentCommandBus.Mode.READ_ONLY
                        modeMenu = false
                    },
                    MirrorMenuAction("批准", selected = writeMode == AgentCommandBus.Mode.APPROVE) {
                        bus.setMode(AgentCommandBus.Mode.APPROVE)
                        writeMode = AgentCommandBus.Mode.APPROVE
                        modeMenu = false
                    },
                    MirrorMenuAction("自动", selected = writeMode == AgentCommandBus.Mode.AUTO) {
                        bus.setMode(AgentCommandBus.Mode.AUTO)
                        writeMode = AgentCommandBus.Mode.AUTO
                        modeMenu = false
                    }
                )
            )
            RowDivider()
            MarkerRow(
                label = "待批",
                value = if (pendingCount > 0) "$pendingCount 件" else null,
                marker = {
                    Icon(
                        Icons.Outlined.KeyboardArrowRight,
                        contentDescription = "待批记录",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                expanded = false,
                onExpandedChange = { if (it) onOpenLetter() },
                actions = emptyList()
            )

            // ── 这台手机
            Spacer(Modifier.height(18.dp))
            GroupLine()
            GroupLabel("这台手机")
            // 一条地址一行：WLAN / 热点 / Tailscale。没有的接口不出现。
            localAddrs.forEachIndexed { index, local ->
                EndpointRow(
                    title = local.kind,
                    url = "memore://${local.ip}:${AgentServerState.PORT}",
                    expanded = linkMenu == index,
                    onExpandedChange = { open -> linkMenu = if (open) index else null },
                    actions = listOf(
                        // 只留连接串（2026-11-18）：插件已经能自己发现地址，
                        // 「复制地址」没有消费方了，删掉；连接串对第三方客户端仍是唯一入口。
                        MirrorMenuAction("复制连接串") {
                            copy(linkFor(local.ip), "memore agent")
                            context.memoreToast("已复制，粘到 DSH 插件配置里")
                            linkMenu = null
                        }
                    )
                )
                if (index < localAddrs.lastIndex) RowDivider()
            }
            RowDivider()
            EndpointRow(
                title = "令牌",
                url = if (token.isBlank()) "" else token.takeLast(4),
                expanded = tokenMenu,
                onExpandedChange = { tokenMenu = it },
                actions = buildList {
                    add(MirrorMenuAction("复制") {
                        copy(token, "memore token")
                        context.memoreToast("令牌已复制")
                        tokenMenu = false
                    })
                    add(MirrorMenuAction("重置", danger = true) {
                        tokenMenu = false
                        confirmReset = true
                    })
                }
            )

            // ── 已授权连接（2026-11-18）：点这一行就地展开，不弹窗、不进新页。
            //    每台电脑一行，右端淡红删除图标；点删除先在本行内确认一次。
            Spacer(Modifier.height(18.dp))
            GroupLine()
            Row(
                Modifier.fillMaxWidth().height(50.dp).clickable { approvedOpen = !approvedOpen },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("已授权连接", style = MemoreType.body, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Text(
                    if (approved.isEmpty()) "" else "${approved.size} 台",
                    style = MemoreType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (approvedOpen) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowRight,
                    contentDescription = if (approvedOpen) "收起" else "展开",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            RowDivider()
            if (approvedOpen) {
                (approved + AgentServerState.approvedNames).sorted().forEach { ip ->
                    if (confirmingRevoke == ip) {
                        Row(
                            Modifier.fillMaxWidth().height(50.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(ip, style = MemoreType.body, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "删除",
                                style = MemoreType.meta,
                                color = Color(0xFFC0554D),
                                modifier = Modifier.clickable {
                                    AgentServerState.revoke(prefs, ip)
                                    confirmingRevoke = null
                                    approved = AgentServerState.approvedDevices.toSet()
                                }
                            )
                            Spacer(Modifier.width(18.dp))
                            Text(
                                "取消",
                                style = MemoreType.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { confirmingRevoke = null }
                            )
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().height(50.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(ip, style = MemoreType.body, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.weight(1f))
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = "撤销这台电脑",
                                modifier = Modifier.size(20.dp).clickable { confirmingRevoke = ip },
                                tint = Color(0xFFC0554D).copy(alpha = 0.85f)
                            )
                        }
                    }
                    RowDivider()
                }
            }
        }
    }

    if (confirmReset) {
        MirrorAlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("重置令牌？", style = MaterialTheme.typography.headlineSmall) },
            text = { Text("已配好的客户端会全部失效。", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    val next = AgentServer.newToken()
                    token = next
                    prefs.edit().putString(KEY_TOKEN, next).apply()
                    confirmReset = false
                }) { Text("重置", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }
}

/**
 * 「标题 + 值 + 记号」一行。记号是真图标，**菜单锚在记号自己身上**（法条 4.9：
 * 你点的那个控件才是锚点，不是整行——锚在行上会从行首弹出来）。
 */
@Composable
private fun MarkerRow(
    label: String,
    value: String?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    actions: List<MirrorMenuAction>,
    marker: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(50.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MemoreType.body, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        if (!value.isNullOrBlank()) {
            Text(
                value,
                style = MemoreType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(6.dp))
        }
        if (actions.isEmpty()) {
            Box(Modifier.clickable { onExpandedChange(true) }) { marker() }
        } else {
            MirrorAnchoredMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                actions = actions
            ) {
                Box(Modifier.clickable { onExpandedChange(true) }) { marker() }
            }
        }
    }
}

/** 清单式条目：标题 + 地址，右侧 ⋯。地址为空就只显示标题。 */
@Composable
private fun EndpointRow(
    title: String,
    url: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    actions: List<MirrorMenuAction>,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier.weight(1f).clickable { onExpandedChange(true) }
        ) {
            Text(title, style = MemoreType.body, color = MaterialTheme.colorScheme.onSurface)
            if (url.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    url,
                    style = MemoreType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // 锚点 = 这个 ⋯ 按钮本身（法条 4.9）。挂在整行上，菜单会从行首弹出来。
        MirrorAnchoredMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            actions = actions
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(MirrorShapes.row)
                    .clickable { onExpandedChange(true) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.MoreHoriz,
                    contentDescription = "更多",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(text, style = MemoreType.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(2.dp))
}

/** 组与组之间：中线 0.18。 */
@Composable
private fun GroupLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)))
}

/** 组内相邻两行：弱线 0.10。 */
@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)))
}

private fun formatUptime(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"
    val m = seconds / 60
    if (m < 60) return "${m} 分钟"
    val h = m / 60
    return "${h} 小时 ${m % 60} 分"
}

/** 最近连接的时间戳 → `HH:mm`（只到分钟，够判断新旧）。 */
private fun formatClock(at: Long): String {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = at
    return String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}

/** 一个可被连到的本机地址：kind 是给人看的名字（WLAN / 热点 / Tailscale），ip 是拼串用的。 */
private data class LocalAddress(val kind: String, val ip: String)

/**
 * 这台手机现在能被连到的 IPv4（2026-11-18 改成列表）。
 *
 * 以前只挑一个：Wi-Fi 优先，Tailscale 被排在最后永远不显示——结果是走组网时
 * App 里拿不到那条串，只能有人去手改配置文件。现在一条一行。
 *
 * 只列**确认可达**的两类网卡：`wlan*`（含热点用的 wlan1）与 `tun*`/`tailscale*`。
 * 其余接口（`ap0`、`eth0`、`rmnet*`、厂商虚拟网卡）一律不列——实测这台机器上
 * 它们会给出 192.168.24.50 / 10.63.x.x 这种从电脑根本连不到的地址，
 * 列出来只是让人白试一次。蜂窝同理：运营商 NAT 后面，写了也连不上。
 */
private fun localAddresses(): List<LocalAddress> = try {
    fun kindOf(name: String): String? = when {
        name.startsWith("wlan") -> "WLAN"
        name.startsWith("tun") || name.startsWith("tailscale") -> "Tailscale"
        else -> null
    }
    java.net.NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { ni ->
            val kind = kindOf(ni.name) ?: return@flatMap emptySequence()
            ni.inetAddresses.asSequence()
                .filterIsInstance<java.net.Inet4Address>()
                .map { LocalAddress(kind, it.hostAddress ?: "") }
        }
        .filter { it.ip.isNotBlank() && !it.ip.startsWith("169.254.") }
        .distinctBy { it.ip }
        .toList()
} catch (_: Exception) {
    emptyList()
}
