package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.TtsVoiceMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.ai.transformers.ttsVoiceBlockCount
import me.rerere.rikkahub.data.ai.transformers.ttsVoiceInstruct
import me.rerere.rikkahub.data.model.AgentStage
import me.rerere.rikkahub.data.model.AgentStageKind
import me.rerere.rikkahub.data.model.AssistantOrchestration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编排块写回的离线测试。
 *
 * 这块逻辑决定了「语气块会不会漏进正文」「可见块会不会重复」——两个都是肉眼很难在真机上发现的缺陷
 * （尤其在流式生成之后才写回），所以按纯函数单测钉死：入参是 [ProducedBlock] 列表，出参是消息。
 */
class AgentOrchestrationApplierTest {
    private fun reply(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun ttsBlock(content: String) = ProducedBlock(
        tag = ORCHESTRATION_TTS_TAG,
        content = content,
        attempts = 1,
        review = null,
    )

    private fun block(tag: String, content: String) = ProducedBlock(
        tag = tag,
        content = content,
        attempts = 1,
        review = null,
    )

    @Test
    fun `empty blocks leave the message untouched`() {
        val message = reply("今天好累")

        assertSame(message, message.applyOrchestrationBlocks(emptyList()))
    }

    @Test
    fun `tts block goes to metadata and never shows up in the text`() {
        val applied = reply("今天好累").applyOrchestrationBlocks(listOf(ttsBlock("语气疲惫，语速偏慢，尾音下沉")))

        assertEquals("今天好累", applied.toText())
        assertFalse(applied.toText().contains(ORCHESTRATION_TTS_TAG))
        assertEquals("语气疲惫，语速偏慢，尾音下沉", applied.ttsVoiceInstruct())
        assertEquals(1, applied.ttsVoiceBlockCount())
    }

    @Test
    fun `existing tts metadata is overwritten by the new block`() {
        // 模拟对话阶段自己写过一个语气块（被 TtsVoiceControlTransformer 剥进 metadata）的情况
        val withOldInstruct = reply("今天好累").copy(
            parts = listOf(
                UIMessagePart.Text(
                    text = "今天好累",
                    metadata = TtsVoiceMetadata(instruct = "旧的语气", blockCount = 1).toMetadata(),
                )
            ),
        )

        val applied = withOldInstruct.applyOrchestrationBlocks(listOf(ttsBlock("新的语气，带一点沙哑")))

        assertEquals("新的语气，带一点沙哑", applied.ttsVoiceInstruct())
        // 覆盖而不是累加：语气是「这条消息最终生效的那一份」
        assertEquals(1, applied.ttsVoiceBlockCount())
    }

    @Test
    fun `other metadata keys on the body part survive the merge`() {
        val signature = JsonObject(mapOf("thought_signature" to JsonPrimitive("sig")))
        val message = reply("今天好累").copy(
            parts = listOf(UIMessagePart.Text(text = "今天好累", metadata = signature)),
        )

        val applied = message.applyOrchestrationBlocks(listOf(ttsBlock("语气疲惫")))
        val part = applied.parts.single() as UIMessagePart.Text

        assertEquals("sig", part.metadata?.get("thought_signature")?.toString()?.trim('"'))
        assertEquals("语气疲惫", part.metadataAs<TtsVoiceMetadata>()?.instruct)
    }

    @Test
    fun `unknown tag is appended as visible text`() {
        val applied = reply("今天好累").applyOrchestrationBlocks(listOf(block("scene", "灯光昏黄的客厅")))

        assertEquals(2, applied.parts.size)
        assertEquals("今天好累", applied.toText().lines().first())
        assertTrue(applied.toText().contains("<scene>"))
        assertTrue(applied.toText().contains("灯光昏黄的客厅"))
        assertTrue(applied.toText().contains("</scene>"))
        // 未知标签没有内置消费者，只有正文一个去处，所以不该进 metadata
        assertEquals("", applied.ttsVoiceInstruct())
        assertEquals(0, applied.ttsVoiceBlockCount())
    }

    @Test
    fun `applying the same blocks twice does not duplicate visible text`() {
        val blocks = listOf(ttsBlock("语气疲惫"), block("scene", "灯光昏黄的客厅"))
        val once = reply("今天好累").applyOrchestrationBlocks(blocks)
        val twice = once.applyOrchestrationBlocks(blocks)

        assertEquals(once.parts, twice.parts)
        assertEquals(2, twice.parts.size)
        assertEquals(1, Regex("<scene>").findAll(twice.toText()).count())
    }

    @Test
    fun `blank block content is skipped`() {
        val applied = reply("今天好累").applyOrchestrationBlocks(
            listOf(ttsBlock("   "), block("scene", ""))
        )

        assertEquals("今天好累", applied.toText())
        assertEquals("", applied.ttsVoiceInstruct())
    }

    @Test
    fun `dialogue text excludes blocks written back by orchestration`() {
        val orchestration = AssistantOrchestration(
            enabled = true,
            stages = listOf(
                AgentStage(kind = AgentStageKind.DIALOGUE),
                AgentStage(kind = AgentStageKind.BLOCK, outputTag = ORCHESTRATION_TTS_TAG),
                AgentStage(kind = AgentStageKind.BLOCK, outputTag = "scene"),
            ),
        )
        val applied = reply("今天好累").applyOrchestrationBlocks(
            listOf(ttsBlock("语气疲惫"), block("scene", "灯光昏黄的客厅"))
        )

        // 审查口径里 <scene> 是编排自己追加的，不能被当成「模型在正文里写了标签」
        assertEquals("今天好累", applied.dialogueTextWithoutBlocks(orchestration))
    }

    @Test
    fun `dialogue text is unchanged when only the tts tag is owned`() {
        val orchestration = AssistantOrchestration(
            enabled = true,
            stages = listOf(AgentStage(kind = AgentStageKind.BLOCK, outputTag = ORCHESTRATION_TTS_TAG)),
        )
        val applied = reply("今天好累").applyOrchestrationBlocks(listOf(ttsBlock("语气疲惫")))

        assertEquals("今天好累", applied.dialogueTextWithoutBlocks(orchestration))
    }

    private companion object {
        /** 与 `OrchestrationPrompt.ORCHESTRATION_TTS_VOICE_TAG` 同值的字面量，避免测试里写一长串常量名 */
        const val ORCHESTRATION_TTS_TAG = "tts_voice_control"
    }
}
