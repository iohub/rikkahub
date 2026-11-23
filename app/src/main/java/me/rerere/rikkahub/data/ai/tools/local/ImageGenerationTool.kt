package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.image.ImageGenerationService
import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

// workflow 的 TextEncodeQwenImageEditPlus 只有 image1/image2 两个图片槽位，服务端一次最多吃 2 张参考图
private const val MAX_REFERENCE_IMAGES = 2

internal fun buildImageGenerationTool(
    service: ImageGenerationService,
    settingsProvider: () -> Settings,
    conversationId: Uuid?,
    // 助手绑定的服务端全身照片 id：非空时它固定占 image1，用户参考图只剩 1 个槽位
    fullBodyPhotoIdProvider: () -> String? = { null },
): Tool = Tool(
    name = "generate_image",
    description = """
        Generate an image through the user's own self-hosted remote image service (ComfyUI Qwen-Image-Edit behind
        a small API). A separate model rewrites your description into a professional diffusion prompt before
        rendering, so pass the user's request faithfully instead of pre-optimizing it yourself.
        Use this whenever the user asks to draw, design, render or edit a picture, avatar, poster, illustration,
        sticker, wallpaper or similar.
        The prompt must cover the subject, its quantity, colors, composition, any text that has to appear inside
        the image (verbatim, in its original language), and the desired style.
        For image-to-image or editing, pass the existing pictures as reference_images using the file:// paths listed
        in <available_reference_images>; at most $MAX_REFERENCE_IMAGES pictures, newest last, and only 1 when the
        user has a fixed full-body photo attached. Omit them for pure text-to-image.
        One run takes 20 seconds to 3 minutes, and noticeably longer when the service is cold starting.
    """.trimIndent().replace("\n", " "),
    parameters = {
        // 有固定全身照片时它自己就占掉 image1，模型只能用剩下的槽位
        val imageSlots = if (fullBodyPhotoIdProvider() != null) MAX_REFERENCE_IMAGES - 1 else MAX_REFERENCE_IMAGES
        InputSchema.Obj(
            properties = buildJsonObject {
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "What to draw or how to edit the reference images. Include subject, quantity, colors, " +
                            "composition, text that must appear in the image, and style."
                    )
                })
                put("negative", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional things to avoid. Leave empty to use the service default.")
                })
                put("size", buildJsonObject {
                    put("type", "string")
                    put("description", "Output resolution as WxH, e.g. 1024x1024 or 1024x1536. Defaults to the configured size.")
                })
                put("steps", buildJsonObject {
                    put("type", "integer")
                    put("description", "Sampling steps. Leave empty to use the configured value.")
                })
                put("seed", buildJsonObject {
                    put("type", "integer")
                    put("description", "Random seed for reproducible results. Omit for a random one.")
                })
                put("batch_size", buildJsonObject {
                    put("type", "integer")
                    put("description", "How many images to generate in this run. Usually 1.")
                })
                put("reference_images", buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "file:// paths of images from this conversation to edit or use as reference, at most " +
                            "$imageSlots. Omit for pure text-to-image."
                    )
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("no_reference", buildJsonObject {
                    put("type", "boolean")
                    put(
                        "description",
                        "Set true to ignore the reference images even if some were passed. " +
                            "Automatically true when no usable reference image is left."
                    )
                })
            },
            required = listOf("prompt"),
        )
    },
    systemPrompt = { _, messages ->
        buildReferenceImagesPrompt(messages, hasFullBodyPhoto = fullBodyPhotoIdProvider() != null)
    },
    needsApproval = { false },
    execute = { args ->
        try {
            parseImageGenerationRequest(args)?.let { request ->
                service.generate(
                    settings = settingsProvider(),
                    conversationId = conversationId,
                    // 全身照片 id 每次执行时现取：助手页刚换过照片，这里立刻就要用新的
                    request = request.copy(
                        fullBodyPhotoId = fullBodyPhotoIdProvider()?.trim()?.takeIf { it.isNotEmpty() },
                    ),
                )
            } ?: listOf(UIMessagePart.Text(errorJson("prompt 不能为空")))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            listOf(UIMessagePart.Text(errorJson(t.message ?: t::class.simpleName ?: "unknown")))
        }
    },
)

/**
 * `get_image_generation_job`：出图失败/中断后按 job_id 把任务找回来的工具。
 *
 * 和 `generate_image` 共用同一个 `LocalToolOption.ImageGeneration` 开关：只要用户开了出图能力，
 * 就该同时拥有「查上一次任务」的能力，否则断网后模型只能重画一遍。
 */
internal fun buildImageJobQueryTool(
    service: ImageGenerationService,
    settingsProvider: () -> Settings,
): Tool = Tool(
    name = "get_image_generation_job",
    description = """
        Look up the current state of a job on the user's self-hosted image service and, when the job has already
        succeeded, return its finished images without regenerating anything.
        Use it whenever generate_image failed, was interrupted, timed out, or reported a lost connection: pass the
        job_id from that result (the service accepts both the job_id and the client_job_id) to learn the real
        progress and to collect the images that were actually produced.
        Also use it when the user asks how the picture is going or whether an earlier attempt finished.
        Omit job_id to list the most recent jobs (newest first) and pick the right one from that list.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("job_id", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "The job_id (or client_job_id) reported by generate_image. Omit it to list recent jobs."
                    )
                })
                put("include_images", buildJsonObject {
                    put("type", "boolean")
                    put(
                        "description",
                        "Download the images when the job already succeeded. Defaults to true; set false to only " +
                            "read the status."
                    )
                })
            },
        )
    },
    needsApproval = { false },
    execute = { args ->
        try {
            val params = args as? JsonObject
            if (params == null) {
                listOf(UIMessagePart.Text(errorJson("参数必须是 JSON 对象")))
            } else {
                service.queryJob(
                    settings = settingsProvider(),
                    jobId = params.contentOrNull("job_id"),
                    includeImages = params.booleanOrNull("include_images") ?: true,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            listOf(UIMessagePart.Text(errorJson(t.message ?: t::class.simpleName ?: "unknown")))
        }
    },
)

/** 解析工具入参；prompt 为空或不是对象时返回 null。 */
internal fun parseImageGenerationRequest(args: JsonElement): ImageGenerationService.Request? {
    val params = args as? JsonObject ?: return null
    val prompt = params.contentOrNull("prompt")?.trim().orEmpty()
    if (prompt.isEmpty()) return null
    return ImageGenerationService.Request(
        prompt = prompt,
        negative = params.contentOrNull("negative"),
        size = params.contentOrNull("size"),
        steps = params.intOrNull("steps"),
        seed = params.longOrNull("seed"),
        batchSize = params.intOrNull("batch_size"),
        referenceImages = params.stringList("reference_images"),
        noReference = params.booleanOrNull("no_reference") ?: false,
    )
}

/**
 * 把会话里已有的本地图片列给模型，让它可以挑几张当参考图；没有就返回空串，不占系统提示词。
 *
 * [hasFullBodyPhoto] 为 true 表示这个助手绑了一张固定的全身照片，出图时它固定占 image1，
 * 所以清单只能列 `MAX_REFERENCE_IMAGES - 1` 张，并额外说明这条固定参考图的存在。
 */
internal fun buildReferenceImagesPrompt(
    messages: List<UIMessage>,
    hasFullBodyPhoto: Boolean = false,
): String {
    // 有固定全身照片时它自己占掉一个槽位，会话里的图只剩 1 个名额
    val slots = if (hasFullBodyPhoto) MAX_REFERENCE_IMAGES - 1 else MAX_REFERENCE_IMAGES
    val urls = messages.asSequence()
        .flatMap { it.parts.asSequence() }
        .filterIsInstance<UIMessagePart.Image>()
        .map { it.url }
        .filter { it.startsWith("file://") }
        .toList()
        .takeLast(slots)
    // 既没有会话里的图，也没有固定全身照片：一句话都不用说
    if (urls.isEmpty() && !hasFullBodyPhoto) return ""
    return buildString {
        if (urls.isNotEmpty()) {
            appendLine("<available_reference_images>")
            appendLine("Images from this conversation that can be used as generate_image reference_images:")
            urls.forEach { appendLine(it) }
            append("</available_reference_images>")
        }
        if (hasFullBodyPhoto) {
            if (urls.isNotEmpty()) appendLine()
            appendLine("<fixed_full_body_photo>")
            appendLine(
                "The user has attached a fixed full-body reference photo of themselves. " +
                    "It is ALWAYS sent as reference image 1."
            )
            appendLine(
                "You may additionally supply at most ${MAX_REFERENCE_IMAGES - 1} image from this conversation " +
                    "(it becomes reference image 2)."
            )
            append("</fixed_full_body_photo>")
        }
    }
}

private fun errorJson(message: String): String = buildJsonObject {
    put("ok", false)
    put("error", message)
}.toString()

private fun JsonObject.contentOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.booleanOrNull(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.stringList(key: String): List<String> {
    val array = this[key] as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { text -> text.isNotBlank() } }
}
