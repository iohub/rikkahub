package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 编排模型的落盘契约。
 *
 * 这些字段一旦改名或改默认值，等于让用户已经配好的编排失效；而助手是老 JSON 反序列化出来的，
 * 所以「没有 orchestration 键的旧数据」必须能解成「不编排」。
 */
class AssistantOrchestrationTest {
    private val dialogueStage = AgentStage(
        id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        name = "对话",
        kind = AgentStageKind.DIALOGUE,
        prompt = "只写对话",
    )

    private val ttsStage = AgentStage(
        id = Uuid.parse("00000000-0000-0000-0000-000000000002"),
        name = "语音导演",
        kind = AgentStageKind.BLOCK,
        outputTag = "tts_voice_control",
        prompt = "{dialogue}",
        temperature = 0.6f,
        maxTokens = 256,
        reviewer = StageReviewerSetting(
            enabled = true,
            presetId = "tts_voice_reviewer",
            prompt = "审一下 {output}",
            maxRetry = 2,
            modelId = Uuid.parse("00000000-0000-0000-0000-000000000003"),
        ),
    )

    @Test
    fun `orchestration survives json round trip`() {
        val orchestration = AssistantOrchestration(
            enabled = true,
            stages = listOf(dialogueStage, ttsStage),
        )

        val encoded = JsonInstant.encodeToString(AssistantOrchestration.serializer(), orchestration)

        assertEquals(orchestration, JsonInstant.decodeFromString(AssistantOrchestration.serializer(), encoded))
    }

    @Test
    fun `orchestration is encoded with defaults so no field is lost`() {
        val encoded = JsonInstant.encodeToString(
            AssistantOrchestration.serializer(),
            AssistantOrchestration(enabled = true, stages = listOf(dialogueStage)),
        )

        // encodeDefaults = true：默认值也落盘，否则以后改默认值会静默改变已有配置的语义
        assertTrue(encoded.contains("\"enabled\":true"))
        assertTrue(encoded.contains("\"reviewer\""))
        assertTrue(encoded.contains("\"maxRetry\":1"))
    }

    @Test
    fun `legacy json with only the switch decodes to no stages`() {
        val decoded = JsonInstant.decodeFromString(
            AssistantOrchestration.serializer(),
            """{"enabled":true}""",
        )

        assertTrue(decoded.enabled)
        assertTrue(decoded.stages.isEmpty())
        assertNull(decoded.primaryStage())
    }

    @Test
    fun `assistant without orchestration key falls back to disabled`() {
        val assistant = JsonInstant.decodeFromString(Assistant.serializer(), """{"name":"小满"}""")

        assertFalse(assistant.orchestration.enabled)
        assertTrue(assistant.orchestration.stages.isEmpty())
        assertTrue(assistant.orchestration.blockStages().isEmpty())
    }

    @Test
    fun `stage kinds use stable serial names`() {
        assertEquals(
            "\"dialogue\"",
            JsonInstant.encodeToString(AgentStageKind.serializer(), AgentStageKind.DIALOGUE),
        )
        assertEquals(
            "\"block\"",
            JsonInstant.encodeToString(AgentStageKind.serializer(), AgentStageKind.BLOCK),
        )
        assertEquals(
            AgentStageKind.DIALOGUE,
            JsonInstant.decodeFromString(AgentStageKind.serializer(), "\"dialogue\""),
        )
    }

    @Test
    fun `only enabled stages run and they keep the configured order`() {
        val disabled = AgentStage(kind = AgentStageKind.BLOCK, outputTag = "tts_voice_control", enabled = false)
        val orchestration = AssistantOrchestration(
            enabled = true,
            stages = listOf(disabled, dialogueStage, ttsStage),
        )

        assertEquals(listOf(dialogueStage, ttsStage), orchestration.enabledStages())
        assertEquals(dialogueStage, orchestration.primaryStage())
        assertEquals(listOf(ttsStage), orchestration.blockStages())
    }

    @Test
    fun `primary stage is the first enabled dialogue stage`() {
        val second = AgentStage(kind = AgentStageKind.DIALOGUE, name = "第二个")
        val orchestration = AssistantOrchestration(stages = listOf(ttsStage, dialogueStage, second))

        assertEquals(dialogueStage, orchestration.primaryStage())
        assertNull(AssistantOrchestration().primaryStage())
        // 只有块阶段时没有主对话阶段，调用方应回退到原生的单 Agent 流程
        assertNull(AssistantOrchestration(stages = listOf(ttsStage)).primaryStage())
    }

    @Test
    fun `block stage without output tag is skipped`() {
        val orchestration = AssistantOrchestration(
            enabled = true,
            stages = listOf(
                AgentStage(kind = AgentStageKind.BLOCK, outputTag = "   "),
                AgentStage(kind = AgentStageKind.BLOCK, outputTag = "memory_note"),
            ),
        )

        // 标签为空的块写不回消息，白烧一次请求没有任何意义
        assertEquals(listOf("memory_note"), orchestration.blockStages().map { it.outputTag })
    }

    @Test
    fun `owns tag compares case insensitively and ignores blanks`() {
        val orchestration = AssistantOrchestration(
            stages = listOf(ttsStage.copy(outputTag = "  TTS_Voice_Control  ")),
        )

        assertTrue(orchestration.ownsTag("tts_voice_control"))
        assertTrue(orchestration.ownsTag("  TTS_VOICE_CONTROL  "))
        assertFalse(orchestration.ownsTag(""))
        assertFalse(orchestration.ownsTag("   "))
        assertFalse(orchestration.ownsTag("memory_note"))
    }

    @Test
    fun `disabled stage does not own its tag`() {
        val orchestration = AssistantOrchestration(
            stages = listOf(ttsStage.copy(enabled = false)),
        )

        // 关掉的阶段什么都不会产出，认领这个标签会让真正的处理方拿不到它
        assertFalse(orchestration.ownsTag("tts_voice_control"))
    }
}
