package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.prompts.fillReviewerPrompt
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "ReplyQualityReviewer"

/** 审查请求的超时时间：审查只是锦上添花，卡住 20 秒就该放行 */
private const val REVIEW_TIMEOUT_MILLIS = 20_000L

/** 每条历史消息最多带进 prompt 的字数 */
private const val HISTORY_MESSAGE_MAX_LENGTH = 300

private const val MIN_SCORE = 0
private const val MAX_SCORE = 100

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
    /** 审查最后一条 assistant 回复；任何异常/超时都 fail-open（passed=true） */
    suspend fun review(
        settings: Settings,
        conversation: Conversation,
        reply: UIMessage,
        historyLimit: Int = 6,
    ): ReplyReviewResult {
        val replyText = reply.toText().trim()
        if (replyText.isEmpty()) return failOpen("empty reply")

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
private fun String.stripCodeFence(): String {
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
