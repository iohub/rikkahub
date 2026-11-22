package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.GlobalSshKey
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.SecretCipher
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.writeClipboardText
import me.rerere.ui.toast.ToastType
import me.rerere.workspace.ssh.SshKeyUtils
import org.koin.compose.koinInject

/**
 * 全局 SSH 密钥设置页。
 *
 * 整个应用只有一个身份密钥：隧道与工作区在没自带私钥时回退到它，因此这里不是列表而是一张表单。
 * 私钥只在保存时用一次——派生公钥、[SecretCipher] 加密后落库，页面之后只展示可以公开的公钥。
 *
 * 输入用 [remember] 而不是 rememberSaveable：私钥和口令不能被写进 saved instance state，转屏后重新填。
 */
@Composable
fun SettingGlobalSshKeyPage() {
    val navController = LocalNavController.current
    val settings = LocalSettings.current
    val settingsStore: SettingsStore = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val globalKey = settings.sshGlobalKey
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var removeConfirmVisible by remember { mutableStateOf(false) }

    /** 复制公钥: 剪贴板里只放公开信息, 私钥永远不离开这个页面 */
    fun copyPublicKey() {
        if (globalKey.publicKey.isBlank()) return
        context.writeClipboardText(globalKey.publicKey)
        toaster.show(context.getString(R.string.ssh_global_key_copied), type = ToastType.Success)
    }

    fun save() {
        if (saving) return
        val keyText = privateKey.trim()
        if (keyText.isEmpty()) {
            // 口令是跟着私钥的, 私钥没变就没有可保存的内容
            if (globalKey.isConfigured) {
                toaster.show(context.getString(R.string.ssh_global_key_unchanged))
                navController.popBackStack()
            } else {
                toaster.show(context.getString(R.string.ssh_global_key_need_private_key), type = ToastType.Error)
            }
            return
        }
        saving = true
        scope.launch {
            try {
                val secret = passphrase.trim().ifEmpty { null }
                // 派生公钥要解析私钥, 加密要访问 Android Keystore, 两者都不能留在主线程
                val saved = withContext(Dispatchers.IO) {
                    val publicKey = SshKeyUtils.derivePublicKey(keyText, secret) ?: return@withContext null
                    GlobalSshKey(
                        publicKey = publicKey,
                        encryptedPrivateKey = SecretCipher.encrypt(keyText),
                        encryptedPassphrase = secret?.let(SecretCipher::encrypt),
                    )
                }
                if (saved == null) {
                    // 解析失败或口令不对: 这份凭据一定连不上, 不写入设置
                    toaster.show(context.getString(R.string.ssh_global_key_invalid), type = ToastType.Error)
                    return@launch
                }
                settingsStore.update { it.copy(sshGlobalKey = saved) }
                toaster.show(context.getString(R.string.ssh_global_key_saved), type = ToastType.Success)
                navController.popBackStack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 只剩 Keystore 不可用这类环境问题, 提示重试即可, 不把异常细节抛给用户
                toaster.show(context.getString(R.string.ssh_global_key_invalid), type = ToastType.Error)
            } finally {
                saving = false
            }
        }
    }

    fun remove() {
        if (saving) return
        saving = true
        scope.launch {
            settingsStore.update { it.copy(sshGlobalKey = GlobalSshKey()) }
            saving = false
            toaster.show(context.getString(R.string.ssh_global_key_removed), type = ToastType.Success)
            navController.popBackStack()
        }
    }

    if (removeConfirmVisible) {
        AlertDialog(
            onDismissRequest = { removeConfirmVisible = false },
            icon = { Icon(HugeIcons.Delete01, contentDescription = null) },
            title = { Text(stringResource(R.string.ssh_global_key_remove)) },
            text = { Text(stringResource(R.string.ssh_global_key_remove_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeConfirmVisible = false
                        remove()
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { removeConfirmVisible = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.ssh_global_key_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = ::save, enabled = !saving) {
                        if (saving) {
                            LoadingIndicator(modifier = Modifier.size(ButtonDefaults.IconSize))
                        } else {
                            Icon(
                                imageVector = HugeIcons.Tick02,
                                contentDescription = stringResource(R.string.ssh_global_key_save),
                            )
                        }
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
            // 1. 这个密钥是什么、什么时候生效
            item {
                CardGroup(title = { Text(stringResource(R.string.ssh_global_key_title)) }) {
                    item(
                        headlineContent = { Text(stringResource(R.string.ssh_global_key_desc)) },
                        supportingContent = { Text(stringResource(R.string.ssh_global_key_keystore_hint)) },
                    )
                }
            }

            // 2. 私钥与口令: 留空表示沿用已保存的内容
            item {
                CardGroup(title = { Text(stringResource(R.string.ssh_global_key_private_key)) }) {
                    formItem(
                        supportingContent = if (globalKey.isConfigured) {
                            { Text(stringResource(R.string.ssh_tunnel_secret_keep)) }
                        } else {
                            null
                        },
                    ) {
                        OutlinedTextField(
                            value = privateKey,
                            onValueChange = { privateKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !saving,
                            placeholder = { Text(stringResource(R.string.ssh_global_key_private_key_placeholder)) },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            minLines = 3,
                            maxLines = 8,
                        )
                    }
                    formItem(
                        headlineContent = { Text(stringResource(R.string.ssh_tunnel_passphrase)) },
                        supportingContent = if (globalKey.encryptedPassphrase != null) {
                            { Text(stringResource(R.string.ssh_tunnel_secret_keep)) }
                        } else {
                            null
                        },
                    ) {
                        SecretTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            placeholder = null,
                            enabled = !saving,
                        )
                    }
                }
            }

            // 3. 派生出的公钥: 复制到服务器的 authorized_keys
            item {
                CardGroup(title = { Text(stringResource(R.string.ssh_global_key_public_key_title)) }) {
                    formItem(
                        supportingContent = { Text(stringResource(R.string.ssh_global_key_public_key_desc)) },
                    ) {
                        val publicKey = globalKey.publicKey
                        if (publicKey.isBlank()) {
                            Text(
                                text = stringResource(R.string.ssh_global_key_public_key_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            OutlinedTextField(
                                value = publicKey,
                                onValueChange = {},
                                modifier = Modifier.fillMaxWidth(),
                                readOnly = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                minLines = 2,
                                maxLines = 6,
                            )
                            FilledTonalButton(
                                onClick = ::copyPublicKey,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Copy01,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize),
                                )
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.ssh_global_key_copy))
                            }
                        }
                    }
                }
            }

            // 4. 移除: 二次确认, 移除后未自带私钥的实体就都连不上了
            if (globalKey.isConfigured) {
                item {
                    FilledTonalButton(
                        onClick = { removeConfirmVisible = true },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.ssh_global_key_remove))
                    }
                }
            }
        }
    }
}

/** 带显示/隐藏切换的凭据输入框; 口令属于敏感信息, 默认打码 */
@Composable
private fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String?,
    enabled: Boolean,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
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
