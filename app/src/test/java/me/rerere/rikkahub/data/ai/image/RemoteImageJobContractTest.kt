package me.rerere.rikkahub.data.ai.image

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁死异步任务链路（`/api/generate/async` + `/api/jobs`）的 wire format。
 *
 * 断网续传完全依赖这几件事：`client_job_id` 真的被发出去、快照里的 `job_id` / `progress` /
 * `download_url` 能被解出来、以及多出来的字段不会让解码炸掉。任何一条回归都会让
 * 「断网后重连拿回图片」静默失效，所以用测试固化。
 */
class RemoteImageJobContractTest {

    // 与 production 里 remote client 的 RequestJson 等价（那边是 private，不去改可见性）。
    // 注意：改 RequestJson 的 explicitNulls / encodeDefaults 时这里要一起改。
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private fun RemoteGenerateRequest.toJsonObject(): JsonObject =
        Json.parseToJsonElement(json.encodeToString(RemoteGenerateRequest.serializer(), this)).jsonObject

    // region 请求体：client_job_id

    @Test
    fun `client job id is sent as a snake case key`() {
        val body = RemoteGenerateRequest(
            prompt = "a corgi",
            clientJobId = "123e4567-e89b-12d3-a456-426614174000",
        ).toJsonObject()

        assertEquals(
            setOf("prompt", "client_job_id"),
            body.keys,
        )
        assertEquals(
            "123e4567-e89b-12d3-a456-426614174000",
            body.getValue("client_job_id").jsonPrimitive.content,
        )
        assertFalse("不能出现 camelCase 的 key", body.containsKey("clientJobId"))
    }

    @Test
    fun `missing client job id keeps the old wire contract`() {
        // 为 null 时必须整条字段消失：旧同步接口不认识它
        val body = RemoteGenerateRequest(prompt = "only a prompt").toJsonObject()

        assertEquals(setOf("prompt"), body.keys)
        assertFalse(body.containsKey("client_job_id"))
    }

    @Test
    fun `async request keeps every legacy field next to client job id`() {
        val body = RemoteGenerateRequest(
            prompt = "a corgi",
            negative = "watermark",
            size = "768x768",
            steps = 4,
            seed = 42L,
            batchSize = 2,
            filenamePrefix = "rikkahub",
            noReference = true,
            images = listOf("data:image/png;base64,AAA"),
            timeout = 1800,
            clientJobId = "job-1",
        ).toJsonObject()

        // 异步接口的字段和同步接口完全一致，只多一个 client_job_id
        assertEquals(
            setOf(
                "prompt",
                "negative",
                "size",
                "steps",
                "seed",
                "batch_size",
                "filename_prefix",
                "no_reference",
                "images",
                "timeout",
                "client_job_id",
            ),
            body.keys,
        )
        assertEquals(4, body.getValue("steps").jsonPrimitive.int)
        assertEquals(1800, body.getValue("timeout").jsonPrimitive.int)
    }

    // endregion

    // region 任务快照解码

    @Test
    fun `succeeded snapshot is decoded field by field`() {
        val snapshot = JsonInstant.decodeFromString<RemoteJobSnapshot>(SUCCEEDED_SNAPSHOT)

        assertEquals(true, snapshot.ok)
        assertEquals("8f5a3f0d-6a1f-4d20-9f0d-2c1b2a9c1e77", snapshot.jobId)
        assertEquals("11111111-2222-3333-4444-555555555555", snapshot.clientJobId)
        assertEquals(JOB_STATUS_SUCCEEDED, snapshot.status)
        assertEquals("done", snapshot.stage)
        assertEquals(1732345678.123, snapshot.createdAt!!, 1e-9)
        assertEquals(1732345679.5, snapshot.startedAt!!, 1e-9)
        assertEquals(1732345700.75, snapshot.finishedAt!!, 1e-9)
        assertEquals("766a0922-845f-47bc-b560-fc60b6970748", snapshot.promptId)
        assertEquals("/api/jobs/8f5a3f0d-6a1f-4d20-9f0d-2c1b2a9c1e77", snapshot.statusUrl)

        // progress 决定超时文案与 elapsed_sec
        assertEquals(21.1, snapshot.progress?.elapsedSec!!, 1e-9)
        assertEquals(1800.0, snapshot.progress?.timeoutSec!!, 1e-9)

        // download_url 是断网重连后唯一可用的取图地址，必须解出来
        val image = snapshot.images.single()
        assertEquals("ComfyUI_00009__1.png", image.filename)
        assertEquals("ComfyUI_00009_.png", image.comfyFilename)
        assertEquals(
            "http://192.168.1.10:8080/files/ComfyUI_00009__1.png",
            image.downloadUrl,
        )
        assertEquals(
            "http://192.168.1.10:8080/files/ComfyUI_00009__1.png?download=1",
            image.downloadUrlAttachment,
        )
        assertEquals("/root/autodl-fs/api-comfy/outputs/ComfyUI_00009__1.png", image.savedPath)
        assertEquals(731637L, image.sizeBytes)

        // 失败态字段在成功快照里是 null，解码不能报错
        assertNull(snapshot.error)
        assertNull(snapshot.httpStatus)

        // detail / resource / meta / request 原样透传，一个字段都不能丢
        assertEquals("start:comfy", snapshot.detail?.jsonObject?.get("step")?.jsonPrimitive?.content)
        assertEquals(
            "comfy",
            snapshot.resource?.jsonObject?.get("mode")?.jsonPrimitive?.content,
        )
        assertEquals(2232042110076787348L, snapshot.meta?.get("seed")?.jsonPrimitive?.long)
        assertEquals("768x768", snapshot.request?.get("size")?.jsonPrimitive?.content)
        assertEquals(1800, snapshot.request?.get("timeout")?.jsonPrimitive?.int)
    }

    @Test
    fun `failed snapshot keeps the error the http status and an empty image list`() {
        val snapshot = JsonInstant.decodeFromString<RemoteJobSnapshot>(FAILED_SNAPSHOT)

        assertEquals(JOB_STATUS_FAILED, snapshot.status)
        assertEquals("资源编排失败：start comfy 超时", snapshot.error)
        assertEquals(502, snapshot.httpStatus)
        assertTrue("非成功态不该有图片", snapshot.images.isEmpty())
        assertEquals(1800.0, snapshot.progress?.timeoutSec!!, 1e-9)
    }

    @Test
    fun `unknown extra fields and missing fields are tolerated`() {
        // 服务端会持续加字段；客户端也必须能接受「只有 job_id」的最小快照
        val snapshot = JsonInstant.decodeFromString<RemoteJobSnapshot>(
            """{"ok":true,"job_id":"job-min","brand_new_field":{"nested":1}}"""
        )

        assertEquals("job-min", snapshot.jobId)
        assertNull(snapshot.status)
        assertNull(snapshot.progress)
        assertTrue(snapshot.images.isEmpty())
    }

    @Test
    fun `job list response is decoded with its compact snapshots`() {
        val response = JsonInstant.decodeFromString<RemoteJobsResponse>(JOBS_RESPONSE)

        assertEquals(true, response.ok)
        assertEquals(2, response.count)
        assertEquals(2, response.jobs.size)
        assertEquals("job-new", response.jobs[0].jobId)
        assertEquals(JOB_STATUS_SUCCEEDED, response.jobs[0].status)
        assertEquals(1, response.jobs[0].images.size)
        assertEquals(
            "http://10.0.0.2:8080/files/ComfyUI_00011__1.png",
            response.jobs[0].images.single().downloadUrl,
        )
        assertEquals(JOB_STATUS_RUNNING, response.jobs[1].status)
        assertEquals("generating", response.jobs[1].stage)
        assertTrue("非成功态列表项没有图片", response.jobs[1].images.isEmpty())
    }

    @Test
    fun `not found error body carries the reason`() {
        val error = JsonInstant.decodeFromString<RemoteGenerateResponse>(
            """{"ok":false,"error":"任务不存在：nope"}"""
        )

        assertEquals(false, error.ok)
        assertEquals("任务不存在：nope", error.error)
        assertTrue("404 时不该有图片", error.images.isEmpty())
    }

    @Test
    fun `unmodelled image fields do not break decoding`() {
        // 服务端 images[] 里还有 subfolder / type / url 等未建模字段，必须被忽略而不是报错
        val raw = Json.parseToJsonElement(SUCCEEDED_SNAPSHOT).jsonObject
            .getValue("images").jsonArray.single().jsonObject
        assertTrue("用例本身要覆盖到未建模字段", raw.containsKey("subfolder") && raw.containsKey("type"))

        val image = JsonInstant.decodeFromString<RemoteJobSnapshot>(SUCCEEDED_SNAPSHOT).images.single()
        assertEquals("ComfyUI_00009__1.png", image.filename)
    }

    // endregion

    private companion object {
        val SUCCEEDED_SNAPSHOT = """
            {
              "ok": true,
              "job_id": "8f5a3f0d-6a1f-4d20-9f0d-2c1b2a9c1e77",
              "client_job_id": "11111111-2222-3333-4444-555555555555",
              "status": "succeeded",
              "stage": "done",
              "created_at": 1732345678.123,
              "started_at": 1732345679.5,
              "finished_at": 1732345700.75,
              "progress": {"elapsed_sec": 21.1, "timeout_sec": 1800.0},
              "prompt_id": "766a0922-845f-47bc-b560-fc60b6970748",
              "images": [{
                "filename": "ComfyUI_00009__1.png",
                "comfy_filename": "ComfyUI_00009_.png",
                "subfolder": "",
                "type": "output",
                "download_url": "http://192.168.1.10:8080/files/ComfyUI_00009__1.png",
                "download_url_attachment": "http://192.168.1.10:8080/files/ComfyUI_00009__1.png?download=1",
                "saved_path": "/root/autodl-fs/api-comfy/outputs/ComfyUI_00009__1.png",
                "size_bytes": 731637
              }],
              "error": null,
              "http_status": null,
              "detail": {"step": "start:comfy"},
              "resource": {"mode": "comfy", "restore_scheduled": true},
              "meta": {"seed": 2232042110076787348, "size": "768x768", "steps": 4},
              "request": {"prompt": "a corgi", "size": "768x768", "steps": 4, "timeout": 1800},
              "status_url": "/api/jobs/8f5a3f0d-6a1f-4d20-9f0d-2c1b2a9c1e77"
            }
        """.trimIndent()

        val FAILED_SNAPSHOT = """
            {
              "ok": true,
              "job_id": "job-failed",
              "client_job_id": "client-failed",
              "status": "failed",
              "stage": "failed",
              "created_at": 1732345678.0,
              "started_at": 1732345679.0,
              "finished_at": 1732345800.0,
              "progress": {"elapsed_sec": 121.0, "timeout_sec": 1800.0},
              "prompt_id": null,
              "images": [],
              "error": "资源编排失败：start comfy 超时",
              "http_status": 502,
              "detail": {"step": "start:comfy"},
              "resource": {"mode": "idle"},
              "status_url": "/api/jobs/job-failed"
            }
        """.trimIndent()

        val JOBS_RESPONSE = """
            {
              "ok": true,
              "count": 2,
              "jobs": [
                {
                  "job_id": "job-new", "client_job_id": "client-new", "status": "succeeded",
                  "stage": "done", "created_at": 1732345700.0, "progress": {"elapsed_sec": 30.0},
                  "images": [{
                    "filename": "ComfyUI_00011__1.png",
                    "download_url": "http://10.0.0.2:8080/files/ComfyUI_00011__1.png",
                    "size_bytes": 100
                  }],
                  "request": {"size": "768x768"}, "status_url": "/api/jobs/job-new"
                },
                {
                  "job_id": "job-old", "client_job_id": null, "status": "running",
                  "stage": "generating", "created_at": 1732345600.0,
                  "progress": {"elapsed_sec": 5.0}, "images": [],
                  "status_url": "/api/jobs/job-old"
                }
              ]
            }
        """.trimIndent()
    }
}
