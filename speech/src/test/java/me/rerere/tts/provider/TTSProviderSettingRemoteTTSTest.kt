package me.rerere.tts.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSProviderSettingRemoteTTSTest {
    @Test
    fun remote_defaults_are_expected() {
        val setting = TTSProviderSetting.RemoteTTS()

        assertEquals("Remote TTS", setting.name)
        assertEquals("http://127.0.0.1:8080/v1", setting.baseUrl)
        assertEquals("", setting.apiKey)
        assertEquals("qwen3-tts-base", setting.model)
        assertEquals("", setting.voice)
        assertEquals("", setting.language)
        assertEquals("", setting.instructions)
        assertEquals("wav", setting.responseFormat)
        assertEquals(24000, setting.sampleRate)
        // 9 个采样参数默认全为 null = 不下发 = 保留服务端引擎默认值
        assertNull(setting.seed)
        assertNull(setting.maxNewTokens)
        assertNull(setting.temperature)
        assertNull(setting.topK)
        assertNull(setting.topP)
        assertNull(setting.repetitionPenalty)
        assertNull(setting.subtalkerTemperature)
        assertNull(setting.subtalkerTopK)
        assertNull(setting.subtalkerTopP)
    }

    @Test
    fun remote_is_registered_in_provider_types() {
        // 没有注册到 Types 列表的话, 设置页的下拉菜单里就不会出现 Remote 选项, 也没法新建
        assertTrue(TTSProviderSetting.Types.contains(TTSProviderSetting.RemoteTTS::class))
    }

    @Test
    fun remote_round_trips_with_serial_name() {
        val original: TTSProviderSetting = TTSProviderSetting.RemoteTTS(
            name = "My Remote",
            baseUrl = "http://192.168.1.10:8080/v1",
            apiKey = "sk-test",
            model = "qwen3-tts-base",
            voice = "my-clone",
            language = "Chinese",
            instructions = "cheerful and warm",
            responseFormat = "pcm",
            sampleRate = 44100,
            seed = 42,
            maxNewTokens = 1024,
            temperature = 0.6f,
            topK = 30,
            topP = 0.9f,
            repetitionPenalty = 1.1f,
            subtalkerTemperature = 0.5f,
            subtalkerTopK = 20,
            subtalkerTopP = 0.8f,
        )

        val json = Json.encodeToString(TTSProviderSetting.serializer(), original)

        assertTrue(json.contains("\"type\":\"remote\""))
        assertEquals(original, Json.decodeFromString(TTSProviderSetting.serializer(), json))
    }

    @Test
    fun remote_legacy_json_without_new_fields_falls_back_to_defaults() {
        // 旧配置里可能没有 language / instructions / 采样参数等字段, 反序列化时必须回落到默认值
        val legacyJson = buildJsonObject {
            put("type", "remote")
            put("name", "Remote TTS")
            put("baseUrl", "http://127.0.0.1:8080/v1")
        }.toString()

        val decoded = Json.decodeFromString(TTSProviderSetting.serializer(), legacyJson)

        assertTrue(decoded is TTSProviderSetting.RemoteTTS)
        decoded as TTSProviderSetting.RemoteTTS
        assertEquals("Remote TTS", decoded.name)
        assertEquals("qwen3-tts-base", decoded.model)
        assertEquals("wav", decoded.responseFormat)
        assertEquals(24000, decoded.sampleRate)
        assertEquals("", decoded.instructions)
        assertNull(decoded.temperature)
        assertNull(decoded.seed)
    }

    @Test
    fun remote_legacy_json_with_numeric_sampling_params_still_decodes() {
        // 旧版本把 temperature:Float=0.8f / seed:Int=-1 写成非空字段, 改成可空后仍必须能读出来
        val legacyJson = """
            {
              "type": "remote",
              "name": "Remote TTS",
              "baseUrl": "http://127.0.0.1:8080/v1",
              "apiKey": "sk-legacy",
              "responseFormat": "pcm",
              "sampleRate": 44100,
              "temperature": 0.8,
              "seed": -1
            }
        """.trimIndent()

        val decoded = Json.decodeFromString(TTSProviderSetting.serializer(), legacyJson)

        assertTrue(decoded is TTSProviderSetting.RemoteTTS)
        decoded as TTSProviderSetting.RemoteTTS
        assertEquals("sk-legacy", decoded.apiKey)
        assertEquals("pcm", decoded.responseFormat)
        assertEquals(44100, decoded.sampleRate)
        assertEquals(0.8f, decoded.temperature)
        assertEquals(-1, decoded.seed)
        // 旧 JSON 里没有的字段仍然是 null
        assertNull(decoded.topK)
    }

    @Test
    fun remote_copyProvider_preserves_id_and_name() {
        val original = TTSProviderSetting.RemoteTTS(
            apiKey = "sk-test",
            voice = "my-clone",
            language = "English",
            instructions = "whisper softly",
        )

        val copied = original.copyProvider(
            id = original.id,
            name = "Renamed Remote"
        ) as TTSProviderSetting.RemoteTTS

        assertEquals(original.id, copied.id)
        assertEquals("Renamed Remote", copied.name)
        // 其余字段必须保持不变
        assertEquals("sk-test", copied.apiKey)
        assertEquals("my-clone", copied.voice)
        assertEquals("English", copied.language)
        assertEquals("whisper softly", copied.instructions)
    }

    @Test
    fun remote_choice_lists_match_server_docs() {
        assertEquals(listOf("wav", "pcm"), TTSProviderSetting.RemoteTTS.REMOTE_TTS_RESPONSE_FORMATS)
        assertEquals(
            listOf(16000, 22050, 24000, 32000, 44100),
            TTSProviderSetting.RemoteTTS.REMOTE_TTS_SAMPLE_RATES
        )
        assertEquals(11, TTSProviderSetting.RemoteTTS.REMOTE_TTS_LANGUAGES.size)
        assertEquals("auto", TTSProviderSetting.RemoteTTS.REMOTE_TTS_LANGUAGES.first())
        assertTrue(TTSProviderSetting.RemoteTTS.REMOTE_TTS_LANGUAGES.contains("Chinese"))
        assertTrue(TTSProviderSetting.RemoteTTS.REMOTE_TTS_LANGUAGES.contains("English"))
    }

    @Test
    fun remote_sampling_param_metadata_covers_all_server_keys_in_order() {
        val params = TTSProviderSetting.RemoteTTS.REMOTE_TTS_SAMPLING_PARAMS

        assertEquals(9, params.size)
        assertEquals(
            listOf(
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
            params.map { it.key }
        )

        val seed = TTSProviderSetting.RemoteTTS.samplingParamsOf("seed")
        assertNotNull(seed)
        // seed 没有滑杆范围概念, 引擎默认 -1 表示随机
        assertEquals(false, seed!!.hasRange)
        assertEquals(-1f, seed.defaultValue)
        assertEquals(false, seed.isFloat)

        val temperature = TTSProviderSetting.RemoteTTS.samplingParamsOf("temperature")
        assertNotNull(temperature)
        assertEquals(0.9f, temperature!!.defaultValue)
        assertEquals(0f, temperature.min)
        assertEquals(2f, temperature.max)
        assertEquals(0.05f, temperature.step)
        assertTrue(temperature.isFloat)

        // 每个参数都有非空 label, 且默认值落在范围内 (seed 无范围除外)
        params.forEach { param ->
            assertTrue("label of ${param.key} should not be blank", param.label.isNotBlank())
            if (param.hasRange) {
                assertTrue(
                    "default of ${param.key} should be within range",
                    param.defaultValue >= param.min && param.defaultValue <= param.max
                )
            }
        }

        assertNull(TTSProviderSetting.RemoteTTS.samplingParamsOf("not_a_param"))
    }
}
