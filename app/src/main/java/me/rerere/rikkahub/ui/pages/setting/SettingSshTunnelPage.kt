package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.SshTunnelSetting
import me.rerere.rikkahub.data.model.displayName
import me.rerere.rikkahub.service.SshTunnelForegroundService
import me.rerere.rikkahub.service.SshTunnelManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject

/**
 * SSH 本地端口转发 (ssh -L) 列表页。
 *
 * 开关直接对应设置里的 `enabled` 和 [SshTunnelManager] 的运行状态: 打开时拉起前台服务再启动转发,
 * 关闭时停止转发并在没有其它启用项时收掉常驻通知。
 */
@Composable
fun SettingSshTunnelPage() {
    val settings = LocalSettings.current
    val settingsStore: SettingsStore = koinInject()
    val manager: SshTunnelManager = koinInject()
    val states by manager.states.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 常驻通知需要通知权限; 没有权限时前台服务仍然能跑, 只是用户看不到通知
    val permissionState = rememberPermissionState(
        permissions = buildSet {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(PermissionNotification)
            }
        },
    )
    PermissionManager(permissionState = permissionState)

    // 用户点了开关但还没授权时先记下来, 授权回调后补启动
    var pendingStartId by remember { mutableStateOf<String?>(null) }

    fun startTunnel(id: String) {
        // 服务在 ACTION_START 里会先确保前台再按新配置打开转发
        if (!SshTunnelForegroundService.start(context, id)) {
            toaster.show(context.getString(R.string.ssh_tunnel_service_start_failed), type = ToastType.Error)
            scope.launch {
                settingsStore.update { current ->
                    current.copy(
                        sshTunnels = current.sshTunnels.map { tunnel ->
                            if (tunnel.id == id) tunnel.copy(enabled = false) else tunnel
                        }
                    )
                }
            }
        }
    }

    fun stopTunnel(id: String) {
        // manager.stop 会把 enabled 一起置回 false
        manager.stop(id)
        // 关掉最后一条隧道后就没必要留着常驻通知了
        val othersEnabled = settings.sshTunnels.any { it.enabled && it.id != id }
        val othersRunning = states.values.any { state ->
            state.id != id && (state.phase == SshTunnelManager.Phase.RUNNING ||
                state.phase == SshTunnelManager.Phase.CONNECTING)
        }
        if (!othersEnabled && !othersRunning) {
            SshTunnelForegroundService.stop(context)
        }
    }

    LaunchedEffect(permissionState.allPermissionsGranted) {
        val id = pendingStartId
        if (id != null && permissionState.allPermissionsGranted) {
            pendingStartId = null
            startTunnel(id)
        }
    }

    // 同一时刻只处理一个待确认的主机指纹, 确认/取消后状态清空, 下一个会接着弹出来
    val pendingState = states.values.firstOrNull { it.pendingHostKey != null }
    val pendingHostKey = pendingState?.pendingHostKey
    if (pendingState != null && pendingHostKey != null) {
        AlertDialog(
            onDismissRequest = { manager.stop(pendingState.id) },
            icon = { Icon(HugeIcons.Key01, contentDescription = null) },
            title = { Text(stringResource(R.string.ssh_tunnel_host_key_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ssh_tunnel_host_key_message))
                    Text(
                        text = "${pendingHostKey.type}\n${pendingHostKey.fingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { manager.trustHostKey(pendingState.id, pendingHostKey) }
                    },
                ) {
                    Text(stringResource(R.string.ssh_tunnel_host_key_trust))
                }
            },
            dismissButton = {
                TextButton(onClick = { manager.stop(pendingState.id) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.ssh_tunnel_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    // 空串表示新建, 保存时再生成 UUID
                    navController.navigate(Screen.SettingSshTunnelDetail(""))
                },
                icon = { Icon(HugeIcons.Add01, contentDescription = null) },
                text = { Text(stringResource(R.string.ssh_tunnel_add)) },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        val tunnels = settings.sshTunnels
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (tunnels.isEmpty()) {
                item {
                    CardGroup(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    ) {
                        item(
                            leadingContent = {
                                CardGroupIcon(
                                    icon = HugeIcons.Connect,
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.ssh_tunnel_empty_title)) },
                            supportingContent = { Text(stringResource(R.string.ssh_tunnel_empty_desc)) },
                        )
                    }
                }
            } else {
                item {
                    val containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    CardGroup(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    ) {
                        tunnels.forEach { tunnel ->
                            val state = states[tunnel.id]
                            val running = state?.phase == SshTunnelManager.Phase.RUNNING
                            val connecting = state?.phase == SshTunnelManager.Phase.CONNECTING
                            item(
                                onClick = {
                                    navController.navigate(Screen.SettingSshTunnelDetail(tunnel.id))
                                },
                                leadingContent = {
                                    CardGroupIcon(
                                        icon = HugeIcons.Link01,
                                        containerColor = containerColor,
                                        contentColor = contentColor,
                                    )
                                },
                                headlineContent = { Text(tunnel.displayName()) },
                                supportingContent = {
                                    val bind = tunnel.bindAddress.ifBlank { SshTunnelSetting.LOOPBACK }
                                    val localPort = when {
                                        running -> state.localPort
                                        tunnel.localPort > 0 -> tunnel.localPort
                                        else -> 0
                                    }
                                    val localPortText = if (localPort > 0) {
                                        localPort.toString()
                                    } else {
                                        stringResource(R.string.ssh_tunnel_port_auto)
                                    }
                                    val reconnectAttempt = state?.reconnectAttempt ?: 0
                                    val statusText = when {
                                        running -> stringResource(R.string.ssh_tunnel_status_running, localPort)
                                        // 自动重连中: 让用户看到在重试第几次, 而不是一直「正在连接」
                                        connecting && reconnectAttempt > 0 ->
                                            stringResource(R.string.ssh_tunnel_status_reconnecting, reconnectAttempt)

                                        connecting -> stringResource(R.string.ssh_tunnel_status_connecting)
                                        state?.phase == SshTunnelManager.Phase.ERROR ->
                                            state.error ?: stringResource(R.string.ssh_tunnel_status_stopped)

                                        else -> stringResource(R.string.ssh_tunnel_status_stopped)
                                    }
                                    val statusColor = when {
                                        running -> MaterialTheme.colorScheme.primary
                                        state?.phase == SshTunnelManager.Phase.ERROR ->
                                            MaterialTheme.colorScheme.error

                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("$bind:$localPortText → ${tunnel.remoteHost}:${tunnel.remotePort}")
                                        Text(statusText, color = statusColor)
                                    }
                                },
                                trailingContent = {
                                    Switch(
                                        checked = running || connecting || tunnel.enabled,
                                        onCheckedChange = { checked ->
                                            if (!checked) {
                                                stopTunnel(tunnel.id)
                                            } else if (permissionState.allPermissionsGranted) {
                                                startTunnel(tunnel.id)
                                            } else {
                                                pendingStartId = tunnel.id
                                                permissionState.requestPermissions()
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.ssh_tunnel_background_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}
