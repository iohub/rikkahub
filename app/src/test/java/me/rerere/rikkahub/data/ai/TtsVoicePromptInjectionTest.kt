package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.prompts.ORCHESTRATION_TTS_VOICE_TAG
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.AgentStage
import me.rerere.rikkahub.data.model.AgentStageKind
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantOrchestration
import me.rerere.rikkahub.data.model.PromptInjectionOverride
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置 `tts_voice_control` 规范的注入门控。
 *
 * 这条判断是「非编排路径逐字节等价」和「编排接管后不重复注入」的交点，两个方向都错得很难在真机上发现：
 * 少注入时模型根本不知道要写块（静默退化），多注入时主 agent 被两套矛盾的规则拉扯（回复里冒出裸标签）。
 */
class TtsVoicePromptInjectionTest {
    private fun assistant(
        localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo, LocalToolOption.Tts),
        override: PromptInjectionOverride = PromptInjectionOverride(),
        orchestration: AssistantOrchestration = AssistantOrchestration(),
    ) = Assistant(
        localTools = localTools,
        promptInjectionOverride = override,
        orchestration = orchestration,
    )

    private fun ttsOrchestration(enabled: Boolean = true, stageEnabled: Boolean = true) =
        AssistantOrchestration(
            enabled = enabled,
            stages = listOf(
                AgentStage(kind = AgentStageKind.DIALOGUE),
                AgentStage(
                    kind = AgentStageKind.BLOCK,
                    outputTag = ORCHESTRATION_TTS_VOICE_TAG,
                    prompt = "写语音导演指令",
                    enabled = stageEnabled,
                ),
            ),
        )

    @Test
    fun `plain assistant with tts tool still gets the built-in prompt`() {
        // 老助手（没有任何编排配置）必须与改造前完全一致
        assertTrue(assistant().injectsTtsVoicePrompt())
    }

    @Test
    fun `assistant without the tts tool never gets it`() {
        assertFalse(assistant(localTools = listOf(LocalToolOption.TimeInfo)).injectsTtsVoicePrompt())
    }

    @Test
    fun `global injection switch off disables it`() {
        assertFalse(
            assistant(override = PromptInjectionOverride(disableAll = true)).injectsTtsVoicePrompt()
        )
    }

    @Test
    fun `orchestration owning the tag takes the prompt away`() {
        // 写块已经是块阶段的事，再给主 agent 注入规范等于让它和阶段抢着写
        assertFalse(assistant(orchestration = ttsOrchestration()).injectsTtsVoicePrompt())
    }

    @Test
    fun `orchestration switched off keeps the built-in prompt`() {
        // 关键回归点：ownsTag 不看总开关，关掉编排时必须还能靠内置规范写出块
        assertTrue(assistant(orchestration = ttsOrchestration(enabled = false)).injectsTtsVoicePrompt())
    }

    @Test
    fun `disabled block stage does not take the prompt away`() {
        assertTrue(
            assistant(orchestration = ttsOrchestration(stageEnabled = false)).injectsTtsVoicePrompt()
        )
    }

    @Test
    fun `orchestration with only non-tts blocks keeps the built-in prompt`() {
        val orchestration = AssistantOrchestration(
            enabled = true,
            stages = listOf(AgentStage(kind = AgentStageKind.BLOCK, outputTag = "scene")),
        )

        assertTrue(assistant(orchestration = orchestration).injectsTtsVoicePrompt())
    }
}
