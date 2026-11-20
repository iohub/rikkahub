package me.rerere.tts.cache

import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSResponse
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [TtsFixedAudioStore] 的 JVM 单测：不依赖 Android Context，用 java.io.tmpdir 下的临时目录。
 */
class TtsFixedAudioStoreTest {
    private lateinit var root: File
    private val messageId = "8b7c6f2e-0000-4000-8000-000000000001"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("tts-fixed-audio").toFile()
        TtsFixedAudioStore.init(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `write then read returns identical audio with format and sample rate`() {
        val audio = sampleAudio()
        assertTrue(
            TtsFixedAudioStore.writeChunk(
                messageId,
                0,
                TTSResponse(audioData = audio, format = AudioFormat.WAV, sampleRate = 24000),
            )
        )

        val stored = TtsFixedAudioStore.readChunk(messageId, 0)
        assertEquals(AudioFormat.WAV, stored?.format)
        assertEquals(24000, stored?.sampleRate)
        assertArrayEquals(audio, stored?.audioData)
        assertEquals(1, TtsFixedAudioStore.chunkCount(messageId))
    }

    @Test
    fun `null sampleRate survives the round trip`() {
        assertTrue(
            TtsFixedAudioStore.writeChunk(
                messageId,
                0,
                TTSResponse(audioData = sampleAudio(), format = AudioFormat.PCM, sampleRate = null),
            )
        )

        val stored = TtsFixedAudioStore.readChunk(messageId, 0)
        assertEquals(AudioFormat.PCM, stored?.format)
        assertNull(stored?.sampleRate)
        assertArrayEquals(sampleAudio(), stored?.audioData)
    }

    @Test
    fun `chunk file uses the same on-disk format as the audio cache`() {
        val audio = sampleAudio()
        TtsFixedAudioStore.writeChunk(
            messageId,
            0,
            TTSResponse(audioData = audio, format = AudioFormat.WAV, sampleRate = 24000),
        )

        val bytes = chunkFile(messageId, 0).readBytes()
        // 4 字节 magic + 22 字节头 = 26 字节，密文长度与明文一致 —— 与 TtsAudioCache 完全同格式
        assertEquals(26 + audio.size, bytes.size)
        assertArrayEquals("RTA1".toByteArray(), bytes.copyOfRange(0, 4))
        assertFalse("明文不应出现在固化文件里", bytes.containsSubArray(audio.copyOfRange(0, 32)))
    }

    @Test
    fun `writing the same index again replaces the audio`() {
        val first = ByteArray(512) { 1 }
        val second = ByteArray(768) { 2 }
        assertTrue(
            TtsFixedAudioStore.writeChunk(
                messageId,
                0,
                TTSResponse(audioData = first, format = AudioFormat.WAV, sampleRate = 24000),
            )
        )
        // 「重新生成」走的就是这条路径
        assertTrue(
            TtsFixedAudioStore.writeChunk(
                messageId,
                0,
                TTSResponse(audioData = second, format = AudioFormat.MP3, sampleRate = 48000),
            )
        )

        val stored = TtsFixedAudioStore.readChunk(messageId, 0)
        assertArrayEquals(second, stored?.audioData)
        assertEquals(AudioFormat.MP3, stored?.format)
        assertEquals(48000, stored?.sampleRate)
        // 覆盖写只留一个文件，也不会留下 tmp_*.part
        assertEquals(1, TtsFixedAudioStore.chunkCount(messageId))
        assertEquals(listOf("0.bin"), chunkFileNames(messageId))
    }

    @Test
    fun `chunkCount stops at the first gap`() {
        listOf(0, 1, 3).forEach { index ->
            assertTrue(
                TtsFixedAudioStore.writeChunk(
                    messageId,
                    index,
                    TTSResponse(audioData = sampleAudio(), format = AudioFormat.WAV, sampleRate = 24000),
                )
            )
        }

        // 0、1 连续，缺 2 → 只认前两片；索引空洞不能算进可播放范围
        assertEquals(2, TtsFixedAudioStore.chunkCount(messageId))
        assertNull(TtsFixedAudioStore.readChunk(messageId, 2))
        assertEquals(3, chunkFileNames(messageId).size)
    }

    @Test
    fun `corrupted chunk is treated as missing and removed`() {
        assertTrue(
            TtsFixedAudioStore.writeChunk(
                messageId,
                0,
                TTSResponse(audioData = sampleAudio(), format = AudioFormat.WAV, sampleRate = 24000),
            )
        )
        val file = chunkFile(messageId, 0)
        file.writeBytes(file.readBytes().copyOf(10))

        assertNull(TtsFixedAudioStore.readChunk(messageId, 0))
        assertFalse(file.exists())
        assertEquals(0, TtsFixedAudioStore.chunkCount(messageId))
    }

    @Test
    fun `deleteMessage clearAll and stats match what is on disk`() {
        val audio = sampleAudio()
        val response = TTSResponse(audioData = audio, format = AudioFormat.WAV, sampleRate = 24000)
        val chunkBytes = 26L + audio.size

        assertTrue(TtsFixedAudioStore.writeChunk("msg-a", 0, response))
        assertTrue(TtsFixedAudioStore.writeChunk("msg-a", 1, response))
        assertTrue(TtsFixedAudioStore.writeChunk("msg-b", 0, response))

        assertEquals(2L * chunkBytes, TtsFixedAudioStore.totalBytes("msg-a"))
        assertEquals(3L * chunkBytes, TtsFixedAudioStore.totalBytes("msg-b") + TtsFixedAudioStore.totalBytes("msg-a"))
        assertEquals(2 to 3L * chunkBytes, TtsFixedAudioStore.totalStats())

        assertTrue(TtsFixedAudioStore.deleteMessage("msg-a"))
        assertFalse("目录已删除，再次删除应返回 false", TtsFixedAudioStore.deleteMessage("msg-a"))
        assertEquals(0, TtsFixedAudioStore.chunkCount("msg-a"))
        assertEquals(0L, TtsFixedAudioStore.totalBytes("msg-a"))
        assertNull(TtsFixedAudioStore.readChunk("msg-a", 0))

        assertEquals(1 to chunkBytes, TtsFixedAudioStore.totalStats())
        assertEquals(1, TtsFixedAudioStore.clearAll())
        assertEquals(0 to 0L, TtsFixedAudioStore.totalStats())
        assertEquals(0, TtsFixedAudioStore.clearAll())
    }

    @Test
    fun `unsafe message id cannot escape the fixed audio directory`() {
        val unsafeId = "../../etc/passwd"
        assertTrue(
            TtsFixedAudioStore.writeChunk(
                unsafeId,
                0,
                TTSResponse(audioData = sampleAudio(), format = AudioFormat.WAV, sampleRate = 24000),
            )
        )

        val sanitized = TtsFixedAudioStore.safeDirName(unsafeId)
        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains("\\"))
        assertFalse(sanitized.contains(".."))

        val fixedRoot = File(root, TtsFixedAudioStore.FOLDER_NAME)
        assertEquals(listOf(sanitized), fixedRoot.listFiles().orEmpty().map { it.name })
        assertTrue(File(fixedRoot, "$sanitized/0.bin").isFile)
        // 只有 tts_fixed 一个目录被创建，没有任何东西被写到 filesDir 之外
        assertEquals(listOf(TtsFixedAudioStore.FOLDER_NAME), root.listFiles().orEmpty().map { it.name })
        assertEquals(1, TtsFixedAudioStore.chunkCount(unsafeId))
    }

    @Test
    fun `every method is a safe no-op before init`() {
        val rootField = TtsFixedAudioStore::class.java.getDeclaredField("rootDir")
        rootField.isAccessible = true
        rootField.set(TtsFixedAudioStore, null)

        val response = TTSResponse(audioData = sampleAudio(), format = AudioFormat.WAV, sampleRate = 24000)
        assertFalse(TtsFixedAudioStore.writeChunk(messageId, 0, response))
        assertNull(TtsFixedAudioStore.readChunk(messageId, 0))
        assertEquals(0, TtsFixedAudioStore.chunkCount(messageId))
        assertEquals(0L, TtsFixedAudioStore.totalBytes(messageId))
        assertEquals(0 to 0L, TtsFixedAudioStore.totalStats())
        assertEquals(0, TtsFixedAudioStore.clearAll())
        assertFalse(TtsFixedAudioStore.deleteMessage(messageId))
        assertNull(TtsFixedAudioStore.dirOf(messageId))

        // 恢复 init，避免影响同一 JVM 里的其它用例
        TtsFixedAudioStore.init(root)
    }

    @Test
    fun `negative index is rejected`() {
        val response = TTSResponse(audioData = sampleAudio(), format = AudioFormat.WAV, sampleRate = 24000)
        assertFalse(TtsFixedAudioStore.writeChunk(messageId, -1, response))
        assertNull(TtsFixedAudioStore.readChunk(messageId, -1))
        assertEquals(0, TtsFixedAudioStore.chunkCount(messageId))
    }

    private fun chunkDir(messageId: String): File =
        TtsFixedAudioStore.dirOf(messageId) ?: error("fixed audio dir missing for $messageId")

    private fun chunkFile(messageId: String, index: Int): File =
        File(chunkDir(messageId), "$index.bin")

    private fun chunkFileNames(messageId: String): List<String> =
        chunkDir(messageId).listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()

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
