package me.rerere.ai.util

import android.util.Log
import java.io.File
import java.security.MessageDigest

private const val TAG = "ImageEncodeCache"

/**
 * 发送给模型前压缩好的图片（JPEG 字节）的磁盘缓存。
 *
 * 历史图片在每轮请求、每次工具调用后都会重新构建请求体，没有缓存时每次都要全尺寸解码再 JPEG 编码。
 * key 包含原图路径、修改时间、大小和压缩参数，原图变化后自然换 key；旧条目按最近使用时间淘汰。
 * 由 [me.rerere.ai.provider.ProviderManager] 初始化缓存目录；未初始化时（例如单元测试）不缓存。
 *
 * 缓存文件落盘前会走 [AttachmentCipher] 加密：这里存的是**明文** JPEG 副本，不加密等于绕过了
 * 原图的落盘加密。旧版本留下的明文缓存文件仍可读（magic 不匹配即直通）。
 */
object ImageEncodeCache {
    // 压缩逻辑变化时递增，让旧缓存失效
    private const val VERSION = 1
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    @Volatile
    private var dir: File? = null

    fun init(cacheDir: File) {
        dir = cacheDir.resolve("encoded_images")
    }

    /** 命中时返回解密后的 JPEG 字节；损坏的条目按未命中处理 */
    internal fun get(key: String): ByteArray? {
        val file = fileOf(key) ?: return null
        if (!file.isFile || file.length() == 0L) return null
        val bytes = AttachmentCipher.readBytesDecrypted(file)
        if (bytes == null || bytes.isEmpty()) {
            file.delete()
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        migrateLegacyPlaintext(file, bytes)
        return bytes
    }

    /**
     * 升级前写下的缓存条目还是明文 JPEG，命中时直接返回就永远不会被加密，会以明文一直留到
     * 256MB 淘汰。这里就地原子重写成密文。
     *
     * 并发安全：重写走「同目录唯一临时文件 + rename」，多个读并发进来各自写自己的临时文件，
     * 最后写者胜，内容等价（IV 不同）；rename 前后的读者分别打开旧 inode（明文可读）或新文件
     * （magic 命中走解密），不会读到半个文件。失败只记日志，不影响本次返回 —— 本次的明文已经
     * 拿到了，下次命中再试一次。
     */
    private fun migrateLegacyPlaintext(file: File, bytes: ByteArray) {
        if (AttachmentCipher.isEncrypted(file)) return
        runCatching {
            writeEncryptedAtomically(file, bytes)
        }.onFailure {
            Log.w(TAG, "Failed to migrate plaintext cache entry to ciphertext", it)
        }
    }

    internal fun put(key: String, bytes: ByteArray) {
        val target = fileOf(key) ?: return
        val parent = target.parentFile ?: return
        runCatching {
            writeEncryptedAtomically(target, bytes)
            trim(parent)
        }.onFailure {
            Log.w(TAG, "Failed to write encoded image cache", it)
        }
    }

    /**
     * 加密后原子落盘：内容先写同目录下唯一命名的临时文件，再 rename 覆盖目标，避免并发请求
     * 读到写了一半的缓存，也避免并发迁移时撞同一个临时文件名。
     */
    private fun writeEncryptedAtomically(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: return
        parent.mkdirs()
        val payload = AttachmentCipher.encryptBytes(bytes)
        val temp = File.createTempFile("tmp_", ".part", parent)
        try {
            temp.writeBytes(payload)
            if (!temp.renameTo(target)) {
                target.delete()
                check(temp.renameTo(target)) { "Failed to move cache file to $target" }
            }
        } finally {
            temp.delete()
        }
    }

    private fun fileOf(key: String): File? {
        val dir = dir ?: return null
        return dir.resolve("${sha256("$VERSION|$key")}.jpg")
    }

    @Synchronized
    private fun trim(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.extension == "jpg" } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_TOTAL_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_TOTAL_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
