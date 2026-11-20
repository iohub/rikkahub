package me.rerere.rikkahub.data.ai

/**
 * 语音语气控制的 system prompt。
 *
 * 取代了原先 `text_to_speech` 工具里的「每轮必须调用」：模型不再调用工具，而是在正文之后输出一个
 * `<tts_voice_control>` 块，块内是一段中文自然语言语气描述。展示层由 `TtsVoiceControlTransformer`
 * 把块从正文里剥掉并写进 metadata，本地 TTS 播放时读出来当动态 instruct。
 *
 * 文案要解决三件事，缺一件效果就明显变差：
 * 1. 位置与数量——块必须单独成段放在正文之后、每条回复最多一个，否则流式输出会把语气描述吐在正文中间；
 * 2. 块内写法——一段连贯的中文描述，而不是 `instruct: warm, slow` 这种关键词列表：provider 侧大多把
 *    instruct 当自然语言（RemoteTTS 直接拼进 instructions），逗号列表反而念得更平；
 * 3. 与正文情绪对齐——描述只影响朗读，不能借机改写正文，也不能每轮复用同一段。
 *
 * @param providerGuidance 当前选中 TTS provider 自带的语气标记引导，非空时追加在末尾。它回答的是
 *   「怎么念」（RemoteTTS 的「不要用内联情绪标签」、MiMo 的 `(开心 磁性)` 标记语法等），与这里的
 *   「何时输出、块内写什么」互补，所以是拼接而不是替换
 */
internal fun ttsVoiceControlPrompt(providerGuidance: String): String {
    val guidance = providerGuidance.trim()
    if (guidance.isEmpty()) return TTS_VOICE_CONTROL_RULES
    return buildString {
        append(TTS_VOICE_CONTROL_RULES)
        appendLine()
        appendLine()
        append("### 当前 TTS 提供商自己的要求（在下面这段朗读文本上同样生效，优先遵守）")
        appendLine()
        append(guidance)
    }
}

/**
 * 语气控制块的规则正文。
 *
 * internal 而不是 private：JVM 单测（`TtsVoiceControlPromptTest`）要直接断言关键约束与示例还在，
 * 避免日后调 prompt 时把「最多一个块」「放在正文之后」这类硬约束整段删掉而没人发现。
 */
internal val TTS_VOICE_CONTROL_RULES = """
    ## 语音语气控制（tts_voice_control）

    这一轮回复会由本地 TTS 朗读给用户。除了正文内容，你还负责决定它**怎么被念出来**：在正文之后输出一个
    `<tts_voice_control>`…`</tts_voice_control>` 块，块内用一段连续的中文自然语言描述这段话的读法——情绪、语速、
    音量、音色、停顿与呼吸感、吐字清晰度都可以写。程序会解析这个块、把它从展示文本里剥掉，并当作本轮朗读的语气指令。

    硬性规则：
    1. 每条回复最多一个块，必须单独成段放在正文之后；正文里不得出现块，也不得出现其它自定义标签。
    2. 块内只写一段连贯的中文描述，不加引号，不写 `instruct:` 之类的前缀，不用逗号堆砌关键词列表，
       不复述正文，不解释为什么这样念。描述要有足够信息量：情绪、语速、音量、音色、气息里至少写满几项，
       只写「平静地念」这种一句话等于没给指令，会被程序判为不合格而退回重写。
    3. 描述要与正文的情绪一致：正文克制时不要夸张，正文激烈时可以更饱满；不要每轮复用同一段描述。
    4. 语气只影响朗读，不得改变正文的内容、完整性与语气。
    5. 一个块描述的是**整条回复**的读法，不是其中某一段：正文有好几段时也不要拆成多个块。
    6. 只是简短确认（例如「好的」「明白了」）时可以省略整个块，直接正常回复。
    7. 块内的描述用中文写，与正文使用哪种语言无关；正文是英文、日文时，描述照样用中文。

    示例一（正文极度悲伤，块内是一整段描述）：

    就算你自己不想治，你也得考虑考虑别人的感受吧。我们这些朋友的感受你不在乎无所谓，那你家人呢？

    <tts_voice_control>
    以极度悲伤、带着明显哭腔的语气，用较小的音量缓缓诉说，语速缓慢，仿佛每一个字都承载着沉重的痛楚，声音颤抖而压抑，吐字虽轻却清晰可辨，透出深藏心底的哀伤与无助。
    </tts_voice_control>

    示例二（轻松的日常交代）：

    明天上午十点在三号会议室，我会提前十分钟提醒你。

    <tts_voice_control>
    语调明快放松，语速略快但吐字清楚，音量适中，说到时间点时轻轻加重，像朋友随口交代一件小事。
    </tts_voice_control>
""".trimIndent()
