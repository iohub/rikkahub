package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.AgentStage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantOrchestration
import me.rerere.rikkahub.data.model.StageReviewerSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 编排配置的归一化：读盘后必须把失效引用和越界取值收回来。
 *
 * 这些分支出错不会报错，只会让阶段悄悄用一个已删的模型、或者带着 9 次重试去烧请求，
 * 所以在归一化这一层把它们钉住。
 */
class OrchestrationNormalizationTest {
    private val modelA = Model(modelId = "model-a", displayName = "Model A")

    @Test
    fun `stage references to deleted models are cleared`() {
        val deletedModelId = Uuid.random()
        val orchestration = AssistantOrchestration(
            enabled = true,
            stages = listOf(
                AgentStage(
                    modelId = modelA.id,
                    reviewer = StageReviewerSetting(enabled = true, modelId = deletedModelId),
                ),
            ),
        )

        val stage = stagesOf(orchestration).single()

        assertEquals(modelA.id, stage.modelId)
        // 清成 null 就退回「跟随会话模型 / 快速模型」，而不是留一个找不到的 ID
        assertNull(stage.reviewer.modelId)
    }

    @Test
    fun `duplicate stage ids are dropped keeping the first one`() {
        val id = Uuid.random()
        val orchestration = AssistantOrchestration(
            stages = listOf(
                AgentStage(id = id, name = "第一个"),
                AgentStage(id = id, name = "第二个"),
            ),
        )

        val stages = stagesOf(orchestration)

        assertEquals(1, stages.size)
        assertEquals("第一个", stages.single().name)
    }

    @Test
    fun `out of range stage values are clamped`() {
        val orchestration = AssistantOrchestration(
            stages = listOf(
                AgentStage(temperature = 5f, maxTokens = 0, reviewer = StageReviewerSetting(maxRetry = 9)),
                AgentStage(temperature = -1f, maxTokens = 512, reviewer = StageReviewerSetting(maxRetry = -2)),
            ),
        )

        val stages = stagesOf(orchestration)

        assertEquals(2f, stages[0].temperature)
        // 0 个 token 等于让模型什么都不说，按「没设置」处理
        assertNull(stages[0].maxTokens)
        assertEquals(3, stages[0].reviewer.maxRetry)

        assertEquals(0f, stages[1].temperature)
        assertEquals(512, stages[1].maxTokens)
        assertEquals(0, stages[1].reviewer.maxRetry)
    }

    @Test
    fun `legal stage values are preserved`() {
        val orchestration = AssistantOrchestration(
            enabled = true,
            stages = listOf(
                AgentStage(
                    modelId = modelA.id,
                    temperature = 0.7f,
                    maxTokens = 256,
                    reviewer = StageReviewerSetting(enabled = true, maxRetry = 2, modelId = modelA.id),
                ),
            ),
        )

        assertEquals(orchestration, stagesOf(orchestration).let { orchestration.copy(stages = it) })
    }

    private fun stagesOf(orchestration: AssistantOrchestration): List<AgentStage> =
        settingsWith(orchestration).normalized().assistants.first().orchestration.stages

    private fun settingsWith(orchestration: AssistantOrchestration) = Settings(
        assistants = listOf(Assistant(name = "小满", orchestration = orchestration)),
        providers = listOf(ProviderSetting.OpenAI(models = listOf(modelA))),
    )
}
