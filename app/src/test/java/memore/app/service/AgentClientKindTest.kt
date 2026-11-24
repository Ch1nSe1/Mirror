package memore.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「最近连接」的来源分类（2026-11-18）。
 *
 * 这行字号很小，判错却最要命：把公网误判成局域网，等于把"库正被外网访问"
 * 这条告警吞掉。所以按段位逐条钉住。
 */
class AgentClientKindTest {

    @Test
    fun privateRangesAreLan() {
        assertEquals("局域网", AgentServerState.classifyClient("192.168.1.2"))
        assertEquals("局域网", AgentServerState.classifyClient("10.0.0.9"))
        assertEquals("局域网", AgentServerState.classifyClient("172.16.5.4"))
        assertEquals("局域网", AgentServerState.classifyClient("172.31.255.1"))
    }

    @Test
    fun tailscaleIsItsOwnKind() {
        assertEquals("Tailscale", AgentServerState.classifyClient("100.64.0.1"))
        assertEquals("Tailscale", AgentServerState.classifyClient("100.85.94.12"))
        assertEquals("Tailscale", AgentServerState.classifyClient("100.127.255.254"))
    }

    @Test
    fun publicAndIpv6ArePublic() {
        assertEquals("公网", AgentServerState.classifyClient("8.8.8.8"))
        assertEquals("公网", AgentServerState.classifyClient("172.32.0.1"))
        assertEquals("公网", AgentServerState.classifyClient("2001:db8::1"))
        // Docker / 隧道的常见形态：IPv4-mapped IPv6
        assertEquals("局域网", AgentServerState.classifyClient("::ffff:192.168.1.2"))
        assertEquals("公网", AgentServerState.classifyClient("::ffff:8.8.8.8"))
    }

    @Test
    fun loopbackIsItsOwnKind() {
        assertEquals("本机", AgentServerState.classifyClient("127.0.0.1"))
    }

    @Test
    fun unknownStaysEmpty() {
        assertEquals("", AgentServerState.classifyClient(null))
        assertEquals("", AgentServerState.classifyClient(""))
    }
}
