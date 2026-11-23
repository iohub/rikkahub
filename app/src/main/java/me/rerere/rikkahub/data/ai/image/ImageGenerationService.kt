package me.rerere.rikkahub.data.ai.image

import android.util.Base64
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.PromptWriter
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_IMAGE_NEGATIVE
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.ai.image.Result as ImageGenerationResult
import java.io.File
import kotlin.uuid.Uuid

// 服务端一次最多吃 3 张参考图
private const val MAX_REFERENCE_IMAGES = 3

// 客户端超时比服务端自己的 timeout 多留一点余量，让服务端先把它自己的超时讲清楚
private const val TIMEOUT_GRACE_SECONDS = 120L

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
    )

    suspend fun generate(settings: Settings, conversationId: Uuid?, request: Request): List<UIMessagePart> {
        val config = settings.imageGenerationConfig
        // 没配服务地址就直接说清楚，别先花一次模型调用去改写 prompt
        if (config.baseUrl.isBlank()) {
            return listOf(failureText(IMAGE_SERVICE_NOT_CONFIGURED))
        }
        val references = withContext(Dispatchers.IO) {
            request.referenceImages.mapNotNull(::normalizeReferenceImage).take(MAX_REFERENCE_IMAGES)
        }
        val noReference = request.noReference || references.isEmpty()
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
            is ImageGenerationResult.Failure -> listOf(failureText(result.message, result.httpCode, result.detail))
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
                    put("elapsed_sec", result.elapsedSec)
                    // 是否用上了独立 agent 的改写结果，出问题时用来定位是改写还是出图的问题
                    put("prompt_rewritten", draft != null)
                    put("images", urls.size)
                }
                urls.map { UIMessagePart.Image(url = it) } + UIMessagePart.Text(summary.toString())
            }
        }
    }

    private fun failureText(message: String, httpCode: Int? = null, detail: String? = null): UIMessagePart.Text =
        UIMessagePart.Text(
            buildJsonObject {
                put("ok", false)
                put("error", message)
                put("http_code", httpCode)
                put("detail", detail)
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
