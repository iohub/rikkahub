package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.tools.local.TEXT_TO_SPEECH_TOOL_NAME

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
internal fun findMissingTtsToolCall(reply: UIMessage): String? =
    if (reply.hasTtsToolCall()) null else MISSING_TTS_TOOL_CALL_REASON

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
 * 这条消息里是否发起过 `text_to_speech` 调用。
 *
 * 只看「模型有没有发起调用」，**不看执行状态**：工具还没执行完（output 为空）、执行报错都算调用过 ——
 * 这条规则约束的是模型的调用义务，执行与否不归模型决定。UI 侧（自动朗读 / 预取 / 语音模式）
 * 也用它判断「音频已经由工具负责，别再从 UI 播一遍」。
 */
internal fun UIMessage.hasTtsToolCall(): Boolean =
    getTools().any { part -> part.toolName == TEXT_TO_SPEECH_TOOL_NAME }
