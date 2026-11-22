package me.rerere.ai.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AttachmentCipherTest {

    private fun tempFile(bytes: ByteArray, name: String = "attachment.jpg"): File =
        File.createTempFile("attachment_cipher_", "_$name").apply {
            deleteOnExit()
            writeBytes(bytes)
        }

    private val samplePlain = ByteArray(2048) { index -> (index % 251).toByte() }

    @Test
    fun `encrypt then decrypt should round trip and keep header layout`() {
        val encrypted = AttachmentCipher.encryptBytes(samplePlain)

        assertEquals(AttachmentCipher.HEADER_BYTES + samplePlain.size, encrypted.size)
        assertEquals("RKI1", encrypted.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(AttachmentCipher.VERSION, encrypted[4])
        assertFalse(encrypted.copyOfRange(AttachmentCipher.HEADER_BYTES, encrypted.size).contentEquals(samplePlain))

        val decrypted = AttachmentCipher.decryptBytes(encrypted)
        assertNotNull(decrypted)
        assertArrayEquals(samplePlain, decrypted)
    }

    @Test
    fun `each encrypt call should use a fresh iv`() {
        val first = AttachmentCipher.encryptBytes(samplePlain)
        val second = AttachmentCipher.encryptBytes(samplePlain)

        assertFalse(
            first.copyOfRange(
                5,
                AttachmentCipher.HEADER_BYTES
            ).contentEquals(second.copyOfRange(5, AttachmentCipher.HEADER_BYTES))
        )
        assertFalse(first.contentEquals(second))
        assertArrayEquals(samplePlain, AttachmentCipher.decryptBytes(first))
        assertArrayEquals(samplePlain, AttachmentCipher.decryptBytes(second))
    }

    @Test
    fun `decrypt should pass plaintext through unchanged`() {
        // 升级前落盘的老图片：没有 magic，必须原样返回，不能崩
        val plain = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        assertArrayEquals(plain, AttachmentCipher.decryptBytes(plain))

        val empty = ByteArray(0)
        assertArrayEquals(empty, AttachmentCipher.decryptBytes(empty))
    }

    @Test
    fun `empty plaintext should round trip`() {
        val encrypted = AttachmentCipher.encryptBytes(ByteArray(0))
        assertEquals(AttachmentCipher.HEADER_BYTES, encrypted.size)
        assertArrayEquals(ByteArray(0), AttachmentCipher.decryptBytes(encrypted))
    }

    @Test
    fun `isEncrypted should distinguish plaintext and ciphertext files`() {
        val plainFile = tempFile(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        assertFalse(AttachmentCipher.isEncrypted(plainFile))

        val encryptedFile = tempFile(AttachmentCipher.encryptBytes(samplePlain))
        assertTrue(AttachmentCipher.isEncrypted(encryptedFile))

        assertFalse(AttachmentCipher.isEncrypted(File(plainFile.parentFile, "does_not_exist.jpg")))
    }

    @Test
    fun `encryptFileInPlace should keep file name and extension`() {
        val file = tempFile(samplePlain)
        val name = file.name

        assertTrue(AttachmentCipher.encryptFileInPlace(file))

        assertEquals(name, file.name)
        assertEquals("jpg", file.extension)
        assertEquals(AttachmentCipher.HEADER_BYTES + samplePlain.size, file.length().toInt())
        assertTrue(AttachmentCipher.isEncrypted(file))
        assertArrayEquals(samplePlain, AttachmentCipher.readBytesDecrypted(file))
        // 临时文件不能留在目录里
        assertFalse(File(file.parentFile, "$name.enc.tmp").exists())
    }

    @Test
    fun `encryptFileInPlace should be idempotent`() {
        val file = tempFile(samplePlain)

        assertTrue(AttachmentCipher.encryptFileInPlace(file))
        val once = file.readBytes()
        assertTrue(AttachmentCipher.encryptFileInPlace(file))

        assertArrayEquals(once, file.readBytes())
        assertArrayEquals(samplePlain, AttachmentCipher.readBytesDecrypted(file))
    }

    @Test
    fun `readBytesDecrypted should pass plaintext files through`() {
        val plainFile = tempFile(samplePlain)
        assertArrayEquals(samplePlain, AttachmentCipher.readBytesDecrypted(plainFile))
    }

    @Test
    fun `openInputStreamDecrypted should handle plaintext and ciphertext`() {
        val plainFile = tempFile(samplePlain)
        val plainRead = AttachmentCipher.openInputStreamDecrypted(plainFile)?.use { it.readBytes() }
        assertArrayEquals(samplePlain, plainRead)

        val encryptedFile = tempFile(AttachmentCipher.encryptBytes(samplePlain))
        val encryptedRead = AttachmentCipher.openInputStreamDecrypted(encryptedFile)?.use { it.readBytes() }
        assertArrayEquals(samplePlain, encryptedRead)
    }

    @Test
    fun `isImageFile should detect by mime or extension`() {
        val imageFile = tempFile(samplePlain)
        assertTrue(AttachmentCipher.isImageFile(imageFile, "image/png"))
        assertTrue(AttachmentCipher.isImageFile(imageFile, "IMAGE/JPEG"))
        // mime 缺失时靠扩展名兜底
        assertTrue(AttachmentCipher.isImageFile(imageFile, null))

        val document = tempFile(samplePlain, "document.pdf")
        assertFalse(AttachmentCipher.isImageFile(document, "application/pdf"))
        // 扩展名不像图片，但 mime 命中也要加密
        assertTrue(AttachmentCipher.isImageFile(document, "image/webp"))
        assertFalse(AttachmentCipher.isImageExtension("a.txt"))
        assertFalse(AttachmentCipher.isImageExtension(null))
    }
}
