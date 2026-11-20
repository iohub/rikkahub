package me.rerere.tts.cache

import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSResponse
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * TTS 音频的二进制编解码层，由 [TtsAudioCache]（可被淘汰的缓存）与 [TtsFixedAudioStore]
 * （用户显式固化的音频）共用：两边落盘格式必须逐字节一致，读回来只有一套实现。
 *
 * 格式：`magic("RTA1",4) + version(1) + AudioFormat.ordinal(1) + sampleRate(4) + IV(16) + AES/CTR 密文`，
 * 即 22 字节头 + 与明文等长的密文（CTR 是流模式，无 padding）。
 *
 * ## 安全边界（与 [TtsAudioCache] 的说明一致，请勿误解）
 *
 * 这是「防止 .bin 被随手翻看 / 误传」的**混淆级**保护，不是强加密：密钥由 App 内的固定常量派生、
 * 硬编码在安装包里；AES/CTR 不做真实性校验，密文被篡改只会解出噪声。之所以不用 AndroidKeyStore，
 * 是因为它的密钥不可导出，换设备/重装后备份里的 .bin 将永久无法解密。
 */

/** 格式版本，读取时不匹配就当作 miss（返回 null），便于将来平滑换格式 */
internal const val TTS_AUDIO_FORMAT_VERSION = 1

internal const val TTS_AUDIO_IV_BYTES = 16

// magic(4) + version(1) + AudioFormat.ordinal(1) + sampleRate(4) + IV(16)
internal const val TTS_AUDIO_HEADER_BYTES = 4 + 1 + 1 + 4 + TTS_AUDIO_IV_BYTES

internal val TTS_AUDIO_MAGIC = "RTA1".toByteArray()

// 固定常量派生 256-bit 密钥：换设备、重装 App 后仍然能解开备份里的 .bin
private val secretKey: SecretKeySpec by lazy {
    val key = MessageDigest.getInstance("SHA-256")
        .digest("rikkahub.tts.audio.cache.v1".toByteArray())
    SecretKeySpec(key, "AES")
}

private val random = SecureRandom()

/** 把 [response] 编码成落盘字节（头 + 密文），每次写入都换新 IV。 */
internal fun encodeTtsAudio(response: TTSResponse): ByteArray {
    val iv = ByteArray(TTS_AUDIO_IV_BYTES).also { random.nextBytes(it) }
    val payload = ByteArray(TTS_AUDIO_HEADER_BYTES + response.audioData.size)
    TTS_AUDIO_MAGIC.copyInto(payload, 0)
    payload[4] = TTS_AUDIO_FORMAT_VERSION.toByte()
    payload[5] = response.format.ordinal.toByte()
    ByteBuffer.wrap(payload, 6, 4).putInt(response.sampleRate ?: 0)
    iv.copyInto(payload, 10)
    // CTR 是流模式，密文长度与明文完全一致，不需要处理 padding
    cipher(Cipher.ENCRYPT_MODE, iv)
        .doFinal(response.audioData, 0, response.audioData.size, payload, TTS_AUDIO_HEADER_BYTES)
    return payload
}

/**
 * 解析 [encodeTtsAudio] 产出的字节。
 *
 * 解析失败（截断、magic/版本/AudioFormat 不合法、解密异常）一律返回 null，
 * 由调用方当作 miss 并删除坏文件，绝不向上抛异常。
 */
internal fun decodeTtsAudio(bytes: ByteArray): TTSResponse? {
    if (bytes.size < TTS_AUDIO_HEADER_BYTES) return null
    if (!TTS_AUDIO_MAGIC.contentEquals(bytes.copyOfRange(0, TTS_AUDIO_MAGIC.size))) return null
    if (bytes[4].toInt() != TTS_AUDIO_FORMAT_VERSION) return null
    val format = AudioFormat.entries.getOrNull(bytes[5].toInt()) ?: return null
    // 0 表示 sampleRate = null（例如服务端没给采样率时）
    val sampleRate = ByteBuffer.wrap(bytes, 6, 4).int.takeIf { it > 0 }
    val iv = bytes.copyOfRange(10, TTS_AUDIO_HEADER_BYTES)
    val plain = runCatching {
        cipher(Cipher.DECRYPT_MODE, iv).doFinal(bytes, TTS_AUDIO_HEADER_BYTES, bytes.size - TTS_AUDIO_HEADER_BYTES)
    }.getOrNull() ?: return null
    return TTSResponse(
        audioData = plain,
        format = format,
        sampleRate = sampleRate,
    )
}

private fun cipher(mode: Int, iv: ByteArray): Cipher =
    Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(mode, secretKey, IvParameterSpec(iv))
    }

/** 十六进制 sha256，缓存键与需要压缩长度的目录名都用它 */
internal fun sha256Hex(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
