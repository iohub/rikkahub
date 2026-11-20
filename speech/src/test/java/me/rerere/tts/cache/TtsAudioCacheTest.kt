package me.rerere.tts.cache

import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [TtsAudioCache] 的 JVM 单测：不依赖 Android Context，用 java.io.tmpdir 下的临时目录。
 */
class TtsAudioCacheTest {
    private lateinit var root: File
    private val providerId = "8b7c6f2e-0000-4000-8000-000000000001"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("tts-audio-cache").toFile()
        TtsAudioCache.init(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `put then get returns identical audio`() {
        val audio = sampleAudio()
        val response = TTSResponse(audioData = audio, format = AudioFormat.WAV, sampleRate = 24000)

        TtsAudioCache.put(providerId, "key-1", response)
        val cached = TtsAudioCache.get(providerId, "key-1")

        assertEquals(AudioFormat.WAV, cached?.format)
        assertEquals(24000, cached?.sampleRate)
        assertArrayEquals(audio, cached?.audioData)
    }

    @Test
    fun `null sampleRate survives the round trip`() {
        val response = TTSResponse(audioData = sampleAudio(), format = AudioFormat.PCM, sampleRate = null)

        TtsAudioCache.put(providerId, "key-null-rate", response)
        val cached = TtsAudioCache.get(providerId, "key-null-rate")

        assertEquals(AudioFormat.PCM, cached?.format)
        assertNull(cached?.sampleRate)
        assertArrayEquals(sampleAudio(), cached?.audioData)
    }

    @Test
    fun `file on disk is encrypted and keeps the header`() {
        val audio = sampleAudio()
        TtsAudioCache.put(
            providerId = providerId,
            key = "key-file",
            response = TTSResponse(audioData = audio, format = AudioFormat.WAV, sampleRate = 24000),
        )

        val files = cacheFiles()
        assertEquals(1, files.size)
        val bytes = files.single().readBytes()

        // 4 字节 magic + 22 字节头 = 26 字节，密文长度与明文一致
        assertEquals(26 + audio.size, bytes.size)
        assertArrayEquals("RTA1".toByteArray(), bytes.copyOfRange(0, 4))
        assertFalse("明文不应出现在缓存文件里", bytes.containsSubArray(audio.copyOfRange(0, 32)))
    }

    @Test
    fun `truncated file is treated as a miss and removed`() {
        TtsAudioCache.put(
            providerId = providerId,
            key = "key-truncated",
            response = TTSResponse(audioData = sampleAudio(), format = AudioFormat.WAV, sampleRate = 24000),
        )
        val file = cacheFiles().single()
        file.writeBytes(file.readBytes().copyOf(10))

        assertNull(TtsAudioCache.get(providerId, "key-truncated"))
        assertFalse(file.exists())
    }

    @Test
    fun `tampered magic is treated as a miss and removed`() {
        TtsAudioCache.put(
            providerId = providerId,
            key = "key-magic",
            response = TTSResponse(audioData = sampleAudio(), format = AudioFormat.WAV, sampleRate = 24000),
        )
        val file = cacheFiles().single()
        val bytes = file.readBytes()
        bytes[0] = 'X'.code.toByte()
        file.writeBytes(bytes)

        assertNull(TtsAudioCache.get(providerId, "key-magic"))
        assertFalse(file.exists())
    }

    @Test
    fun `unknown format ordinal is treated as a miss and removed`() {
        TtsAudioCache.put(
            providerId = providerId,
            key = "key-format",
            response = TTSResponse(audioData = sampleAudio(), format = AudioFormat.WAV, sampleRate = 24000),
        )
        val file = cacheFiles().single()
        val bytes = file.readBytes()
        bytes[5] = 99
        file.writeBytes(bytes)

        assertNull(TtsAudioCache.get(providerId, "key-format"))
        assertFalse(file.exists())
    }

    @Test
    fun `same key does not duplicate files while another key does`() {
        val response = TTSResponse(audioData = sampleAudio(), format = AudioFormat.WAV, sampleRate = 24000)
        TtsAudioCache.put(providerId, "key-same", response)
        TtsAudioCache.put(providerId, "key-same", response)
        assertEquals(1, cacheFiles().size)

        TtsAudioCache.put(providerId, "key-other", response)
        assertEquals(2, cacheFiles().size)
    }

    @Test
    fun `buildKey depends on text voice and sampling params`() {
        val base = TTSProviderSetting.RemoteTTS(id = kotlin.uuid.Uuid.random())

        assertEquals(
            TtsAudioCache.buildKey(base, "hello"),
            TtsAudioCache.buildKey(base.copy(name = "改个显示名"), "hello"),
        )
        // 换 apiKey 不影响音频内容, 也不该让缓存全失效
        assertEquals(
            TtsAudioCache.buildKey(base, "hello"),
            TtsAudioCache.buildKey(base.copy(apiKey = "another-key"), "hello"),
        )
        assertNotEquals(
            TtsAudioCache.buildKey(base, "hello"),
            TtsAudioCache.buildKey(base, "hello there"),
        )
        assertNotEquals(
            TtsAudioCache.buildKey(base, "hello"),
            TtsAudioCache.buildKey(base.copy(voice = "my-clone"), "hello"),
        )
        assertNotEquals(
            TtsAudioCache.buildKey(base, "hello"),
            TtsAudioCache.buildKey(base.copy(temperature = 0.7f), "hello"),
        )
    }

    @Test
    fun `buildKey includes the dynamic instruct but keeps the legacy key when it is blank`() {
        val base = TTSProviderSetting.RemoteTTS(id = kotlin.uuid.Uuid.random(), instructions = "warm")

        // 不传 instruct 时与加入该参数之前算出来的 key 完全一致（旧缓存文件仍然命中）
        assertEquals(
            TtsAudioCache.buildKey(base, "hello"),
            TtsAudioCache.buildKey(base, "hello", instruct = ""),
        )
        // 纯空白等于"没指定"，与合并下发时的 trim 规则一致
        assertEquals(
            TtsAudioCache.buildKey(base, "hello"),
            TtsAudioCache.buildKey(base, "hello", instruct = "   "),
        )

        // 同一段文本换语气必须落成不同 key，否则会播到上一次语气的音频
        assertNotEquals(
            TtsAudioCache.buildKey(base, "hello"),
            TtsAudioCache.buildKey(base, "hello", instruct = "excited"),
        )
        assertNotEquals(
            TtsAudioCache.buildKey(base, "hello", instruct = "excited"),
            TtsAudioCache.buildKey(base, "hello", instruct = "sad"),
        )
        // 只差首尾空白不算换语气
        assertEquals(
            TtsAudioCache.buildKey(base, "hello", instruct = "excited"),
            TtsAudioCache.buildKey(base, "hello", instruct = "  excited  "),
        )
    }

    @Test
    fun `stats and clear count cache entries`() {
        assertEquals(0 to 0L, TtsAudioCache.stats(providerId))

        val audio = sampleAudio()
        val response = TTSResponse(audioData = audio, format = AudioFormat.WAV, sampleRate = 24000)
        TtsAudioCache.put(providerId, "key-a", response)
        TtsAudioCache.put(providerId, "key-b", response)

        val (count, bytes) = TtsAudioCache.stats(providerId)
        assertEquals(2, count)
        assertEquals(2L * (26 + audio.size), bytes)

        assertEquals(2, TtsAudioCache.clear(providerId))
        assertEquals(0 to 0L, TtsAudioCache.stats(providerId))
        assertNull(TtsAudioCache.get(providerId, "key-a"))
    }

    private fun cacheFiles(): List<File> {
        val dir = File(root, TtsAudioCache.FOLDER_NAME).resolve(providerId)
        return dir.listFiles().orEmpty().filter { it.isFile && it.extension == "bin" }
    }

    private fun sampleAudio(): ByteArray = ByteArray(1024) { (it % 251).toByte() }

    private fun ByteArray.containsSubArray(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        for (start in 0..(size - needle.size)) {
            var matched = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }
}
