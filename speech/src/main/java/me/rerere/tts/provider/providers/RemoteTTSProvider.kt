package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject
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
 * 请求体由 [buildRemoteSpeechRequest] 构建: `input` 与 `response_format` 恒发,
 * `model` / `voice` / `language` / `instructions` 非空才发, 9 个采样参数非 null 才发
 * (null = 保留服务端引擎默认值)。
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
        val requestBody = buildRemoteSpeechRequest(providerSetting, request.text)

        Log.i(
            TAG,
            "generateSpeech: model=${providerSetting.model} voice=${providerSetting.voice} " +
                    "language=${providerSetting.language} format=${providerSetting.responseFormat} " +
                    "instructions=${providerSetting.instructions.isNotBlank()} " +
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
                    "instructions" to providerSetting.instructions,
                    "responseFormat" to providerSetting.responseFormat,
                )
            )
        )
    }.flowOn(Dispatchers.IO)
}

/**
 * 构建 `POST {baseUrl}/audio/speech` 的 JSON 请求体 (纯函数, 便于单测且不发网络)。
 *
 * 下发规则严格对齐服务端文档:
 * - `input` 与 `response_format` 恒发 (`response_format` 为空时回落到 "wav");
 * - `model` / `voice` / `language` / `instructions` 非空才发;
 * - 9 个采样参数非 null 才发, key 全部 snake_case; null 表示保留引擎默认值。
 *   注意: 旧版本用 `seed = -1` 表示"不下发", 迁移后应改用 `seed = null`。
 */
internal fun buildRemoteSpeechRequest(
    setting: TTSProviderSetting.RemoteTTS,
    input: String,
): JsonObject = buildJsonObject {
    put("input", input)
    // 服务端需要 response_format 决定返回完整 wav 还是 pcm 裸流, 因此恒发
    put("response_format", setting.responseFormat.ifBlank { "wav" })

    setting.model.takeIf { it.isNotBlank() }?.let { put("model", it) }
    setting.voice.takeIf { it.isNotBlank() }?.let { put("voice", it) }
    setting.language.takeIf { it.isNotBlank() }?.let { put("language", it) }
    setting.instructions.takeIf { it.isNotBlank() }?.let { put("instructions", it) }

    setting.seed?.let { put("seed", it) }
    setting.maxNewTokens?.let { put("max_new_tokens", it) }
    setting.temperature?.let { put("temperature", it) }
    setting.topK?.let { put("top_k", it) }
    setting.topP?.let { put("top_p", it) }
    setting.repetitionPenalty?.let { put("repetition_penalty", it) }
    setting.subtalkerTemperature?.let { put("subtalker_temperature", it) }
    setting.subtalkerTopK?.let { put("subtalker_top_k", it) }
    setting.subtalkerTopP?.let { put("subtalker_top_p", it) }
}
