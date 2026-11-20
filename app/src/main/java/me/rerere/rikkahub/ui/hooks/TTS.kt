package me.rerere.rikkahub.ui.hooks

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import me.rerere.ai.ui.FixedAudioInfo
import me.rerere.tts.cache.TtsFixedAudioStore
import me.rerere.tts.model.PlaybackState
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.utils.extractQuotedContentAsText
import me.rerere.rikkahub.utils.removeBracketedContent
import me.rerere.rikkahub.utils.stripMarkdown
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.controller.TtsController
import org.koin.compose.koinInject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "TTS"

/**
 * Composable function to remember and manage custom TTS state.
 * Uses user-configured TTS providers instead of system TTS.
 */
@Composable
fun rememberCustomTtsState(): CustomTtsState {
    val context = LocalContext.current
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    // Remember the CustomTtsState instance across recompositions
    val ttsState = remember {
        CustomTtsStateImpl(
            context = context.applicationContext,
            settingsStore = settingsStore
        )
    }

    // Update the provider when settings change
    DisposableEffect(
        settings.selectedTTSProviderId,
        settings.ttsProviders,
        settings.defaultTTSPlaybackSpeed,
    ) {
        ttsState.updateProvider(settings.getSelectedTTSProvider())
        ttsState.setSpeed(settings.defaultTTSPlaybackSpeed)
        onDispose { }
    }

    // Cleanup resources when the state is disposed
    DisposableEffect(ttsState) {
        onDispose {
            ttsState.cleanup()
        }
    }

    return ttsState
}

/**
 * Interface defining the public API of our custom TTS state holder.
 */
interface CustomTtsState {
    /** Flow indicating if the TTS provider is available and ready. */
    val isAvailable: StateFlow<Boolean>

    /** Flow indicating if the TTS is currently speaking. */
    val isSpeaking: StateFlow<Boolean>

    /** Flow holding any error message. */
    val error: StateFlow<String?>

    /** Flow indicating current chunk being processed (index) */
    val currentChunk: StateFlow<Int>

    /** Flow indicating total chunks in queue */
    val totalChunks: StateFlow<Int>

    /** Unified playback state (status, position, duration, speed, etc.) */
    val playbackState: StateFlow<PlaybackState>

    /**
     * Speaks the given text using the selected TTS provider.
     * Long texts will be automatically chunked and queued.
     *
     * @param messageId 这段文本归属的聊天消息 id，仅用于给 [PlaybackState.messageId] 打标。
     *   传了它之后 UI 才能只高亮「正在播放的那条消息」，而不是把所有消息的播放按钮都点亮；
     *   与具体消息无关的场合（设置页试听、语音模式）留空即可
     * @param instruct 本次朗读的动态语气指令（情绪 / 语速 / 场景，如「excited and fast」）。
     *   由 `text_to_speech` 工具根据本轮回复的情绪生成，会与设置页配置的静态 instructions 合并后
     *   一起下发给 provider，并参与音频缓存键（同一文本换语气不会播到旧音频）。
     *   留空表示只下发静态配置，行为与旧版一致。
     */
    fun speak(
        text: String,
        flushCalled: Boolean = true,
        messageId: String? = null,
        instruct: String = "",
    )

    /**
     * 后台预生成语音：与 [speak] 走完全相同的文本清理、分片与合成链路，但只写入磁盘缓存，不播放。
     * 仅对「开启了缓存的远程 TTS」生效，其它情况静默跳过。
     *
     * @param instruct 预生成用的动态语气指令。要与随后 [speak] 的 instruct 保持一致才能命中同一份缓存
     */
    fun prefetch(text: String, instruct: String = "")

    /**
     * 固化一条消息的语音：用与播放**完全相同**的文本处理与分片链路合成全部分片，再写入本地固化目录
     * （`filesDir/tts_fixed/<messageId>/`），返回可以落到消息上的元数据。
     *
     * 失败（无可朗读文本 / 未选 provider / 合成失败 / 写盘失败）返回 null，可读原因写在 [error]；
     * 任何一种失败都不会留下半套固化音频。
     *
     * @param text 消息原文。Markdown 清洗与「只读引号内 / 只读括号外」两项设置由本方法自己按
     *   [speak] 调用点同样的顺序处理，调用方直接传 `message.toText()` 即可
     * @param forceRegenerate true = 重新生成：合成时跳过音频缓存、强制请求服务端
     */
    suspend fun fixateMessageAudio(
        messageId: Uuid,
        text: String,
        forceRegenerate: Boolean = false,
    ): FixedAudioInfo?

    /**
     * 播放某条消息**已固化**的音频：全程读本地文件，不请求服务端。
     *
     * @param chunkCount 期望分片数，一般取自消息上的 [FixedAudioInfo.chunkCount]；<=0 时按磁盘实际分片数播放
     */
    fun speakFixedAudio(messageId: Uuid, chunkCount: Int)

    /** 删除某条消息的固化音频文件，返回是否真的删掉了目录 */
    suspend fun deleteFixedMessageAudio(messageId: Uuid): Boolean

    /** Stops the current speech and clears the queue. */
    fun stop()

    /** Pauses the current playback. */
    fun pause()

    /** Resumes the paused playback. */
    fun resume()

    /** Skips to the next chunk in the queue. */
    fun skipNext()

    /** Fast forward current playback by [ms]. */
    fun fastForward(ms: Long = 5_000)

    /** Set playback [speed]. */
    fun setSpeed(speed: Float)

    /** Cleanup resources. */
    fun cleanup()
}

/**
 * Internal implementation of CustomTtsState.
 */
private class CustomTtsStateImpl(
    private val context: Context,
    private val settingsStore: SettingsStore
) : CustomTtsState, KoinComponent {

    private val ttsManager by inject<TTSManager>()
    private val controller by lazy { me.rerere.tts.controller.TtsController(context, ttsManager) }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var currentJob: Job? = null

    override val isAvailable: StateFlow<Boolean> get() = controller.isAvailable
    override val isSpeaking: StateFlow<Boolean> get() = controller.isSpeaking
    override val error: StateFlow<String?> get() = controller.error
    override val currentChunk: StateFlow<Int> get() = controller.currentChunk
    override val totalChunks: StateFlow<Int> get() = controller.totalChunks
    override val playbackState: StateFlow<PlaybackState> get() = controller.playbackState

    fun updateProvider(provider: TTSProviderSetting?) {
        controller.setProvider(provider)
    }

    override fun speak(text: String, flushCalled: Boolean, messageId: String?, instruct: String) {
        val processed = text.stripMarkdown()
        // messageId 只是给 PlaybackState 打标，让 UI 能只高亮「正在播放的那条消息」
        // instruct 是模型本轮生成的动态语气，由 controller 与静态配置合并后下发（并参与缓存键）
        controller.speak(processed, flushCalled, messageId, instruct)
    }

    override fun prefetch(text: String, instruct: String) {
        // 必须与 speak 对称地做 Markdown 清理：否则分片文本不同，缓存键对不上，预生成就白做了
        val processed = text.stripMarkdown()
        controller.prefetch(processed, instruct)
    }

    override suspend fun fixateMessageAudio(
        messageId: Uuid,
        text: String,
        forceRegenerate: Boolean,
    ): FixedAudioInfo? = withContext(Dispatchers.IO) {
        val key = messageId.toString()
        // 用 awaitLoaded 而不是 settingsFlow.value：加载完成前 value 是 dummy，
        // 它的 displaySetting 会让文本过滤（引号/括号）与真实设置不一致，分片就对不上播放路径了
        val settings = settingsStore.awaitLoaded()
        val textToSpeak = processTextForTts(text, settings)
        if (textToSpeak.isBlank()) {
            // 与 speak() 的空文本语义一致：不合成。但要给 UI 一个可读原因，否则点了没反应
            controller.reportError("该消息没有可朗读的文本")
            return@withContext null
        }

        // 与手动播放共用同一个 controller + chunker：分片边界一致，固化下来的音频和点播放听到的一模一样。
        // forceRegenerate 时 bypassCache=true，跳过 TtsAudioCache 强制请求服务端（成功后回写缓存）。
        val responses = controller.synthesizeAll(textToSpeak, bypassCache = forceRegenerate)
        if (responses.isEmpty()) {
            // 合成阶段失败（未选 provider / 任一分片重试耗尽）的原因已由 controller 写进 error
            return@withContext null
        }

        // 覆盖写之前先清掉旧目录：分片数变少时，旧的高位分片会残留下来，
        // 而 chunkCount 是按 0..N 连续计数，残留会让它多算，播到不存在的分片。
        // 放在合成成功之后（而不是之前）是为了：重新生成失败时不会把用户原来的固化音频弄丢。
        TtsFixedAudioStore.deleteMessage(key)
        var index = 0
        for (response in responses) {
            if (!TtsFixedAudioStore.writeChunk(key, index, response)) {
                // 半套固化音频比没有更糟：清干净再报错
                TtsFixedAudioStore.deleteMessage(key)
                controller.reportError("固化音频写入失败")
                return@withContext null
            }
            index++
        }

        // 元数据以磁盘实际内容为准（连续分片数 + 目录总字节）
        val chunkCount = TtsFixedAudioStore.chunkCount(key)
        if (chunkCount <= 0) {
            TtsFixedAudioStore.deleteMessage(key)
            controller.reportError("固化音频写入失败")
            return@withContext null
        }

        // 记下**合成当时**的 provider 配置：之后换 provider / 换音色，也不会让已固化的音频看起来像当前的
        val provider = settings.getSelectedTTSProvider()
        val remote = provider as? TTSProviderSetting.RemoteTTS
        FixedAudioInfo(
            chunkCount = chunkCount,
            providerName = provider?.name.orEmpty(),
            voice = remote?.voice.orEmpty(),
            model = remote?.model.orEmpty(),
            language = remote?.language.orEmpty(),
            sizeBytes = TtsFixedAudioStore.totalBytes(key),
            createdAt = System.currentTimeMillis(),
        )
    }

    override fun speakFixedAudio(messageId: Uuid, chunkCount: Int) {
        controller.speakFixed(messageId.toString(), chunkCount)
    }

    override suspend fun deleteFixedMessageAudio(messageId: Uuid): Boolean =
        withContext(Dispatchers.IO) {
            TtsFixedAudioStore.deleteMessage(messageId.toString())
        }

    override fun stop() {
        controller.stop()
    }

    override fun pause() {
        controller.pause()
        Log.d("CustomTtsState", "TTS paused")
    }

    override fun resume() {
        controller.resume()
        Log.d("CustomTtsState", "TTS resumed")
    }

    override fun skipNext() {
        controller.skipNext()
    }

    override fun fastForward(ms: Long) {
        controller.fastForward(ms)
    }

    override fun setSpeed(speed: Float) {
        controller.setSpeed(speed)
    }

    override fun cleanup() {
        controller.dispose()
        currentJob = null
    }
}

/**
 * 消息原文 → 真正送去合成的文本。
 *
 * 顺序必须与所有播放调用点（`ChatMessageActions` / `TTSAutoPlay` / `TTSPrefetch`）**完全一致**：
 * 先在原文上按设置做「只读引号内」「只读括号外」的过滤，最后由 [stripMarkdown] 去掉格式
 * （`stripMarkdown` 是链路里最后一步，它会解码 HTML 实体，提前做会和界面显示不一致）。
 * 顺序一变分片边界就跟着变，`TtsAudioCache` 的键也就变了，预生成的缓存会全部命中不了。
 */
private fun processTextForTts(text: String, settings: Settings): String {
    var result = text
    if (settings.displaySetting.ttsOnlyReadQuoted) {
        result = result.extractQuotedContentAsText() ?: result
    }
    if (settings.displaySetting.ttsOnlyReadOutsideBrackets) {
        result = result.removeBracketedContent() ?: result
    }
    return result.stripMarkdown()
}
