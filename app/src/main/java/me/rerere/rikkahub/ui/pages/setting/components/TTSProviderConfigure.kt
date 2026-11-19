package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.providers.RemoteVoiceInfo
import me.rerere.ui.components.OutlinedNumberInput
import me.rerere.ui.components.SelectTextField
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

/**
 * TTS 提供商配置表单。
 *
 * @param allowSystemTts 为 false 时类型下拉里不提供系统 TTS —— 内置 TTS 关闭时列表会把系统 TTS 过滤掉，
 *   允许用户切过去只会让条目「消失」，看起来像没保存上
 */
@Composable
fun TTSProviderConfigure(
    setting: TTSProviderSetting,
    modifier: Modifier = Modifier,
    allowSystemTts: Boolean = true,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // 内置 TTS 关闭时不提供系统 TTS 选项
    val providerTypes = remember(allowSystemTts) {
        if (allowSystemTts) {
            TTSProviderSetting.Types
        } else {
            TTSProviderSetting.Types.filterNot { it == TTSProviderSetting.SystemTTS::class }
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        CardGroup {
            formItem(
                headlineContent = { Text(stringResource(R.string.setting_tts_page_provider_type)) },
                supportingContent = { Text(stringResource(R.string.setting_tts_page_provider_type_description)) },
            ) {
                SelectTextField(
                    value = when (setting) {
                        is TTSProviderSetting.OpenAI -> "OpenAI"
                        is TTSProviderSetting.Gemini -> "Gemini"
                        is TTSProviderSetting.SystemTTS -> "System TTS"
                        is TTSProviderSetting.MiniMax -> "MiniMax"
                        is TTSProviderSetting.Qwen -> "Qwen"
                        is TTSProviderSetting.Groq -> "Groq"
                        is TTSProviderSetting.XAI -> "xAI"
                        is TTSProviderSetting.MiMo -> "MiMo"
                        is TTSProviderSetting.Step -> "Step"
                        is TTSProviderSetting.ElevenLabs -> "ElevenLabs"
                        is TTSProviderSetting.FishAudio -> "Fish Audio"
                        is TTSProviderSetting.Volcengine -> "火山引擎"
                        is TTSProviderSetting.DeepSeek -> "DeepSeek"
                        is TTSProviderSetting.RemoteTTS -> stringResource(R.string.setting_tts_page_provider_remote)
                    },
                    options = providerTypes,
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    optionToString = { providerClass ->
                        when (providerClass) {
                            TTSProviderSetting.OpenAI::class -> "OpenAI"
                            TTSProviderSetting.Gemini::class -> "Gemini"
                            TTSProviderSetting.SystemTTS::class -> "System TTS"
                            TTSProviderSetting.MiniMax::class -> "MiniMax"
                            TTSProviderSetting.Qwen::class -> "Qwen"
                            TTSProviderSetting.Groq::class -> "Groq"
                            TTSProviderSetting.XAI::class -> "xAI"
                            TTSProviderSetting.MiMo::class -> "MiMo"
                            TTSProviderSetting.ElevenLabs::class -> "ElevenLabs"
                            TTSProviderSetting.FishAudio::class -> "Fish Audio"
                            TTSProviderSetting.Volcengine::class -> "火山引擎"
                            TTSProviderSetting.Step::class -> "Step"
                            TTSProviderSetting.DeepSeek::class -> "DeepSeek"
                            TTSProviderSetting.RemoteTTS::class -> stringResource(R.string.setting_tts_page_provider_remote)
                            else -> providerClass.simpleName ?: "Unknown"
                        }
                    },
                    onOptionSelected = { providerClass ->
                        val newSetting = when (providerClass) {
                            TTSProviderSetting.OpenAI::class -> TTSProviderSetting.OpenAI(
                                id = setting.id,
                                name = "OpenAI TTS"
                            )

                            TTSProviderSetting.Gemini::class -> TTSProviderSetting.Gemini(
                                id = setting.id,
                                name = "Gemini TTS"
                            )

                            TTSProviderSetting.SystemTTS::class -> TTSProviderSetting.SystemTTS(
                                id = setting.id,
                                name = "System TTS"
                            )

                            TTSProviderSetting.MiniMax::class -> TTSProviderSetting.MiniMax(
                                id = setting.id,
                                name = "MiniMax TTS"
                            )

                            TTSProviderSetting.Qwen::class -> TTSProviderSetting.Qwen(
                                id = setting.id,
                                name = "Qwen TTS"
                            )

                            TTSProviderSetting.Groq::class -> TTSProviderSetting.Groq(
                                id = setting.id,
                                name = "Groq TTS"
                            )

                            TTSProviderSetting.XAI::class -> TTSProviderSetting.XAI(
                                id = setting.id,
                                name = "xAI TTS"
                            )

                            TTSProviderSetting.MiMo::class -> TTSProviderSetting.MiMo(
                                id = setting.id,
                                name = "MiMo TTS"
                            )

                            TTSProviderSetting.ElevenLabs::class -> TTSProviderSetting.ElevenLabs(
                                id = setting.id,
                                name = "ElevenLabs TTS"
                            )

                            TTSProviderSetting.FishAudio::class -> TTSProviderSetting.FishAudio(
                                id = setting.id,
                                name = "Fish Audio TTS"
                            )

                            TTSProviderSetting.Step::class -> TTSProviderSetting.Step(
                                id = setting.id,
                                name = "Step TTS"
                            )

                            TTSProviderSetting.Volcengine::class -> TTSProviderSetting.Volcengine(id = setting.id)

                            TTSProviderSetting.DeepSeek::class -> TTSProviderSetting.DeepSeek(id = setting.id)

                            TTSProviderSetting.RemoteTTS::class -> TTSProviderSetting.RemoteTTS(
                                id = setting.id,
                                name = "Remote TTS"
                            )

                            else -> setting
                        }
                        onValueChange(newSetting)
                    }
                )
            }

            // Name
            formItem(
                headlineContent = { Text(stringResource(R.string.setting_tts_page_name)) },
                supportingContent = { Text(stringResource(R.string.setting_tts_page_name_description)) }
            ) {
                OutlinedTextField(
                    value = setting.name,
                    onValueChange = { newName ->
                        onValueChange(setting.copyProvider(name = newName))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.setting_tts_page_name_placeholder)) }
                )
            }
        }

        CardGroup {
            when (setting) {
                is TTSProviderSetting.OpenAI -> openAITTSItems(setting, onValueChange)
                is TTSProviderSetting.Gemini -> geminiTTSItems(setting, onValueChange)
                is TTSProviderSetting.MiniMax -> miniMaxTTSItems(setting, onValueChange)
                is TTSProviderSetting.SystemTTS -> systemTTSItems(setting, onValueChange)
                is TTSProviderSetting.Qwen -> qwenTTSItems(setting, onValueChange)
                is TTSProviderSetting.Groq -> groqTTSItems(setting, onValueChange)
                is TTSProviderSetting.XAI -> xAITTSItems(setting, onValueChange)
                is TTSProviderSetting.MiMo -> miMoTTSItems(setting, onValueChange)
                is TTSProviderSetting.ElevenLabs -> elevenLabsTTSItems(setting, onValueChange)
                is TTSProviderSetting.FishAudio -> fishAudioTTSItems(setting, onValueChange)
                is TTSProviderSetting.Step -> stepTTSItems(setting, onValueChange)
                is TTSProviderSetting.Volcengine -> volcengineTTSItems(setting, onValueChange)
                is TTSProviderSetting.DeepSeek -> deepSeekTTSItems(setting, onValueChange)
                is TTSProviderSetting.RemoteTTS -> remoteTTSItems(setting, onValueChange)
            }
        }
    }
}

private fun CardGroupScope.openAITTSItems(
    setting: TTSProviderSetting.OpenAI,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_api_key_placeholder_openai)) },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_model_placeholder_openai)) }
        )
    }

    // Voice
    val voices = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { voice ->
                onValueChange(setting.copy(voice = voice))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun CardGroupScope.miMoTTSItems(
    setting: TTSProviderSetting.MiMo,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // MiMo 配置均为自由输入 默认值只是占位
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mimo-xxx") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.xiaomimimo.com/v1") }
        )
    }

    // Model
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mimo-v2-tts") }
        )
    }

    // Voice
    val voices = listOf(
        "mimo_default",
        "冰糖",
        "茉莉",
        "苏打",
        "白桦",
        "Mia",
        "Chloe",
        "Milo",
        "Dean"
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { voice ->
                onValueChange(setting.copy(voice = voice))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("mimo_default") }
        )
    }
}

private fun CardGroupScope.miniMaxTTSItems(
    setting: TTSProviderSetting.MiniMax,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("speech-2.8-hd") }
        )
    }

    // Voice ID
    val voiceIds = listOf(
        "male-qn-qingse",
        "male-qn-jingying",
        "male-qn-badao",
        "male-qn-daxuesheng",
        "female-shaonv",
        "female-yujie",
        "female-chengshu",
        "female-tianmei",
        "audiobook_male_1",
        "audiobook_female_1",
        "cartoon_pig"
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice_id)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_id_description)) }
    ) {
        SelectTextField(
            value = setting.voiceId,
            options = voiceIds,
            onValueChange = { newVoiceId ->
                onValueChange(setting.copy(voiceId = newVoiceId))
            },
            onOptionSelected = { voiceId ->
                onValueChange(setting.copy(voiceId = voiceId))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Speed
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_speed)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_speed_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.25f..4.0f) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }
}

private fun CardGroupScope.geminiTTSItems(
    setting: TTSProviderSetting.Gemini,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_api_key_placeholder_gemini)) },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_model_placeholder_gemini)) }
        )
    }

    // Voice Name
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice_name)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_name_description)) }
    ) {
        OutlinedTextField(
            value = setting.voiceName,
            onValueChange = { newVoiceName ->
                onValueChange(setting.copy(voiceName = newVoiceName))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_voice_name_placeholder)) }
        )
    }
}

private fun CardGroupScope.systemTTSItems(
    setting: TTSProviderSetting.SystemTTS,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // Speech Rate
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_speech_rate)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_speech_rate_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speechRate,
            onValueChange = { newRate ->
                if (newRate in 0.1f..3.0f) {
                    onValueChange(setting.copy(speechRate = newRate))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speech_rate)
        )
    }

    // Pitch
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_pitch)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_pitch_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.pitch,
            onValueChange = { newPitch ->
                if (newPitch in 0.1f..2.0f) {
                    onValueChange(setting.copy(pitch = newPitch))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_pitch)
        )
    }
}

private fun CardGroupScope.qwenTTSItems(
    setting: TTSProviderSetting.Qwen,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-xxx") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    val models = listOf(
        "qwen-audio-3.0-tts-flash",
        "qwen-audio-3.0-tts-plus",
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        SelectTextField(
            value = setting.model,
            options = models,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            onOptionSelected = { model ->
                val defaultVoice = when (model) {
                    "qwen-audio-3.0-tts-plus" -> "longanlingxin"
                    "qwen-audio-3.0-tts-flash" -> "longanhuan_v3.6"
                    else -> setting.voice
                }
                onValueChange(setting.copy(model = model, voice = defaultVoice))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("qwen-audio-3.0-tts-flash") }
        )
    }

    // Voice
    val voices = when (setting.model) {
        "qwen-audio-3.0-tts-plus" -> listOf("longanlingxin", "longanlufeng")
        "qwen-audio-3.0-tts-flash" -> listOf(
            "longanfengyue", "longanyuanfei", "longanlingxi", "longanxiaoxin",
            "longanhuan_v3.6", "longjielidou_v3.6", "longpaopao_v3.6",
            "longhuohuo_v3.6", "longchuanshu_v3.6", "loongmary",
            "loongeva_v3.6", "loongjohn",
        )

        else -> emptyList()
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { voice ->
                onValueChange(setting.copy(voice = voice))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Audio Format
    val formats = listOf("wav", "mp3", "pcm", "opus")

    formItem(
        headlineContent = { Text("Audio Format") },
        supportingContent = { Text("Audio encoding returned by Qwen TTS") }
    ) {
        SelectTextField(
            value = setting.format,
            options = formats,
            readOnly = true,
            onOptionSelected = { format ->
                onValueChange(setting.copy(format = format))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Sample Rate
    val sampleRates = listOf(8000, 16000, 22050, 24000, 44100, 48000)

    formItem(
        headlineContent = { Text("Sample Rate") },
        supportingContent = { Text("Audio sample rate in Hz") }
    ) {
        SelectTextField(
            value = setting.sampleRate.toString(),
            options = sampleRates,
            readOnly = true,
            onOptionSelected = { sampleRate ->
                onValueChange(setting.copy(sampleRate = sampleRate))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun CardGroupScope.groqTTSItems(
    setting: TTSProviderSetting.Groq,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("gsk_xxx") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_base_url_placeholder)) }
        )
    }

    // Model
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("canopylabs/orpheus-v1-english") }
        )
    }

    // Voice
    val voices = listOf("austin", "natalie", "kailin")

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { voice ->
                onValueChange(setting.copy(voice = voice))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun CardGroupScope.xAITTSItems(
    setting: TTSProviderSetting.XAI,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("xai-xxx") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.x.ai/v1") }
        )
    }

    // Voice ID
    val voices = listOf(
        "eve" to "Eve",
        "ara" to "Ara",
        "rex" to "Rex",
        "sal" to "Sal",
        "leo" to "Leo"
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voiceId,
            options = voices,
            onValueChange = { newVoiceId ->
                onValueChange(setting.copy(voiceId = newVoiceId))
            },
            onOptionSelected = { (voiceId, _) ->
                onValueChange(setting.copy(voiceId = voiceId))
            },
            optionToString = { (_, description) -> description },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Language
    val languages = listOf(
        "auto" to "Auto-detect",
        "en" to "English",
        "zh" to "Chinese (Simplified)",
        "ja" to "Japanese",
        "ko" to "Korean",
        "fr" to "French",
        "de" to "German",
        "es-ES" to "Spanish (Spain)",
        "es-MX" to "Spanish (Mexico)",
        "pt-BR" to "Portuguese (Brazil)",
        "pt-PT" to "Portuguese (Portugal)",
        "it" to "Italian",
        "ru" to "Russian",
        "ar-EG" to "Arabic (Egypt)",
        "hi" to "Hindi",
        "tr" to "Turkish",
        "vi" to "Vietnamese",
        "id" to "Indonesian",
        "bn" to "Bengali"
    )

    formItem(
        headlineContent = { Text("Language") },
    ) {
        SelectTextField(
            value = setting.language,
            options = languages,
            onValueChange = { newLanguage ->
                onValueChange(setting.copy(language = newLanguage))
            },
            onOptionSelected = { (code, _) ->
                onValueChange(setting.copy(language = code))
            },
            optionToString = { (code, displayName) -> "$displayName ($code)" },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun CardGroupScope.elevenLabsTTSItems(
    setting: TTSProviderSetting.ElevenLabs,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk_...") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.elevenlabs.io") }
        )
    }

    // Model
    val models = listOf(
        "eleven_multilingual_v2" to "Eleven Multilingual v2",
        "eleven_v3" to "Eleven v3",
        "eleven_flash_v2_5" to "Eleven Flash v2.5"
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        SelectTextField(
            value = setting.model,
            options = models,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            onOptionSelected = { (modelId, _) ->
                onValueChange(setting.copy(model = modelId))
            },
            optionToString = { (modelId, displayName) -> "$displayName ($modelId)" },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Voice ID
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        OutlinedTextField(
            value = setting.voiceId,
            onValueChange = { newVoiceId ->
                onValueChange(setting.copy(voiceId = newVoiceId))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("JBFqnCBsd6RMkjVDRZzb") }
        )
    }

    // Stability
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_stability)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_stability_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.stability,
            onValueChange = { newStability ->
                onValueChange(setting.copy(stability = newStability.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.5",
        )
    }

    // Similarity Boost
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_similarity_boost)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_similarity_boost_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.similarityBoost,
            onValueChange = { newSimilarityBoost ->
                onValueChange(setting.copy(similarityBoost = newSimilarityBoost.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.75",
        )
    }
}

private fun CardGroupScope.fishAudioTTSItems(
    setting: TTSProviderSetting.FishAudio,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_description)) }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://fish.audio/app/api-keys") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.fish.audio") }
        )
    }

    // Model (下拉选择框 + 文本输入框，完全同 ElevenLabs 格式)
    val models = listOf(
        "s2.1-pro" to "S2.1-Pro (推荐)",
        "s2.1-pro-free" to "S2.1-Pro Free (免费)",
        "s2-pro" to "S2-Pro",
        "s1" to "S1"
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        SelectTextField(
            value = setting.model,
            options = models,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            onOptionSelected = { (modelId, _) ->
                onValueChange(setting.copy(model = modelId))
            },
            optionToString = { (modelId, displayName) -> "$displayName ($modelId)" },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Voice ID (reference_id)
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice_id)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_id_description)) }
    ) {
        OutlinedTextField(
            value = setting.referenceId,
            onValueChange = { newReferenceId ->
                onValueChange(setting.copy(referenceId = newReferenceId))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("802e3bc2b27e49c2995d23ef70e6ac89") }
        )
    }

    // Temperature
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_temperature)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_temperature_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.temperature,
            onValueChange = { newTemperature ->
                onValueChange(setting.copy(temperature = newTemperature.coerceIn(0f, 1f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "0.7",
        )
    }

    // Speed
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_speed)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_fish_audio_speed_description)) }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                onValueChange(setting.copy(speed = newSpeed.coerceIn(0.5f, 2f)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "1.0",
        )
    }
}

private fun CardGroupScope.stepTTSItems(
    setting: TTSProviderSetting.Step,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    // API Key
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text("从阶跃星辰官网获取密钥: platform.stepfun.com/interface-key") }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { newApiKey ->
                onValueChange(setting.copy(apiKey = newApiKey))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("从阶跃星辰官网获取密钥") },
        )
    }

    // Base URL
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_base_url_description)) }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { newBaseUrl ->
                onValueChange(setting.copy(baseUrl = newBaseUrl))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.stepfun.com") }
        )
    }

    // Model
    val models = listOf(
        "step-tts-mini" to "step-tts-mini (轻量, 便宜)",
        "step-tts-vivid" to "step-tts-vivid (情感丰富)",
        "stepaudio-2.5-tts" to "stepaudio-2.5-tts (语境感知, 支持 instruction)",
        "step-tts-2" to "step-tts-2 (上一代)",
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_description)) }
    ) {
        SelectTextField(
            value = setting.model,
            options = models,
            onValueChange = { newModel ->
                onValueChange(setting.copy(model = newModel))
            },
            onOptionSelected = { (modelId, _) ->
                onValueChange(setting.copy(model = modelId))
            },
            optionToString = { (_, description) -> description },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Voice
    // 部分常用 voice-id, 完整列表见官方开发指南
    // https://platform.stepfun.com/docs/zh/guides/developer/tts
    val voices = listOf(
        "elegantgentle-female" to "气质温婉 (elegantgentle-female)",
        "livelybreezy-female" to "活力轻快 (livelybreezy-female)",
        "energeticconfident-female" to "活力自信 (energeticconfident-female)",
        "jingdiannvsheng" to "经典女声 (jingdiannvsheng)",
        "wenroushunv" to "温柔熟女 (wenroushunv)",
        "tianmeinvsheng" to "甜美女声 (tianmeinvsheng)",
        "qingchunshaonv" to "清纯少女 (qingchunshaonv)",
        "wenrounvsheng" to "温柔女声 (wenrounvsheng)",
        "ruanmengnvsheng" to "软萌女生 (ruanmengnvsheng)",
        "youyanvsheng" to "优雅女生 (youyanvsheng)",
        "lengyanyujie" to "冷艳御姐 (lengyanyujie)",
        "shuangkuaijiejie" to "爽快姐姐 (shuangkuaijiejie)",
        "wenjingxuejie" to "文静学姐 (wenjingxuejie)",
        "linjiajiejie" to "邻家姐姐 (linjiajiejie)",
        "linjiameimei" to "邻家妹妹 (linjiameimei)",
        "zhixingjiejie" to "知性姐姐 (zhixingjiejie)",
        "cixingnansheng" to "磁性男声 (cixingnansheng)",
        "wenrounansheng" to "温柔男声 (wenrounansheng)",
        "yuanqinansheng" to "元气男声 (yuanqinansheng)",
        "zhengpaiqingnian" to "正派青年 (zhengpaiqingnian)",
        "ruyananshi" to "儒雅男士 (ruyananshi)",
        "boyinnansheng" to "播音男声 (boyinnansheng)",
        "shenchennanyin" to "深沉男音 (shenchennanyin)",
        "shuangkuainansheng" to "爽快男声 (shuangkuainansheng)",
        "ganliannvsheng" to "干练女声 (ganliannvsheng)",
        "qinhenvsheng" to "亲切女声 (qinhenvsheng)",
        "huolinvsheng" to "活力女声 (huolinvsheng)",
        "jilingshaonv" to "机灵少女 (jilingshaonv)",
        "yuanqishaonv" to "元气少女 (yuanqishaonv)",
        "wenrougongzi" to "温柔公子 (wenrougongzi)",
        "qingniandaxuesheng" to "青年大学生 (qingniandaxuesheng)",
    )

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_voice_description)) }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voices,
            onValueChange = { newVoice ->
                onValueChange(setting.copy(voice = newVoice))
            },
            onOptionSelected = { (voiceId, _) ->
                onValueChange(setting.copy(voice = voiceId))
            },
            optionToString = { (_, description) -> description },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Response Format
    val formats = listOf("mp3", "wav", "pcm", "opus", "flac")

    formItem(
        headlineContent = { Text("Response Format") },
        supportingContent = { Text("音频编码格式 (注意 StepFun API 字段名为 camelCase)") }
    ) {
        SelectTextField(
            value = setting.responseFormat,
            options = formats,
            onValueChange = { newFormat ->
                onValueChange(setting.copy(responseFormat = newFormat))
            },
            onOptionSelected = { format ->
                onValueChange(setting.copy(responseFormat = format))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Speed
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_speed)) },
        supportingContent = { Text("语速 (0.5 - 2.0, 1.0 为正常)") }
    ) {
        OutlinedNumberInput(
            value = setting.speed,
            onValueChange = { newSpeed ->
                if (newSpeed in 0.5f..2.0f) {
                    onValueChange(setting.copy(speed = newSpeed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.setting_tts_page_speed)
        )
    }

    // Volume
    formItem(
        headlineContent = { Text("Volume") },
        supportingContent = { Text("音量 (0.1 - 2.0, 1.0 为正常)") }
    ) {
        OutlinedNumberInput(
            value = setting.volume,
            onValueChange = { newVolume ->
                if (newVolume in 0.1f..2.0f) {
                    onValueChange(setting.copy(volume = newVolume))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Volume"
        )
    }

    // Sample Rate
    val sampleRates = listOf(8000, 16000, 22050, 24000)

    formItem(
        headlineContent = { Text("Sample Rate") },
        supportingContent = { Text("采样率 (Hz)") }
    ) {
        SelectTextField(
            value = setting.sampleRate.toString(),
            options = sampleRates,
            readOnly = true,
            onOptionSelected = { rate ->
                onValueChange(setting.copy(sampleRate = rate))
            },
            optionToString = { rate -> "$rate Hz" },
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Instruction (仅 stepaudio-2.5-tts 生效)
    formItem(
        headlineContent = { Text("Instruction") },
        supportingContent = { Text("全局语境指令, 仅 stepaudio-2.5-tts 生效 (≤200 字符, 留空不下发)") }
    ) {
        OutlinedTextField(
            value = setting.instruction,
            onValueChange = { newInstruction ->
                // 服务端上限 200 字符, 客户端做一层保护
                if (newInstruction.length <= 200) {
                    onValueChange(setting.copy(instruction = newInstruction))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("例如: 语气温柔, 语速偏慢") },
            minLines = 2,
            maxLines = 4,
        )
    }
}

private fun CardGroupScope.volcengineTTSItems(
    setting: TTSProviderSetting.Volcengine,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    formItem(
        headlineContent = { Text("API Key") },
        supportingContent = { Text("请填写豆包语音控制台的 API Key，不是火山方舟控制台的 API Key。") }
    ) {
        var keyVisible by remember(setting.id) { mutableStateOf(false) }
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        imageVector = if (keyVisible) HugeIcons.ViewOff else HugeIcons.View,
                        contentDescription = if (keyVisible) "隐藏 API Key" else "显示 API Key",
                    )
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    formItem(headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) }) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { onValueChange(setting.copy(baseUrl = it)) },
            placeholder = { Text("https://openspeech.bytedance.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    formItem(
        headlineContent = { Text("资源 ID") },
        supportingContent = { Text("需与已开通的服务和音色匹配，默认 seed-tts-2.0。") }
    ) {
        OutlinedTextField(
            value = setting.resourceId,
            onValueChange = { onValueChange(setting.copy(resourceId = it)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    formItem(
        headlineContent = { Text("音色 ID") },
        supportingContent = { Text("填写控制台中的音色 ID，默认使用 VV 音色。") }
    ) {
        OutlinedTextField(
            value = setting.speaker,
            onValueChange = { onValueChange(setting.copy(speaker = it)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_speed)) },
        supportingContent = { Text("范围 -50～100，0 为正常语速，-50 为半速，100 为两倍速。") }
    ) {
        OutlinedNumberInput(
            value = setting.speechRate,
            onValueChange = { onValueChange(setting.copy(speechRate = it.coerceIn(-50, 100))) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun CardGroupScope.deepSeekTTSItems(
    setting: TTSProviderSetting.DeepSeek,
    onValueChange: (TTSProviderSetting) -> Unit
) {
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text("DeepSeek 开放平台的 API Key") }
    ) {
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-...") },
            singleLine = true,
        )
    }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = { Text("请求地址为 {Base URL}/v1/tools/tts") }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { onValueChange(setting.copy(baseUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://api.deepseek.com") },
            singleLine = true,
        )
    }

    val voices = listOf(
        "stella" to "Stella（女声）",
        "tide" to "Tide（男声）",
    )
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_voice)) },
        supportingContent = { Text("语种根据文本自动识别，不支持调节语速") }
    ) {
        SelectTextField(
            value = setting.voiceId,
            options = voices,
            onValueChange = { onValueChange(setting.copy(voiceId = it)) },
            onOptionSelected = { (voiceId, _) -> onValueChange(setting.copy(voiceId = voiceId)) },
            optionToString = { (_, label) -> label },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 自建的 OpenAI 兼容 TTS 服务表单, 按服务端文档分成三节:
 * 服务 (baseUrl / apiKey / model) -> 音色与语言 (voice / language / instructions) -> 输出格式。
 *
 * 空字符串 = 不下发; 采样参数见 [remoteSamplingItems] (null = 不下发 = 保留引擎默认值)。
 *
 * internal 而非 private: 「远程 TTS 服务」设置页要复用同一套表单, 避免两处实现漂移。
 *
 * @param voiceOptions 服务端返回的音色列表, 为空表示还没拉取 (用户仍可手写音色名)
 * @param onRefreshVoices 非 null 才显示「刷新音色列表」按钮
 * @param voicesLoading 拉取中, 用于禁用刷新按钮并显示进度
 * @param voiceLoadError 拉取失败的提示, 非 null 时以 supportingContent 展示
 */
internal fun CardGroupScope.remoteTTSItems(
    setting: TTSProviderSetting.RemoteTTS,
    onValueChange: (TTSProviderSetting) -> Unit,
    voiceOptions: List<RemoteVoiceInfo> = emptyList(),
    onRefreshVoices: (() -> Unit)? = null,
    voicesLoading: Boolean = false,
    voiceLoadError: String? = null,
) {
    // ---------------- 服务 ----------------
    // Base URL: 非空但不是合法 URL 时标红
    val baseUrlInvalid = setting.baseUrl.isNotBlank() && setting.baseUrl.toHttpUrlOrNull() == null
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_base_url)) },
        supportingContent = {
            Text(
                if (baseUrlInvalid) {
                    stringResource(R.string.setting_tts_page_base_url_invalid)
                } else {
                    stringResource(R.string.setting_tts_page_base_url_self_hosted_description)
                }
            )
        }
    ) {
        OutlinedTextField(
            value = setting.baseUrl,
            onValueChange = { onValueChange(setting.copy(baseUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("http://127.0.0.1:8080/v1") },
            singleLine = true,
            isError = baseUrlInvalid,
        )
    }

    // API Key: 密码遮罩, 可切换显示
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_api_key)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_api_key_optional_description)) }
    ) {
        var keyVisible by remember(setting.id) { mutableStateOf(false) }
        OutlinedTextField(
            value = setting.apiKey,
            onValueChange = { onValueChange(setting.copy(apiKey = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.setting_tts_page_api_key_placeholder_openai)) },
            visualTransformation = if (keyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        imageVector = if (keyVisible) HugeIcons.ViewOff else HugeIcons.View,
                        contentDescription = null,
                    )
                }
            },
            singleLine = true,
        )
    }

    // Model
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_model)) },
        supportingContent = { Text(stringResource(R.string.setting_tts_page_model_optional_description)) }
    ) {
        OutlinedTextField(
            value = setting.model,
            onValueChange = { onValueChange(setting.copy(model = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("qwen3-tts-base") },
            singleLine = true,
        )
    }

    // ---------------- 音色与语言 ----------------
    remoteGroupTitle { stringResource(R.string.setting_remote_tts_group_voice_language) }

    // Voice: SelectTextField 同时允许下拉选择和自由输入, 服务端列表为空时也能手打音色名
    val voiceChoices = listOf(RemoteVoiceChoice(name = "", kind = "")) +
        voiceOptions.map { RemoteVoiceChoice(name = it.name, kind = it.kind) }
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_remote_tts_voice)) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.setting_remote_tts_voice_hint))
                if (voiceOptions.isEmpty() && voiceLoadError == null) {
                    Text(stringResource(R.string.setting_remote_tts_voice_empty))
                }
                if (voiceLoadError != null) {
                    Text(
                        text = voiceLoadError,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    ) {
        SelectTextField(
            value = setting.voice,
            options = voiceChoices,
            onValueChange = { onValueChange(setting.copy(voice = it)) },
            onOptionSelected = { choice -> onValueChange(setting.copy(voice = choice.name)) },
            optionToString = { choice ->
                when {
                    choice.name.isBlank() -> stringResource(R.string.setting_remote_tts_voice_none)
                    choice.kind.equals("registered", ignoreCase = true) ->
                        stringResource(R.string.setting_remote_tts_voice_registered) + " · " + choice.name

                    choice.kind.equals("speaker", ignoreCase = true) ->
                        stringResource(R.string.setting_remote_tts_voice_speakers) + " · " + choice.name

                    else -> choice.name
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("vivian") },
        )
        if (onRefreshVoices != null) {
            TextButton(onClick = onRefreshVoices, enabled = !voicesLoading) {
                if (voicesLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.setting_remote_tts_voice_refresh))
            }
        }
    }

    // Language: 空 = 服务端默认; 候选值里带 auto, 同时保留自由输入以支持自定义语言标签
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_remote_tts_language)) },
        supportingContent = { Text(stringResource(R.string.setting_remote_tts_language_hint)) }
    ) {
        SelectTextField(
            value = setting.language,
            options = listOf("") + TTSProviderSetting.RemoteTTS.REMOTE_TTS_LANGUAGES,
            onValueChange = { onValueChange(setting.copy(language = it)) },
            onOptionSelected = { language -> onValueChange(setting.copy(language = language)) },
            optionToString = { language ->
                if (language.isBlank()) {
                    stringResource(R.string.setting_remote_tts_language_default)
                } else {
                    language
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Chinese") },
        )
    }

    // Instructions: VoiceDesign 模型必填, 其它模型可选; 空则不下发
    formItem(
        headlineContent = { Text(stringResource(R.string.setting_remote_tts_instructions)) },
        supportingContent = { Text(stringResource(R.string.setting_remote_tts_instructions_hint)) }
    ) {
        OutlinedTextField(
            value = setting.instructions,
            onValueChange = { onValueChange(setting.copy(instructions = it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("gentle, slightly slower") },
            minLines = 2,
            maxLines = 4,
        )
    }

    // ---------------- 输出格式 ----------------
    remoteGroupTitle { stringResource(R.string.setting_remote_tts_group_output) }

    formItem(
        headlineContent = { Text(stringResource(R.string.setting_tts_page_response_format)) },
        supportingContent = { Text(stringResource(R.string.setting_remote_tts_response_format_hint)) }
    ) {
        SelectTextField(
            value = setting.responseFormat,
            options = TTSProviderSetting.RemoteTTS.REMOTE_TTS_RESPONSE_FORMATS,
            onOptionSelected = { format -> onValueChange(setting.copy(responseFormat = format)) },
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Sample Rate: 只有裸 PCM 裸流需要, 播放时用它补 WAV 头
    if (setting.responseFormat.trim().equals("pcm", ignoreCase = true)) {
        formItem(
            headlineContent = { Text(stringResource(R.string.setting_tts_page_sample_rate)) },
            supportingContent = { Text(stringResource(R.string.setting_tts_page_sample_rate_pcm_description)) }
        ) {
            SelectTextField(
                value = setting.sampleRate.toString(),
                options = TTSProviderSetting.RemoteTTS.REMOTE_TTS_SAMPLE_RATES,
                onOptionSelected = { rate -> onValueChange(setting.copy(sampleRate = rate)) },
                optionToString = { it.toString() },
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 采样参数 (精细控制): 9 个参数共用一条 null 语义 ——
 * null = 不写入请求 JSON = 保留服务端引擎默认值。
 *
 * 注意 gotcha: [CardGroupScope] 扩展函数的函数体不是 @Composable, 里面不能调 remember,
 * 组合状态(输入框文本)全部下沉到 [RemoteSamplingValueField] 这个 @Composable 里,
 * 这里只负责录制 [CardGroupScope.formItem] 条目。
 */
internal fun CardGroupScope.remoteSamplingItems(
    setting: TTSProviderSetting.RemoteTTS,
    onValueChange: (TTSProviderSetting) -> Unit,
) {
    // 顶部说明
    item(headlineContent = { Text(stringResource(R.string.setting_remote_tts_sampling_hint)) })

    // 9 行由元数据循环生成, 不手写重复代码
    TTSProviderSetting.RemoteTTS.REMOTE_TTS_SAMPLING_PARAMS.forEach { param ->
        val value = setting.samplingValueOf(param.key)
        val enabled = value != null
        formItem(
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(param.key)
                    // 温度取 0 时是确定性采样, 习惯上叫「贪心」
                    if (param.isGreedyValue(value)) {
                        CardGroupValue(text = stringResource(R.string.setting_remote_tts_sampling_greedy))
                    }
                }
            },
            supportingContent = {
                when (param.key) {
                    "seed" -> Text(stringResource(R.string.setting_remote_tts_seed_hint))
                    "max_new_tokens" -> Text(
                        stringResource(
                            R.string.setting_remote_tts_max_new_tokens_seconds,
                            formatOneDecimal(
                                (value ?: param.defaultValue) / REMOTE_TTS_TOKENS_PER_SECOND
                            ),
                        )
                    )

                    else -> Unit
                }
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        // 打开时写入元数据默认值, 关闭时写回 null (不发送)
                        onValueChange(
                            setting.withSamplingValue(
                                key = param.key,
                                value = if (checked) param.defaultValue else null,
                            )
                        )
                    },
                )
                if (enabled) {
                    RemoteSamplingValueField(
                        settingId = setting.id,
                        param = param,
                        value = value ?: param.defaultValue,
                        onValueChange = { newValue ->
                            onValueChange(setting.withSamplingValue(param.key, newValue))
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onValueChange(setting.withSamplingValue(param.key, null)) },
                    ) {
                        Text(stringResource(R.string.setting_remote_tts_sampling_reset))
                    }
                } else {
                    Text(
                        text = stringResource(
                            R.string.setting_remote_tts_sampling_disabled,
                            formatSamplingValue(param, param.defaultValue),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    // 预设: 批量 copy, 其余参数一律回到「不发送」
    formItem {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { onValueChange(setting.withoutSamplingParams()) }) {
                Text(stringResource(R.string.setting_remote_tts_preset_default))
            }
            OutlinedButton(
                onClick = {
                    onValueChange(
                        setting.withoutSamplingParams().copy(
                            temperature = 0f,
                            subtalkerTemperature = 0f,
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.setting_remote_tts_preset_greedy))
            }
            OutlinedButton(
                onClick = {
                    onValueChange(
                        setting.withoutSamplingParams().copy(
                            temperature = 0.6f,
                            topK = 20,
                            topP = 0.9f,
                            repetitionPenalty = 1.1f,
                            subtalkerTemperature = 0.6f,
                            subtalkerTopK = 20,
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.setting_remote_tts_preset_stable))
            }
            OutlinedButton(
                onClick = {
                    onValueChange(
                        setting.withoutSamplingParams().copy(
                            temperature = 1.1f,
                            topP = 0.95f,
                            repetitionPenalty = 1.02f,
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.setting_remote_tts_preset_creative))
            }
        }
    }
}

/**
 * CardGroup 内部的小节标题: 白底条目里一行弱化文字, 不引入新组件。
 *
 * title 用 @Composable lambda 而不是 String: 调用点所在的 [remoteTTSItems] 是 CardGroupScope
 * 扩展函数、本身不是 @Composable, 在那里不能直接调 stringResource。
 */
private fun CardGroupScope.remoteGroupTitle(title: @Composable () -> String) {
    item(
        headlineContent = {
            Text(
                text = title(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    )
}

/** 音色下拉项: [name] 为空的那一项是「不指定」 */
private data class RemoteVoiceChoice(
    val name: String,
    val kind: String,
)

/** 服务端 `max_new_tokens` 与音频时长的粗略换算: 12.5 token ≈ 1 秒 */
private const val REMOTE_TTS_TOKENS_PER_SECOND = 12.5f

/**
 * 单个采样参数的数值输入框。
 *
 * 用普通文本输入框而不是 OutlinedNumberInput: 后者写死 KeyboardType.Number 敲不出负号,
 * 而 seed 需要 -1 表示随机。只有解析成功且落在元数据的合法区间内才回写 setting,
 * 越界和中间态("-"/"0.")留在文本里并标红, 避免实时钳制把用户正在输入的数字改掉。
 */
@Composable
private fun RemoteSamplingValueField(
    settingId: Uuid,
    param: TTSProviderSetting.RemoteTTSSamplingParam,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // seed 允许负数; 其余参数按元数据 min/max 校验
    val allowNegative = param.key == "seed"
    var text by remember(settingId, param.key) { mutableStateOf(formatSamplingValue(param, value)) }
    // 外部改动(预设 / 重置 / 重新启用)时同步文本; 用户正在输入的等价写法(如 "0.")不覆盖
    LaunchedEffect(param.key, value) {
        if (text.toFloatOrNull() != value) {
            text = formatSamplingValue(param, value)
        }
    }
    val parsed = text.toFloatOrNull()
    val valid = parsed != null && (allowNegative || parsed in param.min..param.max)
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            val newValue = newText.toFloatOrNull() ?: return@OutlinedTextField
            if (!allowNegative && newValue !in param.min..param.max) return@OutlinedTextField
            onValueChange(normalizeSamplingValue(param, newValue, allowNegative))
        },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        isError = !valid,
        keyboardOptions = KeyboardOptions(
            // 整数参数用数字键盘; 浮点要小数点, seed 要负号
            keyboardType = if (param.isFloat || allowNegative) KeyboardType.Text else KeyboardType.Number
        ),
    )
}

/** 元数据 key -> 设置字段; null 表示该参数未启用 (不下发) */
private fun TTSProviderSetting.RemoteTTS.samplingValueOf(key: String): Float? = when (key) {
    "seed" -> seed?.toFloat()
    "max_new_tokens" -> maxNewTokens?.toFloat()
    "temperature" -> temperature
    "top_k" -> topK?.toFloat()
    "top_p" -> topP
    "repetition_penalty" -> repetitionPenalty
    "subtalker_temperature" -> subtalkerTemperature
    "subtalker_top_k" -> subtalkerTopK?.toFloat()
    "subtalker_top_p" -> subtalkerTopP
    else -> null
}

/** 设置字段 <- 元数据 key; [value] 为 null 即把该字段写回 null (恢复「不发送」) */
private fun TTSProviderSetting.RemoteTTS.withSamplingValue(
    key: String,
    value: Float?,
): TTSProviderSetting.RemoteTTS = when (key) {
    "seed" -> copy(seed = value?.roundToInt())
    "max_new_tokens" -> copy(maxNewTokens = value?.roundToInt())
    "temperature" -> copy(temperature = value)
    "top_k" -> copy(topK = value?.roundToInt())
    "top_p" -> copy(topP = value)
    "repetition_penalty" -> copy(repetitionPenalty = value)
    "subtalker_temperature" -> copy(subtalkerTemperature = value)
    "subtalker_top_k" -> copy(subtalkerTopK = value?.roundToInt())
    "subtalker_top_p" -> copy(subtalkerTopP = value)
    else -> this
}

/** 9 个采样参数全部置 null = 全部不发送 */
private fun TTSProviderSetting.RemoteTTS.withoutSamplingParams(): TTSProviderSetting.RemoteTTS = copy(
    seed = null,
    maxNewTokens = null,
    temperature = null,
    topK = null,
    topP = null,
    repetitionPenalty = null,
    subtalkerTemperature = null,
    subtalkerTopK = null,
    subtalkerTopP = null,
)

/** 温度类参数为 0 时展示「贪心」徽标 */
private fun TTSProviderSetting.RemoteTTSSamplingParam.isGreedyValue(value: Float?): Boolean =
    (key == "temperature" || key == "subtalker_temperature") && value != null && value == 0f

/** 按元数据把输入值规整到合法范围 (整型取整, 浮点按 step 的小数位截断) */
private fun normalizeSamplingValue(
    param: TTSProviderSetting.RemoteTTSSamplingParam,
    value: Float,
    allowNegative: Boolean,
): Float {
    if (!param.isFloat) {
        val intValue = value.roundToInt()
        return if (allowNegative) {
            intValue.toFloat()
        } else {
            intValue.coerceIn(param.min.toInt(), param.max.toInt()).toFloat()
        }
    }
    return value.roundToDecimals(decimalsOf(param.step)).coerceIn(param.min, param.max)
}

/** 输入框回显的文本: 整型不带小数点, 浮点去掉长度不定的尾随 0 */
private fun formatSamplingValue(
    param: TTSProviderSetting.RemoteTTSSamplingParam,
    value: Float,
): String {
    if (!param.isFloat) return value.roundToInt().toString()
    val rounded = value.roundToDecimals(decimalsOf(param.step))
    return if (rounded == rounded.toInt().toFloat()) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}

/** "12.5" -> 1; "0.05" -> 2 (用于把浮点值截断到 step 的精度) */
private fun decimalsOf(step: Float): Int {
    val text = step.toString()
    val dot = text.indexOf('.')
    return if (dot < 0) 0 else (text.length - dot - 1).coerceAtMost(3)
}

private fun Float.roundToDecimals(decimals: Int): Float {
    var factor = 1f
    repeat(decimals) { factor *= 10f }
    return (this * factor).roundToInt() / factor
}

/** 秒数保留 1 位小数, 固定 Locale.US 避免某些区域给出本地数字 */
private fun formatOneDecimal(value: Float): String = String.format(Locale.US, "%.1f", value)
