package me.rerere.tts.controller

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.tts.cache.TtsAudioCache
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [TtsSynthesizer] 的 JVM 单测：注入一个假的「请求 → 音频流」动作，不需要 [TTSManager] / Context。
 *
 * 重点覆盖 [TtsSynthesizer.synthesize] 的 `bypassCache`：命中缓存时不请求、绕过缓存时一定重新合成，
 * 且新结果要写回缓存供后续普通播放命中。
 */
class TtsSynthesizerTest {
    private lateinit var root: File
    private val setting = TTSProviderSetting.RemoteTTS(cacheEnabled = true)

    private val oldAudio = ByteArray(512) { 7 }
    private val newAudio = ByteArray(640) { 9 }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("tts-synthesizer").toFile()
        TtsAudioCache.init(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `cache hit is served without calling the provider`() = runBlocking {
        var calls = 0
        val synthesizer = TtsSynthesizer { _, _ ->
            calls++
            audioFlow(oldAudio)
        }
        val chunk = chunk("hello")

        val first = synthesizer.synthesize(setting, chunk)
        assertEquals("首次是缓存 miss，必须请求一次", 1, calls)

        val second = synthesizer.synthesize(setting, chunk)
        assertEquals("第二次应命中 TtsAudioCache，不再请求", 1, calls)
        assertArrayEquals(first.audioData, second.audioData)
    }

    @Test
    fun `bypassCache forces a new synthesis and refreshes the cache`() = runBlocking {
        var calls = 0
        val synthesizer = TtsSynthesizer { _, _ ->
            calls++
            audioFlow(if (calls == 1) oldAudio else newAudio)
        }
        val chunk = chunk("hello")

        val cached = synthesizer.synthesize(setting, chunk)
        assertArrayEquals(oldAudio, cached.audioData)
        assertEquals(1, calls)

        // 重新生成：即使缓存里有旧音频，也必须真的再请求一次
        val regenerated = synthesizer.synthesize(setting, chunk, bypassCache = true)
        assertEquals(2, calls)
        assertArrayEquals("bypassCache 必须拿到新合成的音频，而不是旧缓存", newAudio, regenerated.audioData)

        // 新结果覆盖了缓存：随后普通播放直接命中新音频，不再请求服务端
        val afterRegenerate = synthesizer.synthesize(setting, chunk)
        assertEquals(2, calls)
        assertArrayEquals(newAudio, afterRegenerate.audioData)
    }

    @Test
    fun `bypassCache still works when caching is disabled`() = runBlocking {
        var calls = 0
        val synthesizer = TtsSynthesizer { _, _ ->
            calls++
            audioFlow(newAudio)
        }
        val noCacheSetting = setting.copy(cacheEnabled = false)
        val chunk = chunk("hello")

        synthesizer.synthesize(noCacheSetting, chunk)
        synthesizer.synthesize(noCacheSetting, chunk, bypassCache = true)

        assertEquals("缓存关闭时不落盘，每次都请求", 2, calls)
        assertEquals(0, TtsAudioCache.stats(setting.id.toString()).first)
    }

    @Test
    fun `provider gets the chunk text as the request payload`() = runBlocking {
        val requests = mutableListOf<String>()
        val synthesizer = TtsSynthesizer { _, request ->
            requests += request.text
            audioFlow(newAudio)
        }

        synthesizer.synthesize(setting, TtsChunk(index = 0, text = "第一片"))
        synthesizer.synthesize(setting, TtsChunk(index = 1, text = "第二片"))

        assertEquals(listOf("第一片", "第二片"), requests)
    }

    private fun chunk(text: String) = TtsChunk(index = 0, text = text)

    private fun audioFlow(bytes: ByteArray) = flowOf(
        AudioChunk(data = bytes, format = AudioFormat.WAV, sampleRate = 24000, isLast = true)
    )
}
