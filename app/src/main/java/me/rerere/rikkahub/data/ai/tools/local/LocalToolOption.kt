package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    /**
     * 开启后不注册任何工具，只让系统提示词注入 `<tts_voice_control>` 语气控制规范。
     *
     * 旧的 `text_to_speech` 工具已废弃，保留该枚举值仅为兼容历史数据：已安装用户的 DataStore 里
     * 存着 `{"type":"tts"}`，删掉这个子类会让 `ASSISTANTS` 反序列化失败。
     */
    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()

    @Serializable
    @SerialName("chart_display")
    data object ChartDisplay : LocalToolOption()

    @Serializable
    @SerialName("phone_use")
    data object PhoneUse : LocalToolOption()

    /** 用户自建的远程出图服务，见 [me.rerere.rikkahub.data.datastore.ImageGenerationConfig]。 */
    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : LocalToolOption()
}
