package me.rerere.rikkahub.data.ai

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

/** 审查请求的超时时间：审查只是锦上添花，卡住 20 秒就该放行 */
internal const val REVIEW_TIMEOUT_MILLIS = 20_000L

/**
 * 分数的合法区间。
 *
 * 原本与解析代码同文件时是 private，解析（[parseReviewResult]）搬到
 * [ReviewParsing.kt] 之后必须放到模块内可见，否则跨文件取不到。
 */
internal const val MIN_SCORE = 0
internal const val MAX_SCORE = 100
