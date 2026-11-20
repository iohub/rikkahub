package me.rerere.tts.cache

import android.util.Log
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSProviderSetting
import java.io.File

private const val TAG = "TtsAudioCache"

/**
 * 远程 TTS 合成结果的本地缓存，按 provider 分目录落在 filesDir 下。
 *
 * 同一段文本 + 同一套 provider 配置反复播放（重读历史消息、重新播放整段回答）时，
 * 直接复用上次合成的音频，不再请求服务端。
 *
 * 目录布局：`filesDir/tts_audio/<providerId>/<sha256(key)>.bin`。
 * 用 filesDir 而不是 cacheDir：cacheDir 会被系统随时清掉、也不在备份范围内，
 * 而这里要求「导出备份能还原」，所以必须随 filesDir 一起走。
 * 目录即索引——不额外维护索引文件，备份打包目录、还原解压目录，索引自然就还原了。
 *
 * ## 安全边界（请勿误解）
 *
 * 文件内容是「防止 .bin 被随手翻看 / 误传」的**混淆级**保护，不是强加密，细节见 [TtsAudioCodec]。
 * 这套缓存是**可再生产物**：会被 [trim] 按 mtime 淘汰、被 [clear] 清空、也可以在设置里整目录删掉。
 * 需要「永久保存、不被淘汰」的音频请用 [TtsFixedAudioStore]。
 *
 * 未调用 [init] 时（例如 JVM 单元测试）静默不缓存，所有方法退化为 no-op，
 * 缓存层的任何故障都不允许影响 TTS 播放。
 */
object TtsAudioCache {
    /**
     * 缓存根目录名。
     *
     * 必须与 app 模块 `FileFolders.TTS_AUDIO` 保持一致：备份按这个目录名打包与还原。
     */
    const val FOLDER_NAME = "tts_audio"

    /** 单个 provider 的缓存上限（[put] 的默认值），设置项 `cacheMaxMb` 会覆盖它 */
    const val DEFAULT_MAX_BYTES = 256L * 1024 * 1024

    private const val EXTENSION = "bin"

    /**
     * 写入用的临时文件后缀。
     *
     * [put] 先写 `tmp_*.part` 再原子重命名到目标文件。备份遍历缓存目录时必须跳过它：
     * 半成品一旦被打进备份并还原到 `tts_audio/` 下，[stats] / [clear] 都按 `.bin` 过滤，
     * 既不统计也不清理，会成为永久残留。
     */
    const val PARTIAL_SUFFIX = ".part"

    // 落盘格式（magic / 版本 / 头长 / IV / 密钥）由 TtsAudioCodec 统一维护：
    // 与 TtsFixedAudioStore（用户固化的音频）共用同一套编解码实现，两边文件才能互相读懂。

    @Volatile
    private var rootDir: File? = null

    /** 在 Application 启动、任何合成发生之前调用；[filesDir] 即 `Context.filesDir`。 */
    fun init(filesDir: File) {
        rootDir = filesDir.resolve(FOLDER_NAME)
    }

    /**
     * 命中时返回缓存的音频，并把文件 mtime 刷新为当前时间（mtime 兼作最近使用时间）。
     *
     * 文件损坏（截断、magic/版本/AudioFormat 不合法）时按 miss 处理：顺手删掉坏文件，不抛异常。
     *
     * 会做文件 IO，调用方必须在 IO 线程上使用。
     */
    fun get(providerId: String, key: String): TTSResponse? {
        val file = fileOf(providerId, key) ?: return null
        if (!file.isFile) return null
        val response = runCatching { decodeTtsAudio(file.readBytes()) }
            .onFailure { warn("Failed to read tts audio cache ${file.name}", it) }
            .getOrNull()
        if (response == null) {
            runCatching { file.delete() }
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return response
    }

    /**
     * 写入缓存，同一 key 覆盖写（不会留下重复文件）。
     *
     * @param maxBytes 该 provider 目录的总量上限，超出后按 mtime 从旧到新淘汰
     */
    fun put(
        providerId: String,
        key: String,
        response: TTSResponse,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ) {
        val target = fileOf(providerId, key) ?: return
        runCatching {
            val parent = target.parentFile ?: return
            parent.mkdirs()
            val payload = encodeTtsAudio(response)
            // 先写临时文件再重命名，避免并发读取到写了一半的缓存
            val temp = File.createTempFile("tmp_", PARTIAL_SUFFIX, parent)
            try {
                temp.writeBytes(payload)
                if (!temp.renameTo(target)) {
                    target.delete()
                    check(temp.renameTo(target)) { "Failed to move tts audio cache file to $target" }
                }
            } finally {
                temp.delete()
            }
            trim(parent, maxBytes)
        }.onFailure {
            warn("Failed to write tts audio cache ${target.name}", it)
        }
    }

    /** 返回 (条目数, 总字节数)，供设置页展示；会做文件 IO，调用方需放在 IO 线程。 */
    fun stats(providerId: String): Pair<Int, Long> {
        val dir = dirOf(providerId) ?: return 0 to 0L
        val files = runCatching { dir.listFiles()?.toList().orEmpty() }
            .onFailure { warn("Failed to list tts audio cache dir ${dir.name}", it) }
            .getOrDefault(emptyList())
            .filter { it.isFile && it.extension == EXTENSION }
        return files.size to files.sumOf { it.length() }
    }

    /** 清空该 provider 的缓存，返回删除的条目数；会做文件 IO，调用方需放在 IO 线程。 */
    fun clear(providerId: String): Int {
        val dir = dirOf(providerId) ?: return 0
        var deleted = 0
        runCatching {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.delete()) deleted++
            }
        }.onFailure { warn("Failed to clear tts audio cache dir ${dir.name}", it) }
        return deleted
    }

    /**
     * 缓存键：把影响音频产出的所有设置字段 + 文本拼成规范串再取 sha256。
     *
     * 不含 apiKey（换 key 不影响音频内容）、name（改个显示名不该让缓存全失效）、
     * cacheEnabled / cacheMaxMb（纯缓存开关，与音频内容无关）。
     */
    fun buildKey(setting: TTSProviderSetting.RemoteTTS, text: String): String {
        val canonical = listOf(
            setting.id.toString(),
            setting.baseUrl,
            setting.model,
            setting.voice,
            setting.language,
            setting.instructions,
            setting.responseFormat,
            setting.sampleRate.toString(),
            setting.seed?.toString() ?: "-",
            setting.maxNewTokens?.toString() ?: "-",
            setting.temperature?.toString() ?: "-",
            setting.topK?.toString() ?: "-",
            setting.topP?.toString() ?: "-",
            setting.repetitionPenalty?.toString() ?: "-",
            setting.subtalkerTemperature?.toString() ?: "-",
            setting.subtalkerTopK?.toString() ?: "-",
            setting.subtalkerTopP?.toString() ?: "-",
            text,
        ).joinToString("\u0000")
        return sha256Hex(canonical)
    }

    private fun dirOf(providerId: String): File? =
        rootDir?.resolve(providerId)?.takeIf { it.isDirectory }

    private fun fileOf(providerId: String, key: String): File? {
        val root = rootDir ?: return null
        return root.resolve(providerId).resolve("${sha256Hex(key)}.$EXTENSION")
    }

    /** 按 mtime 升序（最久未使用）淘汰，直到总量回到 [maxBytes] 以下 */
    @Synchronized
    private fun trim(dir: File, maxBytes: Long) {
        val files = dir.listFiles()?.filter { it.isFile && it.extension == EXTENSION } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    // JVM 单测里没有 android.util.Log 的实现，日志本身写失败也不能影响缓存逻辑
    private fun warn(message: String, e: Throwable) {
        runCatching { Log.w(TAG, message, e) }
    }
}
