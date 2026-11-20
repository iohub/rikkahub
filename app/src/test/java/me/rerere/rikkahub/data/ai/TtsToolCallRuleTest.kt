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
 * 与 `UsageRecorder`（Room DAO）才能构造，JVM 单测里造不出来，所以这里直接测它转发的那几个顶层函数 ——
 * 判定（[findMissingTtsToolCall]）、结论形状（[missingTtsToolCallResult]）与参数回读（[hasTtsToolCall] / [ttsInstruct]）。
 */
class TtsToolCallRuleTest {
    private fun tool(
        name: String,
        output: String? = "{}",
        input: String = DEFAULT_TTS_INPUT,
    ) = UIMessagePart.Tool(
        toolCallId = name,
        toolName = name,
        input = input,
        output = listOfNotNull(output?.let { UIMessagePart.Text(it) }),
    )

    private fun ttsTool(input: String, output: String? = "{}") =
        tool(TTS_TOOL_NAME, output = output, input = input)

    private fun reply(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    @Test
    fun `reply that called the tts tool passes`() {
        val message = reply(
            UIMessagePart.Text("今天天气不错。"),
            ttsTool("""{"text":"今天天气不错。","instruct":"warm and slow"}"""),
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
    fun `reply with only pending other tools is flagged`() {
        val message = reply(
            UIMessagePart.Text("现在 10 点。"),
            // output 为空 = 还没执行：没有副作用，重生成是安全的，所以仍然判否
            tool("time_info", output = null),
            tool("clipboard", output = null),
        )

        // 其它工具调用顶不上 TTS：这条规则只认 text_to_speech
        assertEquals(MISSING_TTS_TOOL_CALL_REASON, findMissingTtsToolCall(message))
    }

    @Test
    fun `executed non-tts tool call suspends the rule`() {
        // 已经执行过的写操作类工具：重生成会丢掉工具结果、模型可能重复执行副作用
        val message = reply(
            UIMessagePart.Text("已经帮你记到日历里了。"),
            tool("create_calendar_event"),
        )

        assertNull("有副作用工具执行过时不应强制重生成", findMissingTtsToolCall(message))
        assertNull(missingTtsToolCallResult(message))
        assertFalse(message.hasTtsToolCall())
    }

    @Test
    fun `tool call that has not executed yet still counts`() {
        // 只关心模型是否发起过调用：output 为空（还没执行完 / 执行中）也算调用过
        val message = reply(ttsTool(DEFAULT_TTS_INPUT, output = null))

        assertNull(findMissingTtsToolCall(message))
    }

    @Test
    fun `tts call with blank text is flagged`() {
        // 空文本念不出任何东西（静默无声），不能算有效调用
        assertEquals(
            MISSING_TTS_TOOL_CALL_REASON,
            findMissingTtsToolCall(reply(ttsTool("""{"text":"","instruct":"calm"}"""))),
        )
        assertEquals(
            MISSING_TTS_TOOL_CALL_REASON,
            findMissingTtsToolCall(reply(ttsTool("""{"text":"   ","instruct":"calm"}"""))),
        )
        // 连 text 字段都没有（只传了 instruct）同样不算
        assertEquals(
            MISSING_TTS_TOOL_CALL_REASON,
            findMissingTtsToolCall(reply(ttsTool("""{"instruct":"calm"}"""))),
        )
        assertFalse(reply(ttsTool("""{"text":""}""")).hasTtsToolCall())
    }

    @Test
    fun `unparseable tts call input does not count`() {
        // 空 input / 非法 JSON：解析不出 text，按「没有有效调用」处理
        assertEquals(MISSING_TTS_TOOL_CALL_REASON, findMissingTtsToolCall(reply(ttsTool(""))))
        assertEquals(
            MISSING_TTS_TOOL_CALL_REASON,
            findMissingTtsToolCall(reply(ttsTool("{\"text\":\"你好\","))),
        )
    }

    @Test
    fun `tool name must match exactly`() {
        // output = null（未执行）以免撞上「副作用工具已执行就放行」的保护
        val message = reply(tool("text_to_speech_preview", output = null))

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
        val message = reply(ttsTool("""{"text":"晚安，做个好梦。","instruct":"calm"}"""))

        assertNull(findMissingTtsToolCall(message))
    }

    @Test
    fun `tts instruct is read back from the tool call`() {
        // 手动重播 / 固化要沿用这条回复当初的动态语气，缓存键才能命中同一份音频
        val message = reply(
            UIMessagePart.Text("今天天气不错。"),
            ttsTool("""{"text":"今天天气不错。","instruct":"  excited and fast  "}"""),
        )

        assertEquals("excited and fast", message.ttsInstruct())
    }

    @Test
    fun `tts instruct falls back to empty string`() {
        // 没有 TTS 调用
        assertEquals("", reply(UIMessagePart.Text("今天天气不错。")).ttsInstruct())
        // 有调用但没带 instruct
        assertEquals("", reply(ttsTool("""{"text":"你好"}""")).ttsInstruct())
        // 空 input
        assertEquals("", reply(ttsTool("")).ttsInstruct())
        // 非法 JSON
        assertEquals("", reply(ttsTool("{\"instruct\": \"calm\"")).ttsInstruct())
        // instruct 不是字符串字面量
        assertEquals("", reply(ttsTool("""{"text":"你好","instruct":123}""")).ttsInstruct())
        // 多个 TTS 调用取最后一个：后一次播放会 flush 掉前一次，实际听到的是最后一次的语气
        val multiple = reply(
            ttsTool("""{"text":"第一段","instruct":"first"}"""),
            ttsTool("""{"text":"第二段","instruct":"second"}"""),
        )
        assertEquals("second", multiple.ttsInstruct())
    }

    private companion object {
        const val TTS_TOOL_NAME = "text_to_speech"
        const val DEFAULT_TTS_INPUT = """{"text":"今天天气不错。","instruct":"warm and slow"}"""
    }
}
