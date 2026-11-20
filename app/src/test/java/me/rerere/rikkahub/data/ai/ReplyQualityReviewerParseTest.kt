package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审查结果解析的回归基线：模型不会永远老老实实只输出 JSON。
 *
 * 解析不出来、看不出结论时必须 fail-open 通过，否则一次不守格式的回复会让调用方无限重新生成。
 */
class ReplyQualityReviewerParseTest {
    @Test
    fun `standard json is parsed as is`() {
        val result = parseReviewResult("""{"pass": true, "score": 85, "issues": [], "suggestion": ""}""")

        assertTrue(result.passed)
        assertEquals(85, result.score)
        assertTrue(result.issues.isEmpty())
        assertEquals("", result.suggestion)
    }

    @Test
    fun `json wrapped in markdown fence is parsed`() {
        val result = parseReviewResult(
            """
            ```json
            {"pass": true, "score": 72, "issues": ["语气干瘪"], "suggestion": "加一句语气词"}
            ```
            """.trimIndent()
        )

        assertTrue(result.passed)
        assertEquals(72, result.score)
        assertEquals(listOf("语气干瘪"), result.issues)
        assertEquals("加一句语气词", result.suggestion)
    }

    @Test
    fun `json surrounded by explanation text is extracted`() {
        val result = parseReviewResult(
            """审查如下：这条回复基本合格。
            {"pass": true, "score": 66, "issues": [], "suggestion": ""}
            以上是结论。"""
        )

        assertTrue(result.passed)
        assertEquals(66, result.score)
    }

    @Test
    fun `failing json keeps issues and suggestion`() {
        val result = parseReviewResult(
            """{"pass": false, "score": 40, "issues": ["答非所问", "重复"], "suggestion": "正面回答用户的问题"}"""
        )

        assertFalse(result.passed)
        assertEquals(40, result.score)
        assertEquals(2, result.issues.size)
        assertEquals("答非所问", result.issues[0])
        assertEquals("重复", result.issues[1])
        assertEquals("正面回答用户的问题", result.suggestion)
    }

    @Test
    fun `non json output with explicit fail verdict is rejected`() {
        val result = parseReviewResult("""这条回复不太好，"pass": false，请重写。""")

        assertFalse(result.passed)
        assertEquals(0, result.score)
    }

    @Test
    fun `non json output without any verdict fails open`() {
        val result = parseReviewResult("这条回复不错，无需修改。")

        assertTrue(result.passed)
        assertEquals(100, result.score)
    }

    @Test
    fun `blank output fails open`() {
        assertTrue(parseReviewResult("").passed)
        assertTrue(parseReviewResult("   \n  ").passed)
    }

    @Test
    fun `score outside range is clamped`() {
        assertEquals(100, parseReviewResult("""{"pass": true, "score": 150}""").score)
        assertEquals(0, parseReviewResult("""{"pass": false, "score": -5}""").score)
    }

    @Test
    fun `missing score falls back by verdict`() {
        val passed = parseReviewResult("""{"pass": true, "issues": []}""")
        val failed = parseReviewResult("""{"pass": false, "issues": ["截断"]}""")

        assertEquals(100, passed.score)
        assertEquals(0, failed.score)
        assertEquals(listOf("截断"), failed.issues)
    }
}
