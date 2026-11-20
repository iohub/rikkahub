package me.rerere.tts.model

import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.providers.STEP_INSTRUCTION_MAX_LENGTH

/** 静态指令与动态指令之间的连接符：分号 + 空格，读起来像一句连贯的风格描述 */
private const val INSTRUCTION_JOINER = "; "

/**
 * 把「静态指令」与「动态指令」合并成一次请求真正下发的语气描述。
 *
 * - 静态指令（[staticInstruction]）来自设置页配置，整段会话/整个 provider 都生效，
 *   例如 `TTSProviderSetting.RemoteTTS.instructions`、`TTSProviderSetting.Step.instruction`；
 * - 动态指令（[dynamicInstruction]）来自模型的函数调用，只对**这一次**朗读生效
 *   （见 [TTSRequest.instruct]），为空表示这次没有额外语气要求。
 *
 * 规则：
 * 1. 两边先 trim（纯空白视同空，避免拼出 `";  "` 这种脏串）；
 * 2. 都为空 → 返回空串（调用方据此决定「不下发这个字段」）；
 * 3. 只有一边非空 → 直接返回那一边；
 * 4. 都非空 → 静态在前、动态在后，用 [INSTRUCTION_JOINER] 连接
 *    （静态是长期基调，动态是本次叠加的修饰，顺序反过来会读着别扭）；
 * 5. [maxLength] 不为 null 时（有服务端长度上限的 provider，例如 StepFun 的 `instruction` ≤ 200 字符）
 *    **优先完整保留动态指令**：
 *    - 只有一边非空 → 直接 `take(maxLength)`；
 *    - 两边都非空且动态几乎占满预算（`dynamic.length >= maxLength - 连接符长度`，再拼静态也塞不进一个字）
 *      → 只下发动态；
 *    - 否则把剩余预算给静态：`maxLength - dynamic.length - 连接符长度` 个字，超出的静态从尾部砍掉。
 *
 *    为什么动态优先：动态是模型为**这一条**回复生成的语气，丢了这轮朗读就退回纯静态配置（白调一次工具）；
 *    静态是长期配置，砍掉几个字只是少一点基调修饰，影响小得多。旧实现是在合并结果上直接
 *    `take(maxLength)`，静态接近 200 字符时会把动态整段截掉，等于动态 instruct 完全失效。
 */
internal fun mergeTtsInstructions(
    staticInstruction: String,
    dynamicInstruction: String,
    maxLength: Int? = null,
): String {
    val staticPart = staticInstruction.trim()
    val dynamicPart = dynamicInstruction.trim()

    val merged = when {
        staticPart.isEmpty() && dynamicPart.isEmpty() -> ""
        staticPart.isEmpty() -> dynamicPart
        dynamicPart.isEmpty() -> staticPart
        else -> staticPart + INSTRUCTION_JOINER + dynamicPart
    }

    if (maxLength == null) return merged
    // 上限非正数时什么都下发不了；避免 take(负数) 抛 IllegalArgumentException
    if (maxLength <= 0) return ""

    return when {
        // 只有一边非空：没有取舍问题，按上限从尾部截断即可
        staticPart.isEmpty() || dynamicPart.isEmpty() -> merged.take(maxLength)
        // 动态已经吃掉几乎整个预算（连连接符都放不下）：只保留动态，别让它被静态挤掉
        dynamicPart.length >= maxLength - INSTRUCTION_JOINER.length -> dynamicPart.take(maxLength)
        else -> {
            val staticBudget = maxLength - dynamicPart.length - INSTRUCTION_JOINER.length
            // trimEnd：静态被截断处可能正好落在空白上，留着会拼出 "xxx ; 动态" 这种脏串
            val keptStatic = staticPart.take(staticBudget).trimEnd()
            if (keptStatic.isEmpty()) dynamicPart else keptStatic + INSTRUCTION_JOINER + dynamicPart
        }
    }
}

/**
 * 预览「实际会下发给 provider 的 instruct」：把设置页里配置的静态指令与本轮的动态 instruct
 * 按该 provider 自己的规则合并。
 *
 * 与真实请求体走的是同一个 [mergeTtsInstructions]，所以 UI 上显示的就是真正发出去的值，
 * 调试「为什么这一轮语气是这样」时不用再猜。
 *
 * 各 provider 的规则差异（合并函数只把这些差异集中在一处，避免 UI 和请求各写一份）：
 * - [TTSProviderSetting.RemoteTTS]：静态 [TTSProviderSetting.RemoteTTS.instructions] + 动态，
 *   无长度上限（见 `buildRemoteSpeechRequest`）；
 * - [TTSProviderSetting.Step]：静态 [TTSProviderSetting.Step.instruction] + 动态，
 *   受服务端 ≤[STEP_INSTRUCTION_MAX_LENGTH] 字符约束，超出时**优先完整保留动态**、只砍静态尾部
 *   （见 `buildStepSpeechRequest`）。
 *
 * 返回值可能为空串：表示这次请求不会下发该字段（请求体里连键都不写）。调用方想区分
 * 「不消费 instruct」与「合并后为空」时，请先判 null 再判 isBlank。
 *
 * @param dynamicInstruct 本轮模型为这一次朗读生成的动态语气，为空表示不叠加额外语气
 * @return null 表示该 provider 不消费 instruct（本仓库其余 provider 均是）
 */
fun TTSProviderSetting.mergedInstructionsFor(dynamicInstruct: String): String? = when (this) {
    is TTSProviderSetting.RemoteTTS -> mergeTtsInstructions(instructions, dynamicInstruct)
    is TTSProviderSetting.Step ->
        mergeTtsInstructions(instruction, dynamicInstruct, STEP_INSTRUCTION_MAX_LENGTH)
    else -> null
}
