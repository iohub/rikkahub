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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_REMOTE_TTS_ID
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.fallbackTTSProvider
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.components.remoteTTSItems
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

/**
 * 「远程 TTS 服务」设置页。
 *
 * 只负责配置一个 [TTSProviderSetting.RemoteTTS] 条目（优先默认 id [DEFAULT_REMOTE_TTS_ID]）：
 * - 表单直接复用 [remoteTTSItems]，不在这里重写一遍，避免两处实现漂移；
 * - 语音服务页仍负责增删条目和切换「当前使用」的提供商；
 * - 这里额外补上内置 TTS 的开关（语音服务页没有入口），以及一个就地试听按钮。
 */
@Composable
fun SettingRemoteTTSPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current
    val tts = LocalTTSState.current
    val isSpeaking by tts.isSpeaking.collectAsStateWithLifecycle()
    val isAvailable by tts.isAvailable.collectAsStateWithLifecycle()
    val ttsError by tts.error.collectAsStateWithLifecycle()

    // 目标条目：先按固定 id 找内置的默认远程 TTS，找不到再退到列表里第一个远程 TTS
    val remoteProviders = remember(settings.ttsProviders) {
        settings.ttsProviders.filterIsInstance<TTSProviderSetting.RemoteTTS>()
    }
    val target = remember(remoteProviders) {
        remoteProviders.firstOrNull { it.id == DEFAULT_REMOTE_TTS_ID } ?: remoteProviders.firstOrNull()
    }

    val defaultTestText = stringResource(R.string.setting_remote_tts_test_text)
    var testText by rememberSaveable(defaultTestText) { mutableStateOf(defaultTestText) }
    // 试听走的是「全局选中」的 provider：目标条目没被选中时先切过去，等它生效（控制器报告可用）后再合成，
    // 否则试听测的是另一个服务；提前合成只会换来一句「No TTS provider selected」
    var pendingPreview by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingPreview, settings.selectedTTSProviderId, target?.id, isAvailable) {
        val text = pendingPreview ?: return@LaunchedEffect
        val provider = target ?: return@LaunchedEffect
        if (settings.selectedTTSProviderId != provider.id || !isAvailable) return@LaunchedEffect
        pendingPreview = null
        tts.speak(text)
    }

    // 合成失败时把控制器的错误抛成提示条
    LaunchedEffect(ttsError) {
        val message = ttsError ?: return@LaunchedEffect
        toaster.show(message, type = ToastType.Error)
    }

    // 只回写被编辑的那一条，其它提供商（含内置系统 TTS）原样保留
    fun updateRemoteProvider(updated: TTSProviderSetting) {
        vm.updateSettings { latest ->
            latest.copy(
                ttsProviders = latest.ttsProviders.map { if (it.id == updated.id) updated else it }
            )
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_remote_tts_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (target == null) {
                item {
                    CardGroup {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_remote_tts_missing)) },
                        )
                    }
                }
                item {
                    Button(
                        onClick = {
                            vm.updateSettings { latest ->
                                latest.copy(
                                    ttsProviders = latest.ttsProviders +
                                        TTSProviderSetting.RemoteTTS(id = DEFAULT_REMOTE_TTS_ID)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.setting_remote_tts_add))
                    }
                }
            } else {
                item {
                    // 表单本身来自 TTSProviderConfigure，这里只负责把改动回写进 ttsProviders
                    CardGroup {
                        remoteTTSItems(
                            setting = target,
                            onValueChange = ::updateRemoteProvider,
                        )
                    }
                }
            }

            item {
                CardGroup {
                    switchItem(
                        checked = settings.enableBuiltInTts,
                        onCheckedChange = { enabled ->
                            vm.updateSettings { latest ->
                                latest.copy(
                                    enableBuiltInTts = enabled,
                                    // 关掉内置 TTS 时选中的若是系统 TTS，按 SettingsExt 的门禁顺序换成远程 TTS，
                                    // 否则 getSelectedTTSProvider() 一直在回退，选中项和界面对不上
                                    selectedTTSProviderId = latest.deselectedAfterBuiltInToggle(
                                        enableBuiltInTts = enabled
                                    ),
                                )
                            }
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_remote_tts_enable_builtin_tts_desc))
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_remote_tts_enable_builtin_tts))
                        },
                    )
                }
            }

            item {
                CardGroup(title = { Text(stringResource(R.string.test_tts)) }) {
                    formItem {
                        OutlinedTextField(
                            value = testText,
                            onValueChange = { testText = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                        )
                    }
                    formItem {
                        Button(
                            onClick = {
                                if (isSpeaking) {
                                    tts.stop()
                                } else if (target != null) {
                                    if (settings.selectedTTSProviderId != target.id) {
                                        vm.updateSettings { it.copy(selectedTTSProviderId = target.id) }
                                    }
                                    pendingPreview = testText
                                }
                            },
                            enabled = target != null && (isSpeaking || testText.isNotBlank()),
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (isSpeaking) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                        ) {
                            Icon(
                                imageVector = if (isSpeaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isSpeaking) {
                                    stringResource(R.string.stop)
                                } else {
                                    stringResource(R.string.setting_remote_tts_test_play)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 切换内置 TTS 开关后应该选中的提供商 ID。
 *
 * 打开时不动选中项；关闭时若选中的正是系统 TTS，按 [fallbackTTSProvider] 的顺序换成一个远程提供商
 * （找不到可用的就保持原样，交给 [me.rerere.rikkahub.data.datastore.getSelectedTTSProvider] 回退）。
 * 与数据层归一化里的 `withUsableTTSProvider` 是同一条规则，这里显式做一次，
 * 让开关的效果在写回时就已落到 `selectedTTSProviderId` 上。
 */
private fun Settings.deselectedAfterBuiltInToggle(enableBuiltInTts: Boolean): Uuid {
    if (enableBuiltInTts) return selectedTTSProviderId
    val selected = ttsProviders.find { it.id == selectedTTSProviderId }
    if (selected !is TTSProviderSetting.SystemTTS) return selectedTTSProviderId
    return ttsProviders.fallbackTTSProvider(allowSystemTTS = false)?.id ?: selectedTTSProviderId
}
