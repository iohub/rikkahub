package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.TtsVoiceMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata

/** 完整的语气控制块: 开标签到最近的闭标签之间的内容就是语气描述 */
private val TTS_VOICE_BLOCK_REGEX = Regex("<tts_voice_control>([\\s\\S]*?)</tts_voice_control>")

private const val TTS_VOICE_OPEN_TAG = "<tts_voice_control>"
private const val TTS_VOICE_CLOSE_TAG = "</tts_voice_control>"

/** 块被剥掉后往往留下 3 个以上连续换行, 压成一个空行(段落之间保留一处呼吸) */
private val TTS_VOICE_BLANK_LINES_REGEX = Regex("\n{3,}")

/**
 * 把助手正文里的 `<tts_voice_control>` 块从展示文本里剥离, 内容存进 part 的 metadata。
 *
 * 语气描述是给本地 TTS 用的旁路信息, 用户不该在回复里看到它, 也不该被当作正文朗读。块的位置由
 * 提示词约定在正文之后单独成段, 但流式输出时块会一个字一个字地长出来, 所以这里两种状态都要处理:
 * 已闭合的块整段删掉, 未闭合的块从开标签一直切到文本末尾。
 *
 * 三个钩子都跑同一套剥离逻辑, 结果幂等: 已剥离过的文本里没有块, 再跑一次只会原样返回(metadata 保留),
 * 因此 `visualTransform` 每次预览重算、`transform` 落库前、`onGenerationFinish` 流式结束兜底可以复用。
 */
object TtsVoiceControlTransformer : OutputMessageTransformer {
    /** 流式预览: 只是让用户看不见控制块, 不改消息本身的内容结构 */
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = messages.transformTtsVoiceControl()

    /** 落库前的那一步: 与预览同样的剥离, 顺便把 metadata 写进持久化的消息 */
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = messages.transformTtsVoiceControl()

    /** 生成结束兜底: 流式结束时块可能仍处于未闭合状态, 再切一遍 */
    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = messages.transformTtsVoiceControl()
}

/**
 * 剥离列表里每条助手消息的语气控制块; 其它角色的消息(用户自己贴进来的文本)一律不动。
 */
internal fun List<UIMessage>.transformTtsVoiceControl(): List<UIMessage> = map { message ->
    if (message.role != MessageRole.ASSISTANT) return@map message
    val stripped = message.parts.map { part ->
        if (part is UIMessagePart.Text) part.stripTtsVoiceControl() else part
    }
    // 没有块的消息(绝大多数历史消息)保持原对象, 避免无意义地重建整条消息
    if (stripped == message.parts) message else message.copy(parts = stripped)
}

/**
 * 这条消息最终生效的 TTS 动态语气。
 *
 * 取**最后一个**非空的块: 与旧的 `text_to_speech` 工具一致——一轮里出现多个块时, 用户听到的就是最后
 * 那一段描述; 提示词已要求每条回复最多一个块, 这里只是兜底。语气为空(没有块 / 块内是空白)时返回 "",
 * 等价于"只用设置里的静态语气"。
 */
internal fun UIMessage.ttsVoiceInstruct(): String =
    parts.asReversed().firstNotNullOfOrNull { part ->
        part.metadataAs<TtsVoiceMetadata>()?.instruct?.takeIf { it.isNotBlank() }
    }.orEmpty()

/**
 * 剥离单个 Text part 里的语气控制块。
 *
 * 处理后的空文本**不删除**这个 part: part 下标被上层(工具调用、`transformNewParts` 的切分点)依赖,
 * 打乱下标会让后续步骤的内容错位; `groupMessageParts` 本来就忽略空文本。
 */
private fun UIMessagePart.Text.stripTtsVoiceControl(): UIMessagePart.Text {
    val stripped = stripTtsVoiceControlBlocks(text)
    val instruct = stripped.instruct
    // 没有块: 原样返回, 已有的 metadata(其它 transformer 写的)不能被清掉
    if (instruct == null) {
        return if (stripped.text == text) this else copy(text = stripped.text)
    }
    return copy(
        text = stripped.text,
        metadata = TtsVoiceMetadata(instruct = instruct).toMetadata(),
    )
}

/** 剥离结果: [text] 是给用户看的正文, [instruct] 是本 part 内最后一个非空语气描述(没有则为 null) */
private data class StrippedText(val text: String, val instruct: String?)

/**
 * 把文本里的控制块删掉, 同时取出块内的语气描述。
 *
 * 只删尾部空白(`trimEnd`), **不做 `trim`/`trimStart`**: `ThinkTagTransformer` 的 `<think>` 识别是
 * `\A\s*<think>` 锚定的, 它注册在本 transformer 之前, 但消息会被反复转换, 抠掉正文前导空白会让
 * 后续步骤的锚定行为发生变化。
 */
private fun stripTtsVoiceControlBlocks(raw: String): StrippedText {
    // 1. 完整块: 块内 trim 后为空的不算有效语气(模型偶尔会输出空块), 但块本身照样要从正文里删掉
    val instructs = TTS_VOICE_BLOCK_REGEX.findAll(raw)
        .map { match -> match.groupValues[1].trim() }
        .filter { instruct -> instruct.isNotEmpty() }
        .toList()
    var text = TTS_VOICE_BLOCK_REGEX.replace(raw, "")
    // 2. 未闭合块: 流式输出时块还没写完, 从残留的开标签一直切到末尾, 半截语气描述不能显示出来。
    //    取第一个残留开标签: 存在多个时也从最早的那个切起, 否则切完仍会留下一个可见的标签本身
    val unclosedIndex = text.indexOf(TTS_VOICE_OPEN_TAG)
    if (unclosedIndex >= 0) {
        text = text.substring(0, unclosedIndex)
    }
    // 3. 落单的闭标签(极端情况下开标签在更早的文本里已丢失)同样删掉: 标签本身不是正文
    if (TTS_VOICE_CLOSE_TAG in text) {
        text = text.replace(TTS_VOICE_CLOSE_TAG, "")
    }
    // 4. 块通常独占一段, 删掉后会剩两个空行, 压成一个空行
    text = text.replace(TTS_VOICE_BLANK_LINES_REGEX, "\n\n").trimEnd()
    return StrippedText(text = text, instruct = instructs.lastOrNull())
}
