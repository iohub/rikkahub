package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * 「原始日志」JSON 摊平的回归基线。
 *
 * [buildToolRawLogJson] 是纯函数（只做字段搬运，不碰 Android），所以能直接在 JVM 上测；
 * [ToolRawLogSection] 本身是 Composable，不进单测，它拿到的就是这里断言的那份 JSON。
 *
 * 断言口径对应「调试要看到原样数据」这个唯一目的：入参/输出**不解析、不改写**，
 * 计时缺失时连键都不写（避免 0ms 假象）。
 */
class ToolRawLogJsonTest {
    @Test
    fun `executed tool keeps every raw field`() {
        val json = buildToolRawLogJson(tool())

        assertEquals("chart_display", json.string("toolName"))
        assertEquals("call_1", json.string("toolCallId"))
        assertEquals("auto", json.string("approvalState"))
        assertEquals("executed", json.string("status"))
        // 入参原串一字不改：解析再序列化会把空白/键序都改掉，就不是"原始"了
        assertEquals(INPUT, json.string("inputRaw"))
        assertEquals(listOf(OUTPUT), json["outputRaw"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(STARTED_AT.toString(), json.string("startedAt"))
        assertEquals(FINISHED_AT.toString(), json.string("finishedAt"))
        assertEquals(1500L, json["durationMs"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun `half written input is kept verbatim instead of throwing`() {
        // 流式半截 JSON：工具大概率会执行失败，但调试恰恰要看模型到底写了什么
        val broken = "{\"text\": \"半截"

        val json = buildToolRawLogJson(tool(input = broken))

        assertEquals(broken, json.string("inputRaw"))
    }

    @Test
    fun `not executed tool has no timing keys`() {
        val json = buildToolRawLogJson(tool(output = emptyList(), startedAt = null, finishedAt = null))

        assertEquals(0, json["outputRaw"]!!.jsonArray.size)
        assertEquals("not_executed", json.string("status"))
        // 没跑过就没有用时，写 0ms 会让人以为工具瞬间返回了
        assertFalse(json.containsKey("startedAt"))
        assertFalse(json.containsKey("finishedAt"))
        assertFalse(json.containsKey("durationMs"))
    }

    @Test
    fun `pending approval is reported as pending rather than not executed`() {
        val json = buildToolRawLogJson(
            tool(
                output = emptyList(),
                approvalState = ToolApprovalState.Pending,
                startedAt = null,
                finishedAt = null,
            )
        )

        assertEquals("pending", json.string("status"))
        assertEquals("pending", json.string("approvalState"))
    }

    @Test
    fun `approval state carries the model visible reason`() {
        assertEquals("auto", buildToolRawLogJson(tool()).string("approvalState"))
        assertEquals(
            "approved",
            buildToolRawLogJson(tool(approvalState = ToolApprovalState.Approved)).string("approvalState")
        )

        val denied = buildToolRawLogJson(
            tool(approvalState = ToolApprovalState.Denied("user said no"))
        ).string("approvalState")!!
        assertTrue(denied.contains("denied"))
        assertTrue(denied.contains("user said no"))

        val answered = buildToolRawLogJson(
            tool(approvalState = ToolApprovalState.Answered("yes, go ahead"))
        ).string("approvalState")!!
        assertTrue(answered.contains("answered"))
        assertTrue(answered.contains("yes, go ahead"))
    }

    @Test
    fun `extra fields are merged into the top level`() {
        val extra = buildJsonObject { put("k", "v") }

        val withExtra = buildToolRawLogJson(tool(), extra)
        assertEquals("v", withExtra.string("k"))
        // 通用字段仍在：extra 是补充，不是替换整个日志
        assertEquals("executed", withExtra.string("status"))

        // 同名键以 extra 为准（工具自己最清楚该覆盖什么）
        val overridden = buildToolRawLogJson(tool(), buildJsonObject { put("status", "custom") })
        assertEquals("custom", overridden.string("status"))

        assertFalse(buildToolRawLogJson(tool()).containsKey("k"))
    }

    @Test
    fun `metadata only appears when present`() {
        // provider 没回传协议数据时不写空对象，避免和"回传了空对象"混淆
        assertFalse(buildToolRawLogJson(tool()).containsKey("metadata"))

        val metadata = buildJsonObject { put("thoughtSignature", "sig-1") }
        val json = buildToolRawLogJson(tool(metadata = metadata))
        assertEquals(metadata, json["metadata"])
    }

    @Test
    fun `non text output parts are dropped from the raw log`() {
        // 图片/文档部件只是 URL，对排查协议问题没有价值，还会把日志撑得没法读
        val json = buildToolRawLogJson(
            tool(
                output = listOf(
                    UIMessagePart.Text(OUTPUT),
                    UIMessagePart.Image("https://example.com/a.png"),
                )
            )
        )

        assertEquals(listOf(OUTPUT), json["outputRaw"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    private fun tool(
        input: String = INPUT,
        output: List<UIMessagePart> = listOf(UIMessagePart.Text(OUTPUT)),
        approvalState: ToolApprovalState = ToolApprovalState.Auto,
        metadata: JsonObject? = null,
        startedAt: Instant? = STARTED_AT,
        finishedAt: Instant? = FINISHED_AT,
    ) = UIMessagePart.Tool(
        toolCallId = "call_1",
        toolName = "chart_display",
        input = input,
        output = output,
        approvalState = approvalState,
        metadata = metadata,
        startedAt = startedAt,
        finishedAt = finishedAt,
    )

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

    private companion object {
        const val INPUT = """{"title":"你好","type":"line"}"""
        const val OUTPUT = """{"success":true}"""
        val STARTED_AT: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val FINISHED_AT: Instant = STARTED_AT + 1500.milliseconds
    }
}
