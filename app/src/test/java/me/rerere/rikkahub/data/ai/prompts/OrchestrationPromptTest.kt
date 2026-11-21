package me.rerere.rikkahub.data.ai.prompts

import me.rerere.rikkahub.data.model.AgentStageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编排 prompt 的硬约束与占位符契约。
 *
 * 这些关键词一旦被删掉，编排就会退化成「两个 Agent 各说各话」：对话阶段又开始写语音块、导演阶段又开始
 * 写放之四海皆准的废话。离线单测是唯一能在不实际调用模型的情况下盯住它们的办法。
 */
class OrchestrationPromptTest {
    @Test
    fun `fill orchestration prompt replaces every placeholder`() {
        val filled = "对话：{dialogue}｜历史：{history}｜产出：{output}｜角色：{char}｜用户：{user}"
            .fillOrchestrationPrompt(
                dialogue = "今天好累",
                history = "[user]: 在吗",
                output = "以疲惫的语气",
                charName = "小满",
                userName = "阿泽",
            )

        assertEquals(
            "对话：今天好累｜历史：[user]: 在吗｜产出：以疲惫的语气｜角色：小满｜用户：阿泽",
            filled,
        )
    }

    @Test
    fun `placeholders without a value are erased instead of left visible`() {
        // output / char / user 没给值：宁可替换成空串，也不要把 `{output}` 这种字面量发给模型
        val filled = "正文：{dialogue}｜产出：{output}".fillOrchestrationPrompt(
            dialogue = "今天好累",
            history = "",
        )

        assertEquals("正文：今天好累｜产出：", filled)
    }

    @Test
    fun `dialogue preset forbids brackets and any tag block`() {
        val prompt = promptOf(OrchestrationStagePresetIds.DIALOGUE)

        assertTrue(prompt.contains("禁止括号"))
        assertTrue(prompt.contains("不要输出 <tts_voice_control>"))
        assertTrue(prompt.contains("自检"))
    }

    @Test
    fun `tts voice preset is a director brief bound to this sentence`() {
        val prompt = promptOf(OrchestrationStagePresetIds.TTS_VOICE)

        assertTrue(prompt.contains("配音导演"))
        // 必须只写「这一句」，跨句的通用描述会被逐句复用，听起来每句都一样
        assertTrue(prompt.contains("只为这一句"))
        assertTrue(prompt.contains("禁止"))
        // 明确点名要拦下的废话
        assertTrue(prompt.contains("正常的语气"))
        assertTrue(prompt.contains("适当的情感"))
        // 标签本身由调用方补，模型再写一层会被剥掉
        assertTrue(prompt.contains("不要输出 <tts_voice_control> 标签本身"))
    }

    @Test
    fun `tts voice preset carries both material placeholders`() {
        val prompt = promptOf(OrchestrationStagePresetIds.TTS_VOICE)

        assertTrue(prompt.contains("{dialogue}"))
        assertTrue(prompt.contains("{history}"))
    }

    @Test
    fun `reviewer presets carry their materials and the shared json protocol`() {
        val dialogue = reviewerPromptOf(OrchestrationReviewerPresetIds.DIALOGUE)
        val ttsVoice = reviewerPromptOf(OrchestrationReviewerPresetIds.TTS_VOICE)

        assertTrue(dialogue.contains("{dialogue}"))
        assertTrue(dialogue.contains("{history}"))
        assertTrue(dialogue.contains("pass"))
        assertTrue(dialogue.contains("score"))

        assertTrue(ttsVoice.contains("{dialogue}"))
        assertTrue(ttsVoice.contains("{output}"))
        assertTrue(ttsVoice.contains("pass"))
        assertTrue(ttsVoice.contains("score"))
        // 第 3 条标准是这套预设存在的核心理由
        assertTrue(ttsVoice.contains("空泛废话"))
    }

    @Test
    fun `stage presets declare kind and output tag consistently`() {
        val dialogue = requireNotNull(findOrchestrationStagePreset(OrchestrationStagePresetIds.DIALOGUE))
        val ttsVoice = requireNotNull(findOrchestrationStagePreset(OrchestrationStagePresetIds.TTS_VOICE))

        assertEquals(AgentStageKind.DIALOGUE, dialogue.kind)
        assertEquals("", dialogue.outputTag)
        assertEquals(AgentStageKind.BLOCK, ttsVoice.kind)
        // 与 TtsVoiceControlTransformer 的处理链路共用同一个标签
        assertEquals(ORCHESTRATION_TTS_VOICE_TAG, ttsVoice.outputTag)
        assertEquals("tts_voice_control", ttsVoice.outputTag)
    }

    @Test
    fun `unknown or missing preset ids resolve to null`() {
        assertNull(findOrchestrationStagePreset(null))
        assertNull(findOrchestrationStagePreset("removed_preset"))
        assertNull(findOrchestrationReviewerPreset(null))
        assertNull(findOrchestrationReviewerPreset(OrchestrationStagePresetIds.DIALOGUE))
    }

    @Test
    fun `every declared preset id resolves`() {
        assertTrue(findOrchestrationStagePreset(OrchestrationStagePresetIds.DIALOGUE) != null)
        assertTrue(findOrchestrationStagePreset(OrchestrationStagePresetIds.TTS_VOICE) != null)
        assertTrue(findOrchestrationReviewerPreset(OrchestrationReviewerPresetIds.DIALOGUE) != null)
        assertTrue(findOrchestrationReviewerPreset(OrchestrationReviewerPresetIds.TTS_VOICE) != null)
    }

    private fun promptOf(stagePresetId: String): String =
        requireNotNull(findOrchestrationStagePreset(stagePresetId)).prompt

    private fun reviewerPromptOf(reviewerPresetId: String): String =
        requireNotNull(findOrchestrationReviewerPreset(reviewerPresetId)).prompt
}
