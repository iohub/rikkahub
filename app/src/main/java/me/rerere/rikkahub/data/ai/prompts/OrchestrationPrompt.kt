package me.rerere.rikkahub.data.ai.prompts

import me.rerere.rikkahub.data.model.AgentStageKind
import me.rerere.rikkahub.utils.applyPlaceholders

/**
 * 编排阶段预设的 ID。
 *
 * 这些字符串会随设置落盘（新建阶段时写进 `AgentStage.prompt` 的来源标记），改动等于丢掉用户已经选过的预设，
 * 只能新增不能改名。
 */
object OrchestrationStagePresetIds {
    /** 只产出对话正文、不写任何块的阶段 */
    const val DIALOGUE = "dialogue"

    /** 产出 `tts_voice_control` 语音导演指令的阶段 */
    const val TTS_VOICE = "tts_voice"
}

/**
 * 一套可选的阶段 prompt。
 *
 * @property id 对应 [OrchestrationStagePresetIds] 里的常量
 * @property name 设置页展示名
 * @property kind 这个预设适用于哪种阶段
 * @property outputTag 块阶段的输出标签；对话阶段留空
 * @property prompt prompt 正文，含 `{dialogue}` `{history}` `{char}` `{user}` 占位符
 */
data class OrchestrationStagePreset(
    val id: String,
    val name: String,
    val kind: AgentStageKind,
    val outputTag: String,
    val prompt: String,
)

/**
 * 语音导演阶段写入消息时用的标签，与 [me.rerere.rikkahub.data.ai.transformers] 里
 * `tts_voice_control` 的处理链路同源：沿用同一个标签，剥块 / 朗读才不需要额外分支。
 */
const val ORCHESTRATION_TTS_VOICE_TAG = "tts_voice_control"

/** 阶段 prompt 里被对话正文替换的占位符名 */
const val ORCHESTRATION_PLACEHOLDER_DIALOGUE = "dialogue"

/** 阶段 prompt 里被最近对话历史替换的占位符名 */
const val ORCHESTRATION_PLACEHOLDER_HISTORY = "history"

/** 审查 prompt 里被阶段产出替换的占位符名 */
const val ORCHESTRATION_PLACEHOLDER_OUTPUT = "output"

/** prompt 里被角色名替换的占位符名 */
const val ORCHESTRATION_PLACEHOLDER_CHAR = "char"

/** prompt 里被用户昵称替换的占位符名 */
const val ORCHESTRATION_PLACEHOLDER_USER = "user"

/**
 * 对话阶段的默认指令。
 *
 * 这里的硬约束与 `TtsVoiceControlPrompt` 的注入规范必须同源：编排把「写语音块」这件事整体搬给了
 * 独立的块阶段，对话阶段就必须明确禁止再写块，否则两处都会写出块，剥块逻辑会拿到两份互相矛盾的指令。
 */
private val DIALOGUE_STAGE_PROMPT = """
    你现在只负责「对话文本」，不要写任何语音控制块。

    【输出内容】
    - 纯口语，像真人说话。
    - 禁止括号、旁白、动作描写、心理描写、表情标签（如 *笑*、(叹气)、【】）。
    - 不要输出 <tts_voice_control> 或任何标签块，语音导演指令由另一个 Agent 负责。
    - 长度随语境自由，保持角色语气与关系感。

    【自检——输出前内部执行】
    1. 全文没有括号、旁白、动作或心理描写。
    2. 全文没有任何标签块。
    任一项不通过则修正后再输出。
""".trimIndent()

/**
 * 语音导演阶段的默认指令。
 *
 * 只给「这一句」写指令，不给整段回复写：TTS 每次朗读的是一条消息，跨句的通用风格描述会被逐句复用，
 * 听起来每一句都一样。所以正文里强调「与这一句的具体情境绑定」。
 */
private val TTS_VOICE_STAGE_PROMPT = """
    你是配音导演。你会收到本轮角色的对话文本，请只为这一句写语音导演指令。

    【最近对话】
    {$ORCHESTRATION_PLACEHOLDER_HISTORY}

    【本轮对话文本】
    {$ORCHESTRATION_PLACEHOLDER_DIALOGUE}

    【输出要求】
    - 只输出一小段自然的语音导演指令，不要输出 <tts_voice_control> 标签本身，不要解释、不要加引号、不要分点罗列。
    - 像导演给演员讲戏：根据这句话当下的情绪和意图，描述声音应该是什么样子，自然带出语气、节奏、气息等要素，哪些突出就写哪些，不必面面俱到。
    - 必须与「这一句」的具体情境绑定，禁止「正常的语气」「适当的情感」「用合适的声音说」这类放之四海皆准的废话。
    - 不要出现乱码或无意义字符。
""".trimIndent()

/** 全部阶段预设，[OrchestrationStagePresetIds] 里的每个常量都在这里有对应项 */
val ORCHESTRATION_STAGE_PRESETS: List<OrchestrationStagePreset> = listOf(
    OrchestrationStagePreset(
        id = OrchestrationStagePresetIds.DIALOGUE,
        name = "对话文本",
        kind = AgentStageKind.DIALOGUE,
        outputTag = "",
        prompt = DIALOGUE_STAGE_PROMPT,
    ),
    OrchestrationStagePreset(
        id = OrchestrationStagePresetIds.TTS_VOICE,
        name = "语音导演指令",
        kind = AgentStageKind.BLOCK,
        outputTag = ORCHESTRATION_TTS_VOICE_TAG,
        prompt = TTS_VOICE_STAGE_PROMPT,
    ),
)

/**
 * 阶段级审查预设的 ID。
 *
 * 单独一个命名空间：阶段审查的 prompt 只针对单个阶段的产出（块阶段还有 `{output}`），
 * 与「审查整条回复」是两回事，混用会把「审查这块产出」变成「审查整条回复」。
 */
object OrchestrationReviewerPresetIds {
    const val DIALOGUE = "dialogue_reviewer"
    const val TTS_VOICE = "tts_voice_reviewer"
}

/** 一套可选的阶段审查 prompt，[id] 对应 [OrchestrationReviewerPresetIds] 里的常量 */
data class OrchestrationReviewerPreset(
    val id: String,
    val name: String,
    val prompt: String,
)

/**
 * 对话阶段审查预设。
 *
 * 只有四条标准：格式（括号/旁白）、语体（口语）、标签（出现即不合格）、内容（空洞/出戏）。
 * 刻意不评「文笔好不好」—— 那种主观判断会把大量正常回复打回重写。
 */
private val DIALOGUE_REVIEWER_PROMPT = """
    你是对话文本审查员。只审查下面这段「对话文本」（不含语音控制块）。

    【最近对话】
    {$ORCHESTRATION_PLACEHOLDER_HISTORY}

    【待审对话文本】
    {$ORCHESTRATION_PLACEHOLDER_DIALOGUE}

    【审查标准】
    1. 是否不含括号、旁白、动作描写、心理描写、表情标签（如 *笑*、(叹气)、【】）。
    2. 是否为纯口语表达，而不是书面语或说明文。
    3. 是否出现了 <tts_voice_control> 或其它标签块（出现即不合格）。
    4. 是否空洞、出戏、与上下文无关。

    只输出 JSON，不要输出其它任何内容：
    {"pass": true/false, "score": 0-100, "issues": ["..."], "suggestion": "..."}
""".trimIndent()

/**
 * 语音导演指令审查预设。
 *
 * 第 3 条（空泛废话）是这套预设存在的核心理由：导演指令最典型的失败形态不是乱码，而是
 * 「用平静的语气说出来」这种看着像样、念出来毫无区别的句子。
 */
private val TTS_VOICE_REVIEWER_PROMPT = """
    你是语音导演指令审查员。

    【本轮对话文本】
    {$ORCHESTRATION_PLACEHOLDER_DIALOGUE}

    【待审语音导演指令】
    {$ORCHESTRATION_PLACEHOLDER_OUTPUT}

    【审查标准】
    1. 是否为空。
    2. 是否含乱码或无意义字符。
    3. 是否含空泛废话（如「正常的语气」「适当的情感」「用合适的声音说」）。
    4. 是否与本句对话的具体情境和情绪对应，而不是放之四海皆准的描述。

    只输出 JSON，不要输出其它任何内容：
    {"pass": true/false, "score": 0-100, "issues": ["..."], "suggestion": "..."}
""".trimIndent()

/** 阶段审查预设，按阶段类型排列 */
val ORCHESTRATION_REVIEWER_PRESETS: List<OrchestrationReviewerPreset> = listOf(
    OrchestrationReviewerPreset(
        id = OrchestrationReviewerPresetIds.DIALOGUE,
        name = "对话文本审查",
        prompt = DIALOGUE_REVIEWER_PROMPT,
    ),
    OrchestrationReviewerPreset(
        id = OrchestrationReviewerPresetIds.TTS_VOICE,
        name = "语音导演指令审查",
        prompt = TTS_VOICE_REVIEWER_PROMPT,
    ),
)

/** 找不到（或没设置）预设时返回 null，不抛异常 */
fun findOrchestrationStagePreset(id: String?): OrchestrationStagePreset? =
    ORCHESTRATION_STAGE_PRESETS.find { it.id == id }

/** 找不到（或没设置）预设时返回 null，不抛异常 */
fun findOrchestrationReviewerPreset(id: String?): OrchestrationReviewerPreset? =
    ORCHESTRATION_REVIEWER_PRESETS.find { it.id == id }

/**
 * 把材料填进 prompt 模板。
 *
 * 与其它后台任务的占位符替换不同：这里一律是纯替换，不做「模板里一个占位符都没有就把材料追加到末尾」的兜底。
 * 编排阶段的模板是用户自己写的，硬塞材料会破坏他刻意安排的结构；真正的兜底放在
 * `buildBlockStagePrompt` 里，那里知道自己在拼哪个阶段、缺哪些材料。
 *
 * 传空串等价于「不替换」：占位符原样留着也是可见的噪音，所以宁可替换成空。
 */
fun String.fillOrchestrationPrompt(
    dialogue: String,
    history: String,
    output: String = "",
    charName: String = "",
    userName: String = "",
): String = applyPlaceholders(
    ORCHESTRATION_PLACEHOLDER_DIALOGUE to dialogue,
    ORCHESTRATION_PLACEHOLDER_HISTORY to history,
    ORCHESTRATION_PLACEHOLDER_OUTPUT to output,
    ORCHESTRATION_PLACEHOLDER_CHAR to charName,
    ORCHESTRATION_PLACEHOLDER_USER to userName,
)
