package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.PromptWriter
import me.rerere.rikkahub.data.ai.image.ImageFileSaver
import me.rerere.rikkahub.data.ai.image.ImageGenerationService
import me.rerere.rikkahub.data.ai.image.JobListLookup
import me.rerere.rikkahub.data.ai.image.JobLookup
import me.rerere.rikkahub.data.ai.image.JOB_STATUS_SUCCEEDED
import me.rerere.rikkahub.data.ai.image.RemoteImageGenerationClient
import me.rerere.rikkahub.data.ai.image.RemoteJobProgress
import me.rerere.rikkahub.data.ai.image.RemoteJobSnapshot
import me.rerere.rikkahub.data.datastore.ImageGenerationConfig
import me.rerere.rikkahub.data.datastore.Settings
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `get_image_generation_job` 工具的接线：工具名、审批策略、入参解析与「不传 job_id 就列最近任务」的兜底。
 *
 * 这是模型在断网/超时之后唯一的自救入口，参数解析错一次就会让它彻底丢掉产物，所以单独固化。
 */
class ImageJobQueryToolTest {

    @Test
    fun `tool exposes its name and never asks for approval`() {
        val tool = buildTool(FakeJobClient())

        assertEquals("get_image_generation_job", tool.name)
        assertFalse("查任务没有副作用，不该弹审批", tool.needsApproval(JsonNull))
        assertTrue("描述里要告诉模型它能把图取回来", tool.description.contains("job_id"))
        assertTrue(tool.description.contains("generate_image"))
    }

    @Test
    fun `job id argument looks the job up and returns the images`() = runBlocking {
        val client = FakeJobClient(lookup = JobLookup.Found(succeededSnapshot()))
        val tool = buildTool(client)

        val parts = tool.execute(Json.parseToJsonElement("""{"job_id":"  job-1  "}"""))

        assertEquals("两边的空白要去掉，别把脏 key 发给服务端", listOf("job-1"), client.lookedUpKeys)
        assertEquals(1, parts.filterIsInstance<UIMessagePart.Image>().size)
        val summary = Json.parseToJsonElement((parts.last() as UIMessagePart.Text).text).jsonObject
        assertEquals(true, summary.getValue("ok").jsonPrimitive.boolean)
        assertEquals("job-1", summary.getValue("job_id").jsonPrimitive.content)
    }

    @Test
    fun `include images false is passed through`() = runBlocking {
        val client = FakeJobClient(lookup = JobLookup.Found(succeededSnapshot()))
        val tool = buildTool(client)

        val parts = tool.execute(
            Json.parseToJsonElement("""{"job_id":"job-1","include_images":false}""")
        )

        assertEquals("只要状态时不该下载", 0, client.downloadCount)
        assertEquals(0, parts.filterIsInstance<UIMessagePart.Image>().size)
        assertTrue(parts.single() is UIMessagePart.Text)
    }

    @Test
    fun `include images defaults to true`() = runBlocking {
        val client = FakeJobClient(lookup = JobLookup.Found(succeededSnapshot()))
        val tool = buildTool(client)

        tool.execute(Json.parseToJsonElement("""{"job_id":"job-1"}"""))

        assertEquals("默认要把图取回来", 1, client.downloadCount)
    }

    @Test
    fun `missing job id lists the most recent jobs`() = runBlocking {
        val client = FakeJobClient(listed = listOf(succeededSnapshot()))
        val tool = buildTool(client)

        val parts = tool.execute(Json.parseToJsonElement("{}"))

        assertEquals(1, parts.size)
        assertEquals("没给 job_id 时不该查单个任务", emptyList<String>(), client.lookedUpKeys)
        assertEquals(listOf(10), client.listedLimits)
        val text = (parts.single() as UIMessagePart.Text).text
        assertTrue("列表里要有 job_id：$text", text.contains("job-1"))
        assertTrue("列表里要有状态：$text", text.contains(JOB_STATUS_SUCCEEDED))
    }

    @Test
    fun `non object args fail with a readable json error`() = runBlocking {
        val client = FakeJobClient()
        val tool = buildTool(client)

        listOf("""[]""", """"job-1"""", "42").forEach { args ->
            val parts = tool.execute(Json.parseToJsonElement(args))
            assertEquals("坏参数只该产生一条文本：$args", 1, parts.size)
            val body = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
            assertEquals(false, body.getValue("ok").jsonPrimitive.boolean)
            assertTrue(body.getValue("error").jsonPrimitive.content.isNotBlank())
        }
        assertTrue("坏参数不该打到服务端", client.lookedUpKeys.isEmpty())
    }

    @Test
    fun `service errors are turned into a text part instead of throwing`() = runBlocking {
        val client = FakeJobClient(lookup = JobLookup.Error("连不上服务端", 503))
        val tool = buildTool(client)

        val parts = tool.execute(Json.parseToJsonElement("""{"job_id":"job-1"}"""))

        val body = Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
        assertEquals(false, body.getValue("ok").jsonPrimitive.boolean)
        assertEquals(503, body.getValue("http_code").jsonPrimitive.int)
    }

    private fun buildTool(client: RemoteImageGenerationClient) = buildImageJobQueryTool(
        service = ImageGenerationService(
            client = client,
            promptWriter = PromptWriter { _, _, _, _ -> null },
            fileSaver = ImageFileSaver { bytes, _, _ ->
                bytes.mapIndexed { index, _ -> "file:///tmp/job_$index.png" }
            },
        ),
        settingsProvider = {
            Settings(imageGenerationConfig = ImageGenerationConfig(baseUrl = "http://127.0.0.1:8080"))
        },
    )

    private fun succeededSnapshot() = RemoteJobSnapshot(
        ok = true,
        jobId = "job-1",
        clientJobId = "client-1",
        status = JOB_STATUS_SUCCEEDED,
        stage = "done",
        progress = RemoteJobProgress(elapsedSec = 21.1, timeoutSec = 1800.0),
        promptId = "pid-1",
        images = listOf(
            me.rerere.rikkahub.data.ai.image.RemoteImageItem(
                filename = "ComfyUI_00009__1.png",
                downloadUrl = "http://192.168.1.10:8080/files/ComfyUI_00009__1.png",
            )
        ),
    )

    /** 假的查询层：只记录调用次数，不碰网络。 */
    private class FakeJobClient(
        private val lookup: JobLookup = JobLookup.NotFound,
        private val listed: List<RemoteJobSnapshot> = emptyList(),
    ) : RemoteImageGenerationClient(OkHttpClient()) {
        val lookedUpKeys = mutableListOf<String>()
        val listedLimits = mutableListOf<Int>()
        var downloadCount = 0

        override suspend fun fetchJob(config: ImageGenerationConfig, key: String): JobLookup {
            lookedUpKeys += key
            return lookup
        }

        override suspend fun listJobs(
            config: ImageGenerationConfig,
            limit: Int,
            status: String?,
        ): JobListLookup {
            listedLimits += limit
            return JobListLookup.Ok(listed)
        }

        override suspend fun downloadJobImages(
            config: ImageGenerationConfig,
            snapshot: RemoteJobSnapshot,
        ): List<ByteArray> {
            downloadCount++
            return listOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        }
    }
}
