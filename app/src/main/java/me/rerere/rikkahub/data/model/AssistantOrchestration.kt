package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 多 Agent 串联编排：把一次回复拆成若干阶段，每个阶段独立请求模型、独立审查。
 *
 * 只有 [enabled] 为 true 且至少有一个启用阶段时才走编排链路；否则调用方应完全按原来的单 Agent 流程生成，
 * 所以下面所有判断方法都返回「什么都不做」的默认值（空列表 / null / false），调用方不需要额外判空。
 *
 * 阶段按 [stages] 的先后顺序**串行**执行：第一个 [AgentStageKind.DIALOGUE] 阶段产出对话正文，
 * 之后每个 [AgentStageKind.BLOCK] 阶段拿着正文再请求一次模型，产出一段可以写回消息的标签块
 * （如 `tts_voice_control`）。串行而不是并行是刻意的：块阶段依赖对话正文，而多个块阶段之间
 * 目前没有依赖关系，但串行能保证失败时已产出的块仍然可用。
 */
@Serializable
data class AssistantOrchestration(
    val enabled: Boolean = false,
    val stages: List<AgentStage> = emptyList(),
) {
    /** 启用的阶段，保持配置顺序 —— 这就是实际的串行执行顺序 */
    fun enabledStages(): List<AgentStage> = stages.filter { it.enabled }

    /**
     * 主对话阶段：第一个启用的对话阶段。
     *
     * 允许配多个对话阶段（例如「先吐槽再正经回答」），此时只有第一个负责产出交付给用户的消息正文。
     */
    fun primaryStage(): AgentStage? = enabledStages().firstOrNull { it.kind == AgentStageKind.DIALOGUE }

    /**
     * 需要独立生成并写回消息的块阶段：启用的、类型是块的、且 [AgentStage.outputTag] 非空。
     *
     * `outputTag` 为空意味着产出包不出标签、写不回消息，这种配置直接跳过而不是让它白烧一次请求。
     */
    fun blockStages(): List<AgentStage> = enabledStages().filter {
        it.kind == AgentStageKind.BLOCK && it.outputTag.isNotBlank()
    }

    /**
     * 这个编排是否「占用」某个标签。
     *
     * 调用方拿到一段带标签的文本时靠它判断该由编排的块阶段负责，避免与内置的 TTS 语气块处理互相覆盖。
     * 比较忽略大小写与首尾空白；空标签永远不算占用（否则任何空产出都会被认领走）。
     * 只比对 [blockStages]，未启用阶段的标签不占用 —— 它不会产出任何东西。
     */
    fun ownsTag(tag: String): Boolean {
        val normalized = tag.trim()
        if (normalized.isEmpty()) return false
        return blockStages().any { stage -> stage.outputTag.trim().equals(normalized, ignoreCase = true) }
    }
}

/** 阶段类型：产出对话正文，还是产出某个标签块 */
@Serializable
enum class AgentStageKind {
    /** 对话正文，交付给用户的那部分 */
    @SerialName("dialogue")
    DIALOGUE,

    /** 标签块，例如语音导演指令 `tts_voice_control` */
    @SerialName("block")
    BLOCK,
}

/**
 * 编排里的一个阶段。
 *
 * @property id 阶段标识，用于把产出写回消息时定位
 * @property name 展示名，只影响界面与日志
 * @property enabled 关掉后不参与执行
 * @property kind 见 [AgentStageKind]
 * @property outputTag 只在 [AgentStageKind.BLOCK] 时生效：产出会被包成 `<tag>…</tag>` 写回消息
 * @property prompt 该阶段独立的指令正文，含 `{dialogue}` / `{history}` 等占位符；留空则用预设默认正文
 * @property modelId 该阶段使用的模型；null 表示跟随本轮会话模型（[AgentStageKind.BLOCK] 通常该跟对话模型）
 * @property temperature 覆盖该阶段的生成温度；null 表示用后台请求的默认值
 * @property maxTokens 覆盖该阶段的输出长度上限；null 表示不限制
 * @property reviewer 该阶段独立的审查配置，见 [StageReviewerSetting]
 */
@Serializable
data class AgentStage(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val enabled: Boolean = true,
    val kind: AgentStageKind = AgentStageKind.BLOCK,
    val outputTag: String = "",
    val prompt: String = "",
    val modelId: Uuid? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val reviewer: StageReviewerSetting = StageReviewerSetting(),
)

/**
 * 单个阶段自己的审查配置。
 *
 * 与全局的 `ReviewerSetting` 是两套东西：全局那份审「整条回复」，这份只审「这个阶段的产出」，
 * 所以它多了一个 `{output}` 占位符 —— 块阶段要审的是自己产出的那段内容，而不是对话正文。
 *
 * @property enabled 是否启用阶段级审查，默认关闭，不改变已有助手的行为
 * @property presetId 对应 `OrchestrationReviewerPresetIds` 里的常量；认不出时按阶段类型回退默认预设
 * @property prompt 实际发给审查模型的正文，含 `{dialogue}` `{history}` `{output}` `{char}` `{user}` 占位符
 * @property maxRetry 审查不通过时最多重试几次，归一化夹在 0..3
 * @property modelId 审查用模型；null 表示跟随 `Settings.fastModelId`
 */
@Serializable
data class StageReviewerSetting(
    val enabled: Boolean = false,
    val presetId: String? = null,
    val prompt: String = "",
    val maxRetry: Int = 1,
    val modelId: Uuid? = null,
)
