package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语气控制提示词的硬约束。
 *
 * 这些词一旦被删掉，模型就会把块写在正文中间、一段回复输出多个块，或者在块里写 `instruct: warm, slow`
 * 这种关键词列表 —— 离线单测是唯一能在不改 prompt 效果的情况下盯住它们的办法。
 */
class TtsVoiceControlPromptTest {
    @Test
    fun `prompt should contain a paired block example`() {
        val prompt = ttsVoiceControlPrompt("")

        assertEquals(
            prompt.split("<tts_voice_control>").size - 1,
            prompt.split("</tts_voice_control>").size - 1,
        )
        assertTrue(prompt.contains("<tts_voice_control>"))
        assertTrue(prompt.contains("</tts_voice_control>"))
    }

    @Test
    fun `prompt should keep the hard constraints`() {
        val prompt = ttsVoiceControlPrompt("")

        assertTrue(prompt.contains("最多一个块"))
        assertTrue(prompt.contains("正文之后"))
        assertTrue(prompt.contains("单独成段"))
        // 块内写法：一段连贯的中文描述，而不是关键词列表
        assertTrue(prompt.contains("不加引号"))
    }

    @Test
    fun `empty guidance should not add extra content`() {
        assertEquals(TTS_VOICE_CONTROL_RULES, ttsVoiceControlPrompt(""))
        assertEquals(TTS_VOICE_CONTROL_RULES, ttsVoiceControlPrompt("   \n\n  "))
    }

    @Test
    fun `provider guidance should be appended`() {
        val guidance = "This TTS service synthesizes speech from plain text; no inline emotion tags are supported."
        val prompt = ttsVoiceControlPrompt("  $guidance  ")

        assertTrue(prompt.endsWith(guidance))
        assertTrue(prompt.contains(TTS_VOICE_CONTROL_RULES))
    }

    @Test
    fun `prompt length should stay reasonable`() {
        val length = ttsVoiceControlPrompt("").length

        assertTrue("prompt too short: $length", length >= 800)
        assertTrue("prompt too long: $length", length <= 2500)
    }
}
