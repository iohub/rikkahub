package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.SshTunnelSetting
import me.rerere.rikkahub.data.model.displayName
import me.rerere.rikkahub.data.model.toConnectionConfig
import me.rerere.rikkahub.data.model.validationError
import me.rerere.workspace.ssh.SshConnectionPool
import me.rerere.workspace.ssh.SshHostKey
import me.rerere.workspace.ssh.SshHostKeyChangedException
import me.rerere.workspace.ssh.SshPortForward
import me.rerere.workspace.ssh.SshUnknownHostKeyException
import java.net.BindException
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "SshTunnelManager"

/** 重连一条隧道最多等这么久：请求重试前调用, 不能让一次卡住的建连无限期拖住生成 */
private const val ENSURE_TIMEOUT_MS = 30_000L

/**
 * SSH 本地端口转发的运行时: 按 [SshTunnelSetting] 里的配置打开转发, 并把运行状态暴露成 [states]。
 *
 * 配置本身存在设置里, 这里只持有 [SshPortForward] 句柄和状态:
 * - 打开转发是阻塞的网络调用, 全部在 [Dispatchers.IO] 上执行, 界面线程只读 [states];
 * - 同一 id 的启动/停止串行执行, 不同 id 之间互不阻塞 (建连可能要等几十秒);
 * - 任何失败都只写进 [TunnelState.error], 不抛到协程外, 不能让进程崩溃。
 */
class SshTunnelManager(
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val sshPool: SshConnectionPool,
) {
    enum class Phase {
        STOPPED,
        CONNECTING,
        RUNNING,
        ERROR,
    }

    data class TunnelState(
        val id: String,
        val phase: Phase = Phase.STOPPED,
        /** RUNNING 时为实际监听的本地端口 (配置里选随机端口时和 [SshTunnelSetting.localPort] 不同) */
        val localPort: Int = 0,
        /** 失败原因, 直接显示给用户 */
        val error: String? = null,
        /** 不为 null 表示需要用户确认指纹, 确认后调 [trustHostKey] */
        val pendingHostKey: SshHostKey? = null,
    )

    // id -> 运行中的转发句柄, 只在持有对应 id 的锁时增删
    private val forwards = ConcurrentHashMap<String, SshPortForward>()

    // 同一 id 的操作串行化, 保证不会同时打开两条转发
    private val locks = ConcurrentHashMap<String, Mutex>()

    private val _states = MutableStateFlow<Map<String, TunnelState>>(emptyMap())

    /** key 是 [SshTunnelSetting.id] */
    val states: StateFlow<Map<String, TunnelState>> = _states.asStateFlow()

    /**
     * 各隧道当前实际监听的本地端口（id -> 端口），未知或未运行时不出现在结果里。
     *
     * 句柄上的端口优先：随机分配端口时 [SshTunnelSetting.localPort] 是 0，只有这里能拿到真实端口，
     * 重建和「这次请求是不是走这条隧道」的匹配都要用它。
     */
    fun localPorts(): Map<String, Int> = buildMap {
        _states.value.forEach { (id, state) ->
            state.localPort.takeIf { it > 0 }?.let { put(id, it) }
        }
        forwards.forEach { (id, forward) ->
            forward.localPort.takeIf { it > 0 }?.let { put(id, it) }
        }
    }

    // shutdown 之后才建好的连接要立刻关掉, 否则服务已经停了转发还留着
    @Volatile
    private var shutDown = false

    /** 开始一条转发; 立即返回, 状态从 [states] 里读。已经跑着的会先关掉旧的再重连。 */
    fun start(id: String) {
        shutDown = false
        appScope.launch(Dispatchers.IO) { open(id) }
    }

    /** 换配置后重连: 先关掉旧句柄再按当前设置重连 */
    fun restart(id: String) {
        shutDown = false
        appScope.launch(Dispatchers.IO) {
            lockOf(id).withLock {
                closeHandle(id)
                openLocked(id)
            }
        }
    }

    /**
     * 确保一条隧道可用：已经在跑且底层会话健康时直接返回，否则重建一次并等建连结束。
     *
     * 与 [restart] 的区别：restart 是 fire-and-forget，这里能 await 结果，供请求重试前使用。
     * 用户主动关掉的隧道（`enabled == false`）不会被拉起，[shutDown] 之后也不重连：
     * 自动重连只能修「本该在跑却断了」的隧道，不能违背用户的意愿复活一条隧道。
     *
     * 重建会尽量复用原来的本地端口：随机分配端口（[SshTunnelSetting.localPort] 为 0）时重建后端口会变，
     * 用户填的 `127.0.0.1:<port>` 代理地址就失效了。
     *
     * @return 结束时这条隧道是否处于 [Phase.RUNNING]
     */
    suspend fun ensureRunning(id: String): Boolean {
        if (shutDown) {
            Log.i(TAG, "Skip reconnecting tunnel $id: manager is shut down")
            return false
        }
        // 建连本身有超时, 但等同一 id 上正在进行的操作可能更久: 请求重试不该被无限期拖住
        return withTimeoutOrNull(ENSURE_TIMEOUT_MS) {
            lockOf(id).withLock {
                val tunnel = settingsStore.awaitLoaded().sshTunnels.find { it.id == id }
                if (tunnel == null || !tunnel.enabled) {
                    Log.i(TAG, "Skip reconnecting tunnel $id: not configured or disabled by user")
                    return@withLock false
                }
                val current = forwards[id]
                if (current != null && current.isHealthy && _states.value[id]?.phase == Phase.RUNNING) {
                    Log.i(TAG, "Tunnel $id is still healthy on port ${current.localPort}")
                    return@withLock true
                }
                // 关句柄之前记下正在用的端口, 重建时复用它而不是重新随机分配
                val previousPort = (forwards[id]?.localPort ?: _states.value[id]?.localPort)
                    ?.takeIf { it in 1..65535 }
                Log.i(TAG, "Reconnecting tunnel $id (previous port: ${previousPort ?: "unknown"})")
                closeHandle(id)
                openLocked(id, requestedPortOverride = previousPort)
                if (_states.value[id]?.phase == Phase.RUNNING) return@withLock true
                // 原端口已被别的进程占用时退回配置端口再试一次, 这一次端口可能会变
                if (previousPort != null && previousPort != tunnel.localPort) {
                    Log.w(TAG, "Reconnect tunnel $id on port $previousPort failed, retrying with configured port")
                    openLocked(id)
                    return@withLock _states.value[id]?.phase == Phase.RUNNING
                }
                false
            }
        } ?: run {
            Log.w(TAG, "Reconnecting tunnel $id timed out after ${ENSURE_TIMEOUT_MS}ms")
            false
        }
    }

    /** 停止一条转发, 并把 enabled 置回 false (用户明确不想让它自己恢复) */
    fun stop(id: String) {
        appScope.launch(Dispatchers.IO) {
            lockOf(id).withLock {
                closeHandle(id)
                setState(id) { TunnelState(id) }
                updateSetting(id) { it.copy(enabled = false) }
                Log.i(TAG, "Tunnel $id stopped")
            }
        }
    }

    /** 按设置里的期望状态启动所有 enabled 的转发; 串行建连, 避免一次开太多连接 */
    suspend fun startAllEnabled() {
        shutDown = false
        val tunnels = settingsStore.awaitLoaded().sshTunnels.filter { it.enabled }
        Log.i(TAG, "Starting ${tunnels.size} enabled tunnel(s)")
        tunnels.forEach { tunnel -> open(tunnel.id) }
    }

    /** 用户确认了主机指纹: 写回设置后自动重连 */
    suspend fun trustHostKey(id: String, hostKey: SshHostKey) {
        Log.i(TAG, "Trust host key for tunnel $id: ${hostKey.type} ${hostKey.fingerprint}")
        updateSetting(id) { it.copy(hostKey = hostKey) }
        shutDown = false
        lockOf(id).withLock {
            closeHandle(id)
            openLocked(id)
        }
    }

    /**
     * 关闭全部转发并清空状态, 服务停止或销毁时调用。
     *
     * 不改设置里的 enabled: 那是用户期望的状态, 下次启动据此自动恢复。
     */
    fun shutdown() {
        shutDown = true
        forwards.keys.toList().forEach { id -> closeHandle(id) }
        forwards.clear()
        val known = _states.value.keys
        _states.value = known.associateWith { TunnelState(it) }
        Log.i(TAG, "All SSH tunnels shut down (${known.size} known)")
    }

    /**
     * 用户主动停止全部转发, 同时把设置里的 enabled 全部置回 false。
     *
     * 与 [shutdown] 的区别: 用户点通知里的“停止”表示不再需要这些隧道, 下次冷启动不应该
     * 再被 [startAllEnabled] 自动拉起来。先落盘再断连接, 这样正在建连的转发在结束时
     * 会看到 [shutDown] 而放弃写回 enabled=true。
     */
    suspend fun stopAllAndDisable() {
        settingsStore.update { settings ->
            if (settings.sshTunnels.none { it.enabled }) {
                settings
            } else {
                settings.copy(sshTunnels = settings.sshTunnels.map { it.copy(enabled = false) })
            }
        }
        shutdown()
        Log.i(TAG, "All SSH tunnels stopped and disabled")
    }

    private suspend fun open(id: String) {
        lockOf(id).withLock { openLocked(id) }
    }

    /**
     * 打开一条转发, 调用方必须持有 [id] 的锁。
     *
     * [requestedPortOverride] 不为 null 时用它代替 [SshTunnelSetting.localPort] 作为本地监听端口，
     * 供重建时复用原来实际使用的端口（配置里选了随机端口时尤其重要）。
     */
    private suspend fun openLocked(id: String, requestedPortOverride: Int? = null) {
        if (shutDown) {
            Log.i(TAG, "Skip starting tunnel $id: manager is shut down")
            return
        }
        // 服务刚启动时设置可能还在读盘, 这里等一次加载完成
        val tunnel = settingsStore.awaitLoaded().sshTunnels.find { it.id == id }
        if (tunnel == null) {
            Log.w(TAG, "Tunnel $id not found in settings")
            setState(id) { TunnelState(id) }
            return
        }
        // 同一 id 重复 start 时先关旧句柄, 保证只有一个监听端口
        closeHandle(id)
        setState(id) { TunnelState(id, phase = Phase.CONNECTING) }
        val config = try {
            tunnel.validationError()?.let { message ->
                Log.w(TAG, "Tunnel $id is invalid: $message")
                setState(id) { TunnelState(id, phase = Phase.ERROR, error = message) }
                return
            }
            // 解密凭据, 解密失败说明密钥已经不可用
            tunnel.toConnectionConfig()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build config for tunnel $id", e)
            setState(id) { TunnelState(id, phase = Phase.ERROR, error = e.message) }
            return
        }
        try {
            val forward = withContext(Dispatchers.IO) {
                sshPool.openLocalForward(
                    id = id,
                    config = config,
                    bindAddress = tunnel.bindAddress.ifBlank { SshTunnelSetting.LOOPBACK },
                    localPort = requestedPortOverride ?: tunnel.localPort,
                    remoteHost = tunnel.remoteHost,
                    remotePort = tunnel.remotePort,
                )
            }
            if (shutDown) {
                // 建连期间服务被停掉了, 不能把句柄留下来
                runCatching { forward.close() }
                return
            }
            forwards[id] = forward
            setState(id) { TunnelState(id, phase = Phase.RUNNING, localPort = forward.localPort) }
            Log.i(
                TAG,
                "Tunnel ${tunnel.displayName()} running: ${tunnel.bindAddress}:${forward.localPort} -> " +
                    "${tunnel.remoteHost}:${tunnel.remotePort}",
            )
            // 连上了才记下用户的期望状态, 下次启动自动恢复
            updateSetting(id) { it.copy(enabled = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SshUnknownHostKeyException) {
            Log.w(TAG, "Tunnel $id needs host key confirmation", e)
            setState(id) {
                TunnelState(
                    id = id,
                    phase = Phase.ERROR,
                    error = "需要确认主机指纹",
                    pendingHostKey = e.hostKey,
                )
            }
        } catch (e: SshHostKeyChangedException) {
            Log.w(TAG, "Host key of tunnel $id changed", e)
            setState(id) {
                TunnelState(
                    id = id,
                    phase = Phase.ERROR,
                    error = "主机指纹已变化, 可能是服务器重装或遭到中间人攻击, 确认后再连接",
                    pendingHostKey = e.actual,
                )
            }
        } catch (e: BindException) {
            Log.w(TAG, "Local port of tunnel $id is unavailable", e)
            setState(id) {
                TunnelState(
                    id = id,
                    phase = Phase.ERROR,
                    error = "本地端口 ${tunnel.localPort} 已被占用",
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tunnel $id", e)
            setState(id) { TunnelState(id, phase = Phase.ERROR, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun closeHandle(id: String) {
        forwards.remove(id)?.let { forward ->
            runCatching { forward.close() }.onFailure { Log.w(TAG, "Failed to close tunnel $id", it) }
        }
    }

    private fun setState(id: String, transform: TunnelState.() -> TunnelState) {
        _states.update { current ->
            val base = current[id] ?: TunnelState(id)
            current + (id to base.transform())
        }
    }

    /** 只改这一条转发, 找不到时不动设置 */
    private suspend fun updateSetting(id: String, transform: (SshTunnelSetting) -> SshTunnelSetting) {
        settingsStore.update { settings ->
            if (settings.sshTunnels.none { it.id == id }) {
                settings
            } else {
                settings.copy(
                    sshTunnels = settings.sshTunnels.map { tunnel ->
                        if (tunnel.id == id) transform(tunnel) else tunnel
                    }
                )
            }
        }
    }

    private fun lockOf(id: String): Mutex = locks.computeIfAbsent(id) { Mutex() }
}
