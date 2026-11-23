package me.rerere.rikkahub.data.ai.image

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val GENERATE_PATH = "/api/generate"
private const val ASYNC_GENERATE_PATH = "/api/generate/async"
private const val JOBS_PATH = "/api/jobs"
// 服务端照片库：上传一次全身照片，之后每次出图带上 photo_id 就能固定占 workflow 的 image1
private const val PHOTOS_PATH = "/api/photos"
private const val AUTH_HEADER = "X-Auth-Token"
private const val MAX_DETAIL_CHARS = 800

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** 服务地址没填时的提示语：编排层提前拦一次，避免白跑一次 prompt 改写。 */
internal const val IMAGE_SERVICE_NOT_CONFIGURED = "图片生成服务地址未配置，请到 设置 → 图片生成 填写"

/** 失败时给模型的唯一线索：任务很可能还在服务端跑，别急着重画。 */
internal const val JOB_RECOVERY_HINT =
    "任务可能仍在服务端继续，可稍后用 get_image_generation_job 查询该 job_id 的进度与产物"

// 单次 HTTP 调用的独立超时。OkHttp 默认 readTimeout 是 10 分钟，断网时一个卡住的
// 轮询就能把整条链路钉死，所以每一次调用都必须自己能收口。
private const val CALL_TIMEOUT_MS = 20_000L

// 结果图下载宽松一点：768x768 的 PNG 也有近 1MB，慢链路上 20 秒未必够
private const val DOWNLOAD_TIMEOUT_MS = 60_000L

// 照片上传是一次性的几 MB body：既不能像短请求那样 20 秒收口，也不必按出图时长等
private const val UPLOAD_TIMEOUT_MS = 60_000L

private const val POLL_INTERVAL_MS = 2_000L

// 查询 404（服务端把任务丢了）时最多重新提交几次，避免死循环刷服务端
private const val MAX_RESUBMITS = 3
private const val DEFAULT_REQUEST_TIMEOUT_SECONDS = 1200

// 退避序列：2s → 3s → 5s → 8s → 10s（封顶）。断网时别把服务端打满，也别等太久
private val RETRY_BACKOFF_MS = longArrayOf(2_000L, 3_000L, 5_000L, 8_000L, 10_000L)

// 单张图下载失败的重试间隔
private val DOWNLOAD_RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 3_000L)

// 老服务端根本不认识 /api/generate/async：这三个状态码说明它没升级
private val LEGACY_FALLBACK_CODES = setOf(404, 405, 501)

internal const val JOB_STATUS_QUEUED = "queued"
internal const val JOB_STATUS_RUNNING = "running"
internal const val JOB_STATUS_SUCCEEDED = "succeeded"
internal const val JOB_STATUS_FAILED = "failed"
internal const val JOB_STATUS_INTERRUPTED = "interrupted"

/** client_job_id 的服务端字符集约束：提交前自检一次，别把 400 留给自己。 */
internal val CLIENT_JOB_ID_PATTERN = Regex("^[A-Za-z0-9._:-]{1,128}$")

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
    /**
     * 客户端自己生成的任务幂等键。
     *
     * 只有异步提交会带上它：任务 id 握在客户端手里，POST 的响应丢了也能用同一个 id 查回来。
     * 为 null 时不进请求体，保持旧同步接口的 wire 契约。
     */
    @SerialName("client_job_id") val clientJobId: String? = null,
    /**
     * 服务端照片库里的全身照片 id。
     *
     * 带上后服务端会把这张照片固定放在 images 最前（占 workflow 的 image1），用户自己传的
     * images 顺延到 image2。为 null 时不进请求体，保持没有照片时的 wire 契约不变。
     */
    @SerialName("full_body_photo_id") val fullBodyPhotoId: String? = null,
)

/** 服务端照片库里的一张照片（`POST /api/photos` 与 `GET /api/photos` 的条目）。 */
@Serializable
data class RemotePhotoInfo(
    @SerialName("photo_id") val photoId: String,
    val filename: String? = null,
    val mime: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    val url: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
)

/** 上传照片的请求体：`image` 是 `data:image/png;base64,...` 形式的完整 data URL。 */
@Serializable
private data class RemotePhotoUploadRequest(
    val image: String,
    val filename: String? = null,
)

/** `POST /api/photos` 的响应。 */
@Serializable
private data class RemotePhotoUploadResponse(
    val ok: Boolean = false,
    val photo: RemotePhotoInfo? = null,
    val error: String? = null,
)

/** `DELETE /api/photos/<id>` 的响应。 */
@Serializable
private data class RemotePhotoDeleteResponse(
    val ok: Boolean = false,
    val deleted: Boolean = false,
    val error: String? = null,
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
    @SerialName("comfy_filename") val comfyFilename: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("download_url_attachment") val downloadUrlAttachment: String? = null,
    @SerialName("saved_path") val savedPath: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
)

/** 任务进度：`elapsed_sec` 从任务开始算起，`timeout_sec` 是服务端自己的超时上限。 */
@Serializable
data class RemoteJobProgress(
    @SerialName("elapsed_sec") val elapsedSec: Double? = null,
    @SerialName("timeout_sec") val timeoutSec: Double? = null,
)

/**
 * 异步任务快照：`POST /api/generate/async` 与 `GET /api/jobs/<key>` 的响应体。
 *
 * 全部字段带默认值：服务端版本之间会多字段/少字段，缺一个字段不该让整条断网续传链路崩掉。
 */
@Serializable
data class RemoteJobSnapshot(
    val ok: Boolean = true,
    @SerialName("job_id") val jobId: String? = null,
    @SerialName("client_job_id") val clientJobId: String? = null,
    val status: String? = null,
    val stage: String? = null,
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("finished_at") val finishedAt: Double? = null,
    val progress: RemoteJobProgress? = null,
    @SerialName("prompt_id") val promptId: String? = null,
    val images: List<RemoteImageItem> = emptyList(),
    val error: String? = null,
    @SerialName("http_status") val httpStatus: Int? = null,
    val detail: JsonElement? = null,
    val resource: JsonElement? = null,
    val meta: JsonObject? = null,
    val request: JsonObject? = null,
    @SerialName("status_url") val statusUrl: String? = null,
)

/** `GET /api/jobs` 的响应：列表项是去掉 detail/prompt 的紧凑快照。 */
@Serializable
data class RemoteJobsResponse(
    val ok: Boolean = false,
    val count: Int = 0,
    val jobs: List<RemoteJobSnapshot> = emptyList(),
    val error: String? = null,
)

/**
 * 任务状态到「下一步动作」的映射。
 *
 * 这是整条断网续传链路的核心判断，抽成纯函数便于单测：未知状态（服务端将来新增）按
 * 「继续等」处理——等不到由整体截止时间收口，总比误判成失败、把已经跑完的图丢掉好。
 */
internal enum class JobStatusKind { WAITING, SUCCEEDED, FAILED, INTERRUPTED }

internal fun jobStatusKind(status: String?): JobStatusKind = when (status?.trim()?.lowercase()) {
    JOB_STATUS_SUCCEEDED -> JobStatusKind.SUCCEEDED
    JOB_STATUS_FAILED -> JobStatusKind.FAILED
    JOB_STATUS_INTERRUPTED -> JobStatusKind.INTERRUPTED
    // queued / running / null / 任何没见过的状态：都当作「还没好」
    else -> JobStatusKind.WAITING
}

/** 第 [attempt] 次瞬时失败（0 起）之后该等多久。 */
internal fun retryBackoffMs(attempt: Int): Long =
    RETRY_BACKOFF_MS[attempt.coerceIn(0, RETRY_BACKOFF_MS.lastIndex)]

/** 客户端任务 id：UUID 恰好落在服务端允许的字符集里，且天然唯一。 */
internal fun newClientJobId(): String = UUID.randomUUID().toString()

/** 查单个任务的结果。 */
sealed interface JobLookup {
    data class Found(val snapshot: RemoteJobSnapshot) : JobLookup
    data object NotFound : JobLookup
    data class Error(val message: String, val httpCode: Int? = null) : JobLookup
}

/** 列最近任务的结果。 */
sealed interface JobListLookup {
    data class Ok(val jobs: List<RemoteJobSnapshot>) : JobListLookup
    data class Error(val message: String, val httpCode: Int? = null) : JobListLookup
}

/** 出图结果：成功时带回图片字节，失败时带回可展示给模型与人看的原因。 */
sealed interface Result {
    data class Success(
        val images: List<ByteArray>,
        val fileNames: List<String>,
        val elapsedSec: Double?,
        val promptId: String?,
        val metaJson: String?,
        /**
         * 服务端任务 id；断网后靠它把产物找回来。
         *
         * 降级到旧同步接口时这里是 `prompt_id`（老服务端没有任务概念，但总得给个可追查的 id）。
         */
        val jobId: String? = null,
    ) : Result

    data class Failure(
        val message: String,
        val httpCode: Int?,
        val detail: String?,
        /** 服务端 job_id，或提交时用的 client_job_id：查询工具的唯一入口。 */
        val jobId: String? = null,
    ) : Result
}

/** 提交、轮询两个阶段各自返回的「下一步」，让主循环保持扁平。 */
private sealed interface SubmitStep {
    data object Submitted : SubmitStep
    class Retry(val message: String) : SubmitStep
    class Abort(val result: Result) : SubmitStep
}

private sealed interface PollStep {
    data object Waiting : PollStep
    data object Resubmit : PollStep
    class Retry(val message: String) : PollStep
    class Done(val result: Result) : PollStep
}

/** 一次 HTTP 调用的结果：要么拿到响应，要么是可重试的传输层失败。 */
private sealed interface HttpOutcome {
    class Response(val code: Int, val body: ByteArray) : HttpOutcome {
        val text: String get() = String(body, Charsets.UTF_8)
        val isSuccessful: Boolean get() = code in 200..299
    }

    data class Transient(val message: String) : HttpOutcome
}

/**
 * 远程出图服务的纯 HTTP 层：异步提交任务、轮询进度、下载结果图。
 *
 * 这里不碰 Settings 的其它字段、不落盘、不拼 prompt，只负责把一次请求跑完并给出结构化的结果。
 * 声明成 open 是为了单测能用假的子类替掉真实网络请求。
 */
open class RemoteImageGenerationClient(
    private val okHttpClient: OkHttpClient,
) {
    // 短超时专用 client：共享连接池与线程池，只把 callTimeout 收紧。
    // 这是断网时的最后一道保险——Call.await() 被取消时底层 call 不会跟着断，
    // 只有 OkHttp 自己的 callTimeout 能真正把它掐掉。
    private val shortCallClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    private val downloadClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .callTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    private val uploadClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .callTimeout(UPLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * 跑一次出图：先异步提交拿到任务，再轮询到任务终态。
     *
     * 断网不等于失败：提交与轮询期间的 IO 错误一律按退避重试，直到 [RemoteGenerateRequest.timeout]
     * 秒的整体截止时间。任务 id 由客户端生成（client_job_id），所以哪怕 POST 的响应丢了，
     * 重连后也能用同一个 id 把任务查回来——这就是「断网后仍能拿回图」的全部秘密。
     */
    open suspend fun generate(config: ImageGenerationConfig, request: RemoteGenerateRequest): Result {
        val baseUrl = resolveBaseUrl(config.baseUrl)
        if (baseUrl.isEmpty()) {
            return Result.Failure(IMAGE_SERVICE_NOT_CONFIGURED, null, null)
        }
        val clientJobId = request.clientJobId?.takeIf { CLIENT_JOB_ID_PATTERN.matches(it) }
            ?: newClientJobId()
        val timeoutSeconds = request.timeout ?: DEFAULT_REQUEST_TIMEOUT_SECONDS
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        return runJob(
            config = config,
            baseUrl = baseUrl,
            asyncRequest = request.copy(clientJobId = clientJobId),
            clientJobId = clientJobId,
            deadline = deadline,
        )
    }

    /** 查一个任务的当前状态；[key] 可以是 job_id 也可以是 client_job_id。 */
    open suspend fun fetchJob(config: ImageGenerationConfig, key: String): JobLookup {
        val baseUrl = resolveBaseUrl(config.baseUrl)
        if (baseUrl.isEmpty()) return JobLookup.Error(IMAGE_SERVICE_NOT_CONFIGURED)
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return JobLookup.Error("job_id 不能为空")
        val request = Request.Builder()
            .url(baseUrl + jobPath(trimmed))
            .get()
            .applyAuth(config)
            .build()
        return when (val outcome = performCall(shortCallClient, request, CALL_TIMEOUT_MS)) {
            is HttpOutcome.Transient -> JobLookup.Error(outcome.message)
            is HttpOutcome.Response -> when {
                outcome.code == 404 -> JobLookup.NotFound
                outcome.isSuccessful -> {
                    val snapshot = decodeOrNull<RemoteJobSnapshot>(outcome.text)
                    if (snapshot?.ok == true) {
                        JobLookup.Found(snapshot)
                    } else {
                        JobLookup.Error(
                            decodeOrNull<RemoteGenerateResponse>(outcome.text)?.error
                                ?: "任务状态响应无法解析（HTTP ${outcome.code}）",
                            outcome.code,
                        )
                    }
                }
                else -> JobLookup.Error(
                    decodeOrNull<RemoteGenerateResponse>(outcome.text)?.error
                        ?: "查询任务失败（HTTP ${outcome.code}）",
                    outcome.code,
                )
            }
        }
    }

    /** 列最近的出图任务：App 重启后什么都不知道时的兜底入口。 */
    open suspend fun listJobs(
        config: ImageGenerationConfig,
        limit: Int = 10,
        status: String? = null,
    ): JobListLookup {
        val baseUrl = resolveBaseUrl(config.baseUrl)
        if (baseUrl.isEmpty()) return JobListLookup.Error(IMAGE_SERVICE_NOT_CONFIGURED)
        val url = buildString {
            append(baseUrl)
            append(JOBS_PATH)
            append("?limit=")
            append(limit.coerceIn(1, 100))
            status?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append("&status=")
                append(URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20"))
            }
        }
        val request = Request.Builder()
            .url(url)
            .get()
            .applyAuth(config)
            .build()
        return when (val outcome = performCall(shortCallClient, request, CALL_TIMEOUT_MS)) {
            is HttpOutcome.Transient -> JobListLookup.Error(outcome.message)
            is HttpOutcome.Response -> {
                val parsed = decodeOrNull<RemoteJobsResponse>(outcome.text)
                if (outcome.isSuccessful && parsed?.ok == true) {
                    JobListLookup.Ok(parsed.jobs)
                } else {
                    JobListLookup.Error(
                        parsed?.error ?: "查询任务列表失败（HTTP ${outcome.code}）",
                        outcome.code,
                    )
                }
            }
        }
    }

    /**
     * 按快照里的 `download_url` 把结果图取回来。
     *
     * 服务端每次查询都用当前请求的 Host 重算下载地址，换网络后地址会跟着变，
     * 所以必须用**这次查到的**快照里的 url，不能用提交时记下来的。单张失败会被跳过。
     */
    open suspend fun downloadJobImages(
        config: ImageGenerationConfig,
        snapshot: RemoteJobSnapshot,
    ): List<ByteArray> {
        val baseUrl = resolveBaseUrl(config.baseUrl)
        if (baseUrl.isEmpty()) return emptyList()
        return snapshot.images
            .mapNotNull { imageUrl(it, baseUrl) }
            .mapNotNull { downloadWithRetry(it, config, baseUrl) }
    }

    // region 照片库（全身照片）

    /**
     * 上传一张照片进服务端照片库，返回服务端分配的 `photo_id`。
     *
     * [dataUrl] 必须是完整的 `data:image/...;base64,...`（服务端不接受裸 base64）。
     * 这是几 MB 的请求体，所以用独立的 60 秒超时；任何失败都折成 `Result.failure`，
     * 绝不把异常抛给 UI —— 上传失败只该让用户看到一句人话，不该让设置页崩掉。
     */
    open suspend fun uploadPhoto(
        config: ImageGenerationConfig,
        dataUrl: String,
        filename: String? = null,
    ): kotlin.Result<RemotePhotoInfo> {
        val baseUrl = resolveBaseUrl(config.baseUrl)
        if (baseUrl.isEmpty()) {
            return kotlin.Result.failure(IllegalStateException(IMAGE_SERVICE_NOT_CONFIGURED))
        }
        val payload = dataUrl.trim()
        if (payload.isEmpty()) {
            return kotlin.Result.failure(IllegalArgumentException("照片内容为空，请重新选择图片"))
        }
        return try {
            val body = RequestJson
                .encodeToString(RemotePhotoUploadRequest(image = payload, filename = filename))
                .toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(baseUrl + PHOTOS_PATH)
                .post(body)
                .applyAuth(config)
                .build()
            when (val outcome = performCall(uploadClient, request, UPLOAD_TIMEOUT_MS)) {
                is HttpOutcome.Transient -> kotlin.Result.failure(IllegalStateException(outcome.message))
                is HttpOutcome.Response -> {
                    val parsed = decodeOrNull<RemotePhotoUploadResponse>(outcome.text)
                    val photo = parsed?.photo
                    if (outcome.isSuccessful && parsed?.ok == true && photo != null) {
                        kotlin.Result.success(photo)
                    } else {
                        val reason = parsed?.error?.takeIf { it.isNotBlank() }
                            ?: "上传照片失败（HTTP ${outcome.code}）"
                        // 413 是照片超过服务端 16MB 上限，单看状态码没人看得懂
                        val hint = httpHint(outcome.code)
                            ?: if (outcome.code == 413) "照片太大了，服务端上限 16MB" else null
                        // 非 JSON 的错误响应（网关 HTML）也留一点线索，否则只剩一个状态码
                        val snippet = if (parsed == null) bodySnippet(outcome.text) else null
                        kotlin.Result.failure(
                            IllegalStateException(
                                listOfNotNull(reason, hint, snippet).joinToString("；")
                            )
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            kotlin.Result.failure(IllegalStateException(describe(t)))
        }
    }

    /**
     * 删除服务端照片库里的照片。
     *
     * best-effort：换照片/移除照片时顺手清掉旧照片，失败只返回 false——一次清理请求
     * 不该拦住本地流程（旧照片留在服务端只是占点磁盘）。404 视为「已经不在了」，算成功。
     */
    open suspend fun deletePhoto(config: ImageGenerationConfig, photoId: String): Boolean {
        val baseUrl = resolveBaseUrl(config.baseUrl)
        if (baseUrl.isEmpty()) return false
        val id = photoId.trim()
        if (id.isEmpty()) return false
        return try {
            val encoded = URLEncoder.encode(id, Charsets.UTF_8.name()).replace("+", "%20")
            val request = Request.Builder()
                .url("$baseUrl$PHOTOS_PATH/$encoded")
                .delete()
                .applyAuth(config)
                .build()
            when (val outcome = performCall(shortCallClient, request, CALL_TIMEOUT_MS)) {
                is HttpOutcome.Transient -> false
                is HttpOutcome.Response -> if (outcome.code == 404) {
                    true
                } else {
                    val parsed = decodeOrNull<RemotePhotoDeleteResponse>(outcome.text)
                    outcome.isSuccessful && (parsed?.ok == true || parsed?.deleted == true)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            false
        }
    }

    // endregion

    // region 状态机

    private suspend fun runJob(
        config: ImageGenerationConfig,
        baseUrl: String,
        asyncRequest: RemoteGenerateRequest,
        clientJobId: String,
        deadline: Long,
    ): Result {
        // 旧接口的请求体必须保持原样：老服务端不认识 client_job_id
        val legacyRequest = asyncRequest.copy(clientJobId = null)
        val timeoutSeconds = asyncRequest.timeout ?: DEFAULT_REQUEST_TIMEOUT_SECONDS
        var needSubmit = true
        var resubmits = 0
        var backoffAttempt = 0
        var lastProblem: String? = null

        while (true) {
            if (System.currentTimeMillis() >= deadline) {
                return deadlineFailure(clientJobId, timeoutSeconds, lastProblem)
            }
            if (needSubmit) {
                when (val step = submitOnce(config, baseUrl, asyncRequest, legacyRequest, clientJobId)) {
                    is SubmitStep.Submitted -> {
                        needSubmit = false
                        backoffAttempt = 0
                        lastProblem = null
                    }

                    is SubmitStep.Retry -> {
                        lastProblem = step.message
                        waitForNextAttempt(deadline, retryBackoffMs(backoffAttempt++))
                    }

                    is SubmitStep.Abort -> return step.result
                }
                continue
            }
            // 轮询一律用 client_job_id：这个 id 是我们自己生成的，POST 的响应丢了也照样查得到
            when (val step = pollOnce(config, baseUrl, clientJobId, resubmits)) {
                is PollStep.Waiting -> {
                    backoffAttempt = 0
                    waitForNextAttempt(deadline, POLL_INTERVAL_MS)
                }

                is PollStep.Resubmit -> {
                    resubmits++
                    needSubmit = true
                }

                is PollStep.Retry -> {
                    lastProblem = step.message
                    waitForNextAttempt(deadline, retryBackoffMs(backoffAttempt++))
                }

                is PollStep.Done -> return step.result
            }
        }
    }

    /** 提交一次；404/405/501 说明服务端还是旧的，直接回落到同步接口。 */
    private suspend fun submitOnce(
        config: ImageGenerationConfig,
        baseUrl: String,
        asyncRequest: RemoteGenerateRequest,
        legacyRequest: RemoteGenerateRequest,
        clientJobId: String,
    ): SubmitStep {
        val body = RequestJson.encodeToString(asyncRequest).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(baseUrl + ASYNC_GENERATE_PATH)
            .post(body)
            .applyAuth(config)
            .build()
        return when (val outcome = performCall(shortCallClient, request, CALL_TIMEOUT_MS)) {
            is HttpOutcome.Transient -> SubmitStep.Retry(outcome.message)
            is HttpOutcome.Response -> when {
                outcome.code in LEGACY_FALLBACK_CODES ->
                    SubmitStep.Abort(legacyGenerate(config, baseUrl, legacyRequest, clientJobId))

                outcome.code == 401 || outcome.code == 403 -> SubmitStep.Abort(
                    Result.Failure(
                        message = "服务端鉴权失败（HTTP ${outcome.code}），请检查设置里的访问令牌",
                        httpCode = outcome.code,
                        detail = bodySnippet(outcome.text),
                        jobId = clientJobId,
                    )
                )

                // 400/422 这类参数错误重试一万次也是同样的结果，直接告诉模型
                !outcome.isSuccessful -> SubmitStep.Abort(
                    failure(outcome.code, decodeOrNull<RemoteGenerateResponse>(outcome.text), clientJobId)
                )

                // 2xx 但 body 不是快照：服务端坏了，重试没意义，但 client_job_id 还在，
                // 失败信息里带上它就还能事后查
                else -> {
                    val snapshot = decodeOrNull<RemoteJobSnapshot>(outcome.text)
                    if (snapshot?.ok != true) {
                        SubmitStep.Abort(
                            Result.Failure(
                                message = "提交出图任务失败：服务端响应无法解析",
                                httpCode = outcome.code,
                                detail = bodySnippet(outcome.text),
                                jobId = clientJobId,
                            )
                        )
                    } else {
                        SubmitStep.Submitted
                    }
                }
            }
        }
    }

    /** 轮询一次；404 时按剩余重提交次数决定「重新提交」还是「放弃」。 */
    private suspend fun pollOnce(
        config: ImageGenerationConfig,
        baseUrl: String,
        jobKey: String,
        resubmits: Int,
    ): PollStep {
        val request = Request.Builder()
            .url(baseUrl + jobPath(jobKey))
            .get()
            .applyAuth(config)
            .build()
        return when (val outcome = performCall(shortCallClient, request, CALL_TIMEOUT_MS)) {
            is HttpOutcome.Transient -> PollStep.Retry(outcome.message)
            is HttpOutcome.Response -> when {
                // 服务端重启/换实例会把任务表丢掉：用同一个 client_job_id 重提交是幂等的，
                // 重提交不了（次数用尽）就诚实地告诉模型，别假装还在等
                outcome.code == 404 -> {
                    val parsed = decodeOrNull<RemoteGenerateResponse>(outcome.text)
                    if (resubmits >= MAX_RESUBMITS) {
                        PollStep.Done(
                            Result.Failure(
                                message = "任务查询失败：服务端多次找不到该任务（HTTP 404），已放弃等待；" +
                                    "可稍后用该 job_id 重新查询",
                                httpCode = outcome.code,
                                detail = parsed?.error,
                                jobId = jobKey,
                            )
                        )
                    } else {
                        PollStep.Resubmit
                    }
                }

                outcome.code == 401 || outcome.code == 403 -> PollStep.Done(
                    Result.Failure(
                        message = "服务端鉴权失败（HTTP ${outcome.code}），请检查设置里的访问令牌",
                        httpCode = outcome.code,
                        detail = bodySnippet(outcome.text),
                        jobId = jobKey,
                    )
                )

                // 网关/限流/后端重启期间看到 5xx 很正常，这不是任务本身的结论：继续等
                isTransientStatusCode(outcome.code) ->
                    PollStep.Retry("服务端暂时不可用（HTTP ${outcome.code}）")

                !outcome.isSuccessful -> PollStep.Done(
                    failure(outcome.code, decodeOrNull<RemoteGenerateResponse>(outcome.text), jobKey)
                )

                else -> {
                    val snapshot = decodeOrNull<RemoteJobSnapshot>(outcome.text)
                    if (snapshot?.ok != true) {
                        // 200 但 body 不像快照：可能是网关插了一页 HTML，下一轮再看
                        PollStep.Retry("任务状态响应无法解析（HTTP ${outcome.code}）")
                    } else {
                        when (jobStatusKind(snapshot.status)) {
                            JobStatusKind.SUCCEEDED ->
                                PollStep.Done(finishedResult(config, baseUrl, snapshot, jobKey))

                            JobStatusKind.FAILED -> PollStep.Done(failedResult(snapshot, jobKey))

                            JobStatusKind.INTERRUPTED -> PollStep.Done(
                                Result.Failure(
                                    message = "服务端重启导致任务中断，请重新生成",
                                    httpCode = snapshot.httpStatus,
                                    detail = snapshot.statusUrl,
                                    jobId = snapshot.jobId ?: jobKey,
                                )
                            )

                            JobStatusKind.WAITING -> PollStep.Waiting
                        }
                    }
                }
            }
        }
    }

    /** 任务成功：把图下载下来；一张都拿不到才算失败（此时 job_id 仍然给出去）。 */
    private suspend fun finishedResult(
        config: ImageGenerationConfig,
        baseUrl: String,
        snapshot: RemoteJobSnapshot,
        jobKey: String,
    ): Result {
        val downloaded = downloadJobImages(config, snapshot)
        if (downloaded.isEmpty()) {
            return Result.Failure(
                message = "任务已完成，但没有取到任何图片（可能是下载地址已过期，可用 job_id 再查一次）",
                httpCode = snapshot.httpStatus,
                detail = snapshot.statusUrl,
                jobId = snapshot.jobId ?: jobKey,
            )
        }
        return Result.Success(
            images = downloaded,
            fileNames = snapshot.images.mapNotNull { it.filename },
            elapsedSec = snapshot.progress?.elapsedSec,
            promptId = snapshot.promptId,
            metaJson = snapshot.meta?.let { JsonInstant.encodeToString(JsonObject.serializer(), it) },
            jobId = snapshot.jobId ?: jobKey,
        )
    }

    private fun failedResult(snapshot: RemoteJobSnapshot, jobKey: String): Result.Failure {
        val reason = snapshot.error?.takeIf { it.isNotBlank() } ?: "图片生成任务失败"
        val hint = snapshot.httpStatus?.let(::httpHint)
        return Result.Failure(
            message = if (hint == null) reason else "$reason；$hint",
            httpCode = snapshot.httpStatus,
            detail = snapshot.detail?.let(::detailText),
            jobId = snapshot.jobId ?: jobKey,
        )
    }

    private fun deadlineFailure(jobKey: String, timeoutSeconds: Int, lastProblem: String?): Result.Failure =
        Result.Failure(
            message = "已等待 $timeoutSeconds 秒仍未完成；服务端任务可能仍在继续，可用 job_id $jobKey 稍后查询",
            httpCode = null,
            detail = lastProblem,
            jobId = jobKey,
        )

    /** 等到下一轮，但绝不睡过整体截止时间（否则会白白多等一轮才报超时）。 */
    private suspend fun waitForNextAttempt(deadline: Long, delayMs: Long) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining > 0) delay(minOf(delayMs, remaining))
    }

    // endregion

    // region 旧同步接口（服务端未升级时的降级路径）

    /**
     * 老服务端的一次性同步 POST：跑完才返回，失败即失败。
     *
     * 降级前会剥掉 client_job_id，保证发给老服务端的请求体与改造前逐字节一致。
     */
    private suspend fun legacyGenerate(
        config: ImageGenerationConfig,
        baseUrl: String,
        request: RemoteGenerateRequest,
        clientJobId: String,
    ): Result {
        return try {
            val body = RequestJson.encodeToString(request).toRequestBody(JSON_MEDIA_TYPE)
            val httpRequest = Request.Builder()
                .url(baseUrl + GENERATE_PATH)
                .post(body)
                .applyAuth(config)
                .build()
            // 同步出图要 3~6 分钟，用原始 client（readTimeout 由构造方决定），不套 20 秒短超时
            okHttpClient.newCall(httpRequest).await().use { response ->
                val text = response.body.string()
                val parsed = decodeOrNull<RemoteGenerateResponse>(text)
                if (!response.isSuccessful || parsed?.ok != true) {
                    failure(response.code, parsed, clientJobId)
                } else {
                    val downloaded = parsed.images
                        .mapNotNull { imageUrl(it, baseUrl) }
                        .mapNotNull { downloadWithRetry(it, config, baseUrl) }
                    if (downloaded.isEmpty()) {
                        Result.Failure(
                            message = "图片生成服务返回成功，但没有取到任何图片",
                            httpCode = response.code,
                            detail = null,
                            jobId = parsed.promptId,
                        )
                    } else {
                        Result.Success(
                            images = downloaded,
                            fileNames = parsed.images.mapNotNull { it.filename },
                            elapsedSec = parsed.elapsedSec,
                            promptId = parsed.promptId,
                            metaJson = parsed.meta?.let {
                                JsonInstant.encodeToString(JsonObject.serializer(), it)
                            },
                            // 老服务端没有 job_id，用 prompt_id 顶替，上层永远有 id 可展示
                            jobId = parsed.promptId,
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.Failure(describe(t), null, null, clientJobId)
        }
    }

    // endregion

    /** 非 2xx 或 `ok=false`：把服务端的 error 和状态码翻译成一句能直接读懂的话。 */
    private fun failure(httpCode: Int, parsed: RemoteGenerateResponse?, jobId: String?): Result.Failure {
        val reason = parsed?.error?.takeIf { it.isNotBlank() } ?: "图片生成失败（HTTP $httpCode）"
        val hint = httpHint(httpCode)
        return Result.Failure(
            message = if (hint == null) reason else "$reason；$hint",
            httpCode = httpCode,
            detail = parsed?.detail?.let(::detailText),
            jobId = jobId,
        )
    }

    /** 网关/限流这类状态码说明「这一次没问到」，不是任务本身的结论。 */
    private fun isTransientStatusCode(code: Int): Boolean =
        code == 408 || code == 425 || code == 429 || code in 500..599

    /** 服务端常见的几个状态码各自的含义，直接告诉用户该等还是该去查环境。 */
    private fun httpHint(httpCode: Int): String? = when (httpCode) {
        404 -> "服务端没有找到该接口（可能是旧版本服务端）"
        409 -> "服务正忙，稍后重试"
        502 -> "显存编排失败，请检查服务端 GPU 状态"
        503 -> "连不上 ComfyUI，请确认服务端工作流已启动"
        504 -> "等待出图超时，冷启动时可以再试一次"
        else -> null
    }

    // region 单次 HTTP 调用

    /**
     * 发一次请求并读完整 body，失败一律归类成 [HttpOutcome.Transient]。
     *
     * 只有 [CancellationException] 往上抛：用户取消/上层超时不能被当成「网络抖动」吞掉。
     */
    private suspend fun performCall(
        client: OkHttpClient,
        request: Request,
        timeoutMs: Long,
    ): HttpOutcome {
        val response = try {
            withTimeoutOrNull(timeoutMs) { client.newCall(request).await() }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return HttpOutcome.Transient(describe(t))
        }
        if (response == null) {
            return HttpOutcome.Transient("请求超过 ${timeoutMs / 1000} 秒没有响应（网络可能已断开）")
        }
        return response.use { resp ->
            try {
                HttpOutcome.Response(resp.code, resp.body.bytes())
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                HttpOutcome.Transient(describe(t))
            }
        }
    }

    /** 下载结果图；单张失败重试 3 次，仍失败返回 null（全部失败由调用方判定）。 */
    private suspend fun downloadWithRetry(
        url: String,
        config: ImageGenerationConfig,
        baseUrl: String,
    ): ByteArray? {
        val absolute = absoluteUrl(baseUrl, url)
        var attempt = 0
        while (true) {
            val request = try {
                Request.Builder().url(absolute).get().applyAuth(config).build()
            } catch (e: IllegalArgumentException) {
                // 地址本身非法（模型/服务端给了脏数据）：重试也不会变好
                return null
            }
            val outcome = performCall(downloadClient, request, DOWNLOAD_TIMEOUT_MS)
            if (outcome is HttpOutcome.Response && outcome.isSuccessful) {
                return outcome.body
            }
            if (attempt >= DOWNLOAD_RETRY_DELAYS_MS.size) return null
            delay(DOWNLOAD_RETRY_DELAYS_MS[attempt])
            attempt++
        }
    }

    /** 结果图的下载地址：优先服务端给的绝对地址，退化时回退到 `/files/<filename>`。 */
    private fun imageUrl(item: RemoteImageItem, baseUrl: String): String? =
        item.downloadUrl?.takeIf { it.isNotBlank() }
            ?: item.filename?.takeIf { it.isNotBlank() }?.let { "$baseUrl/files/$it" }

    private fun absoluteUrl(baseUrl: String, url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "$baseUrl/${url.trimStart('/')}"
        }

    private fun Request.Builder.applyAuth(config: ImageGenerationConfig): Request.Builder {
        val token = config.authToken.trim()
        return if (token.isEmpty()) this else header(AUTH_HEADER, token)
    }

    // endregion

    /** 把用户填的地址归一化成「根地址」，顺便容忍把完整接口地址贴进设置里的情况。 */
    private fun resolveBaseUrl(configured: String): String {
        val trimmed = configured.trim().trimEnd('/')
        return when {
            trimmed.endsWith(ASYNC_GENERATE_PATH) ->
                trimmed.removeSuffix(ASYNC_GENERATE_PATH).trimEnd('/')

            trimmed.endsWith(GENERATE_PATH) -> trimmed.removeSuffix(GENERATE_PATH).trimEnd('/')
            else -> trimmed
        }
    }

    private fun jobPath(key: String): String =
        "$JOBS_PATH/" + URLEncoder.encode(key, Charsets.UTF_8.name()).replace("+", "%20")

    private fun detailText(detail: JsonElement): String? =
        runCatching { JsonInstant.encodeToString(JsonElement.serializer(), detail) }
            .getOrNull()
            ?.take(MAX_DETAIL_CHARS)

    /** 非 JSON 的错误响应（网关 HTML、纯文本）也要给模型留一点线索。 */
    private fun bodySnippet(text: String): String? =
        text.trim().takeIf { it.isNotEmpty() }?.take(MAX_DETAIL_CHARS)

    private fun describe(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t::class.simpleName ?: "unknown"

    private inline fun <reified T> decodeOrNull(text: String): T? =
        runCatching { JsonInstant.decodeFromString<T>(text) }.getOrNull()
}
