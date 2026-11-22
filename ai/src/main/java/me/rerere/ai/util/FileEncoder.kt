package me.rerere.ai.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import android.util.Base64OutputStream
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessagePart
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

private val supportedTypes = setOf(
    "image/jpeg",
    "image/png",
    "image/gif",
    "image/webp",
)

data class EncodedImage(
    val base64: String,
    val mimeType: String
)

internal enum class ExifTransformType {
    NONE,
    FLIP_HORIZONTAL,
    ROTATE_180,
    FLIP_VERTICAL,
    TRANSPOSE,
    ROTATE_90,
    TRANSVERSE,
    ROTATE_270,
}

internal fun mapExifOrientationToTransform(orientation: Int): ExifTransformType = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransformType.FLIP_HORIZONTAL
    ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransformType.ROTATE_180
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransformType.FLIP_VERTICAL
    ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransformType.TRANSPOSE
    ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransformType.ROTATE_90
    ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransformType.TRANSVERSE
    ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransformType.ROTATE_270
    ExifInterface.ORIENTATION_NORMAL,
    ExifInterface.ORIENTATION_UNDEFINED
    -> ExifTransformType.NONE

    else -> ExifTransformType.NONE
}

fun UIMessagePart.Image.encodeBase64(withPrefix: Boolean = true): Result<EncodedImage> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            // 图片可能已落盘加密（AttachmentCipher）：mime 嗅探与解码都走「按需新开解密流」，
            // 不把整份明文读进内存（密文 N+21 与明文 N 同时驻留会让峰值翻倍，20MB 照片直接 OOM）
            val mimeType = file.guessMimeType().getOrThrow()
            // 统一进行压缩处理
            val (encoded, outputMimeType) = file.compressAndEncode(mimeType)
            EncodedImage(
                base64 = if (withPrefix) "data:$outputMimeType;base64,$encoded" else encoded,
                mimeType = outputMimeType
            )
        }

        this.url.startsWith("data:") -> {
            // 从 data URL 提取 mime type
            val mimeType = url.substringAfter("data:").substringBefore(";")
            EncodedImage(base64 = url, mimeType = mimeType)
        }
        this.url.startsWith("http") -> {
            // HTTP URL 无法确定 mime type，默认使用 image/png
            EncodedImage(base64 = url, mimeType = "image/png")
        }
        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

fun UIMessagePart.Video.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = file.encodeToBase64Streaming()
            if (withPrefix) "data:video/mp4;base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

fun UIMessagePart.Audio.encodeBase64(withPrefix: Boolean = true): Result<String> = runCatching {
    when {
        this.url.startsWith("file://") -> {
            val filePath =
                this.url.toUri().path ?: throw IllegalArgumentException("Invalid file URI: ${this.url}")
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File does not exist: ${this.url}")
            }
            val encoded = file.encodeToBase64Streaming()
            if (withPrefix) "data:audio/mp3;base64,$encoded" else encoded
        }

        else -> throw IllegalArgumentException("Unsupported URL format: $url")
    }
}

/**
 * 把图片压缩成 JPEG 再 base64。
 *
 * 全程按流处理：原图可能已落盘加密，明文只在解码/编码时短暂存在于位图与输出缓冲里。
 * 缓存 key 仍由[File]的路径 + mtime + size 派生，所以加密文件与明文文件的缓存互不干扰。
 */
private fun File.compressAndEncode(
    mimeType: String,
    maxDimension: Int = 10_000,
    maxPixels: Long = 16_000_000L,
    quality: Int = 85
): Pair<String, String> {
    // GIF 保持原样（可能是动图）：直接流式 base64，不把它整份读进内存
    if (mimeType == "image/gif") {
        return Pair(encodeToBase64Streaming(), mimeType)
    }

    // 历史图片每轮请求都会重新编码，命中缓存时跳过解码和 JPEG 重编码
    val cacheKey = "$absolutePath|${lastModified()}|${length()}|$maxDimension|$maxPixels|$quality"
    // 缓存文件可能在命中后、读取前被系统清理，读取失败按未命中处理，继续从原图编码
    ImageEncodeCache.get(cacheKey)
        ?.let { cached -> return Pair(cached.encodeToBase64Streaming(), "image/jpeg") }

    val jpegBytes = compressToJpeg(maxDimension = maxDimension, maxPixels = maxPixels, quality = quality)
    ImageEncodeCache.put(cacheKey, jpegBytes)
    return Pair(jpegBytes.encodeToBase64Streaming(), "image/jpeg")
}

private fun File.compressToJpeg(maxDimension: Int, maxPixels: Long, quality: Int): ByteArray {
    // 读取图片尺寸（解析失败时 outWidth/outHeight 为 -1，inSampleSize 会退化为 1）
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    // 解密流不可 seek（CipherInputStream），读完必须重开一条新流；每条流用 use 及时释放
    openDecryptedStream().use { input ->
        BitmapFactory.decodeStream(input, null, options)
    }

    options.inSampleSize = calculateImageInSampleSize(
        width = options.outWidth,
        height = options.outHeight,
        maxDimension = maxDimension,
        maxPixels = maxPixels
    )
    options.inJustDecodeBounds = false

    val bitmap = openDecryptedStream().use { input ->
        BitmapFactory.decodeStream(input, null, options)
    } ?: throw IllegalArgumentException("Failed to decode image: $absolutePath")
    val normalizedBitmap = normalizeByExif(bitmap)

    return try {
        val outputStream = ByteArrayOutputStream()
        // 强制使用 JPEG 格式，因为很多提供商不支持 webp
        check(normalizedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)) {
            "Failed to compress image: $absolutePath"
        }
        outputStream.toByteArray()
    } finally {
        if (normalizedBitmap !== bitmap) {
            normalizedBitmap.recycle()
        }
        bitmap.recycle()
    }
}

private fun File.normalizeByExif(bitmap: Bitmap): Bitmap {
    // 位图解码已经把上一条流读空了，EXIF 必须从另一条新开的解密流读；多包一层
    // BufferedInputStream 是为了给 ExifInterface 提供 mark/reset 能力（CipherInputStream 不支持）
    val orientation = runCatching {
        BufferedInputStream(openDecryptedStream(), EXIF_BUFFER_SIZE).use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val transform = mapExifOrientationToTransform(orientation)
    return applyExifTransform(bitmap, transform)
}

private fun applyExifTransform(bitmap: Bitmap, transform: ExifTransformType): Bitmap {
    if (transform == ExifTransformType.NONE) return bitmap

    val matrix = Matrix()
    when (transform) {
        ExifTransformType.NONE -> return bitmap
        ExifTransformType.FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifTransformType.ROTATE_180 -> matrix.setRotate(180f)
        ExifTransformType.FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifTransformType.TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_90 -> matrix.setRotate(90f)
        ExifTransformType.TRANSVERSE -> {
            matrix.setRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        ExifTransformType.ROTATE_270 -> matrix.setRotate(270f)
    }

    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrElse { bitmap }
}

/** 已经在内存里的明文字节（gif、压缩缓存）直接编码，省一次临时读写 */
private fun ByteArray.encodeToBase64Streaming(): String = inputStream().use { it.copyToBase64() }

/**
 * 流式 base64。视频/音频这次没有加密，但走 [AttachmentCipher.openInputStreamDecrypted] 的
 * 解密流可以让它们将来被加密时无需再改这里；明文文件是零成本直通。
 */
private fun File.encodeToBase64Streaming(): String {
    val input = AttachmentCipher.openInputStreamDecrypted(this)
        ?: error("Failed to open file: $absolutePath")
    return input.use { it.copyToBase64() }
}

private fun InputStream.copyToBase64(): String {
    val byteArrayOutputStream = ByteArrayOutputStream()
    Base64OutputStream(byteArrayOutputStream, Base64.NO_WRAP).use { base64Stream ->
        copyTo(base64Stream, bufferSize = 8 * 1024)
    }
    return byteArrayOutputStream.toString(Charsets.ISO_8859_1.name())
}

internal fun calculateImageInSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int,
    maxPixels: Long
): Int {
    if (width <= 0 || height <= 0) return 1

    var inSampleSize = 1
    while (
        (height / inSampleSize) > maxDimension ||
        (width / inSampleSize) > maxDimension ||
        (width.toLong() / inSampleSize) * (height.toLong() / inSampleSize) > maxPixels
    ) {
        inSampleSize *= 2
    }
    return inSampleSize
}

/**
 * 从**已解密**的文件头字节里嗅探图片 mime。
 *
 * 不能再看文件本身：加密后的文件头是 `RKI1` 而不是 `FF D8`。嗅探不出时退回扩展名
 * （文件名/扩展名在加密前后完全一致），两者都失败才报错。
 */
private fun guessMimeType(header: ByteArray, fileName: String): Result<String> = runCatching {
    if (header.size < 12) error("File too short to determine MIME type")
    val bytes = header

    // 判断 HEIF/HEIC/AVIF 格式：ISO-BMFF 容器，"ftyp" box 位于字节 4..8，主品牌码位于 8..12
    // 新手机的 HDR HEIF 照片常用 heix/hevc/mif1/msf1 等品牌码，而非仅 heic，需全部识别
    if (bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") {
        when (bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)) {
            "heic", "heix", "heim", "heis",
            "hevc", "hevx", "hevm", "hevs",
            "mif1", "msf1", "heif",
            -> return@runCatching "image/heic"

            "avif", "avis" -> return@runCatching "image/avif"
        }
    }

    // 判断 JPEG 格式：开头为 0xFF 0xD8
    if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
        return@runCatching "image/jpeg"
    }

    // 判断 PNG 格式：开头为 89 50 4E 47 0D 0A 1A 0A
    if (bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        )
    ) {
        return@runCatching "image/png"
    }

    // 判断WebP格式：开头为 "RIFF" + 4字节长度 + "WEBP"
    if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12)
            .toString(Charsets.US_ASCII) == "WEBP"
    ) {
        return@runCatching "image/webp"
    }

    // 判断 GIF 格式：开头为 "GIF89a" 或 "GIF87a"
    val header = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
    if (header == "GIF89a" || header == "GIF87a") {
        return@runCatching "image/gif"
    }

    error(
        "Failed to guess MIME type: $header, ${
            bytes.copyOfRange(0, minOf(16, bytes.size)).joinToString(",") {
                it.toUByte().toString()
            }
        }"
    )
}.recoverCatching { throwable ->
    // 扩展名兜底：文件名在加密前后不变，bmp 之类嗅探不到但能正常显示
    guessMimeTypeByExtension(fileName) ?: throw throwable
}

private fun guessMimeTypeByExtension(fileName: String): String? {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return extension
        .takeIf { it.isNotEmpty() }
        ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        ?.takeIf { it.startsWith("image/") }
}

/**
 * 新开一条解密流，只读文件头用于嗅探 mime —— 明文总长不进内存，加密文件看到的是解密后的字节。
 * 读不出头（IO 失败）时退回扩展名，与 [guessMimeType] 的兜底策略保持一致。
 */
private fun File.guessMimeType(): Result<String> {
    val header = ByteArray(MIME_SNIFF_BYTES)
    val size = runCatching {
        openDecryptedStream().use { it.readAtMost(header) }
    }.getOrElse { throwable ->
        return guessMimeTypeByExtension(name)?.let { Result.success(it) }
            ?: Result.failure(throwable)
    }
    return guessMimeType(header.copyOf(size), name)
}

/**
 * 打开一条新的解密流。调用方负责 close；解密流不可 seek，需要重复读同一文件时必须重新调用。
 */
private fun File.openDecryptedStream(): InputStream =
    AttachmentCipher.openInputStreamDecrypted(this)
        ?: throw IllegalStateException("Failed to read image: $absolutePath")

/** 尽力读满 [target]，提前遇到 EOF 就返回实际读到的字节数 */
private fun InputStream.readAtMost(target: ByteArray): Int {
    var total = 0
    while (total < target.size) {
        val read = read(target, total, target.size - total)
        if (read <= 0) break
        total += read
    }
    return total
}

/** mime 嗅探只需要前 12 字节，读 512 字节留足余量（HEIF 主品牌码在 8..12） */
private const val MIME_SNIFF_BYTES = 512

private const val EXIF_BUFFER_SIZE = 64 * 1024
