package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.TtsVoiceMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语气调试面板的数据来源：一条消息里所有非空语气描述。
 *
 * 这些断言盯着三件容易回归的事：语气块所在的 part 下标要能定位（调试就靠它认「哪一段改了语气」）、
 * 空/空白语气不能凭空多出面板、以及别的 metadata（如 diff）不能被误读成语气。
 */
class TtsInstructDebugTest {
    @Test
    fun `parts without metadata yield nothing`() {
        val parts = listOf(
            UIMessagePart.Text("今天天气不错。"),
            UIMessagePart.Text("不过要下雨了。"),
        )

        assertTrue(parts.collectTtsInstructs().isEmpty())
    }

    @Test
    fun `single text part with instruct yields one entry`() {
        val parts = listOf(
            UIMessagePart.Text(text = "正文", metadata = TtsVoiceMetadata(instruct = SAD).toMetadata()),
        )

        assertEquals(listOf(TtsInstructEntry(partIndex = 0, instruct = SAD)), parts.collectTtsInstructs())
    }

    @Test
    fun `multiple parts are collected in parts order with their indexes`() {
        val parts = listOf(
            UIMessagePart.Text(text = "第一段", metadata = TtsVoiceMetadata(instruct = CALM).toMetadata()),
            UIMessagePart.Text("中间一段没有语气"),
            UIMessagePart.Text(text = "第三段", metadata = TtsVoiceMetadata(instruct = SAD).toMetadata()),
        )

        assertEquals(
            listOf(
                TtsInstructEntry(partIndex = 0, instruct = CALM),
                // 没有语气的 part 不占位，但下标仍是它在 parts 里的真实位置
                TtsInstructEntry(partIndex = 2, instruct = SAD),
            ),
            parts.collectTtsInstructs(),
        )
    }

    @Test
    fun `blank instruct is filtered out`() {
        val parts = listOf(
            UIMessagePart.Text(text = "空串", metadata = TtsVoiceMetadata(instruct = "").toMetadata()),
            UIMessagePart.Text(text = "空白", metadata = TtsVoiceMetadata(instruct = "  \n  ").toMetadata()),
            // tts_instruct 缺省的 metadata 等同于没有语气
            UIMessagePart.Text(text = "缺省", metadata = TtsVoiceMetadata().toMetadata()),
        )

        assertTrue(parts.collectTtsInstructs().isEmpty())
    }

    @Test
    fun `instruct is trimmed before display`() {
        val parts = listOf(
            UIMessagePart.Text(text = "正文", metadata = TtsVoiceMetadata(instruct = "  $SAD\n").toMetadata()),
        )

        assertEquals(SAD, parts.collectTtsInstructs().single().instruct)
    }

    @Test
    fun `other metadata is not mistaken for an instruct`() {
        val parts = listOf(
            // 同一个 metadata 槽被别的 transformer 用过（文件编辑的 diff）
            UIMessagePart.Text(text = "文件改完了", metadata = DiffMetadata(diff = "-a\n+b").toMetadata()),
        )

        assertTrue(parts.collectTtsInstructs().isEmpty())
    }

    @Test
    fun `reasoning part with instruct is collected too`() {
        // 模型把语气块写进 <think> 里时，ThinkTagTransformer 会把整段搬进 Reasoning，语气跟着落在那里
        val parts = listOf(
            UIMessagePart.Reasoning(
                reasoning = "先想想语气。",
                metadata = TtsVoiceMetadata(instruct = CALM).toMetadata(),
            ),
            UIMessagePart.Text(text = "正文", metadata = TtsVoiceMetadata(instruct = SAD).toMetadata()),
        )

        assertEquals(
            listOf(
                TtsInstructEntry(partIndex = 0, instruct = CALM),
                TtsInstructEntry(partIndex = 1, instruct = SAD),
            ),
            parts.collectTtsInstructs(),
        )
    }

    companion object {
        private const val CALM = "平静地念，语速放慢"
        private const val SAD = "以极度悲伤、带着明显哭腔的语气，用较小的音量缓缓诉说。"
    }
}
