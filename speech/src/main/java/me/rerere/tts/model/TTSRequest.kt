package me.rerere.tts.model

import kotlinx.serialization.Serializable

@Serializable
data class TTSRequest(
    val text: String,
    /**
     * 动态语气指令。
     *
     * 指模型在**这一次**朗读里临时给出的语气/情绪描述（例如「兴奋一点」「低声耳语」），
     * 由上层每次调用时传入；provider 侧会把它与设置页配置的静态 instructions
     * 合并后再下发（静态在前、动态在后，见 [mergeTtsInstructions]）。
     *
     * 默认空串：旧版本落盘的 JSON、以及不关心语气的既有调用点都能原样反序列化 / 编译。
     */
    val instruct: String = "",
)

@Serializable
enum class AudioFormat {
    MP3,
    WAV,
    OGG,
    AAC,
    OPUS,
    PCM
}
