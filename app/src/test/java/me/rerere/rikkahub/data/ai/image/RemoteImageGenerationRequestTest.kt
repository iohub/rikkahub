package me.rerere.rikkahub.data.ai.image

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁死跟远程出图服务约定的 wire format。
 *
 * 这些断言全部来自服务端 README 的接口约定：字段名是 snake_case、可选字段缺失而不是 null、
 * 布尔是 JSON 布尔。任何一条回归都会让线上请求直接 400，所以用测试固化。
 */
class RemoteImageGenerationRequestTest {

    // 与 production 里 remote client 的 RequestJson 等价；那边的实例是 private 的，不去改它的可见性。
    // 注意：两个 Json 配置是手工同步的——改 RequestJson 的 explicitNulls / encodeDefaults 时这里要一起改，
    // 否则下面「null 字段必须消失」的用例测的是旧约定。
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private fun RemoteGenerateRequest.toJsonObject(): JsonObject =
        Json.parseToJsonElement(json.encodeToString(RemoteGenerateRequest.serializer(), this)).jsonObject

    private val camelCaseKeys = listOf("batchSize", "filenamePrefix", "noReference")

    @Test
    fun `full request is encoded with the snake case keys the service expects`() {
        val body = RemoteGenerateRequest(
            prompt = "a corgi wearing sunglasses",
            negative = "watermark",
            size = "1024x1024",
            steps = 4,
            seed = 42L,
            batchSize = 2,
            filenamePrefix = "rikkahub",
            noReference = false,
            images = listOf("data:image/png;base64,AAA"),
            timeout = 1200,
        ).toJsonObject()

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
            ),
            body.keys,
        )
        camelCaseKeys.forEach { key ->
            assertFalse("请求体里不能出现 camelCase 的 key: $key", body.containsKey(key))
        }

        // 数字字段必须是 JSON number，不是被转成字符串的 "4" / "42"
        assertEquals(4, body.getValue("steps").jsonPrimitive.int)
        assertEquals(42L, body.getValue("seed").jsonPrimitive.long)
        assertEquals(2, body.getValue("batch_size").jsonPrimitive.int)
        assertEquals(1200, body.getValue("timeout").jsonPrimitive.int)
        assertEquals("rikkahub", body.getValue("filename_prefix").jsonPrimitive.content)
    }

    @Test
    fun `null fields are omitted instead of being sent as null`() {
        // 服务端的布尔/数字字段不接受 null，所以没填的字段必须整个从请求体里消失
        val body = RemoteGenerateRequest(prompt = "only a prompt").toJsonObject()

        assertEquals(setOf("prompt"), body.keys)
        listOf("negative", "size", "steps", "seed", "no_reference", "images", "timeout", "batch_size")
            .forEach { key ->
                assertFalse("值为 null 的字段 $key 不应该出现在请求体里", body.containsKey(key))
            }
        camelCaseKeys.forEach { key ->
            assertFalse("请求体里不能出现 camelCase 的 key: $key", body.containsKey(key))
        }
    }

    @Test
    fun `no reference flag is a json boolean not a string`() {
        val flag = RemoteGenerateRequest(prompt = "text to image", noReference = true)
            .toJsonObject()
            .getValue("no_reference")
            .jsonPrimitive

        assertEquals(true, flag.boolean)
        assertFalse("布尔值不能被序列化成字符串 \"true\"", flag.isString)
    }

    @Test
    fun `reference images are encoded as a json array`() {
        val images = RemoteGenerateRequest(
            prompt = "edit this image",
            images = listOf("data:image/png;base64,AAA"),
        ).toJsonObject().getValue("images").jsonArray

        assertEquals(1, images.size)
        assertEquals("data:image/png;base64,AAA", images[0].jsonPrimitive.content)
    }

    @Test
    fun `success response is parsed and unknown fields are ignored`() {
        // 用仓库的 JsonInstant 解码：它是纯 kotlinx-serialization 的实例（ignoreUnknownKeys = true），
        // 不依赖 Android 类，JVM 单测里可直接用
        val response = JsonInstant.decodeFromString(RemoteGenerateResponse.serializer(), SUCCESS_RESPONSE)

        assertEquals(true, response.ok)
        assertEquals("766a0922-845f-47bc-b560-fc60b6970748", response.promptId)
        assertEquals(1, response.images.size)
        assertEquals("ComfyUI_00009__1.png", response.images[0].filename)
        assertEquals(
            "http://127.0.0.1:8080/files/ComfyUI_00009__1.png",
            response.images[0].downloadUrl,
        )
        assertEquals("/root/autodl-fs/api-comfy/outputs/ComfyUI_00009__1.png", response.images[0].savedPath)
        assertEquals(21.28, response.elapsedSec!!, 1e-9)

        // meta 原样透传给模型，不能丢字段；seed 超过 Int 范围，必须是 Long 级的原样文本
        assertNotNull(response.meta)
        assertEquals("768x768", response.meta?.get("size")?.jsonPrimitive?.content)
        assertEquals(2232042110076787348L, response.meta?.get("seed")?.jsonPrimitive?.long)
        assertEquals(4, response.meta?.get("steps")?.jsonPrimitive?.int)
    }

    @Test
    fun `failure response keeps the reason and the detail`() {
        val response = JsonInstant.decodeFromString(RemoteGenerateResponse.serializer(), FAILURE_RESPONSE)

        assertFalse(response.ok)
        assertTrue("error 不能为空", response.error?.isNotBlank() == true)
        assertTrue("error 应保留服务端原文", response.error!!.contains("超时"))
        assertNotNull("detail 要留给用户排查", response.detail)
        assertEquals("start:comfy", response.detail?.jsonObject?.get("step")?.jsonPrimitive?.content)
        assertTrue(response.images.isEmpty())
    }

    private companion object {
        // 服务端真实成功响应：多出的 comfy_filename / size_bytes / output_dir / resource 是未建模字段，
        // 解码不报错即证明 ignoreUnknownKeys 生效
        val SUCCESS_RESPONSE = """
            {"ok":true,"prompt_id":"766a0922-845f-47bc-b560-fc60b6970748","elapsed_sec":21.28,
             "images":[{"filename":"ComfyUI_00009__1.png","comfy_filename":"ComfyUI_00009_.png",
               "download_url":"http://127.0.0.1:8080/files/ComfyUI_00009__1.png",
               "saved_path":"/root/autodl-fs/api-comfy/outputs/ComfyUI_00009__1.png","size_bytes":731637}],
             "meta":{"seed":2232042110076787348,"size":"768x768","steps":4,"cfg":1},
             "output_dir":"/root/autodl-fs/api-comfy/outputs",
             "resource":{"mode":"comfy","restore_scheduled":true}}
        """.trimIndent()

        val FAILURE_RESPONSE = """
            {"ok":false,"error":"资源编排失败：start comfy 超时","detail":{"step":"start:comfy"},
             "resource":{"mode":"idle"}}
        """.trimIndent()
    }
}
