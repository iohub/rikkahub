package me.rerere.rikkahub.data.datastore

import kotlin.uuid.Uuid
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内置 TTS 开关同时约束着归一化与「当前选中提供商」的读取：关闭时系统 TTS 既不参与回退也不参与读取。
 *
 * 「远程 TTS 服务」设置页的开关直接依赖这条规则（关掉时要把选中项从系统 TTS 换走），
 * 改这里的行为前先看这个测试。
 */
class TtsProviderGateTest {
    private fun settings(
        enableBuiltInTts: Boolean,
        selectedTTSProviderId: Uuid,
    ) = Settings(
        enableBuiltInTts = enableBuiltInTts,
        selectedTTSProviderId = selectedTTSProviderId,
        ttsProviders = listOf(
            TTSProviderSetting.RemoteTTS(id = DEFAULT_REMOTE_TTS_ID),
            TTSProviderSetting.SystemTTS(id = DEFAULT_SYSTEM_TTS_ID),
        ),
    )

    @Test
    fun `closing built-in tts moves the selection off the system provider`() {
        val normalized = settings(
            enableBuiltInTts = false,
            selectedTTSProviderId = DEFAULT_SYSTEM_TTS_ID,
        ).normalized()

        assertEquals(DEFAULT_REMOTE_TTS_ID, normalized.selectedTTSProviderId)
    }

    @Test
    fun `closing built-in tts keeps a remote selection`() {
        val normalized = settings(
            enableBuiltInTts = false,
            selectedTTSProviderId = DEFAULT_REMOTE_TTS_ID,
        ).normalized()

        assertEquals(DEFAULT_REMOTE_TTS_ID, normalized.selectedTTSProviderId)
    }

    @Test
    fun `opening built-in tts keeps the system selection`() {
        val normalized = settings(
            enableBuiltInTts = true,
            selectedTTSProviderId = DEFAULT_SYSTEM_TTS_ID,
        ).normalized()

        assertEquals(DEFAULT_SYSTEM_TTS_ID, normalized.selectedTTSProviderId)
    }

    @Test
    fun `selected provider falls back to the remote one while built-in tts is off`() {
        val normalized = settings(
            enableBuiltInTts = false,
            selectedTTSProviderId = DEFAULT_SYSTEM_TTS_ID,
        ).normalized()

        assertEquals(DEFAULT_REMOTE_TTS_ID, normalized.getSelectedTTSProvider()?.id)
    }
}
