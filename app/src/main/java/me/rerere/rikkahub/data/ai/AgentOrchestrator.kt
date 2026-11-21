package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.prompts.ORCHESTRATION_PLACEHOLDER_DIALOGUE
import me.rerere.rikkahub.data.ai.prompts.ORCHESTRATION_PLACEHOLDER_HISTORY
import me.rerere.rikkahub.data.ai.prompts.ORCHESTRATION_STAGE_PRESETS
import me.rerere.rikkahub.data.ai.prompts.OrchestrationStagePreset
import me.rerere.rikkahub.data.ai.prompts.OrchestrationStagePresetIds
import me.rerere.rikkahub.data.ai.prompts.fillOrchestrationPrompt
import me.rerere.rikkahub.data.ai.prompts.findOrchestrationStagePreset
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.AgentStage
import me.rerere.rikkahub.data.model.AgentStageKind
import me.rerere.rikkahub.data.model.AssistantOrchestration
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf

private const val TAG = "AgentOrchestrator"

/** 阶段级审查的重试上限，与 `SettingsNormalization` 对 `reviewer.maxRetry` 的收敛范围同源 */
private const val MAX_STAGE_RETRY = 3

/**
 * 一个块阶段最终产出的内容。
 *
 * @property tag 要写回消息的标签名（已 trim）
 * @property content 标签内的正文（已归一化：无围栏、无外层标签、无首尾引号）
 * @property attempts 这条内容一共试了几次（1 表示一次过）
 * @property review 最后一次审查结论；未启用阶段审查时为 null
 */
data class ProducedBlock(
    val tag: String,
    val content: String,
    val attempts: Int,
    val review: ReplyReviewResult?,
)

/**
 * 一次编排里所有块阶段的结果。
 *
 * @property blocks 成功产出的块，顺序与配置的块阶段顺序一致
 * @property failures 非致命的失败说明（某个阶段彻底没产出、审查到上限后 fail-open 采用等），
 * 只用于日志与界面提示；非空不代表没有 blocks
 */
data class OrchestrationResult(
    val blocks: List<ProducedBlock>,
    val failures: List<String>,
)

/**
 * 多 Agent 编排器：串行执行块阶段，每个阶段独立生成、（可选）独立审查。
 *
 * 只负责「块阶段」这一段：对话正文由调用方生成好之后交进来（[runBlockStages] 的 `dialogue`），
 * 产出的块也只交回去，怎么拼进消息由调用方决定。这样才能在下一阶段把编排接进 `ChatService`
 * 而不动这里的任何逻辑。
 *
 * 三个刻意的取舍：
 * 1. **任何单阶段的失败都不影响其它阶段**：块是独立的信息，一个阶段的模型抽风不该让整条回复丢掉语音指令；
 * 2. **fail-open**：审查重试到上限后，只要最后一次内容非空就照用（与 [ReplyQualityReviewer] 同一套哲学），
 *    并记一条 failure 让上层知道这块质量存疑；
 * 3. **[CancellationException] 一律 rethrow**：用户点了停止生成就必须立刻停，不能被「不中断整链」的兜底吞掉。
 */
class AgentOrchestrator(
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val stageReviewer: StageReviewer,
) {
    /**
     * 按配置顺序串行执行所有块阶段。
     *
     * @param dialogue 本轮的对话正文，作为每个块阶段的输入
     * @param history 最近的对话历史，一行一条（由调用方截断）
     */
    suspend fun runBlockStages(
        settings: Settings,
        conversation: Conversation,
        orchestration: AssistantOrchestration,
        dialogue: String,
        history: String,
    ): OrchestrationResult {
        val stages = orchestration.blockStages()
        // 总开关关掉时一律不执行：调用方可能因为别的原因把整份编排传进来，这里兜一道，
        // 免得「关掉编排」这件事要靠每个调用点自己记住
        if (!orchestration.enabled) return OrchestrationResult(blocks = emptyList(), failures = emptyList())
        if (stages.isEmpty()) return OrchestrationResult(blocks = emptyList(), failures = emptyList())
        // 正文为空时块阶段没有任何输入，让模型凭空编一段语音指令只会得到和这句话无关的废话
        if (dialogue.isBlank()) {
            return OrchestrationResult(
                blocks = emptyList(),
                failures = listOf("对话正文为空，已跳过 ${stages.size} 个块阶段"),
            )
        }

        val assistant = settings.getAssistantOf(conversation)
        val charName = assistant.name.ifBlank { "assistant" }
        val userName = settings.displaySetting.userNickname.ifBlank { "user" }
        val blocks = mutableListOf<ProducedBlock>()
        val failures = mutableListOf<String>()

        for (stage in stages) {
            runCatching {
                generateBlockStage(
                    settings = settings,
                    conversation = conversation,
                    stage = stage,
                    dialogue = dialogue,
                    history = history,
                    charName = charName,
                    userName = userName,
                    noteFailure = failures::add,
                )
            }.onSuccess { block ->
                blocks += block
            }.onFailure { error ->
                // 用户停止生成/离开会话必须立刻中断整条链，否则「停止」在块阶段里会失效
                if (error is CancellationException) throw error
                Log.w(TAG, "Block stage failed: ${stage.name}", error)
                failures += "${stageDisplayName(stage)}：${error.message ?: error.javaClass.simpleName}"
            }
        }

        return OrchestrationResult(blocks = blocks, failures = failures)
    }

    /** 单个块阶段的完整流程：组装 prompt → 生成 → 归一化 → 审查 → （重试） */
    private suspend fun generateBlockStage(
        settings: Settings,
        conversation: Conversation,
        stage: AgentStage,
        dialogue: String,
        history: String,
        charName: String,
        userName: String,
        noteFailure: (String) -> Unit,
    ): ProducedBlock {
        val template = stage.prompt.takeIf { it.isNotBlank() } ?: defaultStagePrompt(stage)?.prompt
            ?: error("阶段没有可用的指令")

        // 阶段模型优先；没配时跟随本轮会话模型 —— 块阶段要读懂这句话的语境，用全局默认模型只会跑题
        val model = settings.findModelById(stage.modelId)
            ?: settings.getChatModelOf(conversation)
            ?: error("找不到可用模型")
        val provider = model.findProvider(settings.providers)
            ?: error("模型 ${model.displayName} 找不到提供商")

        // 后台请求的默认参数里没有 temperature/maxTokens，阶段想覆盖时直接写在上面
        val baseParams = backgroundTextGenerationParams(
            model,
            conversation.id,
            settings.fastModelReasoningLevel,
        )
        val params = baseParams.copy(
            temperature = stage.temperature ?: baseParams.temperature,
            maxTokens = stage.maxTokens ?: baseParams.maxTokens,
        )

        val maxAttempts = stage.reviewer.maxRetry.coerceIn(0, MAX_STAGE_RETRY) + 1
        var attempts = 0
        var lastReview: ReplyReviewResult? = null
        var content = ""

        while (attempts < maxAttempts) {
            attempts++
            val prompt = buildBlockStagePrompt(
                template = template,
                dialogue = dialogue,
                history = history,
                charName = charName,
                userName = userName,
                retryFeedback = lastReview?.let { buildStageRetryFeedback(it) }.orEmpty(),
            )
            val raw = usageRecorder.wrap(
                providerManager.getProviderByType(provider),
                // 这是真正的生成请求（用的是聊天模型与聊天参数），计入 CHAT 才符合用量统计的口径；
                // 审查请求走 REVIEWER，由 StageReviewer 自己记录
                UsageTag(UsageSource.CHAT, conversation.assistantId, conversation.id),
            ).generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = params,
            ).message.toText()

            content = normalizeBlockOutput(raw, stage.outputTag)
            if (!stage.reviewer.enabled) {
                return ProducedBlock(stage.outputTag.trim(), content, attempts, null)
            }

            // 空产出交给 LLM 审也一定是「不合格」，本地直接判掉，省一次请求
            val review = if (content.isEmpty()) {
                emptyOutputReview()
            } else {
                stageReviewer.review(
                    settings = settings,
                    conversation = conversation,
                    stage = stage,
                    dialogue = dialogue,
                    history = history,
                    output = content,
                )
            }
            lastReview = review
            if (review.passed) return ProducedBlock(stage.outputTag.trim(), content, attempts, review)
        }

        // 重试到上限仍未通过：最后一次内容非空就照用（fail-open），空产出则整块放弃
        if (content.isEmpty()) error("输出为空，重试 $attempts 次后放弃")
        noteFailure(
            "${stageDisplayName(stage)}：审查未通过（得分 ${lastReview?.score ?: 0}），" +
                "已采用最后一次输出"
        )
        return ProducedBlock(stage.outputTag.trim(), content, attempts, lastReview)
    }
}

/** 阶段在界面/日志里的名字：优先用配置的 name，退到标签，最后才用 id */
private fun stageDisplayName(stage: AgentStage): String = stage.name.ifBlank {
    stage.outputTag.ifBlank { stage.id.toString() }
}

/** 空产出的审查结论：不算 LLM 审查，只是一个让重试反馈有内容的本地判定 */
private fun emptyOutputReview() = ReplyReviewResult(
    passed = false,
    score = 0,
    issues = listOf("输出为空"),
    suggestion = "重新输出一段完整、与这句对话对应的语音导演指令",
    raw = "empty-output",
)

/**
 * 阶段没写 prompt 时的默认指令：先按类型找预设，块阶段优先按 `outputTag` 精确匹配预设。
 *
 * 认不出标签时退回第一个块预设 —— 阶段已经声明了要产出一个块，给一个「像样的默认」比直接失败更有用，
 * 审查会在质量不达标时把它打回。
 */
private fun defaultStagePrompt(stage: AgentStage): OrchestrationStagePreset? = when (stage.kind) {
    AgentStageKind.DIALOGUE -> findOrchestrationStagePreset(OrchestrationStagePresetIds.DIALOGUE)

    AgentStageKind.BLOCK -> ORCHESTRATION_STAGE_PRESETS.firstOrNull { preset ->
        preset.kind == AgentStageKind.BLOCK &&
            preset.outputTag.equals(stage.outputTag.trim(), ignoreCase = true)
    } ?: ORCHESTRATION_STAGE_PRESETS.firstOrNull { it.kind == AgentStageKind.BLOCK }
}

/**
 * 把模型输出收成「块内正文」。
 *
 * 模型对「块内内容」的输出习惯很不稳定，这一步负责吃掉所有可预期的包装：
 * 1. Markdown 围栏（```xml / ```）—— 复用全局审查那套 [stripCodeFence]；
 * 2. 自作主张加上的外层 `<tag>…</tag>`（大小写、标签名两侧空白都容忍，多层嵌套逐层剥）；
 * 3. 首尾成对的引号（`"` `'` `“”` `「」` `『』`）；
 * 4. 连续空行与首尾空白。
 *
 * 只处理「整段被包住」的情况：中间夹着解释文字的产出不在这里猜边界，交给审查去判不合格。
 */
internal fun normalizeBlockOutput(raw: String, tag: String): String {
    var text = raw.stripCodeFence().replace("\r\n", "\n")

    val name = tag.trim()
    if (name.isNotEmpty()) {
        val wrapped = Regex(
            pattern = """\A<\s*${Regex.escape(name)}\s*>(.*)<\s*/\s*${Regex.escape(name)}\s*>\z""",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        // 每轮至少少掉一对标签，一定收敛；`<tag></tag>` 剥完变成空串后自然退出
        while (true) {
            val inner = wrapped.matchEntire(text.trim())?.groupValues?.get(1) ?: break
            text = inner
        }
    }

    text = text.trim().stripSurroundingQuotes()
    // 剥掉标签后常常留下两三个空行，压成一个空行；段内的单个换行是有意的，保留
    text = BLANK_LINE_RUN.replace(text, "\n\n")
    return text.trim()
}

private val BLANK_LINE_RUN = Regex("""\n{3,}""")

/** 成对引号：中英文都收，`「」『』` 在中文里同样常被模型当成「引用」用 */
private val SURROUNDING_QUOTE_PAIRS = listOf(
    "\"" to "\"",
    "'" to "'",
    "“" to "”",
    "「" to "」",
    "『" to "』",
)

private fun String.stripSurroundingQuotes(): String {
    var text = this.trim()
    while (text.length >= 2) {
        val pair = SURROUNDING_QUOTE_PAIRS.firstOrNull { (open, close) ->
            text.startsWith(open) && text.endsWith(close)
        } ?: break
        text = text.removePrefix(pair.first).removeSuffix(pair.second).trim()
    }
    return text
}

/**
 * 组装块阶段的最终 prompt：先填占位符，再按需兜底材料，最后挂上重试反馈。
 *
 * 兜底的理由与 `fillReviewerPrompt` 一样：用户手写模板时很容易只写指令、不写占位符，那样模型什么材料都
 * 看不到，只会凭空编。这里知道自己在拼哪个阶段，所以可以补得比 `fillOrchestrationPrompt` 更具体。
 */
internal fun buildBlockStagePrompt(
    template: String,
    dialogue: String,
    history: String,
    charName: String,
    userName: String,
    retryFeedback: String = "",
): String {
    val filled = template.fillOrchestrationPrompt(
        dialogue = dialogue,
        history = history,
        charName = charName,
        userName = userName,
    )
    val hasMaterialPlaceholder =
        template.contains("{${ORCHESTRATION_PLACEHOLDER_DIALOGUE}}") ||
            template.contains("{${ORCHESTRATION_PLACEHOLDER_HISTORY}}")
    val body = if (hasMaterialPlaceholder) {
        filled.trim()
    } else {
        buildString {
            appendLine(filled.trim())
            appendLine()
            appendLine("【最近对话】")
            appendLine(history)
            appendLine()
            appendLine("【本轮对话文本】")
            append(dialogue)
        }.trim()
    }
    val feedback = retryFeedback.trim()
    return if (feedback.isEmpty()) body else body.trimEnd() + "\n\n" + feedback
}

/**
 * 把上一次审查的结论翻成给生成模型的修正指令。
 *
 * 一次重试只能改进有限的地方，所以先把 issues 全列出来（它们是具体问题），再接一句 suggestion（方向）。
 * 模型只给了分数、没给问题时也要有内容，否则重试提示是空话，等于原样再问一遍。
 */
internal fun buildStageRetryFeedback(result: ReplyReviewResult): String = buildString {
    appendLine("上一轮未通过审查，需修正：")
    val issues = result.issues.map { it.trim() }.filter { it.isNotEmpty() }
    issues.forEach { issue -> appendLine("- $issue") }
    val suggestion = result.suggestion.trim()
    when {
        suggestion.isNotEmpty() -> append("建议：$suggestion")
        issues.isEmpty() -> append("- 上一轮不合格（得分 ${result.score}），请重新生成")
    }
}.trim()

/** 把产出的块包成 `<tag>\\n内容\\n</tag>`，供调用方直接拼进消息正文 */
internal fun formatBlockText(block: ProducedBlock): String =
    "<${block.tag}>\n${block.content.trim()}\n</${block.tag}>"

/**
 * 这条消息里真正「说出来的话」：只拼 [UIMessagePart.Text]。
 *
 * 不复用 [UIMessage.toText] 是因为那边会给非 Text 的 part 也留一个空串占位，结果是一堆空行；
 * reasoning / tool 调用 / 图片都不属于对话正文，块阶段拿它们当输入只会被带偏。
 */
internal fun UIMessage.dialogueOnlyText(): String = parts
    .filterIsInstance<UIMessagePart.Text>()
    .map { it.text.trim() }
    .filter { it.isNotEmpty() }
    .joinToString(separator = "\n")
