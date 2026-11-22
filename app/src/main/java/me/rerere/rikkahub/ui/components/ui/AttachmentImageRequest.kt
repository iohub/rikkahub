package me.rerere.rikkahub.ui.components.ui

import android.content.Context
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import java.io.File

/**
 * 附件图片的 Coil 请求构造器。
 *
 * 聊天附件在磁盘上是 `me.rerere.ai.util.AttachmentCipher` 加密过的，
 * `me.rerere.rikkahub.data.files.AttachmentFetcher` 会先解出明文再交给解码器。解码前的
 * 明文字节一旦进入 Coil 的磁盘缓存，就等于把加密又还原成明文落到 `cacheDir/coil3_disk_cache`，
 * 所以**指向本地文件的请求必须关掉磁盘缓存**。
 *
 * 远程图片（Markdown 内嵌图、头像等）不受影响：它们不是加密附件，磁盘缓存照常生效。
 */
fun attachmentImageRequestBuilder(context: Context, model: Any?): ImageRequest.Builder =
    ImageRequest.Builder(context)
        .data(model)
        .apply {
            if (isLocalFileModel(model)) {
                diskCachePolicy(CachePolicy.DISABLED)
            }
        }

/** `file://` 与裸绝对路径指向的都是本机文件，可能是加密附件 */
private fun isLocalFileModel(model: Any?): Boolean = when (model) {
    is File -> true
    is String -> model.startsWith("file:") || model.startsWith("/")
    else -> false
}
