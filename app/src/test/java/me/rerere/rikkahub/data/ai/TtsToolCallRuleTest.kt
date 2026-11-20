package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本轮回复必须调用 text_to_speech」硬规则的回归基线。
 *
 * 规则是确定性的、只读本地工具调用记录；[ReplyQualityReviewer] 本身需要 `ProviderManager`（Android Context）
 * 与 `UsageRecorder`（Room DAO）才能构造，JVM 单测里造不出来，所以这里直接测它转发的那两个顶层函数 ——
 * 判定（[findMissingTtsToolCall]）与结论形状（[missingTtsToolCallResult]）。
 */
class TtsToolCallRuleTest {
    private fun tool(name: String, output: String? = "{}") = UIMessagePart.Tool(
        toolCallId = name,
        toolName = name,
        input = "{}",
        output = listOfNotNull(output?.let { UIMessagePart.Text(it) }),
    )

    private fun reply(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    @Test
    fun `reply that called the tts tool passes`() {
        val message = reply(
            UIMessagePart.Text("今天天气不错。"),
            tool("text_to_speech", """{"success":true,"instruct":"warm and slow"}"""),
        )

        assertNull(findMissingTtsToolCall(message))
        assertNull(missingTtsToolCallResult(message))
        assertTrue(message.hasTtsToolCall())
    }

    @Test
    fun `reply without any tool call is flagged`() {
        val message = reply(UIMessagePart.Text("今天天气不错。"))

        assertEquals(MISSING_TTS_TOOL_CALL_REASON, findMissingTtsToolCall(message))
        assertFalse(message.hasTtsToolCall())
    }

    @Test
    fun `reply with only other tools is flagged`() {
        val message = reply(
            UIMessagePart.Text("现在 10 点。"),
            tool("time_info"),
            tool("clipboard"),
        )

        // 其它工具调用顶不上 TTS：这条规则只认 text_to_speech
        assertEquals(MISSING_TTS_TOOL_CALL_REASON, findMissingTtsToolCall(message))
    }

    @Test
    fun `tool call that has not executed yet still counts`() {
        // 只关心模型是否发起过调用：output 为空（还没执行完 / 执行中）也算调用过
        val message = reply(tool("text_to_speech", output = null))

        assertNull(findMissingTtsToolCall(message))
    }

    @Test
    fun `tool name must match exactly`() {
        val message = reply(tool("text_to_speech_preview"))

        assertEquals(MISSING_TTS_TOOL_CALL_REASON, findMissingTtsToolCall(message))
    }

    @Test
    fun `rule result is a hard failure with its own raw marker`() {
        val message = reply(UIMessagePart.Text("今天天气不错。"))

        val result = requireNotNull(missingTtsToolCallResult(message)) { "规则应该命中" }

        assertEquals(false, result.passed)
        assertEquals(0, result.score)
        assertEquals(MISSING_TTS_TOOL_CALL_RULE, result.raw)
        assertEquals(listOf(MISSING_TTS_TOOL_CALL_REASON), result.issues)
        assertEquals(MISSING_TTS_TOOL_CALL_SUGGESTION, result.suggestion)
    }

    @Test
    fun `empty reply that called the tts tool is not flagged`() {
        // 纯工具调用、没有正文的回复：正文为空是因为话都说进工具里了，不该判漏调
        val message = reply(tool("text_to_speech", """{"success":true,"instruct":"calm"}"""))

        assertNull(findMissingTtsToolCall(message))
    }
}
