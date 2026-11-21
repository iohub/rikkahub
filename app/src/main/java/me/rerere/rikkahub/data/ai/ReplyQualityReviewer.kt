package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.prompts.fillReviewerPrompt
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.transformers.ttsVoiceBlockCount
import me.rerere.rikkahub.data.ai.transformers.ttsVoiceInstruct
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "ReplyQualityReviewer"

/** 审查请求的超时时间：审查只是锦上添花，卡住 20 秒就该放行 */
internal const val REVIEW_TIMEOUT_MILLIS = 20_000L

/** 每条历史消息最多带进 prompt 的字数 */
private const val HISTORY_MESSAGE_MAX_LENGTH = 300

private const val MIN_SCORE = 0
private const val MAX_SCORE = 100

/**
 * `<tts_voice_control>` 块内的语气描述至少要有几个词组。
 *
 * 阈值 5 是刻意定得很低的兜底：只拦明显敷衍的短句（「平静地念」「语气平淡」），
 * 正常写完语气/音量/语速/音色/气息/情绪六个维度的描述轻松超过 20 个词组。
 */
private const val MIN_VOICE_INSTRUCT_WORDS = 5

/**
 * 正文有效字符数不超过这个值时，「必须有块」这条不生效。
 *
 * 注入给模型的规范（[TtsVoiceControlPrompt] 规则 6）允许「只是简短确认时省略整个块」，
 * 审查这边必须用同一个口径，否则用户说一句「嗯」、模型回一句「好的」就会被判缺块而白白重生成一整轮。
 * 这个阈值宁可放宽：漏判的代价只是这轮 TTS 用设置里的静态语气朗读，误判的代价是多烧一整轮生成。
 */
private const val SHORT_REPLY_EXEMPT_LENGTH = 12

/**
 * 一次审查的结论。
 *
 * @property passed 是否通过；任何异常都按通过处理（fail-open），审查失败不该阻塞用户
 * @property score 0..100 的整体质量分，解析不出来时通过给 100、不通过给 0
 * @property issues 命中的问题，用中文描述
 * @property suggestion 一句话改进建议，通过时可能为空串
 * @property raw 模型原始输出；fail-open 时是放行的原因（如 "timeout"）
 */
data class ReplyReviewResult(
    val passed: Boolean,
    val score: Int,
    val issues: List<String> = emptyList(),
    val suggestion: String = "",
    val raw: String = "",
)

/**
 * 回复质量审查器：拿设置里的审查 prompt 调一次辅助模型，判断刚生成的回复能不能交付。
 *
 * 只负责「问一次、解析一次」，什么时候调用、判否后怎么重生成由调用方（ChatService）决定。
 * 任何一环出问题都返回 passed=true：审查是可选的质量兜底，不能因为它失败让用户拿不到回复。
 */
class ReplyQualityReviewer(
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
) {
    /**
     * 审查最后一条 assistant 回复；任何异常/超时都 fail-open（passed=true）
     */
    suspend fun review(
        settings: Settings,
        conversation: Conversation,
        reply: UIMessage,
        historyLimit: Int = 6,
    ): ReplyReviewResult {
        val replyText = reply.toText().trim()
        if (replyText.isEmpty()) return failOpen("empty reply")

        // 确定性校验先于 LLM 审查：`<tts_voice_control>` 块已经被 TtsVoiceControlTransformer 从正文里剥掉，
        // toText() 里看不到任何标签，这种纯格式问题让辅助模型猜只会白烧一次 20 秒的调用。
        // 「需不需要有块」必须与 ChatRequestFactory 注入规范的条件同源，否则会出现
        // 「根本没注入规范却要求必须有块」的死循环。另外，已经剥到过块（blockCount > 0）说明模型确实在用
        // 这个格式，此时无论设置怎么变都要校验块内非空。
        val assistant = settings.getAssistantOf(conversation)
        val effectiveSystemPrompt =
            if (assistant.allowConversationSystemPrompt && !conversation.customSystemPrompt.isNullOrBlank()) {
                conversation.customSystemPrompt
            } else {
                assistant.systemPrompt
            }
        val blockCount = reply.ttsVoiceBlockCount()
        val requiresBlock =
            (LocalToolOption.Tts in assistant.localTools && !assistant.promptInjectionOverride.disableAll) ||
                effectiveSystemPrompt.contains("tts_voice_control") ||
                blockCount > 0
        // 正文有效字符数（此刻 toText() 里的 `<tts_voice_control>` 块已被 TtsVoiceControlTransformer 剥掉，
        // 所以数出来就是真正的对话正文）。与注入规范的「简短确认可省略块」对齐。
        val isShortReply = replyText.count { it.isLetterOrDigit() } <= SHORT_REPLY_EXEMPT_LENGTH
        checkVoiceControlBlock(
            requiresBlock = requiresBlock,
            blockCount = blockCount,
            instruct = reply.ttsVoiceInstruct(),
            isShortReply = isShortReply,
        )?.let { return it }

        // 没配审查模型时跟随快速模型，语义和记忆整理一致
        val model = settings.reviewerSetting.modelId?.let { settings.findModelById(it) }
            ?: settings.findModelById(settings.fastModelId)
            ?: return failOpen("model not found")

        val provider = model.findProvider(settings.providers)
            ?: return failOpen("provider not found")

        // summaryAsText 自带 [ROLE] 前缀；待审查的这条本身不重复带进去
        val history = conversation.currentMessages
            .filterNot { it.id == reply.id }
            .takeLast(historyLimit)
            .joinToString(separator = "\n") { it.summaryAsText(maxLength = HISTORY_MESSAGE_MAX_LENGTH) }

        val prompt = settings.reviewerSetting.prompt.fillReviewerPrompt(history = history, reply = replyText)

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
            // 调用方取消（用户停止生成、离开会话）不算审查失败，照常往上抛
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Review request failed", e)
            return failOpen(e.message ?: e.javaClass.simpleName)
        }

        return parseReviewResult(raw)
    }

    private fun failOpen(reason: String) = ReplyReviewResult(
        passed = true,
        score = MAX_SCORE,
        raw = reason,
    )
}

/**
 * 确定性校验：`<tts_voice_control>` 块必须**恰好一个**、块内非空，且块内的语气描述不能太简略。
 *
 * 纯函数、不挂起、不碰任何外部依赖，所以可以直接单测：审查链路上真正会误判的部分在 LLM 那边，
 * 这条格式约定不该也交给它去猜。块本体已经被 `TtsVoiceControlTransformer` 从正文里剥掉了，
 * 入参全部来自 metadata 回读（[ttsVoiceBlockCount] / [ttsVoiceInstruct]）。
 *
 * 四条规则按「先结构、后内容」排序，命中第一条就返回，避免同一处错误被报成两条：
 * 1. 块数量 > 1 → 判否（不管要不要块，多写标签都是违规）；
 * 2. 需要块却一个都没有 → 判否（正文短到 [SHORT_REPLY_EXEMPT_LENGTH] 以内时豁免，与注入规范的第 6 条一致）；
 * 3. 有块但块内是空白 → 判否；
 * 4. 块内文本分词后不足 [MIN_VOICE_INSTRUCT_WORDS] 个词组 → 判否，判词用 [ChineseWordSegmenter]。
 *
 * @param requiresBlock 这一轮是否要求必须有块（助手开了 TTS 本地工具且注入没被全局关掉，或 system prompt
 * 里本来就写了这条规范）。为 false 时不校验「有没有 / 空不空」，但「多出来第二个块」照样算违规：
 * 那种情况下模型是在自说自话地输出标签。
 * @param blockCount 这条回复累计剥离掉的块数量（含内容为空的块）
 * @param instruct 这条回复最终生效的语气描述，空块 / 没有块时是空串
 * @param isShortReply 正文本身短到可以豁免「必须有块」，见 [SHORT_REPLY_EXEMPT_LENGTH]。只影响规则 2：
 * 多块、块内空白、块内太简略这三条照样判否 —— 模型既然主动写了块，就没有「太短所以不用写」的理由。
 * @return null 表示通过；非 null 是判否结果，issues / suggestion 会拼进给生成模型的校正指令
 */
internal fun checkVoiceControlBlock(
    requiresBlock: Boolean,
    blockCount: Int,
    instruct: String,
    /** 正文本身短到可以豁免「必须有块」，见 [SHORT_REPLY_EXEMPT_LENGTH] */
    isShortReply: Boolean = false,
): ReplyReviewResult? {
    if (blockCount > 1) {
        return voiceBlockFailure(
            issue = "出现了 $blockCount 个 <tts_voice_control> 块，只能有 1 个",
            suggestion = "只保留正文之后那一个块，删掉多余的块；正文里不要提到标签本身",
        )
    }
    if (requiresBlock && !isShortReply && blockCount == 0 && instruct.isBlank()) {
        return voiceBlockFailure(
            issue = "缺少 <tts_voice_control> 块",
            suggestion = "在正文之后单独成段补一个 <tts_voice_control>…</tts_voice_control> 块，" +
                "块内用一段自然语言写清语气、音量、语速、音色、气息、情绪；" +
                "正文里不要出现括号、旁白或动作描写",
        )
    }
    // 有块但内容全空（例如 `<tts_voice_control>   </tts_voice_control>`）：块的位置对了，语气描述没写
    if (requiresBlock && blockCount >= 1 && instruct.isBlank()) {
        return voiceBlockFailure(
            issue = "<tts_voice_control> 块内没有内容",
            suggestion = "块内必须写一段具体的语气描述，涵盖语气、音量、语速、音色、气息、情绪六项，不能留空",
        )
    }
    // 块存在但写得太简略：TTS 只能拿到「平静地念」这种三词描述，等于没有风格指令。
    // 只判内容不判 requiresBlock —— 走到这里 blockCount > 0，而 review() 里 requiresBlock 的判定
    // 本来就含 blockCount > 0，为 true 只是必然结果；反过来「没要求块但模型自己写了块」时，
    // 写得太敷衍同样该打回。
    if (instruct.isNotBlank()) {
        val wordCount = ChineseWordSegmenter.segment(instruct).size
        if (wordCount < MIN_VOICE_INSTRUCT_WORDS) {
            return voiceBlockFailure(
                issue = "<tts_voice_control> 块内只写了 $wordCount 个词组，太简略",
                suggestion = "块内要写成完整的一句风格描述，至少 $MIN_VOICE_INSTRUCT_WORDS 个词组，例如" +
                    "「以略带慵懒和不满的语气，音量偏轻，语速不疾不徐，音色低沉柔和，" +
                    "气息里带一点叹息，情绪是关心却装作不在意」",
            )
        }
    }
    return null
}

/** 确定性校验的判否结果：0 分、raw 固定标记，方便在日志里和 LLM 审查区分开 */
private fun voiceBlockFailure(issue: String, suggestion: String) = ReplyReviewResult(
    passed = false,
    score = MIN_SCORE,
    issues = listOf(issue),
    suggestion = suggestion,
    raw = "voice-block-check",
)

/** 审查模型应该输出的 JSON，缺字段时按下面默认值走 */
@Serializable
private data class ReviewPayload(
    val pass: Boolean = false,
    // 可空（而不是 0）才能区分「模型没给分」和「模型给了 0 分」
    val score: Int? = null,
    val issues: List<String> = emptyList(),
    val suggestion: String = "",
)

/**
 * 解析审查模型的输出。
 *
 * 模型不一定会老老实实只输出 JSON：可能包了 markdown 围栏、前后带解释文字，
 * 也可能完全不守格式。所以先按 JSON 解析（取第一个 `{` 到最后一个 `}`），失败后按关键词判定，
 * 连关键词都没有时 **fail-open 通过** —— 否则模型一不守格式就会无限重新生成。
 */
internal fun parseReviewResult(raw: String): ReplyReviewResult {
    val text = raw.stripCodeFence().trim()

    val payload = extractJsonObject(text)?.let { json ->
        runCatching { JsonInstant.decodeFromString<ReviewPayload>(json) }.getOrNull()
    }
    if (payload != null) {
        val passed = payload.pass
        return ReplyReviewResult(
            passed = passed,
            score = (payload.score ?: if (passed) MAX_SCORE else MIN_SCORE).coerceIn(MIN_SCORE, MAX_SCORE),
            issues = payload.issues.map { it.trim() }.filter { it.isNotEmpty() },
            suggestion = payload.suggestion.trim(),
            raw = text,
        )
    }

    return when (keywordVerdict(text)) {
        ReviewVerdict.FAIL -> ReplyReviewResult(passed = false, score = MIN_SCORE, raw = text)
        ReviewVerdict.PASS -> ReplyReviewResult(passed = true, score = MAX_SCORE, raw = text)
        // 看不出结论就放行，把「模型不守格式」变成不重试
        ReviewVerdict.UNKNOWN -> ReplyReviewResult(passed = true, score = MAX_SCORE, raw = text)
    }
}

private enum class ReviewVerdict { PASS, FAIL, UNKNOWN }

// 显式写了 pass 的取值才算数，避免正文里的 pass 字样干扰判断
private val EXPLICIT_FAIL = Regex("""pass["']?\s*[:：=]\s*(false|no)""", RegexOption.IGNORE_CASE)
private val EXPLICIT_PASS = Regex("""pass["']?\s*[:：=]\s*(true|yes)""", RegexOption.IGNORE_CASE)
private val FAIL_WORD = Regex("""fail(ed)?|reject(ed)?|不通过|不合格|未通过""", RegexOption.IGNORE_CASE)
private val PASS_WORD = Regex("""\bpass(ed)?\b|\bok\b|通过|合格""", RegexOption.IGNORE_CASE)

private fun keywordVerdict(text: String): ReviewVerdict = when {
    // 先判否：`"pass": false` 里也含有 pass
    EXPLICIT_FAIL.containsMatchIn(text) -> ReviewVerdict.FAIL
    FAIL_WORD.containsMatchIn(text) -> ReviewVerdict.FAIL
    EXPLICIT_PASS.containsMatchIn(text) -> ReviewVerdict.PASS
    PASS_WORD.containsMatchIn(text) -> ReviewVerdict.PASS
    else -> ReviewVerdict.UNKNOWN
}

/** 去掉 ```json / ``` 围栏；没有围栏时原样返回 */
internal fun String.stripCodeFence(): String {
    var text = trim()
    if (!text.startsWith("```")) return text
    text = text.substring(3)
    // 围栏的第一行是语言标记（```json / ```JSON），不是正文
    val firstNewline = text.indexOf('\n')
    if (firstNewline >= 0 && !text.substring(0, firstNewline).contains('{')) {
        text = text.substring(firstNewline + 1)
    }
    if (text.trimEnd().endsWith("```")) {
        text = text.trimEnd().dropLast(3)
    }
    return text.trim()
}

/** 取第一个 `{` 到最后一个 `}`，容忍前后夹带的解释文字 */
private fun extractJsonObject(text: String): String? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return text.substring(start, end + 1)
}
