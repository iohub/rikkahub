package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编排器里所有能离线测的部分：模型输出的归一化、prompt 组装、重试反馈、块写回格式。
 *
 * 这些函数的共同点是「模型行为不可控，结果必须可预期」—— 真正的请求不可测，但把请求结果收干净
 * 这一步是可测的，而这一步恰好决定了写回消息的内容对不对。
 */
class AgentOrchestratorTest {
    @Test
    fun `markdown fence around the block is stripped`() {
        val normalized = normalizeBlockOutput(
            """
            ```text
            以慵懒的语气，语速偏慢，尾音拖长
            ```
            """.trimIndent(),
            TAG,
        )

        assertEquals("以慵懒的语气，语速偏慢，尾音拖长", normalized)
    }

    @Test
    fun `outer tag added by the model is stripped ignoring case and padding`() {
        assertEquals("低沉一些", normalizeBlockOutput("<tts_voice_control>低沉一些</tts_voice_control>", TAG))
        assertEquals("低沉一些", normalizeBlockOutput("<TTS_Voice_Control>低沉一些</TTS_VOICE_CONTROL>", TAG))
        assertEquals("低沉一些", normalizeBlockOutput("< tts_voice_control >低沉一些</ tts_voice_control >", TAG))
    }

    @Test
    fun `nested wrapping is stripped layer by layer`() {
        val raw = "<tts_voice_control><tts_voice_control>低沉一些</tts_voice_control></tts_voice_control>"

        assertEquals("低沉一些", normalizeBlockOutput(raw, TAG))
    }

    @Test
    fun `fence and tag and quotes are stripped together`() {
        val raw = """
            ```
            <tts_voice_control>
            "带一点笑意的调侃，语速偏快"
            </tts_voice_control>
            ```
        """.trimIndent()

        assertEquals("带一点笑意的调侃，语速偏快", normalizeBlockOutput(raw, TAG))
    }

    @Test
    fun `surrounding quotes of either language are stripped`() {
        assertEquals("低沉一些", normalizeBlockOutput("\"低沉一些\"", TAG))
        assertEquals("低沉一些", normalizeBlockOutput("“低沉一些”", TAG))
        assertEquals("低沉一些", normalizeBlockOutput("「低沉一些」", TAG))
        assertEquals("低沉一些", normalizeBlockOutput("『低沉一些』", TAG))
    }

    @Test
    fun `blank lines are collapsed and edges trimmed`() {
        assertEquals("第一句\n\n第二句", normalizeBlockOutput("\n\n  第一句\n\n\n\n第二句 \n\n", TAG))
    }

    @Test
    fun `blank tag never strips anything`() {
        val raw = "<tts_voice_control>低沉一些</tts_voice_control>"

        // 阶段没配标签时不该按标签去切内容，否则等于替用户猜了什么该被删掉
        assertEquals(raw, normalizeBlockOutput(raw, "   "))
    }

    @Test
    fun `block stage prompt fills placeholders and appends retry feedback`() {
        val prompt = buildBlockStagePrompt(
            template = "台词：{dialogue}\n历史：{history}",
            dialogue = "你回来了",
            history = "[user]: 我回来了",
            charName = "小满",
            userName = "阿泽",
            retryFeedback = "上一轮未通过审查，需修正：\n- 太笼统",
        )

        assertTrue(prompt.contains("台词：你回来了"))
        assertTrue(prompt.contains("历史：[user]: 我回来了"))
        // 重试反馈挂在最后，前面留一个空行与材料分开
        assertTrue(prompt.endsWith("[user]: 我回来了\n\n上一轮未通过审查，需修正：\n- 太笼统"))
    }

    @Test
    fun `template without material placeholders still gets the materials appended`() {
        val prompt = buildBlockStagePrompt(
            template = "写一段语音导演指令。",
            dialogue = "你回来了",
            history = "[user]: 我回来了",
            charName = "小满",
            userName = "阿泽",
        )

        assertTrue(prompt.startsWith("写一段语音导演指令。"))
        assertTrue(prompt.contains("【最近对话】"))
        assertTrue(prompt.contains("[user]: 我回来了"))
        assertTrue(prompt.contains("【本轮对话文本】\n你回来了"))
    }

    @Test
    fun `retry feedback lists issues then suggestion`() {
        val feedback = buildStageRetryFeedback(
            ReplyReviewResult(
                passed = false,
                score = 40,
                issues = listOf(" 太笼统 ", ""),
                suggestion = "绑定这句的具体情绪",
            )
        )

        assertEquals("上一轮未通过审查，需修正：\n- 太笼统\n建议：绑定这句的具体情绪", feedback)
    }

    @Test
    fun `retry feedback with only a score still carries a reason`() {
        val feedback = buildStageRetryFeedback(ReplyReviewResult(passed = false, score = 12))

        // 模型只给了分数没给问题时，重试提示不能是空的，否则等于原样再问一遍
        assertTrue(feedback.contains("得分 12"))
    }

    @Test
    fun `block text is wrapped in the tag with trimmed content`() {
        val block = ProducedBlock(
            tag = TAG,
            content = "  以慵懒的语气\n",
            attempts = 1,
            review = null,
        )

        assertEquals("<tts_voice_control>\n以慵懒的语气\n</tts_voice_control>", formatBlockText(block))
    }

    @Test
    fun `dialogue only text skips reasoning and non text parts`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("你回来了"),
                UIMessagePart.Image("https://example.com/a.png"),
                UIMessagePart.Text("  路上小心  "),
            ),
        )

        // 块阶段只该看到真正说出来的话：图片/reasoning 这些 part 会把它带偏
        assertEquals("你回来了\n路上小心", message.dialogueOnlyText())
    }

    @Test
    fun `dialogue only text of a message without text is empty`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Notice(title = "提示", text = "生成失败")),
        )

        assertEquals("", message.dialogueOnlyText())
    }

    private companion object {
        const val TAG = "tts_voice_control"
    }
}
