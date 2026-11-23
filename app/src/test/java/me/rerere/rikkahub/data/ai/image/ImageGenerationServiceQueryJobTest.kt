package me.rerere.rikkahub.data.ai.image

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.PromptWriter
import me.rerere.rikkahub.data.datastore.ImageGenerationConfig
import me.rerere.rikkahub.data.datastore.Settings
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `get_image_generation_job` 的后端（[ImageGenerationService.queryJob]）：
 * 断网/超时之后按 job_id 把任务找回来的那条路。
 *
 * 这里只测编排层（快照 → 消息 part、下载 → 落盘、列表兜底文案），HTTP 由假 client 替掉：
 * 真实网络行为由 [RemoteImageGenerationClient] 自己负责。
 */
class ImageGenerationServiceQueryJobTest {

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun `succeeded job downloads the images and returns them with a json summary`() = runBlocking {
        val client = FakeJobClient(
            lookup = JobLookup.Found(succeededSnapshot()),
            downloads = listOf(pngBytes),
        )
        val saver = FakeSaver(listOf("file:///tmp/upload/job.png"))
        val service = ImageGenerationService(client, PromptWriter { _, _, _, _ -> null }, saver)

        val parts = service.queryJob(settings(), jobId = "job-1")

        // 先图片后摘要，和 generate_image 的返回风格一致
        val images = parts.filterIsInstance<UIMessagePart.Image>()
        assertEquals(1, images.size)
        assertEquals("file:///tmp/upload/job.png", images.single().url)
        assertEquals("查询成功必须真的落盘", 1, saver.savedBytes?.size)
        assertTrue("落盘文件名要能看出是出图产物", saver.displayName!!.startsWith("generated_"))
        assertEquals("image/png", saver.mimeType)
        assertEquals("查的是用户给的那个 job", "job-1", client.lookedUpKeys.single())
        assertEquals("下载必须用这次查询拿到的快照", "job-1", client.downloadedSnapshots.single().jobId)

        val summary = summaryOf(parts)
        assertEquals(true, summary.getValue("ok").jsonPrimitive.boolean)
        assertEquals("job-1", summary.getValue("job_id").jsonPrimitive.content)
        assertEquals("client-1", summary.getValue("client_job_id").jsonPrimitive.content)
        assertEquals(JOB_STATUS_SUCCEEDED, summary.getValue("status").jsonPrimitive.content)
        assertEquals("done", summary.getValue("stage").jsonPrimitive.content)
        assertEquals("pid-1", summary.getValue("prompt_id").jsonPrimitive.content)
        assertEquals(21.1, summary.getValue("elapsed_sec").jsonPrimitive.double, 1e-9)
        assertEquals(1, summary.getValue("images").jsonPrimitive.int)
        assertEquals(1, summary.getValue("expected_images").jsonPrimitive.int)
        assertEquals(
            "http://192.168.1.10:8080/files/ComfyUI_00009__1.png",
            summary.getValue("download_urls").jsonArray.single().jsonPrimitive.content,
        )
    }

    @Test
    fun `include images false only reports the status`() = runBlocking {
        val client = FakeJobClient(lookup = JobLookup.Found(succeededSnapshot()))
        val saver = FakeSaver(listOf("file:///tmp/upload/job.png"))
        val service = ImageGenerationService(client, PromptWriter { _, _, _, _ -> null }, saver)

        val parts = service.queryJob(settings(), jobId = "job-1", includeImages = false)

        assertEquals("只要状态时不该返回图片 part", 0, parts.filterIsInstance<UIMessagePart.Image>().size)
        assertTrue("只要状态时不该下载", client.downloadedSnapshots.isEmpty())
        assertNull(saver.savedBytes)
        val summary = summaryOf(parts)
        assertEquals(true, summary.getValue("ok").jsonPrimitive.boolean)
        assertEquals(0, summary.getValue("images").jsonPrimitive.int)
        // 下载地址仍然给出，用户/模型可以自己去取
        assertEquals(1, summary.getValue("download_urls").jsonArray.size)
    }

    @Test
    fun `running job keeps reporting progress without downloading`() = runBlocking {
        val client = FakeJobClient(
            lookup = JobLookup.Found(
                succeededSnapshot().copy(status = JOB_STATUS_RUNNING, stage = "generating", images = emptyList())
            )
        )
        val service = ImageGenerationService(
            client,
            PromptWriter { _, _, _, _ -> null },
            FakeSaver(emptyList()),
        )

        val parts = service.queryJob(settings(), jobId = "job-1")

        assertEquals(1, parts.size)
        assertTrue("还没好时只能给文本", parts.single() is UIMessagePart.Text)
        assertTrue("没成功就不该下载", client.downloadedSnapshots.isEmpty())
        val summary = summaryOf(parts)
        assertEquals(false, summary.getValue("ok").jsonPrimitive.boolean)
        assertEquals(JOB_STATUS_RUNNING, summary.getValue("status").jsonPrimitive.content)
        assertEquals("generating", summary.getValue("stage").jsonPrimitive.content)
    }

    @Test
    fun `failed job reports the reason the code and the job id`() = runBlocking {
        val client = FakeJobClient(
            lookup = JobLookup.Found(
                succeededSnapshot().copy(
                    status = JOB_STATUS_FAILED,
                    stage = "failed",
                    images = emptyList(),
                    error = "资源编排失败：start comfy 超时",
                    httpStatus = 502,
                )
            )
        )
        val service = ImageGenerationService(
            client,
            PromptWriter { _, _, _, _ -> null },
            FakeSaver(emptyList()),
        )

        val parts = service.queryJob(settings(), jobId = "job-1")

        val summary = summaryOf(parts)
        assertEquals(false, summary.getValue("ok").jsonPrimitive.boolean)
        assertEquals("资源编排失败：start comfy 超时", summary.getValue("error").jsonPrimitive.content)
        assertEquals(502, summary.getValue("http_code").jsonPrimitive.int)
        assertEquals("job-1", summary.getValue("job_id").jsonPrimitive.content)
    }

    @Test
    fun `unknown job id explains that the job is gone`() = runBlocking {
        val client = FakeJobClient(lookup = JobLookup.NotFound)
        val service = ImageGenerationService(
            client,
            PromptWriter { _, _, _, _ -> null },
            FakeSaver(emptyList()),
        )

        val parts = service.queryJob(settings(), jobId = "missing-job")

        assertEquals(1, parts.size)
        val text = (parts.single() as UIMessagePart.Text).text
        assertTrue("要明确说任务不存在：$text", text.contains("任务不存在"))
        assertTrue("要把用户给的 id 回显出来：$text", text.contains("missing-job"))
    }

    @Test
    fun `lookup error is reported as a recoverable failure`() = runBlocking {
        val client = FakeJobClient(lookup = JobLookup.Error("连不上服务端", 503))
        val service = ImageGenerationService(
            client,
            PromptWriter { _, _, _, _ -> null },
            FakeSaver(emptyList()),
        )

        val parts = service.queryJob(settings(), jobId = "job-1")

        val summary = summaryOf(parts)
        assertEquals(false, summary.getValue("ok").jsonPrimitive.boolean)
        assertEquals("连不上服务端", summary.getValue("error").jsonPrimitive.content)
        assertEquals(503, summary.getValue("http_code").jsonPrimitive.int)
        assertEquals("job-1", summary.getValue("job_id").jsonPrimitive.content)
        assertTrue("要告诉模型可以稍后重试", summary.getValue("hint").jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `blank job id lists the recent jobs`() = runBlocking {
        val client = FakeJobClient(
            lookup = JobLookup.NotFound,
            listed = listOf(
                succeededSnapshot(),
                succeededSnapshot().copy(
                    jobId = "job-2",
                    status = JOB_STATUS_RUNNING,
                    stage = "generating",
                    images = emptyList(),
                ),
            ),
        )
        val service = ImageGenerationService(
            client,
            PromptWriter { _, _, _, _ -> null },
            FakeSaver(emptyList()),
        )

        val parts = service.queryJob(settings(), jobId = "   ")

        assertEquals(1, parts.size)
        assertEquals("列最近任务才是兜底入口", 10, client.listedLimits.single())
        val text = (parts.single() as UIMessagePart.Text).text
        listOf("job-1", "job-2", JOB_STATUS_SUCCEEDED, JOB_STATUS_RUNNING, "generating").forEach { token ->
            assertTrue("列表里要有 $token：$text", text.contains(token))
        }
        assertTrue("列表里要给出下载地址：$text", text.contains("/files/ComfyUI_00009__1.png"))
        assertFalse("列表不该假装成功", text.startsWith("{"))
    }

    @Test
    fun `blank base url asks the user to configure the service first`() = runBlocking {
        val client = FakeJobClient(lookup = JobLookup.Found(succeededSnapshot()))
        val service = ImageGenerationService(
            client,
            PromptWriter { _, _, _, _ -> null },
            FakeSaver(emptyList()),
        )

        val parts = service.queryJob(settings(baseUrl = "  "), jobId = "job-1")

        val summary = summaryOf(parts)
        assertEquals(false, summary.getValue("ok").jsonPrimitive.boolean)
        assertEquals(IMAGE_SERVICE_NOT_CONFIGURED, summary.getValue("error").jsonPrimitive.content)
        assertTrue("没配地址时不该发请求", client.lookedUpKeys.isEmpty())
    }

    // region fakes & helpers

    private fun succeededSnapshot() = RemoteJobSnapshot(
        ok = true,
        jobId = "job-1",
        clientJobId = "client-1",
        status = JOB_STATUS_SUCCEEDED,
        stage = "done",
        createdAt = 1732345678.123,
        startedAt = 1732345679.5,
        finishedAt = 1732345700.75,
        progress = RemoteJobProgress(elapsedSec = 21.1, timeoutSec = 1800.0),
        promptId = "pid-1",
        images = listOf(
            RemoteImageItem(
                filename = "ComfyUI_00009__1.png",
                downloadUrl = "http://192.168.1.10:8080/files/ComfyUI_00009__1.png",
                savedPath = "/root/autodl-fs/api-comfy/outputs/ComfyUI_00009__1.png",
                sizeBytes = 731637,
            )
        ),
        statusUrl = "/api/jobs/job-1",
    )

    private fun settings(baseUrl: String = "http://127.0.0.1:8080") = Settings(
        imageGenerationConfig = ImageGenerationConfig(baseUrl = baseUrl)
    )

    private fun summaryOf(parts: List<UIMessagePart>) =
        Json.parseToJsonElement((parts.last() as UIMessagePart.Text).text).jsonObject

    /** 假的任务查询层：只记录入参、返回预设结果，不碰网络。 */
    private class FakeJobClient(
        private val lookup: JobLookup,
        private val downloads: List<ByteArray> = emptyList(),
        private val listed: List<RemoteJobSnapshot> = emptyList(),
    ) : RemoteImageGenerationClient(OkHttpClient()) {
        val lookedUpKeys = mutableListOf<String>()
        val listedLimits = mutableListOf<Int>()
        val downloadedSnapshots = mutableListOf<RemoteJobSnapshot>()

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
            downloadedSnapshots += snapshot
            return downloads
        }
    }

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
