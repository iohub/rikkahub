package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.providers.DashScopeASRController
import me.rerere.asr.providers.OpenAIRealtimeASRController
import me.rerere.asr.providers.VolcengineASRController
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.hasTtsToolCall
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.extractQuotedContentAsText
import me.rerere.rikkahub.utils.removeBracketedContent
import me.rerere.rikkahub.utils.stripMarkdown
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

/** Lives above adaptive drawer branches, so resizing does not recreate the voice session. */
@Composable
fun rememberVoiceModeStarter(vm: ChatVM, settings: Settings): () -> Unit {
    val context = LocalContext.current.applicationContext
    val client = koinInject<OkHttpClient>()
    val asr = LocalASRState.current
    val tts = LocalTTSState.current
    val toaster = LocalToaster.current
    val permission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permission)
    val voice = vm.voiceSession
    val state by voice.state.collectAsStateWithLifecycle()
    val provider = settings.getSelectedASRProvider()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(voice, lifecycleOwner, provider, settings.getSelectedTTSProvider()) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) voice.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            voice.stop()
        }
    }

    // 语音轮的 speak 回调只拿得到文本：助手消息本身已经在会话状态里（回复文本就是 runTurn 结束后
    // 从 session.state 的 currentMessages 取出来再 complete 的），所以能回查「本轮回复有没有调用过
    // text_to_speech」。这里读的是调用时刻的最新状态，不会拿到 start() 那一刻的旧快照。
    val replySpokeViaTtsTool: () -> Boolean = {
        vm.conversation.value.currentMessages
            .lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.hasTtsToolCall() == true
    }
    // playbackState.Ended 分片之间也会发一次；isSpeaking 直到整个合成/播放队列结束才变 false
    val awaitSpeechFinished: suspend () -> Unit = {
        combine(tts.isSpeaking, tts.error) { speaking, error ->
            check(error == null) { error.orEmpty() }
            !speaking
        }.first { it }
    }

    val start: () -> Unit = {
        val blocked = when {
            provider == null -> context.getString(R.string.chat_page_voice_configure_asr)
            !provider.supportsServerVadVoiceMode -> context.getString(R.string.chat_page_voice_unsupported_asr)
            vm.currentChatModel.value == null -> context.getString(R.string.chat_page_voice_select_model)
            asr.state.value.isRecording -> context.getString(R.string.chat_page_voice_finish_dictation)
            vm.messageQueue.value.paused && vm.messageQueue.value.messages.isNotEmpty() ->
                context.getString(R.string.chat_page_voice_resume_queue)
            vm.conversation.value.currentMessages.any { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            } -> context.getString(R.string.chat_page_voice_pending_tools)
            else -> null
        }
        when {
            blocked != null -> toaster.show(message = blocked)
            !permission.allRequiredPermissionsGranted -> permission.requestPermissions()
            else -> voice.start(
                createAsr = { createVoiceAsr(context, client, checkNotNull(provider)) },
                speak = if (tts.isAvailable.value) {
                    { reply ->
                        var text = reply
                        if (settings.displaySetting.ttsOnlyReadQuoted) {
                            text = text.extractQuotedContentAsText() ?: text
                        }
                        if (settings.displaySetting.ttsOnlyReadOutsideBrackets) {
                            text = text.removeBracketedContent() ?: text
                        }
                        // tts.speak() strips markdown itself; stripping twice would turn decoded
                        // entities like &lt;tag&gt; into real tags and drop them.
                        if (text.stripMarkdown().isNotBlank()) {
                            // 本轮回复自己调用过 text_to_speech：音频已经由工具触发播放，而且带着模型
                            // 为本轮生成的动态语气。这里再 speak 会把那段播放打断、从头再念一遍，
                            // 所以只等它播完，交给下面的麦克风暂停逻辑
                            if (replySpokeViaTtsTool()) {
                                awaitSpeechFinished()
                            } else {
                                tts.speak(text)
                                awaitSpeechFinished()
                            }
                        }
                    }
                } else {
                    null
                },
                stopSpeaking = tts::stop,
                listenWhileReplying = settings.displaySetting.voiceModeListenWhileReplying,
            )
        }
    }

    if (state.isActive) {
        val view = LocalView.current
        DisposableEffect(view) {
            val previous = view.keepScreenOn
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = previous }
        }
    }
    return start
}

private fun createVoiceAsr(context: Context, client: OkHttpClient, provider: ASRProviderSetting): ASRController {
    val delegate = when (provider) {
        is ASRProviderSetting.OpenAIRealtime -> {
            check(provider.apiKey.isNotBlank()) { context.getString(R.string.chat_page_voice_configure_key) }
            OpenAIRealtimeASRController(context, client, provider)
        }
        is ASRProviderSetting.DashScope -> {
            check(provider.apiKey.isNotBlank()) { context.getString(R.string.chat_page_voice_configure_key) }
            DashScopeASRController(context, client, provider)
        }
        is ASRProviderSetting.Volcengine -> {
            check(provider.apiKey.isNotBlank()) { context.getString(R.string.chat_page_voice_configure_key) }
            VolcengineASRController(context, client, provider)
        }
        else -> error(context.getString(R.string.chat_page_voice_no_endpointing))
    }
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()
    return object : ASRController by delegate {
        override fun start(onTranscriptChange: (String) -> Unit) {
            check(audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Unable to acquire audio focus for the microphone. Try again later."
            }
            delegate.start(onTranscriptChange)
        }

        override fun dispose() {
            try {
                delegate.dispose()
            } finally {
                audioManager.abandonAudioFocusRequest(focus)
            }
        }
    }
}
