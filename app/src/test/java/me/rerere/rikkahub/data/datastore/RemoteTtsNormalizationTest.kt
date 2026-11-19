package me.rerere.rikkahub.data.datastore

import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 远程 TTS 的 responseFormat / sampleRate 只支持服务端列出的取值。
 *
 * 历史数据里可能残留 mp3/ogg 之类的格式（服务端不认，合成直接失败）或任意采样率，
 * 归一化必须在读盘时把它们收回到 wav / 24000，同时不动合法值。
 */
class RemoteTtsNormalizationTest {
    private fun normalizedRemote(provider: TTSProviderSetting.RemoteTTS): TTSProviderSetting.RemoteTTS =
        Settings(
            enableBuiltInTts = false,
            selectedTTSProviderId = provider.id,
            ttsProviders = listOf(provider),
        ).normalized().ttsProviders
            .filterIsInstance<TTSProviderSetting.RemoteTTS>()
            .first { it.id == provider.id }

    @Test
    fun `unsupported response format falls back to wav`() {
        val normalized = normalizedRemote(
            TTSProviderSetting.RemoteTTS(id = DEFAULT_REMOTE_TTS_ID, responseFormat = "mp3"),
        )

        assertEquals("wav", normalized.responseFormat)
    }

    @Test
    fun `unsupported sample rate falls back to 24000`() {
        val normalized = normalizedRemote(
            TTSProviderSetting.RemoteTTS(id = DEFAULT_REMOTE_TTS_ID, sampleRate = 8000),
        )

        assertEquals(24000, normalized.sampleRate)
    }

    @Test
    fun `supported values are preserved`() {
        val normalized = normalizedRemote(
            TTSProviderSetting.RemoteTTS(
                id = DEFAULT_REMOTE_TTS_ID,
                responseFormat = "pcm",
                sampleRate = 44100,
            ),
        )

        assertEquals("pcm", normalized.responseFormat)
        assertEquals(44100, normalized.sampleRate)
    }

    @Test
    fun `legacy uppercase format is lowercased instead of reset`() {
        val normalized = normalizedRemote(
            TTSProviderSetting.RemoteTTS(id = DEFAULT_REMOTE_TTS_ID, responseFormat = " WAV "),
        )

        assertEquals("wav", normalized.responseFormat)
    }
}
