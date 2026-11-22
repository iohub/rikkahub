package me.rerere.rikkahub.data.files

import android.util.Log
import android.webkit.MimeTypeMap
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.util.AttachmentCipher
import okio.Buffer
import java.io.File

/**
 * Coil 的本地文件读取钩子：附件图片在磁盘上是 [AttachmentCipher] 加密过的，解码器必须先拿到
 * 明文字节。
 *
 * 注册点只有一处（`RouteActivity` 的 singleton ImageLoader），因此聊天缩略图、点开大图、
 * Markdown 内嵌图、工具卡片、文生图预览全部自动覆盖。
 *
 * @param file 已确认是密文的文件；明文请求不会被这个 Fetcher 接走。
 */
internal class AttachmentFetcher private constructor(
    private val file: File,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bytes = withContext(Dispatchers.IO) { AttachmentCipher.readBytesDecrypted(file) }
        if (bytes == null) {
            Log.w(TAG, "fetch: failed to decrypt ${file.name}")
            return null
        }
        return SourceFetchResult(
            // ImageSource 持有 BufferedSource，和内置 ByteArrayFetcher 的构造方式一致
            source = ImageSource(Buffer().write(bytes), options.fileSystem),
            mimeType = imageMimeTypeOf(file),
            dataSource = DataSource.DISK,
        )
    }

    /**
     * 只接管 `file://` 且文件头是我们 magic 的请求。
     *
     * 返回 null 时 Coil 会继续尝试内置的 FileUriFetcher —— 存量明文图片、缓存目录里的临时
     * 明文、视频/音频都走原来的链路，零额外开销（一次 4 字节读取）。
     */
    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "file") return null
            val path = data.path ?: return null
            val file = File(path)
            if (!file.isFile) return null
            if (file.length() > MAX_DECRYPT_BYTES) {
                Log.w(TAG, "create: ${file.name} is too large to decrypt in memory")
                return null
            }
            if (!AttachmentCipher.isEncrypted(file)) return null
            return AttachmentFetcher(file, options)
        }
    }

    companion object {
        private const val TAG = "AttachmentFetcher"

        /** 单张图解密进内存的上限，超过就放弃（交回默认链路，让解码器自己报错） */
        private const val MAX_DECRYPT_BYTES = 128L * 1024 * 1024

        /**
         * 加密前后扩展名不变，所以按扩展名猜 mime 是安全的；猜不到就返回 null 交给解码器嗅探。
         */
        private fun imageMimeTypeOf(file: File): String? {
            val extension = file.extension.lowercase()
            if (extension.isEmpty()) return null
            return MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension)
                ?.takeIf { it.startsWith("image/") }
        }
    }
}
