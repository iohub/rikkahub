package me.rerere.rikkahub.data.ai

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.utils.JsonInstant

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
