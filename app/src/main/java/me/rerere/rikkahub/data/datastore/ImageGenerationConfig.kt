package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 远程图片生成服务（用户自建的 ComfyUI Qwen-Image-Edit 之类）的配置。
 *
 * 服务地址留空时视为未配置，`generate_image` 工具会直接返回提示，让用户去设置里填写。
 */
@Serializable
data class ImageGenerationConfig(
    /** 远程出图服务地址，如 `http://192.168.1.10:8080`；空 = 未配置。 */
    val baseUrl: String = "",
    /** 服务端启用 `--token` 时填这里，请求会带上 `X-Auth-Token` 头。 */
    val authToken: String = "",
    /** 单次出图的等待秒数：冷启动可能要 1~3 分钟，默认给足 1200 秒。 */
    val timeoutSeconds: Int = 1200,
    /** 采样步数，Qwen-Image-Edit 的推荐值是 4。 */
    val steps: Int = 4,
    /** 默认出图尺寸，格式 `宽x高`，如 `1024x1024`；工具没指定尺寸时用它。 */
    val defaultSize: String = "1024x1024",
    /**
     * 强制使用 [defaultSize]：开启后忽略模型在 `generate_image` 工具调用里指定的尺寸。
     *
     * 为什么默认开：workflow 的 latent 尺寸由服务端参数决定，模型乱填尺寸（比如 1920x1080
     * 之外的怪值）会让出图比例失控；对普通用户来说「尺寸稳定」比「模型能自由选尺寸」更重要。
     */
    val forceDefaultSize: Boolean = true,
    /**
     * 改写绘图 prompt 的独立 agent 用哪个模型；null 时回退快速模型，再回退对话模型。
     *
     * 和对话模型分开是为了避免绘图 prompt 的风格与上下文污染聊天。
     */
    val promptModelId: Uuid? = null,
    /** 关掉后直接用模型给出的原文当 prompt，不再经过独立 agent 改写。 */
    val enablePromptAgent: Boolean = true,
)
