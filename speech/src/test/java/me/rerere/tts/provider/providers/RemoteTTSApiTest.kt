package me.rerere.tts.provider.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTTSApiTest {
    @Test
    fun endpoint_keeps_v1_prefix_and_appends_path_segments() {
        val url = remoteTtsEndpoint("http://127.0.0.1:8080/v1", "audio", "voices")

        assertEquals("http://127.0.0.1:8080/v1/audio/voices", url.toString())
        assertEquals(listOf("v1", "audio", "voices"), url.pathSegments)
    }

    @Test
    fun endpoint_tolerates_trailing_slash_in_base_url() {
        val url = remoteTtsEndpoint("http://127.0.0.1:8080/v1/", "health")

        assertEquals("http://127.0.0.1:8080/v1/health", url.toString())
    }

    @Test
    fun endpoint_url_encodes_voice_name() {
        val url = remoteTtsEndpoint("http://127.0.0.1:8080/v1", "audio", "voices", "我的 音色/1")

        // 原始名字里的空格与斜杠必须被编码, 否则 DELETE 会打到错误的路径上
        val encoded = url.toString()
        assertFalse(encoded.contains(" "))
        assertTrue(encoded.contains("%20"))
        assertTrue(encoded.contains("%2F"))
        // 解码后仍能还原成原始音色名, 说明它只是单个路径段
        assertEquals(listOf("v1", "audio", "voices", "我的 音色/1"), url.pathSegments)
    }

    @Test
    fun endpoint_rejects_invalid_base_url() {
        val error = assertThrows(RemoteTTSApiException::class.java) {
            remoteTtsEndpoint("not a url", "health")
        }

        assertEquals(0, error.statusCode)
        assertTrue(error.message!!.contains("Invalid base url"))
    }

    @Test
    fun upload_size_guard_rejects_payload_above_20mb() {
        val oversize = ByteArray((REMOTE_TTS_MAX_UPLOAD_BYTES + 1).toInt())

        val error = assertThrows(RemoteTTSApiException::class.java) {
            checkRemoteUploadSize(oversize, "wav")
        }

        assertEquals(413, error.statusCode)
        assertTrue(error.message!!.contains("20MB"))
    }

    @Test
    fun upload_size_guard_accepts_small_and_empty_payloads() {
        checkRemoteUploadSize(ByteArray(0), "wav")
        checkRemoteUploadSize(ByteArray(1024), "spk")
    }

    @Test
    fun error_message_prefers_server_error_field() {
        val body = """{"error":{"message":"only valid for base models"}}"""

        assertEquals(
            "only valid for base models",
            remoteErrorMessage(400, "Bad Request", body),
        )
        assertEquals("only valid for base models", parseRemoteErrorMessage(body))
    }

    @Test
    fun error_message_falls_back_to_status_line() {
        assertEquals(
            "HTTP 500 Internal Server Error",
            remoteErrorMessage(500, "Internal Server Error", "<html>boom</html>"),
        )
        assertEquals(
            "HTTP 502 Bad Gateway",
            remoteErrorMessage(502, "Bad Gateway", ""),
        )
        // error.message 为空串时同样回落到状态码文案
        assertEquals(
            "HTTP 400 Bad Request",
            remoteErrorMessage(400, "Bad Request", """{"error":{"message":""}}"""),
        )
    }

    @Test
    fun parse_error_message_returns_null_for_unexpected_bodies() {
        assertNull(parseRemoteErrorMessage(""))
        assertNull(parseRemoteErrorMessage("not json"))
        assertNull(parseRemoteErrorMessage("""{"detail":"missing"}"""))
        assertNull(parseRemoteErrorMessage("""{"error":"plain string"}"""))
    }

    @Test
    fun voice_info_marks_registered_voices() {
        assertTrue(RemoteVoiceInfo(name = "my-clone", kind = "registered").isRegistered)
        assertFalse(RemoteVoiceInfo(name = "vivian", kind = "speaker").isRegistered)
    }

    @Test
    fun api_exception_retry_policy() {
        // 0 = 没有拿到 HTTP 响应 (网络层失败), 与 5xx/429/408 一样值得重试
        assertTrue(RemoteTTSApiException("network", statusCode = 0).isRetryable)
        assertTrue(RemoteTTSApiException("server", statusCode = 503).isRetryable)
        assertTrue(RemoteTTSApiException("too many", statusCode = 429).isRetryable)
        assertFalse(RemoteTTSApiException("bad request", statusCode = 400).isRetryable)
        assertFalse(RemoteTTSApiException("too large", statusCode = 413).isRetryable)
    }
}
