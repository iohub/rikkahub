package me.rerere.rikkahub.data.files

import android.content.Context
import android.util.Log
import me.rerere.ai.util.AttachmentCipher
import java.io.File
import java.util.UUID

/**
 * 把「落盘加密的附件」临时解成明文文件，供必须拿到真实路径的消费方使用：
 * 交给系统应用打开（FileProvider）、写进相册（MediaStore）等。
 *
 * 临时文件放在 `cacheDir/attachment_plaintext/`，不参与备份打包；每次准备新文件前顺手清掉
 * 超过 [MAX_AGE_MS] 的旧文件 —— 分享出去的临时文件不能立刻删（接收方是异步读取的），
 * 只能按时间兜底清理。
 */
object AttachmentPlaintextCache {
    private const val TAG = "AttachmentPlaintextCache"
    private const val DIR = "attachment_plaintext"
    private const val MAX_AGE_MS = 60L * 60 * 1000

    /**
     * 明文文件直接返回自身（调用方不要删）；密文文件解密后写到 cacheDir 临时文件。
     * 解密/写盘失败返回 null。
     */
    fun prepare(context: Context, file: File): File? {
        if (!file.isFile) return null
        if (!AttachmentCipher.isEncrypted(file)) return file

        val plain = AttachmentCipher.readBytesDecrypted(file)
        if (plain == null) {
            Log.w(TAG, "prepare: failed to decrypt ${file.name}")
            return null
        }

        val dir = File(context.cacheDir, DIR)
        dir.mkdirs()
        sweep(dir)
        val extension = file.extension.takeIf { it.isNotEmpty() } ?: "bin"
        return runCatching {
            File(dir, "${UUID.randomUUID()}.$extension").apply { writeBytes(plain) }
        }.onFailure {
            Log.w(TAG, "prepare: failed to write plaintext temp for ${file.name}", it)
        }.getOrNull()
    }

    /** 清掉过期的临时明文文件 */
    fun sweep(context: Context) {
        sweep(File(context.cacheDir, DIR))
    }

    private fun sweep(dir: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }
}
