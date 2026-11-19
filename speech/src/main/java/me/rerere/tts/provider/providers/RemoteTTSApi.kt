package me.rerere.tts.provider.providers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val TAG = "RemoteTTSApi"
private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** 服务端单个文件上传上限: 20MB (base64 膨胀 4/3, 服务端请求体上限约 32MB) */
internal const val REMOTE_TTS_MAX_UPLOAD_BYTES: Long = 20L * 1024 * 1024

/**
 * 远程 TTS 服务端的一个音色。
 *
 * [kind] 只有两种取值:
 * - `speaker`: 模型自带的内置说话人, 不可删除;
 * - `registered`: 通过 `POST /audio/voices` 注册的克隆音色, 可删除。
 */
data class RemoteVoiceInfo(
    val name: String,
    val kind: String,
) {
    val isRegistered: Boolean
        get() = kind.equals("registered", ignoreCase = true)
}

/**
 * [RemoteTTSApi] 调用失败。
 *
 * [statusCode] 为 HTTP 状态码; `0` 表示请求没有拿到任何 HTTP 响应 (连接/超时/DNS 等)。
 */
class RemoteTTSApiException(
    message: String,
    val statusCode: Int,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val isRetryable: Boolean
        get() = statusCode == 0 || statusCode == 408 || statusCode == 429 || statusCode in 500..599
}

/**
 * 远程 TTS 服务的控制面 API (无状态, 每次调用自行发请求)。
 *
 * 覆盖服务端除合成 (`POST /audio/speech`, 见 [RemoteTTSProvider]) 之外的管理接口:
 * - `GET  {baseUrl}/health`               -> [health] (可选端点, 详见 [health])
 * - `GET  {baseUrl}/models`               -> [listModels]
 * - `GET  {baseUrl}/audio/voices`         -> [listVoices]
 * - `POST {baseUrl}/audio/voices` (wav)   -> [registerVoiceWithWav]
 * - `POST {baseUrl}/audio/voices` (latents) -> [registerVoiceWithLatents]
 * - `DELETE {baseUrl}/audio/voices/{name}` -> [deleteVoice]
 *
 * baseUrl 约定已包含 `/v1` (与 OpenAI 一致); [TTSProviderSetting.RemoteTTS.apiKey] 非空时
 * 每个请求都带 `Authorization: Bearer`。失败统一抛 [RemoteTTSApiException]。
 *
 * 本类不持有可变状态, UI 层可以直接 `remember { RemoteTTSApi() }`。
 */
class RemoteTTSApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // 注册克隆音色要上传参考音频, 服务端还要做说话人编码, 给足超时
    private val uploadClient = client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * `GET {baseUrl}/health`, 返回 `status == "ok"`。
     *
     * 注意: 该端点是**可选**的, OpenAI 规范里并不存在。部分自建服务把健康检查挂在根路径
     * `/health` (而不是 `{baseUrl}/health`), 或者干脆不实现, 于是这里会抛 [RemoteTTSApiException]
     * (典型为 404)。**调用方不得据此判定服务不可用** —— 连通性应看 [listModels]。
     */
    suspend fun health(setting: TTSProviderSetting.RemoteTTS): Boolean = withContext(Dispatchers.IO) {
        val body = client.executeText(
            requestBuilder(setting, remoteTtsEndpoint(setting.baseUrl, "health")).get().build()
        )
        parseJsonObjectOrNull(body)
            ?.get("status")
            ?.stringOrNull()
            ?.equals("ok", ignoreCase = true)
            ?: false
    }

    /** `GET /models`, 返回 `data[].id` */
    suspend fun listModels(setting: TTSProviderSetting.RemoteTTS): List<String> = withContext(Dispatchers.IO) {
        val body = client.executeText(
            requestBuilder(setting, remoteTtsEndpoint(setting.baseUrl, "models")).get().build()
        )
        parseJsonObjectOrNull(body)
            ?.get("data")
            ?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { element ->
                runCatching { element.jsonObject["id"]?.stringOrNull() }
                    .getOrNull()
            }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    /** `GET /audio/voices`, 返回 `voices[].{name, kind}` */
    suspend fun listVoices(setting: TTSProviderSetting.RemoteTTS): List<RemoteVoiceInfo> =
        withContext(Dispatchers.IO) {
            val body = client.executeText(
                requestBuilder(setting, remoteTtsEndpoint(setting.baseUrl, "audio", "voices"))
                    .get()
                    .build()
            )
            parseJsonObjectOrNull(body)
                ?.get("voices")
                ?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { element ->
                    runCatching {
                        val obj = element.jsonObject
                        val name = obj["name"]?.stringOrNull()
                        val kind = obj["kind"]?.stringOrNull()
                        if (name.isNullOrBlank()) null else RemoteVoiceInfo(name, kind.orEmpty())
                    }.getOrNull()
                }
                ?: emptyList()
        }

    /**
     * `POST /audio/voices`, 用整段 WAV 参考音频注册克隆音色。
     *
     * @param name 新音色名, 必填; 与服务端已有音色重名会被服务端拒绝
     * @param refText 参考音频对应的文本, 留空则不下发
     * @param wavBytes WAV 参考音频原始字节 (上限 [REMOTE_TTS_MAX_UPLOAD_BYTES]), 内部做 base64
     */
    suspend fun registerVoiceWithWav(
        setting: TTSProviderSetting.RemoteTTS,
        name: String,
        refText: String,
        wavBytes: ByteArray,
    ): Unit = withContext(Dispatchers.IO) {
        checkRemoteUploadSize(wavBytes, "wav")
        val payload = buildJsonObject {
            put("name", name)
            if (refText.isNotBlank()) {
                put("ref_text", refText)
            }
            put("wav_b64", Base64.getEncoder().encodeToString(wavBytes))
        }
        uploadClient.executeIgnoringBody(
            requestBuilder(setting, remoteTtsEndpoint(setting.baseUrl, "audio", "voices"))
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
    }

    /**
     * `POST /audio/voices`, 用预提取的 speaker/rvq latents 注册克隆音色。
     *
     * @param spkBytes speaker embedding 原始字节, 内部做 base64
     * @param rvqBytes rvq codes 原始字节, 内部做 base64 (与 spk 必须成对出现)
     */
    suspend fun registerVoiceWithLatents(
        setting: TTSProviderSetting.RemoteTTS,
        name: String,
        refText: String,
        spkBytes: ByteArray,
        rvqBytes: ByteArray,
    ): Unit = withContext(Dispatchers.IO) {
        checkRemoteUploadSize(spkBytes, "spk")
        checkRemoteUploadSize(rvqBytes, "rvq")
        val payload = buildJsonObject {
            put("name", name)
            if (refText.isNotBlank()) {
                put("ref_text", refText)
            }
            put("spk_b64", Base64.getEncoder().encodeToString(spkBytes))
            put("rvq_b64", Base64.getEncoder().encodeToString(rvqBytes))
        }
        uploadClient.executeIgnoringBody(
            requestBuilder(setting, remoteTtsEndpoint(setting.baseUrl, "audio", "voices"))
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
    }

    /** `DELETE /audio/voices/{name}`, 路径段自动 URL 编码 (音色名可能含空格/中文/斜杠) */
    suspend fun deleteVoice(
        setting: TTSProviderSetting.RemoteTTS,
        name: String,
    ): Unit = withContext(Dispatchers.IO) {
        if (name.isBlank()) {
            // 空名字会打到 `/audio/voices/` (集合本身), DELETE 到集合没有意义, 直接拒绝
            throw RemoteTTSApiException("Voice name must not be blank", statusCode = 400)
        }
        uploadClient.executeIgnoringBody(
            requestBuilder(setting, remoteTtsEndpoint(setting.baseUrl, "audio", "voices", name))
                .delete()
                .build()
        )
    }

    private fun requestBuilder(
        setting: TTSProviderSetting.RemoteTTS,
        url: HttpUrl,
    ): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json")
        if (setting.apiKey.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer ${setting.apiKey}")
        }
        return builder
    }
}

/**
 * 拼接 `{baseUrl}/{segments...}`, baseUrl 已含 `/v1` 之类的前缀。
 * 每个 segment 都会被 OkHttp 做路径编码, 因此音色名里的空格/中文/斜杠都是安全的。
 */
internal fun remoteTtsEndpoint(baseUrl: String, vararg segments: String): HttpUrl {
    val normalized = baseUrl.trim().trimEnd('/')
    val url = normalized.toHttpUrlOrNull()
        ?: throw RemoteTTSApiException("Invalid base url: $baseUrl", statusCode = 0)
    val builder = url.newBuilder()
    segments.forEach { builder.addPathSegment(it) }
    return builder.build()
}

/** 上传体积保护: 超过 20MB 直接失败, 不发起请求 (避免 base64 后撑爆服务端 32MB 上限) */
internal fun checkRemoteUploadSize(bytes: ByteArray, label: String) {
    if (bytes.size.toLong() > REMOTE_TTS_MAX_UPLOAD_BYTES) {
        throw RemoteTTSApiException(
            message = "$label too large: ${bytes.size} bytes, limit is " +
                "${REMOTE_TTS_MAX_UPLOAD_BYTES / 1024 / 1024}MB",
            statusCode = 413,
        )
    }
}

/** 从 `{"error":{"message":"..."}}` 里取 message, 取不到返回 null */
internal fun parseRemoteErrorMessage(body: String): String? {
    return parseJsonObjectOrNull(body)
        ?.get("error")
        ?.let { runCatching { it.jsonObject["message"]?.stringOrNull() }.getOrNull() }
        ?.takeIf { it.isNotBlank() }
}

/**
 * 错误文案: 优先用服务端的 `error.message` (例如非 base 模型克隆时的
 * "only valid for base models"), 没有则回落到 `HTTP {code} {message}`。
 */
internal fun remoteErrorMessage(statusCode: Int, statusMessage: String, body: String): String {
    return parseRemoteErrorMessage(body)
        ?: "HTTP $statusCode $statusMessage".trim()
}

/** 把非 2xx 响应转成异常 */
internal fun remoteErrorFromResponse(response: Response, body: String): RemoteTTSApiException {
    return RemoteTTSApiException(
        message = remoteErrorMessage(response.code, response.message, body),
        statusCode = response.code,
    )
}

private fun parseJsonObjectOrNull(text: String): JsonObject? =
    runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

/** 只有 JSON 字符串/数字等标量才取内容, 对象/数组一律返回 null */
private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

/** 只关心是否成功的写请求 (响应体丢弃) */
private fun OkHttpClient.executeIgnoringBody(request: Request) {
    executeText(request)
}

/**
 * 执行请求并返回响应体文本; 非 2xx 与网络异常统一转换成 [RemoteTTSApiException]。
 */
private fun OkHttpClient.executeText(request: Request): String {
    return try {
        newCall(request).execute().use { response ->
            val body = runCatching { response.body.string() }.getOrNull().orEmpty()
            if (!response.isSuccessful) {
                Log.e(TAG, "${request.method} ${request.url} failed: ${response.code}, body=$body")
                throw remoteErrorFromResponse(response, body)
            }
            body
        }
    } catch (e: RemoteTTSApiException) {
        throw e
    } catch (e: IOException) {
        Log.e(TAG, "${request.method} ${request.url} network error", e)
        throw RemoteTTSApiException(
            message = e.message ?: "Network error: ${e::class.simpleName}",
            statusCode = 0,
            cause = e,
        )
    }
}
