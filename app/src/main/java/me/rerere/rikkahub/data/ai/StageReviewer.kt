package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.prompts.OrchestrationReviewerPresetIds
import me.rerere.rikkahub.data.ai.prompts.fillOrchestrationPrompt
import me.rerere.rikkahub.data.ai.prompts.findOrchestrationReviewerPreset
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.AgentStage
import me.rerere.rikkahub.data.model.AgentStageKind
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.getAssistantOf

private const val TAG = "StageReviewer"

/**
 * 阶段级审查器：审「一个编排阶段的产出」，而不是整条回复。
 *
 * 与 [ReplyQualityReviewer] 同构（同样是「问一次、解析一次」，同样 fail-open），差异只有三点：
 * 1. 审查对象是阶段产出（块阶段的产出还要额外填进 `{output}`），所以 prompt 从 `stage.reviewer` 取；
 * 2. 完全没有 `checkVoiceControlBlock` 那套 TTS 块确定性校验 —— 那套规则针对的是「一条回复里必须
 *    恰好一个 `<tts_voice_control>` 块」，而块阶段的产出本身就是块内容，套用只会自相矛盾；
 * 3. 判否之后的重试策略不在这里，由 [AgentOrchestrator] 决定。
 *
 * 任何一环出问题都返回 `passed = true`：审查是可选的质量兜底，不能因为它失败就让整条编排链断掉。
 */
class StageReviewer(
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
) {
    /**
     * 审查一个阶段的产出。
     *
     * @param dialogue 本轮的对话正文（块阶段的输入、对话阶段就是要审的对象）
     * @param history 最近的对话历史，一行一条，用于判断「是否与上下文有关」
     * @param output 待审的阶段产出；对话阶段审查时留空
     * @return 审查结论；超时、异常、找不到模型/提供商/预设时一律 fail-open
     */
    suspend fun review(
        settings: Settings,
        conversation: Conversation,
        stage: AgentStage,
        dialogue: String,
        history: String,
        output: String = "",
    ): ReplyReviewResult {
        val template = stage.reviewer.prompt.takeIf { it.isNotBlank() }
            ?: findOrchestrationReviewerPreset(stage.reviewer.presetId)?.prompt
            ?: defaultReviewerPrompt(stage)
            ?: return failOpen("prompt not found")

        // 没配阶段审查模型时跟随快速模型，语义与 ReplyQualityReviewer / 记忆整理一致
        val model = stage.reviewer.modelId?.let { settings.findModelById(it) }
            ?: settings.findModelById(settings.fastModelId)
            ?: return failOpen("model not found")

        val provider = model.findProvider(settings.providers)
            ?: return failOpen("provider not found")

        // 占位符里的角色名/用户名与 PlaceholderTransformer 同源，避免两处渲染出不同的称呼
        val assistant = settings.getAssistantOf(conversation)
        val prompt = template.fillOrchestrationPrompt(
            dialogue = dialogue,
            history = history,
            output = output,
            charName = assistant.name.ifBlank { "assistant" },
            userName = settings.displaySetting.userNickname.ifBlank { "user" },
        )

        val raw = try {
            withTimeoutOrNull(REVIEW_TIMEOUT_MILLIS) {
                usageRecorder.wrap(
                    providerManager.getProviderByType(provider),
                    UsageTag(UsageSource.REVIEWER, conversation.assistantId, conversation.id),
                ).generateText(
                    providerSetting = provider,
                    messages = listOf(UIMessage.user(prompt)),
                    params = backgroundTextGenerationParams(
                        model,
                        conversation.id,
                        settings.fastModelReasoningLevel,
                    ),
                ).message.toText()
            } ?: return failOpen("timeout")
        } catch (e: CancellationException) {
            // 用户停止生成 / 离开会话不算审查失败，照常往上抛，让整条链立刻停下
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Stage review request failed", e)
            return failOpen(e.message ?: e.javaClass.simpleName)
        }

        // 解析直接复用全局审查那一套：两边 prompt 都要求同一个 JSON 协议，
        // 各写一份解析只会让「围栏 / 夹带解释 / 关键词兜底」的容错慢慢长歪
        return parseReviewResult(raw)
    }

    private fun failOpen(reason: String) = ReplyReviewResult(
        passed = true,
        score = 100,
        raw = reason,
    )
}

/**
 * 阶段没配预设也没写 prompt 时的兜底：按阶段类型回退到对应预设。
 *
 * 全体预设都认不出时返回 null，交给调用方 fail-open —— 与「审查失败不阻塞生成」同一个态度。
 */
internal fun defaultReviewerPrompt(stage: AgentStage): String? {
    val id = when (stage.kind) {
        AgentStageKind.DIALOGUE -> OrchestrationReviewerPresetIds.DIALOGUE
        AgentStageKind.BLOCK -> OrchestrationReviewerPresetIds.TTS_VOICE
    }
    return findOrchestrationReviewerPreset(id)?.prompt
}
