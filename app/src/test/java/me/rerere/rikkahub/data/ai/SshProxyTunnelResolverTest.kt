package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/**
 * 只测 [SshProxyTunnelResolver] 里的纯函数。
 *
 * 组件本身依赖 SettingsStore 和 SshTunnelManager，构造它们需要 Android 运行时，单测里用不了；
 * [me.rerere.rikkahub.data.ai.isReconnectableCause] 就是 shouldReconnect 的实现，直接测它。
 */
class SshProxyTunnelResolverTest {
    @Test
    fun `proxy port selects the tunnel listening on it`() {
        val tunnels = listOf("claude" to 8080, "openai" to 9090)

        assertEquals(
            "openai",
            matchTunnelByPort(proxyPort = 9090, baseUrlPort = null, tunnels = tunnels),
        )
    }

    @Test
    fun `loopback base url port selects the tunnel`() {
        val tunnels = listOf("claude" to 8080, "openai" to 9090)

        assertEquals(
            "claude",
            matchTunnelByPort(proxyPort = null, baseUrlPort = 8080, tunnels = tunnels),
        )
        assertEquals(8080, baseUrlPortOf("http://127.0.0.1:8080/v1"))
    }

    @Test
    fun `no matching port means no tunnel`() {
        val tunnels = listOf("claude" to 8080)

        assertNull(matchTunnelByPort(proxyPort = 7890, baseUrlPort = 3000, tunnels = tunnels))
        assertNull(matchTunnelByPort(proxyPort = null, baseUrlPort = null, tunnels = tunnels))
        assertNull(matchTunnelByPort(proxyPort = 8080, baseUrlPort = null, tunnels = emptyList()))
    }

    @Test
    fun `the first tunnel wins when several listen on the same port`() {
        val tunnels = listOf("first" to 8080, "second" to 8080)

        assertEquals("first", matchTunnelByPort(proxyPort = 8080, baseUrlPort = null, tunnels = tunnels))
    }

    @Test
    fun `remote base url is never matched`() {
        // 远端域名和隧道端口相同纯属巧合
        assertNull(baseUrlPortOf("https://api.openai.com:8080/v1"))
        assertNull(baseUrlPortOf("https://api.openai.com/v1"))
        assertEquals(8080, baseUrlPortOf("http://localhost:8080"))
        assertEquals(3000, baseUrlPortOf("http://[::1]:3000/v1"))
    }

    @Test
    fun `proxy url port is parsed in every accepted form`() {
        assertEquals(7890, proxyPortOf("http://127.0.0.1:7890"))
        assertEquals(1080, proxyPortOf("socks5://localhost:1080"))
        assertEquals(8080, proxyPortOf("127.0.0.1:8080"))
        // 带认证信息时 toProxyOrNull 不认，退回按 URI 取端口
        assertEquals(8081, proxyPortOf("http://user:pass@127.0.0.1:8081"))
        assertNull(proxyPortOf(""))
        assertNull(proxyPortOf("http://127.0.0.1"))
    }

    @Test
    fun `timeouts and disconnects are worth reconnecting`() {
        assertTrue(isReconnectableCause(SocketTimeoutException("timeout")))
        assertTrue(isReconnectableCause(ConnectException("refused")))
        assertTrue(isReconnectableCause(NoRouteToHostException("unreachable")))
        assertTrue(isReconnectableCause(SocketException("connection reset")))
        assertTrue(isReconnectableCause(EOFException("unexpected end of stream")))
        assertTrue(isReconnectableCause(InterruptedIOException("timeout")))
        assertTrue(isReconnectableCause(UnknownHostException("127.0.0.1")))
        assertTrue(isReconnectableCause(SSLException("handshake failed")))
    }

    @Test
    fun `other failures and cancellation do not reconnect`() {
        assertFalse(isReconnectableCause(IllegalStateException("bad request")))
        assertFalse(isReconnectableCause(RuntimeException("parse failed")))
        // 用户点了「停止生成」：取消不是网络故障
        assertFalse(isReconnectableCause(CancellationException("stop")))
    }
}
