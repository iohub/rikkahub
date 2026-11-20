package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住 TTS 工具的 prompt 文案。
 *
 * 这些文案不参与任何逻辑，最容易被当成「顺手精简」的对象删掉或被改写成一句空话，
 * 而质量回归只会在真实对话里过很久才显现，所以在 JVM 层把关键片段钉成断言。
 * 纯文本断言，不需要 Android Context，可以在普通单测里跑。
 */
class TextToSpeechToolPromptTest {
    @Test
    fun `rules require exactly one call in every reply`() {
        assertTrue(TEXT_TO_SPEECH_RULES.contains("exactly once in EVERY reply"))
    }

    @Test
    fun `rules forbid copying the static settings instructions`() {
        assertTrue(
            TEXT_TO_SPEECH_RULES.contains("NEVER copy, translate or paraphrase the static instructions")
        )
    }

    @Test
    fun `rules carry at least six balanced examples`() {
        val open = countOccurrences(TEXT_TO_SPEECH_RULES, "<example>")
        val close = countOccurrences(TEXT_TO_SPEECH_RULES, "</example>")
        assertEquals("每个 <example> 都必须配一个 </example>", open, close)
        // 1 组英文 + 5 组中文：只有中文示例时，英文对话里模型容易把 text 也写成中文
        assertTrue("示例少于 6 个时 few-shot 效果明显变差，实际 $open 个", open >= 6)
    }

    @Test
    fun `rules include an english example`() {
        // 纯英文示例在示例区最前面：示例全是中文时，英文对话里模型容易把 text 也写成中文
        assertTrue(TEXT_TO_SPEECH_RULES.contains("My flight got cancelled"))
        assertTrue(TEXT_TO_SPEECH_RULES.contains("calm, supportive, slightly slower, practical"))
    }

    @Test
    fun `examples show both text and instruct arguments`() {
        // 等价于 Regex("""(?m)^\s*instruct: ".*""")，这里用转义写法避免 raw string 尾部的连续引号产生歧义
        val instructLines = Regex("(?m)^\\s*instruct: \".*\"").findAll(TEXT_TO_SPEECH_RULES).count()
        val textLines = Regex("(?m)^\\s*text: \".*\"").findAll(TEXT_TO_SPEECH_RULES).count()
        assertTrue("示例里要给出 instruct 的具体写法，实际 $instructLines 行", instructLines >= 5)
        assertTrue("示例里要给出 text 的具体写法，实际 $textLines 行", textLines >= 5)
    }

    @Test
    fun `rules list rejected anti-patterns`() {
        assertTrue(TEXT_TO_SPEECH_RULES.contains("Anti-patterns"))
        // 一轮内多次调用是最常见的坏行为，必须留着反例
        assertTrue(TEXT_TO_SPEECH_RULES.contains("Two calls in one reply"))
    }

    @Test
    fun `rules handle replies without prose`() {
        assertTrue(TEXT_TO_SPEECH_RULES.contains("已经帮你把会议加到日程里了。"))
    }

    @Test
    fun `rules give provider tone syntax priority inside text`() {
        assertTrue(TEXT_TO_SPEECH_RULES.contains("wins inside"))
    }

    @Test
    fun `rules specify the language to write instruct in`() {
        assertTrue(TEXT_TO_SPEECH_RULES.contains("Chinese"))
    }

    @Test
    fun `rules keep the speech language aligned with the reply`() {
        // text 的语言必须跟随回复语言，否则英文对话会被中文示例带偏
        assertTrue(TEXT_TO_SPEECH_RULES.contains("Chinese reply ->"))
        assertTrue(TEXT_TO_SPEECH_RULES.contains("Never translate the reply into another language"))
    }

    @Test
    fun `rules size stays within guard rails`() {
        // 下界防止被删成空壳，上界防止 prompt 无限膨胀挤占上下文
        assertTrue(
            "TEXT_TO_SPEECH_RULES 长度异常：${TEXT_TO_SPEECH_RULES.length}",
            TEXT_TO_SPEECH_RULES.length in 1500..6000
        )
    }

    @Test
    fun `description keeps its own instruct micro example`() {
        // 用户关掉工具提示词注入后，description 是模型唯一还能看到的指导，必须自带示例
        assertTrue(TEXT_TO_SPEECH_DESCRIPTION.contains("instruct"))
        assertTrue(TEXT_TO_SPEECH_DESCRIPTION.contains("markdown"))
        assertTrue(TEXT_TO_SPEECH_DESCRIPTION.contains("urgent, fast, clipped"))
    }

    private fun countOccurrences(text: String, needle: String): Int =
        text.windowed(needle.length, 1).count { it == needle }
}
