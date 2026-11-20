package me.rerere.tts.model

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
 * 5. [maxLength] 不为 null 时对**合并结果**调用 `take(maxLength)`，即超出部分从尾部截断。
 *    这是给有服务端长度上限的 provider 用的（例如 StepFun 的 `instruction` ≤ 200 字符），
 *    截断放在合并之后，保证「先满足配置基调、再叠加本次语气」的语义不被破坏。
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

    return if (maxLength != null) merged.take(maxLength) else merged
}
