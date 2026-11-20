package me.rerere.tts.cache

import android.util.Log
import me.rerere.tts.model.TTSResponse
import java.io.File

private const val TAG = "TtsFixedAudioStore"

/**
 * 「消息级语音固化」的存储层：把某条聊天消息的合成音频**永久**留在 filesDir 下。
 *
 * 与 [TtsAudioCache] 的分工：
 * - [TtsAudioCache] 是**可再生产物**——按文本 + provider 配置做键、按 mtime LRU 淘汰、可被一键清空；
 * - 本类存的是用户显式点「固化」后的音频——按消息分区、不参与淘汰、不因文本相同而互相覆盖，
 *   只有用户删除消息 / 取消固化 / 清空时才会消失。
 *
 * 目录布局：`filesDir/tts_fixed/<安全化后的 messageId>/<index>.bin`，index 从 0 开始。
 * 文件内容与 [TtsAudioCache] **逐字节同格式**（见 [encodeTtsAudio]/[decodeTtsAudio]），
 * 两个目录里的文件可以互相读懂，解码实现只有一份。
 * 目录即索引：不额外维护清单文件，备份打包目录、还原解压目录，索引自然就还原了。
 *
 * messageId 来自上层（消息 Uuid 字符串），必须当作不可信输入处理：[safeDirName] 只保留
 * `[A-Za-z0-9_-]`，其余字符一律替换成 `_`，因此 `../`、`/`、`\0` 等都无法把文件写到 `tts_fixed/` 之外。
 *
 * 未调用 [init] 时（例如 JVM 单元测试、或早期启动阶段）所有方法退化为 no-op：
 * 写入返回 false、读取返回 null、统计返回 0。存储层的任何故障都不允许影响 TTS 播放。
 */
object TtsFixedAudioStore {
    /**
     * 固化音频根目录名。
     *
     * 必须与 app 模块 `FileFolders.TTS_FIXED` 保持一致：备份按这个目录名打包与还原。
     */
    const val FOLDER_NAME = "tts_fixed"

    /**
     * 写入用的临时文件后缀，与 [TtsAudioCache.PARTIAL_SUFFIX] 相同。
     *
     * 备份遍历目录时必须跳过 `*.part`：半成品一旦进了备份并还原回来，[chunkCount]/[totalStats]
     * 都按 `.bin` 过滤，既不统计也不清理，会成为永久残留。
     */
    const val PARTIAL_SUFFIX = ".part"

    private const val EXTENSION = "bin"

    /** 单层目录名的长度上限，远低于文件系统的 255 字节限制 */
    private const val MAX_DIR_NAME_LENGTH = 100

    private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9_-]")

    @Volatile
    private var rootDir: File? = null

    /** 在 Application 启动、任何固化读写之前调用；[filesDir] 即 `Context.filesDir`。幂等。 */
    fun init(filesDir: File) {
        rootDir = filesDir.resolve(FOLDER_NAME)
    }

    /** 该消息的固化目录；不存在时返回 null（未 init 也返回 null）。 */
    fun dirOf(messageId: String): File? =
        rootOrNull()?.resolve(safeDirName(messageId))?.takeIf { it.isDirectory }

    /**
     * 写入一片固化音频，文件名 `<index>.bin`，同一 index 覆盖写（「重新生成」走的就是这条路）。
     *
     * 先写 `tmp_*.part` 再原子重命名，与 [TtsAudioCache.put] 同风格：并发读不会读到半成品。
     *
     * @return 是否写入成功；未 init、index 非法、IO 失败都返回 false 且不抛异常
     */
    fun writeChunk(messageId: String, index: Int, response: TTSResponse): Boolean {
        val dir = resolveDir(messageId) ?: return false
        if (index < 0) return false
        val target = File(dir, "$index.$EXTENSION")
        return runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) {
                warn("Failed to create fixed tts audio dir ${dir.name}", null)
                return false
            }
            val payload = encodeTtsAudio(response)
            val temp = File.createTempFile("tmp_", PARTIAL_SUFFIX, dir)
            try {
                temp.writeBytes(payload)
                if (!temp.renameTo(target)) {
                    target.delete()
                    check(temp.renameTo(target)) { "Failed to move fixed tts audio file to $target" }
                }
            } finally {
                temp.delete()
            }
            true
        }.onFailure {
            warn("Failed to write fixed tts audio ${target.name}", it)
        }.getOrDefault(false)
    }

    /**
     * 读取一片固化音频；文件缺失或损坏（截断 / magic 不合法 / 解密失败）都返回 null。
     *
     * 坏文件会被顺手删掉——否则它会永远占着 index，[chunkCount] 也会把它算作「存在」。
     * 会做文件 IO，调用方必须在 IO 线程上使用。
     */
    fun readChunk(messageId: String, index: Int): TTSResponse? {
        if (index < 0) return null
        val dir = dirOf(messageId) ?: return null
        val file = File(dir, "$index.$EXTENSION").takeIf { it.isFile } ?: return null
        val response = runCatching { decodeTtsAudio(file.readBytes()) }
            .onFailure { warn("Failed to read fixed tts audio ${file.name}", it) }
            .getOrNull()
        if (response == null) {
            runCatching { file.delete() }
            return null
        }
        return response
    }

    /**
     * 实际存在且**从 0 开始连续**的分片数（遇到第一个缺口即停止）。
     *
     * 用「连续」而不是「文件个数」：播放是按 index 递增读的，索引空洞会让播放越界，
     * 这里提前把可用范围收窄到连续区间，调用方拿到的数就是能安全播放的分片数。
     *
     * 只判断文件存在性，不校验内容（校验发生在 [readChunk]）。
     */
    fun chunkCount(messageId: String): Int {
        val dir = dirOf(messageId) ?: return 0
        var count = 0
        while (File(dir, "$count.$EXTENSION").isFile) {
            count++
        }
        return count
    }

    /** 该消息固化音频占用的字节数（含不连续分片与残留临时文件）；会做文件 IO。 */
    fun totalBytes(messageId: String): Long {
        val dir = dirOf(messageId) ?: return 0L
        return runCatching { dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L }
            .onFailure { warn("Failed to size fixed tts audio dir ${dir.name}", it) }
            .getOrDefault(0L)
    }

    /** 递归删除该消息的固化目录；目录本就不存在时返回 false。会做文件 IO，调用方需放在 IO 线程。 */
    fun deleteMessage(messageId: String): Boolean {
        val dir = dirOf(messageId) ?: return false
        return runCatching { dir.deleteRecursively() }
            .onFailure { warn("Failed to delete fixed tts audio dir ${dir.name}", it) }
            .getOrDefault(false)
    }

    /** 清空所有固化音频，返回删除的消息目录数。会做文件 IO，调用方需放在 IO 线程。 */
    fun clearAll(): Int {
        val root = dirOfAll() ?: return 0
        var deleted = 0
        runCatching {
            root.listFiles()?.forEach { entry ->
                if (entry.isDirectory && entry.deleteRecursively()) deleted++
            }
        }.onFailure { warn("Failed to clear fixed tts audio", it) }
        return deleted
    }

    /** 返回 (消息目录数, 总字节数)，供设置页展示；会做文件 IO，调用方需放在 IO 线程。 */
    fun totalStats(): Pair<Int, Long> {
        val root = dirOfAll() ?: return 0 to 0L
        val dirs = runCatching { root.listFiles()?.filter { it.isDirectory }.orEmpty() }
            .onFailure { warn("Failed to list fixed tts audio", it) }
            .getOrDefault(emptyList())
        val bytes = dirs.sumOf { dir ->
            dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }
        return dirs.size to bytes
    }

    /**
     * messageId → 能安全当目录名用的字符串：只保留 `[A-Za-z0-9_-]`，其它字符一律换成 `_`。
     *
     * 这一步是路径穿越的唯一防线（`../x` → `___x`），因此**不要**用 `File(root, messageId)` 绕过它。
     * 超长 id（例如把整段文本当 id 传进来）压成定长哈希，避免撞文件系统的名字长度上限。
     */
    internal fun safeDirName(messageId: String): String {
        val sanitized = messageId.replace(UNSAFE_NAME_CHARS, "_")
        if (sanitized.isEmpty()) return "_"
        if (sanitized.length <= MAX_DIR_NAME_LENGTH) return sanitized
        return sha256Hex(sanitized).take(32)
    }

    private fun rootOrNull(): File? = rootDir

    /** 只在目录已存在时有意义（统计 / 读取 / 删除都走这里） */
    private fun dirOfAll(): File? = rootDir?.takeIf { it.isDirectory }

    /** 写入前的目录路径：允许尚不存在，由 [writeChunk] 负责创建 */
    private fun resolveDir(messageId: String): File? = rootDir?.resolve(safeDirName(messageId))

    // JVM 单测里没有 android.util.Log 的实现，日志本身写失败也不能影响存储逻辑
    private fun warn(message: String, e: Throwable?) {
        runCatching { if (e != null) Log.w(TAG, message, e) else Log.w(TAG, message) }
    }
}
