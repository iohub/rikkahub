package me.rerere.ai.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageEncodeCacheTest {

    private val jpegBytes = ByteArray(1024) { index -> (index % 97).toByte() }

    private fun newCacheDir(): File =
        File(System.getProperty("java.io.tmpdir"), "image_encode_cache_${System.nanoTime()}").apply {
            mkdirs()
            deleteOnExit()
        }

    private fun onlyCacheFile(cacheDir: File): File {
        val files = File(cacheDir, "encoded_images")
            .listFiles { file -> file.isFile && file.extension == "jpg" }
            ?.toList()
            .orEmpty()
        assertEquals(1, files.size)
        return files.single()
    }

    @Test
    fun `put should store ciphertext and get should return original bytes`() {
        val cacheDir = newCacheDir()
        ImageEncodeCache.init(cacheDir)

        ImageEncodeCache.put("key-cipher", jpegBytes)

        assertTrue(AttachmentCipher.isEncrypted(onlyCacheFile(cacheDir)))
        assertArrayEquals(jpegBytes, ImageEncodeCache.get("key-cipher"))
    }

    @Test
    fun `get should migrate legacy plaintext entry to ciphertext`() {
        val cacheDir = newCacheDir()
        ImageEncodeCache.init(cacheDir)
        ImageEncodeCache.put("key-legacy", jpegBytes)

        // 模拟升级前写入的明文缓存条目
        val cached = onlyCacheFile(cacheDir)
        cached.writeBytes(jpegBytes)
        assertFalse(AttachmentCipher.isEncrypted(cached))

        // 命中时就地重写成密文，且本次仍然返回原字节
        assertArrayEquals(jpegBytes, ImageEncodeCache.get("key-legacy"))
        assertTrue(AttachmentCipher.isEncrypted(cached))
        // 迁移后照常命中
        assertArrayEquals(jpegBytes, ImageEncodeCache.get("key-legacy"))
    }
}
