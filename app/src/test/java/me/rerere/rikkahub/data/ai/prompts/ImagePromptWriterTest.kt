package me.rerere.rikkahub.data.ai.prompts

import me.rerere.rikkahub.utils.applyPlaceholders
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 绘图 prompt 常量与占位符的健壮性。
 *
 * `applyPlaceholders` 只替换命中的占位符，模板里少写一个 `{xxx}` 就会把裸花括号原样发给模型，
 * 所以占位符名单也要有测试兜住。
 */
class ImagePromptWriterTest {

    private val placeholders = listOf("content", "size", "locale")

    @Test
    fun `writer prompt carries every placeholder the rewrite depends on`() {
        placeholders.forEach { name ->
            assertTrue(
                "提示词缺少 {$name} 占位符，applyPlaceholders 不会替换它",
                DEFAULT_IMAGE_PROMPT_WRITER_PROMPT.contains("{$name}"),
            )
        }
    }

    @Test
    fun `no placeholder is left behind after rendering`() {
        val rendered = DEFAULT_IMAGE_PROMPT_WRITER_PROMPT.applyPlaceholders(
            "content" to "一只戴墨镜的柴犬",
            "size" to "1024x1024",
            "locale" to "Chinese (Simplified)",
        )

        placeholders.forEach { name ->
            assertFalse("渲染后仍残留裸占位符 {$name}", rendered.contains("{$name}"))
        }
        assertTrue(rendered.contains("一只戴墨镜的柴犬"))
        assertTrue(rendered.contains("1024x1024"))
        assertTrue(rendered.contains("Chinese (Simplified)"))
    }

    @Test
    fun `default negative is non blank and names watermark`() {
        assertTrue(DEFAULT_IMAGE_NEGATIVE.isNotBlank())
        assertTrue("默认避开项里应包含 watermark", DEFAULT_IMAGE_NEGATIVE.contains("watermark"))
    }
}
