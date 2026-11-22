package me.rerere.ai.util

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 聊天附件（当前只用于图片）的落盘加密。
 *
 * 格式：`magic("RKI1",4) + version(1) + IV(16) + AES/CTR 密文`，即 21 字节头 + 与明文等长的密文
 * （CTR 是流模式，无 padding）。放在 ai 模块是因为 [FileEncoder] 送模型前要解密，而 app 依赖 ai；
 * 反过来放 app 会让 ai 反向依赖 app。
 *
 * ## 安全边界（与 `speech/tts/cache/TtsAudioCodec` 同一套权衡，请勿误解）
 *
 * 这是「防止 filesDir 里的图片被顺手翻看 / 误传」的**混淆级**保护，不是强加密：密钥由 App 内的
 * 固定常量派生、硬编码在安装包里，任何拿到 APK 的人都能解开。之所以不用 AndroidKeyStore，
 * 是因为它的密钥不可导出，换设备、重装、恢复备份后历史图片将永久无法解密 —— 而「换机后照片
 * 还能打开」是本功能的硬需求。
 *
 * ## 向后兼容
 *
 * 解密入口全部先看 magic：不是 `"RKI1"` 就当作明文原样放行，所以升级前存下来的老图片、
 * 以及将来被明文写回的文件，都不会因此崩掉。加密前后文件名与扩展名保持不变（Room 里存量
 * `file://` url 已持久化，改名会让历史消息的图片全部失效）。
 */
object AttachmentCipher {
    private const val TAG = "AttachmentCipher"

    /** 文件头魔数，用来区分「我们加密过的」和「明文老文件」 */
    private const val MAGIC = "RKI1"

    /** 当前格式版本。将来换算法时递增，[decryptBytes] 对未知版本返回 null 而不是解出噪声 */
    const val VERSION: Byte = 0x01

    private const val IV_BYTES = 16

    /** magic(4) + version(1) + IV(16) */
    const val HEADER_BYTES = 4 + 1 + IV_BYTES

    private val MAGIC_BYTES = MAGIC.toByteArray(Charsets.US_ASCII)

    private const val TRANSFORMATION = "AES/CTR/NoPadding"

    /** 固定常量派生 256-bit 密钥：换设备、重装 App、恢复备份后仍然能解开 */
    private const val KEY_SEED = "rikkahub.attachment.image.v1"

    private const val COPY_BUFFER_SIZE = 64 * 1024

    private val secretKey: SecretKeySpec by lazy {
        val key = MessageDigest.getInstance("SHA-256").digest(KEY_SEED.toByteArray())
        SecretKeySpec(key.copyOf(32), "AES")
    }

    private val random = SecureRandom()

    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif",
    )

    /** mimeType 以 `image/` 开头即视为图片 */
    fun isImageMimeType(mimeType: String?): Boolean =
        mimeType?.trim()?.lowercase()?.startsWith("image/") == true

    /** 扩展名属于图片集合（远端有时候不给 mime，只能靠名字） */
    fun isImageExtension(fileName: String?): Boolean {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext.isNotEmpty() && ext in imageExtensions
    }

    /** 写入侧的唯一判定入口：mime 或扩展名任一命中就加密 */
    fun isImageFile(file: File, mimeType: String?): Boolean =
        isImageMimeType(mimeType) || isImageExtension(file.name)

    /** 只读文件头判断是否已加密（4 字节 magic，任意版本都算） */
    fun isEncrypted(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < MAGIC_BYTES.size.toLong()) return@runCatching false
        val header = ByteArray(HEADER_BYTES)
        val read = file.inputStream().use { it.readUpTo(header) }
        read >= MAGIC_BYTES.size && hasMagic(header)
    }.getOrDefault(false)

    /** 加 21 字节头并加密，每次写入都换新 IV */
    fun encryptBytes(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val payload = cipher(Cipher.ENCRYPT_MODE, iv).doFinal(plain)
        val out = ByteArray(HEADER_BYTES + payload.size)
        MAGIC_BYTES.copyInto(out, 0)
        out[4] = VERSION
        iv.copyInto(out, 5)
        payload.copyInto(out, HEADER_BYTES)
        return out
    }

    /**
     * 解密 [data]；没有 magic 时**原样返回**（明文直通）。
     *
     * 头部损坏、版本不认识、解密异常一律返回 null，绝不向上抛异常 —— UI 与网络层只看到
     * 「读不到」而不是崩溃。
     */
    fun decryptBytes(data: ByteArray): ByteArray? {
        if (!hasMagic(data)) return data
        if (data.size < HEADER_BYTES) {
            Log.w(TAG, "decryptBytes: truncated header, size=${data.size}")
            return null
        }
        val version = data[4]
        if (version != VERSION) {
            Log.w(TAG, "decryptBytes: unsupported version=$version")
            return null
        }
        val iv = data.copyOfRange(5, HEADER_BYTES)
        return runCatching {
            cipher(Cipher.DECRYPT_MODE, iv).doFinal(data, HEADER_BYTES, data.size - HEADER_BYTES)
        }.onFailure {
            Log.w(TAG, "decryptBytes: failed to decrypt ${data.size} bytes", it)
        }.getOrNull()
    }

    /** 读文件并解密；明文文件直通，读/解密失败返回 null */
    fun readBytesDecrypted(file: File): ByteArray? = runCatching {
        decryptBytes(file.readBytes())
    }.onFailure {
        Log.w(TAG, "readBytesDecrypted: failed to read ${file.name}", it)
    }.getOrNull()

    /**
     * 流式解密，给大文件 / 流式 base64 用；明文文件直接返回原始流（指针回到 0）。
     *
     * 注意 CTR 也不做完整性校验，密文被截断只会少解出一点数据，不会抛异常。
     */
    fun openInputStreamDecrypted(file: File): InputStream? = runCatching {
        val input = BufferedInputStream(file.inputStream(), COPY_BUFFER_SIZE)
        input.mark(HEADER_BYTES)
        val header = ByteArray(HEADER_BYTES)
        val read = input.readUpTo(header)
        if (read < MAGIC_BYTES.size || !hasMagic(header)) {
            // 明文：把读掉的几个字节推回去，走直通
            input.reset()
            return@runCatching input
        }
        if (read < HEADER_BYTES || header[4] != VERSION) {
            input.close()
            Log.w(TAG, "openInputStreamDecrypted: unsupported header for ${file.name}")
            return@runCatching null
        }
        val iv = header.copyOfRange(5, HEADER_BYTES)
        CipherInputStream(input, cipher(Cipher.DECRYPT_MODE, iv))
    }.onFailure {
        Log.w(TAG, "openInputStreamDecrypted: failed to open ${file.name}", it)
    }.getOrNull()

    /**
     * 原地加密：先写同目录 `<name>.enc.tmp` 再 rename 覆盖原文件，文件名与扩展名保持不变。
     *
     * 失败时删掉临时文件、原文件保持明文（读侧直通），不会留下半个坏文件。已经是密文则直接
     * 返回 true，可以安全地重复调用。
     */
    fun encryptFileInPlace(file: File): Boolean {
        if (!file.isFile) return false
        if (isEncrypted(file)) return true

        val temp = File(file.parentFile ?: return false, "${file.name}.enc.tmp")
        return try {
            val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
            val cipher = cipher(Cipher.ENCRYPT_MODE, iv)
            FileOutputStream(temp).use { output ->
                output.write(MAGIC_BYTES)
                output.write(VERSION.toInt())
                output.write(iv)
                file.inputStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        val chunk = cipher.update(buffer, 0, read)
                        if (chunk != null && chunk.isNotEmpty()) output.write(chunk)
                    }
                    val tail = cipher.doFinal()
                    if (tail.isNotEmpty()) output.write(tail)
                }
                output.flush()
            }
            // 同一目录下 rename 是原子的：读侧要么看到完整明文，要么看到完整密文
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "encryptFileInPlace: failed to encrypt ${file.name}", e)
            temp.delete()
            false
        }
    }

    private fun cipher(mode: Int, iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, secretKey, IvParameterSpec(iv))
        }

    private fun hasMagic(data: ByteArray): Boolean {
        if (data.size < MAGIC_BYTES.size) return false
        for (index in MAGIC_BYTES.indices) {
            if (data[index] != MAGIC_BYTES[index]) return false
        }
        return true
    }

    private fun InputStream.readUpTo(target: ByteArray): Int {
        var total = 0
        while (total < target.size) {
            val read = read(target, total, target.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }
}
