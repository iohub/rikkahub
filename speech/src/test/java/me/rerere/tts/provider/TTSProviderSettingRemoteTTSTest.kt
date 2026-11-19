package me.rerere.tts.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
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
        assertEquals("wav", setting.responseFormat)
        assertEquals(24000, setting.sampleRate)
        assertEquals(0.8f, setting.temperature)
        assertEquals(-1, setting.seed)
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
            responseFormat = "pcm",
            sampleRate = 44100,
            temperature = 0.6f,
            seed = 42,
        )

        val json = Json.encodeToString(TTSProviderSetting.serializer(), original)

        assertTrue(json.contains("\"type\":\"remote\""))
        assertEquals(original, Json.decodeFromString(TTSProviderSetting.serializer(), json))
    }

    @Test
    fun remote_legacy_json_without_new_fields_falls_back_to_defaults() {
        // 旧配置里可能没有 language / temperature / seed 等字段, 反序列化时必须回落到默认值
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
        assertEquals(0.8f, decoded.temperature)
        assertEquals(-1, decoded.seed)
    }

    @Test
    fun remote_copyProvider_preserves_id_and_name() {
        val original = TTSProviderSetting.RemoteTTS(
            apiKey = "sk-test",
            voice = "my-clone",
            language = "English",
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
    }
}
