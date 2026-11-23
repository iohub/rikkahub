package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ImagePromptAgent
import me.rerere.rikkahub.data.ai.PromptWriter
import me.rerere.rikkahub.data.ai.image.ImageFileSaver
import me.rerere.rikkahub.data.ai.image.ImageGenerationService
import me.rerere.rikkahub.data.ai.image.RemoteGenerateRequest
import me.rerere.rikkahub.data.ai.image.RemoteImageGenerationClient
import me.rerere.rikkahub.data.datastore.ImageGenerationConfig
import me.rerere.rikkahub.data.datastore.Settings
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import me.rerere.rikkahub.data.ai.image.Result as ImageResult

/**
 * 「agent 调工具 → 真实服务出图」的端到端功能测试。
 *
 * 会真的打 [System.getenv] 里给的服务地址，冷启动 ComfyUI 可能要好几分钟，
 * 所以默认跳过：不设 `IMAGE_GEN_E2E_BASE_URL` 时整类 assume 掉，常规单测不受影响。
 *
 * 跑法：
 * ```
 * IMAGE_GEN_E2E_BASE_URL=http://127.0.0.1:8080 \
 *   ./gradlew :app:testGithubDebugUnitTest --tests '*ImageGenerationToolE2ETest*'
 * ```
 */
class ImageGenerationToolE2ETest {

    private val baseUrl: String? = System.getenv("IMAGE_GEN_E2E_BASE_URL")

    @Before
    fun requireService() {
        assumeTrue("未设置 IMAGE_GEN_E2E_BASE_URL，跳过真实出图测试", !baseUrl.isNullOrBlank())
    }

    @Test(timeout = 900_000)
    fun `agent generates a real image and the summary reports it`() = runBlocking {
        val saver = TempFileSaver()
        val client = RecordingRealClient(
            OkHttpClient.Builder()
                // 冷启动要 3~6 分钟，默认的 10 秒 readTimeout 根本不够
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.MINUTES)
                .writeTimeout(5, TimeUnit.MINUTES)
                // 0 = 不额外限制整次调用，超时交给服务端参数与下面的 withTimeout
                .callTimeout(0, TimeUnit.SECONDS)
                .build()
        )
        val writer = PromptWriter { _, _, request, size ->
            ImagePromptAgent.Draft(
                prompt = "$request, soft watercolor, clean composition, no text, $size",
                negative = "watermark, blurry, low quality, extra limbs",
            )
        }
        val service = ImageGenerationService(client, writer, saver)
        // 和 LocalTools 里接线方式一致：工具自己从 provider 取设置
        val tool = buildImageGenerationTool(
            service = service,
            settingsProvider = { settingsWith(baseUrl!!) },
            conversationId = null,
        )

        val parts = withTimeout(780_000) {
            tool.execute(
                Json.parseToJsonElement(
                    """
                    {"prompt":"a cute orange cat sitting in snow, watercolor style",
                     "size":"768x768","steps":4,"no_reference":true}
                    """.trimIndent()
                )
            )
        }

        val images = parts.filterIsInstance<UIMessagePart.Image>()
        val summary = Json.parseToJsonElement((parts.last() as UIMessagePart.Text).text).jsonObject

        // 请求体按 agent 给的参数拼装：改写结果 + 显式 no_reference
        val remote = client.lastRequest
        assertNotNull("真实 client 应该收到请求", remote)
        requireNotNull(remote)
        assertEquals("768x768", remote.size)
        assertEquals(4, remote.steps)
        assertEquals("no_reference 必须原样发出去", true, remote.noReference)
        assertNull("纯文生图不该发参考图", remote.images)
        assertTrue("negative 应该来自改写结果", remote.negative!!.contains("watermark"))

        assertEquals("真实出图必须成功：${summary}", true, summary.getValue("ok").jsonPrimitive.boolean)
        assertTrue("至少要有一张图片 part", images.isNotEmpty())
        assertTrue("图片 url 必须来自 saver：${images.map { it.url }}", images.all { it.url.startsWith("file:") })
        assertEquals(images.size, summary.getValue("images").jsonPrimitive.int)

        saver.files.forEach { file ->
            val bytes = file.readBytes()
            // 只认 PNG 魔数，避免服务端把错误页当成图片返回却没人发现
            assertTrue("落盘文件不是 PNG：${file.absolutePath}", bytes.size > 10_000)
            assertTrue(
                "PNG 魔数不对：${bytes.take(4).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}",
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte(),
            )
            val image = ImageIO.read(file)
            println(
                "[E2E] ok=true prompt_id=${summary.getValue("prompt_id").jsonPrimitive.contentOrNull} " +
                    "elapsed_sec=${summary.getValue("elapsed_sec").jsonPrimitive.doubleOrNull} " +
                    "images=${images.size} bytes=${bytes.size} " +
                    "size=${image?.width}x${image?.height} file=${file.absolutePath}"
            )
        }
        println("[E2E] prompt sent = ${remote.prompt}")
    }

    @Test(timeout = 60_000)
    fun `unreachable service fails without throwing`() = runBlocking {
        val saver = TempFileSaver()
        val service = ImageGenerationService(
            client = RemoteImageGenerationClient(
                OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .build()
            ),
            promptWriter = PromptWriter { _, _, _, _ -> null },
            fileSaver = saver,
        )
        val tool = buildImageGenerationTool(
            service = service,
            // 断网后客户端会按退避重试到 timeout 用完，这里压到 2 秒，测试才不会等 20 分钟
            settingsProvider = { settingsWith("http://127.0.0.1:1", timeoutSeconds = 2) },
            conversationId = null,
        )

        val parts = withTimeout(30_000) {
            tool.execute(Json.parseToJsonElement("""{"prompt":"a cat"}"""))
        }

        assertEquals("连不上时必须只有一条文本结果", 1, parts.size)
        val body = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("连不上必须报 ok=false：$body", false, body.getValue("ok").jsonPrimitive.boolean)
        assertTrue("error 要写清原因：$body", body.getValue("error").jsonPrimitive.content.isNotBlank())
        assertTrue("连不上不该有图片", saver.files.isEmpty())
    }

    private fun settingsWith(url: String, timeoutSeconds: Int = 600) = Settings(
        imageGenerationConfig = ImageGenerationConfig(
            baseUrl = url,
            // 服务端侧超时 600 秒：冷启动 3~6 分钟 + 出图，比外层 780 秒的看门狗早一点收口
            timeoutSeconds = timeoutSeconds,
            steps = 4,
            defaultSize = "768x768",
            enablePromptAgent = true,
        )
    )

    /** 真实 HTTP 层 + 记录最后一次请求，用来断言 agent 的参数确实被原样发出去。 */
    private class RecordingRealClient(
        okHttpClient: OkHttpClient,
    ) : RemoteImageGenerationClient(okHttpClient) {
        var lastRequest: RemoteGenerateRequest? = null

        override suspend fun generate(
            config: ImageGenerationConfig,
            request: RemoteGenerateRequest,
        ): ImageResult {
            lastRequest = request
            return super.generate(config, request)
        }
    }

    /** 把字节写到临时文件并返回 `file:` URL，等价于生产里 FilesManager 的落盘，只是不需要 Context。 */
    private class TempFileSaver : ImageFileSaver {
        private val dir: File = File(
            System.getProperty("java.io.tmpdir"),
            "rikkahub-image-e2e-${System.currentTimeMillis()}",
        ).apply { mkdirs() }

        val files = mutableListOf<File>()

        override fun save(bytes: List<ByteArray>, displayName: String, mimeType: String): List<String> =
            bytes.mapIndexed { index, byteArray ->
                val file = File(dir, "${index}_$displayName")
                file.writeBytes(byteArray)
                files += file
                file.toURI().toString()
            }
    }
}
