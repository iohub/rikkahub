package me.rerere.rikkahub.data.ai.prompts

import me.rerere.rikkahub.utils.applyPlaceholders

/**
 * 回复质量审查预设的 ID。
 *
 * 这些字符串会随设置落盘，改动等于丢掉用户已经选过的预设，只能新增不能改名。
 */
object ReviewerPresetIds {
    const val STRICT = "strict"
    const val BALANCED = "balanced"
    const val LOOSE = "loose"

    /** 用户手动改过 prompt，不再对应任何一套预设 */
    const val CUSTOM = "custom"

    /** 落盘里出现过的全部合法取值，归一化时用它判断要不要回退 */
    val KNOWN: Set<String> = setOf(STRICT, BALANCED, LOOSE, CUSTOM)
}

/** 一套可选的审查 prompt，[id] 用它对应 [ReviewerPresetIds] 里的常量 */
data class ReviewerPromptPreset(
    val id: String,
    val prompt: String,
)

/** 审查 prompt 里被对话历史替换的占位符名 */
const val REVIEWER_PLACEHOLDER_HISTORY = "history"

/** 审查 prompt 里被待审查回复替换的占位符名 */
const val REVIEWER_PLACEHOLDER_REPLY = "reply"

// 三套预设共用的审查维度，插在各自的正文里，避免几份 prompt 慢慢写得不一样
private val REVIEWER_DIMENSIONS = """
    ## 检查维度

    逐条对照下面的维度检查待审查的回复。命中任何一条都写进 issues，每条用一句不超过 20 个字的中文说明「问题是什么 + 出现在哪里」。

    1. 是否没回应之前的消息：答非所问；忽略了用户上一条消息里的问题、诉求或约束；完全跑题、自说自话。这是最严重的问题。
    2. 是否有过多重复内容：重复上一轮自己已经说过的话；同义反复地堆砌形容词；原样复读用户的话却不推进对话；同一段意思翻来覆去说了好几遍。
    3. 角色一致性：是否跳出角色、改用旁白或说明文的口吻；是否说出了与该人设身份或知识水平明显不符的内容。
    4. 是否空洞，没有任何实质内容，也没有推进对话；
""".trimIndent()

// 三套预设共用的输出协议：只输出一个 JSON 对象，调用方直接按 JSON 解析
private val REVIEWER_OUTPUT_PROTOCOL = """
    ## 输出格式（务必严格遵守）

    只输出一个 JSON 对象，不要任何解释文字、前后缀，也不要 Markdown 代码块围栏：

    {"pass": true, "score": 85, "issues": [], "suggestion": ""}

    字段说明：
    - pass：布尔值，是否通过审查
    - score：0 到 100 的整数，整体质量分
    - issues：字符串数组，逐条列出命中的问题（用中文写）；没有问题时是空数组 []
    - suggestion：一句话的中文改进建议；pass 为 true 时可以写成空字符串 ""

    所有判定、issues 与 suggestion 一律用中文输出。
""".trimIndent()

// 上下文材料，占位符由 ReplyQualityReviewer 填入
private val REVIEWER_CONTEXT_BLOCK = """
    ## 待审查材料

    <history>
    最近的对话历史，每行一条消息，格式为 [角色]: 内容。
    ${"{"}${REVIEWER_PLACEHOLDER_HISTORY}}
    </history>

    <reply>
    待审查的 AI 回复正文：
    ${"{"}${REVIEWER_PLACEHOLDER_REPLY}}
    </reply>
""".trimIndent()

private fun buildReviewerPrompt(intro: String, verdict: String): String = listOf(
    intro,
    REVIEWER_CONTEXT_BLOCK,
    REVIEWER_DIMENSIONS,
    verdict,
    REVIEWER_OUTPUT_PROTOCOL,
).joinToString(separator = "\n\n")

private val STRICT_REVIEWER_PROMPT: String = buildReviewerPrompt(
    intro = """
        你是严格的回复质量审查员。助手刚刚生成了下面这条回复，它会被直接展示给用户，还可能被语音播报出来。请判断这条回复是否足以交付，不合格就退回重写。
    """.trimIndent(),
    verdict = """
        ## 判定规则（严格）

        - 先给整体质量分 score（0 到 100 的整数）。
        - pass 规则：score 达到或超过 80，并且 issues 为空，才判 pass=true。两者缺一即 pass=false。
        - 上面 8 个维度全部零容忍：命中任意一条就 pass=false，并在 issues 里写清楚是哪一条。
        - 轻微瑕疵也算问题：一处答非所问的措辞、一处多余重复、该有语气描写却没有，都足以判 pass=false。
        - 拿不准时倾向 pass=false：宁可让助手重写一次，也不要把平庸的回复放出去。
    """.trimIndent(),
)

private val BALANCED_REVIEWER_PROMPT: String = buildReviewerPrompt(
    intro = """
        你是回复质量审查员。助手刚刚生成了下面这条回复，它会被直接展示给用户，还可能被语音播报出来。请判断这条回复是否足以交付，不合格就退回重写。
    """.trimIndent(),
    verdict = """
        ## 判定规则（平衡，默认）

        - 先给整体质量分 score（0 到 100 的整数）。
        - pass 规则：score 达到或超过 60 即判 pass=true。
        - 以下三类问题零容忍，命中任意一条一律 pass=false，不管 score 有多高：
          1. 答非所问（维度 1）
          2. 内容被截断或格式残缺（维度 5）
          3. 明确跳出角色、自称 AI（维度 4）
        - 其余维度只扣分、不直接判否：重复、语气干瘪、略显敷衍、语言不一致、虚构细节这些可以容忍轻微瑕疵，但要写进 issues 并体现在 score 里。
        - 回复只要基本回应了用户、内容完整、没有出戏，就应当 pass。不要因为风格偏好拦下回复。
    """.trimIndent(),
)

private val LOOSE_REVIEWER_PROMPT: String = buildReviewerPrompt(
    intro = """
        你是回复质量审查员。助手刚刚生成了下面这条回复，它会直接展示给用户。请只拦下那些明显不能交付的回复，其余一律放行。
    """.trimIndent(),
    verdict = """
        ## 判定规则（宽松）

        - 先给整体质量分 score（0 到 100 的整数）。
        - 默认判 pass=true。只有出现下面这类致命问题时才 pass=false：
          1. 完全没有回应问题：回复内容与用户的问题毫无关系，或者完全没理会用户的核心诉求
          2. 内容被截断：句子中途断掉，代码块、括号或引号未闭合，用户拿到的是一段残缺内容
          3. 明确跳出角色：自称「AI」「语言模型」，或明确表示自己只是个程序，破坏了人设
        - 重复、语气干瘪、略显敷衍、个别措辞不佳、语言上的小瑕疵，都不判否，最多在 issues 里提一句、扣一点 score。
        - 拿不准时判 pass=true，不要因为风格偏好拦截回复。
    """.trimIndent(),
)

/**
 * 三套预设，按从严到松排列。
 *
 * 默认用 [ReviewerPresetIds.BALANCED]：严格版会把大量正常回复打回重写，宽松版拦不住跑题。
 */
val REVIEWER_PRESETS: List<ReviewerPromptPreset> = listOf(
    ReviewerPromptPreset(id = ReviewerPresetIds.STRICT, prompt = STRICT_REVIEWER_PROMPT),
    ReviewerPromptPreset(id = ReviewerPresetIds.BALANCED, prompt = BALANCED_REVIEWER_PROMPT),
    ReviewerPromptPreset(id = ReviewerPresetIds.LOOSE, prompt = LOOSE_REVIEWER_PROMPT),
)

/**
 * 设置页默认展示、也是 `ReviewerSetting.prompt` 默认值的审查 prompt，正文与平衡版预设完全一致。
 *
 * 这里不是 `const val`：正文由几个片段拼成并做过 trimIndent，不是编译期常量，
 * 和项目里同样被设置默认值引用的 [DEFAULT_TITLE_PROMPT] 保持一致的写法。
 */
val DEFAULT_REVIEWER_PROMPT: String = BALANCED_REVIEWER_PROMPT

/** 找不到（或没设置）预设时返回 null，不抛异常 */
fun findReviewerPreset(id: String?): ReviewerPromptPreset? = REVIEWER_PRESETS.find { it.id == id }

/**
 * 把对话历史与待审查回复填进 prompt。
 *
 * 用户可能把占位符删干净了，那样模型什么都看不到，所以一个占位符都没有时把材料追加在末尾。
 */
fun String.fillReviewerPrompt(history: String, reply: String): String {
    val hasHistory = contains("{${REVIEWER_PLACEHOLDER_HISTORY}}")
    val hasReply = contains("{${REVIEWER_PLACEHOLDER_REPLY}}")
    val filled = applyPlaceholders(
        REVIEWER_PLACEHOLDER_HISTORY to history,
        REVIEWER_PLACEHOLDER_REPLY to reply,
    )
    if (hasHistory || hasReply) return filled
    return buildString {
        appendLine(filled)
        appendLine()
        appendLine("<history>")
        appendLine(history)
        appendLine("</history>")
        appendLine()
        appendLine("<reply>")
        appendLine(reply)
        appendLine("</reply>")
    }
}
