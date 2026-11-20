package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.TEXT_TO_SPEECH_TOOL_NAME

/** `text_to_speech` 的正文参数名，同时也是判定「这次调用算不算数」的字段 */
private const val TTS_TEXT_ARGUMENT = "text"

/** `text_to_speech` 的动态语气参数名，重播 / 固化时要把它透传给 TTS controller */
private const val TTS_INSTRUCT_ARGUMENT = "instruct"

/**
 * 硬规则命中时的原始标记，用来和模型审查的结论区分开：
 * 日志 / trace 里看到它就知道这条不是模型判的，而是本地规则。
 */
internal const val MISSING_TTS_TOOL_CALL_RULE = "rule:missing-tts-tool-call"

/** 规则命中理由：给用户看，也是重生成时提示 toast 的文案来源 */
internal const val MISSING_TTS_TOOL_CALL_REASON =
    "本轮回复没有调用 text_to_speech，因此缺少由模型根据语境生成的动态语气指令（instruct）。"

/** 规则命中后的改进建议，与理由分开存，便于 toast 只显示短句 */
internal const val MISSING_TTS_TOOL_CALL_SUGGESTION =
    "请在回复前调用 text_to_speech：text 传去掉 Markdown 的正文，instruct 传本轮的语气。"

/**
 * 「本轮回复必须调用 text_to_speech」的确定性判定。
 *
 * 为什么不能只靠提示词：工具自带的 systemPrompt 已经写成「每轮必须调用」，但模型仍可能漏调，
 * 而漏调的后果是这轮朗读没有动态语气（只用设置里的静态配置）。这里用本地工具调用记录做硬判断，
 * **不花模型调用、不看 reviewer 总开关、不会被审查超时/解析失败 fail-open 掉**，
 * 所以它比 prompt 约束可靠，也比模型审查便宜。
 *
 * 只依赖 `UIMessage.parts` 里的 [me.rerere.ai.ui.UIMessagePart.Tool]，纯函数、无 Android 依赖，可直接 JVM 单测。
 *
 * @return 命中违规时返回用户可读原因，否则 null
 */
internal fun findMissingTtsToolCall(reply: UIMessage): String? {
    if (reply.hasTtsToolCall()) return null
    // 放行保护：这条回复里已经有**执行过**的其它工具（写文件、发消息、建日程……）。
    // 重生成会整条丢弃这条回复（连同这些工具结果），模型很可能把副作用再执行一遍；
    // 「必须调用 TTS」的收益远小于重复写操作的风险，所以这里放弃判定、直接放行。
    // 只看 isExecuted：模型发了但被拒绝 / 还没执行的工具没有副作用，仍然可以判否。
    if (reply.hasExecutedNonTtsToolCall()) return null
    return MISSING_TTS_TOOL_CALL_REASON
}

/** 这条回复里是否存在**已执行**的非 TTS 工具调用（有副作用，重生成不安全） */
private fun UIMessage.hasExecutedNonTtsToolCall(): Boolean =
    getTools().any { it.toolName != TEXT_TO_SPEECH_TOOL_NAME && it.isExecuted }

/**
 * 规则命中时拼出的不通过结论。
 *
 * 单独抽成顶层函数是为了能直接单测结论形状（passed / score / raw）：
 * [ReplyQualityReviewer] 的构造需要 `ProviderManager`（Android Context）与 `UsageRecorder`（Room DAO），
 * 在 JVM 单测里编不出来，所以真正的判定与结论都在这里，reviewer 只做转发。
 *
 * @return 命中违规时返回不通过结论，通过时返回 null
 */
internal fun missingTtsToolCallResult(reply: UIMessage): ReplyReviewResult? {
    val issue = findMissingTtsToolCall(reply) ?: return null
    return ReplyReviewResult(
        // 0 分：这条规则不参与质量打分，命中就是不合格
        passed = false,
        score = 0,
        issues = listOf(issue),
        suggestion = MISSING_TTS_TOOL_CALL_SUGGESTION,
        raw = MISSING_TTS_TOOL_CALL_RULE,
    )
}

/**
 * 这条消息里是否有**有效**的 `text_to_speech` 调用。
 *
 * 只看「模型有没有发起调用」，**不看执行状态**：工具还没执行完（output 为空）、执行报错都算调用过 ——
 * 这条规则约束的是模型的调用义务，执行与否不归模型决定。
 *
 * 但 `text` 必须非空白：模型偶尔会传空串，那种调用念不出任何东西（静默无声），
 * 算「有效调用」会让规则误放行。参数解析失败（空 input / 非法 JSON）同样视为没有有效调用。
 */
internal fun UIMessage.hasTtsToolCall(): Boolean =
    getTools().any { part ->
        part.toolName == TEXT_TO_SPEECH_TOOL_NAME && part.ttsTextArgument().isNotBlank()
    }

/**
 * 这条回复当初调用 `text_to_speech` 时模型生成的动态语气指令。
 *
 * **已由 `<tts_voice_control>` 取代**：播放 / 预取 / 固化 / 语音模式现在都读
 * `UIMessage.ttsVoiceInstruct()`（见 `data/ai/transformers/TtsVoiceControlTransformer.kt`），
 * 本函数只剩兼容旧消息的读取逻辑，随 `text_to_speech` 工具一起删除。
 *
 * 旧版重播 / 固化音频曾沿用它（音频缓存键 `TtsAudioCache.buildKey` 把 instruct 算进去了），
 * 这条语义现在由 `ttsVoiceInstruct()` 承担，这里保留只是为了让旧消息的读取路径完整。
 *
 * 取**最后一次**调用：一轮里多次调用时，后一次 `speak(flush = true)` 会打断前一次的播放，
 * 用户实际听到的就是最后一次的语气（提示词也已要求每轮只调用一次，这里只是兜底）。
 *
 * @return 没有 TTS 调用 / 参数解析失败 / 字段缺失时返回 ""（等价于「只用设置里的静态配置」）
 */
internal fun UIMessage.ttsInstruct(): String =
    getTools()
        .lastOrNull { it.toolName == TEXT_TO_SPEECH_TOOL_NAME }
        ?.ttsInstructArgument()
        .orEmpty()

/** 取这次 TTS 调用里真正要念的文本；解析失败 / 缺字段 / 空白都返回 "" */
private fun UIMessagePart.Tool.ttsTextArgument(): String = stringArgument(TTS_TEXT_ARGUMENT)

/** 取这次 TTS 调用里模型生成的动态语气；解析失败 / 缺字段返回 "" */
private fun UIMessagePart.Tool.ttsInstructArgument(): String = stringArgument(TTS_INSTRUCT_ARGUMENT)

/**
 * 从工具调用参数里读一个字符串参数。
 *
 * `input` 可能是空串（流式未完成 / 旧消息）或非法 JSON，[UIMessagePart.Tool.inputAsJson]
 * 已经把这两种情况兜成 `{}`；这里再兜一层「不是字符串字面量」的情况（例如 `"text": 123`）。
 */
private fun UIMessagePart.Tool.stringArgument(name: String): String =
    runCatching {
        (inputAsJson() as? JsonObject)
            ?.get(name)
            ?.let { it as? JsonPrimitive }
            // 只认字符串字面量：模型偶尔把 instruct 传成数字/布尔，那不是可用的语气描述
            ?.takeIf { it.isString }
            ?.content
    }.getOrNull().orEmpty().trim()
