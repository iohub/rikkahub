package me.rerere.tts.provider

import android.content.Context
import kotlinx.coroutines.flow.Flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.TTSRequest

interface TTSProvider<T : TTSProviderSetting> {
    fun generateSpeech(
        context: Context,
        providerSetting: T,
        request: TTSRequest
    ): Flow<AudioChunk>

    /**
     * 可选：指导 AI 如何在朗读文本中加入该 provider 支持的语气/情感标记的提示词。
     *
     * 默认空 = 不注入。支持内联标记的 provider 直接在实现类里覆盖此值（硬编码）。
     * 该内容会在 `<tts_voice_control>` 语气控制规范注入 system prompt 时追加在末尾
     * （见 `ttsVoiceControlPrompt`）。
     *
     * 注意（写内容时的两个约束）：
     * 1. 标记要写进**给用户看的正文**：`<tts_voice_control>` 块只承载语气描述，朗读前会被整体剥离，
     *    写进块里的标记到不了合成器；
     * 2. 标记需能扛过清洗管线：避免 `*` `_` 以及 `<tag>` 形式的尖括号等会被 stripMarkdown 删除的符号，
     *    且标记内部不要含 `。，！？…` 等会被 TextChunker 切断的标点。优先用方括号。
     */
    val promptGuidance: String
        get() = ""
}
