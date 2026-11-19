package me.rerere.tts.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
sealed class TTSProviderSetting {
    abstract val id: Uuid
    abstract val name: String

    abstract fun copyProvider(
        id: Uuid = this.id,
        name: String = this.name,
    ): TTSProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var name: String = "OpenAI TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.openai.com/v1",
        val model: String = "gpt-4o-mini-tts",
        val voice: String = "alloy"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("gemini")
    data class Gemini(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Gemini TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        val model: String = "gemini-2.5-flash-preview-tts",
        val voiceName: String = "Kore"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("system")
    data class SystemTTS(
        override var id: Uuid = Uuid.random(),
        override var name: String = "System TTS",
        val speechRate: Float = 1.0f,
        val pitch: Float = 1.0f,
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("minimax")
    data class MiniMax(
        override var id: Uuid = Uuid.random(),
        override var name: String = "MiniMax TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.minimaxi.com/v1",
        val model: String = "speech-2.8-hd",
        val voiceId: String = "female-shaonv",
        val speed: Float = 1.0f
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("qwen")
    data class Qwen(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Qwen TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1",
        val model: String = "qwen-audio-3.0-tts-flash",
        val voice: String = "longanhuan_v3.6",
        val format: String = "wav",
        val sampleRate: Int = 24000,
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("groq")
    data class Groq(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Groq TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.groq.com/openai/v1",
        val model: String = "canopylabs/orpheus-v1-english",
        val voice: String = "austin"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("xai")
    data class XAI(
        override var id: Uuid = Uuid.random(),
        override var name: String = "xAI TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.x.ai/v1",
        val voiceId: String = "eve",
        val language: String = "auto"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("mimo")
    // 默认值仅用于快捷起步 可在设置页任意修改
    data class MiMo(
        override var id: Uuid = Uuid.random(),
        override var name: String = "MiMo TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.xiaomimimo.com/v1",
        val model: String = "mimo-v2.5-tts",
        val voice: String = "mimo_default"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("elevenlabs")
    data class ElevenLabs(
        override var id: Uuid = Uuid.random(),
        override var name: String = "ElevenLabs TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.elevenlabs.io",
        val model: String = "eleven_multilingual_v2",
        val voiceId: String = "JBFqnCBsd6RMkjVDRZzb",
        val stability: Float = 0.5f,
        val similarityBoost: Float = 0.75f,
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    /**
     * 阶跃星辰 Step TTS (step-tts-mini / step-tts-vivid / stepaudio-2.5-tts)。
     *
     * 与 Step ASR 共用同一个 baseUrl 与鉴权方式 (Authorization: Bearer sk-xxx),
     * 走 OpenAI 兼容的 [POST /v1/audio/speech] 非流式接口, 服务端一次性返回完整音频
     * 二进制 (默认 mp3, 也可选 wav/pcm/opus/flac)。客户端把整段音频包成一个 AudioChunk
     * 发出, 由 TtsSynthesizer 统一收集后交给播放器。
     *
     * 仅 stepaudio-2.5-tts 模型支持 [instruction] 字段 (全局语境, ≤200 字符), 其它模型
     * (step-tts-mini / step-tts-vivid / step-tts-2) 会忽略该字段, 留空时不下发。
     *
     * 官方文档:
     * - 模型总览: https://platform.stepfun.com/docs/zh/guides/models/stepaudio-2.5-tts
     * - 开发指南: https://platform.stepfun.com/docs/zh/guides/developer/tts
     */
    @Serializable
    @SerialName("step")
    data class Step(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Step TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.stepfun.com",
        // step-tts-mini | step-tts-vivid | stepaudio-2.5-tts | step-tts-2
        val model: String = "step-tts-mini",
        // 完整 voice-id 列表见开发指南; 默认值与官方 SDK 一致
        val voice: String = "elegantgentle-female",
        // mp3 | wav | pcm | opus | flac; 注意 StepFun API 使用 camelCase 字段名
        val responseFormat: String = "mp3",
        // 0.5 - 2.0, 1.0 为正常语速
        val speed: Float = 1.0f,
        // 0.1 - 2.0, 1.0 为正常音量
        val volume: Float = 1.0f,
        // 8000 | 16000 | 22050 | 24000
        val sampleRate: Int = 24000,
        // 仅 stepaudio-2.5-tts 生效; ≤200 字符, 留空时不下发
        val instruction: String = "",
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("fish-audio")
    data class FishAudio(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Fish Audio TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.fish.audio",
        val model: String = "s2.1-pro",
        val referenceId: String = "",
        val temperature: Float = 0.7f,
        val speed: Float = 1.0f,
        val format: String = "mp3",
        val topP: Float = 0.7f,
        val chunkLength: Int = 300,
        val normalize: Boolean = true,
        val latency: String = "normal",
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }
    }

    @Serializable
    @SerialName("volcengine")
    data class Volcengine(
        override val id: Uuid = Uuid.random(),
        override val name: String = "Volcengine TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://openspeech.bytedance.com",
        val resourceId: String = "seed-tts-2.0",
        val speaker: String = "zh_female_vv_uranus_bigtts",
        val speechRate: Int = 0,
    ) : TTSProviderSetting() {
        override fun copyProvider(id: Uuid, name: String): TTSProviderSetting =
            copy(id = id, name = name)
    }

    /**
     * DeepSeek 语音合成。走 [POST {baseUrl}/v1/tools/tts] 整段合成接口, 不是 OpenAI 兼容格式。
     * 请求 pcm 格式 (24kHz / 16bit / mono), 由播放器补 WAV 头。不支持语速调节, 语种自动识别。
     *
     * 官方文档: https://api-docs.deepseek.com/zh-cn/guides/tts
     */
    @Serializable
    @SerialName("deepseek")
    data class DeepSeek(
        override val id: Uuid = Uuid.random(),
        override val name: String = "DeepSeek TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.deepseek.com",
        // stella (女声, 默认) | tide (男声)
        val voiceId: String = "stella",
    ) : TTSProviderSetting() {
        override fun copyProvider(id: Uuid, name: String): TTSProviderSetting =
            copy(id = id, name = name)
    }

    /**
     * 自建 qwen-talker tts-server (OpenAI 兼容 TTS 接口)。
     *
     * 走 `POST {baseUrl}/audio/speech`, baseUrl 约定已包含 `/v1` (与 OpenAI 一致),
     * apiKey 为空时不下发 Authorization 头。请求体规则:
     * - 恒发: `input`、`response_format` ("wav" | "pcm");
     * - 非空才发: `model` / `voice` / `language` / `instructions`;
     * - 非 null 才发 (null = 保留服务端引擎默认值): 9 个采样参数。
     *
     * 服务端另外提供 `GET /health`、`GET /models`、`GET|POST /audio/voices` 与
     * `DELETE /audio/voices/{name}`, 由 RemoteTTSApi 封装。
     *
     * responseFormat = "wav" 时服务端一次性返回完整 wav 文件; "pcm" 时返回 s16le 裸流 (无头部),
     * 由 AudioPlayer 按 [sampleRate] 补 WAV 头后再播放。
     */
    @Serializable
    @SerialName("remote")
    data class RemoteTTS(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Remote TTS",
        val baseUrl: String = "http://127.0.0.1:8080/v1",
        val apiKey: String = "",
        // 服务端 alias, 留空时不下发, 由服务端选择默认模型
        val model: String = "qwen3-tts-base",
        // 克隆音色名, 留空时不下发, 使用服务端默认音色
        val voice: String = "",
        // 覆盖服务端默认语言, 如 "English" / "Chinese", 留空时不下发
        val language: String = "",
        // 风格/情绪指令 (自然语言), 留空时不下发
        val instructions: String = "",
        // wav: 一次性返回完整文件 | pcm: 流式返回 s16le 裸流
        val responseFormat: String = "wav",
        // 仅 pcm 裸流需要, AudioPlayer 用它补 WAV 头
        val sampleRate: Int = 24000,
        // 以下 9 个采样参数: null = 不下发 = 保留服务端引擎默认值
        val seed: Int? = null,
        val maxNewTokens: Int? = null,
        val temperature: Float? = null,
        val topK: Int? = null,
        val topP: Float? = null,
        val repetitionPenalty: Float? = null,
        val subtalkerTemperature: Float? = null,
        val subtalkerTopK: Int? = null,
        val subtalkerTopP: Float? = null,
        // 语音缓存：合成结果落盘复用，命中时不再请求服务端
        val cacheEnabled: Boolean = true,
        val cacheMaxMb: Int = 256,
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
            )
        }

        companion object {
            /** 服务端支持的 `language` 候选值; `auto` 表示由服务端自行判定语种 */
            val REMOTE_TTS_LANGUAGES = listOf(
                "auto",
                "Chinese",
                "English",
                "Japanese",
                "Korean",
                "German",
                "French",
                "Russian",
                "Portuguese",
                "Spanish",
                "Italian",
            )

            /** 服务端支持的 `response_format` 候选值 */
            val REMOTE_TTS_RESPONSE_FORMATS = listOf("wav", "pcm")

            /** pcm 裸流补 WAV 头时可选的采样率 */
            val REMOTE_TTS_SAMPLE_RATES = listOf(16000, 22050, 24000, 32000, 44100)

            /**
             * 9 个采样参数的元数据, 供设置页画滑杆/输入框并展示「引擎默认值」。
             * [RemoteTTSSamplingParam.key] 即服务端 snake_case 字段名, 与 [RemoteTTS] 的字段一一对应。
             */
            val REMOTE_TTS_SAMPLING_PARAMS = listOf(
                RemoteTTSSamplingParam(
                    key = "seed",
                    label = "Seed",
                    isFloat = false,
                    defaultValue = -1f,
                    min = 0f,
                    max = 0f,
                    step = 1f,
                    hasRange = false,
                ),
                RemoteTTSSamplingParam(
                    key = "max_new_tokens",
                    label = "Max New Tokens",
                    isFloat = false,
                    defaultValue = 2048f,
                    min = 16f,
                    max = 4096f,
                    step = 1f,
                ),
                RemoteTTSSamplingParam(
                    key = "temperature",
                    label = "Temperature",
                    isFloat = true,
                    defaultValue = 0.9f,
                    min = 0f,
                    max = 2f,
                    step = 0.05f,
                ),
                RemoteTTSSamplingParam(
                    key = "top_k",
                    label = "Top K",
                    isFloat = false,
                    defaultValue = 50f,
                    min = 0f,
                    max = 200f,
                    step = 1f,
                ),
                RemoteTTSSamplingParam(
                    key = "top_p",
                    label = "Top P",
                    isFloat = true,
                    defaultValue = 1.0f,
                    min = 0.05f,
                    max = 1f,
                    step = 0.01f,
                ),
                RemoteTTSSamplingParam(
                    key = "repetition_penalty",
                    label = "Repetition Penalty",
                    isFloat = true,
                    defaultValue = 1.05f,
                    min = 1f,
                    max = 2f,
                    step = 0.01f,
                ),
                RemoteTTSSamplingParam(
                    key = "subtalker_temperature",
                    label = "Subtalker Temperature",
                    isFloat = true,
                    defaultValue = 0.9f,
                    min = 0f,
                    max = 2f,
                    step = 0.05f,
                ),
                RemoteTTSSamplingParam(
                    key = "subtalker_top_k",
                    label = "Subtalker Top K",
                    isFloat = false,
                    defaultValue = 50f,
                    min = 0f,
                    max = 200f,
                    step = 1f,
                ),
                RemoteTTSSamplingParam(
                    key = "subtalker_top_p",
                    label = "Subtalker Top P",
                    isFloat = true,
                    defaultValue = 1.0f,
                    min = 0.05f,
                    max = 1f,
                    step = 0.01f,
                ),
            )

            /** 按服务端字段名取采样参数元数据, 找不到返回 null */
            fun samplingParamsOf(key: String): RemoteTTSSamplingParam? =
                REMOTE_TTS_SAMPLING_PARAMS.firstOrNull { it.key == key }
        }
    }

    /**
     * 一个采样参数的元数据 (纯 UI 用, 不落盘)。
     *
     * [key] 是服务端 snake_case 字段名; [isFloat] = false 表示 int 参数;
     * [defaultValue] 是服务端引擎默认值 (int 参数也以 Float 承载, 便于 UI 统一处理);
     * [hasRange] = false 表示该参数没有滑杆范围概念 (例如 seed), UI 用普通输入框。
     */
    data class RemoteTTSSamplingParam(
        val key: String,
        val label: String,
        val isFloat: Boolean,
        val defaultValue: Float,
        val min: Float,
        val max: Float,
        val step: Float,
        val hasRange: Boolean = true,
    )

    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                Gemini::class,
                SystemTTS::class,
                MiniMax::class,
                Qwen::class,
                Groq::class,
                XAI::class,
                MiMo::class,
                ElevenLabs::class,
                Step::class,
                FishAudio::class,
                Volcengine::class,
                DeepSeek::class,
                RemoteTTS::class,
            )
        }
    }
}
