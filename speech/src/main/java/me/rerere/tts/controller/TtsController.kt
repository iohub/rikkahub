package me.rerere.tts.controller

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.tts.cache.TtsFixedAudioStore
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSProviderSetting
import java.io.IOException
import java.util.UUID

private const val TAG = "TtsController"
private const val MAX_SYNTHESIS_ATTEMPTS = 3
private const val SYNTHESIS_RETRY_BASE_DELAY_MS = 500L

/**
 * TTS 控制器（重构版）
 * - 负责文本分片、预取合成、排队播放与状态上报
 * - 对外 API 与原版兼容
 */
class TtsController(
    context: Context,
    private val ttsManager: TTSManager
) {
    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 组件
    private val chunker = TextChunker(maxChunkLength = 160)
    private val synthesizer = TtsSynthesizer(ttsManager)
    private val audio = AudioPlayer(context)

    // Provider & 作业
    private var currentProvider: TTSProviderSetting? = null
    private var workerJob: Job? = null

    /**
     * 当前这次播放归属的聊天消息 id（null = 与具体消息无关）。
     *
     * 只用来给 [PlaybackState.messageId] 打标：UI 据此只高亮「正在播放的那条消息」。
     * 合成播放与固化播放共用它，[stop]/[internalReset] 时清空。
     */
    private var currentMessageId: String? = null

    /**
     * 播放代次：每次开始新一轮播放 / 重置 / 停止都自增。
     *
     * 取消 worker 是异步的——被取消的协程的 `finally` 会在之后才执行。用代次判断「我是否还是当前这次播放」，
     * 避免上一轮播放的收尾把新一轮的状态、消息标记冲掉（合成播放与固化播放共用 workerJob，这一点尤其重要）。
     */
    private var playbackGeneration = 0L

    // 后台预生成作业：与播放的 workerJob 完全独立，且只保留最新一次
    private var prefetchJob: Job? = null
    private var isPaused = false

    // 队列与缓存（基于稳定 ID）
    private val queue: java.util.concurrent.ConcurrentLinkedQueue<TtsChunk> = java.util.concurrent.ConcurrentLinkedQueue()
    private val allChunks: MutableList<TtsChunk> = mutableListOf()
    private val cache = java.util.concurrent.ConcurrentHashMap<UUID, kotlinx.coroutines.Deferred<TTSResponse>>()

    // 行为参数
    private val chunkDelayMs = 120L
    private val prefetchCount = 2

    // 状态流（保留与旧版兼容的 StateFlow）
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _currentChunk = MutableStateFlow(0)
    val currentChunk: StateFlow<Int> = _currentChunk.asStateFlow()

    private val _totalChunks = MutableStateFlow(0)
    val totalChunks: StateFlow<Int> = _totalChunks.asStateFlow()

    // 统一播放状态（融合音频播放 + 分片进度）
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    init {
        // 同步底层播放器状态到统一状态，并补充分片信息
        scope.launch {
            audio.playbackState.collectLatest { audioState ->
                _playbackState.update {
                    audioState.copy(
                        currentChunkIndex = _currentChunk.value,
                        totalChunks = _totalChunks.value,
                        // AudioPlayer 不知道消息归属，这里用 controller 的标记覆盖，避免被冲成 null
                        messageId = currentMessageId,
                        // 没有 provider 时不允许出现「正在播放」；但固化音频是离线的，
                        // 它播放期间 isSpeaking 为 true，此时不强制回落 Idle
                        status = if (!_isAvailable.value && !_isSpeaking.value) {
                            PlaybackStatus.Idle
                        } else {
                            audioState.status
                        }
                    )
                }
            }
        }
    }

    /** 选择/取消选择 Provider */
    fun setProvider(provider: TTSProviderSetting?) {
        currentProvider = provider
        _isAvailable.update { provider != null }
        if (provider == null) stop()
    }

    /**
     * 朗读文本
     * - flush=true: 清空当前进度并重新开始
     * - flush=false: 继续队列，追加朗读
     *
     * @param messageId 这段文本归属的聊天消息 id，仅用于给 [PlaybackState.messageId] 打标；
     *   与具体消息无关的场合（设置页试听、语音模式）留空即可
     */
    fun speak(text: String, flush: Boolean = true, messageId: String? = null) {
        if (text.isBlank()) return
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        val newChunks = chunker.split(text)
        if (newChunks.isEmpty()) return

        if (flush) {
            internalReset()
            allChunks.addAll(newChunks)
            queue.addAll(newChunks)
            _currentChunk.update { 0 }
        } else {
            // 追加时，重映射 index 以保持全局顺序
            val startIndex = (allChunks.lastOrNull()?.index ?: -1) + 1
            val remapped = newChunks.mapIndexed { i, c -> c.copy(index = startIndex + i) }
            allChunks.addAll(remapped)
            queue.addAll(remapped)
        }
        // internalReset() 已把消息标记清空，这里在它之后重新打标
        currentMessageId = messageId
        _totalChunks.update { queue.size }
        _error.update { null }

        _playbackState.update {
            it.copy(
                currentChunkIndex = _currentChunk.value,
                totalChunks = _totalChunks.value,
                messageId = currentMessageId,
                status = PlaybackStatus.Buffering
            )
        }

        if (workerJob?.isActive != true) startWorker()
    }

    /**
     * 后台预生成：[text] 走与 [speak] 相同的分片与合成链路，但只写入磁盘缓存，不播放。
     * 用于「生成完成后自动预生成」，让随后的播放命中 TtsAudioCache。
     *
     * 只碰 [chunker] 与 [synthesizer]：不写队列、不写进程内合成缓存、不动播放状态机，也不进 [audio]。
     * 仅当当前 provider 为 RemoteTTS 且其 cacheEnabled 打开时才有意义，其它情况直接跳过
     * （此时 [TtsSynthesizer.synthesize] 仍会请求服务端，只是不落盘，白耗流量）。
     */
    fun prefetch(text: String) {
        if (text.isBlank()) return
        val provider = currentProvider
        if (provider !is TTSProviderSetting.RemoteTTS || !provider.cacheEnabled) return

        // 串行化并支持取消：连发多条消息时只保留最后一次，避免后台堆积大量合成请求抢占服务
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            for (chunk in chunker.split(text)) {
                // 预生成是纯后台任务，被取消时立刻退出，不要继续请求剩余分片
                ensureActive()
                try {
                    synthesizer.synthesize(provider, chunk)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 静默失败：单段失败不影响其它分片，也不向 UI 报错（用户手动播放时还会重试）
                    Log.d(TAG, "Prefetch failed for chunk ${chunk.index}", e)
                }
            }
        }
    }

    // region 消息级语音固化
    /**
     * 离线合成整段文本，供「固化」使用：调用方拿到列表后自己写 [TtsFixedAudioStore]。
     *
     * 文本处理与 [speak] 完全一致（同一个 [chunker]）：markdown 清洗等在 app 的 hook 层完成，
     * controller 不做二次加工，保证固化下来的分片与正常播放听到的内容一模一样。
     *
     * 与 [speak] 的区别只在于**不播放**：不碰 queue / allChunks / 进程内合成缓存 /
     * currentChunk / totalChunks / playbackState，只读 [currentProvider]、只写 [_error]。
     *
     * @param bypassCache true = 重新生成：跳过 `TtsAudioCache` 直接请求服务端，
     *   成功后覆盖缓存（详见 [TtsSynthesizer.synthesize]）
     * @return 全部分片；任一分片最终失败则返回空列表（不留半套固化音频）并在 [_error] 写原因
     */
    suspend fun synthesizeAll(text: String, bypassCache: Boolean = false): List<TTSResponse> =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext emptyList()
            val provider = currentProvider
            if (provider == null) {
                _error.update { "No TTS provider selected" }
                return@withContext emptyList()
            }
            val chunks = chunker.split(text)
            if (chunks.isEmpty()) return@withContext emptyList()

            val responses = ArrayList<TTSResponse>(chunks.size)
            for (chunk in chunks) {
                try {
                    // 串行合成：失败沿用播放链路的重试策略（指数退避，最多 3 次）
                    responses += synthesizeWithRetry(provider, chunk, bypassCache)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 固化要求整条消息完整：缺一片就整体失败，避免留下「播到一半就没了」的固化音频
                    Log.e(TAG, "Fixed synthesis failed for chunk ${chunk.index}", e)
                    _error.update { "TTS 固化失败: ${e.message ?: "synthesis error"}" }
                    return@withContext emptyList()
                }
            }
            _error.update { null }
            responses
        }

    /**
     * 播放某条消息**已固化**的音频：全程读本地文件，不再请求服务端。
     *
     * 与 [speak] 共用 [workerJob] 与同一套状态流，因此「同一时刻只有一条播放」：
     * 调用它会先打断正在进行的合成播放（[internalReset]），反之亦然。
     * [stop] / [internalReset] 的取消语义对它同样生效。
     *
     * 某一片缺失或损坏时跳过并继续下一片（不整段失败）；一片都读不到时只写 [_error]，
     * 状态回到 [PlaybackStatus.Idle]，不抛异常。
     *
     * @param chunkCount 期望的分片数（一般来自消息上记录的固化信息）；<=0 时按磁盘上实际连续的分片数播放，
     *   大于磁盘实际数量时以磁盘为准，避免越界
     */
    fun speakFixed(messageId: String, chunkCount: Int) {
        // 打断正在进行的合成播放 / 上一次固化播放：workerJob 是唯一的播放权
        internalReset()
        currentMessageId = messageId
        _currentChunk.update { 0 }
        _totalChunks.update { chunkCount.coerceAtLeast(0) }
        _playbackState.update {
            PlaybackState(
                status = PlaybackStatus.Buffering,
                totalChunks = _totalChunks.value,
                messageId = messageId,
            )
        }

        val generation = ++playbackGeneration
        workerJob = scope.launch {
            _isSpeaking.update { true }
            var played = 0
            var total = 0
            try {
                // 目录探测是文件 IO，放到 IO 线程；stored 才是可信的分片数
                val stored = withContext(Dispatchers.IO) {
                    TtsFixedAudioStore.chunkCount(messageId)
                }
                if (stored <= 0) {
                    _error.update { "固化音频缺失" }
                    return@launch
                }
                total = if (chunkCount > 0) chunkCount.coerceAtMost(stored) else stored
                _totalChunks.update { total }
                _playbackState.update { it.copy(totalChunks = total, messageId = currentMessageId) }

                for (index in 0 until total) {
                    ensureActive()
                    // 暂停时原地等待：不能用 continue 跳过，循环变量一涨就等于丢掉这一片
                    while (isPaused) delay(80)

                    _currentChunk.update { index + 1 }
                    _playbackState.update {
                        it.copy(
                            currentChunkIndex = index + 1,
                            totalChunks = total,
                            messageId = currentMessageId,
                        )
                    }

                    val response = withContext(Dispatchers.IO) {
                        TtsFixedAudioStore.readChunk(messageId, index)
                    }
                    if (response == null) {
                        // 单片缺失/损坏：跳过继续下一片，最后一片都没播到时再统一报错
                        Log.w(TAG, "Fixed tts audio chunk $index is missing for message $messageId")
                        continue
                    }

                    played++
                    try {
                        audio.play(response)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Playback error", e)
                        _error.update { e.message ?: "Audio playback error" }
                    }
                }
            } finally {
                // 已被新一轮播放 / stop() 取代时不要改状态，否则会把新一轮的状态冲掉
                if (generation == playbackGeneration) {
                    _isSpeaking.update { false }
                    if (played == 0) {
                        _error.update { "固化音频缺失" }
                        _playbackState.update {
                            it.copy(status = PlaybackStatus.Idle, messageId = currentMessageId)
                        }
                    } else {
                        _playbackState.update {
                            it.copy(
                                status = PlaybackStatus.Ended,
                                currentChunkIndex = played,
                                totalChunks = if (total > 0) total else it.totalChunks,
                                messageId = currentMessageId,
                            )
                        }
                    }
                }
            }
        }
    }
    // endregion

    private fun internalReset() {
        // Reset current session while keeping provider availability
        workerJob?.cancel()
        audio.stop()
        audio.clear()
        isPaused = false
        queue.clear()
        allChunks.clear()
        cache.values.forEach { it.cancel(CancellationException("Reset")) }
        cache.clear()
        _isSpeaking.update { false }
        _currentChunk.update { 0 }
        _totalChunks.update { 0 }
        _error.update { null }
        currentMessageId = null
        playbackGeneration++
        _playbackState.update { PlaybackState(status = PlaybackStatus.Idle) }
    }

    /** 暂停播放（保留进度） */
    fun pause() {
        isPaused = true
        audio.pause()
        _playbackState.update { it.copy(status = PlaybackStatus.Paused) }
    }

    /** 恢复播放 */
    fun resume() {
        isPaused = false
        audio.resume()
        _playbackState.update { it.copy(status = PlaybackStatus.Playing) }
    }

    /** 快进当前音频 */
    fun fastForward(ms: Long = 5_000) {
        audio.seekBy(ms)
    }

    /** 设置播放速度 */
    fun setSpeed(speed: Float) {
        audio.setSpeed(speed)
    }

    /** 跳过下一段（不打断当前正在播放） */
    fun skipNext() {
        if (queue.isNotEmpty()) {
            queue.poll()
            _totalChunks.update { queue.size }
        }
    }

    /** 停止并清空状态 */
    fun stop() {
        workerJob?.cancel()
        audio.stop()
        audio.clear()
        isPaused = false
        queue.clear()
        allChunks.clear()
        cache.values.forEach { it.cancel(CancellationException("Stopped")) }
        cache.clear()
        _isSpeaking.update { false }
        _currentChunk.update { 0 }
        _totalChunks.update { 0 }
        currentMessageId = null
        playbackGeneration++
        _playbackState.update { PlaybackState(status = PlaybackStatus.Idle) }
    }

    /** 释放资源 */
    fun dispose() {
        stop()
        scope.cancel()
        audio.release()
    }

    // region 内部：播放调度
    private fun startWorker() {
        val provider = currentProvider
        if (provider == null) {
            _error.update { "No TTS provider selected" }
            return
        }

        val generation = ++playbackGeneration
        workerJob = scope.launch {
            _isSpeaking.update { true }
            var processedCount = _currentChunk.value
            try {
                while (isActive) {
                    if (isPaused) {
                        delay(80)
                        continue
                    }

                    val chunk = queue.poll() ?: break

                    // 更新状态（1-based）
                    _currentChunk.update { processedCount + 1 }
                    _totalChunks.update { queue.size + 1 }
                    _playbackState.update {
                        it.copy(
                            currentChunkIndex = _currentChunk.value,
                            totalChunks = _totalChunks.value,
                            messageId = currentMessageId,
                        )
                    }

                    // 仅预取当前分片后的固定窗口
                    prefetchNextChunks(chunk.index)

                    val response = try {
                        awaitOrCreate(chunk, provider)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Synthesis error", e)
                        _error.update { e.message ?: "TTS synthesis error" }
                        processedCount++
                        continue
                    }

                    // 播放
                    try {
                        audio.play(response)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Playback error", e)
                        _error.update { e.message ?: "Audio playback error" }
                    }

                    if (queue.isNotEmpty()) delay(chunkDelayMs)

                    processedCount++
                }
            } finally {
                // 已被新一轮播放 / stop() 取代时不要改状态，否则会把新一轮的状态冲掉
                if (generation == playbackGeneration) {
                    _isSpeaking.update { false }
                    if (queue.isEmpty()) {
                        _playbackState.update { it.copy(status = PlaybackStatus.Ended) }
                    }
                }
            }
        }
    }

    private fun prefetchNextChunks(currentIndex: Int) {
        val provider = currentProvider ?: return
        val begin = currentIndex + 1
        val endExclusive = (begin + prefetchCount).coerceAtMost(allChunks.size)
        if (begin >= endExclusive) return

        for (i in begin until endExclusive) {
            val chunk = allChunks.getOrNull(i) ?: continue
            getOrCreateSynthesis(chunk, provider)
        }
    }

    private suspend fun awaitOrCreate(chunk: TtsChunk, provider: TTSProviderSetting): TTSResponse {
        val deferred = getOrCreateSynthesis(chunk, provider)
        return try {
            deferred.await()
        } catch (e: Exception) {
            // 避免后续复用已经失败或取消的 Deferred
            cache.remove(chunk.id, deferred)
            throw e
        }
    }

    private fun getOrCreateSynthesis(
        chunk: TtsChunk,
        provider: TTSProviderSetting
    ): kotlinx.coroutines.Deferred<TTSResponse> {
        return cache.computeIfAbsent(chunk.id) {
            scope.async(Dispatchers.IO) {
                synthesizeWithRetry(provider, chunk)
            }
        }
    }

    private suspend fun synthesizeWithRetry(
        provider: TTSProviderSetting,
        chunk: TtsChunk,
        bypassCache: Boolean = false,
    ): TTSResponse {
        var attempt = 1
        while (true) {
            try {
                return synthesizer.synthesize(provider, chunk, bypassCache)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!e.isRetryableSynthesisError() || attempt >= MAX_SYNTHESIS_ATTEMPTS) throw e

                val retryDelayMs = SYNTHESIS_RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))
                Log.w(
                    TAG,
                    "Synthesis attempt $attempt/$MAX_SYNTHESIS_ATTEMPTS failed for chunk ${chunk.index}; " +
                        "retrying in ${retryDelayMs}ms",
                    e
                )
                delay(retryDelayMs)
                attempt++
            }
        }
    }
    // endregion
}

private fun Exception.isRetryableSynthesisError(): Boolean {
    return this is IOException || this is TTSProviderException && isRetryable
}
