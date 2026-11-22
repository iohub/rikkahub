package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

/** 守护协程的巡检周期: 空闲时每这么久确认一次会话还活着 */
private const val HEALTH_CHECK_INTERVAL_MS = 10_000L

/** 自动重连的首次退避, 之后每次翻倍, 封顶 [MAX_RECONNECT_DELAY_MS] */
private const val RECONNECT_BASE_DELAY_MS = 1_000L

/** 退避上限: 服务器长时间不可用时也别把重试间隔拉得太大, 否则恢复后要等很久 */
private const val MAX_RECONNECT_DELAY_MS = 60_000L

/**
 * 前几次自动重连不吓用户 (仍显示「连接中」), 超过这个次数才把状态置成失败。
 * 只是展示口径, 重试本身没有次数上限。
 */
private const val MAX_QUIET_ATTEMPTS = 3

/**
 * SSH 本地端口转发的运行时: 按 [SshTunnelSetting] 里的配置打开转发, 并把运行状态暴露成 [states]。
 *
 * 配置本身存在设置里, 这里只持有 [SshPortForward] 句柄和状态:
 * - 打开转发是阻塞的网络调用, 全部在 [Dispatchers.IO] 上执行, 界面线程只读 [states];
 * - 同一 id 的启动/停止串行执行, 不同 id 之间互不阻塞 (建连可能要等几十秒);
 * - 任何失败都只写进 [TunnelState.error], 不抛到协程外, 不能让进程崩溃;
 * - 每条开始运行的隧道都有一个常驻的守护协程 (见 [supervise]): 定期检活, 断了就退避重连,
 *   网络恢复时立刻检查一次, 直到用户关掉它或需要人工介入 (确认指纹/配置错误)。
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
        /** 0 表示正常; 大于 0 表示守护协程正在做第 n 次自动重连 */
        val reconnectAttempt: Int = 0,
        /** 最近一次失败的原因 (原始原因, 不带展示用的前缀), 用于拼「自动重连中」文案 */
        val lastError: String? = null,
        /** 这次失败值不值得自动重试: 需要用户确认指纹/改配置的失败为 false */
        val autoRetryable: Boolean = true,
    )

    // id -> 运行中的转发句柄, 只在持有对应 id 的锁时增删
    private val forwards = ConcurrentHashMap<String, SshPortForward>()

    // 同一 id 的操作串行化, 保证不会同时打开两条转发
    private val locks = ConcurrentHashMap<String, Mutex>()

    // id -> 守护协程, 只由 ensureMonitor 登记, 由守护协程自己在 finally 里移除
    private val monitors = ConcurrentHashMap<String, Job>()

    // 网络恢复信号: 守护协程在等巡检周期时会被它提前唤醒, 不必等满一个周期
    private val networkSignals = MutableSharedFlow<Unit>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /*
     * id -> 代际令牌。JSch 建连是阻塞调用, 取消无法及时生效: 用户在等待期间 stop/restart 一条隧道时,
     * 一次「迟到的建连」可能在该操作之后才返回。stop/restart/shutdown/信任指纹都会让令牌加一,
     * 建连结束后令牌对不上就把句柄丢掉, 免得把用户已经关掉的隧道又复活。
     */
    private val epochs = ConcurrentHashMap<String, Int>()

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
        // 换配置前的建连结果作废, 否则旧配置的句柄可能在这次重连之后才写回来
        bumpEpoch(id)
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
     * 守护协程 ([supervise]) 的重连也走这里, 不再另写一套建连逻辑。
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

    /**
     * 网络恢复时叫醒所有守护协程, 让它们立刻检查一次而不是等满一个巡检周期。
     *
     * 没有隧道在运行时什么也不做: 用户没开隧道就不该在后台为网络变化干活。
     */
    fun notifyNetworkAvailable() {
        if (monitors.isNotEmpty()) networkSignals.tryEmit(Unit)
    }

    /** 停止一条转发, 并把 enabled 置回 false (用户明确不想让它自己恢复) */
    fun stop(id: String) {
        // 先停守护再换代: 之后才返回的建连结果会被丢弃, 不会把隧道又拉起来
        cancelMonitor(id)
        bumpEpoch(id)
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
        // 之前那次失败是「等用户确认」, 换代后重新开始, 守护循环也重新起一轮
        bumpEpoch(id)
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
        // 先停掉所有守护协程和正在进行的建连, 再关句柄
        monitors.keys.toList().forEach { id -> cancelMonitor(id) }
        val known = buildSet {
            addAll(forwards.keys)
            addAll(_states.value.keys)
        }
        known.forEach { id ->
            bumpEpoch(id)
            closeHandle(id)
        }
        forwards.clear()
        val stateIds = _states.value.keys
        _states.value = stateIds.associateWith { TunnelState(it) }
        Log.i(TAG, "All SSH tunnels shut down (${stateIds.size} known)")
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
        // 服务刚启动时设置可能还在读盘, 这里等一次加载完成;
        // 整份设置留着, 下面组装连接配置时要用全局 SSH 密钥做回退
        val settings = settingsStore.awaitLoaded()
        val tunnel = settings.sshTunnels.find { it.id == id }
        if (tunnel == null) {
            Log.w(TAG, "Tunnel $id not found in settings")
            setState(id) { TunnelState(id) }
            return
        }
        // 同一 id 重复 start 时先关旧句柄, 保证只有一个监听端口
        closeHandle(id)
        // replay 保留 reconnectAttempt 和 localPort: 自动重连的建连过程中界面上的「重连中(第 n 次)」
        // 与已知端口不该闪回初始值
        setState(id) {
            TunnelState(
                id = id,
                phase = Phase.CONNECTING,
                localPort = localPort,
                reconnectAttempt = reconnectAttempt,
            )
        }
        val config = try {
            tunnel.validationError()?.let { message ->
                Log.w(TAG, "Tunnel $id is invalid: $message")
                setState(id) {
                    TunnelState(
                        id = id,
                        phase = Phase.ERROR,
                        localPort = localPort,
                        error = message,
                        lastError = message,
                        reconnectAttempt = reconnectAttempt,
                        // 配置不对要用户自己改, 自动重试没有意义
                        autoRetryable = false,
                    )
                }
                return
            }
            // 解密凭据, 解密失败说明密钥已经不可用; 本隧道没自带私钥时回退设置里的全局密钥
            tunnel.toConnectionConfig(settings.sshGlobalKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build config for tunnel $id", e)
            setState(id) {
                TunnelState(
                    id = id,
                    phase = Phase.ERROR,
                    localPort = localPort,
                    error = e.message,
                    lastError = e.message,
                    reconnectAttempt = reconnectAttempt,
                    // 凭据解不开只能靠用户重新填一遍
                    autoRetryable = false,
                )
            }
            return
        }
        // 建连是阻塞调用, 取消不及时: 记下代号, 建好之后发现用户已经 stop/改过配置就丢弃这个句柄
        val epoch = epochOf(id)
        var created: SshPortForward? = null
        try {
            val forward = try {
                withContext(Dispatchers.IO) {
                    sshPool.openLocalForward(
                        id = id,
                        config = config,
                        bindAddress = tunnel.bindAddress.ifBlank { SshTunnelSetting.LOOPBACK },
                        localPort = requestedPortOverride ?: tunnel.localPort,
                        remoteHost = tunnel.remoteHost,
                        remotePort = tunnel.remotePort,
                    ).also { created = it }
                }
            } catch (e: CancellationException) {
                // 建连期间被取消(stop/shutdown): 已经建好的句柄必须关掉, 否则监听端口会泄漏
                created?.let { runCatching { it.close() } }
                throw e
            }
            if (shutDown || epochOf(id) != epoch) {
                // 建连期间服务被停掉, 或者这条隧道被 stop/restart 过: 不能把句柄留下来
                Log.i(TAG, "Discard forward of tunnel $id: state changed while connecting")
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
            // 连上了才开始守护: 之后断线由守护协程负责重建
            ensureMonitor(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SshUnknownHostKeyException) {
            Log.w(TAG, "Tunnel $id needs host key confirmation", e)
            setState(id) {
                TunnelState(
                    id = id,
                    phase = Phase.ERROR,
                    localPort = localPort,
                    error = "需要确认主机指纹",
                    lastError = "需要确认主机指纹",
                    pendingHostKey = e.hostKey,
                    reconnectAttempt = reconnectAttempt,
                    // 指纹要让用户看一眼再决定, 不能自动重试
                    autoRetryable = false,
                )
            }
        } catch (e: SshHostKeyChangedException) {
            Log.w(TAG, "Host key of tunnel $id changed", e)
            val message = "主机指纹已变化, 可能是服务器重装或遭到中间人攻击, 确认后再连接"
            setState(id) {
                TunnelState(
                    id = id,
                    phase = Phase.ERROR,
                    localPort = localPort,
                    error = message,
                    lastError = message,
                    pendingHostKey = e.actual,
                    reconnectAttempt = reconnectAttempt,
                    // 同理, 需要用户重新确认
                    autoRetryable = false,
                )
            }
        } catch (e: BindException) {
            Log.w(TAG, "Local port of tunnel $id is unavailable", e)
            val message = "本地端口 ${tunnel.localPort} 已被占用"
            setState(id) {
                TunnelState(
                    id = id,
                    phase = Phase.ERROR,
                    localPort = localPort,
                    error = message,
                    lastError = message,
                    reconnectAttempt = reconnectAttempt,
                    // 端口被别的进程占着可能自己就空出来了, 值得重试
                    autoRetryable = true,
                )
            }
            ensureMonitorIfEnabled(id, tunnel)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tunnel $id", e)
            val message = e.message ?: e.javaClass.simpleName
            setState(id) {
                TunnelState(
                    id = id,
                    phase = Phase.ERROR,
                    localPort = localPort,
                    error = message,
                    lastError = message,
                    reconnectAttempt = reconnectAttempt,
                    // 网络/服务器故障大多是暂时的, 交给守护协程退避重连
                    autoRetryable = true,
                )
            }
            ensureMonitorIfEnabled(id, tunnel)
        }
    }

    /**
     * 一条隧道的守护协程: 定期检活, 断了就退避重连, 直到用户关掉它或需要人工介入。
     *
     * 只由 [ensureMonitor] 启动; 重建一律走 [ensureRunning] (复用原本地端口、30s 超时、
     * 拒绝拉起用户关掉的隧道这些规则都在那里), 这里只负责节奏和文案。
     */
    private suspend fun supervise(id: String) {
        val self = currentCoroutineContext()[Job]
        var attempt = 0
        try {
            while (true) {
                if (shouldStopMonitoring(id)) {
                    Log.i(TAG, "Monitor of tunnel $id exits")
                    return
                }
                // 检活: 本地还在监听且底层会话没断才算健康; 会话断开后 accept 仍会成功, 所以必须看 isHealthy
                val healthy = forwards[id]?.isHealthy == true && _states.value[id]?.phase == Phase.RUNNING
                if (healthy) {
                    attempt = 0
                    awaitNextHealthCheck()
                    continue
                }
                attempt += 1
                Log.i(TAG, "Tunnel $id is down, auto reconnect attempt #$attempt")
                // 先把「正在重连」摆到界面上, 再交给 ensureRunning 真正重建
                setState(id) {
                    TunnelState(
                        id = id,
                        phase = Phase.CONNECTING,
                        localPort = localPort,
                        reconnectAttempt = attempt,
                        error = lastError,
                        lastError = lastError,
                    )
                }
                val reconnected = try {
                    ensureRunning(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnect tunnel $id failed", e)
                    false
                }
                if (reconnected) {
                    Log.i(TAG, "Tunnel $id reconnected after $attempt attempt(s)")
                    attempt = 0
                    setState(id) {
                        TunnelState(id, phase = Phase.RUNNING, localPort = forwards[id]?.localPort ?: localPort)
                    }
                    awaitNextHealthCheck()
                    continue
                }
                val failed = _states.value[id] ?: TunnelState(id)
                // 需要人工介入(确认指纹/改配置): 别盲目重试, 直接退出守护, 等用户处理完再由 openLocked 重新拉起
                if (failed.pendingHostKey != null || !failed.autoRetryable) {
                    Log.i(TAG, "Give up auto reconnect of tunnel $id: needs user action")
                    return
                }
                if (shouldStopMonitoring(id)) {
                    Log.i(TAG, "Monitor of tunnel $id exits")
                    return
                }
                // 展示口径: 前几次只说「重连中」, 次数多了才置成失败, 但仍然继续退避重试
                val reason = failed.lastError ?: failed.error
                setState(id) {
                    if (attempt <= MAX_QUIET_ATTEMPTS) {
                        copy(phase = Phase.CONNECTING, reconnectAttempt = attempt, error = reason)
                    } else {
                        copy(
                            phase = Phase.ERROR,
                            reconnectAttempt = attempt,
                            // 每次都从原始原因重新拼, 不会把前缀叠起来
                            error = "自动重连中（第 $attempt 次）：${reason ?: "连接失败"}",
                            lastError = reason,
                        )
                    }
                }
                val backoff = backoffFor(attempt)
                Log.i(TAG, "Retry tunnel $id in ${backoff}ms (attempt #$attempt)")
                delay(backoff)
            }
        } finally {
            // 精确移除自己: 不能误删 stop/restart 之后重新登记的新 Job
            monitors.remove(id, self)
        }
    }

    /** 等下一个巡检周期; 网络恢复信号会提前唤醒它, 让恢复后能马上重连 */
    private suspend fun awaitNextHealthCheck() {
        withTimeoutOrNull(HEALTH_CHECK_INTERVAL_MS) { networkSignals.first() }
    }

    /** 1s → 2s → 4s → 8s → 16s → 32s → 60s 封顶 */
    private fun backoffFor(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 6)
        return (RECONNECT_BASE_DELAY_MS shl shift).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    /**
     * 守护循环的终止条件: 用户关掉了隧道 / 服务已关停 / 需要人工介入。
     *
     * 这里读的是设置里的 `enabled` 而不是内存里的句柄: 用户可能在别的页面把隧道关掉或删掉了。
     */
    private suspend fun shouldStopMonitoring(id: String): Boolean {
        if (shutDown) return true
        val state = _states.value[id]
        if (state?.pendingHostKey != null) return true
        if (state != null && !state.autoRetryable) return true
        val tunnel = settingsStore.awaitLoaded().sshTunnels.find { it.id == id } ?: return true
        return !tunnel.enabled
    }

    /**
     * 保证 [id] 有一条守护协程在跑; 已经在跑就什么也不做。
     *
     * 只有这里会 launch, 并且带 isActive 判重: 守护协程自己会经由 [ensureRunning] → [openLocked]
     * 成功分支回到这里, 如果这里做「取消旧的再启动新的」就会把正在运行的自身取消掉, 循环随之死亡。
     */
    private fun ensureMonitor(id: String) {
        if (shutDown) return
        var started: Job? = null
        monitors.compute(id) { _, existing ->
            if (existing?.isActive == true) {
                existing
            } else {
                // LAZY 启动: 先登记进 monitors 再开始跑, finally 里的精确移除才不会漏掉自己
                appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { supervise(id) }
                    .also { started = it }
            }
        }
        started?.start()
    }

    /** 取消 [id] 的守护协程 (幂等) */
    private fun cancelMonitor(id: String) {
        monitors.remove(id)?.cancel()
    }

    /**
     * 建连失败后退避重连: 只有当这条隧道本来就在运行 (设置里 enabled) 时才有意义,
     * 用户没开过的隧道不能因为一次失败就被自动拉起来。
     */
    private fun ensureMonitorIfEnabled(id: String, tunnel: SshTunnelSetting) {
        if (shutDown || !tunnel.enabled) return
        val state = _states.value[id] ?: return
        if (!state.autoRetryable || state.pendingHostKey != null) return
        ensureMonitor(id)
    }

    private fun epochOf(id: String): Int = epochs[id] ?: 0

    private fun bumpEpoch(id: String) {
        epochs.merge(id, 1) { current, delta -> current + delta }
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
