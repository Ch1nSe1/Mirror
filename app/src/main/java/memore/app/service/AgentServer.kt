package memore.app.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import memore.app.MemoreApp
import memore.app.data.vector.VectorIndexService
import memore.app.data.vector.VectorMath
import memore.app.data.model.LightRecord
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ln

/**
 * Memore 的本地 HTTP 服务端（2026-11-13）。
 *
 * 为什么是"服务端"而不是"同步快照"：agent 操作电脑文件时读的就是当前磁盘，
 * 没有"同步"这一步。要让它对记忆也能"看、找、用"，App 就必须是**能回答问题的那个**，
 * 而不是定期上传一份副本——副本必然过期，而过期是看不见的错误。
 *
 * 第一版**只读**：/health、/records、/records/{id}、/tags、/origins。
 * 写回（POST /commands）留到下一步，因为写要过 repository 和批准机制。
 *
 * 零依赖：裸 ServerSocket + 线程池。项目禁新依赖，这里一千行也值。
 */
object AgentServerState {
    const val PORT = 8790

    @Volatile var running = false
    @Volatile var startedAt = 0L
    @Volatile var lastError: String? = null
    /** 端口绑定失败后的重试次数（成功绑定时清零）。 */
    @Volatile var bindAttempts = 0
    val clients = AtomicInteger(0)
    @Volatile var lastRequestAt = 0L
    @Volatile var requests = 0L
    /** mDNS 注册成功后的服务名（`Memore` 或 `Memore-2` 这类被系统改过的名字）；空 = 没在广播。 */
    @Volatile var mdnsName: String? = null
    /** 最近一次请求的来源类别：局域网 / Tailscale / 公网 / 本机；空 = 还没人连过。 */
    @Volatile var lastClientKind: String = ""
    /** 最近一次请求的时间（毫秒）；0 = 还没人连过。 */
    @Volatile var lastClientAt: Long = 0L
    /** 最近一次请求里带的电脑名（插件头 X-Memore-Client）；空 = 对方没带。 */
    @Volatile var lastClientName: String = ""

    /**
     * 已授权的电脑（2026-11-18）。
     *
     * 为什么不再靠令牌：令牌要人手粘一次，而「哪台电脑被允许」这件事
     * 手机本来就能判断——第一次连进来时让你点一下「允许」，之后记住这台机器。
     * 令牌仍然有效：带了正确令牌的请求直接放行，不需要点。
     */
    val approvedDevices: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 连进来但还没得到批准的地址（界面据此显示「待确认」）。 */
    val pendingDevices: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 待确认设备的电脑名（IP -> 名称），界面优先显示名字。 */
    val pendingNames: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    /**
     * 已授权的**设备名**（2026-11-20）。
     *
     * 为什么不能只记 IP：DHCP/热点重启会让同一台电脑换地址，用户就得重批一次；
     * 反过来，同一 IP 被别的设备占用时又会被错误放行。插件每次都带 X-Memore-Client，
     * 所以同时记住名字，换 IP 不用再问。
     */
    val approvedNames: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private const val PREF_APPROVED = "agent_approved_devices"
    private const val PREF_APPROVED_NAMES = "agent_approved_names"

    /** 服务启动时从 prefs 读回已授权列表。 */
    fun loadApproved(prefs: android.content.SharedPreferences) {
        approvedDevices.clear()
        approvedDevices.addAll(
            prefs.getString(PREF_APPROVED, "")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        )
        approvedNames.clear()
        approvedNames.addAll(
            prefs.getString(PREF_APPROVED_NAMES, "")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        )
    }

    /** 用户在界面上点了「允许」：记住这台机器，并写回 prefs。 */
    fun approve(prefs: android.content.SharedPreferences, ip: String, name: String = "") {
        val clean = ip.removePrefix("::ffff:").trim()
        val device = name.trim().take(60)
        if (clean.isNotBlank()) approvedDevices.add(clean)
        if (device.isNotBlank()) approvedNames.add(device)
        pendingDevices.remove(clean)
        pendingNames.remove(clean)
        prefs.edit()
            .putString(PREF_APPROVED, approvedDevices.joinToString(","))
            .putString(PREF_APPROVED_NAMES, approvedNames.joinToString(","))
            .apply()
    }

    /** 拒绝：这次不记，只把它从待确认里去掉。 */
    fun deny(ip: String) {
        pendingDevices.remove(ip.removePrefix("::ffff:").trim())
    }

    /** 撤销一台电脑的访问权（2026-11-18）：从已授权列表里删掉并写回 prefs。 */
    fun revoke(prefs: android.content.SharedPreferences, entry: String) {
        val clean = entry.removePrefix("::ffff:").trim()
        if (clean.isBlank()) return
        // 名字和 IP 都可能被撤：都试着删一遍，再写回。
        approvedDevices.remove(clean)
        approvedNames.remove(clean)
        prefs.edit()
            .putString(PREF_APPROVED, approvedDevices.joinToString(","))
            .putString(PREF_APPROVED_NAMES, approvedNames.joinToString(","))
            .apply()
    }

    /** IP 或设备名任一命中都算已授权（令牌命中的情况不经过这里）。 */
    fun isApproved(host: String?, name: String? = null): Boolean {
        val clean = (host ?: "").removePrefix("::ffff:").trim()
        if (clean.isNotBlank() && approvedDevices.contains(clean)) return true
        val device = (name ?: "").trim()
        return device.isNotBlank() && approvedNames.contains(device)
    }

    /** 把请求方 IPv4 归成三类——用户只需要知道「是不是外网进来了」。 */
    fun classifyClient(host: String?): String {
        val ip = (host ?: return "").removePrefix("::ffff:")
        val parts = ip.split(".")
        if (parts.size == 4) {
            val a = parts[0].toIntOrNull() ?: return ""
            val b = parts[1].toIntOrNull() ?: 0
            return when {
                a == 10 -> "局域网"
                a == 192 && b == 168 -> "局域网"
                a == 172 && b in 16..31 -> "局域网"
                a == 100 && b in 64..127 -> "Tailscale"
                a == 127 -> "本机"
                else -> "公网"
            }
        }
        // IPv6（隧道/运营商）一律按外网看待，避免漏报
        return if (ip.isBlank()) "" else "公网"
    }
}

class AgentServer(private val app: MemoreApp, private val port: Int = AgentServerState.PORT) {

    /**
     * 只保留"用户写的记录"（2026-11-15）。
     *
     * AI 札记是 agent 的产出，**和用户的记录先天隔离**：它只在自己的区域里出现，
     * 不进任何汇总（统计 / 摘要 / 建议 / 目录 / 关联推荐）。
     * 所以凡是要"从全库聚合"的地方，都必须过这一层过滤；
     * 明细接口（records / get / search）不过滤——札记在时间线上是看得见的。
     */
    private fun userLight(list: List<memore.app.data.model.LightRecord>) =
        list.filter { it.type != "AI_NOTE" }

    private val pool = Executors.newFixedThreadPool(4)
    /** 实际绑定的端口（8790 被占时可能是 8791…8794）。 */
    @Volatile private var activePort = port
    private var serverSocket: ServerSocket? = null

    @Volatile private var alive = false

    // mDNS 广播（2026-11-18）：让同一局域网里的电脑能自己找到这台手机，
    // 换 Wi-Fi 也不用重新粘连接串。注册失败不影响 HTTP 服务本身。
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    fun start() {
        if (alive) return
        // 2026-11-20 修：先 bind 成功再置 alive。
        // 原来先 alive=true 再 ServerSocket(port)，bind 抛异常（端口被占）后 alive 永远为真，
        // 之后每次 start() 都直接 return —— 前台服务还在、通知还在，但 8790 永远没监听，
        // 客户端只能看到 ECONNREFUSED，而且不重启进程就永远好不了。
        // 端口回退（2026-11-20）：8790 被旧包/别的程序占住时，往后试 4 个端口。
        // 实际端口写进 mDNS SRV，插件靠 mDNS 自动跟过来；/health 也报真实端口。
        var bound: ServerSocket? = null
        var usedPort = port
        var lastErr: Exception? = null
        for (p in port..(port + 4)) {
            try {
                bound = ServerSocket(p)
                usedPort = p
                break
            } catch (e: Exception) {
                lastErr = e
            }
        }
        val sock = bound
        if (sock == null) {
            alive = false
            AgentServerState.running = false
            AgentServerState.lastError =
                "端口 $port–${port + 4} 都被占用，连不上（可能是旧版本 App 还在跑）：${lastErr?.message ?: "未知"}"
            scheduleBindRetry()
            return
        }
        activePort = usedPort
        alive = true
        serverSocket = sock
        AgentServerState.running = true
        AgentServerState.bindAttempts = 0
        AgentServerState.startedAt = System.currentTimeMillis()
        AgentServerState.lastError = null
        // 读回「允许过哪些电脑」：重启后不该再问一遍。
        AgentServerState.loadApproved(app.preferences)
        registerMdns()

        Thread({
            while (alive) {
                val client = try {
                    sock.accept()
                } catch (e: Exception) {
                    break
                }
                AgentServerState.clients.incrementAndGet()
                pool.execute {
                    try {
                        handle(client)
                    } catch (e: Exception) {
                        AgentServerState.lastError = e.message
                    } finally {
                        AgentServerState.clients.decrementAndGet()
                        runCatching { client.close() }
                    }
                }
            }
        }, "memore-agent-accept").start()
    }

    /**
     * 端口占用时按 5 秒退避重试，最多 24 次（2 分钟）。
     * 超过就停手，把最后的错误留在界面上——不无限刷日志，也不假装还在跑。
     */
    private fun scheduleBindRetry() {
        if (retryScheduled) return
        retryScheduled = true
        Thread({
            try {
                while (!alive && AgentServerState.bindAttempts < MAX_BIND_ATTEMPTS) {
                    Thread.sleep(5_000)
                    if (alive) break
                    AgentServerState.bindAttempts += 1
                    start()
                }
                if (!alive) {
                    AgentServerState.lastError =
                        "端口 $port 连续 ${AgentServerState.bindAttempts} 次被占用，已停止重试"
                }
            } catch (_: InterruptedException) {
                // 服务被停止：什么都不做
            } finally {
                retryScheduled = false
            }
        }, "memore-agent-bind-retry").start()
    }

    @Volatile private var retryScheduled = false
    private val MAX_BIND_ATTEMPTS = 24

    fun stop() {
        alive = false
        AgentServerState.running = false
        unregisterMdns()
        releaseMulticastLock()
        runCatching { serverSocket?.close() }
        runCatching { pool.shutdownNow() }
    }

    /**
     * 在局域网里广播 `_memore._tcp`，供电脑侧的插件自动发现。
     *
     * 为什么不是先做「手填一次」再想办法：手填那条串里绑着 IP，换 Wi-Fi 就失效；
     * 广播之后电脑按服务名找，地址变了也能跟到。注册失败只记错误，不让服务起不来。
     */
    private fun registerMdns() {
        if (nsdListener != null) return
        val manager = app.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        // 组播锁（2026-11-18）：多数国产 ROM（含 MIUI）在省电状态下会过滤掉
        // 入站组播，不拿锁就等于“广播出去了，但收不到查询”，电脑永远扫不到。
        acquireMulticastLock()
        // 注意：apply 里写 `port = port` 会自赋值成 NsdServiceInfo 自己的 0 端口，
        // 注册时直接抛 IllegalArgumentException: Invalid port number（2026-11-18 踩到）。
        val advertisedPort = activePort
        val info = NsdServiceInfo().apply {
            serviceName = "Memore"
            serviceType = "_memore._tcp"
            port = advertisedPort
            runCatching {
                setAttribute("app", "memore")
                setAttribute("ver", "1")
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                AgentServerState.mdnsName = info.serviceName
                android.util.Log.i("MemoreAgent", "mDNS 注册成功: ${info.serviceName} ${info.serviceType}:${info.port}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                AgentServerState.lastError = "局域网广播注册失败（$errorCode）"
                android.util.Log.w("MemoreAgent", "mDNS 注册失败 code=$errorCode")
                // 注册失败常见于刚切网络/旧注册未释放：10 秒后自动再试一次。
                nsdListener = null
                nsdManager = null
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (alive) registerMdns()
                }, 10_000)
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                AgentServerState.mdnsName = null
                android.util.Log.i("MemoreAgent", "mDNS 已注销")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                AgentServerState.mdnsName = null
                android.util.Log.w("MemoreAgent", "mDNS 注销失败 code=$errorCode")
            }
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess {
                nsdManager = manager
                nsdListener = listener
                android.util.Log.i("MemoreAgent", "mDNS 注册请求已发出: $info")
            }
            .onFailure {
                android.util.Log.w("MemoreAgent", "mDNS 注册调用失败", it)
            }
    }

    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    @Suppress("DEPRECATION")
    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        runCatching {
            val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as? android.net.wifi.WifiManager ?: return
            multicastLock = wm.createMulticastLock("memore-agent-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
            android.util.Log.i("MemoreAgent", "组播锁已获取")
        }.onFailure {
            android.util.Log.w("MemoreAgent", "组播锁获取失败", it)
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        multicastLock = null
    }

    /**
     * 网络变化后重新广播（2026-11-20）。
     *
     * 原因：Wi-Fi 断开/重连后旧注册可能已经失效，而电脑那边 mDNS 是缓存 10 秒的，
     * 不重注册就会一直扫不到。先注销、1.5 秒后再注册（NSD 需要时间释放服务名）。
     */
    fun rebindMdns() {
        unregisterMdns()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (alive) registerMdns()
        }, 1500)
    }

    private fun unregisterMdns() {
        val manager = nsdManager
        val listener = nsdListener
        if (manager != null && listener != null) {
            runCatching { manager.unregisterService(listener) }
        }
        nsdManager = null
        nsdListener = null
        AgentServerState.mdnsName = null
    }

    // ---------------------------------------------------------------- 请求处理

    private fun handle(socket: Socket) {
        socket.soTimeout = 15000
        // 记下“谁连进来的”（2026-11-18）：界面只显示类别，不显示 IP 全文。
        runCatching {
            AgentServerState.lastClientKind =
                AgentServerState.classifyClient(socket.inetAddress?.hostAddress)
            AgentServerState.lastClientAt = System.currentTimeMillis()
        }
        val input = BufferedInputStream(socket.getInputStream())
        val head = readHead(input) ?: return
        val out = socket.getOutputStream()

        val lines = head.split("\r\n")
        // 电脑的名字（2026-11-18）：插件在请求头里带 X-Memore-Client，
        // 界面显示「已连接 · 设备名」，而不是只给一个 IP。
        runCatching {
            lines.firstOrNull { it.startsWith("X-Memore-Client:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.take(40)
                ?.takeIf { it.isNotBlank() }
                ?.let { AgentServerState.lastClientName = it }
        }
        val requestLine = lines.firstOrNull().orEmpty()
        val parts = requestLine.split(" ")
        if (parts.size < 2) return respond(out, 400, err("bad request"))

        val method = parts[0].uppercase(Locale.ROOT)
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")

        // 请求体：只有 POST /commands 用得到
        val contentLength = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val bodyText = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read, Charsets.UTF_8)
        } else ""

        val isCommandPost = method == "POST" && path == "/commands"
        // 2026-11-15：动作接口（agent 连接 v2）。POST 四个：提动作 / 批准 / 拒绝 / 撤回。
        val isActionPost = method == "POST" && (
            path == "/actions" ||
                ACTION_APPROVE_RE.matches(path) ||
                ACTION_REJECT_RE.matches(path) ||
                ACTION_UNDO_RE.matches(path)
            )
        if (method != "GET" && !isCommandPost && !isActionPost) {
            return respond(out, 405, err("只支持 GET，以及 POST /commands、/actions"))
        }

        val token = lines.firstOrNull { it.startsWith("X-Memore-Token:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
            ?: parseQuery(query)["token"]
        val clientName = lines.firstOrNull { it.startsWith("X-Memore-Client:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.take(60).orEmpty()
        // 授权（2026-11-20）：令牌对 → 放行；否则按 **IP 或设备名** 判断（换 IP 不用重批）。
        // 第一次连进来的电脑进 pending，界面显示「待确认」，点允许后把名字和 IP 一起写进 prefs。
        val clientHost = socket.inetAddress?.hostAddress
        if (!tokenMatches(token) && !AgentServerState.isApproved(clientHost, clientName)) {
            val ip = (clientHost ?: "").removePrefix("::ffff:").trim()
            if (ip.isNotBlank()) {
                val isNew = AgentServerState.pendingDevices.add(ip)
                if (clientName.isNotBlank()) AgentServerState.pendingNames[ip] = clientName
                // 2026-11-20：第一次被敲时发通知。只发一次（isNew），不然每次重试都响。
                if (isNew) {
                    runCatching {
                        AgentAccessNotifier.notifyPending(app, clientName.ifBlank { ip })
                    }
                }
            }
            return respond(out, 401, err("这台电脑还没被允许：去 App 的「Agent 连接」点允许", "awaiting_approval"))
        }
        // 已按 IP 放行的老客户端，第一次带着名字过来时把名字记下：
        // 这样它换了 IP（DHCP/热点重启）也不会再被拦一次。
        if (!tokenMatches(token) && clientName.isNotBlank() && !AgentServerState.approvedNames.contains(clientName)) {
            runCatching { AgentServerState.approve(app.preferences, clientHost.orEmpty(), clientName) }
        }

        // 限流（2026-11-14）：每 10 秒最多 40 次。agent 一轮里发几十个请求会把手机拖住。
        if (!RateLimiter.allow()) {
            audit(method, path, 429)
            return respond(out, 429, err("请求太密，稍后再试", "rate_limited"))
        }

        AgentServerState.requests += 1
        AgentServerState.lastRequestAt = System.currentTimeMillis()
        audit(method, path, 200)

        val q = parseQuery(query)
        // 2026-11-15：动作接口（POST）。三种：提动作 / 批准 / 拒绝。
        if (isActionPost) {
            // 2026-11-20：提议者 ≠ 批准者。批准/拒绝/撤回只在手机上点；
            // 网络端（含插件）即便有 token 也只能 POST /actions 提议，不能自己批准自己。
            if (path != "/actions" && !isLoopbackHost(socket.inetAddress?.hostAddress)) {
                return respond(out, 403, err("批准、拒绝、撤回只能在手机上操作", "local_only"))
            }
            val actions = memore.app.data.agent.MirrorActionService(app)
            if (path == "/actions") {
                val parsed = runCatching { JSONObject(bodyText) }
                    .getOrElse { return respond(out, 400, err("动作体不是合法 JSON")) }
                val verbs = parsed.optJSONArray("verbs") ?: return respond(out, 400, err("缺 verbs"))
                val result = runCatching {
                    actions.propose(
                        id = parsed.optString("action_id"),
                        title = parsed.optString("title"),
                        actor = parsed.optString("actor"),
                        verbs = verbs,
                        requiresConfirm = parsed.optBoolean("requires_confirm", false)
                    )
                }.getOrElse { e -> err("提交失败：" + (e.message ?: "未知")) }
                return respond(out, 200, result)
            }
            val id = ACTION_APPROVE_RE.find(path)?.groupValues?.get(1).orEmpty()
                .ifBlank { ACTION_REJECT_RE.find(path)?.groupValues?.get(1).orEmpty() }
                .ifBlank { ACTION_UNDO_RE.find(path)?.groupValues?.get(1).orEmpty() }
            // 二次确认：body {"confirmed":true} 或 ?confirm=1（2026-11-15）
            val confirmed = runCatching { JSONObject(bodyText).optBoolean("confirmed", false) }.getOrDefault(false) ||
                q["confirm"] == "1" || q["confirmed"] == "true"
            val result = runCatching {
                when {
                    path.endsWith("/approve") -> actions.approve(id, confirmed = confirmed)
                    path.endsWith("/reject") -> actions.reject(id)
                    else -> actions.undo(id)
                }
            }.getOrElse { e -> err("执行失败：" + (e.message ?: "未知")) }
            return respond(out, 200, result)
        }
        if (isCommandPost) {
            // 2026-11-15：**对外关闭裸命令入口**。
            // 理由：`kind` 是执行器的实现细节，暴露它等于让 agent 直接拼执行步骤，
            // 动作层的校验、二次确认、快照、撤回全都能被绕过。
            // 现在唯一的写入口是 POST /actions（一次动作 = 一组改动 + 一次批准 + 一次撤回）。
            return respond(
                out, 410,
                err("这个入口已下线，改用 POST /actions（verbs 见 /health 的 verbs 字段）", "gone")
            )
        }
        val body = when {
            path == "/commands" -> commands()
            // 2026-11-15：动作读取（v2）
            path == "/actions" -> {
                val lim = (q["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                val svc = memore.app.data.agent.MirrorActionService(app)
                if (q["status"] == "applied" || q["status"] == "recent") {
                    val recent = svc.recentActions(lim)
                    val arr = org.json.JSONArray()
                    recent.forEach { a -> svc.get(a.id)?.let { arr.put(it) } }
                    JSONObject().put("ok", true).put("schema_version", 1)
                        .put("total", recent.size).put("actions", arr)
                } else {
                    val all = svc.pending()
                    JSONObject().put("ok", true).put("schema_version", 1)
                        .put("total", all.size)
                        .put("pending", org.json.JSONArray(all.take(lim)))
                }
            }
            ACTION_ONE_RE.matches(path) -> {
                val id = path.removePrefix("/actions/")
                memore.app.data.agent.MirrorActionService(app).get(id)
                    ?: return respond(out, 404, err("没有这个动作: $id", "not_found"))
            }
            TICK_RECORDS_RE.matches(path) ->
                tickRecords(TICK_RECORDS_RE.find(path)?.groupValues?.get(1).orEmpty(), q)
            ATTACHMENT_CONTENT_RE.matches(path) ->
                attachmentContent(ATTACHMENT_CONTENT_RE.find(path)?.groupValues?.get(1)?.toLongOrNull() ?: -1L)
            path == "/prompts" -> prompts()
            path == "/similar" -> similar(q)
            path == "/events" -> events(q)
            path == "/health" -> health()
            // 2026-11-15：读端点补齐（agent 当"管理系统"用）
            path == "/changes" -> changes(q)
            path == "/anchors" -> anchors(q)
            path == "/anchors/summary" -> anchorsSummary()
            path == "/insights" -> insights(q)
            path == "/zones" -> zones(q)
            path == "/ticks" -> ticks(q)
            path == "/projection" -> projection(q)
            path == "/relations" -> relations(q)
            path == "/dials" -> dials(q)
            // 2026-11-15：补五个只读缺口（协议 / 附件索引 / 版本 / 设置 / 记忆节点）
            path == "/protocols" -> protocolsEndpoint()
            path == "/attachments" -> attachmentsEndpoint(q)
            path == "/versions" -> versionsEndpoint(q)
            path == "/settings" -> settingsEndpoint()
            // 2026-11-16：锁状态（v5 门闩）——UI 常驻提示与迁移判断都读它
            path == "/lock/state" -> lockStateEndpoint()
            path == "/search" -> search(q)
            path == "/drafts" -> drafts()
            path.startsWith("/drafts/") -> draftDetail(path.removePrefix("/drafts/").toLongOrNull() ?: -1L)
            path == "/audit" -> auditLog(q)
            path == "/stats" -> stats(q)
            path == "/digest" -> digest(q)
            path == "/suggest" -> suggest(q)
            path == "/facets" -> facets()
            path == "/records" -> records(q)
            RECORD_VERSIONS_RE.matches(path) ->
                recordVersions(RECORD_VERSIONS_RE.find(path)?.groupValues?.get(1).orEmpty())
            path.endsWith("/neighbors") && path.startsWith("/records/") ->
                neighbors(path.removePrefix("/records/").removeSuffix("/neighbors"))
            path.startsWith("/records/") -> recordById(path.removePrefix("/records/"))
            path == "/tags" -> namesOf("tags")
            path == "/origins" -> origins()
            else -> return respond(out, 404, err("没有这个接口: $path", "not_found"))
        }
        respond(out, 200, body)
    }

    private fun health(): JSONObject {
        val dao = app.container.database.recordDao()
        val light = runBlocking { dao.getAllActiveLight() }
        return JSONObject()
            .put("ok", true)
            .put("schema_version", 1)
            .put("ts", System.currentTimeMillis())
            .put("server", JSONObject()
                .put("app", "Memore")
                .put("uptime_s", ((System.currentTimeMillis() - AgentServerState.startedAt) / 1000))
                .put("requests", AgentServerState.requests)
                .put("port", activePort)
                .put("bound", AgentServerState.running)
                .put("bind_attempts", AgentServerState.bindAttempts)
                .put("last_error", AgentServerState.lastError ?: ""))
            .put("db", JSONObject()
                .put("records", light.size)
                .put("last_record_at", light.maxOfOrNull { it.createdAt } ?: 0L))
            // 2026-11-15：把"这套后端会什么"直接写在健康检查里。
            // agent 不该靠猜：支持哪些动词、上限多少，一次问清。
            .put("verbs", org.json.JSONArray(memore.app.data.agent.MirrorActionService.VERBS))
            .put("limits", JSONObject()
                .put("max_verbs_per_action", memore.app.data.agent.MirrorActionService.MAX_VERBS)
                .put("max_rows_per_action", memore.app.data.agent.MirrorActionService.MAX_SNAPSHOT_ROWS))
            // 2026-11-15：读端点清单与权限档位也写进来 —— agent 一次问清"能读什么"。
            .put("read", JSONObject()
                .put("endpoints", org.json.JSONArray(listOf(
                    "/records", "/records/{id}", "/changes", "/search", "/stats", "/digest",
                    
                    "/origins", "/tags", "/drafts", "/audit", "/zones", "/ticks", "/dials",
                    "/anchors", "/anchors/summary", "/insights", "/records/{id}/versions", "/projection", "/relations",
                    "/prompts", "/similar", "/ticks/{id}/records", "/attachments/{id}/content"
                )))
                .put("paging", "/records?cursor=<created_at>&limit=1..500&full=1")
                .put("delta", "/changes?since=<server_ts>")
                .put("scopes", org.json.JSONArray(scopes().sorted()))
                .put("locked_content", if (canLocked()) "allowed" else "never"))
            // 2026-11-20：把「动作」这一层也写进自描述。
            // 以前 agent 从 /health 只能看到 verbs，看不出还有"待批列表 / 单条详情 / 批准"这些路径。
            .put("actions", JSONObject()
                .put("submit", "POST /actions")
                .put("pending_list", "/actions")
                .put("detail", "/actions/{id}")
                .put("approve", "POST /actions/{id}/approve")
                .put("reject", "POST /actions/{id}/reject")
                .put("undo", "POST /actions/{id}/undo")
                .put("events", "GET /events?since=<ms>&wait=1..25")
                .put("max_verbs", memore.app.data.agent.MirrorActionService.MAX_VERBS))
            .put("policy", memore.app.data.agent.MirrorActionService.policy())
            .put("client", JSONObject()
                .put("min_version", "0.33")
                .put("identity_header", "X-Memore-Client")
                .put("token_header", "X-Memore-Token"))
    }

    private fun records(q: Map<String, String>): JSONObject {
        val dao = app.container.database.recordDao()
        val all = runBlocking { dao.getAllActiveLight() }
        val since = q["since"]?.toLongOrNull() ?: 0L
        // 2026-11-15：游标分页 + 全文。以前 offset 被忽略、正文截 400 字，
        // agent 翻不到 200 条以外的东西，长文还要逐条再取一次。
        val cursor = (q["cursor"] ?: q["before"])?.toLongOrNull()
        val limit = (q["limit"] ?: q["k"])?.toIntOrNull()?.coerceIn(1, 500) ?: 30
        val wantFull = q["full"] == "1" || q["full"] == "true"
        val include = q["include"].orEmpty()
        val canContent = can("read:content")
        val keyword = q["q"].orEmpty()

        // 2026-11-20：批量取（ids=a,b,c），避免 agent N+1。
        val idsParam = q["ids"]?.trim().orEmpty()
        if (idsParam.isNotBlank()) {
            val wanted = idsParam.split(',').map { it.trim() }.filter { it.isNotBlank() }.take(50).toSet()
            val picked = all.filter { it.id in wanted && (canLocked() || !it.isLocked) }
            val fullMap = if (canContent) runBlocking { dao.getAllActiveRecords() }.associateBy { it.id } else emptyMap()
            val arr = JSONArray()
            picked.forEach { r -> arr.put(if (canContent) fullJson(r, fullMap[r.id]) else metaJson(r)) }
            attachCountsInto(arr, picked.map { it.id })
            return JSONObject().put("ok", true).put("schema_version", 1)
                .put("returned", arr.length()).put("results", arr)
        }

        var rows = all.filter { (canLocked() || !it.isLocked) && it.createdAt > since }
        if (cursor != null && cursor > 0L) rows = rows.filter { it.createdAt < cursor }

        // 结构化过滤（2026-11-13）：按标签名 / 来源 / 类型 / 时间窗
        q["tag"]?.takeIf { it.isNotBlank() }?.let { tagName ->
            val ids = runBlocking { dao.recordIdsByTagName(tagName) }.toSet()
            rows = rows.filter { it.id in ids }
        }
        q["origin"]?.takeIf { it.isNotBlank() }?.let { origin ->
            val target = runBlocking { app.container.database.originDao().all() }
                .firstOrNull { it.id == origin || it.name == origin }
            if (target != null) rows = rows.filter { it.originId == target.id }
        }
        q["from"]?.toLongOrNull()?.let { from -> rows = rows.filter { it.createdAt >= from } }
        q["to"]?.toLongOrNull()?.let { to -> rows = rows.filter { it.createdAt <= to } }
        if (keyword.isNotBlank()) {
            val grams = bigrams(keyword)
            rows = rows.mapNotNull { r ->
                val score = score(grams, r.content + " " + r.notes) + if (r.content.contains(keyword)) 2.0 else 0.0
                if (score > 0.0) r to score else null
            }.sortedByDescending { it.second }.map { it.first }
        } else {
            rows = rows.sortedByDescending { it.createdAt }
        }

        val page = rows.take(limit)
        // 全文模式：一次性取全量表再按 id 映射，避免 N 次单条查询
        val fullMap = if (wantFull && canContent) {
            runBlocking { dao.getAllActiveRecords() }.associateBy { it.id }
        } else emptyMap()

        val arr = JSONArray()
        page.forEach { r ->
            arr.put(
                when {
                    !canContent -> metaJson(r)
                    wantFull -> fullJson(r, fullMap[r.id])
                    include == "meta" -> metaJson(r)
                    else -> lightJson(r)
                }
            )
        }
        attachCountsInto(arr, page.map { it.id })
        return JSONObject()
            .put("ok", true)
            .put("schema_version", 1)
            .put("total_scanned", all.size)
            .put("returned", arr.length())
            .put("next_cursor", page.lastOrNull()?.createdAt ?: JSONObject.NULL)
            .put("has_more", rows.size > limit)
            .put("results", arr)
    }

    /**
     * 增量同步（2026-11-15）。两种模式：
     *
     * · `?since=<ts>`：**时间戳模式**，只比 records 的时间列。快、但会漏掉
     *   "打标签 / 加区域 / 建关联"这类不改 records 行的变化。
     * · 不带 since：**快照模式**（推荐）。拿当前指纹和上次存的快照逐条比，
     *   标签/协议/关联/区域都算进指纹，漏不掉。首次调用返回 bootstrap（全量 id 列表）。
     *
     * `commit=0` 只预览、不推进快照。
     */
    private fun changes(q: Map<String, String>): JSONObject {
        val since = q["since"]?.toLongOrNull()
        if (since != null) {
            val rows = runCatching { runBlocking { app.container.database.recordDao().getChangeRows() } }
                .getOrDefault(emptyList())
            val created = rows.filter { !it.isDeleted && it.createdAt > since }.map { it.id }
            val updated = rows.filter { !it.isDeleted && it.updatedAt > since && it.createdAt <= since }.map { it.id }
            val deleted = rows.filter { it.isDeleted && it.updatedAt > since }.map { it.id }
            return JSONObject()
                .put("ok", true).put("schema_version", 1)
                .put("mode", "timestamp").put("since", since)
                .put("server_ts", System.currentTimeMillis())
                .put("created", JSONArray(created))
                .put("updated", JSONArray(updated))
                .put("deleted", JSONArray(deleted))
                .put("counts", JSONObject()
                    .put("created", created.size).put("updated", updated.size).put("deleted", deleted.size))
        }

        val current = buildFingerprints()
        val store = AgentSyncStore(app)
        val commit = q["commit"] != "0"
        val now = System.currentTimeMillis()
        val snapshot = store.load()
        if (snapshot == null) {
            if (commit) store.save(now, current)
            return JSONObject()
                .put("ok", true).put("schema_version", 1)
                .put("mode", "bootstrap").put("server_ts", now)
                .put("total", current.size)
                .put("created", JSONArray(current.keys.toList()))
                .put("changed", JSONArray())
                .put("removed", JSONArray())
                .put("counts", JSONObject()
                    .put("created", current.size).put("changed", 0).put("removed", 0))
        }
        val created = current.keys.filter { it !in snapshot.fp }
        val changed = current.keys.filter { snapshot.fp[it] != null && snapshot.fp[it] != current[it] }
        val removed = snapshot.fp.keys.filter { it !in current }
        if (commit) store.save(now, current)
        return JSONObject()
            .put("ok", true).put("schema_version", 1)
            .put("mode", "delta")
            .put("server_ts", now)
            .put("prev_ts", snapshot.ts)
            .put("committed", commit)
            .put("created", JSONArray(created))
            .put("changed", JSONArray(changed))
            .put("removed", JSONArray(removed))
            .put("counts", JSONObject()
                .put("created", created.size).put("changed", changed.size).put("removed", removed.size))
    }

    /**
     * 当前全库指纹（2026-11-15）。
     *
     * 含：正文 / 类型 / 来源 / 删除位 / 标签名 / 协议名 / 关联边 / 区域成员。
     * 不含锁定正文（只看锁定位，不读密文内容）。批量查询，避免逐条 N+1。
     */
    private fun buildFingerprints(): Map<String, Int> {
        val db = app.container.database
        val active = runCatching { runBlocking { db.recordDao().getAllActiveLight() } }.getOrDefault(emptyList())
        val trashed = runCatching { runBlocking { db.recordDao().getTrashedRecords() } }.getOrDefault(emptyList())
        val ids = active.map { it.id } + trashed.map { it.id }

        val tags = HashMap<String, MutableList<String>>()
        val protocols = HashMap<String, MutableList<String>>()
        val relations = HashMap<String, MutableList<String>>()
        val zones = HashMap<String, MutableList<String>>()
        runCatching {
            runBlocking { db.tagDao().getTagsForRecords(ids) }
        }.getOrNull()?.forEach { tags.getOrPut(it.recordId) { mutableListOf() }.add(it.name) }
        runCatching {
            runBlocking { db.protocolDao().getProtocolsForRecords(ids) }
        }.getOrNull()?.forEach { protocols.getOrPut(it.recordId) { mutableListOf() }.add(it.name) }
        runCatching {
            runBlocking { db.recordRelationDao().all().filter { it.validTo == null } }
        }.getOrNull()?.forEach { rel ->
            relations.getOrPut(rel.fromId) { mutableListOf() }.add("out:" + rel.toId + ":" + rel.type)
            relations.getOrPut(rel.toId) { mutableListOf() }.add("in:" + rel.fromId + ":" + rel.type)
        }
        runCatching {
            runBlocking { db.zoneDao().allMembers() }
        }.getOrNull()?.forEach { zones.getOrPut(it.recordId) { mutableListOf() }.add(it.zoneId) }

        val out = HashMap<String, Int>(ids.size * 2)
        active.forEach { r ->
            out[r.id] = AgentSyncStore.fingerprint(
                r.id, r.content, r.type, r.originId, false,
                tags[r.id].orEmpty(), protocols[r.id].orEmpty(),
                relations[r.id].orEmpty(), zones[r.id].orEmpty()
            )
        }
        trashed.forEach { r ->
            out[r.id] = AgentSyncStore.fingerprint(
                r.id, r.content, r.type.name, r.originId, true,
                tags[r.id].orEmpty(), protocols[r.id].orEmpty(),
                relations[r.id].orEmpty(), zones[r.id].orEmpty()
            )
        }
        return out
    }

    /** 区域（2026-11-15）：以前完全没有端点，agent 想知道"创作里有什么"只能靠人看。 */
    private fun zones(q: Map<String, String>): JSONObject {
        val dao = app.container.database.zoneDao()
        val zones = runCatching { runBlocking { dao.all() } }.getOrDefault(emptyList())
        val members = runCatching { runBlocking { dao.allMembers() } }.getOrDefault(emptyList())
            .groupBy { it.zoneId }
        val wantMembers = q["members"] == "1"
        val arr = JSONArray()
        zones.forEach { z ->
            val mids = members[z.id].orEmpty().map { it.recordId }
            val o = JSONObject()
                .put("id", z.id)
                .put("name", z.name)
                .put("builtin", z.builtin)
                .put("sort", z.sort)
                .put("member_count", mids.size)
            // 锚点/札记这类内置区域是"标记 + 视图"，成员在记录表里，不在 zone_records
            if (wantMembers) o.put("member_ids", JSONArray(mids))
            arr.put(o)
        }
        return JSONObject().put("ok", true).put("schema_version", 1)
            .put("total", arr.length()).put("zones", arr)
    }

    /** 刻度（2026-11-15）：名称/起止/冻结/summary 长度；summary=1 时带正文。 */
    private fun ticks(q: Map<String, String>): JSONObject {
        val list = runCatching { runBlocking { app.container.database.tickDao().getActive() } }
            .getOrDefault(emptyList())
        val wantSummary = q["summary"] == "1" || q["full"] == "1"
        val arr = JSONArray()
        list.forEach { t ->
            val o = JSONObject()
                .put("id", t.id)
                .put("name", t.name)
                .put("start_ms", t.startMs)
                .put("end_ms", t.endMs ?: JSONObject.NULL)
                .put("frozen", t.frozenAt != null)
                .put("summary_len", t.summary.length)
            if (wantSummary) o.put("summary", t.summary)
            arr.put(o)
        }
        return JSONObject().put("ok", true).put("schema_version", 1)
            .put("total", arr.length()).put("ticks", arr)
    }

    /** 表盘（2026-11-15）：与刻度同形。 */
    private fun dials(q: Map<String, String>): JSONObject {
        val list = runCatching { runBlocking { app.container.database.dialDao().all() } }
            .getOrDefault(emptyList())
        val wantSummary = q["summary"] == "1" || q["full"] == "1"
        val arr = JSONArray()
        list.forEach { d ->
            val o = JSONObject()
                .put("id", d.id)
                .put("name", d.name)
                .put("start_ms", d.startMs)
                .put("end_ms", d.endMs ?: JSONObject.NULL)
                .put("summary_len", d.summary.length)
            if (wantSummary) o.put("summary", d.summary)
            arr.put(o)
        }
        return JSONObject().put("ok", true).put("schema_version", 1)
            .put("total", arr.length()).put("dials", arr)
    }

    /** 协议清单（2026-11-15）：标签/来源都有端点，协议一直没有。 */
    private fun protocolsEndpoint(): JSONObject {
        val counts = runCatching { runBlocking { app.container.database.protocolDao().protocolCounts() } }
            .getOrDefault(emptyList())
        val arr = JSONArray()
        counts.forEach { p -> arr.put(JSONObject().put("name", p.name).put("records", p.n)) }
        return JSONObject().put("ok", true).put("schema_version", 1)
            .put("total", arr.length()).put("protocols", arr)
    }

    /** 附件索引（2026-11-15）：只给元数据，不给文件本体（二进制通道还没做）。 */
    private fun attachmentsEndpoint(q: Map<String, String>): JSONObject {
        val list = runCatching { runBlocking { app.container.database.attachmentDao().getAll() } }
            .getOrDefault(emptyList())
        val recordId = q["record_id"].orEmpty()
        val limit = (q["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 1000)
        val filtered = if (recordId.isBlank()) list else list.filter { it.recordId == recordId }
        val arr = JSONArray()
        filtered.take(limit).forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("record_id", a.recordId)
                    .put("file_name", a.fileName)
                    .put("type", a.type)
                    .put("file_size", a.fileSize)
                    .put("duration", a.duration ?: JSONObject.NULL)
                    .put("created_at", a.createdAt)
                    .put("is_encrypted", a.isEncrypted)
            )
        }
        return JSONObject().put("ok", true).put("schema_version", 1)
            .put("total", filtered.size).put("returned", arr.length())
            .put("attachments", arr)
    }

    /** 版本快照（2026-11-15）：给 id/记录/时间 + 正文长度，正文用 content=1 才给。 */
    private fun versionsEndpoint(q: Map<String, String>): JSONObject {
        val list = runCatching { runBlocking { app.container.database.versionDao().getAll() } }
            .getOrDefault(emptyList())
        val recordId = q["record_id"].orEmpty()
        val wantContent = q["content"] == "1"
        val filtered = if (recordId.isBlank()) list else list.filter { it.recordId == recordId }
        val limit = (q["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 1000)
        val arr = JSONArray()
        filtered.take(limit).forEach { v ->
            val o = JSONObject()
                .put("id", v.id)
                .put("record_id", v.recordId)
                .put("created_at", v.createdAt)
                .put("chars", v.content.length)
            if (wantContent) o.put("content", v.content)
            arr.put(o)
        }
        return JSONObject().put("ok", true).put("schema_version", 1)
            .put("total", filtered.size).put("returned", arr.length()).put("versions", arr)
    }

    /**
     * 设置只读快照（2026-11-15）。
     *
     * **脱敏**：令牌、API Key、加密相关的键一律只回 "***"——agent 不需要看见它们，
     * 看得见就等于泄露面变大。
     */
    private fun settingsEndpoint(): JSONObject {
        val prefs = app.preferences
        val sensitive = listOf("token", "key", "secret", "password", "salt", "hash", "wrapped")
        val o = JSONObject()
        runCatching {
            prefs.all.forEach { (k, v) ->
                if (v == null) return@forEach
                val key = k.lowercase()
                if (sensitive.any { key.contains(it) }) o.put(k, "***") else o.put(k, v.toString())
            }
        }
        return JSONObject().put("ok", true).put("schema_version", 1)
            .put("total", o.length()).put("prefs", o)
    }

    /**
     * 锁状态（2026-11-16，v5 门闩）。
     *
     * 给两处用：
     *  · UI 的常驻提示（"有 N 条锁着，设置密码后可查看"）
     *  · 迁移前的判断（旧版配置 + 还有密文 → 该迁移了）
     *
     * **只给计数与版本，不给任何正文**。
     */
    private fun lockStateEndpoint(): JSONObject {
        val db = app.container.database
        runCatching { runBlocking { memore.app.utils.EncryptionManager.refreshConfigStatus() } }
        val locked = runCatching {
            runBlocking { db.recordDao().getAllActiveLight().count { it.isLocked } }
        }.getOrDefault(0)
        val cipher = runCatching { runBlocking { db.recordDao().countCipherRecords() } }.getOrDefault(0)
        val configured = memore.app.utils.EncryptionManager.isConfigured()
        val version = memore.app.utils.EncryptionManager.configVersion()
        return JSONObject()
            .put("ok", true)
            .put("schema_version", 1)
            .put("configured", configured)
            .put("version", version)
            .put("mode", if (version == memore.app.utils.EncryptionManager.V5_VERSION) "gate" else "legacy")
            .put("locked", locked)
            .put("cipher_records", cipher)
            // "锁着但没有密码" = 导入到新机后的待设密码状态
            .put("pending_setup", !configured && locked > 0)
            .put("needs_migration", configured && version != memore.app.utils.EncryptionManager.V5_VERSION && cipher > 0)
    }

    /**
     * 语义 + 词法混合检索（2026-11-13）。
     *
     * 直接复用 App 自己的 RetrievalService：它已经有查询扩展、向量召回、
     * 字符 bigram 词法分、MMR 去重和系列折叠——重写一份只会更差。
     *
     * 之前 agent 只能搜字面（"骑行"搜不到"骑车"），这里开始按意思找。
     */
    /**
     * 语义 + 词法混合检索（2026-11-13）。
     *
     * 复用 App 自己的 RetrievalService（查询扩展、向量召回、bigram 词法、MMR 去重），
     * 再叠一层**别名扩展**：别名组里的几种写法是同一批记录，不接别名就只能搜到一部分。
     *
     * 别名扩展是本地查表，不花 token。（2026-11-21：LLM 短查询扩展已删。）
     */
    /**
     * 混合检索（2026-11-13 第二轮）：分数自己算，不再只看名次。
     *
     * score = 0.50 语义 + 0.25 词面 + 0.15 覆盖率 + 0.10 时间，再乘长度惩罚。
     *
     * 为什么要自己算：RRF 只保留名次，丢掉"到底多像"。于是搜「花钱」时，
     * 一篇顺带提到钱的长文可能压过一条整篇都在讲开销的短记录。
     *
     * 返回里带 score_parts —— agent 才能跟用户解释"为什么这两条相关"。
     */
    private fun search(q: Map<String, String>): JSONObject {
        val query = q["q"].orEmpty().trim()
        if (query.isBlank()) return err("缺少 q", "bad_request")
        val k = (q["k"]?.toIntOrNull() ?: 10).coerceIn(1, 50)
        val variants = listOf(query) + aliasVariants(query)

        // 查询向量（最多 4 个变体，一次批量嵌入）
        val provider = app.container.embeddingProvider
        val qVecs: List<FloatArray> = if (provider == null) emptyList() else runCatching {
            runBlocking { provider.embed(variants, isQuery = true) }
        }.getOrDefault(emptyList())

        // 记录 → 块向量
        val rows = runCatching { runBlocking { app.container.database.vectorDao().all() } }
            .getOrDefault(emptyList())
        val byRecord = HashMap<String, ArrayList<FloatArray>>()
        rows.forEach { row ->
            val vec = runCatching { VectorIndexService.bytesToFloats(row.vec) }.getOrNull() ?: return@forEach
            byRecord.getOrPut(row.recordId) { ArrayList() }.add(vec)
        }

        val light = runCatching { runBlocking { app.container.database.recordDao().getAllActiveLight() } }
            .getOrDefault(emptyList())
            .filter { (canLocked() || !it.isLocked) }
            .let { userLight(it) }

        val now = System.currentTimeMillis()
        val queryGrams = bigrams(query)

        // 语义原始分先单独算出来：后面要跨候选归一化。
        // 绝对值没意义（本地模型对任何文本都给 0.3+），相对差异才说明问题。
        val semRaw = HashMap<String, Double>()
        if (qVecs.isNotEmpty()) {
            light.forEach { r ->
                val vecs = byRecord[r.id] ?: return@forEach
                var best = 0.0
                for (qv in qVecs) {
                    for (v in vecs) {
                        val c = runCatching { VectorMath.cosine(qv, v) }.getOrDefault(0f).toDouble()
                        if (c > best) best = c
                    }
                }
                semRaw[r.id] = best
            }
        }
        // 按名次归一（不是 min-max）：候选之间语义分差很小（0.34~0.61），
        // min-max 会把噪声也拉到接近 1.0，反而制造假信号。名次归一保住顺序、
        // 又不给绝对分任何额外含义。
        val semRank = HashMap<String, Double>()
        if (semRaw.isNotEmpty()) {
            val ordered = semRaw.entries.sortedByDescending { it.value }
            val last = (ordered.size - 1).coerceAtLeast(1)
            ordered.forEachIndexed { idx, e -> semRank[e.key] = 1.0 - idx.toDouble() / last }
        }

        val scored = light.mapNotNull { r ->
            // ① 语义：按名次归一（见上）
            val sem = semRank[r.id] ?: 0.0

            // ② 词面：变体里最高的那个
            var lex = 0.0
            for (v in variants) {
                val s = score(bigrams(v), r.content + " " + r.notes)
                if (s > lex) lex = s
            }

            // ③ 覆盖率：整句出现在正文前 200 字 > 出现在别处 > 只有字组命中
            val cov = when {
                r.content.take(200).contains(query) -> 1.0
                r.content.contains(query) -> 0.6
                queryGrams.isEmpty() -> 0.0
                else -> queryGrams.count { g -> r.content.contains(g) }.toDouble() / queryGrams.size * 0.4
            }

            // ④ 时间：半衰期一年。只给 0.1 权重，不能压过相关性
            val ageDays = ((now - r.createdAt).coerceAtLeast(0L)) / 86_400_000.0
            val rec = 1.0 / (1.0 + ageDays / 365.0)

            // ⑤ 长度惩罚：800 字以后开始衰减
            val lenPen = 1.0 / (1.0 + ln(1.0 + r.content.length / 800.0))
            val penalty = 0.6 + 0.4 * lenPen

            // ⑥ 重复惩罚：不同字符占比过低 = 测试垃圾 / 粘贴重复
            //    同一句重复五次、去重后只剩两个字，技术上"全是某人"，但毫无信息。
            val distinct = r.content.toSet().size
            val repeat = (distinct.toDouble() / r.content.length.coerceAtLeast(1))
            // 单字占比：一个字占了全文 1/4 以上 = 复读/测试。同一句重复就是这种。
            val topCharCount = r.content.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            val dominance = topCharCount.toDouble() / r.content.length.coerceAtLeast(1)
            val repeatPenalty = (0.35 + 0.65 * (repeat / 0.25).coerceAtMost(1.0))
                .coerceAtMost(if (dominance > 0.25) 0.55 else 1.0)

            // ⑦ 只有语义支撑（词面 0、覆盖 0）= 是"猜"，不是"找到"。
            //    降权并标记，让 agent 能如实告诉用户库里没有直接命中。
            val semanticOnly = lex <= 0.0 && cov <= 0.0
            val supportPenalty = if (semanticOnly) 0.55 else 1.0

            val base = 0.50 * sem + 0.25 * lex + 0.15 * cov + 0.10 * rec
            val total = base * penalty * repeatPenalty * supportPenalty
            if (total <= 0.0) return@mapNotNull null

            val parts = JSONObject()
                .put("semantic", round4(sem))
                .put("lexical", round4(lex))
                .put("coverage", round4(cov))
                .put("recency", round4(rec))
                .put("length_penalty", round4(penalty))
                .put("repeat_penalty", round4(repeatPenalty))
                .put("dominance", round4(dominance))
                .put("support", if (semanticOnly) "semantic_only" else "hit")
            Triple(r, total, parts)
        }.sortedByDescending { it.second }.take(k)

        val arr = JSONArray()
        scored.forEach { (r, total, parts) ->
            val text = r.content
            val shown = if (text.length <= 400) text
            else text.take(400).trimEnd() + " …（共 " + text.length + " 字）"
            arr.put(JSONObject()
                .put("id", r.id)
                .put("created_at", r.createdAt)
                .put("origin_id", r.originId ?: JSONObject.NULL)
                .put("content", shown)
                .put("chars", text.length)
                .put("score", round4(total))
                .put("score_parts", parts))
        }

        return JSONObject()
            .put("ok", true)
            .put("schema_version", 1)
            .put("query", query)
            .put("mode", "hybrid:semantic+lexical+coverage+recency")
            .put("aliases_used", JSONArray(variants.drop(1)))
            .put("embedded", qVecs.isNotEmpty())
            .put("indexed", byRecord.size)
            .put("returned", arr.length())
            .put("results", arr)
    }

    private fun round4(v: Double): Double = Math.round(v * 10000.0) / 10000.0

    /**
     * 别名变体：查别名映射表，把 query 里出现的名字换成同组的其它名字。
     * 例如 query 一个别名时，会同时展开成它的其它写法。
     */
    private fun aliasVariants(query: String): List<String> {
        val groups = runCatching { runBlocking { app.container.aliasMappingRepo.load() } }
            .getOrDefault(emptyList())
        val out = LinkedHashSet<String>()
        for (g in groups) {
            val names = listOf(g.standardName) + g.aliases
            val hit = names.firstOrNull { it.isNotBlank() && query.contains(it) }
            if (hit == null) continue
            val others = names.filter { it.isNotBlank() && it != hit }.take(2)
            if (others.isEmpty()) continue
            // 把命中的名字替换成同组别名，保持 query 的其余部分
            out += query.replace(hit, others.first())
            if (others.size > 1) out += query.replace(hit, others[1])
        }
        return out.take(3)
    }

    /**
     * 一条记录的邻居（2026-11-13）。
     *
     * 三个来源：
     *   · relations —— 用户/AI 建立的关联边（802 条），双向都算
     *   · amendments —— 增修链（同一条记忆的后续）
     *   · sharedTags —— 共享标签的其它记录，按共享数排序（弱关系，但要标出来）
     *
     * 有了它，agent 可以"沿边走"，而不是每次重新做一次语义搜索。
     */
    private fun neighbors(id: String): JSONObject {
        val db = app.container.database
        val relDao = db.recordRelationDao()
        val out = runCatching { runBlocking { relDao.from(id).filter { it.validTo == null } } }.getOrDefault(emptyList())
        val inc = runCatching { runBlocking { relDao.to(id).filter { it.validTo == null } } }.getOrDefault(emptyList())

        // 同一对关系可能同时出现在 from 和 to 两侧（双向存储），按邻居 id 去重：
        // 合并 direction 为 both，取较大的分。否则列表里会出现两条一模一样的记录。
        val merged = LinkedHashMap<String, Pair<String, Double>>()
        out.forEach { rel ->
            val prev = merged[rel.toId]
            merged[rel.toId] = if (prev == null) ("out" to rel.score)
            else ("both" to maxOf(prev.second, rel.score))
        }
        inc.forEach { rel ->
            val prev = merged[rel.fromId]
            merged[rel.fromId] = if (prev == null) ("in" to rel.score)
            else ("both" to maxOf(prev.second, rel.score))
        }
        val edges = merged.map { (nid, v) -> Triple(nid, v.first, v.second) }
        val neighborIds = edges.map { it.first }

        // 取邻居摘要（截断正文）
        val rows: Map<String, memore.app.data.model.Record> = if (neighborIds.isEmpty()) {
            emptyMap()
        } else {
            runCatching {
                runBlocking { app.container.database.recordDao().getRecordsByIds(neighborIds) }
            }.getOrDefault(emptyList()).filter { !it.isDeleted && (canLocked() || !it.isLocked) }.associateBy { it.id }
        }

        val relations = JSONArray()
        edges.sortedByDescending { it.third }.forEach { (nid, dir, score) ->
            val r = rows[nid] ?: return@forEach
            relations.put(JSONObject()
                .put("id", r.id)
                .put("direction", dir)
                .put("score", round4(score))
                .put("created_at", r.createdAt)
                .put("content", snippet(r.content)))
        }

        // 增修链
        val amendments = JSONArray()
        runCatching { runBlocking { db.recordDao().amendmentsOf(id) } }.getOrDefault(emptyList())
            .forEach { a ->
                amendments.put(JSONObject()
                    .put("id", a.id)
                    .put("created_at", a.createdAt)
                    .put("content", snippet(a.content)))
            }

        // 同标签的其它记录（弱关系）：共享标签越多越靠前
        val sharedTags = JSONArray()
        val myTags = runCatching { runBlocking { db.recordDao().tagNamesOf(id) } }.getOrDefault(emptyList())
        if (myTags.isNotEmpty()) {
            val counter = HashMap<String, Int>()
            myTags.forEach { tag ->
                val ids = runCatching { runBlocking { db.recordDao().recordIdsByTagName(tag) } }
                    .getOrDefault(emptyList())
                ids.filter { it != id }.forEach { rid -> counter[rid] = (counter[rid] ?: 0) + 1 }
            }
            val top = counter.entries.sortedByDescending { it.value }.take(8)
            if (top.isNotEmpty()) {
                val brief: Map<String, memore.app.data.model.Record> = runCatching {
                    runBlocking { app.container.database.recordDao().getRecordsByIds(top.map { it.key }) }
                }.getOrDefault(emptyList()).filter { !it.isDeleted && (canLocked() || !it.isLocked) }.associateBy { it.id }
                top.forEach { (rid, n) ->
                    val r = brief[rid] ?: return@forEach
                    sharedTags.put(JSONObject()
                        .put("id", r.id)
                        .put("shared", n)
                        .put("created_at", r.createdAt)
                        .put("content", snippet(r.content)))
                }
            }
        }

        return JSONObject()
            .put("ok", true)
            .put("schema_version", 1)
            .put("id", id)
            .put("relations", relations)
            .put("amendments", amendments)
            .put("shared_tags", sharedTags)
            .put("my_tags", JSONArray(myTags))
    }

    private fun snippet(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= 160) flat else flat.take(160) + "…"
    }

    /**
     * 锚点（2026-11-20）：agent 读"这条记录提到谁、哪首歌、哪里"。
     * 可选 `record_id`；不带则给最近的锚点。
     */
    private fun anchors(q: Map<String, String>): JSONObject {
        val dao = app.container.database.recordEntityDao()
        val rid = q["record_id"]?.trim().orEmpty()
        val limit = (q["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val offset = (q["offset"]?.toIntOrNull() ?: 0).coerceIn(0, 5000)
        val rows = runBlocking {
            if (rid.isNotBlank()) dao.forRecordUnlocked(rid) else dao.all(limit + offset)
        }.drop(offset).take(limit)
        val arr = org.json.JSONArray()
        rows.forEach { e ->
            arr.put(JSONObject()
                .put("record_id", e.recordId)
                .put("name", e.name)
                .put("kind", e.kind)
                .put("quote", e.quote)
                .put("created_at", e.createdAt))
        }
        // total 只表示"这次返回了几条"；不带 record_id 且打满 limit 时标记还有更多。
        return JSONObject().put("ok", true).put("schema_version", 1)
            .put("returned", rows.size)
            .put("offset", offset)
            .put("has_more", rid.isBlank() && rows.size >= limit)
            .put("anchors", arr)
    }

    /** 锚点聚合：名字 + 类型 + 出现次数 + 覆盖记录数。 */
    private fun anchorsSummary(): JSONObject {
        val dao = app.container.database.recordEntityDao()
        val arr = org.json.JSONArray()
        runBlocking { dao.summary() }.forEach { r ->
            arr.put(JSONObject()
                .put("name", r.name)
                .put("kind", r.kind)
                .put("total", r.total)
                .put("records", r.recordCount))
        }
        return JSONObject().put("ok", true).put("schema_version", 1).put("summary", arr)
    }

    /** 一条记录的历次分析（最新在前，最多 5 条在库里）。 */
    private fun recordVersions(id: String): JSONObject {
        val rec = runBlocking { app.container.recordRepository.getRecordById(id) }
            ?: return JSONObject().put("ok", false).put("error", "没有这条记录").put("code", "not_found")
        if (rec.isLocked && !canLocked()) {
            return JSONObject().put("ok", false).put("error", "这条记录已锁定").put("code", "locked")
        }
        val list = runBlocking { app.container.database.analysisVersionDao().forRecord(rec.id) }
        val arr = org.json.JSONArray()
        list.forEach { v ->
            arr.put(JSONObject()
                .put("id", v.id)
                .put("prompt_version", v.promptVersion)
                .put("status", v.status)
                .put("model", v.model)
                .put("summary", v.summary)
                .put("reason", v.reason)
                .put("has_input_hash", v.inputHash.isNotBlank())
                .put("created_at", v.createdAt))
        }
        return JSONObject().put("ok", true).put("schema_version", 1)
            .put("record_id", rec.id).put("total", list.size).put("versions", arr)
    }

    /** AI 洞察（files/insights/archive.jsonl，最新在前）。 */
    /**
     * 待批事件（2026-11-20，长轮询）。
     *
     * `GET /events?since=<ms>&wait=1..25`：有新 proposed 动作（created_at > since）立即返回；
     * 否则最多挂 wait 秒再返回空。并发最多 2 条，第三条直接回 retry_after，
     * 避免把线程池占满（accept 是每连接一线程）。
     */
    private fun events(q: Map<String, String>): JSONObject {
        val since = q["since"]?.toLongOrNull() ?: 0L
        val waitS = (q["wait"]?.toIntOrNull() ?: 25).coerceIn(1, 25)
        if (!EVENT_SLOTS.tryAcquire()) {
            return JSONObject().put("ok", true).put("schema_version", 1)
                .put("retry_after_s", 2).put("items", org.json.JSONArray())
        }
        try {
            val svc = memore.app.data.agent.MirrorActionService(app)
            val deadline = System.currentTimeMillis() + waitS * 1000L
            while (true) {
                val items = org.json.JSONArray()
                svc.pendingActions().forEach { a ->
                    if (a.createdAt > since) {
                        items.put(JSONObject()
                            .put("id", a.id)
                            .put("title", a.title)
                            .put("created_at", a.createdAt)
                            .put("requires_confirm", a.requiresConfirm))
                    }
                }
                if (items.length() > 0 || System.currentTimeMillis() >= deadline) {
                    return JSONObject().put("ok", true).put("schema_version", 1)
                        .put("since", System.currentTimeMillis())
                        .put("items", items)
                }
                Thread.sleep(500)
            }
        } catch (_: InterruptedException) {
            return JSONObject().put("ok", true).put("schema_version", 1)
                .put("items", org.json.JSONArray())
        } finally {
            EVENT_SLOTS.release()
        }
    }

    /**
     * 给 AI 读的投影（2026-11-20）：按刻度分段，每段给边界/条数/字数/总结/增修/锚定。
     *
     * 为什么要有：桥现在只能给 agent 平铺 322 条记录，它没法先看"这段时期是什么"。
     * 投影是**纯读**：不写库、不加表，走 [memore.app.data.repository.TickMembership] 的同一份归属规则。
     */
    /** 和某条记录语义相近的记录（向量，不经过 AI）。 */
    private fun similar(q: Map<String, String>): JSONObject {
        val rid = q["id"]?.trim().orEmpty()
        if (rid.isBlank()) return JSONObject().put("ok", false).put("error", "缺 id")
        val k = (q["k"]?.toIntOrNull() ?: 5).coerceIn(1, 20)
        val hits = runCatching { runBlocking { app.container.retrievalService.similar(rid, k) } }
            .getOrElse { e -> return JSONObject().put("ok", false).put("error", e.message ?: "检索失败") }
        val arr = JSONArray()
        hits.forEach { h ->
            arr.put(JSONObject()
                .put("id", h.record.id)
                .put("type", h.record.type)
                .put("created_at", h.record.createdAt)
                .put("score", h.score.toDouble())
                .put("snippet", h.snippet ?: ""))
        }
        return JSONObject().put("ok", true).put("schema_version", 1).put("similar", arr)
    }

    /** 提示词 + 当前生效的外部规则（system instruction），只读。 */
    private fun prompts(): JSONObject {
        val arr = JSONArray()
        memore.app.core.ai.Prompts.ALL_PROMPTS.filter { !it.isAbandoned }.forEach { def ->
            val (promptKey, displayName) = def
            arr.put(JSONObject()
                .put("key", promptKey)
                .put("name", displayName)
                .put("standard", memore.app.core.ai.Prompts.getPrompt(promptKey, false))
                .put("thinking", if (def.dualMode) memore.app.core.ai.Prompts.getPrompt(promptKey, true)
                                 else memore.app.core.ai.Prompts.getPrompt(promptKey, false)))
        }
        val activeName = memore.app.core.ai.SystemInstructionStore.activeName(app)
        val active = memore.app.core.ai.SystemInstructionStore.load(app)
            .firstOrNull { it.name == activeName }
        return JSONObject().put("ok", true).put("schema_version", 1)
            .put("prompts", arr)
            .put("system_instruction", JSONObject()
                .put("name", activeName)
                .put("content", active?.content.orEmpty()))
    }

    /** 某个刻度时间窗内的记录（给 agent“这一段里都有什么”）。 */
    private fun tickRecords(id: String, q: Map<String, String>): JSONObject {
        val tick = runCatching { runBlocking { app.container.database.tickDao().getActive() } }
            .getOrDefault(emptyList()).firstOrNull { it.id == id }
            ?: return JSONObject().put("ok", false).put("error", "没有这个刻度").put("code", "not_found")
        val limit = (q["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
        val list = runCatching { runBlocking { app.container.tickRepository.recordsInRange(tick.startMs, tick.endMs) } }
            .getOrDefault(emptyList())
        val arr = JSONArray()
        list.take(limit).forEach { r ->
            arr.put(JSONObject()
                .put("id", r.id)
                .put("type", r.type)
                .put("created_at", r.createdAt)
                .put("content", r.content.take(400)))
        }
        return JSONObject().put("ok", true).put("schema_version", 1)
            .put("tick", JSONObject().put("id", tick.id).put("name", tick.name)
                .put("start", tick.startMs).put("end", tick.endMs ?: JSONObject.NULL))
            .put("total", list.size).put("records", arr)
    }

    /**
     * 附件内容（base64，2026-11-21 改）。
     *
     * 旧实现：单文件超过 5MB 直接拒 —— 手机照片轻松 10MB+，等于看不了图。
     * 现规则：
     *  · 图片：解码后按**长边 2048** 缩放、JPEG q85 再传（通常 200KB~800KB），并标 `downscaled`；
     *  · 非图片（音频/文件）：原始字节，上限 20MB；
     *  · 解码失败（损坏图/HEIC 等）：退回原始字节，仍受 20MB 限制。
     * 原文件不动，只是传输时压一份。
     */
    private fun attachmentContent(id: Long): JSONObject {
        val a = runCatching { runBlocking { app.container.database.attachmentDao().getAll() } }
            .getOrDefault(emptyList()).firstOrNull { it.id == id }
            ?: return JSONObject().put("ok", false).put("error", "没有这个附件").put("code", "not_found")
        val f = java.io.File(a.filePath)
        if (!f.exists()) return JSONObject().put("ok", false).put("error", "文件不在了").put("code", "missing_file")
        if (f.length() > 20L * 1024 * 1024) {
            return JSONObject().put("ok", false).put("error", "文件超过 20MB").put("code", "too_large")
        }
        if (!can("read:content")) return JSONObject().put("ok", false).put("error", "没有 read:content").put("code", "forbidden")

        val originalBytes = f.length()
        if (a.type == "IMAGE") {
            val scaled = runCatching { downscaleImage(f, 2048) }.getOrNull()
            if (scaled != null) {
                val b64 = android.util.Base64.encodeToString(scaled, android.util.Base64.NO_WRAP)
                return JSONObject().put("ok", true).put("schema_version", 1)
                    .put("id", a.id).put("record_id", a.recordId)
                    .put("file_name", a.fileName).put("type", a.type)
                    .put("bytes", scaled.size).put("original_bytes", originalBytes)
                    .put("downscaled", true).put("base64", b64)
            }
        }

        val bytes = runCatching { f.readBytes() }.getOrElse { e ->
            return JSONObject().put("ok", false).put("error", e.message ?: "读取失败")
        }
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return JSONObject().put("ok", true).put("schema_version", 1)
            .put("id", a.id).put("record_id", a.recordId)
            .put("file_name", a.fileName).put("type", a.type)
            .put("bytes", bytes.size).put("original_bytes", originalBytes)
            .put("downscaled", false).put("base64", b64)
    }

    /** 图片缩到长边 ≤ maxSide，JPEG q85。失败返回 null（调用方退回原始字节）。 */
    private fun downscaleImage(f: java.io.File, maxSide: Int): ByteArray? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
        val longSide = maxOf(bmp.width, bmp.height)
        if (longSide > maxSide) {
            val scale = maxSide.toFloat() / longSide
            val w = (bmp.width * scale).toInt().coerceAtLeast(1)
            val h = (bmp.height * scale).toInt().coerceAtLeast(1)
            val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    /**
     * 关联边 + 置信度（2026-11-20）。
     *
     * agent 审边之前要先拿到"这条边有多少依据"：引文是否接地、是否共享锚点、向量分、类型、来源。
     * 排序按置信度降序，`min_confidence` 用来筛待审队列。锁定/已删记录涉及的边不返回（引文会泄露正文）。
     */
    private fun relations(q: Map<String, String>): JSONObject {
        val rid = q["record_id"]?.trim().orEmpty()
        val minConfidence = (q["min_confidence"]?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 1.0)
        val limit = (q["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
        val relDao = app.container.database.recordRelationDao()
        val all = runBlocking {
            if (rid.isNotBlank()) relDao.activeForRecord(rid) else relDao.all()
        }.filter { it.validTo == null && it.status == "ACTIVE" }
            // 入向：谁引用/补充了这条（2026-11-20）。
            .filter { q["to_id"].isNullOrBlank() || it.toId == q["to_id"] }
        val recDao = app.container.database.recordDao()
        val blocked = runBlocking { recDao.blockedRecordIds() }.toHashSet()
        val contents = runBlocking { recDao.getAllActiveRecords() }.associate { it.id to it.content }
        val anchors = runBlocking { app.container.database.recordEntityDao().all(5000) }
            .groupBy { it.recordId }
            .mapValues { e -> e.value.map { it.name }.toSet() }
        val rows = all
            .filter { it.fromId !in blocked && it.toId !in blocked }
            .map { r ->
                r to memore.app.data.repository.RelationConfidence.evaluate(
                    r,
                    anchors[r.fromId].orEmpty(),
                    anchors[r.toId].orEmpty(),
                    contents[r.fromId],
                    contents[r.toId]
                )
            }
            .filter { it.second.value >= minConfidence }
            .sortedByDescending { it.second.value }
            .take(limit)
        val arr = org.json.JSONArray()
        // 2026-11-20：审边时 agent 需要看两端正文。带上截断（默认 300 字），避免 N+1 调 /records/{id}。
        val includeRecords = q["include_records"] == "1"
        val contentChars = (q["content_chars"]?.toIntOrNull() ?: 300).coerceIn(50, 2000)
        rows.forEach { (r, c) ->
            val edge = JSONObject()
                .put("from_id", r.fromId)
                .put("to_id", r.toId)
                .put("type", r.type)
                .put("source", r.source)
                .put("why", r.why)
                .put("quote_a", r.quoteA)
                .put("quote_b", r.quoteB)
                .put("score", r.score)
                .put("confidence", c.value)
                .put("parts", JSONObject()
                    .put("quote", c.quote)
                    .put("anchors", c.anchors)
                    .put("vector", c.vector)
                    .put("type", c.type)
                    .put("source", c.source))
            if (includeRecords) {
                edge.put("from_content", contents[r.fromId]?.take(contentChars) ?: "")
                    .put("to_content", contents[r.toId]?.take(contentChars) ?: "")
            }
            arr.put(edge)
        }
        return JSONObject().put("ok", true).put("schema_version", 1)
            .put("min_confidence", minConfidence)
            .put("scanned", all.size)
            .put("returned", rows.size)
            .put("relations", arr)
    }

    private fun projection(q: Map<String, String>): JSONObject {
        val summaryChars = (q["summary_chars"]?.toIntOrNull() ?: 300).coerceIn(0, 2000)
        val ticks = runBlocking { app.container.tickRepository.getActive() }.sortedBy { it.startMs }
        val dao = app.container.database.recordDao()
        val records = runBlocking { dao.getAllActiveRecords() }.filter { canLocked() || !it.isLocked }
        val allAmends = runBlocking { dao.getAllActiveAmendments() }.filter { canLocked() || !it.isLocked }
        val amendsByParent = allAmends.groupBy { it.correctionOfId }
        val byTick = LinkedHashMap<String, MutableList<memore.app.data.model.Record>>()
        var unticked = 0
        var untickedChars = 0
        records.forEach { r ->
            val tick = memore.app.data.repository.TickMembership.tickOf(r.createdAt, ticks)
            if (tick == null) {
                unticked++
                untickedChars += r.content.length
            } else {
                byTick.getOrPut(tick.id) { mutableListOf() }.add(r)
            }
        }
        val arr = org.json.JSONArray()
        var assignedAmends = 0
        ticks.forEach { t ->
            val list = byTick[t.id].orEmpty()
            val ids = list.map { it.id }.toHashSet()
            val amends = allAmends.filter { it.correctionOfId in ids }
            assignedAmends += amends.size
            val anchored = list.filter { it.isAnchored }.map { it.id }
            arr.put(JSONObject()
                .put("id", t.id)
                .put("name", t.name)
                .put("start", t.startMs)
                .put("end", t.endMs ?: JSONObject.NULL)
                .put("status", if (t.endMs == null) "进行中" else "已定格")
                .put("records", list.size)
                .put("chars", list.sumOf { it.content.length })
                .put("amendments", amends.size)
                .put("amend_chars", amends.sumOf { it.content.length })
                .put("amendment_ids", org.json.JSONArray(amends.take(20).map { it.id }))
                .put("summary", if (summaryChars > 0) t.summary.take(summaryChars) else "")
                .put("summary_chars", t.summary.length)
                .put("anchored", org.json.JSONArray(anchored.take(20)))
                .put("anchored_count", anchored.size))
        }
        return JSONObject().put("ok", true).put("schema_version", 1)
            .put("generated_at", System.currentTimeMillis())
            .put("total_records", records.size)
            .put("total_amendments", allAmends.size)
            .put("orphan_amendments", allAmends.size - assignedAmends)
            .put("ticks", arr)
            .put("unticked", JSONObject().put("records", unticked).put("chars", untickedChars))
    }


    private fun insights(q: Map<String, String>): JSONObject {
        val svc = memore.app.data.vector.InsightService(app, app.container.recordRepository)
        val limit = (q["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
        val offset = (q["offset"]?.toIntOrNull() ?: 0).coerceIn(0, 5000)
        val blocked = runBlocking { app.container.database.recordDao().blockedRecordIds() }.toHashSet()
        val all = svc.archive()
        // 先按锁过滤，再分页：否则 offset 会滑过被挡掉的条目。
        val visible = all.filter { e ->
            e.claims.any { c ->
                val r = c.recordId
                r.isNullOrBlank() || r !in blocked
            }
        }
        val arr = org.json.JSONArray()
        for (e in visible.drop(offset).take(limit)) {
            // 洞察是 JSONL，不经过 records 表：锁定/已删记录的 claim 要在这里逐条剔掉，
            // 否则 /insights 会成为绕过锁的旁路（2026-11-20）。
            val kept = e.claims.filter { c ->
                val r = c.recordId
                r.isNullOrBlank() || r !in blocked
            }
            if (kept.isEmpty()) continue
            val claims = org.json.JSONArray()
            kept.forEach { c ->
                claims.put(JSONObject()
                    .put("text", c.text)
                    .put("quote", c.quote)
                    .put("date", c.date)
                    .put("record_id", c.recordId ?: "")
                    .put("kind", c.kind)
                    .put("see", c.see ?: ""))
            }
            arr.put(JSONObject()
                .put("id", e.id)
                .put("at", e.at)
                .put("kind", e.kind)
                .put("meta", e.meta)
                .put("targets", org.json.JSONArray(e.targets.filter { it !in blocked }))
                .put("claims", claims))
        }
        return JSONObject().put("ok", true).put("schema_version", 1)
            .put("total", visible.size).put("returned", arr.length())
            .put("offset", offset).put("insights", arr)
    }

    private fun recordById(id: String): JSONObject {
        val dao = app.container.database.recordDao()
        val raw = runBlocking { dao.rawById(id) } ?: return err("没有这条记录", "not_found")
        if (raw.isDeleted) return err("这条记录已删除")
        if (raw.isLocked && !canLocked()) return err("这条记录已锁定（当前 token 没有 read:locked）")

        val obj = JSONObject()
            .put("id", raw.id)
            .put("type", raw.type.name)
            .put("created_at", raw.createdAt)
            .put("updated_at", raw.updatedAt)
            .put("origin_id", raw.originId ?: JSONObject.NULL)
            .put("content", raw.content)
            .put("notes", raw.notes)
            .put("tags", JSONArray(runBlocking { dao.tagNamesOf(id) }))
            .put("protocols", JSONArray(runBlocking { dao.protocolNamesOf(id) }))

        // 附件元数据（2026-11-14）：只给名字/类型/大小/时长，**不给文件路径与内容**
        val atts = runCatching { runBlocking { app.container.database.attachmentDao().getForRecord(id) } }
            .getOrDefault(emptyList())
        obj.put("attachments", JSONArray(atts.map { a ->
            JSONObject()
                .put("id", a.id)
                .put("file_name", a.fileName)
                .put("type", a.type)
                .put("size", a.fileSize)
                .put("duration", a.duration ?: JSONObject.NULL)
                .put("created_at", a.createdAt)
        }))

        // AI 分析结果（用户 2026-11-13 同意暴露）：**标注来源**，它不是用户写的
        raw.analysis?.let { a ->
            obj.put("ai_analysis", JSONObject()
                .put("summary", a.summary)
                .put("emotion_raw", raw.emotionalTags)
                .put("source", "AI 生成，非用户原话"))
        }

        val amends = runBlocking { dao.amendmentsOf(id) }
        obj.put("amendments", JSONArray(amends.map { m ->
            JSONObject().put("id", m.id).put("created_at", m.createdAt).put("content", m.content)
        }))

        return JSONObject().put("ok", true).put("schema_version", 1).put("record", obj)
    }

    /**
     * 时间聚合（2026-11-14）：每周/每月的条数、平均篇幅、情绪频次。
     *
     * 为什么要它：之前只能按条数取记录，"最近这三个月状态怎么样"这种问题
     * 得让 agent 拉几百条自己算——拉不动、也算不准。
     */
    private fun stats(q: Map<String, String>): JSONObject {
        val bucket = q["bucket"] ?: "week"
        val to = q["to"]?.toLongOrNull() ?: System.currentTimeMillis()
        val from = q["from"]?.toLongOrNull() ?: (to - 90L * 86_400_000L)

        val light = runCatching { runBlocking { app.container.database.recordDao().getAllActiveLight() } }
            .getOrDefault(emptyList())
            .filter { (canLocked() || !it.isLocked) && it.createdAt in from..to }
            .let { userLight(it) }
        val emotions = runCatching { runBlocking { app.container.database.recordDao().idAndEmotions() } }
            .getOrDefault(emptyList()).associate { it.id to parseEmotions(it.emotionalTags) }

        val fmt = when (bucket) {
            "day" -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
            "month" -> java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.ROOT)
            else -> java.text.SimpleDateFormat("yyyy-'W'ww", java.util.Locale.ROOT)
        }
        val grouped = light.groupBy { fmt.format(java.util.Date(it.createdAt)) }
        val arr = JSONArray()
        grouped.toSortedMap().forEach { (key, rows) ->
            val emoCount = HashMap<String, Int>()
            rows.forEach { r ->
                emotions[r.id]?.forEach { name -> emoCount[name] = (emoCount[name] ?: 0) + 1 }
            }
            val emoArr = JSONArray()
            emoCount.entries.sortedByDescending { it.value }.take(5).forEach { (n, c) ->
                emoArr.put(JSONObject().put("name", n).put("count", c))
            }
            arr.put(JSONObject()
                .put("key", key)
                .put("records", rows.size)
                .put("avg_chars", if (rows.isEmpty()) 0 else rows.sumOf { it.content.length } / rows.size)
                .put("emotions", emoArr))
        }
        return JSONObject()
            .put("ok", true)
            .put("schema_version", 1)
            .put("bucket", bucket)
            .put("from", from)
            .put("to", to)
            .put("totals", JSONObject()
                .put("records", light.size)
                .put("avg_chars", if (light.isEmpty()) 0 else light.sumOf { it.content.length } / light.size))
            .put("buckets", arr)
    }

    /**
     * 压缩上下文（2026-11-14）：一段话能说清"最近在写什么"。
     *
     * 它同时是两件事的地基：agent 对话（不用靠会话历史记人）和主动提醒（拿它判断说什么）。
     */
    private fun digest(q: Map<String, String>): JSONObject {
        val days = (q["days"]?.toIntOrNull() ?: 30).coerceIn(1, 3650)
        val to = System.currentTimeMillis()
        val from = to - days * 86_400_000L
        val db = app.container.database
        val light = runCatching { runBlocking { db.recordDao().getAllActiveLight() } }
            .getOrDefault(emptyList()).filter { (canLocked() || !it.isLocked) }.let { userLight(it) }

        val recent = light.filter { it.createdAt >= from }.sortedByDescending { it.createdAt }
        val emotions = runCatching { runBlocking { db.recordDao().idAndEmotions() } }
            .getOrDefault(emptyList()).associate { it.id to parseEmotions(it.emotionalTags) }

        val emoCount = HashMap<String, Int>()
        recent.forEach { r -> emotions[r.id]?.forEach { emoCount[it] = (emoCount[it] ?: 0) + 1 } }

        val fmtDay = java.text.SimpleDateFormat("MM-dd", java.util.Locale.ROOT)
        val byDay = recent.groupBy { fmtDay.format(java.util.Date(it.createdAt)) }

        // 久未触及（2026-11-14 修）：看**这个标签最后一条记录距今多少天**。
        // 原来是"最近 N 天没有新记录挂它"——那会把「心理问题」这种天天在写、
        // 但最近忘了打标签的主题误报成沉默，agent 拿去说"你三个月没碰了"，是错的。
        val createdAtById = light.associate { it.id to it.createdAt }
        val minSilentDays = (q["silent_days"]?.toIntOrNull() ?: 30).coerceIn(7, 3650)
        val silent = JSONArray()
        runCatching {
            runBlocking { db.tagDao().tagCounts() }
        }.getOrDefault(emptyList()).take(20).forEach { t ->
            val ids = runCatching { runBlocking { db.recordDao().recordIdsByTagName(t.name) } }
                .getOrDefault(emptyList())
            val lastAt = ids.mapNotNull { createdAtById[it] }.maxOrNull() ?: return@forEach
            val daysSince = ((to - lastAt) / 86_400_000L).toInt()
            if (ids.isNotEmpty() && daysSince >= minSilentDays) {
                silent.put(JSONObject()
                    .put("name", t.name)
                    .put("records", ids.size)
                    .put("last_at", lastAt)
                    .put("days_since", daysSince))
            }
        }


        val recentArr = JSONArray()
        recent.take(5).forEach { r ->
            recentArr.put(JSONObject()
                .put("id", r.id)
                .put("created_at", r.createdAt)
                .put("excerpt", r.content.replace(Regex("\\s+"), " ").take(60)))
        }

        val tagsArr = JSONArray()
        runCatching { runBlocking { db.tagDao().tagCounts() } }.getOrDefault(emptyList()).take(5).forEach {
            tagsArr.put(JSONObject().put("name", it.name).put("records", it.n))
        }
        return JSONObject()
            .put("ok", true)
            .put("schema_version", 1)
            .put("days", days)
            .put("records", recent.size)
            .put("days_since_last", if (recent.isEmpty()) -1 else ((to - recent.first().createdAt) / 86_400_000L).toInt())
            .put("busiest_day", byDay.maxByOrNull { it.value.size }?.let { JSONObject().put("day", it.key).put("records", it.value.size) } ?: JSONObject.NULL)
            .put("emotion_top", emoCount.entries.maxByOrNull { it.value }?.let { JSONObject().put("name", it.key).put("count", it.value) } ?: JSONObject.NULL)
            .put("top_tags", tagsArr)
            .put("silent_tags", silent)
            .put("recent", recentArr)
    }

    /**
     * 整理候选（2026-11-14）：告诉 agent "该整理什么"。
     *
     * 没有它，agent 只能等你开口；有了它，它可以主动提"这两个标签像同一个"。
     */
    private fun suggest(q: Map<String, String>): JSONObject {
        val db = app.container.database
        val light = runCatching { runBlocking { db.recordDao().getAllActiveLight() } }
            .getOrDefault(emptyList()).filter { (canLocked() || !it.isLocked) }.let { userLight(it) }

        // ① 近重复：用记录的代表向量比余弦（每条的块向量取平均）
        val rowsByRecord = runCatching { runBlocking { db.vectorDao().all() } }
            .getOrDefault(emptyList()).groupBy { it.recordId }
        val vecs = HashMap<String, FloatArray>()
        rowsByRecord.forEach { (id, rows) ->
            val vs = rows.mapNotNull { r -> runCatching { VectorIndexService.bytesToFloats(r.vec) }.getOrNull() }
            if (vs.isNotEmpty()) {
                val dim = vs.first().size
                val mean = FloatArray(dim)
                vs.forEach { v -> for (i in 0 until dim) mean[i] += v[i] }
                for (i in 0 until dim) mean[i] /= vs.size.toFloat()
                vecs[id] = mean
            }
        }
        val pairs = ArrayList<Triple<String, String, Double>>()
        val ids = vecs.keys.toList()
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                val a = vecs[ids[i]] ?: continue
                val b = vecs[ids[j]] ?: continue
                val c = runCatching { VectorMath.cosine(a, b) }.getOrDefault(0f).toDouble()
                if (c >= 0.94) pairs += Triple(ids[i], ids[j], c)
            }
        }
        val pairArr = JSONArray()
        pairs.sortedByDescending { it.third }.take(8).forEach { (a, b, c) ->
            val ra = light.firstOrNull { it.id == a }
            val rb = light.firstOrNull { it.id == b }
            if (ra != null && rb != null) {
                pairArr.put(JSONObject()
                    .put("a", a).put("b", b).put("score", round4(c))
                    .put("a_excerpt", ra.content.replace(Regex("\\s+"), " ").take(40))
                    .put("b_excerpt", rb.content.replace(Regex("\\s+"), " ").take(40)))
            }
        }

        // ② 近义标签：名字的字符重叠（"戒断反应" vs "戒断"）
        val tags = runCatching { runBlocking { db.tagDao().allTags() } }.getOrDefault(emptyList())
        val mergeArr = JSONArray()
        for (i in tags.indices) {
            for (j in i + 1 until tags.size) {
                val a = tags[i].name
                val b = tags[j].name
                if (a.length < 2 || b.length < 2) continue
                val shorter = if (a.length <= b.length) a else b
                val longer = if (a.length <= b.length) b else a
                if (longer.contains(shorter)) {
                    mergeArr.put(JSONObject()
                        .put("from", longer).put("to", shorter)
                        .put("reason", "名字包含「$shorter」"))
                }
            }
        }

        // ③ 没标签的记录
        val untagged = JSONArray()
        val counts = runCatching { runBlocking { db.tagDao().tagCounts() } }.getOrDefault(emptyList())
            .associate { it.name to it.n }
        var untaggedCount = 0
        light.forEach { r ->
            val has = runCatching { runBlocking { db.recordDao().tagNamesOf(r.id) } }.getOrDefault(emptyList())
            if (has.isEmpty()) {
                untaggedCount++
                if (untagged.length() < 5) {
                    untagged.put(JSONObject()
                        .put("id", r.id)
                        .put("excerpt", r.content.replace(Regex("\\s+"), " ").take(40)))
                }
            }
        }

        return JSONObject()
            .put("ok", true)
            .put("schema_version", 1)
            .put("similar_pairs", pairArr)
            .put("tag_merges", mergeArr)
            .put("untagged", JSONObject().put("count", untaggedCount).put("samples", untagged))
    }

    /** 情绪标签原始 JSON → 名字列表。解析不了就返回空，绝不抛。 */
    private fun parseEmotions(raw: String): List<String> = runCatching {
        if (raw.isBlank() || raw == "[]") return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            when (val v = arr.opt(i)) {
                is JSONObject -> v.optString("name").ifBlank { null }
                is String -> v.ifBlank { null }
                else -> null
            }
        }
    }.getOrDefault(emptyList())

    /**
     * 草稿列表（2026-11-14，只读）。
     *
     * 用户原话：「草稿应该暴露，不然到时候分享心路历程还要截图吗」。
     * 所以给读；**不给写**——草稿是还没成形的自己，AI 插进去是污染。
     */
    private fun drafts(): JSONObject {
        val db = app.container.database
        val list = runCatching { runBlocking { db.draftDao().all() } }.getOrDefault(emptyList())
        val arr = JSONArray()
        list.sortedByDescending { it.updatedAt }.forEach { d ->
            val blocks = runCatching { runBlocking { db.draftBlockDao().getByDraft(d.id) } }
                .getOrDefault(emptyList())
            arr.put(JSONObject()
                .put("id", d.id)
                .put("name", d.name)
                .put("created_at", d.createdAt)
                .put("updated_at", d.updatedAt)
                .put("archived", d.archived)
                .put("pinned", d.pinned)
                .put("blocks", blocks.size)
                .put("chars", blocks.sumOf { it.content.length }))
        }
        return JSONObject().put("ok", true).put("schema_version", 1).put("drafts", arr)
    }

    /** 草稿内容：每块带 kind（MINE/AI/QA/DISCUSS），能分清哪句是用户写的。 */
    private fun draftDetail(id: Long): JSONObject {
        if (id <= 0) return err("草稿 id 不合法", "bad_request")
        val db = app.container.database
        val draft = runCatching { runBlocking { db.draftDao().get(id) } }.getOrNull()
            ?: return err("没有这本草稿", "not_found")
        val blocks = runCatching { runBlocking { db.draftBlockDao().getByDraft(id) } }
            .getOrDefault(emptyList())
        val arr = JSONArray()
        blocks.sortedBy { it.createdAt }.forEach { b ->
            arr.put(JSONObject()
                .put("id", b.id)
                .put("kind", b.kind)
                .put("created_at", b.createdAt)
                .put("content", b.content))
        }
        return JSONObject()
            .put("ok", true)
            .put("schema_version", 1)
            .put("id", draft.id)
            .put("name", draft.name)
            .put("created_at", draft.createdAt)
            .put("updated_at", draft.updatedAt)
            .put("blocks", arr)
    }

    /** 最近的请求记录（2026-11-14）。让用户能回看"agent 读了什么"。 */
    private fun auditLog(q: Map<String, String>): JSONObject {
        val limit = (q["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val arr = JSONArray()
        AuditLog.recent(limit).forEach { e -> arr.put(e) }
        return JSONObject()
            .put("ok", true)
            .put("schema_version", 1)
            .put("total", AgentServerState.requests)
            .put("entries", arr)
    }

    private fun audit(method: String, path: String, status: Int) {
        AuditLog.add(JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("method", method)
            .put("path", path)
            .put("status", status))
    }

    private fun commandBus(): AgentCommandBus = AgentCommandBus(app)

    /** 命令队列（待批准 / 最近结果）。 */
    private fun commands(): JSONObject {
        val bus = commandBus()
        val list = runCatching { bus.pending() }.getOrDefault(emptyList())
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject()
                .put("id", c.id)
                .put("kind", c.kind)
                .put("payload", c.payload)
                .put("created_at", c.createdAt))
        }
        return JSONObject()
            .put("ok", true)
            .put("schema_version", 1)
            .put("mode", bus.mode().value)
            .put("pending", arr)
    }

    /** 目录（2026-11-13）：让 agent 先看"有什么"，再决定怎么查。 */
    private fun facets(): JSONObject {
        val db = app.container.database
        val tags = JSONArray()
        runBlocking { db.tagDao().tagCounts() }.forEach {
            tags.put(JSONObject().put("name", it.name).put("records", it.n))
        }
        val protocols = JSONArray()
        runBlocking { db.protocolDao().protocolCounts() }.forEach {
            protocols.put(JSONObject().put("name", it.name).put("records", it.n))
        }
        val origins = JSONArray()
        val originNames = runBlocking { db.originDao().all() }.associate { it.id to it.name }
        runBlocking { db.recordDao().originCounts() }.forEach {
            origins.put(JSONObject()
                .put("id", it.originId ?: JSONObject.NULL)
                .put("name", it.originId?.let { id -> originNames[id] } ?: "未标")
                .put("records", it.n))
        }
        val light = runBlocking { db.recordDao().getAllActiveLight() }.filter { (canLocked() || !it.isLocked) }.let { userLight(it) }
        // 2026-11-21：区域也进 facets —— agent 老会话里的 memore_facets 工具不必等插件更新，
        // 就能看到有哪些区域、各多少条（区域名只有这里和 /zones 能拿到）。
        val zonesArr = JSONArray()
        runCatching {
            val zoneList = runBlocking { db.zoneDao().all() }
            val zoneMembers = runBlocking { db.zoneDao().allMembers() }.groupBy { it.zoneId }
            zoneList.forEach { z ->
                zonesArr.put(
                    JSONObject()
                        .put("id", z.id)
                        .put("name", z.name)
                        .put("builtin", z.builtin)
                        .put("records", zoneMembers[z.id].orEmpty().size)
                )
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("schema_version", 1)
            .put("records", light.size)
            .put("oldest", light.minOfOrNull { it.createdAt } ?: 0L)
            .put("newest", light.maxOfOrNull { it.createdAt } ?: 0L)
            .put("tags", tags)
            .put("protocols", protocols)
            .put("origins", origins)
            .put("zones", zonesArr)
    }

    private fun namesOf(table: String): JSONObject {
        val names = runBlocking {
            if (table == "tags") app.container.database.tagDao().allTags().map { it.name }
            else emptyList()
        }
        return JSONObject().put("ok", true).put("schema_version", 1).put("names", JSONArray(names))
    }

    private fun origins(): JSONObject {
        val list = runBlocking { app.container.database.originDao().all() }
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject()
                .put("id", it.id)
                .put("name", it.name)
                .put("w_cluster", it.wCluster)
                .put("w_link", it.wLink)
                .put("w_stats", it.wStats)
                .put("link_threshold", it.linkThreshold))
        }
        return JSONObject().put("ok", true).put("schema_version", 1).put("origins", arr)
    }

    /** 列表里截断正文（2026-11-13）：一次查询返回十万字没有意义，单条用 /records/{id} 取全文。 */
    private fun lightJson(r: LightRecord, limit: Int = 400): JSONObject {
        val text = r.content
        val shown = if (text.length <= limit) text
        else text.take(limit).trimEnd() + " …（共 " + text.length + " 字）"
        return JSONObject()
            .put("id", r.id)
            .put("created_at", r.createdAt)
            .put("origin_id", r.originId ?: JSONObject.NULL)
            .put("content", shown)
            .put("chars", text.length)
            .put("notes", r.notes.take(120))
    }

    /** 只要元数据（2026-11-15）：include=meta，或 token 没有 read:content。 */
    /**
     * 给列表里的每条记录补 `attachment_count`（2026-11-21）。
     * 以前列表完全看不到附件，agent 翻记录时不知道哪条带图。
     */
    private fun attachCountsInto(arr: JSONArray, ids: List<String>) {
        if (ids.isEmpty()) return
        val counts = runCatching {
            runBlocking { app.container.database.attachmentDao().countForRecords(ids) }
        }.getOrDefault(emptyList()).associate { it.recordId to it.n }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            o.put("attachment_count", counts[id] ?: 0)
        }
    }

    private fun metaJson(r: LightRecord): JSONObject = JSONObject()
        .put("id", r.id)
        .put("created_at", r.createdAt)
        .put("origin_id", r.originId ?: JSONObject.NULL)
        .put("type", r.type)
        .put("chars", r.content.length)

    /** 全文（2026-11-15）：full=1 时用；记录本体缺失时退回投影。 */
    private fun fullJson(r: LightRecord, rec: memore.app.data.model.Record?): JSONObject {
        val o = metaJson(r)
        o.put("content", rec?.content ?: r.content)
        o.put("notes", rec?.notes ?: r.notes)
        if (rec != null) {
            o.put("updated_at", rec.updatedAt)
            o.put("is_anchored", rec.isAnchored)
        }
        return o
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 读权限档位（2026-11-15，2026-11-16 改）。
     *
     * 一份 token 现在带 scope：默认全读 + 只提议写。想收紧就在设置里改 `agent_scopes`
     * （逗号分隔）。
     *
     * **锁定记录的正文**：v5 门闩式之后库里就是明文，能不能给 agent 由 `read:locked` 决定。
     * 默认 scope 里带着它 → 默认给；去掉它 → 读端点会把锁定记录过滤掉，
     * `/lock/state` 的 `locked_content` 会变成 "never"。
     */
    private fun scopes(): Set<String> = runCatching {
        (app.preferences.getString("agent_scopes", null) ?: DEFAULT_SCOPES.joinToString(","))
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(DEFAULT_SCOPES)

    private fun can(scope: String): Boolean = scopes().contains(scope)

    /** 2026-11-16：门闩式之后，锁定记录的正文就在库里；能不能给 agent 由 `read:locked` 决定。 */
    private fun canLocked(): Boolean = can("read:locked")

    /** 是否本机（手机自己回环）。批准/拒绝/撤回只认这个。 */
    private fun isLoopbackHost(host: String?): Boolean {
        val h = (host ?: "").removePrefix("::ffff:").trim()
        return h == "127.0.0.1" || h == "::1" || h == "0:0:0:0:0:0:0:1"
    }

    private fun tokenMatches(token: String?): Boolean {
        val expected = app.preferences.getString("agent_token", null) ?: return false
        return token != null && token == expected
    }

    private fun readHead(input: BufferedInputStream): String? {
        val buf = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.toString()
            if (buf.length > 16384) return null
        }
    }

    private fun respond(out: OutputStream, code: Int, body: JSONObject) {
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        out.write(header)
        out.write(payload)
        out.flush()
    }

    /** 错误带 code：客户端能分清是没权限、不存在，还是服务端出错。 */
    private fun err(message: String, code: String = "bad_request"): JSONObject =
        JSONObject().put("ok", false).put("code", code).put("error", message)

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { it.isNotBlank() }.mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null
            else URLDecoder.decode(it.substring(0, i), "UTF-8") to
                URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()

    /**
     * 只从**连续汉字段**里切 bigram（2026-11-13）。
     *
     * 之前对整段文本切，英文单词会产出 me/er/su 这种跨词碎片，
     * 而一篇 AI 长文里几乎必然包含它们——于是长文永远赢，短记录被埋。
     */
    private fun bigrams(text: String): Set<String> {
        val out = HashSet<String>()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= 2) {
                for (i in 0 until run.length - 1) out += run.substring(i, i + 2)
            } else if (run.length == 1) {
                out += run.toString()
            }
            run.setLength(0)
        }
        for (ch in text) {
            if (ch in '\u4e00'..'\u9fa5') run.append(ch) else flush()
        }
        flush()
        return out
    }

    /**
     * 命中率 × 长度惩罚（2026-11-13）：500 字以后开始衰减，
     * 否则一篇一万字的长文靠"包含的字组多"压过一条真正相关的短记录。
     */
    private fun score(grams: Set<String>, doc: String): Double {
        if (grams.isEmpty()) return 0.0
        val d = bigrams(doc)
        if (d.isEmpty()) return 0.0
        val hit = grams.count { it in d }
        val base = hit.toDouble() / grams.size
        val penalty = 1.0 / (1.0 + ln(1.0 + doc.length / 500.0))
        return base * (0.4 + 0.6 * penalty)
    }

    // ---------------------------------------------------------------- 供设置页显示

    companion object {
        /** 动作接口的路径形态（2026-11-15）。 */
        private val ACTION_APPROVE_RE = Regex("^/actions/([^/]+)/approve$")
        private val ACTION_REJECT_RE = Regex("^/actions/([^/]+)/reject$")
        private val ACTION_UNDO_RE = Regex("^/actions/([^/]+)/undo$")
        private val ACTION_ONE_RE = Regex("^/actions/([^/]+)$")
    private val RECORD_VERSIONS_RE = Regex("^/records/([^/]+)/versions$")
    private val TICK_RECORDS_RE = Regex("^/ticks/([^/]+)/records$")
    private val ATTACHMENT_CONTENT_RE = Regex("^/attachments/(\\d+)/content$")

        /** 默认读权限（2026-11-15）：全读 + 只提议写。锁定记录正文永远不给。 */
        private val DEFAULT_SCOPES = setOf(
            "read:meta", "read:content", "read:locked", "write:propose"
        )

        /** 长轮询并发闸门：最多 2 条挂着，避免占满线程池。 */
        private val EVENT_SLOTS = java.util.concurrent.Semaphore(2)

        fun newToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
        }

        fun formatStart(ms: Long): String =
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    }
}

/**
 * 审计环（2026-11-14）。
 *
 * 只留最近 200 条，内存表。它的用途不是合规，是让**用户能回看**：
 * "今天 agent 到底读了哪些记录"。持久化留到有需要再说。
 */
object AuditLog {
    private const val CAP = 200
    private val ring = ArrayDeque<JSONObject>()

    fun add(entry: JSONObject) {
        synchronized(ring) {
            ring.addLast(entry)
            while (ring.size > CAP) ring.removeFirst()
        }
    }

    fun recent(limit: Int): List<JSONObject> = synchronized(ring) {
        ring.toList().takeLast(limit).reversed()
    }
}

/** 令牌桶：每 10 秒 40 次，桶容量 40。 */
object RateLimiter {
    private const val CAP = 40
    private const val WINDOW_MS = 10_000L
    private var tokens = CAP.toDouble()
    private var last = System.currentTimeMillis()

    fun allow(): Boolean = synchronized(this) {
        val now = System.currentTimeMillis()
        val add = (now - last).toDouble() / WINDOW_MS * CAP
        if (add > 0) {
            tokens = (tokens + add).coerceAtMost(CAP.toDouble())
            last = now
        }
        if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }
}




