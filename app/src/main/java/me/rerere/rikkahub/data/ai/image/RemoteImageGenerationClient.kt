package me.rerere.rikkahub.data.ai.image

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.common.http.await
import me.rerere.rikkahub.data.datastore.ImageGenerationConfig
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val GENERATE_PATH = "/api/generate"
private const val AUTH_HEADER = "X-Auth-Token"
private const val MAX_DETAIL_CHARS = 800

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** 服务地址没填时的提示语：编排层提前拦一次，避免白跑一次 prompt 改写。 */
internal const val IMAGE_SERVICE_NOT_CONFIGURED = "图片生成服务地址未配置，请到 设置 → 图片生成 填写"

// 请求体里没填的字段直接不发：服务端的布尔/数字字段不接受 null
private val RequestJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

/** 发给远程出图服务的请求体，字段名跟服务端约定成败在 snake_case。 */
@Serializable
data class RemoteGenerateRequest(
    val prompt: String,
    val negative: String? = null,
    val size: String? = null,
    val steps: Int? = null,
    val seed: Long? = null,
    @SerialName("batch_size") val batchSize: Int? = null,
    @SerialName("filename_prefix") val filenamePrefix: String? = null,
    @SerialName("no_reference") val noReference: Boolean? = null,
    /** 参考图，`data:image/...;base64,...` 形式。 */
    val images: List<String>? = null,
    /** 服务端侧的单次任务超时秒数。 */
    val timeout: Int? = null,
)

@Serializable
data class RemoteGenerateResponse(
    val ok: Boolean = false,
    val error: String? = null,
    @SerialName("prompt_id") val promptId: String? = null,
    @SerialName("elapsed_sec") val elapsedSec: Double? = null,
    val images: List<RemoteImageItem> = emptyList(),
    val detail: JsonElement? = null,
    val meta: JsonObject? = null,
)

@Serializable
data class RemoteImageItem(
    val filename: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("saved_path") val savedPath: String? = null,
)

/** 出图结果：成功时带回图片字节，失败时带回可展示给模型与人看的原因。 */
sealed interface Result {
    data class Success(
        val images: List<ByteArray>,
        val fileNames: List<String>,
        val elapsedSec: Double?,
        val promptId: String?,
        val metaJson: String?,
    ) : Result

    data class Failure(
        val message: String,
        val httpCode: Int?,
        val detail: String?,
    ) : Result
}

/**
 * 远程出图服务的纯 HTTP 层：提交任务、下载结果图。
 *
 * 这里不碰 Settings 的其它字段、不落盘、不拼 prompt，只负责把一次请求跑完并给出结构化的结果。
 * 声明成 open 是为了单测能用假的子类替掉真实网络请求。
 */
open class RemoteImageGenerationClient(
    private val okHttpClient: OkHttpClient,
) {
    open suspend fun generate(config: ImageGenerationConfig, request: RemoteGenerateRequest): Result {
        val baseUrl = config.baseUrl.trim().trimEnd('/')
        if (baseUrl.isEmpty()) {
            return Result.Failure(IMAGE_SERVICE_NOT_CONFIGURED, null, null)
        }
        val endpoint = if (baseUrl.endsWith(GENERATE_PATH)) baseUrl else baseUrl + GENERATE_PATH
        return try {
            val body = RequestJson.encodeToString(request).toRequestBody(JSON_MEDIA_TYPE)
            val httpRequest = Request.Builder()
                .url(endpoint)
                .post(body)
                .applyAuth(config)
                .build()
            okHttpClient.newCall(httpRequest).await().use { response ->
                val text = response.body.string()
                val parsed = runCatching {
                    JsonInstant.decodeFromString<RemoteGenerateResponse>(text)
                }.getOrNull()
                if (!response.isSuccessful || parsed?.ok != true) {
                    return@use failure(response.code, parsed)
                }
                val downloaded = parsed.images.mapNotNull { item ->
                    item.downloadUrl?.takeIf { it.isNotBlank() }
                        ?: item.filename?.takeIf { it.isNotBlank() }?.let { "$baseUrl/files/$it" }
                }.mapNotNull { url -> download(url, config, baseUrl) }
                if (downloaded.isEmpty()) {
                    Result.Failure(
                        message = "图片生成服务返回成功，但没有取到任何图片",
                        httpCode = response.code,
                        detail = null,
                    )
                } else {
                    Result.Success(
                        images = downloaded,
                        fileNames = parsed.images.mapNotNull { it.filename },
                        elapsedSec = parsed.elapsedSec,
                        promptId = parsed.promptId,
                        metaJson = parsed.meta?.let { JsonInstant.encodeToString(JsonObject.serializer(), it) },
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.Failure(t.message ?: t::class.simpleName ?: "unknown", null, null)
        }
    }

    /** 非 2xx 或 `ok=false`：把服务端的 error 和状态码翻译成一句能直接读懂的话。 */
    private fun failure(httpCode: Int, parsed: RemoteGenerateResponse?): Result.Failure {
        val reason = parsed?.error?.takeIf { it.isNotBlank() } ?: "图片生成失败（HTTP $httpCode）"
        val hint = httpHint(httpCode)
        return Result.Failure(
            message = if (hint == null) reason else "$reason；$hint",
            httpCode = httpCode,
            detail = parsed?.detail?.let { detail ->
                runCatching { JsonInstant.encodeToString(JsonElement.serializer(), detail) }
                    .getOrNull()
                    ?.take(MAX_DETAIL_CHARS)
            },
        )
    }

    /** 服务端常见的几个状态码各自的含义，直接告诉用户该等还是该去查环境。 */
    private fun httpHint(httpCode: Int): String? = when (httpCode) {
        409 -> "服务正忙，稍后重试"
        502 -> "显存编排失败，请检查服务端 GPU 状态"
        503 -> "连不上 ComfyUI，请确认服务端工作流已启动"
        504 -> "等待出图超时，冷启动时可以再试一次"
        else -> null
    }

    /** 下载结果图；单张失败只跳过它，全部失败由调用方判定。 */
    private suspend fun download(url: String, config: ImageGenerationConfig, baseUrl: String): ByteArray? {
        val absolute = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "$baseUrl/${url.trimStart('/')}"
        }
        return try {
            val request = Request.Builder()
                .url(absolute)
                .get()
                .applyAuth(config)
                .build()
            okHttpClient.newCall(request).await().use { response ->
                if (response.isSuccessful) response.body.bytes() else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }
    }

    private fun Request.Builder.applyAuth(config: ImageGenerationConfig): Request.Builder {
        val token = config.authToken.trim()
        return if (token.isEmpty()) this else header(AUTH_HEADER, token)
    }
}
