package me.rerere.tts.provider.providers

import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTTSProviderTest {
    @Test
    fun default_setting_only_sends_input_and_response_format() {
        // model 默认是 "qwen3-tts-base" (非空), 这里显式清空以覆盖"无可选字段"的极端情况
        val setting = TTSProviderSetting.RemoteTTS(model = "")

        val body = buildRemoteSpeechRequest(setting, "Hello there")

        assertEquals(setOf("input", "response_format"), body.keys)
        assertEquals("Hello there", body["input"]!!.jsonPrimitive.content)
        assertEquals("wav", body["response_format"]!!.jsonPrimitive.content)
    }

    @Test
    fun all_optional_fields_are_sent_with_snake_case_keys() {
        val setting = TTSProviderSetting.RemoteTTS(
            model = "qwen-talker-1.7b",
            voice = "my-clone",
            language = "Chinese",
            instructions = "cheerful and warm",
            responseFormat = "pcm",
            seed = 42,
            maxNewTokens = 1024,
            temperature = 0.7f,
            topK = 30,
            topP = 0.9f,
            repetitionPenalty = 1.1f,
            subtalkerTemperature = 0.5f,
            subtalkerTopK = 20,
            subtalkerTopP = 0.8f,
        )

        val body = buildRemoteSpeechRequest(setting, "你好")

        // input + response_format + 4 个字符串字段 + 9 个采样参数
        assertEquals(15, body.size)
        assertEquals(
            setOf(
                "input",
                "response_format",
                "model",
                "voice",
                "language",
                "instructions",
                "seed",
                "max_new_tokens",
                "temperature",
                "top_k",
                "top_p",
                "repetition_penalty",
                "subtalker_temperature",
                "subtalker_top_k",
                "subtalker_top_p",
            ),
            body.keys,
        )

        assertEquals("你好", body["input"]!!.jsonPrimitive.content)
        assertEquals("pcm", body["response_format"]!!.jsonPrimitive.content)
        assertEquals("qwen-talker-1.7b", body["model"]!!.jsonPrimitive.content)
        assertEquals("my-clone", body["voice"]!!.jsonPrimitive.content)
        assertEquals("Chinese", body["language"]!!.jsonPrimitive.content)
        assertEquals("cheerful and warm", body["instructions"]!!.jsonPrimitive.content)

        assertEquals(42, body["seed"]!!.jsonPrimitive.int)
        assertEquals(1024, body["max_new_tokens"]!!.jsonPrimitive.int)
        assertEquals(0.7f, body["temperature"]!!.jsonPrimitive.float, 0f)
        assertEquals(30, body["top_k"]!!.jsonPrimitive.int)
        assertEquals(0.9f, body["top_p"]!!.jsonPrimitive.float, 0f)
        assertEquals(1.1f, body["repetition_penalty"]!!.jsonPrimitive.float, 0f)
        assertEquals(0.5f, body["subtalker_temperature"]!!.jsonPrimitive.float, 0f)
        assertEquals(20, body["subtalker_top_k"]!!.jsonPrimitive.int)
        assertEquals(0.8f, body["subtalker_top_p"]!!.jsonPrimitive.float, 0f)
    }

    @Test
    fun model_is_sent_only_when_not_blank() {
        val withModel = buildRemoteSpeechRequest(
            TTSProviderSetting.RemoteTTS(model = "qwen3-tts-base"),
            "hi",
        )
        assertEquals("qwen3-tts-base", withModel["model"]!!.jsonPrimitive.content)

        val withoutModel = buildRemoteSpeechRequest(
            TTSProviderSetting.RemoteTTS(model = ""),
            "hi",
        )
        assertNull(withoutModel["model"])
    }

    @Test
    fun blank_optional_strings_and_null_sampling_params_are_omitted() {
        val setting = TTSProviderSetting.RemoteTTS(
            model = "",
            voice = "",
            language = "",
            instructions = "   ",
            seed = null,
            temperature = null,
        )

        val body = buildRemoteSpeechRequest(setting, "hi")

        assertFalse(body.containsKey("voice"))
        assertFalse(body.containsKey("language"))
        assertFalse(body.containsKey("instructions"))
        assertFalse(body.containsKey("seed"))
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("top_k"))
    }

    @Test
    fun blank_response_format_falls_back_to_wav() {
        val body = buildRemoteSpeechRequest(
            TTSProviderSetting.RemoteTTS(responseFormat = ""),
            "hi",
        )

        assertEquals("wav", body["response_format"]!!.jsonPrimitive.content)
    }

    @Test
    fun default_seed_is_null_so_no_sampling_override_is_serialized() {
        // 默认配置不应携带任何采样参数, 保证"引擎默认"语义
        val body = buildRemoteSpeechRequest(TTSProviderSetting.RemoteTTS(), "hi")

        TTSProviderSetting.RemoteTTS.REMOTE_TTS_SAMPLING_PARAMS.forEach { param ->
            assertFalse("default request must not contain ${param.key}", body.containsKey(param.key))
        }
        // 默认 model 非空, 所以只剩 input / response_format / model 三个 key
        assertEquals(setOf("input", "response_format", "model"), body.keys)
        assertTrue(body["input"]!!.jsonPrimitive.content.isNotBlank())
    }
}
