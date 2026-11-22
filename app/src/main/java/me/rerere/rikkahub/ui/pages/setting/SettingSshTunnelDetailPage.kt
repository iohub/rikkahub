package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FingerPrint
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.SshTunnelSetting
import me.rerere.rikkahub.data.model.toConnectionConfig
import me.rerere.rikkahub.data.model.validationError
import me.rerere.rikkahub.service.SshTunnelForegroundService
import me.rerere.rikkahub.service.SshTunnelManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.ParsedSshTunnel
import me.rerere.rikkahub.utils.SecretCipher
import me.rerere.rikkahub.utils.parseSshCommand
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.ui.toast.ToastType
import me.rerere.workspace.ssh.SshAuthType
import me.rerere.workspace.ssh.SshConnectionPool
import me.rerere.workspace.ssh.SshHostKey
import me.rerere.workspace.ssh.SshHostKeyChangedException
import me.rerere.workspace.ssh.SshUnknownHostKeyException
import org.koin.compose.koinInject

/** 局域网内可访问的监听地址 */
private const val ANY_ADDRESS = "0.0.0.0"

/** 待确认的主机指纹; [expected] 不为 null 表示指纹发生了变化 */
private data class PendingHostKeyConfirm(
    val hostKey: SshHostKey,
    val expected: SshHostKey? = null,
)

/**
 * SSH 本地端口转发 (ssh -L) 的编辑页。
 *
 * [tunnelId] 为空串表示新建: id 在保存时才生成 UUID (空 id 的条目会被设置归一化丢掉)。
 * 所有输入都放在本地状态里, 只在进入页面时读一次设置快照, 避免设置变化把输入重置掉。
 */
@Composable
fun SettingSshTunnelDetailPage(tunnelId: String) {
    val navController = LocalNavController.current
    val settings = LocalSettings.current
    val settingsStore: SettingsStore = koinInject()
    val manager: SshTunnelManager = koinInject()
    val sshPool: SshConnectionPool = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 只在进入页面时读一次快照; 不能用 remember(settings), 否则每次设置变化都会重置正在输入的内容
    val existing = remember(tunnelId) { settings.sshTunnels.find { it.id == tunnelId } }
    val isEditing = tunnelId.isNotBlank()

    var name by remember(tunnelId) { mutableStateOf(existing?.name.orEmpty()) }
    var host by remember(tunnelId) { mutableStateOf(existing?.host.orEmpty()) }
    var portText by remember(tunnelId) { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by remember(tunnelId) { mutableStateOf(existing?.username.orEmpty()) }
    // 新建隧道时配了全局密钥就默认走私钥认证: 私钥留空, 由连接层回退到全局密钥; 已有隧道保持原值
    var authType by remember(tunnelId) {
        mutableStateOf(
            existing?.authType
                ?: if (settings.sshGlobalKey.isConfigured) SshAuthType.PRIVATE_KEY else SshAuthType.PASSWORD
        )
    }
    // 凭据输入初始为空: 留空表示沿用已保存的密文
    var password by remember(tunnelId) { mutableStateOf("") }
    var privateKey by remember(tunnelId) { mutableStateOf("") }
    var passphrase by remember(tunnelId) { mutableStateOf("") }
    var bindAddress by remember(tunnelId) {
        mutableStateOf(existing?.bindAddress?.ifBlank { SshTunnelSetting.LOOPBACK } ?: SshTunnelSetting.LOOPBACK)
    }
    var localPortText by remember(tunnelId) {
        mutableStateOf(existing?.localPort?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var remoteHost by remember(tunnelId) {
        mutableStateOf(existing?.remoteHost?.ifBlank { SshTunnelSetting.LOOPBACK } ?: SshTunnelSetting.LOOPBACK)
    }
    var remotePortText by remember(tunnelId) {
        mutableStateOf(existing?.remotePort?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var hostKey by remember(tunnelId) { mutableStateOf(existing?.hostKey) }

    var commandInput by remember(tunnelId) { mutableStateOf("") }
    var commandWarnings by remember(tunnelId) { mutableStateOf(emptyList<String>()) }
    // 剪贴板里识别到 ssh 命令的轻提示: 单独放, 不能塞进 commandWarnings, 否则会被自动解析结果覆盖
    var clipboardCommandDetected by remember(tunnelId) { mutableStateOf(false) }
    // 读剪贴板只做一次; 用 saveable 保证转屏后不会重复读取
    var clipboardChecked by rememberSaveable(tunnelId) { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var deleteConfirmVisible by remember { mutableStateOf(false) }
    var pendingHostKey by remember { mutableStateOf<PendingHostKeyConfirm?>(null) }

    /** 用当前输入拼出待保存的配置; 凭据留空时沿用已加密保存的旧值 */
    fun buildDraft(id: String): SshTunnelSetting {
        val encryptedPassword = when {
            authType != SshAuthType.PASSWORD -> null
            password.isNotEmpty() -> SecretCipher.encrypt(password)
            else -> existing?.encryptedPassword
        }
        val encryptedPrivateKey = when {
            authType != SshAuthType.PRIVATE_KEY -> null
            privateKey.isNotEmpty() -> SecretCipher.encrypt(privateKey)
            else -> existing?.encryptedPrivateKey
        }
        val encryptedPassphrase = when {
            authType != SshAuthType.PRIVATE_KEY -> null
            passphrase.isNotEmpty() -> SecretCipher.encrypt(passphrase)
            else -> existing?.encryptedPassphrase
        }
        return SshTunnelSetting(
            id = id,
            name = name.trim(),
            host = host.trim(),
            port = portText.toIntOrNull() ?: 22,
            username = username.trim(),
            authType = authType,
            encryptedPassword = encryptedPassword,
            encryptedPrivateKey = encryptedPrivateKey,
            encryptedPassphrase = encryptedPassphrase,
            hostKey = hostKey,
            bindAddress = bindAddress.ifBlank { SshTunnelSetting.LOOPBACK },
            localPort = localPortText.toIntOrNull() ?: 0,
            remoteHost = remoteHost.trim(),
            remotePort = remotePortText.toIntOrNull() ?: 0,
            // 沿用原有的期望状态, 新建默认不自动启动
            enabled = existing?.enabled ?: false,
        )
    }

    /**
     * 把解析结果写进表单。
     *
     * 只覆盖命令里确实出现的字段: 纯登录命令 (`ssh -p 22 root@host`) 不带 `-L`,
     * 此时绝不动转发相关字段, 免得把用户已经填好的转发配置清空。
     * 端口同理: `ParsedSshTunnel.port` 恒有值 (缺省 22), 必须看 [ParsedSshTunnel.hasPort]
     * 才知道命令里是否真的写了 `-p` / `ssh://host:port`, 否则会把已填端口静默重置成 22。
     */
    fun fillFromParsed(parsed: ParsedSshTunnel) {
        if (parsed.host.isNotBlank()) host = parsed.host
        if (parsed.username.isNotBlank()) username = parsed.username
        if (parsed.hasPort && parsed.port in 1..65535) portText = parsed.port.toString()
        if (parsed.hasForward) {
            if (parsed.bindAddress.isNotBlank()) bindAddress = parsed.bindAddress
            parsed.localPort?.let { localPortText = it.toString() }
            if (parsed.remoteHost.isNotBlank()) remoteHost = parsed.remoteHost
            parsed.remotePort?.let { remotePortText = it.toString() }
        }
        commandWarnings = parsed.warnings
    }

    fun applyCommand() {
        val parsed = parseSshCommand(commandInput)
        if (parsed == null) {
            commandWarnings = listOf(context.getString(R.string.ssh_tunnel_import_failed))
            return
        }
        fillFromParsed(parsed)
    }

    // 命令变化后自动解析填充: delay 让连续输入只在停手 300ms 后解析一次 (新值会让上一次 effect 直接取消)
    LaunchedEffect(commandInput) {
        if (commandInput.isBlank()) {
            // 清空命令时一并清掉旧提示, 但不动已经填好的表单字段
            commandWarnings = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        // 还没输入到目标主机 (比如刚敲了 "ssh -p 22") 时静默跳过, 免得边打字边报错
        val parsed = parseSshCommand(commandInput)
            ?.takeIf { it.host.isNotBlank() }
            ?: return@LaunchedEffect
        fillFromParsed(parsed)
    }

    // 新建隧道时读一次剪贴板: 里面正好是 ssh 命令就带进命令框, 由上面的自动解析完成字段填充
    LaunchedEffect(tunnelId) {
        if (clipboardChecked) return@LaunchedEffect
        clipboardChecked = true
        if (isEditing || commandInput.isNotBlank()) return@LaunchedEffect
        val clip = runCatching { context.readClipboardText() }.getOrDefault("").trim()
        if (clip.isBlank()) return@LaunchedEffect
        val parsed = parseSshCommand(clip)?.takeIf { it.host.isNotBlank() } ?: return@LaunchedEffect
        commandInput = clip
        clipboardCommandDetected = true
    }

    fun testConnection() {
        if (testing) return
        val draft = buildDraft(tunnelId.ifBlank { "probe" })
        draft.validationError()?.let { message ->
            toaster.show(message, type = ToastType.Error)
            return
        }
        testing = true
        scope.launch {
            try {
                // probe 是阻塞的网络调用, 必须离开主线程; 全局密钥要一起传, 才能和真实连接行为一致
                val result = withContext(Dispatchers.IO) {
                    sshPool.probe(draft.toConnectionConfig(settings.sshGlobalKey))
                }
                hostKey = result.hostKey
                toaster.show(
                    context.getString(R.string.ssh_tunnel_test_success, result.hostKey.fingerprint),
                    type = ToastType.Success,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SshUnknownHostKeyException) {
                // 首次连接: 让用户核对指纹后再信任
                pendingHostKey = PendingHostKeyConfirm(e.hostKey)
            } catch (e: SshHostKeyChangedException) {
                pendingHostKey = PendingHostKeyConfirm(e.actual, e.expected)
            } catch (e: Exception) {
                toaster.show(e.message ?: e.javaClass.simpleName, type = ToastType.Error)
            } finally {
                testing = false
            }
        }
    }

    fun save() {
        if (saving) return
        // 新建时先生成 UUID: 空 id 的条目会被设置归一化直接丢掉
        val id = tunnelId.ifBlank { UUID.randomUUID().toString() }
        val setting = buildDraft(id)
        setting.validationError()?.let { message ->
            toaster.show(message, type = ToastType.Error)
            return
        }
        saving = true
        scope.launch {
            settingsStore.update { current ->
                val stored = current.sshTunnels.any { it.id == setting.id }
                current.copy(
                    sshTunnels = if (stored) {
                        current.sshTunnels.map { if (it.id == setting.id) setting else it }
                    } else {
                        current.sshTunnels + setting
                    }
                )
            }
            if (setting.enabled) {
                // 服务收到 ACTION_START 后会按新配置重新打开转发 (内部先关旧句柄), 等价于 restart
                if (!SshTunnelForegroundService.start(context, setting.id)) {
                    manager.restart(setting.id)
                }
            }
            saving = false
            toaster.show(context.getString(R.string.ssh_tunnel_saved), type = ToastType.Success)
            navController.popBackStack()
        }
    }

    fun delete() {
        if (saving) return
        val id = tunnelId
        saving = true
        scope.launch {
            manager.stop(id)
            settingsStore.update { current ->
                current.copy(sshTunnels = current.sshTunnels.filterNot { it.id == id })
            }
            // 还有别的启用/运行中的隧道时不能收掉常驻通知
            val othersEnabled = settings.sshTunnels.any { it.enabled && it.id != id }
            val othersRunning = manager.states.value.values.any { state ->
                state.id != id && (state.phase == SshTunnelManager.Phase.RUNNING ||
                    state.phase == SshTunnelManager.Phase.CONNECTING)
            }
            if (!othersEnabled && !othersRunning) {
                SshTunnelForegroundService.stop(context)
            }
            saving = false
            navController.popBackStack()
        }
    }

    pendingHostKey?.let { pending ->
        val changed = pending.expected != null
        AlertDialog(
            onDismissRequest = { pendingHostKey = null },
            icon = { Icon(HugeIcons.Key01, contentDescription = null) },
            title = {
                Text(
                    stringResource(
                        if (changed) {
                            R.string.ssh_tunnel_host_key_changed_title
                        } else {
                            R.string.ssh_tunnel_host_key_title
                        }
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            if (changed) {
                                R.string.ssh_tunnel_host_key_changed_message
                            } else {
                                R.string.ssh_tunnel_host_key_message
                            }
                        )
                    )
                    if (changed) {
                        Text(
                            text = pending.expected.fingerprint,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text(
                        text = "${pending.hostKey.type}\n${pending.hostKey.fingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 只写回本地草稿, 保存时一起落库
                        hostKey = pending.hostKey
                        pendingHostKey = null
                    },
                ) {
                    Text(
                        stringResource(
                            if (changed) {
                                R.string.ssh_tunnel_host_key_update
                            } else {
                                R.string.ssh_tunnel_host_key_trust
                            }
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingHostKey = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (deleteConfirmVisible) {
        AlertDialog(
            onDismissRequest = { deleteConfirmVisible = false },
            icon = { Icon(HugeIcons.Delete01, contentDescription = null) },
            title = { Text(stringResource(R.string.ssh_tunnel_delete)) },
            text = { Text(stringResource(R.string.ssh_tunnel_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmVisible = false
                        delete()
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmVisible = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isEditing) R.string.ssh_tunnel_edit_title else R.string.ssh_tunnel_add
                        )
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = { deleteConfirmVisible = true }, enabled = !saving) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.ssh_tunnel_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    IconButton(onClick = ::save, enabled = !saving && !testing) {
                        Icon(HugeIcons.Tick02, contentDescription = stringResource(R.string.ssh_tunnel_save))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. 从 ssh 命令填充: 新建时最好用, 编辑时也能拿来快速改端口
            item {
                CardGroup(title = { Text(stringResource(R.string.ssh_tunnel_import_title)) }) {
                    formItem {
                        OutlinedTextField(
                            value = commandInput,
                            onValueChange = {
                                commandInput = it
                                // 用户自己动过命令框, 就不再提示"已从剪贴板识别"
                                clipboardCommandDetected = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.ssh_tunnel_import_placeholder)) },
                            supportingText = if (clipboardCommandDetected) {
                                { Text(stringResource(R.string.ssh_tunnel_import_clipboard_hint)) }
                            } else {
                                null
                            },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            minLines = 2,
                            maxLines = 4,
                        )
                        FilledTonalButton(
                            onClick = ::applyCommand,
                            enabled = commandInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.ssh_tunnel_import_action))
                        }
                        commandWarnings.forEach { warning ->
                            Text(
                                text = warning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 2. 连接信息
            item {
                CardGroup(title = { Text(stringResource(R.string.ssh_tunnel_section_connection)) }) {
                    formItem(headlineContent = { Text(stringResource(R.string.ssh_tunnel_name)) }) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.ssh_tunnel_name_placeholder)) },
                            singleLine = true,
                        )
                    }
                    formItem(headlineContent = { Text(stringResource(R.string.ssh_tunnel_host)) }) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("example.com") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }
                    formItem(headlineContent = { Text(stringResource(R.string.ssh_tunnel_port)) }) {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                            modifier = Modifier.fillMaxWidth(),
                            isError = portText.toIntOrNull()?.let { it !in 1..65535 } ?: true,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    formItem(headlineContent = { Text(stringResource(R.string.ssh_tunnel_username)) }) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("root") },
                            singleLine = true,
                        )
                    }
                }
            }

            // 3. 端口转发
            item {
                CardGroup(title = { Text(stringResource(R.string.ssh_tunnel_section_forward)) }) {
                    switchItem(
                        checked = bindAddress != SshTunnelSetting.LOOPBACK,
                        onCheckedChange = { checked ->
                            bindAddress = if (checked) ANY_ADDRESS else SshTunnelSetting.LOOPBACK
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Connect) },
                        headlineContent = { Text(stringResource(R.string.ssh_tunnel_bind_lan)) },
                        supportingContent = { Text(stringResource(R.string.ssh_tunnel_bind_lan_desc)) },
                    )
                    formItem(
                        headlineContent = { Text(stringResource(R.string.ssh_tunnel_local_port)) },
                        supportingContent = { Text(stringResource(R.string.ssh_tunnel_local_port_desc)) },
                    ) {
                        OutlinedTextField(
                            value = localPortText,
                            onValueChange = { localPortText = it.filter(Char::isDigit).take(5) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.ssh_tunnel_port_auto)) },
                            isError = (localPortText.toIntOrNull() ?: 0) !in 0..65535,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    formItem(
                        headlineContent = { Text(stringResource(R.string.ssh_tunnel_remote_host)) },
                        supportingContent = { Text(stringResource(R.string.ssh_tunnel_remote_host_desc)) },
                    ) {
                        OutlinedTextField(
                            value = remoteHost,
                            onValueChange = { remoteHost = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("localhost") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }
                    formItem(headlineContent = { Text(stringResource(R.string.ssh_tunnel_remote_port)) }) {
                        OutlinedTextField(
                            value = remotePortText,
                            onValueChange = { remotePortText = it.filter(Char::isDigit).take(5) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("80") },
                            isError = remotePortText.toIntOrNull()?.let { it !in 1..65535 } ?: true,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
            }

            // 4. 认证
            item {
                CardGroup(title = { Text(stringResource(R.string.ssh_tunnel_section_auth)) }) {
                    formItem {
                        val options = listOf(
                            SshAuthType.PASSWORD to stringResource(R.string.ssh_tunnel_auth_password),
                            SshAuthType.PRIVATE_KEY to stringResource(R.string.ssh_tunnel_auth_private_key),
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            options.forEachIndexed { index, (type, label) ->
                                SegmentedButton(
                                    selected = authType == type,
                                    onClick = {
                                        authType = type
                                        // 切换认证方式时清掉另一侧的输入, 免得把密码当私钥存下去
                                        password = ""
                                        privateKey = ""
                                        passphrase = ""
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }
                    when (authType) {
                        SshAuthType.PASSWORD -> formItem(
                            headlineContent = { Text(stringResource(R.string.ssh_tunnel_auth_password)) },
                            supportingContent = if (existing?.encryptedPassword != null) {
                                { Text(stringResource(R.string.ssh_tunnel_secret_keep)) }
                            } else {
                                null
                            },
                        ) {
                            SecretTextField(
                                value = password,
                                onValueChange = { password = it },
                                placeholder = null,
                            )
                        }

                        SshAuthType.PRIVATE_KEY -> {
                            formItem(
                                headlineContent = { Text(stringResource(R.string.ssh_tunnel_auth_private_key)) },
                                supportingContent = when {
                                    existing?.encryptedPrivateKey != null -> {
                                        { Text(stringResource(R.string.ssh_tunnel_secret_keep)) }
                                    }
                                    // 本隧道没存私钥: 留空就会用设置里的全局密钥, 提前说明免得以为填漏了
                                    settings.sshGlobalKey.isConfigured -> {
                                        { Text(stringResource(R.string.ssh_global_key_using_hint)) }
                                    }
                                    else -> null
                                },
                            ) {
                                OutlinedTextField(
                                    value = privateKey,
                                    onValueChange = { privateKey = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = {
                                        Text(stringResource(R.string.ssh_tunnel_private_key_placeholder))
                                    },
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    minLines = 3,
                                    maxLines = 8,
                                )
                            }
                            formItem(
                                headlineContent = { Text(stringResource(R.string.ssh_tunnel_passphrase)) },
                                supportingContent = if (existing?.encryptedPassphrase != null) {
                                    { Text(stringResource(R.string.ssh_tunnel_secret_keep)) }
                                } else {
                                    null
                                },
                            ) {
                                SecretTextField(
                                    value = passphrase,
                                    onValueChange = { passphrase = it },
                                    placeholder = null,
                                )
                            }
                        }
                    }
                }
            }

            // 5. 主机指纹
            item {
                CardGroup(title = { Text(stringResource(R.string.ssh_tunnel_section_security)) }) {
                    item(
                        leadingContent = { CardGroupIcon(HugeIcons.FingerPrint) },
                        headlineContent = { Text(stringResource(R.string.ssh_tunnel_section_security)) },
                        supportingContent = {
                            val trusted = hostKey
                            if (trusted == null) {
                                Text(stringResource(R.string.ssh_tunnel_host_key_unconfirmed))
                            } else {
                                Text(
                                    text = "${trusted.type}\n${trusted.fingerprint}",
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        },
                    )
                    formItem {
                        FilledTonalButton(
                            onClick = ::testConnection,
                            enabled = !testing && !saving,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (testing) {
                                LoadingIndicator(modifier = Modifier.size(ButtonDefaults.IconSize))
                            } else {
                                Icon(
                                    imageVector = HugeIcons.Connect,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize),
                                )
                            }
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(
                                stringResource(
                                    if (testing) R.string.ssh_tunnel_testing else R.string.ssh_tunnel_host_key_test
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 带显示/隐藏切换的凭据输入框; [placeholder] 不为空表示已保存过, 留空沿用 */
@Composable
private fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String?,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) HugeIcons.ViewOff else HugeIcons.View,
                    contentDescription = null,
                )
            }
        },
    )
}
