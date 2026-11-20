package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.prompts.DEFAULT_REVIEWER_PROMPT
import me.rerere.rikkahub.data.ai.prompts.ReviewerPresetIds
import me.rerere.rikkahub.data.ai.prompts.findReviewerPreset
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 审查配置的落盘契约：字段与默认值变化等于丢失用户数据，归一化则要在读盘后把越界取值收回来。
 */
class ReviewerSettingTest {
    @Test
    fun `reviewer setting survives json round trip`() {
        val setting = ReviewerSetting(
            enabled = true,
            presetId = ReviewerPresetIds.STRICT,
            prompt = "my prompt {history} {reply}",
            maxRetry = 2,
            modelId = Uuid.parse("00000000-0000-0000-0000-000000000042"),
        )

        val encoded = JsonInstant.encodeToString(ReviewerSetting.serializer(), setting)

        assertEquals(setting, JsonInstant.decodeFromString(ReviewerSetting.serializer(), encoded))
    }

    @Test
    fun `legacy settings json without reviewer setting falls back to default`() {
        // 旧版本导出的设置 JSON 里没有 reviewerSetting 这个键
        val legacy = """{"dynamicColor":false,"webServerPort":9090}"""

        val settings = JsonInstant.decodeFromString(Settings.serializer(), legacy)

        assertEquals(ReviewerSetting(), settings.reviewerSetting)
        assertFalse(settings.reviewerSetting.enabled)
        assertEquals(ReviewerPresetIds.BALANCED, settings.reviewerSetting.presetId)
        assertEquals(DEFAULT_REVIEWER_PROMPT, settings.reviewerSetting.prompt)
        assertEquals(1, settings.reviewerSetting.maxRetry)
        assertEquals(null, settings.reviewerSetting.modelId)
    }

    @Test
    fun `default prompt is the balanced preset`() {
        val balanced = findPreset(ReviewerPresetIds.BALANCED)

        assertEquals(balanced, DEFAULT_REVIEWER_PROMPT)
        assertEquals(ReviewerSetting().prompt, balanced)
    }

    @Test
    fun `every preset carries both placeholders`() {
        listOf(ReviewerPresetIds.STRICT, ReviewerPresetIds.BALANCED, ReviewerPresetIds.LOOSE).forEach { id ->
            val preset = findPreset(id)

            assertEquals(true, preset.contains("{history}"))
            assertEquals(true, preset.contains("{reply}"))
        }
    }

    @Test
    fun `out of range retry count is clamped`() {
        assertEquals(3, normalized(ReviewerSetting(maxRetry = 9)).maxRetry)
        assertEquals(0, normalized(ReviewerSetting(maxRetry = -2)).maxRetry)
        assertEquals(2, normalized(ReviewerSetting(maxRetry = 2)).maxRetry)
    }

    @Test
    fun `unknown preset falls back to balanced`() {
        val normalized = normalized(ReviewerSetting(presetId = "removed_preset"))

        assertEquals(ReviewerPresetIds.BALANCED, normalized.presetId)
    }

    @Test
    fun `blank prompt falls back to default prompt`() {
        val normalized = normalized(ReviewerSetting(presetId = ReviewerPresetIds.CUSTOM, prompt = "   \n "))

        assertEquals(DEFAULT_REVIEWER_PROMPT, normalized.prompt)
    }

    @Test
    fun `custom prompt and preset are preserved`() {
        val setting = ReviewerSetting(
            enabled = true,
            presetId = ReviewerPresetIds.CUSTOM,
            prompt = "只拦下答非所问的回复 {history} {reply}",
            maxRetry = 2,
        )

        assertEquals(setting, normalized(setting))
    }

    private fun normalized(setting: ReviewerSetting): ReviewerSetting =
        Settings(reviewerSetting = setting).normalized().reviewerSetting

    private fun findPreset(id: String): String =
        requireNotNull(findReviewerPreset(id)).prompt
}
