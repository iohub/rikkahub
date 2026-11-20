package me.rerere.tts.model

/**
 * 统一的播放状态（对外暴露给 app 侧使用）。
 */
enum class PlaybackStatus {
    Idle,
    Buffering,
    Playing,
    Paused,
    Ended,
    Error
}

data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val currentChunkIndex: Int = 0, // 1-based，与 currentChunk StateFlow 对齐
    val totalChunks: Int = 0,
    val errorMessage: String? = null,
    /**
     * 本次播放归属的聊天消息 id（消息级语音：播放某条消息的文本或其固化音频时带上）。
     *
     * null = 与具体消息无关（设置页试听、语音模式等），UI 据此决定要不要高亮某条消息。
     * 放在末尾并带默认值：既有构造点全部无需改动。
     */
    val messageId: String? = null
)
