package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.mergeTtsInstructions
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "StepTTSProvider"

// StepFun /v1/audio/speech 返回的二进制格式按 responseFormat 决定
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * 阶跃星辰 Step TTS 适配器。
 *
 * 走 [POST {baseUrl}/v1/audio/speech] 非流式接口, 一次性 POST 文本, 服务端返回完整音频
 * 二进制 (mp3/wav/pcm/opus/flac)。鉴权用标准 `Authorization: Bearer sk-xxx`。
 *
 * 字段命名遵循阶跃官方 Android SDK (TtsSpeechRequest) 的 camelCase 约定:
 * responseFormat / sampleRate / voiceLabel 等, 与 OpenAI 的 snake_case 不同, 切换 baseUrl
 * 到 OpenAI 兼容代理时需要注意。
 *
 * 官方文档:
 * - 模型总览: https://platform.stepfun.com/docs/zh/guides/models/stepaudio-2.5-tts
 * - 开发指南: https://platform.stepfun.com/docs/zh/guides/developer/tts
 */
class StepTTSProvider : TTSProvider<TTSProviderSetting.Step> {
    private val httpClient = OkHttpClient.Builder()
        // 一次性合成可能比较慢 (长文本 + 高质量模型), 给足读超时
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Step,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        // 只在 instruction 非空时下发; step-tts-mini / step-tts-vivid 不支持该字段,
        // 但服务端会忽略未知字段 (responseFormat 也允许 ignoreUnknownKeys)
        val requestBody = buildStepSpeechRequest(providerSetting, request.text, request.instruct)

        Log.i(
            TAG,
            "generateSpeech: model=${providerSetting.model} voice=${providerSetting.voice} format=${providerSetting.responseFormat}"
        )

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl.trimEnd('/')}/v1/audio/speech")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/octet-stream")
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = httpClient.newCall(httpRequest).execute()
        if (!response.isSuccessful) {
            // 把错误响应体读出来方便排查 (4xx 通常返回 JSON 错误信息)
            val errorBody = runCatching { response.body?.string() }.getOrNull().orEmpty()
            throw TTSProviderException(
                message = "Step TTS request failed: HTTP ${response.code} ${response.message}. body=$errorBody",
                statusCode = response.code
            )
        }

        val audioBytes = response.body?.bytes()
            ?: throw Exception("Step TTS returned empty body")

        if (audioBytes.isEmpty()) {
            throw Exception("Step TTS returned 0 bytes")
        }

        // StepFun 端的 format 字符串与 AudioFormat 枚举对齐
        val audioFormat = when (providerSetting.responseFormat.lowercase()) {
            "mp3" -> AudioFormat.MP3
            "wav" -> AudioFormat.WAV
            "pcm" -> AudioFormat.PCM
            "ogg" -> AudioFormat.OGG
            "opus" -> AudioFormat.OPUS
            "aac" -> AudioFormat.AAC
            else -> AudioFormat.MP3
        }

        emit(
            AudioChunk(
                data = audioBytes,
                format = audioFormat,
                sampleRate = providerSetting.sampleRate,
                isLast = true,
                metadata = mapOf(
                    "provider" to "step",
                    "model" to providerSetting.model,
                    "voice" to providerSetting.voice,
                    "responseFormat" to providerSetting.responseFormat,
                )
            )
        )
    }
}

/**
 * StepFun `instruction` 字段的服务端上限（≤200 字符）。
 * 设置页输入框与这里的截断共用同一个上限，避免「UI 能输进去、请求却被截」的不一致。
 */
internal const val STEP_INSTRUCTION_MAX_LENGTH = 200

/**
 * 构建 `POST {baseUrl}/v1/audio/speech` 的 JSON 请求体（纯函数，便于单测且不发网络）。
 *
 * 字段命名遵循阶跃官方 SDK 的 camelCase 约定；`instruction` 由设置页的静态
 * [TTSProviderSetting.Step.instruction] 与本次动态 [instruct] 合并而来（静态在前），
 * 合并结果超过 [STEP_INSTRUCTION_MAX_LENGTH] 时从尾部截断，合并后仍为空白则整键不下发。
 *
 * @param instruct 这一次朗读的动态语气指令，为空表示不叠加额外语气
 */
internal fun buildStepSpeechRequest(
    setting: TTSProviderSetting.Step,
    input: String,
    instruct: String = "",
): JsonObject = buildJsonObject {
    put("model", setting.model)
    put("input", input)
    put("voice", setting.voice)
    put("responseFormat", setting.responseFormat)
    put("speed", setting.speed)
    put("volume", setting.volume)
    put("sampleRate", setting.sampleRate)
    mergeTtsInstructions(setting.instruction, instruct, maxLength = STEP_INSTRUCTION_MAX_LENGTH)
        .takeIf { it.isNotBlank() }
        ?.let { put("instruction", it) }
}
