package me.rerere.rikkahub.data.ai.image

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.ImageGenerationConfig
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * 用 JDK 自带的 `com.sun.net.httpserver` 起一个真 HTTP 服务，验证「异步提交 → 轮询 → 下载」这条
 * 断网续传链路的真实行为（URL 拼接、client_job_id 往返、404 重提交、旧服务端降级、结果图下载）。
 *
 * 仓库没有 mockwebserver 依赖，也不允许为它加依赖，所以这里直接用 JDK 的 HttpServer：
 * 跑在 JVM 单测里、不碰外网、不留后台进程。
 */
class RemoteImageGenerationAsyncFlowTest {

    private var server: HttpServer? = null
    private var baseUrl: String = ""

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test(timeout = 60_000)
    fun `async submit then poll downloads the finished images`() = runBlocking {
        val submitBodies = mutableListOf<String>()
        val pollPaths = mutableListOf<String>()
        var polls = 0
        startServer { exchange ->
            val path = exchange.requestURI.path
            when {
                path == "/api/generate/async" -> {
                    submitBodies += exchange.readBody()
                    exchange.respondJson(
                        202,
                        """
                        {"ok":true,"job_id":"server-job-1","status":"queued","stage":"queued",
                         "created_at":1732345678.123,"status_url":"/api/jobs/server-job-1"}
                        """.trimIndent(),
                    )
                }

                path.startsWith("/api/jobs/") -> {
                    pollPaths += path
                    polls++
                    if (polls == 1) {
                        // 第一次还在跑：客户端必须继续等，而不是直接失败
                        exchange.respondJson(
                            200,
                            """
                            {"ok":true,"job_id":"server-job-1","status":"running","stage":"generating",
                             "progress":{"elapsed_sec":5.0,"timeout_sec":60.0}}
                            """.trimIndent(),
                        )
                    } else {
                        exchange.respondJson(
                            200,
                            """
                            {"ok":true,"job_id":"server-job-1","status":"succeeded","stage":"done",
                             "progress":{"elapsed_sec":21.1,"timeout_sec":60.0},"prompt_id":"pid-1",
                             "images":[{"filename":"x.png","download_url":"$baseUrl/files/x.png","size_bytes":8}]}
                            """.trimIndent(),
                        )
                    }
                }

                path == "/files/x.png" -> exchange.respondBytes(200, pngBytes)
                else -> exchange.respondJson(404, """{"ok":false,"error":"no such route"}""")
            }
        }

        val result = client().generate(
            ImageGenerationConfig(baseUrl = baseUrl, timeoutSeconds = 60),
            RemoteGenerateRequest(prompt = "a corgi", timeout = 60),
        )

        assertTrue("必须是成功结果：$result", result is Result.Success)
        val success = result as Result.Success
        assertArrayEquals("下载到的必须是服务端原始字节", pngBytes, success.images.single())
        assertEquals(21.1, success.elapsedSec!!, 1e-9)
        assertEquals("pid-1", success.promptId)
        assertEquals("server-job-1", success.jobId)

        // 提交只能发生一次（幂等键在手，不需要重提交）
        assertEquals(1, submitBodies.size)
        val clientJobId = Json.parseToJsonElement(submitBodies.single()).jsonObject
            .getValue("client_job_id").jsonPrimitive.content
        assertTrue("client_job_id 必须满足服务端字符集", CLIENT_JOB_ID_PATTERN.matches(clientJobId))
        // 轮询一律用 client_job_id：POST 的响应丢了也照样查得到
        assertEquals(listOf("/api/jobs/$clientJobId", "/api/jobs/$clientJobId"), pollPaths)
    }

    @Test(timeout = 60_000)
    fun `async endpoint 404 falls back to the legacy synchronous call`() = runBlocking {
        var asyncBody: String? = null
        var legacyBody: String? = null
        startServer { exchange ->
            val path = exchange.requestURI.path
            when (path) {
                "/api/generate/async" -> {
                    asyncBody = exchange.readBody()
                    exchange.respondJson(404, """{"ok":false,"error":"接口不存在"}""")
                }

                "/api/generate" -> {
                    legacyBody = exchange.readBody()
                    exchange.respondJson(
                        200,
                        """
                        {"ok":true,"prompt_id":"pid-legacy","elapsed_sec":12.5,
                         "images":[{"filename":"legacy.png","download_url":"$baseUrl/files/legacy.png"}]}
                        """.trimIndent(),
                    )
                }

                "/files/legacy.png" -> exchange.respondBytes(200, pngBytes)
                else -> exchange.respondJson(404, """{"ok":false,"error":"no such route"}""")
            }
        }

        val result = client().generate(
            ImageGenerationConfig(baseUrl = baseUrl),
            RemoteGenerateRequest(prompt = "a corgi", timeout = 30),
        )

        assertTrue("旧服务端也要能出图：$result", result is Result.Success)
        val success = result as Result.Success
        assertArrayEquals(pngBytes, success.images.single())
        // 老服务端没有任务概念：job_id 用 prompt_id 顶替，保证失败/成功后都有 id 可言
        assertEquals("pid-legacy", success.promptId)
        assertEquals("pid-legacy", success.jobId)

        assertTrue("异步提交必须带 client_job_id", asyncBody!!.contains("client_job_id"))
        assertFalse(
            "降级到旧同步接口时必须剥掉 client_job_id，wire 契约一个字都不能变：$legacyBody",
            legacyBody!!.contains("client_job_id"),
        )
    }

    @Test(timeout = 60_000)
    fun `polling 404 resubmits with the same client job id`() = runBlocking {
        val submitIds = mutableListOf<String>()
        var polls = 0
        startServer { exchange ->
            val path = exchange.requestURI.path
            when {
                path == "/api/generate/async" -> {
                    val body = exchange.readBody()
                    submitIds += Json.parseToJsonElement(body).jsonObject
                        .getValue("client_job_id").jsonPrimitive.content
                    exchange.respondJson(
                        202,
                        """{"ok":true,"job_id":"server-job-${submitIds.size}","status":"queued","stage":"queued"}""",
                    )
                }

                path.startsWith("/api/jobs/") -> {
                    polls++
                    if (polls == 1) {
                        // 服务端重启把任务表丢了：客户端应该用同一个 client_job_id 重新提交
                        exchange.respondJson(404, """{"ok":false,"error":"任务不存在"}""")
                    } else {
                        exchange.respondJson(
                            200,
                            """
                            {"ok":true,"job_id":"server-job-2","status":"succeeded","stage":"done",
                             "progress":{"elapsed_sec":9.0},"prompt_id":"pid-2",
                             "images":[{"filename":"y.png","download_url":"$baseUrl/files/y.png"}]}
                            """.trimIndent(),
                        )
                    }
                }

                path == "/files/y.png" -> exchange.respondBytes(200, pngBytes)
                else -> exchange.respondJson(404, """{"ok":false,"error":"no such route"}""")
            }
        }

        val result = client().generate(
            ImageGenerationConfig(baseUrl = baseUrl),
            RemoteGenerateRequest(prompt = "a corgi", timeout = 60),
        )

        assertTrue("重提交之后必须还能拿到图：$result", result is Result.Success)
        assertEquals(2, submitIds.size)
        assertEquals("重提交必须复用同一个 client_job_id（幂等键）", submitIds[0], submitIds[1])
    }

    @Test(timeout = 60_000)
    fun `python style failure snapshot is reported as a failure with the job id`() = runBlocking {
        startServer { exchange ->
            val path = exchange.requestURI.path
            when {
                path == "/api/generate/async" -> exchange.respondJson(
                    202,
                    """{"ok":true,"job_id":"server-job-9","status":"queued","stage":"queued"}""",
                )

                path.startsWith("/api/jobs/") -> exchange.respondJson(
                    200,
                    """
                    {"ok":true,"job_id":"server-job-9","status":"failed","stage":"failed",
                     "error":"资源编排失败：start comfy 超时","http_status":502,
                     "detail":{"step":"start:comfy"},"images":[]}
                    """.trimIndent(),
                )

                else -> exchange.respondJson(404, """{"ok":false,"error":"no such route"}""")
            }
        }

        val result = client().generate(
            ImageGenerationConfig(baseUrl = baseUrl),
            RemoteGenerateRequest(prompt = "a corgi", timeout = 60),
        )

        assertTrue("失败也要结构化返回：$result", result is Result.Failure)
        val failure = result as Result.Failure
        assertEquals(502, failure.httpCode)
        assertTrue("原因要透传：${failure.message}", failure.message.contains("start comfy 超时"))
        assertTrue("detail 要留给排查：${failure.detail}", failure.detail!!.contains("start:comfy"))
        assertEquals("失败也必须带回 job_id", "server-job-9", failure.jobId)
    }

    @Test(timeout = 120_000)
    fun `a connection dropped while polling is retried instead of failing the job`() = runBlocking {
        var polls = 0
        startServer { exchange ->
            val path = exchange.requestURI.path
            when {
                path == "/api/generate/async" -> exchange.respondJson(
                    202,
                    """{"ok":true,"job_id":"server-job-1","status":"queued","stage":"queued"}""",
                )

                path.startsWith("/api/jobs/") -> {
                    polls++
                    if (polls == 1) {
                        // 模拟断网：声明 1000 字节的响应，只写十几个字节就把连接掐了。
                        // 客户端读 body 时必然炸，且这种「响应读到一半断线」连 OkHttp 自己都不会重试，
                        // 只能由我们的轮询循环兜住。
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, 1000)
                        exchange.responseBody.use { body ->
                            body.write("""{"ok":true,""".toByteArray())
                            body.flush()
                        }
                    } else {
                        exchange.respondJson(
                            200,
                            """
                            {"ok":true,"job_id":"server-job-1","status":"succeeded","stage":"done",
                             "progress":{"elapsed_sec":30.0},"prompt_id":"pid-1",
                             "images":[{"filename":"x.png","download_url":"$baseUrl/files/x.png"}]}
                            """.trimIndent(),
                        )
                    }
                }

                path == "/files/x.png" -> exchange.respondBytes(200, pngBytes)
                else -> exchange.respondJson(404, """{"ok":false,"error":"no such route"}""")
            }
        }

        val startedAt = System.currentTimeMillis()
        val result = client().generate(
            ImageGenerationConfig(baseUrl = baseUrl),
            RemoteGenerateRequest(prompt = "a corgi", timeout = 60),
        )
        val elapsedMs = System.currentTimeMillis() - startedAt

        assertTrue("断一次网不该让已经提交的任务失败：$result", result is Result.Success)
        assertArrayEquals(pngBytes, (result as Result.Success).images.single())
        assertEquals("被掐断后必须重试轮询", 2, polls)
        // 第一次重试按退避要等 2 秒：这条断言同时证明「没有立刻失败」和「真的退避了」
        assertTrue("重试前应该退避，而不是紧循环刷服务端：elapsed=${elapsedMs}ms", elapsedMs >= 1_500)
    }

    @Test(timeout = 60_000)
    fun `fetchJob and listJobs speak the documented endpoints`() = runBlocking {
        val listedQueries = mutableListOf<String>()
        startServer { exchange ->
            val path = exchange.requestURI.path
            when {
                path == "/api/jobs" -> {
                    listedQueries += exchange.requestURI.query ?: ""
                    exchange.respondJson(
                        200,
                        """
                        {"ok":true,"count":1,"jobs":[{"job_id":"server-job-1","status":"succeeded",
                         "stage":"done","created_at":1732345700.0,
                         "images":[{"filename":"z.png","download_url":"$baseUrl/files/z.png"}]}]}
                        """.trimIndent(),
                    )
                }

                path == "/api/jobs/server-job-1" -> exchange.respondJson(
                    200,
                    """{"ok":true,"job_id":"server-job-1","status":"succeeded","stage":"done"}""",
                )

                path == "/files/z.png" -> exchange.respondBytes(200, pngBytes)
                else -> exchange.respondJson(404, """{"ok":false,"error":"任务不存在：${path.removePrefix("/api/jobs/")}"}""")
            }
        }
        val client = client()
        val config = ImageGenerationConfig(baseUrl = baseUrl)

        val found = client.fetchJob(config, "server-job-1")
        assertTrue(found is JobLookup.Found)
        assertEquals("server-job-1", (found as JobLookup.Found).snapshot.jobId)

        assertEquals("不存在的任务要如实报告 NotFound", JobLookup.NotFound, client.fetchJob(config, "nope"))

        val listed = client.listJobs(config, limit = 10)
        assertTrue(listed is JobListLookup.Ok)
        assertEquals(1, (listed as JobListLookup.Ok).jobs.size)
        assertEquals("limit=10", listedQueries.single())

        // 查询链路下载图片：走快照里的 download_url
        val downloaded = client.downloadJobImages(config, listed.jobs.single())
        assertArrayEquals(pngBytes, downloaded.single())
    }

    // region helpers

    private fun client() = RemoteImageGenerationClient(
        OkHttpClient.Builder()
            // 真实 socket，但所有请求都在本机，2 秒足够
            .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    )

    private fun startServer(handler: (HttpExchange) -> Unit) {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/") { exchange -> handler(exchange) }
        started.executor = Executors.newFixedThreadPool(2)
        started.start()
        server = started
        baseUrl = "http://127.0.0.1:${started.address.port}"
    }

    private fun HttpExchange.readBody(): String =
        requestBody.use { it.readBytes() }.toString(Charsets.UTF_8)

    private fun HttpExchange.respondJson(code: Int, body: String) =
        respondBytes(code, body.toByteArray(), "application/json; charset=utf-8")

    private fun HttpExchange.respondBytes(
        code: Int,
        bytes: ByteArray,
        contentType: String = "application/octet-stream",
    ) {
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    // endregion
}
