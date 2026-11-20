package me.rerere.tts.controller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import me.rerere.tts.cache.TtsAudioCache
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import java.io.ByteArrayOutputStream

/**
 * Bridge TTS provider flow to a single audio buffer.
 *
 * 主构造器接收的是「怎么把一个请求变成音频流」这个动作本身，而不是 [TTSManager]：
 * [TTSManager] 是具体类且构造需要 Android Context，JVM 单测里无法构造，
 * 用动作注入后「命中缓存 / 绕过缓存」这类纯逻辑就能在 JVM 上直接测。
 * 生产代码仍然用 `TtsSynthesizer(ttsManager)` 构造。
 */
class TtsSynthesizer internal constructor(
    private val generate: (TTSProviderSetting, TTSRequest) -> Flow<AudioChunk>,
) {
    constructor(ttsManager: TTSManager) : this(ttsManager::generateSpeech)

    /**
     * 合成一片文本。
     *
     * @param bypassCache true = 用户要求「重新生成」：跳过 [TtsAudioCache] 的读取，强制请求服务端；
     *   成功后再把结果写回缓存，于是随后的普通播放 / 预取能直接命中这份新音频。
     *   只对 `RemoteTTS` 且 `cacheEnabled` 的 provider 有意义，其它 provider 本来就不落盘。
     */
    suspend fun synthesize(
        setting: TTSProviderSetting,
        chunk: TtsChunk,
        bypassCache: Boolean = false,
    ): TTSResponse = withContext(Dispatchers.IO) {
        // 只有远程 TTS 有磁盘缓存（文本 + provider 配置即可定位），其它 provider 保持原样
        val remote = setting as? TTSProviderSetting.RemoteTTS
        if (remote != null && remote.cacheEnabled) {
            val providerId = remote.id.toString()
            val key = TtsAudioCache.buildKey(remote, chunk.text)
            if (!bypassCache) {
                TtsAudioCache.get(providerId, key)?.let { return@withContext it }
            }
            val response = collectToResponse(
                generate(setting, TTSRequest(text = chunk.text))
            )
            TtsAudioCache.put(
                providerId = providerId,
                key = key,
                response = response,
                maxBytes = remote.cacheMaxMb.toLong() * 1024 * 1024,
            )
            return@withContext response
        }
        collectToResponse(
            generate(setting, TTSRequest(text = chunk.text))
        )
    }

    private suspend fun collectToResponse(flow: Flow<AudioChunk>): TTSResponse {
        var format: AudioFormat? = null
        var sampleRate: Int? = null
        val output = ByteArrayOutputStream()
        flow.collect { chunk ->
            if (format == null) format = chunk.format
            if (sampleRate == null) sampleRate = chunk.sampleRate
            output.write(chunk.data)
        }
        return TTSResponse(
            audioData = output.toByteArray(),
            format = format ?: AudioFormat.MP3,
            sampleRate = sampleRate
        )
    }
}
