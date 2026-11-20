package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `<tts_voice_control>` 块的确定性校验。
 *
 * 块本体已经被 `TtsVoiceControlTransformer` 从正文里剥掉，`toText()` 里一个标签都看不到，
 * 所以这条格式约定必须由代码判定，不能让辅助模型去猜。这里盯的是四种情形：
 * 要求有块却没有、块内是空的、多出第二个块、以及根本不需要块时不能乱判否。
 */
class ReplyQualityReviewerVoiceBlockTest {
    @Test
    fun `missing block is rejected when required`() {
        val result = checkVoiceControlBlock(requiresBlock = true, blockCount = 0, instruct = "")

        assertNotNull(result)
        assertFalse(result!!.passed)
        assertEquals(0, result.score)
        assertTrue(result.issues.single().contains("缺少"))
        assertTrue(result.suggestion.contains("tts_voice_control"))
        assertEquals("voice-block-check", result.raw)
    }

    @Test
    fun `missing block passes when not required`() {
        // 助手没开 TTS 本地工具、system prompt 里也没有这条规范：不能要求必须有块，否则会死循环重生成
        val result = checkVoiceControlBlock(requiresBlock = false, blockCount = 0, instruct = "")

        assertNull(result)
    }

    @Test
    fun `single block with content passes`() {
        val result = checkVoiceControlBlock(requiresBlock = true, blockCount = 1, instruct = INSTRUCT)

        assertNull(result)
    }

    @Test
    fun `single block with blank content is rejected`() {
        val result = checkVoiceControlBlock(requiresBlock = true, blockCount = 1, instruct = "")

        assertNotNull(result)
        assertFalse(result!!.passed)
        assertTrue(result.issues.single().contains("没有内容"))
        assertTrue(result.suggestion.contains("不能留空"))
    }

    @Test
    fun `two blocks are rejected even when one of them has content`() {
        val result = checkVoiceControlBlock(requiresBlock = true, blockCount = 2, instruct = INSTRUCT)

        assertNotNull(result)
        assertFalse(result!!.passed)
        assertTrue(result.issues.single().contains("只能有 1 个"))
        assertTrue(result.issues.single().contains("2"))
        assertTrue(result.suggestion.contains("删掉多余的块"))
    }

    @Test
    fun `extra block is rejected even when block is not required`() {
        // 没要求有块时模型仍在正文里自说自话地输出标签，同样算违规：一个块都不该有
        val result = checkVoiceControlBlock(requiresBlock = false, blockCount = 2, instruct = INSTRUCT)

        assertNotNull(result)
        assertFalse(result!!.passed)
        assertTrue(result.issues.single().contains("只能有 1 个"))
    }

    @Test
    fun `blank instruct check only applies when block exists`() {
        // 0 块且 instruct 为空、又不需要块：两个前置条件都不满足，不能因为 instruct 空就判否
        assertNull(checkVoiceControlBlock(requiresBlock = false, blockCount = 0, instruct = ""))
        // 有块但空内容，即使不需要块也放行（没注入规范时不该挑剔内容）
        assertNull(checkVoiceControlBlock(requiresBlock = false, blockCount = 1, instruct = ""))
    }

    companion object {
        private const val INSTRUCT =
            "以极度悲伤、带着明显哭腔的语气，用较小的音量缓缓诉说，语速缓慢，声音颤抖而压抑。"
    }
}
