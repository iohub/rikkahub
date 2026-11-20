package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.tts.provider.TTSManager

const val TEXT_TO_SPEECH_TOOL_NAME = "text_to_speech"

/**
 * 追加在 provider 语气引导之后的硬性规则。
 *
 * provider 的 `getPromptGuidance` 只回答「怎么念」（它自己的语气标记语法），
 * 这里回答「什么时候念、两个参数怎么填」，二者互补，所以拼接而不是替换。
 *
 * 除了规则条款，这里还带 few-shot 示例与反例：只写条款时模型容易「每轮复用同一组语气词」
 * 或「直接抄设置里的静态语气」，给出成对示例后这类偏差明显减少。
 *
 * 之所以是 internal 而不是 private：JVM 单测（TextToSpeechToolPromptTest）要直接断言关键片段存在，
 * 避免日后重构 prompt 时把示例或反例整段删掉而没人发现。
 */
internal val TEXT_TO_SPEECH_RULES = """
    ## Text-to-speech rules (hard requirements)

    1. Call `text_to_speech` exactly once in EVERY reply while this tool is available — even when the
       user never mentions audio. A reply that skips this call is treated as invalid.
    2. `text` is the spoken version of THIS reply, written for the ear:
       - strip every markdown marker: headings, **bold**, *italic*, `code`, bullet symbols, tables,
         link syntax, code fences;
       - drop emoji and kaomoji;
       - spell out what a person would say out loud: "42ms" -> "forty-two milliseconds",
         "v2.1" -> "version two point one", "3 台" -> "三台".
    3. Merge the whole reply into ONE `text`. Never call this tool twice in the same reply: the second
       call cuts off the playback of the first one, so only the last part is actually heard.
    4. `instruct` is the delivery direction YOU invent for THIS reply from its emotion, energy, pacing
       and situation: 2 to 6 comma-separated descriptors, for example "excited, fast, rising energy" or
       "warm, soft, slower pace, reassuring".
       - NEVER copy, translate or paraphrase the static instructions configured in the app settings.
         The system already merges them into the request; repeating them doubles the emphasis.
       - NEVER leave it empty and never write a full explanatory sentence.
       - Write it in concise English; if the static instructions in the settings are Chinese, write it
         in Chinese instead.
       - Vary it for every reply. The same descriptors message after message is a failure.
    5. If this reply has no prose for the user (for instance you only ran a background tool), still call
       the tool with one short natural confirmation, e.g. text "已经帮你把会议加到日程里了。".
    6. If the guidance above defines its own tone markers or syntax for the spoken text, that syntax
       wins inside `text`; keep everything else plain.
    7. The delivery direction only changes the audio. It must never change the content, completeness or
       tone of the written reply.

    ### Worked examples

    <example>
    User: 今天面试挂了，好难受。
    Your reply: 别灰心，能走到面试这一步已经很不容易了。要不要先歇一晚，明天我们一起复盘？
    Tool call:
      text: "别灰心，能走到面试这一步已经很不容易了。要不要先歇一晚，明天我们一起复盘？"
      instruct: "warm, gentle, slower pace, reassuring"
    </example>

    <example>
    User: 服务器还活着吗？快说！
    Your reply: 活着！三台实例全部在线，延迟 42ms，零报错。
    Tool call:
      text: "活着！三台实例全部在线，延迟四十二毫秒，零报错。"
      instruct: "urgent, fast, clipped, high energy"
    </example>

    <example>
    User: 明天几点开会？
    Your reply: 上午十点，三号会议室。我会提前十分钟提醒你。
    Tool call:
      text: "上午十点，三号会议室。我会提前十分钟提醒你。"
      instruct: "calm, neutral, clear, steady pace"
    </example>

    <example>
    User: 这段代码为什么要用双重检查锁？
    Your reply: **核心是为了少加锁。** 先判断 null，再进同步块，之后都走无锁快路径。代码如下：
    ```kotlin
    if (instance == null) { ... }
    ```
    Tool call:
      text: "核心是为了少加锁。先判断是否为空，再进同步块，之后都走无锁快路径。"
      instruct: "patient, instructional, slightly slower, clarifying"
    </example>

    <example>
    User: 帮我把明天下午三点的会加到日程。
    Your reply: （已由其它工具完成，没有别的正文要说）
    Tool call:
      text: "已经加好了，明天下午三点。需要提前提醒的话跟我说一声。"
      instruct: "matter-of-fact, brief, friendly"
    </example>

    ### Anti-patterns (rejected by quality review)

    - The same `instruct` in every reply ("warm, gentle, slow"): it must describe THIS reply.
    - `instruct: "温柔一点，语速放慢"` copied from the settings page: that is the static config, not your input.
    - `text: "**结论**：可以\n\n- 先备份再上线"`: markdown symbols and line breaks get read out loud.
    - Two calls in one reply ("上半段" then "下半段"): the second interrupts the first.
    - `instruct: "我觉得这一轮应该温柔一点，因为用户在难过"`: a whole sentence, use a few descriptors.
""".trimIndent()

/**
 * 工具的 description。
 *
 * 用户可以在设置里关掉工具提示词注入（`PromptInjectionOverride.isToolPromptsDisabled`），
 * 那时 [TEXT_TO_SPEECH_RULES] 不会进入 system prompt，description 就是模型唯一还能看到的指导，
 * 所以这里也必须自带一条 `instruct` 的微示例，而不是只描述「这个工具干什么」。
 */
internal val TEXT_TO_SPEECH_DESCRIPTION = """
    Speak text aloud to the user using the device's text-to-speech engine.
    Whenever this tool is available you MUST call it exactly once in every reply, even if the
    user did not explicitly ask for audio output. Never call it more than once per reply: if
    several parts of the reply should be read out, merge them into a single `text` argument
    (additional calls in the same reply cut off the previous playback).
    The tool returns immediately; audio plays in the background on the device.
    Always pass natural, readable plain text without markdown formatting, plus an `instruct`
    that describes how this particular reply should be delivered.
    Example: a comforting reply passes instruct "warm, softer, slower"; an urgent alert passes
    instruct "urgent, fast, clipped"; never reuse the user's static settings text. Keep `text`
    free of markdown and emoji.
""".trimIndent().replace("\n", " ")

internal fun buildTextToSpeechTool(
    eventBus: AppEventBus,
    ttsManager: TTSManager,
    settingsStore: SettingsStore,
): Tool = Tool(
    name = TEXT_TO_SPEECH_TOOL_NAME,
    description = TEXT_TO_SPEECH_DESCRIPTION,
    systemPrompt = { _, _ ->
        // 当前选中的 TTS provider 若硬编码了语气标记引导，先注入（它定义了「怎么念」）；
        // 再追加本工具的使用规则（定义了「何时念、参数怎么填」）。
        val guidance = settingsStore.settingsFlow.value.getSelectedTTSProvider()
            ?.let { ttsManager.getPromptGuidance(it) }
            .orEmpty()
            .trim()
        if (guidance.isEmpty()) TEXT_TO_SPEECH_RULES else "$guidance\n\n$TEXT_TO_SPEECH_RULES"
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "The text to speak aloud. Plain readable text of the current reply, " +
                            "with all markdown formatting removed."
                    )
                })
                put("instruct", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Required. Delivery direction for THIS reply only: emotion, energy, pacing, " +
                            "volume, emphasis and pauses, about 15-60 characters. For example " +
                            "\"excited and fast, playful tone\" or \"warm, soft and slow, comforting\". " +
                            "It is merged with the user's static instructions configured in settings, " +
                            "so never copy that static text here."
                    )
                })
            },
            required = listOf("text", "instruct")
        )
    },
    execute = {
        val text = it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            ?: error("text is required")
        // instruct 偶尔会被模型漏传或传空白：兜底成空串，等于「只用设置里的静态语气」，不会崩
        val instruct = it.jsonObject["instruct"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        eventBus.emit(AppEvent.Speak(text, instruct))
        val payload = buildJsonObject {
            put("success", true)
            // 回显实际生效的动态语气，便于 UI 与 trace 排查「为什么这轮语气是这样」
            put("instruct", instruct)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
