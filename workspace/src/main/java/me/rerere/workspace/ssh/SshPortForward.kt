package me.rerere.workspace.ssh

import android.util.Log
import com.jcraft.jsch.ChannelDirectTCPIP
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一条本地端口转发, 等价于 OpenSSH 的 `ssh -L`: 本地 `bindAddress` 的 [localPort] 上收到的连接,
 * 经 SSH 连接上的 direct-tcpip channel 转发到 `remoteHost:remotePort`。
 *
 * 转发打开期间一直占用所在连接, 不会被 [SshConnectionPool] 的空闲清理关掉; 用完必须 [close]。
 * 每条本地连接单独打开 channel, 打不开只影响这一条连接, 不影响已建立的转发和后续连接。
 */
class SshPortForward internal constructor(
    private val connection: SshConnection,
    bindAddress: String,
    requestedPort: Int,
    private val remoteHost: String,
    private val remotePort: Int,
    private val channelTimeoutMillis: Int,
    private val onClose: () -> Unit,
) : Closeable {
    // reuseAddress 要在 bind 之前设置: 端口还处于 TIME_WAIT 时也能直接监听; 端口被占用时 bind 抛 BindException
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(bindAddress.ifEmpty { LOOPBACK }, requestedPort))
    }

    /** 实际监听的端口: 打开时传 0 由系统分配, 构造完成后从这里读取 */
    val localPort: Int = server.localPort

    private val closed = AtomicBoolean(false)

    // 当前活跃的转发连接数: accept 之后加一, 连接关闭或 channel 打开失败时减一
    private val openConnections = AtomicInteger()

    // accept 之后、channel 打开之前的 socket 也要能被 close 打断, 否则 close 要等 channel 打开超时
    private val pendingSockets = ConcurrentHashMap.newKeySet<Socket>()

    // 已经建立转发的连接, close 时统一关闭
    private val activePeers = ConcurrentHashMap.newKeySet<Peer>()

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ssh-forward-$localPort").apply { isDaemon = true }
    }

    private val acceptThread = Thread({ acceptLoop() }, "ssh-forward-$localPort-accept").apply { isDaemon = true }

    /** 是否还在监听; [close] 之后为 false */
    val isActive: Boolean
        get() = !closed.get() && !server.isClosed

    /**
     * 转发可用: 本地还在监听, 且底层 SSH 会话没有断开。
     *
     * [isActive] 只看本地 ServerSocket: 会话断开后端口照样 accept, 要到 [openPeer] 才发现失败,
     * 调用方看到的是一次莫名其妙的中断。判断「要不要重建」时用这个。
     */
    val isHealthy: Boolean
        get() = isActive && connection.isConnected

    /** 当前活跃的转发连接数 */
    val activeConnections: Int
        get() = openConnections.get()

    init {
        acceptThread.start()
    }

    /**
     * 停止监听并关闭所有转发连接, 最后释放连接池的引用计数。
     * 幂等; 只做本地操作, 不等待网络回应, 不会阻塞调用方。
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // 先停掉 accept, 不再有新的连接进来
        runCatching { server.close() }
        // 还没打开 channel 的连接先关掉 socket, 唤醒可能卡在 channel 打开上的线程
        pendingSockets.forEach { runCatching { it.close() } }
        pendingSockets.clear()
        activePeers.forEach { it.close() }
        activePeers.clear()
        executor.shutdownNow()
        // 连接池的引用计数只释放一次; 释放失败也不能影响已经完成的关闭
        runCatching { onClose() }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                // close() 关闭 ServerSocket 时 accept 会抛异常, 属于正常退出
                if (!closed.get()) warn("accept on port $localPort failed", e)
                return
            }
            pendingSockets += socket
            openConnections.incrementAndGet()
            runCatching { socket.tcpNoDelay = true }
            try {
                executor.execute { handle(socket) }
            } catch (e: RejectedExecutionException) {
                // close() 之后才 accept 到的连接, 直接关掉
                pendingSockets -= socket
                runCatching { socket.close() }
                openConnections.decrementAndGet()
            }
        }
    }

    /** 为一条本地连接打开 direct-tcpip channel, 双向拷贝到连接结束 */
    private fun handle(socket: Socket) {
        val peer = try {
            openPeer(socket)
        } catch (e: Exception) {
            // 单个连接打不开只记日志: 既不能打断 accept 循环, 也不能影响连接池里的连接
            warn("open direct-tcpip channel to $remoteHost:$remotePort failed", e)
            pendingSockets -= socket
            runCatching { socket.close() }
            openConnections.decrementAndGet()
            return
        }
        pendingSockets -= socket
        activePeers += peer
        try {
            // close() 可能刚好扫完 activePeers, 这里再看一次, 避免刚建立的连接留在已关闭的转发里
            if (closed.get()) return
            // 一个方向交给线程池, 当前线程跑另一个方向; 任一方向结束都会关掉两端, 把对方唤醒
            val reversed = try {
                executor.submit { peer.pump(fromRemote = true) }
            } catch (e: RejectedExecutionException) {
                // close() 已经关闭线程池, 只跑本方向就够了
                null
            }
            peer.pump(fromRemote = false)
            reversed?.let { runCatching { it.get() } }
        } finally {
            activePeers -= peer
            peer.close()
            openConnections.decrementAndGet()
        }
    }

    /** 打开一条 direct-tcpip channel; 输入输出流必须在 connect 之前取, 否则开头的回应会丢 */
    private fun openPeer(socket: Socket): Peer {
        val channel = connection.rawSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
        channel.setHost(remoteHost)
        channel.setPort(remotePort)
        // 源地址只会写进 sshd 的转发日志, 不做校验; 用回环地址表示连接来自本机转发
        channel.setOrgIPAddress(LOOPBACK)
        channel.setOrgPort(socket.port)
        val input = channel.inputStream
        val output = channel.outputStream
        try {
            channel.connect(channelTimeoutMillis)
        } catch (e: Exception) {
            runCatching { channel.disconnect() }
            throw e
        }
        return Peer(socket, channel, input, output)
    }

    /**
     * 一条已建立的转发: 本地 socket 与 direct-tcpip channel 两端。
     *
     * 不给 channel 设置自定义流, JSch 默认接的是管道流, [ChannelDirectTCPIP.getInputStream] 和
     * [ChannelDirectTCPIP.getOutputStream] 就能直接做双向拷贝。
     */
    private class Peer(
        private val socket: Socket,
        private val channel: ChannelDirectTCPIP,
        private val channelInput: InputStream,
        private val channelOutput: OutputStream,
    ) {
        private val closed = AtomicBoolean(false)

        /** 把一个方向的数据搬到另一个方向, 结束时关闭整条转发 */
        fun pump(fromRemote: Boolean) {
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                val input = if (fromRemote) channelInput else socket.getInputStream()
                val output = if (fromRemote) socket.getOutputStream() else channelOutput
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) return
                    output.write(buffer, 0, read)
                    // channel 和 socket 的流都带缓冲, 不 flush 会攒满缓冲区才发出去
                    output.flush()
                }
            } catch (e: IOException) {
                // 对端正常关闭、连接断开或被 close() 打断都会走到这里, 不是错误
            } finally {
                close()
            }
        }

        /** 关闭两端; 只执行一次, 任一方向先结束时由它唤醒另一个方向的读取 */
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { channelInput.close() }
            runCatching { channelOutput.close() }
            runCatching { channel.disconnect() }
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val TAG = "SshPortForward"
        const val LOOPBACK = "127.0.0.1"
        const val BUFFER_SIZE = 8 * 1024

        // workspace 的单测跑在 JVM 上, 没有 Android 运行时; 日志失败不能影响转发的收尾
        fun warn(message: String, error: Throwable) {
            runCatching { Log.w(TAG, message, error) }
        }
    }
}
