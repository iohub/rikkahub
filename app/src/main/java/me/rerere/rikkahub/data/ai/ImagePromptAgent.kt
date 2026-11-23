package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_IMAGE_NEGATIVE
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_IMAGE_PROMPT_WRITER_PROMPT
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * 出图改写这一步的端口：返回 null 表示没能改写（调用方回退用户原文）。
 *
 * 单独抽出来是为了让出图编排能脱离 [ImagePromptAgent] 依赖的 ProviderManager / UsageRecorder，
 * 在 JVM 单测里用固定结果替换掉这次模型调用。
 */
fun interface PromptWriter {
    suspend fun writePrompt(settings: Settings, conversationId: Uuid?, request: String, size: String): ImagePromptAgent.Draft?
}

/**
 * 出图专用的独立 agent：用一个单独的模型把用户需求改写成专业 diffusion prompt。
 *
 * 独立于聊天上下文，所以改写结果不会被对话历史带偏，也不需要占对话的模型额度。
 * 任何失败都只返回 null 或 fail-open 的原文，让出图流程继续跑下去。
 */
open class ImagePromptAgent(
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
) : PromptWriter {
    /** 改写结果：正向 prompt 与避开项。 */
    data class Draft(val prompt: String, val negative: String)

    @Serializable
    private data class Raw(val prompt: String? = null, val negative: String? = null)

    /**
     * 用独立模型把 [request] 改写成绘图 prompt。
     *
     * 模型找不到、请求失败时返回 null（调用方回退用户原文）；模型返回的内容不是可解析的 JSON 时
     * fail-open，把清洗后的原文当 prompt 并配默认 negative，避免一句格式错误就丢一次出图机会。
     */
    override suspend fun writePrompt(
        settings: Settings,
        conversationId: Uuid?,
        request: String,
        size: String,
    ): Draft? {
        val (model, provider) = resolveModel(settings) ?: return null
        val prompt = DEFAULT_IMAGE_PROMPT_WRITER_PROMPT.applyPlaceholders(
            "content" to request,
            "size" to size,
            "locale" to Locale.getDefault().displayName,
        )
        return try {
            val result = usageRecorder.wrap(
                providerManager.getProviderByType(provider),
                UsageTag(UsageSource.IMAGE_PROMPT, conversationId = conversationId),
            ).generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(
                    model = model,
                    sessionId = conversationId ?: Uuid.random(),
                    reasoningLevel = settings.fastModelReasoningLevel,
                ),
            )
            val text = result.message.toText()
            // 解析不出来就 fail-open 用原文：改写只是加分项，不该因为它丢掉一次出图
            parse(text) ?: Draft(
                prompt = stripCodeFence(text).take(MAX_FALLBACK_CHARS),
                negative = DEFAULT_IMAGE_NEGATIVE,
            ).takeIf { it.prompt.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }
    }

    /** 改写用的模型：专用模型 → 快速模型 → 对话模型，取第一个模型和它的提供商都还在的。 */
    private fun resolveModel(settings: Settings): Pair<Model, ProviderSetting>? {
        val candidates = listOfNotNull(
            settings.imageGenerationConfig.promptModelId,
            settings.fastModelId,
            settings.chatModelId,
        )
        for (id in candidates.distinct()) {
            val model = settings.findModelById(id) ?: continue
            val provider = model.findProvider(settings.providers) ?: continue
            return model to provider
        }
        return null
    }

    private fun parse(text: String): Draft? {
        val cleaned = stripCodeFence(text)
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val raw = runCatching {
            JsonInstant.decodeFromString<Raw>(cleaned.substring(start, end + 1))
        }.getOrNull() ?: return null
        val prompt = raw.prompt?.trim().orEmpty()
        if (prompt.isEmpty()) return null
        val negative = raw.negative?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_IMAGE_NEGATIVE
        return Draft(prompt = prompt, negative = negative)
    }

    /** 去掉模型偶尔加上的 markdown 围栏，JSON 截取和 fail-open 都用清洗后的文本。 */
    private fun stripCodeFence(text: String): String {
        var result = text.trim()
        if (result.startsWith("```")) {
            val newline = result.indexOf('\n')
            result = if (newline >= 0) result.substring(newline + 1) else result.removePrefix("```")
        }
        if (result.endsWith("```")) {
            result = result.removeSuffix("```")
        }
        return result.trim()
    }

    private companion object {
        const val MAX_FALLBACK_CHARS = 2000
    }
}
