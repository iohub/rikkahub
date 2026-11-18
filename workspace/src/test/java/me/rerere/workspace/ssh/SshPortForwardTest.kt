package me.rerere.workspace.ssh

import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** 本地端口转发: 本地连接经 SSH 的 direct-tcpip channel 到达 sshd 能访问到的地址 */
class SshPortForwardTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: SshServer
    private lateinit var echo: ServerSocket
    private lateinit var pool: SshConnectionPool
    private lateinit var config: SshConnectionConfig

    @Before
    fun setUp() {
        echo = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    val socket = echo.accept()
                    thread(isDaemon = true) { echoAll(socket) }
                }
            }
        }
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(tempFolder.root, "host_key").toPath())
            passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            subsystemFactories = listOf(SftpSubsystemFactory())
            start()
        }
        pool = SshConnectionPool()
        // 转发场景的 remoteDir 不参与, 用根目录
        val hostKey = assertThrows(SshUnknownHostKeyException::class.java) {
            SshConnection.probe(rawConfig(hostKey = null))
        }.hostKey
        config = rawConfig(hostKey)
    }

    @After
    fun tearDown() {
        pool.close()
        server.stop(true)
        runCatching { echo.close() }
    }

    @Test
    fun forwardsLocalConnectionsToRemoteAddress() {
        val forward = pool.openLocalForward(ID, config, "127.0.0.1", 0, "127.0.0.1", echo.localPort)
        assertTrue(forward.localPort > 0)
        assertTrue(forward.isActive)
        val payload = ByteArray(256 * 1024) { index -> (index % 251).toByte() }
        Socket(InetAddress.getLoopbackAddress(), forward.localPort).use { socket ->
            socket.soTimeout = 15_000
            socket.getOutputStream().apply {
                write(payload)
                flush()
            }
            assertArrayEquals(payload, socket.getInputStream().readNBytes(payload.size))
        }
        assertTrue(waitUntil(5_000) { forward.activeConnections == 0 })
        forward.close()
        assertFalse(forward.isActive)
        // 关闭后本地端口不再监听
        assertThrows(IOException::class.java) {
            Socket(InetAddress.getLoopbackAddress(), forward.localPort).close()
        }
    }

    @Test
    fun openForwardKeepsTheConnectionOutOfIdleSweep() {
        // 空闲清理比转发存活时间还短: 转发期间连接不能被回收
        SshConnectionPool(idleTimeoutMillis = 300, sweepIntervalMillis = 100).use { shortLived ->
            val forward = shortLived.openLocalForward(ID, config, "", 0, "127.0.0.1", echo.localPort)
            try {
                Thread.sleep(800)
                assertTrue(shortLived.isConnected(ID))
                assertTrue(forward.isActive)
                Socket(InetAddress.getLoopbackAddress(), forward.localPort).use { socket ->
                    socket.soTimeout = 15_000
                    socket.getOutputStream().apply {
                        write(REQUEST)
                        flush()
                    }
                    assertArrayEquals(REQUEST, socket.getInputStream().readNBytes(REQUEST.size))
                }
            } finally {
                forward.close()
            }
            // 转发关闭后连接不再被占用, 空闲清理能把它回收
            assertTrue(waitUntil(5_000) { !shortLived.isConnected(ID) })
        }
    }

    @Test
    fun boundLocalPortFailsWithoutEvictingTheConnection() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { taken ->
            assertThrows(BindException::class.java) {
                pool.openLocalForward(ID, config, "127.0.0.1", taken.localPort, "127.0.0.1", echo.localPort)
            }
        }
        // 本地端口占用与 SSH 连接无关: 连接还在池里, 可以继续用来打开转发
        assertTrue(pool.isConnected(ID))
        pool.openLocalForward(ID, config, "127.0.0.1", 0, "127.0.0.1", echo.localPort).close()
    }

    @Test
    fun rejectsPortsOutOfRange() {
        assertThrows(IllegalArgumentException::class.java) {
            pool.openLocalForward(ID, config, "127.0.0.1", 65_536, "127.0.0.1", 80)
        }
        assertThrows(IllegalArgumentException::class.java) {
            pool.openLocalForward(ID, config, "127.0.0.1", 0, "127.0.0.1", 0)
        }
    }

    private fun rawConfig(hostKey: SshHostKey?) = SshConnectionConfig(
        host = "127.0.0.1",
        port = server.port,
        username = USER,
        authType = SshAuthType.PASSWORD,
        password = PASSWORD,
        remoteDir = "/",
        hostKey = hostKey,
    )

    private fun echoAll(socket: Socket) {
        socket.use {
            val buffer = ByteArray(8 * 1024)
            val output = it.getOutputStream()
            while (true) {
                val read = it.getInputStream().read(buffer)
                if (read < 0) return
                output.write(buffer, 0, read)
                output.flush()
            }
        }
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private companion object {
        const val ID = "forward-test"
        const val USER = "tester"
        const val PASSWORD = "secret"
        val REQUEST = "hello over the tunnel".toByteArray()
    }
}
