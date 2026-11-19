package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_REMOTE_TTS_ID
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.fallbackTTSProvider
import me.rerere.rikkahub.data.files.FileUtils
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.components.remoteSamplingItems
import me.rerere.rikkahub.ui.pages.setting.components.remoteTTSItems
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.providers.RemoteTTSApi
import me.rerere.tts.provider.providers.RemoteVoiceInfo
import me.rerere.ui.toast.ToastType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    // ---------------- 服务端控制面：音色列表 / 连接测试 / 克隆注册 ----------------
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 无状态，整个页面共用一个实例（内部持有 OkHttpClient）
    val api = remember { RemoteTTSApi() }
    var voices by remember { mutableStateOf<List<RemoteVoiceInfo>>(emptyList()) }
    var voicesLoading by remember { mutableStateOf(false) }
    var voicesError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testModels by remember { mutableStateOf<List<String>?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }
    // 服务端 /models 的探测结果，只服务于克隆区的「当前模型不支持克隆」预警：
    // 与 testModels 分开，避免自动探测就把「连接成功」的文案点亮。
    var detectedModels by remember { mutableStateOf<List<String>?>(null) }
    // 克隆注册的表单状态提到 LazyColumn 元素之外：滚出屏幕再回来时已读进内存的参考音频不会丢
    val cloneForm = remember { CloneVoiceForm() }

    /** 拉一次音色列表；返回值供调用方判断「刚注册的音色是否已经出现在服务端列表里」 */
    suspend fun loadVoices(provider: TTSProviderSetting.RemoteTTS): List<RemoteVoiceInfo> {
        voicesLoading = true
        voicesError = null
        return try {
            api.listVoices(provider).also { voices = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            voicesError = e.readableMessage()
            voices
        } finally {
            voicesLoading = false
        }
    }

    /** health + models 两个接口，确认地址/密钥/模型都是通的 */
    suspend fun testConnection(provider: TTSProviderSetting.RemoteTTS) {
        testing = true
        testError = null
        testModels = null
        try {
            if (!api.health(provider)) {
                testError = context.getString(R.string.setting_remote_tts_connection_unhealthy)
                return
            }
            val models = api.listModels(provider)
            testModels = models
            // 克隆区预警复用同一份探测结果：命中 customvoice / voicedesign 就提前提示
            detectedModels = models
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            testError = context.getString(
                R.string.setting_remote_tts_connection_failed,
                e.readableMessage(),
            )
        } finally {
            testing = false
        }
    }

    // 地址/密钥/模型任一变化都重新拉一次：换端点、换凭据、换模型都可能换掉可用音色集合。
    // baseUrl 为空或不是合法 URL 时不发请求，交给表单里的红色提示。
    // 先 delay 一下当防抖：keys 变了会取消上一个协程，逐字输入 baseUrl 不会打出一串请求。
    LaunchedEffect(target?.id, target?.baseUrl, target?.apiKey, target?.model) {
        val provider = target ?: return@LaunchedEffect
        if (provider.baseUrl.isBlank() || provider.baseUrl.toHttpUrlOrNull() == null) return@LaunchedEffect
        // 换了端点/密钥/模型，旧服务端的探测结果立刻作废，避免短暂的跨端点误报
        detectedModels = null
        delay(VOICE_REFRESH_DEBOUNCE_MS)
        loadVoices(provider)
        // 顺带探测 /models，供克隆区提前提示「当前模型不支持克隆」。
        // 失败静默：进页面不该因为探测失败就报错，连接测试有自己的错误展示。
        detectedModels = try {
            api.listModels(provider)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
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

    // 合成失败时把控制器的错误抛成提示条。
    // 「音色不存在」在服务端是 502 + `synthesis failed`，而 TtsController 只保留 message 文本，
    // 这里在展示前把它翻译成用户能照做的提示（见 [synthesisErrorMessage]）。
    // voice 用 rememberUpdatedState 取最新值：LaunchedEffect 的 block 只在 ttsError 变化时重启。
    val currentVoice by rememberUpdatedState(target?.voice.orEmpty())
    LaunchedEffect(ttsError) {
        val message = ttsError ?: return@LaunchedEffect
        toaster.show(
            message = synthesisErrorMessage(context, message, currentVoice),
            type = ToastType.Error,
        )
    }

    // 只回写被编辑的那一条，其它提供商（含内置系统 TTS）原样保留
    fun updateRemoteProvider(updated: TTSProviderSetting) {
        vm.updateSettings { latest ->
            latest.copy(
                ttsProviders = latest.ttsProviders.map { if (it.id == updated.id) updated else it }
            )
        }
    }

    // 只改 voice 一个字段：克隆注册/删除后回写，避免拿陈旧快照覆盖用户同时改过的其它字段
    fun useVoice(providerId: Uuid, voice: String) {
        vm.updateSettings { latest ->
            latest.copy(
                ttsProviders = latest.ttsProviders.map { provider ->
                    if (provider.id == providerId && provider is TTSProviderSetting.RemoteTTS) {
                        provider.copy(voice = voice)
                    } else {
                        provider
                    }
                }
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
                            voiceOptions = voices,
                            onRefreshVoices = { scope.launch { loadVoices(target) } },
                            voicesLoading = voicesLoading,
                            voiceLoadError = voicesError,
                        )
                    }
                }

                // 连接状态：health 判活 + models 取模型列表，一次点按就能确认地址/密钥/模型
                item {
                    CardGroup(title = { Text(stringResource(R.string.setting_remote_tts_group_connection)) }) {
                        formItem(
                            headlineContent = { Text(stringResource(R.string.setting_remote_tts_connection_test)) },
                            supportingContent = {
                                val error = testError
                                val models = testModels
                                when {
                                    error != null -> Text(
                                        text = error,
                                        color = MaterialTheme.colorScheme.error,
                                    )

                                    models != null -> Text(
                                        stringResource(
                                            R.string.setting_remote_tts_connection_ok,
                                            // 多模型只列前几个，避免撑爆一行
                                            models.take(3).joinToString(", ").ifBlank { "—" } +
                                                if (models.size > 3) " …" else "",
                                        )
                                    )

                                    else -> Unit
                                }
                            },
                        ) {
                            Button(
                                onClick = { scope.launch { testConnection(target) } },
                                enabled = !testing,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (testing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.setting_remote_tts_connection_test))
                            }
                        }
                    }
                }

                // 采样参数（精细控制）：9 个参数各自 null = 不发送 = 保留引擎默认值
                item {
                    CardGroup(title = { Text(stringResource(R.string.setting_remote_tts_group_sampling)) }) {
                        remoteSamplingItems(
                            setting = target,
                            onValueChange = ::updateRemoteProvider,
                        )
                    }
                }

                // 克隆注册：整段 WAV 或预提取的 .spk/.rvq，注册成功直接切过去用
                item {
                    CloneVoiceGroup(
                        provider = target,
                        api = api,
                        currentVoice = target.voice,
                        voices = voices,
                        detectedModels = detectedModels,
                        form = cloneForm,
                        refreshVoices = { loadVoices(target) },
                        onUseVoice = { voice -> useVoice(target.id, voice) },
                    )
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

/** 克隆音色的上传方式：整段 WAV，或预提取的 .spk + .rvq latents */
private enum class CloneUploadMode { Wav, Latents }

/** 待上传的槽位：一个系统文件选择器复用三种文件 */
private enum class ClonePickTarget { Wav, Spk, Rvq }

/** 读进内存的参考音频：文件名用于回显，bytes 直接上传 */
private class SelectedAudio(val fileName: String, val bytes: ByteArray)

private sealed interface AudioPickResult {
    data class Success(val audio: SelectedAudio) : AudioPickResult
    data class Failure(val message: String) : AudioPickResult
}

/**
 * 克隆注册表单的状态。
 *
 * 由页面级 `remember` 持有而不是放在 LazyColumn 的 item 里：item 被回收时
 * 已读进内存的参考音频（最大 20MB）没必要让用户重选一次。
 */
private class CloneVoiceForm {
    var name by mutableStateOf("")
    var refText by mutableStateOf("")
    var mode by mutableStateOf(CloneUploadMode.Wav)
    var wav by mutableStateOf<SelectedAudio?>(null)
    var spk by mutableStateOf<SelectedAudio?>(null)
    var rvq by mutableStateOf<SelectedAudio?>(null)
    var pickTarget by mutableStateOf(ClonePickTarget.Wav)
    var registering by mutableStateOf(false)
    var deleting by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var success by mutableStateOf<String?>(null)
    var pendingDelete by mutableStateOf<String?>(null)

    /** 当前模式下要上传的文件是否齐了（注册按钮的可用条件之一） */
    val filesReady: Boolean
        get() = when (mode) {
            CloneUploadMode.Wav -> wav != null
            CloneUploadMode.Latents -> spk != null && rvq != null
        }

    fun clearPicked() {
        wav = null
        spk = null
        rvq = null
    }
}

/** 单个文件上传上限：与服务端一致（speech 模块里的同名常量是 internal，跨模块取不到） */
private const val CLONE_MAX_UPLOAD_BYTES: Long = 20L * 1024 * 1024

/** 音色列表自动刷新的防抖窗口：逐字输入 baseUrl 时不至于每一击都发一次请求 */
private const val VOICE_REFRESH_DEBOUNCE_MS = 500L

/** customvoice / voicedesign 类型模型不支持克隆注册（服务端返回 400），用于克隆区提前预警 */
private val CLONE_UNSUPPORTED_MODEL_PATTERN = Regex(
    "customvoice|custom_voice|voicedesign|voice_design",
    RegexOption.IGNORE_CASE,
)

/**
 * 合成失败的特征。
 *
 * 服务端在 `voice` 指向不存在的音色时返回 **502** + `{"error":{"message":"synthesis failed"}}`，
 * 但 [TtsController][me.rerere.tts.controller.TtsController] 只把异常 message 透到 UI、
 * 丢掉了 statusCode，所以这里只能在文本里认 `synthesis failed` 与 `502`。
 */
private val SYNTHESIS_FAILURE_PATTERN = Regex(
    "synthesis failed|\\b502\\b",
    RegexOption.IGNORE_CASE,
)

/**
 * 把「合成失败」翻译成用户能照做的提示，原始 message 附在末尾，不丢诊断信息。
 *
 * 音色名打错、或服务端重启后克隆音色丢失时，用户只会看到 `synthesis failed`，
 * 完全不知道该改哪一项；[voice] 为空说明用的是服务端默认音色，问题多半在地址/模型。
 */
private fun synthesisErrorMessage(context: Context, message: String, voice: String): String {
    if (!SYNTHESIS_FAILURE_PATTERN.containsMatchIn(message)) return message
    return if (voice.isBlank()) {
        context.getString(R.string.setting_remote_tts_synthesis_failed, message)
    } else {
        context.getString(R.string.setting_remote_tts_synthesis_voice_missing, voice, message)
    }
}

/**
 * 「克隆音色注册」分组。
 *
 * 协议见服务端文档：`POST {baseUrl}/audio/voices`，要么整段 WAV，要么预提取的 spk + rvq latents。
 * 只有 base 类型模型支持克隆，其它模型服务端返回 400 并带上 `only valid for base models`。
 */
@Composable
private fun CloneVoiceGroup(
    provider: TTSProviderSetting.RemoteTTS,
    api: RemoteTTSApi,
    currentVoice: String,
    voices: List<RemoteVoiceInfo>,
    detectedModels: List<String>?,
    form: CloneVoiceForm,
    refreshVoices: suspend () -> List<RemoteVoiceInfo>,
    onUseVoice: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    suspend fun applyPick(target: ClonePickTarget, result: AudioPickResult) {
        when (result) {
            is AudioPickResult.Failure -> form.error = result.message
            is AudioPickResult.Success -> {
                form.error = null
                form.success = null
                when (target) {
                    ClonePickTarget.Wav -> form.wav = result.audio
                    ClonePickTarget.Spk -> form.spk = result.audio
                    ClonePickTarget.Rvq -> form.rvq = result.audio
                }
            }
        }
    }

    suspend fun register() {
        val name = form.name.trim()
        if (name.isEmpty()) {
            form.error = context.getString(R.string.setting_remote_tts_clone_name)
            return
        }
        form.registering = true
        form.error = null
        form.success = null
        try {
            val refText = form.refText.trim()
            val missingFile = context.getString(R.string.setting_remote_tts_clone_missing_file)
            when (form.mode) {
                CloneUploadMode.Wav -> {
                    val wav = form.wav?.bytes ?: throw IllegalStateException(missingFile)
                    api.registerVoiceWithWav(provider, name, refText, wav)
                }

                CloneUploadMode.Latents -> {
                    val spk = form.spk?.bytes ?: throw IllegalStateException(missingFile)
                    val rvq = form.rvq?.bytes ?: throw IllegalStateException(missingFile)
                    api.registerVoiceWithLatents(provider, name, refText, spk, rvq)
                }
            }
            form.success = context.getString(R.string.setting_remote_tts_clone_success, name)
            form.name = ""
            form.refText = ""
            form.clearPicked()
            // 出现在服务端列表里才切过去：避免选中一个服务端其实没有的音色
            if (refreshVoices().any { it.name == name }) {
                onUseVoice(name)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            form.error = cloneErrorMessage(context, e)
        } finally {
            form.registering = false
        }
    }

    suspend fun delete(name: String) {
        form.deleting = name
        form.error = null
        form.success = null
        try {
            api.deleteVoice(provider, name)
            refreshVoices()
            // 删掉的正好是当前音色，置空回到服务端默认，否则合成会一直报音色不存在
            if (currentVoice == name) {
                onUseVoice("")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            form.error = context.getString(
                R.string.setting_remote_tts_clone_delete_failed,
                e.readableMessage(),
            )
        } finally {
            form.deleting = null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 回调时读 pickTarget：一次点击只改一个槽位
        val target = form.pickTarget
        scope.launch { applyPick(target, pickAudioFile(context, uri)) }
    }

    fun launchPicker(target: ClonePickTarget) {
        form.pickTarget = target
        picker.launch(
            when (target) {
                // WAV 有标准 mime
                ClonePickTarget.Wav -> arrayOf("audio/wav", "audio/x-wav", "audio/*")
                // .spk/.rvq 是自定义扩展名，mime 多半是 application/octet-stream，只能放开
                ClonePickTarget.Spk, ClonePickTarget.Rvq -> arrayOf("*/*")
            }
        )
    }

    val registered = voices.filter { it.isRegistered }
    // CardGroup 的 content lambda 不是 @Composable，需要当参数传出去的文案必须在这里先取好
    val wavLabel = stringResource(R.string.setting_remote_tts_clone_pick_wav)
    val spkLabel = stringResource(R.string.setting_remote_tts_clone_pick_spk)
    val rvqLabel = stringResource(R.string.setting_remote_tts_clone_pick_rvq)
    // 模型类型预警：只有在 /models 探测成功且命中 customvoice/voicedesign 时才提示。
    // 未探测过或列表为空时保持沉默，避免对 base 模型误报。
    val unsupportedModelHint = detectedModels
        ?.firstOrNull { CLONE_UNSUPPORTED_MODEL_PATTERN.containsMatchIn(it) }
        ?.let { stringResource(R.string.setting_remote_tts_clone_model_unsupported, it) }

    CardGroup(title = { Text(stringResource(R.string.setting_remote_tts_group_clone)) }) {
        item(headlineContent = { Text(stringResource(R.string.setting_remote_tts_clone_hint)) })

        if (unsupportedModelHint != null) {
            item(
                headlineContent = {
                    Text(
                        text = unsupportedModelHint,
                        color = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }

        formItem(
            headlineContent = { Text(stringResource(R.string.setting_remote_tts_clone_name)) },
            supportingContent = { Text(stringResource(R.string.setting_remote_tts_clone_name_hint)) },
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { form.name = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("myvoice") },
                singleLine = true,
            )
        }

        formItem(
            headlineContent = { Text(stringResource(R.string.setting_remote_tts_clone_ref_text)) },
            supportingContent = { Text(stringResource(R.string.setting_remote_tts_clone_ref_text_hint)) },
        ) {
            OutlinedTextField(
                value = form.refText,
                onValueChange = { form.refText = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )
        }

        formItem(headlineContent = { Text(stringResource(R.string.setting_remote_tts_clone_mode)) }) {
            val modes = listOf(
                CloneUploadMode.Wav to stringResource(R.string.setting_remote_tts_clone_mode_wav),
                CloneUploadMode.Latents to stringResource(R.string.setting_remote_tts_clone_mode_latents),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = form.mode == mode,
                        onClick = { form.mode = mode },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    ) {
                        Text(label)
                    }
                }
            }
        }

        when (form.mode) {
            CloneUploadMode.Wav -> cloneFileRow(
                label = wavLabel,
                fileName = form.wav?.fileName,
                onPick = { launchPicker(ClonePickTarget.Wav) },
            )

            CloneUploadMode.Latents -> {
                cloneFileRow(
                    label = spkLabel,
                    fileName = form.spk?.fileName,
                    onPick = { launchPicker(ClonePickTarget.Spk) },
                )
                cloneFileRow(
                    label = rvqLabel,
                    fileName = form.rvq?.fileName,
                    onPick = { launchPicker(ClonePickTarget.Rvq) },
                )
            }
        }

        // 网络/读取失败都落到这里，不静默吞掉
        val error = form.error
        if (error != null) {
            item(headlineContent = { Text(error, color = MaterialTheme.colorScheme.error) })
        }
        val success = form.success
        if (success != null) {
            item(headlineContent = { Text(success, color = MaterialTheme.colorScheme.primary) })
        }

        formItem {
            Button(
                onClick = { scope.launch { register() } },
                enabled = !form.registering && form.name.isNotBlank() && form.filesReady,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (form.registering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (form.registering) {
                        stringResource(R.string.setting_remote_tts_clone_registering)
                    } else {
                        stringResource(R.string.setting_remote_tts_clone_register)
                    }
                )
            }
        }

        item(headlineContent = { Text(stringResource(R.string.setting_remote_tts_clone_registered_title)) })
        if (registered.isEmpty()) {
            item(headlineContent = { Text(stringResource(R.string.setting_remote_tts_clone_empty)) })
        } else {
            registered.forEach { voice ->
                item(
                    headlineContent = { Text(voice.name) },
                    trailingContent = {
                        IconButton(
                            onClick = { form.pendingDelete = voice.name },
                            enabled = form.deleting == null && !form.registering,
                        ) {
                            if (form.deleting == voice.name) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = HugeIcons.Delete01,
                                    contentDescription = stringResource(R.string.setting_remote_tts_clone_delete),
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    // 删除前二次确认：服务端不保留回收站，误删要重新上传参考音频
    val pendingDelete = form.pendingDelete
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { form.pendingDelete = null },
            title = { Text(stringResource(R.string.setting_remote_tts_clone_delete)) },
            text = { Text(stringResource(R.string.setting_remote_tts_clone_delete_confirm, pendingDelete)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        form.pendingDelete = null
                        scope.launch { delete(pendingDelete) }
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { form.pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** 文件选择行：点整行调起系统文件选择器，已选文件名显示在副标题 */
private fun CardGroupScope.cloneFileRow(
    label: String,
    fileName: String?,
    onPick: () -> Unit,
) {
    item(
        onClick = onPick,
        headlineContent = { Text(label) },
        supportingContent = if (fileName != null) {
            { Text(fileName) }
        } else {
            null
        },
    )
}

/**
 * 读取参考音频。
 *
 * 先看 provider 报告的大小、再兜底看实际读到的字节数：任一超过 20MB 都直接拒绝，
 * 不发起请求（base64 后体积再涨 4/3，会撞上服务端 32MB 的请求体上限）。
 */
private suspend fun pickAudioFile(context: Context, uri: Uri): AudioPickResult =
    withContext(Dispatchers.IO) {
        try {
            val declaredSize = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull() ?: -1L
            if (declaredSize > CLONE_MAX_UPLOAD_BYTES) {
                return@withContext AudioPickResult.Failure(
                    context.getString(R.string.setting_remote_tts_clone_file_too_large)
                )
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext AudioPickResult.Failure(
                    context.getString(R.string.setting_remote_tts_clone_missing_file)
                )
            // 有些 provider 不返回 statSize，读完再兜一次底
            if (bytes.size.toLong() > CLONE_MAX_UPLOAD_BYTES) {
                return@withContext AudioPickResult.Failure(
                    context.getString(R.string.setting_remote_tts_clone_file_too_large)
                )
            }
            AudioPickResult.Success(
                SelectedAudio(
                    fileName = FileUtils.getFileNameFromUri(context, uri)
                        ?: uri.lastPathSegment?.substringAfterLast('/')
                        ?: uri.toString(),
                    bytes = bytes,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AudioPickResult.Failure(e.readableMessage())
        }
    }

/** 非 base 模型克隆时服务端返回 400 + `only valid for base models`，补一句本地化的行动建议 */
private fun cloneErrorMessage(context: Context, e: Exception): String {
    val message = e.readableMessage()
    return if (message.contains("only valid for base models", ignoreCase = true)) {
        message + "\n" + context.getString(R.string.setting_remote_tts_clone_base_model_only)
    } else {
        message
    }
}

/** 异常文案：message 缺失时用类名兜底，保证界面上总有内容可显示 */
private fun Exception.readableMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: (this::class.simpleName ?: "Unknown error")
