package me.rerere.rikkahub.data.ai.image

import android.util.Base64
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.PromptWriter
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_IMAGE_NEGATIVE
import me.rerere.rikkahub.data.datastore.ImageGenerationConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.ai.image.Result as ImageGenerationResult
import java.io.File
import kotlin.uuid.Uuid

// workflow 的 TextEncodeQwenImageEditPlus 只有 image1/image2 两个图片槽位，服务端最多吃 2 张参考图
private const val MAX_REFERENCE_IMAGES = 2

// 客户端超时比服务端自己的 timeout 多留一点余量，让服务端先把它自己的超时讲清楚
private const val TIMEOUT_GRACE_SECONDS = 120L

// 不传 job_id 时列多少个最近任务：够用户/模型认出刚才那一次就够了
private const val RECENT_JOB_LIMIT = 10

private val SIZE_PATTERN = Regex("""^\d+x\d+$""")

/** 把生成好的图片字节存成聊天附件，返回可直接放进 UIMessagePart.Image 的 url。 */
fun interface ImageFileSaver {
    fun save(bytes: List<ByteArray>, displayName: String, mimeType: String): List<String>
}

/**
 * 出图编排：改写 prompt → 归一化参考图 → 调远程服务 → 图片落盘。
 *
 * 失败一律返回一条文本 part（内含 JSON），不抛异常：工具调用失败要让模型看见原因并自己决定下一步。
 */
class ImageGenerationService(
    private val client: RemoteImageGenerationClient,
    private val promptWriter: PromptWriter,
    private val fileSaver: ImageFileSaver,
) {
    data class Request(
        val prompt: String,
        val negative: String? = null,
        val size: String? = null,
        val steps: Int? = null,
        val seed: Long? = null,
        val batchSize: Int? = null,
        /** 参考图，`file://` 路径或 `data:` URL。 */
        val referenceImages: List<String> = emptyList(),
        val noReference: Boolean = false,
        /**
         * 助手绑定的服务端全身照片 id；非空时它固定占 image1，用户参考图只剩 1 个槽位。
         */
        val fullBodyPhotoId: String? = null,
    )

    suspend fun generate(settings: Settings, conversationId: Uuid?, request: Request): List<UIMessagePart> {
        val config = settings.imageGenerationConfig
        // 没配服务地址就直接说清楚，别先花一次模型调用去改写 prompt
        if (config.baseUrl.isBlank()) {
            return listOf(failureText(IMAGE_SERVICE_NOT_CONFIGURED))
        }
        val fullBodyPhotoId = request.fullBodyPhotoId?.trim()?.takeIf { it.isNotEmpty() }
        // 全身照片固定占 image1，因此用户自己的参考图只剩 1 个槽位；没有照片时用户可用满 2 个
        val userImageLimit = if (fullBodyPhotoId != null) MAX_REFERENCE_IMAGES - 1 else MAX_REFERENCE_IMAGES
        val references = withContext(Dispatchers.IO) {
            request.referenceImages.mapNotNull(::normalizeReferenceImage).take(userImageLimit)
        }
        // 有固定全身照片时永远不是纯文生图：哪怕这次没给参考图，服务端也有一张图要喂给 image1
        val noReference = fullBodyPhotoId == null && (request.noReference || references.isEmpty())
        val size = request.size?.trim()?.takeIf { SIZE_PATTERN.matches(it) } ?: config.defaultSize
        val steps = request.steps ?: config.steps

        val draft = if (config.enablePromptAgent) {
            promptWriter.writePrompt(settings, conversationId, request.prompt, size)
        } else {
            null
        }
        val prompt = draft?.prompt ?: request.prompt
        val negative = request.negative?.takeIf { it.isNotBlank() } ?: draft?.negative ?: DEFAULT_IMAGE_NEGATIVE

        val remoteRequest = RemoteGenerateRequest(
            prompt = prompt,
            negative = negative,
            size = size,
            steps = steps,
            seed = request.seed,
            batchSize = request.batchSize,
            noReference = if (noReference) true else null,
            images = references.takeIf { it.isNotEmpty() },
            timeout = config.timeoutSeconds,
            fullBodyPhotoId = fullBodyPhotoId,
        )

        val timeoutSeconds = config.timeoutSeconds + TIMEOUT_GRACE_SECONDS
        val result = try {
            withTimeoutOrNull(timeoutSeconds * 1000L) {
                client.generate(config, remoteRequest)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            ImageGenerationResult.Failure(t.message ?: t::class.simpleName ?: "unknown", null, null)
        }

        if (result == null) {
            return listOf(failureText(message = "图片生成超时（超过 $timeoutSeconds 秒没有返回），请稍后重试"))
        }

        return when (result) {
            is ImageGenerationResult.Failure -> listOf(
                failureText(result.message, result.httpCode, result.detail, result.jobId)
            )

            is ImageGenerationResult.Success -> {
                val urls = withContext(Dispatchers.IO) {
                    fileSaver.save(
                        result.images,
                        "generated_${System.currentTimeMillis()}.png",
                        "image/png",
                    )
                }
                val summary = buildJsonObject {
                    put("ok", true)
                    put("prompt", prompt)
                    put("negative", negative)
                    put("size", size)
                    put("steps", steps)
                    put("seed", request.seed)
                    put("prompt_id", result.promptId)
                    // 任务 id：断网/超时后模型靠它把产物查回来，必须出现在成功摘要里
                    put("job_id", result.jobId)
                    put("elapsed_sec", result.elapsedSec)
                    // 是否用上了独立 agent 的改写结果，出问题时用来定位是改写还是出图的问题
                    put("prompt_rewritten", draft != null)
                    put("images", urls.size)
                }
                urls.map { UIMessagePart.Image(url = it) } + UIMessagePart.Text(summary.toString())
            }
        }
    }

    /**
     * 查询一次出图任务现在的状态（`get_image_generation_job` 的后端）。
     *
     * 这是「断网 / 超时 / App 重启之后」唯一的找回入口：给 [jobId] 直接取任务详情，
     * 成功的话顺手把图片落盘成图片 part；不给就列出最近的任务让模型自己挑。
     * 任何协议层失败都只返回文本，绝不抛异常——工具调用失败必须让模型看见原因。
     */
    suspend fun queryJob(
        settings: Settings,
        jobId: String?,
        includeImages: Boolean = true,
    ): List<UIMessagePart> {
        val config = settings.imageGenerationConfig
        if (config.baseUrl.isBlank()) {
            return listOf(failureText(IMAGE_SERVICE_NOT_CONFIGURED))
        }
        val key = jobId?.trim().orEmpty()
        if (key.isEmpty()) {
            return listOf(UIMessagePart.Text(recentJobsText(config)))
        }
        return when (val lookup = client.fetchJob(config, key)) {
            is JobLookup.NotFound -> listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", false)
                        put("job_id", key)
                        put("error", "任务不存在：$key")
                        put(
                            "hint",
                            "服务端任务表里没有这个 id（可能已重启并清空）。不传 job_id 可以列出最近的任务，再挑一个查。",
                        )
                    }.toString()
                )
            )

            is JobLookup.Error -> listOf(failureText(lookup.message, lookup.httpCode, jobId = key))
            is JobLookup.Found -> jobSnapshotParts(config, lookup.snapshot, includeImages)
        }
    }

    /** 不知道 job_id 时的兜底：把最近任务读成一段人能看懂的列表。 */
    private suspend fun recentJobsText(config: ImageGenerationConfig): String =
        when (val lookup = client.listJobs(config, limit = RECENT_JOB_LIMIT)) {
            is JobListLookup.Error -> buildJsonObject {
                put("ok", false)
                put("error", lookup.message)
                put("http_code", lookup.httpCode)
                put("hint", "服务端暂时查不了任务列表，稍后重试；如果还记得 job_id 可以直接用它查询")
            }.toString()

            is JobListLookup.Ok -> if (lookup.jobs.isEmpty()) {
                "最近没有出图任务（服务端任务表为空）。"
            } else {
                buildString {
                    appendLine("最近的 ${lookup.jobs.size} 个出图任务（新的在前）：")
                    lookup.jobs.forEach { appendLine(describeJob(it)) }
                    append("需要图片就用上面的 job_id 再查一次（get_image_generation_job）。")
                }
            }
        }

    /** 任务详情：成功且 [includeImages] 时把图落盘成 part，其它状态只给一段 JSON 摘要。 */
    private suspend fun jobSnapshotParts(
        config: ImageGenerationConfig,
        snapshot: RemoteJobSnapshot,
        includeImages: Boolean,
    ): List<UIMessagePart> {
        val succeeded = jobStatusKind(snapshot.status) == JobStatusKind.SUCCEEDED
        val bytes = if (succeeded && includeImages) {
            withContext(Dispatchers.IO) { client.downloadJobImages(config, snapshot) }
        } else {
            emptyList()
        }
        val urls = if (bytes.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                fileSaver.save(bytes, "generated_${System.currentTimeMillis()}.png", "image/png")
            }
        }
        val summary = buildJsonObject {
            put("ok", succeeded)
            put("job_id", snapshot.jobId)
            put("client_job_id", snapshot.clientJobId)
            put("status", snapshot.status)
            put("stage", snapshot.stage)
            put("prompt_id", snapshot.promptId)
            put("created_at", snapshot.createdAt)
            put("elapsed_sec", snapshot.progress?.elapsedSec)
            put("timeout_sec", snapshot.progress?.timeoutSec)
            put("error", snapshot.error)
            put("http_code", snapshot.httpStatus)
            put("status_url", snapshot.statusUrl)
            snapshot.detail?.let { put("detail", it) }
            put("images", urls.size)
            put("expected_images", snapshot.images.size)
            if (snapshot.images.isNotEmpty()) {
                // 下载地址是服务端按当前 Host 算的：模型/用户拿着它能直接取图
                put(
                    "download_urls",
                    JsonArray(
                        snapshot.images
                            .mapNotNull { it.downloadUrl ?: it.filename }
                            .map { JsonPrimitive(it) }
                    ),
                )
            }
            if (succeeded && includeImages && urls.isEmpty()) {
                put("hint", "任务已完成，但图片没下载下来；可以再查一次，或用 download_urls 里的地址自行下载")
            }
        }
        return urls.map { UIMessagePart.Image(url = it) } + UIMessagePart.Text(summary.toString())
    }

    /** 列表里的一行：job_id / status / stage / 创建时间 / 图片数 / 下载地址。 */
    private fun describeJob(snapshot: RemoteJobSnapshot): String = buildString {
        append("- job_id=").append(snapshot.jobId ?: "?")
        append(" status=").append(snapshot.status ?: "?")
        append(" stage=").append(snapshot.stage ?: "?")
        append(" created_at=").append(snapshot.createdAt ?: "?")
        append(" images=").append(snapshot.images.size)
        val urls = snapshot.images.mapNotNull { it.downloadUrl ?: it.filename }
        if (urls.isNotEmpty()) {
            append(" download_url=").append(urls.joinToString(", "))
        }
        snapshot.error?.takeIf { it.isNotBlank() }?.let { append(" error=").append(it) }
    }

    private fun failureText(
        message: String,
        httpCode: Int? = null,
        detail: String? = null,
        jobId: String? = null,
    ): UIMessagePart.Text =
        UIMessagePart.Text(
            buildJsonObject {
                put("ok", false)
                put("error", message)
                put("http_code", httpCode)
                put("detail", detail)
                // job_id 是断网后能找回产物的唯一线索，连提示一起给模型
                put("job_id", jobId)
                put("hint", JOB_RECOVERY_HINT)
            }.toString()
        )

    /**
     * 参考图统一成服务端认的 `data:` URL：`file://` 路径读文件转 base64，已有的 `data:` 原样保留。
     *
     * 读不到的文件和非本地路径直接跳过，不因为一张坏图让整次出图失败。
     */
    private fun normalizeReferenceImage(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("data:")) return text
        val file = when {
            text.startsWith("file:") -> text.toUri().path?.let(::File)
            text.startsWith("/") -> File(text)
            else -> null
        } ?: return null
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val mimeType = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            else -> "image/png"
        }
        return "data:$mimeType;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
