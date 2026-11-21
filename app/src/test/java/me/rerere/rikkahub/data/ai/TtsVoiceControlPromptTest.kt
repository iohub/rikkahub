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
        // 生成侧必须提前知道有信息量下限，否则模型会把「平静地念」这种一句话当成合规描述
        assertTrue(prompt.contains("足够信息量"))
        assertTrue(prompt.contains("视为不合格"))
    }

    @Test
    fun `prompt should state the five word lower bound`() {
        val prompt = ttsVoiceControlPrompt("")

        // 六个维度要写全（漏掉「语气」时模型写出来的描述会缺这一项）
        assertTrue(prompt.contains("语气、音量、语速、音色、气息、情绪"))
        // 下限要写成具体数字（5 个词组），模型才能照着数；只写「要有信息量」等于没给标准
        assertTrue(prompt.contains("至少要有 5 个词组"))
    }

    @Test
    fun `prompt should mark the short confirmation as the only block exempt case`() {
        val prompt = ttsVoiceControlPrompt("")

        assertTrue(prompt.contains("可以省略整个块"))
        assertTrue(prompt.contains("这条是唯一允许不写块的情况"))
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
