package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `<tts_voice_control>` 块的剥离与回读。
 *
 * 这些断言盯着三件容易回归的事：块不能出现在展示文本里（含流式未闭合的那一段）、语气描述能被播放链路
 * 原样读回、以及正文除了块之外一个字符都不改（尤其前导空白，ThinkTagTransformer 的锚定依赖它）。
 */
class TtsVoiceControlTransformerTest {
    @Test
    fun `closed block should be stripped and instruct stored in metadata`() {
        val result = transform("$BODY\n\n<tts_voice_control>$SAD_INSTRUCT</tts_voice_control>")

        assertEquals(BODY, result.toText())
        assertEquals(SAD_INSTRUCT, result.ttsVoiceInstruct())
        // 语气落在承载它的 Text part 上，而不是整条消息上：消息级没有别的地方放
        assertNotNull(result.parts.filterIsInstance<UIMessagePart.Text>().single().metadata)
    }

    @Test
    fun `instruct should be trimmed`() {
        val result = transform("$BODY\n\n<tts_voice_control>\n\n   $SAD_INSTRUCT   \n\n</tts_voice_control>")

        assertEquals(BODY, result.toText())
        assertEquals(SAD_INSTRUCT, result.ttsVoiceInstruct())
    }

    @Test
    fun `unclosed block should be removed while streaming`() {
        // 流式输出中途：开标签已经出现，块内描述只写了一半
        val result = transform("$BODY\n\n<tts_voice_control>以极度悲伤、带着明显哭腔的语")

        assertEquals(BODY, result.toText())
        assertEquals("", result.ttsVoiceInstruct())
    }

    @Test
    fun `last block should win when a reply has several`() {
        val result = transform(
            "$BODY\n\n<tts_voice_control>平静地念</tts_voice_control>\n\n" +
                "<tts_voice_control>急促而激动</tts_voice_control>"
        )

        assertEquals(BODY, result.toText())
        assertEquals("急促而激动", result.ttsVoiceInstruct())
    }

    @Test
    fun `last part with a block should win`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("$BODY\n\n<tts_voice_control>平静地念</tts_voice_control>"),
                UIMessagePart.Text("补充一句。\n\n<tts_voice_control>急促而激动</tts_voice_control>"),
            ),
        )

        val result = listOf(message).transformTtsVoiceControl().single()

        assertEquals("$BODY\n补充一句。", result.toText())
        assertEquals("急促而激动", result.ttsVoiceInstruct())
    }

    @Test
    fun `empty block should be dropped without metadata`() {
        // 模型偶尔会输出一个空块：块要删掉，但不能写入空语气（否则会覆盖掉旧值）
        val result = transform("$BODY\n\n<tts_voice_control>   \n  </tts_voice_control>")

        assertEquals(BODY, result.toText())
        assertEquals("", result.ttsVoiceInstruct())
        assertNull(result.parts.filterIsInstance<UIMessagePart.Text>().single().metadata)
    }

    @Test
    fun `non assistant message should be untouched`() {
        val message = UIMessage.user("$BODY\n\n<tts_voice_control>$SAD_INSTRUCT</tts_voice_control>")

        assertEquals(listOf(message), listOf(message).transformTtsVoiceControl())
    }

    @Test
    fun `leading whitespace of the visible text should be kept`() {
        // 只 trimEnd：正文前导空白属于正文，抠掉会让 ThinkTagTransformer 的 \A\s* 锚定行为变化
        val result = transform("\n\n$BODY\n\n<tts_voice_control>$SAD_INSTRUCT</tts_voice_control>")

        assertEquals("\n\n$BODY", result.toText())
    }

    @Test
    fun `message without block should stay equivalent`() {
        val message = UIMessage.assistant(BODY)

        assertEquals(listOf(message), listOf(message).transformTtsVoiceControl())
    }

    @Test
    fun `stripping should be idempotent`() {
        val first = transform("$BODY\n\n<tts_voice_control>$SAD_INSTRUCT</tts_voice_control>")

        // visualTransform 每次预览都会重跑，transform/onGenerationFinish 还会再来两遍
        val second = listOf(first).transformTtsVoiceControl().single()

        assertEquals(first, second)
        assertEquals(SAD_INSTRUCT, second.ttsVoiceInstruct())
    }

    @Test
    fun `blank lines left behind by the block should be compressed`() {
        val result = transform("$BODY\n\n<tts_voice_control>$SAD_INSTRUCT</tts_voice_control>\n\n")

        assertEquals(BODY, result.toText())
    }

    private fun transform(text: String): UIMessage =
        listOf(UIMessage.assistant(text)).transformTtsVoiceControl().single()

    companion object {
        private const val BODY = "就算你自己不想治，你也得考虑考虑别人的感受吧。我们这些朋友的感受你不在乎无所谓，那你家人呢？"
        private const val SAD_INSTRUCT =
            "以极度悲伤、带着明显哭腔的语气，用较小的音量缓缓诉说，语速缓慢，仿佛每一个字都承载着沉重的痛楚，" +
                "声音颤抖而压抑，吐字虽轻却清晰可辨，透出深藏心底的哀伤与无助。"
    }
}
