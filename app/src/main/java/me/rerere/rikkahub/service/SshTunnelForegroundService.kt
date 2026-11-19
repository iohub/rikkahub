package me.rerere.rikkahub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.SSH_TUNNEL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.SshTunnelSetting
import org.koin.android.ext.android.inject

private const val TAG = "SshTunnelFgs"

/**
 * SSH 本地端口转发的前台服务: 进程活着隧道才能转, 所以用前台服务把进程留住。
 *
 * 隧道本身由 [SshTunnelManager] 持有, 这里只负责前台生命周期和通知。
 * 与其它服务不同, 这里返回 [START_STICKY]: 转发是用户期望长期运行的状态,
 * 进程被系统回收后要能自己恢复; 系统重启服务时 intent 为 null, 按 START 语义处理。
 */
class SshTunnelForegroundService : Service() {

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.SSH_TUNNEL_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.SSH_TUNNEL_STOP"
        const val EXTRA_TUNNEL_ID = "tunnel_id"

        // 2001 是 WebServerService, 2005 是 BackgroundShellForegroundService
        const val NOTIFICATION_ID = 2006

        /** 拉起服务; 带上 [tunnelId] 时只启动这一条, 不带时按设置恢复全部 enabled 的转发 */
        fun start(context: Context, tunnelId: String? = null): Boolean {
            val intent = Intent(context, SshTunnelForegroundService::class.java).apply {
                action = ACTION_START
                tunnelId?.let { putExtra(EXTRA_TUNNEL_ID, it) }
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                Log.e(TAG, "Unable to start SSH tunnel foreground service", it)
            }.getOrDefault(false)
        }

        /** 停止服务; 已经在跑的时候是普通 startService, 不是 startForegroundService */
        fun stop(context: Context) {
            val intent = Intent(context, SshTunnelForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Unable to stop SSH tunnel foreground service", it)
            }
        }
    }

    private val manager: SshTunnelManager by inject()
    private val settingsStore: SettingsStore by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null
    private var isForeground = false

    // 网络恢复时叫醒守护协程, 让隧道不用等下一个巡检周期就能重连
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        registerNetworkCallback()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> start(intent, startId)
            // 系统重启服务时 intent 为 null, 按 START 语义恢复转发 (与其它服务不同, 见类注释)
            null -> start(null, startId)
            ACTION_STOP -> stop(startId)
            else -> {
                stopSelf(startId)
                START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        cancelStateObserver()
        manager.shutdown()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // specialUse 没有时长限制, 这里只是兜底
        Log.w(TAG, "Foreground service timed out (type=$fgsType)")
        unregisterNetworkCallback()
        cancelStateObserver()
        manager.shutdown()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    /** 进入前台失败时返回 [START_NOT_STICKY]: 起不来就不该让系统反复重启 */
    private fun start(intent: Intent?, startId: Int): Int {
        if (!enterForegroundCompat()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        observeStates()
        val tunnelId = intent?.getStringExtra(EXTRA_TUNNEL_ID)
        serviceScope.launch {
            if (tunnelId != null) {
                manager.start(tunnelId)
            } else {
                manager.startAllEnabled()
            }
        }
        // 这个服务需要后台长期存活, 进程被回收后要自愈
        return START_STICKY
    }

    /**
     * 用户主动停止: 除了断连接还要把 enabled 落成 false, 否则下次冷启动会把隧道自动恢复。
     *
     * 落盘是挂起操作, 必须等它完成再 stopSelf (作用域会随服务销毁被取消, 提前 stopSelf 会
     * 让 enabled 停留在 true)。被动销毁 (onDestroy / onTimeout) 仍然只调 shutdown(), 保留
     * 用户的期望状态以便恢复。
     */
    private fun stop(startId: Int): Int {
        cancelStateObserver()
        serviceScope.launch {
            try {
                manager.stopAllAndDisable()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop SSH tunnels", e)
            } finally {
                if (isForeground) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isForeground = false
                }
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun observeStates() {
        if (stateObserverJob != null) return
        stateObserverJob = serviceScope.launch {
            manager.states.collect { updateNotification() }
        }
    }

    private fun cancelStateObserver() {
        stateObserverJob?.cancel()
        stateObserverJob = null
    }

    /**
     * 监听默认网络: 网络恢复时叫醒守护协程, 立刻检查一次隧道而不是等满一个巡检周期。
     *
     * 注册失败 (个别 ROM 会拒绝) 只记日志: 隧道还能靠巡检周期和请求失败重试自愈, 不能因为
     * 监听网络失败就让服务起不来。
     */
    private fun registerNetworkCallback() {
        runCatching {
            val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    manager.notifyNetworkAvailable()
                }
            }
            connectivity.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure {
            Log.w(TAG, "Unable to watch network changes", it)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }.onFailure {
            Log.w(TAG, "Unable to stop watching network changes", it)
        }
    }

    private fun enterForegroundCompat(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
            true
        } catch (e: Exception) {
            // 部分 OEM ROM 会在系统侧拒绝 FGS 类型权限, 即使 Manifest 已声明也会抛 SecurityException
            Log.e(TAG, "Failed to enter foreground", e)
            false
        }
    }

    private fun updateNotification() {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }.onFailure {
            Log.e(TAG, "Failed to update notification", it)
        }
    }

    private fun buildNotification(): Notification {
        val active = activeTunnelLines()
        val states = manager.states.value.values
        // 自动重连中的隧道不算失败: 守护协程正在退避重试, 通知里报错只会让用户白紧张
        val reconnecting = states.count { it.isAutoRetrying }
        val failing = states.count { state ->
            state.phase == SshTunnelManager.Phase.ERROR && !state.isAutoRetrying
        }
        val text = when {
            active.isNotEmpty() -> active.first()
            reconnecting > 0 -> getString(R.string.ssh_tunnel_notification_reconnecting, reconnecting)
            failing > 0 -> getString(R.string.ssh_tunnel_notification_error, failing)
            else -> getString(R.string.ssh_tunnel_notification_connecting)
        }
        val builder = NotificationCompat.Builder(this, SSH_TUNNEL_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(buildLaunchPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (active.size > 1) {
            // 多条时正文只放第一条, 总数和完整列表分别放进副标题和展开内容
            builder.setSubText(getString(R.string.ssh_tunnel_notification_running, active.size))
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(active.joinToString("\n")))
        }
        return builder
            .addAction(0, getString(R.string.ssh_tunnel_notification_stop), buildStopPendingIntent())
            .build()
    }

    /** 正在被守护协程自动重连: 即使已经连续失败多次也还会继续退避重试, 不算最终失败 */
    private val SshTunnelManager.TunnelState.isAutoRetrying: Boolean
        get() = autoRetryable && reconnectAttempt > 0

    /** 每条运行中的隧道显示成 `127.0.0.1:6008 → localhost:6008` */
    private fun activeTunnelLines(): List<String> {
        val settings = settingsStore.settingsFlow.value
        return manager.states.value.mapNotNull { (id, state) ->
            if (state.phase != SshTunnelManager.Phase.RUNNING) return@mapNotNull null
            val tunnel = settings.sshTunnels.find { it.id == id } ?: return@mapNotNull null
            getString(
                R.string.ssh_tunnel_notification_active,
                "${tunnel.bindAddress.ifBlank { SshTunnelSetting.LOOPBACK }}:${state.localPort}",
                "${tunnel.remoteHost}:${tunnel.remotePort}",
            )
        }
    }

    private fun buildLaunchPendingIntent(): PendingIntent? = PendingIntent.getActivity(
        this,
        0,
        packageManager.getLaunchIntentForPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildStopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, SshTunnelForegroundService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
