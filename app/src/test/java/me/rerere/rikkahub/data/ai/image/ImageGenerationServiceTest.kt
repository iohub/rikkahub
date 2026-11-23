package me.rerere.rikkahub.data.ai.image

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ImagePromptAgent
import me.rerere.rikkahub.data.ai.PromptWriter
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_IMAGE_NEGATIVE
import me.rerere.rikkahub.data.datastore.ImageGenerationConfig
import me.rerere.rikkahub.data.datastore.Settings
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.image.Result as ImageResult

/**
 * 出图编排层的功能测试：prompt 改写 → 参考图归一化 → 远程调用 → 落盘 → 生成给模型看的 summary。
 *
 * 全部依赖都用假件替换（仓库没有 mockk/Robolectric，延续手写 fake 的既有范式）：
 * 这里验证的是「参数怎么拼、失败怎么表达、超时怎么办」，真实 HTTP 由 E2E 测试覆盖。
 */
class ImageGenerationServiceTest {

    // 只要 8 字节的 PNG 魔数就够证明字节是原样透传的
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun `success returns image parts plus a json summary and sends the mapped request`() = runTest {
        val client = FakeClient { _, _ ->
            ImageResult.Success(
                images = listOf(pngBytes, pngBytes),
                fileNames = listOf("a.png", "b.png"),
                elapsedSec = 12.5,
                promptId = "pid-1",
                metaJson = """{"seed":42}""",
            )
        }
        val writer = FakeWriter(ImagePromptAgent.Draft("rewritten prompt", "watermark, blurry"))
        val saver = FakeSaver(listOf("file:///tmp/upload/a.png", "file:///tmp/upload/b.png"))
        val service = ImageGenerationService(client, writer, saver)

        val parts = service.generate(
            // 这个用例要验证「模型指定尺寸生效」的链路，因此显式关掉强制默认尺寸
            settings = settings(forceDefaultSize = false),
            conversationId = null,
            request = ImageGenerationService.Request(
                prompt = "一只戴墨镜的柴犬",
                size = "768x768",
                steps = 4,
                seed = 42L,
                batchSize = 1,
            ),
        )

        // 返回结构：N 张图 + 1 条 summary
        assertEquals(2, parts.filterIsInstance<UIMessagePart.Image>().size)
        assertEquals(
            listOf("file:///tmp/upload/a.png", "file:///tmp/upload/b.png"),
            parts.filterIsInstance<UIMessagePart.Image>().map { it.url },
        )
        assertTrue("最后一条必须是文本 summary", parts.last() is UIMessagePart.Text)

        // 改写 agent 收到的是用户原文与归一化后的尺寸
        assertEquals(1, writer.calls.size)
        assertEquals("一只戴墨镜的柴犬", writer.calls.single().request)
        assertEquals("768x768", writer.calls.single().size)

        // 发给远程服务的是改写后的 prompt 与独立的 negative
        val remote = client.requests.single()
        assertEquals("rewritten prompt", remote.prompt)
        assertEquals("watermark, blurry", remote.negative)
        assertEquals("768x768", remote.size)
        assertEquals(4, remote.steps)
        assertEquals(42L, remote.seed)
        assertEquals(1, remote.batchSize)
        // 没有参考图时必须显式告诉服务端别去看图，否则服务端可能拿上一轮的图做 img2img
        assertEquals(true, remote.noReference)
        assertNull(remote.images)
        assertEquals(1200, remote.timeout)

        // 落盘拿到的是服务端原始字节，文件名与 mime 固定
        assertEquals(2, saver.savedBytes!!.size)
        assertArrayEquals(pngBytes, saver.savedBytes!!.first())
        assertTrue(saver.displayName!!.startsWith("generated_"))
        assertTrue(saver.displayName!!.endsWith(".png"))
        assertEquals("image/png", saver.mimeType)

        val summary = summaryOf(parts)
        assertEquals(true, summary.getValue("ok").jsonPrimitive.boolean)
        assertEquals("rewritten prompt", summary.getValue("prompt").jsonPrimitive.content)
        assertEquals("watermark, blurry", summary.getValue("negative").jsonPrimitive.content)
        assertEquals("768x768", summary.getValue("size").jsonPrimitive.content)
        assertEquals(4, summary.getValue("steps").jsonPrimitive.int)
        assertEquals(42L, summary.getValue("seed").jsonPrimitive.long)
        assertEquals("pid-1", summary.getValue("prompt_id").jsonPrimitive.content)
        assertEquals(12.5, summary.getValue("elapsed_sec").jsonPrimitive.double, 1e-9)
        assertEquals(true, summary.getValue("prompt_rewritten").jsonPrimitive.boolean)
        assertEquals(2, summary.getValue("images").jsonPrimitive.int)
    }

    @Test
    fun `disabled prompt agent never calls the writer`() = runTest {
        val client = FakeClient { _, _ -> ImageResult.Success(listOf(pngBytes), listOf("a.png"), 1.0, "pid", null) }
        val writer = FakeWriter(ImagePromptAgent.Draft("不该被用到", "不该被用到"))
        val service = ImageGenerationService(client, writer, FakeSaver(listOf("file:///tmp/a.png")))

        val parts = service.generate(
            settings = settings(enablePromptAgent = false),
            conversationId = null,
            request = ImageGenerationService.Request(prompt = "原始 prompt"),
        )

        assertEquals("关掉改写后不应再调模型", 0, writer.calls.size)
        assertEquals("原始 prompt", client.requests.single().prompt)
        assertEquals(false, summaryOf(parts).getValue("prompt_rewritten").jsonPrimitive.boolean)
    }

    @Test
    fun `writer returning null falls back to the user prompt and still generates`() = runTest {
        val client = FakeClient { _, _ -> ImageResult.Success(listOf(pngBytes), listOf("a.png"), 1.0, "pid", null) }
        val writer = FakeWriter(null)
        val saver = FakeSaver(listOf("file:///tmp/a.png"))
        val service = ImageGenerationService(client, writer, saver)

        val parts = service.generate(
            settings = settings(),
            conversationId = null,
            request = ImageGenerationService.Request(prompt = "原始 prompt"),
        )

        assertEquals(1, writer.calls.size)
        val remote = client.requests.single()
        assertEquals("模型不可用时要 fail-open 用原文", "原始 prompt", remote.prompt)
        assertEquals(DEFAULT_IMAGE_NEGATIVE, remote.negative)
        assertEquals(1, saver.savedBytes!!.size)
        val summary = summaryOf(parts)
        assertEquals(false, summary.getValue("prompt_rewritten").jsonPrimitive.boolean)
        assertEquals(true, summary.getValue("ok").jsonPrimitive.boolean)
    }

    @Test
    fun `blank base url explains the missing configuration without calling the client`() = runTest {
        val client = FakeClient { _, _ -> ImageResult.Success(listOf(pngBytes), listOf("a.png"), 1.0, "pid", null) }
        val writer = FakeWriter(ImagePromptAgent.Draft("rewritten", "neg"))
        val saver = FakeSaver(listOf("file:///tmp/a.png"))
        val service = ImageGenerationService(client, writer, saver)

        val parts = service.generate(
            settings = settings(baseUrl = "   "),
            conversationId = null,
            request = ImageGenerationService.Request(prompt = "一只猫"),
        )

        assertEquals(1, parts.size)
        val body = summaryOf(parts)
        assertEquals(false, body.getValue("ok").jsonPrimitive.boolean)
        assertEquals(IMAGE_SERVICE_NOT_CONFIGURED, body.getValue("error").jsonPrimitive.content)
        assertTrue("提示里要告诉用户去设置里填地址", body.getValue("error").jsonPrimitive.content.contains("未配置"))
        assertEquals("没配地址时不该发请求", 0, client.requests.size)
        assertEquals("没配地址时也不该先花一次模型调用", 0, writer.calls.size)
        assertNull(saver.savedBytes)
    }

    @Test
    fun `remote failure keeps the reason the http code and the detail`() = runTest {
        val client = FakeClient { _, _ ->
            ImageResult.Failure("资源编排失败：start comfy 超时；服务正忙，稍后重试", 409, """{"step":"start:comfy"}""")
        }
        val saver = FakeSaver(listOf("file:///tmp/a.png"))
        val service = ImageGenerationService(client, FakeWriter(null), saver)

        val parts = service.generate(
            settings = settings(),
            conversationId = null,
            request = ImageGenerationService.Request(prompt = "一只猫"),
        )

        assertEquals(1, parts.size)
        val body = summaryOf(parts)
        assertEquals(false, body.getValue("ok").jsonPrimitive.boolean)
        assertEquals(409, body.getValue("http_code").jsonPrimitive.int)
        assertTrue(body.getValue("error").jsonPrimitive.content.contains("服务正忙"))
        assertTrue(body.getValue("detail").jsonPrimitive.content.contains("start:comfy"))
        assertNull("失败时不该落盘", saver.savedBytes)
    }

    @Test
    fun `usable data reference is forwarded while missing local files are dropped`() = runTest {
        val client = FakeClient { _, _ -> ImageResult.Success(listOf(pngBytes), listOf("a.png"), 1.0, "pid", null) }
        val service = ImageGenerationService(client, FakeWriter(null), FakeSaver(listOf("file:///tmp/a.png")))

        service.generate(
            settings = settings(),
            conversationId = null,
            request = ImageGenerationService.Request(
                prompt = "把这张图改成水彩",
                // JVM 单测里 file:// 分支依赖 android.net.Uri（Stub），所以用等价的裸绝对路径覆盖「读不到就跳过」
                referenceImages = listOf("data:image/png;base64,AAA", "/nope/missing.png"),
            ),
        )

        val remote = client.requests.single()
        assertEquals(listOf("data:image/png;base64,AAA"), remote.images)
        assertNull("有可用参考图时不该发 no_reference", remote.noReference)
    }

    @Test
    fun `no usable reference forces no_reference true`() = runTest {
        val client = FakeClient { _, _ -> ImageResult.Success(listOf(pngBytes), listOf("a.png"), 1.0, "pid", null) }
        val service = ImageGenerationService(client, FakeWriter(null), FakeSaver(listOf("file:///tmp/a.png")))

        service.generate(
            settings = settings(),
            conversationId = null,
            request = ImageGenerationService.Request(
                prompt = "一只猫",
                referenceImages = listOf("/nope/missing.png", "  ", "relative/path.png"),
            ),
        )

        val remote = client.requests.single()
        assertNull(remote.images)
        assertEquals("一张可用的参考图都没有时必须显式禁用参考图", true, remote.noReference)
    }

    @Test
    fun `full body photo takes image 1 so only one user reference is sent`() = runTest {
        val client = FakeClient { _, _ -> ImageResult.Success(listOf(pngBytes), listOf("a.png"), 1.0, "pid", null) }
        val service = ImageGenerationService(client, FakeWriter(null), FakeSaver(listOf("file:///tmp/a.png")))

        service.generate(
            settings = settings(),
            conversationId = null,
            request = ImageGenerationService.Request(
                prompt = "把我的全身照换成海边背景",
                referenceImages = listOf("data:image/png;base64,AAA", "data:image/png;base64,BBB"),
                fullBodyPhotoId = "photo-1",
            ),
        )

        val remote = client.requests.single()
        // workflow 只有 image1/image2：全身照片固定占 image1，用户自己的参考图只剩一个槽位
        assertEquals(listOf("data:image/png;base64,AAA"), remote.images)
        assertEquals("photo-1", remote.fullBodyPhotoId)
        assertNull("有全身照片时不是纯文生图", remote.noReference)
    }

    @Test
    fun `without a full body photo two user references still fit`() = runTest {
        val client = FakeClient { _, _ -> ImageResult.Success(listOf(pngBytes), listOf("a.png"), 1.0, "pid", null) }
        val service = ImageGenerationService(client, FakeWriter(null), FakeSaver(listOf("file:///tmp/a.png")))

        service.generate(
            settings = settings(),
            conversationId = null,
            request = ImageGenerationService.Request(
                prompt = "把这两张图合成一张",
                referenceImages = listOf("data:image/png;base64,AAA", "data:image/png;base64,BBB"),
            ),
        )

        val remote = client.requests.single()
        assertEquals(
            listOf("data:image/png;base64,AAA", "data:image/png;base64,BBB"),
            remote.images,
        )
        assertNull("没绑照片时不该发 full_body_photo_id", remote.fullBodyPhotoId)
    }

    @Test
    fun `full body photo alone is not treated as a text to image request`() = runTest {
        val client = FakeClient { _, _ -> ImageResult.Success(listOf(pngBytes), listOf("a.png"), 1.0, "pid", null) }
        val service = ImageGenerationService(client, FakeWriter(null), FakeSaver(listOf("file:///tmp/a.png")))

        service.generate(
            settings = settings(),
            conversationId = null,
            request = ImageGenerationService.Request(prompt = "画一张我的全身像", fullBodyPhotoId = "photo-1"),
        )

        val remote = client.requests.single()
        assertNull("这次没有会话里的参考图", remote.images)
        // 全身照片本身就是喂给 image1 的图，置 true 会让服务端把它忽略掉
        assertNull("有全身照片时不能发 no_reference=true", remote.noReference)
        assertEquals("photo-1", remote.fullBodyPhotoId)
    }

    @Test
    fun `timeout is reported as a text part instead of hanging`() = runTest {
        val client = FakeClient { _, _ ->
            // runTest 的虚拟时间会直接跳到超时点，不会真的等 200 秒
            delay(200_000)
            ImageResult.Failure("不该走到这里", null, null)
        }
        val saver = FakeSaver(listOf("file:///tmp/a.png"))
        val service = ImageGenerationService(client, FakeWriter(null), saver)

        val parts = service.generate(
            settings = settings(timeoutSeconds = 1),
            conversationId = null,
            request = ImageGenerationService.Request(prompt = "一只猫"),
        )

        assertEquals(1, parts.size)
        val body = summaryOf(parts)
        assertEquals(false, body.getValue("ok").jsonPrimitive.boolean)
        assertTrue(
            "超时提示里要写清等了多少秒",
            body.getValue("error").jsonPrimitive.content.contains("超时"),
        )
        assertTrue(body.getValue("error").jsonPrimitive.content.contains("121"))
        assertNull(saver.savedBytes)
    }

    @Test(timeout = 60_000)
    fun `unreachable service retries until its deadline and still hands over the job id`() = runBlocking {
        val client = RemoteImageGenerationClient(
            OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
        )
        val service = ImageGenerationService(client, FakeWriter(null), FakeSaver(emptyList()))
        val startedAt = System.currentTimeMillis()

        val parts = service.generate(
            // 断网不再「立刻失败」：客户端会按退避重试到服务端 timeout（这里压到 2 秒）用完为止，
            // 因为提交/轮询的失败可能只是瞬时断网，任务其实还在服务端跑
            settings = settings(baseUrl = "http://127.0.0.1:1", timeoutSeconds = 2),
            conversationId = null,
            request = ImageGenerationService.Request(prompt = "一只猫"),
        )

        assertEquals(1, parts.size)
        val body = summaryOf(parts)
        assertEquals(false, body.getValue("ok").jsonPrimitive.boolean)
        assertTrue("必须把连接失败的原因写进 error", body.getValue("error").jsonPrimitive.content.isNotBlank())
        // 连不上也必须交出 client_job_id：用户可能只是这一瞬间断网，任务其实已经在服务端了
        assertTrue("job_id 不能为空：$body", body.getValue("job_id").jsonPrimitive.content.isNotBlank())
        assertTrue(
            "hint 要告诉模型用 get_image_generation_job 把产物找回来",
            body.getValue("hint").jsonPrimitive.content.contains("get_image_generation_job"),
        )
        assertTrue(
            "重试应该由整体截止时间收口，而不是第一次连不上就放弃",
            System.currentTimeMillis() - startedAt >= 1_500,
        )
        assertTrue("必须把失败原因留给排查", body.getValue("detail").jsonPrimitive.content.isNotBlank())
    }

    // region forceDefaultSize

    @Test
    fun `force default size ignores the size the model asked for`() = runTest {
        val client = FakeClient { _, _ -> ImageResult.Success(listOf(pngBytes), listOf("a.png"), 1.0, "pid", null) }
        val writer = FakeWriter(ImagePromptAgent.Draft("rewritten", "neg"))
        val saver = FakeSaver(listOf("file:///tmp/a.png"))
        val service = ImageGenerationService(client, writer, saver)

        val parts = service.generate(
            // 不显式传 forceDefaultSize，验证「默认就是开启」
            settings = settings(defaultSize = "1024x1536"),
            conversationId = null,
            request = ImageGenerationService.Request(prompt = "画一张竖版海报", size = "1920x1080"),
        )

        val remote = client.requests.single()
        assertEquals("强制默认尺寸开启时必须忽略模型给的尺寸", "1024x1536", remote.size)
        // 改写 agent 也要看到最终生效的尺寸，免得提示词按 1920x1080 的构图去写
        assertEquals("1024x1536", writer.calls.single().size)
        // 回显给模型的摘要必须是真正发给服务端的尺寸
        assertEquals("1024x1536", summaryOf(parts).getValue("size").jsonPrimitive.content)
    }

    @Test
    fun `when forcing is off a valid requested size wins`() = runTest {
        val client = FakeClient { _, _ -> ImageResult.Success(listOf(pngBytes), listOf("a.png"), 1.0, "pid", null) }
        val writer = FakeWriter(ImagePromptAgent.Draft("rewritten", "neg"))
        val saver = FakeSaver(listOf("file:///tmp/a.png"))
        val service = ImageGenerationService(client, writer, saver)

        val parts = service.generate(
            settings = settings(defaultSize = "1024x1536", forceDefaultSize = false),
            conversationId = null,
            request = ImageGenerationService.Request(prompt = "画一张横版海报", size = "1920x1080"),
        )

        val remote = client.requests.single()
        assertEquals("关掉开关后模型给的合法尺寸要照用", "1920x1080", remote.size)
        assertEquals("1920x1080", summaryOf(parts).getValue("size").jsonPrimitive.content)
    }

    @Test
    fun `when forcing is off an illegal requested size falls back to the configured default`() = runTest {
        val client = FakeClient { _, _ -> ImageResult.Success(listOf(pngBytes), listOf("a.png"), 1.0, "pid", null) }
        val writer = FakeWriter(ImagePromptAgent.Draft("rewritten", "neg"))
        val saver = FakeSaver(listOf("file:///tmp/a.png"))
        val service = ImageGenerationService(client, writer, saver)

        val parts = service.generate(
            settings = settings(defaultSize = "1024x1536", forceDefaultSize = false),
            conversationId = null,
            request = ImageGenerationService.Request(prompt = "画一张方图", size = "乱写的"),
        )

        val remote = client.requests.single()
        assertEquals("非法尺寸要回落到设置里的默认尺寸", "1024x1536", remote.size)
        assertEquals("1024x1536", summaryOf(parts).getValue("size").jsonPrimitive.content)
    }

    @Test
    fun `a corrupted configured size still falls back to a legal one`() = runTest {
        val client = FakeClient { _, _ -> ImageResult.Success(listOf(pngBytes), listOf("a.png"), 1.0, "pid", null) }
        val service = ImageGenerationService(client, FakeWriter(null), FakeSaver(listOf("file:///tmp/a.png")))

        // 备份导入 / 手改 DataStore 都可能把默认尺寸写坏，这时候也要发出一个合法尺寸
        service.generate(
            settings = settings(defaultSize = "1024 x 1024", forceDefaultSize = true),
            conversationId = null,
            request = ImageGenerationService.Request(prompt = "一只猫"),
        )

        // 与 ImageGenerationService 里的 FALLBACK_IMAGE_SIZE 对齐
        assertEquals("1024x1024", client.requests.single().size)
    }

    // endregion

    // region fakes & helpers

    private fun settings(
        baseUrl: String = "http://127.0.0.1:8080",
        enablePromptAgent: Boolean = true,
        timeoutSeconds: Int = 1200,
        steps: Int = 4,
        defaultSize: String = "1024x1024",
        // 默认值与 ImageGenerationConfig 保持一致：生产环境默认强制默认尺寸
        forceDefaultSize: Boolean = true,
    ) = Settings(
        imageGenerationConfig = ImageGenerationConfig(
            baseUrl = baseUrl,
            timeoutSeconds = timeoutSeconds,
            steps = steps,
            defaultSize = defaultSize,
            forceDefaultSize = forceDefaultSize,
            enablePromptAgent = enablePromptAgent,
        )
    )

    private fun summaryOf(parts: List<UIMessagePart>) =
        Json.parseToJsonElement((parts.last() as UIMessagePart.Text).text).jsonObject

    /** 假的 HTTP 层：记录入参并返回预设结果，替掉真实网络请求。 */
    private class FakeClient(
        private val respond: suspend (ImageGenerationConfig, RemoteGenerateRequest) -> ImageResult,
    ) : RemoteImageGenerationClient(OkHttpClient()) {
        val requests = mutableListOf<RemoteGenerateRequest>()
        val configs = mutableListOf<ImageGenerationConfig>()

        override suspend fun generate(
            config: ImageGenerationConfig,
            request: RemoteGenerateRequest,
        ): ImageResult {
            requests += request
            configs += config
            return respond(config, request)
        }
    }

    private data class WriterCall(
        val request: String,
        val size: String,
    )

    /** 假的改写 agent：真实实现依赖 ProviderManager/UsageRecorder，JVM 单测里构造不出来。 */
    private class FakeWriter(
        private val draft: ImagePromptAgent.Draft?,
    ) : PromptWriter {
        val calls = mutableListOf<WriterCall>()

        override suspend fun writePrompt(
            settings: Settings,
            conversationId: Uuid?,
            request: String,
            size: String,
        ): ImagePromptAgent.Draft? {
            calls += WriterCall(request, size)
            return draft
        }
    }

    /** 假的落盘：只记字节与文件信息，不碰 Context。 */
    private class FakeSaver(
        private val urls: List<String>,
    ) : ImageFileSaver {
        var savedBytes: List<ByteArray>? = null
        var displayName: String? = null
        var mimeType: String? = null

        override fun save(bytes: List<ByteArray>, displayName: String, mimeType: String): List<String> {
            savedBytes = bytes
            this.displayName = displayName
            this.mimeType = mimeType
            return urls
        }
    }

    // endregion
}
