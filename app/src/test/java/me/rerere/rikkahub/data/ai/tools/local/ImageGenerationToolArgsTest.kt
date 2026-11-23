package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.PromptWriter
import me.rerere.rikkahub.data.ai.image.ImageFileSaver
import me.rerere.rikkahub.data.ai.image.ImageGenerationService
import me.rerere.rikkahub.data.ai.image.RemoteGenerateRequest
import me.rerere.rikkahub.data.ai.image.RemoteImageGenerationClient
import me.rerere.rikkahub.data.datastore.ImageGenerationConfig
import me.rerere.rikkahub.data.datastore.Settings
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.data.ai.image.Result as ImageResult

/**
 * `generate_image` 工具的入参解析与参考图提示词。
 *
 * 这两块都是纯逻辑（不碰 Context、不发网络请求），所以默认就在 JVM 单测里跑：
 * 模型传错字段名/空 prompt 时，工具必须稳定地给出可读结果，而不是抛异常给上层。
 */
class ImageGenerationToolArgsTest {

    // region parseImageGenerationRequest

    @Test
    fun `full args are mapped field by field`() {
        val request = parseImageGenerationRequest(
            Json.parseToJsonElement(
                """
                {
                  "prompt": "  一只戴墨镜的柴犬  ",
                  "negative": "watermark",
                  "size": "768x768",
                  "steps": 8,
                  "seed": 2232042110076787348,
                  "batch_size": 2,
                  "reference_images": ["file:///a.png", "file:///b.png"],
                  "no_reference": true
                }
                """.trimIndent()
            )
        )

        assertEquals("一只戴墨镜的柴犬", request?.prompt)
        assertEquals("watermark", request?.negative)
        assertEquals("768x768", request?.size)
        assertEquals(8, request?.steps)
        assertEquals(2232042110076787348L, request?.seed)
        assertEquals(2, request?.batchSize)
        assertEquals(listOf("file:///a.png", "file:///b.png"), request?.referenceImages)
        assertEquals(true, request?.noReference)
    }

    @Test
    fun `prompt only leaves every optional field empty`() {
        val request = parseImageGenerationRequest(
            Json.parseToJsonElement("""{"prompt":"a cat"}""")
        )

        assertEquals("a cat", request?.prompt)
        assertNull(request?.negative)
        assertNull(request?.size)
        assertNull(request?.steps)
        assertNull(request?.seed)
        assertNull(request?.batchSize)
        assertEquals(emptyList<String>(), request?.referenceImages)
        // 缺省的 no_reference 必须是 false 而不是 null，否则会拼出 null 传给服务端
        assertEquals(false, request?.noReference)
    }

    @Test
    fun `blank or missing prompt returns null`() {
        listOf(
            """{"prompt":""}""",
            """{"prompt":"   "}""",
            """{"negative":"watermark"}""",
            """{}""",
        ).forEach { args ->
            assertNull("这些入参都应该被判定为没有 prompt：$args", parseImageGenerationRequest(Json.parseToJsonElement(args)))
        }
    }

    @Test
    fun `non object args returns null`() {
        listOf(
            "[]",
            "\"a cat\"",
            "42",
            "null",
        ).forEach { args ->
            assertNull("不是 JSON 对象的入参应该返回 null：$args", parseImageGenerationRequest(Json.parseToJsonElement(args)))
        }
    }

    @Test
    fun `reference images drop blank entries and keep primitive text`() {
        val request = parseImageGenerationRequest(
            Json.parseToJsonElement(
                """
                {"prompt":"edit","reference_images":["file:///a.png","","   ",null,42,"file:///b.png"]}
                """.trimIndent()
            )
        )

        // 空串、纯空格与 JSON null 都被丢掉；数字型 primitive 会按 content 变成 "42"（现有语义），
        // 这种非路径字符串随后会在 normalizeReferenceImage 里被丢弃，不会污染请求体
        assertEquals(listOf("file:///a.png", "42", "file:///b.png"), request?.referenceImages)
    }

    // endregion

    // region buildReferenceImagesPrompt

    @Test
    fun `reference prompt lists only local file images`() {
        val prompt = buildReferenceImagesPrompt(
            listOf(
                messageWithParts(
                    UIMessagePart.Text("你好"),
                    UIMessagePart.Image(url = "file:///data/user/0/rikkahub/files/upload/a.png"),
                ),
                messageWithParts(
                    UIMessagePart.Image(url = "https://example.com/remote.png"),
                    UIMessagePart.Image(url = "data:image/png;base64,AAA"),
                    UIMessagePart.Image(url = "file:///data/user/0/rikkahub/files/upload/b.png"),
                ),
            )
        )

        assertTrue(prompt.contains("<available_reference_images>"))
        assertTrue(prompt.contains("file:///data/user/0/rikkahub/files/upload/a.png"))
        assertTrue(prompt.contains("file:///data/user/0/rikkahub/files/upload/b.png"))
        assertFalse("远程图片不该列给模型", prompt.contains("https://example.com/remote.png"))
        assertFalse("data URL 不该列给模型", prompt.contains("data:image/png"))
        assertTrue(prompt.endsWith("</available_reference_images>"))
    }

    @Test
    fun `reference prompt keeps only the newest two local images`() {
        val prompt = buildReferenceImagesPrompt(
            (1..3).map { index ->
                messageWithParts(UIMessagePart.Image(url = "file:///upload/img$index.png"))
            }
        )

        assertFalse("最旧的一张应该被截掉", prompt.contains("img1.png"))
        listOf("img2.png", "img3.png").forEach { name ->
            assertTrue("$name 应该在提示词里", prompt.contains("file:///upload/$name"))
        }
        // 顺序保持会话里的时间顺序：越靠后越新
        assertTrue(
            "参考图顺序应该保持原始顺序",
            prompt.indexOf("img2.png") < prompt.indexOf("img3.png"),
        )
    }

    @Test
    fun `reference prompt with a fixed full body photo lists only one conversation image`() {
        val prompt = buildReferenceImagesPrompt(
            messages = (1..3).map { index ->
                messageWithParts(UIMessagePart.Image(url = "file:///upload/img$index.png"))
            },
            hasFullBodyPhoto = true,
        )

        // 全身照片固定占 image1，会话里的图只剩 1 个槽位：只留最新的 img3
        assertTrue(prompt.contains("<fixed_full_body_photo>"))
        assertTrue("要告诉模型照片固定是参考图 1", prompt.contains("ALWAYS sent as reference image 1"))
        assertTrue(prompt.contains("file:///upload/img3.png"))
        assertFalse("槽位只剩一个，img2 不该出现", prompt.contains("file:///upload/img2.png"))
        assertFalse(prompt.contains("file:///upload/img1.png"))
    }

    @Test
    fun `reference prompt still explains the fixed photo when there is no conversation image`() {
        val prompt = buildReferenceImagesPrompt(emptyList(), hasFullBodyPhoto = true)

        assertTrue("没有会话图片时也要说明固定全身照片的存在", prompt.contains("<fixed_full_body_photo>"))
        assertFalse("没有会话图片就不该有可用参考图清单", prompt.contains("<available_reference_images>"))
    }

    @Test
    fun `reference prompt is empty when there is no local image`() {
        assertEquals("", buildReferenceImagesPrompt(emptyList()))
        assertEquals(
            "",
            buildReferenceImagesPrompt(
                listOf(
                    messageWithParts(UIMessagePart.Text("只是文字")),
                    messageWithParts(UIMessagePart.Image(url = "https://example.com/a.png")),
                )
            ),
        )
    }

    // endregion

    // region tool wiring

    @Test
    fun `blank prompt short circuits before touching the service`() = runBlocking {
        val client = RecordingClient()
        val tool = buildTool(client)

        val parts = tool.execute(Json.parseToJsonElement("""{"prompt":""}"""))

        assertEquals(1, parts.size)
        val body = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        assertEquals(false, body.getValue("ok").jsonPrimitive.boolean)
        assertEquals("prompt 不能为空", body.getValue("error").jsonPrimitive.content)
        assertEquals("空 prompt 不该走到服务层", 0, client.requests.size)
    }

    @Test
    fun `tool reads settings on every call`() = runBlocking {
        val client = RecordingClient()
        var settings = settingsWith(defaultSize = "1024x1024")
        val tool = buildImageGenerationTool(
            service = serviceWith(client),
            settingsProvider = { settings },
            conversationId = null,
        )
        val args = Json.parseToJsonElement(
            """{"prompt":"a cat","steps":4,"no_reference":true}"""
        )

        tool.execute(args)
        settings = settingsWith(defaultSize = "768x768")
        tool.execute(args)

        assertEquals(2, client.requests.size)
        assertEquals("没传 size 时应该用设置里的默认尺寸", "1024x1024", client.requests[0].size)
        // 第二次调用应该读到新的设置值：settingsProvider 是每次执行时才求值的
        assertEquals("settingsProvider 应该每次执行都重新读", "768x768", client.requests[1].size)
        assertEquals(4, client.requests[1].steps)
        assertEquals(true, client.requests[1].noReference)
    }

    @Test
    fun `tool returns the service summary as a single text part`() = runBlocking {
        val client = RecordingClient()
        val tool = buildTool(client)

        val parts = tool.execute(Json.parseToJsonElement("""{"prompt":"a cat","size":"512x512"}"""))

        val body = Json.parseToJsonElement((parts.last() as UIMessagePart.Text).text).jsonObject
        assertEquals(true, body.getValue("ok").jsonPrimitive.boolean)
        assertEquals("512x512", body.getValue("size").jsonPrimitive.content)
        assertEquals(1, client.requests.size)
        assertEquals("a cat", client.requests.single().prompt)
        assertEquals(1, body.getValue("images").jsonPrimitive.int)
        assertEquals(1, parts.filterIsInstance<UIMessagePart.Image>().size)
    }

    // endregion

    private fun buildTool(client: RemoteImageGenerationClient) = buildImageGenerationTool(
        service = serviceWith(client),
        settingsProvider = { settingsWith(defaultSize = "1024x1024") },
        conversationId = null,
    )

    private fun serviceWith(client: RemoteImageGenerationClient) = ImageGenerationService(
        client = client,
        promptWriter = PromptWriter { _, _, _, _ -> null },
        fileSaver = ImageFileSaver { bytes, _, _ ->
            bytes.mapIndexed { index, _ -> "file:///tmp/generated_$index.png" }
        },
    )

    private fun settingsWith(defaultSize: String) = Settings(
        imageGenerationConfig = ImageGenerationConfig(
            baseUrl = "http://127.0.0.1:8080",
            defaultSize = defaultSize,
            enablePromptAgent = false,
        )
    )

    private fun messageWithParts(vararg parts: UIMessagePart) = UIMessage(
        role = MessageRole.USER,
        parts = parts.toList(),
    )

    /** 只记录入参的假 client：真实 HTTP 层在 JVM 单测里不需要出场。 */
    private class RecordingClient : RemoteImageGenerationClient(OkHttpClient()) {
        val requests = mutableListOf<RemoteGenerateRequest>()

        override suspend fun generate(
            config: ImageGenerationConfig,
            request: RemoteGenerateRequest,
        ): ImageResult {
            requests += request
            return ImageResult.Success(
                images = listOf(byteArrayOf(1, 2, 3)),
                fileNames = listOf("generated.png"),
                elapsedSec = 1.0,
                promptId = "fake-prompt-id",
                metaJson = null,
            )
        }
    }
}
