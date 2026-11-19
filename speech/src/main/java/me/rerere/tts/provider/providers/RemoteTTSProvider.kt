package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "RemoteTTSProvider"
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * 自建 OpenAI 兼容 TTS 服务适配器 (qwen-talker 的 tts-server)。
 *
 * [POST {baseUrl}/audio/speech], baseUrl 约定已包含 `/v1`; apiKey 为空时不发 Authorization 头。
 * 请求体在 OpenAI 标准字段 (model/input/voice/response_format) 之外支持 language
 * (覆盖服务端默认语言)、temperature 与 seed, 空字符串字段一律不下发。
 *
 * 音频内容一次性读完整响应体后作为单个 [AudioChunk] 发出:
 * - response_format = "wav" (或其它非 pcm 格式): 服务端返回完整音频文件, 原样交给播放器;
 * - response_format = "pcm": 服务端返回 s16le 裸流 (无头部), 带上 sampleRate 交给
 *   AudioPlayer 补 WAV 头后再播放。
 *
 * 中途断连会直接抛异常, 不会把半段音频当成功; 非 2xx 抛 [TTSProviderException] 以便复用
 * 上层 TtsController 的重试逻辑。
 */
class RemoteTTSProvider : TTSProvider<TTSProviderSetting.RemoteTTS> {
    private val httpClient = OkHttpClient.Builder()
        // 一次性合成可能比较慢 (长文本 + 高音质模型), 给足连接与读取超时
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override val promptGuidance: String
        get() = "This TTS service synthesizes speech from plain text and can use a specific cloned " +
                "voice and an explicit language; no inline emotion tags are supported."

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.RemoteTTS,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = buildJsonObject {
            if (providerSetting.model.isNotBlank()) {
                put("model", providerSetting.model)
            }
            put("input", request.text)
            if (providerSetting.voice.isNotBlank()) {
                put("voice", providerSetting.voice)
            }
            if (providerSetting.language.isNotBlank()) {
                put("language", providerSetting.language)
            }
            if (providerSetting.responseFormat.isNotBlank()) {
                put("response_format", providerSetting.responseFormat)
            }
            put("temperature", providerSetting.temperature)
            // 负数表示不指定采样种子
            if (providerSetting.seed >= 0) {
                put("seed", providerSetting.seed)
            }
        }

        Log.i(
            TAG,
            "generateSpeech: model=${providerSetting.model} voice=${providerSetting.voice} " +
                    "language=${providerSetting.language} format=${providerSetting.responseFormat} " +
                    "length=${request.text.length}"
        )

        val requestBuilder = Request.Builder()
            .url("${providerSetting.baseUrl.trimEnd('/')}/audio/speech")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/octet-stream")
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))

        // apiKey 为空时不下发 Authorization 头 (自建服务常常不设鉴权)
        if (providerSetting.apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
        }

        val audioBytes = httpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                // 把错误响应体读出来方便排查 (4xx 通常返回 JSON 错误信息)
                val errorBody = runCatching { response.body.string() }.getOrNull().orEmpty()
                Log.e(
                    TAG,
                    "Remote TTS request failed: ${response.code} ${response.message}, body: $errorBody"
                )
                throw TTSProviderException(
                    message = "Remote TTS request failed: HTTP ${response.code} ${response.message}. body=$errorBody",
                    statusCode = response.code
                )
            }
            response.body.bytes()
        }

        if (audioBytes.isEmpty()) {
            throw Exception("Remote TTS returned 0 bytes")
        }

        val isPcm = providerSetting.responseFormat.trim().equals("pcm", ignoreCase = true)
        val audioFormat = when (providerSetting.responseFormat.trim().lowercase()) {
            "wav" -> AudioFormat.WAV
            "mp3" -> AudioFormat.MP3
            "ogg" -> AudioFormat.OGG
            "opus" -> AudioFormat.OPUS
            "aac" -> AudioFormat.AAC
            "pcm" -> AudioFormat.PCM
            else -> AudioFormat.WAV
        }

        emit(
            AudioChunk(
                data = audioBytes,
                format = audioFormat,
                // 裸 PCM 需要采样率才能补 WAV 头; 其它格式自带头部, 不需要
                sampleRate = if (isPcm) providerSetting.sampleRate else null,
                isLast = true,
                metadata = mapOf(
                    "provider" to "remote",
                    "model" to providerSetting.model,
                    "voice" to providerSetting.voice,
                    "language" to providerSetting.language,
                    "responseFormat" to providerSetting.responseFormat,
                )
            )
        )
    }.flowOn(Dispatchers.IO)
}
