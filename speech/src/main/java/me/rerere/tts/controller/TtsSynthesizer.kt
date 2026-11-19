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
 */
class TtsSynthesizer(
    private val ttsManager: TTSManager
) {
    suspend fun synthesize(
        setting: TTSProviderSetting,
        chunk: TtsChunk
    ): TTSResponse = withContext(Dispatchers.IO) {
        // 只有远程 TTS 有磁盘缓存（文本 + provider 配置即可定位），其它 provider 保持原样
        val remote = setting as? TTSProviderSetting.RemoteTTS
        if (remote != null && remote.cacheEnabled) {
            val providerId = remote.id.toString()
            val key = TtsAudioCache.buildKey(remote, chunk.text)
            TtsAudioCache.get(providerId, key)?.let { return@withContext it }
            val response = collectToResponse(
                ttsManager.generateSpeech(setting, TTSRequest(text = chunk.text))
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
            ttsManager.generateSpeech(setting, TTSRequest(text = chunk.text))
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
