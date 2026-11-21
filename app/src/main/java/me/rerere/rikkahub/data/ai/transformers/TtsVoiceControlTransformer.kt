package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.TtsVoiceMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata

/**
 * 标签名本体。
 *
 * 容忍规则以它为准, 并且**所有匹配点必须共用同一套规则**: 完整块正则、未闭合块定位、尾部半截前缀裁剪
 * 都允许名字两侧夹空白、都不区分大小写。否则会出现"完整标签被剥掉了、它的半截前缀反而留在正文里"的
 * 不一致——这层容错本身就是为了堵流式残留, 三处不统一等于白堵。
 *
 * 取舍: 容忍 `<` 之后夹空白会带来极小的误伤面(正文末尾恰好是 `a < t` 这类片段时会被当作半截标签裁掉)。
 * 这类文本只可能出现在正文**最末尾**且刚好是标签名真前缀, 实际概率远低于模型写成 `< tts_voice_control >`
 * 的概率, 因此接受。正文中间的 `<` 不受影响: 只有"从某个 `<` 一直到文本末尾都构成标签名真前缀"才会裁。
 */
private const val TTS_VOICE_TAG_NAME = "tts_voice_control"

/** 完整的语气控制块: 开标签到最近的闭标签之间的内容就是语气描述 */
private val TTS_VOICE_BLOCK_REGEX = Regex(
    "<\\s*$TTS_VOICE_TAG_NAME\\s*>([\\s\\S]*?)<\\s*/\\s*$TTS_VOICE_TAG_NAME\\s*>",
    RegexOption.IGNORE_CASE,
)

/** 未闭合块(开标签还没有闭标签): 从开标签起一直切到文本末尾 */
private val TTS_VOICE_OPEN_TAG_START_REGEX =
    Regex("<\\s*$TTS_VOICE_TAG_NAME", RegexOption.IGNORE_CASE)

/** 落单的闭标签: 开标签在更早的文本里已丢失时, 闭标签本身也不是正文 */
private val TTS_VOICE_STRAY_CLOSE_TAG_REGEX =
    Regex("<\\s*/\\s*$TTS_VOICE_TAG_NAME\\s*>", RegexOption.IGNORE_CASE)

/** 块被剥掉后往往留下 3 个以上连续换行, 压成一个空行(段落之间保留一处呼吸) */
private val TTS_VOICE_BLANK_LINES_REGEX = Regex("\\n{3,}")

/**
 * 把助手正文里的 `<tts_voice_control>` 块从展示文本里剥离, 内容存进 part 的 metadata。
 *
 * 语气描述是给本地 TTS 用的旁路信息, 用户不该在回复里看到它, 也不该被当作正文朗读。块的位置由
 * 提示词约定在正文之后单独成段, 但流式输出时块会一个字一个字地长出来, 所以三种状态都要处理:
 * 已闭合的块整段删掉, 未闭合的块从开标签一直切到文本末尾, 开标签自己还没写完时裁掉尾部那半截前缀。
 *
 * 三个钩子都跑同一套剥离逻辑, 结果幂等: 已剥离过的文本里没有块, 再跑一次只会原样返回(metadata 保留),
 * 因此 `visualTransform` 每次预览重算、`transform` 落库前、`onGenerationFinish` 流式结束兜底可以复用。
 *
 * 注意 `Reasoning` part 也要处理: `ThinkTagTransformer` 注册在本 transformer 之前, 会把 `<think>…</think>`
 * 整段搬进 `UIMessagePart.Reasoning`, 模型若把语气块写进思考里, 块文本会留在思考折叠区可见。
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
        when (part) {
            is UIMessagePart.Text -> part.stripTtsVoiceControl()
            is UIMessagePart.Reasoning -> part.stripTtsVoiceControl()
            else -> part
        }
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
 *
 * 倒序遍历所有 part 的 metadata, 所以语气块无论落在 `Text` 还是 `Reasoning` 上都能读回。
 */
internal fun UIMessage.ttsVoiceInstruct(): String =
    parts.asReversed().firstNotNullOfOrNull { part ->
        part.metadataAs<TtsVoiceMetadata>()?.instruct?.takeIf { it.isNotBlank() }
    }.orEmpty()

/**
 * 这条消息上一共剥离掉多少个 `<tts_voice_control>` 块; 跨 part 求和, Reasoning 里的块也算。
 *
 * 块本体已经从正文里删掉了, 审查 (`toText()`) 看不到任何标签, 所以「到底出现了几个块」只能靠 metadata
 * 里的计数回读。三个钩子各自会跑一遍剥离, 计数在 [stripTtsVoiceControlBlocks] 那层做成累加,
 * 重复跑不会把同一个块数成两个, 见 [TtsVoiceMetadata.blockCount]。
 */
internal fun UIMessage.ttsVoiceBlockCount(): Int =
    parts.sumOf { part -> part.metadataAs<TtsVoiceMetadata>()?.blockCount ?: 0 }

/**
 * 一次剥离要写回 metadata 的内容。
 *
 * @property instruct 生效的语气描述: 本次剥到的优先, 没有就沿用旧值(不能把上一步写好的语气抹掉)
 * @property blockCount 累计块数量
 */
private data class StrippedMetadata(val instruct: String?, val blockCount: Int?)

/**
 * 算出这次剥离该写进 metadata 的内容; 返回 null 表示**没有任何新信息**, 调用方应原样返回这个 part。
 *
 * 块计数是累加的: 只有本次真的剥到了块才增长。这一点是幂等性的关键 —— `visualTransform` 每次预览重算、
 * `transform` 落库前、`onGenerationFinish` 流式结束兜底会把同一段文本剥三遍, 后两遍里已经没有块
 * (`blockCount == 0`), 此时计数必须保持不变, 否则 1 个块会被数成 3 个, 审查永远判否。
 * 反过来, 多步回复里每个步骤各冒出一个块时, 每次都能累加上去。
 */
private fun TtsVoiceMetadata?.withStripped(stripped: StrippedText): StrippedMetadata? {
    val existingInstruct = this?.instruct
    val existingCount = this?.blockCount
    val newInstruct = stripped.instruct ?: existingInstruct
    val newCount = if (stripped.blockCount > 0) (existingCount ?: 0) + stripped.blockCount else existingCount
    if (newInstruct == existingInstruct && newCount == existingCount) return null
    return StrippedMetadata(instruct = newInstruct, blockCount = newCount)
}

/**
 * 剥离单个 Text part 里的语气控制块。
 *
 * 处理后的空文本**不删除**这个 part: part 下标被上层(工具调用、`transformNewParts` 的切分点)依赖,
 * 打乱下标会让后续步骤的内容错位; `groupMessageParts` 本来就忽略空文本。
 */
private fun UIMessagePart.Text.stripTtsVoiceControl(): UIMessagePart.Text {
    val stripped = stripTtsVoiceControlBlocks(text)
    // 没有新信息(没有块, 也没有新的空块计数): 原样返回, 已有的 metadata(其它 transformer / provider
    // 写的)不能被清掉
    val update = metadataAs<TtsVoiceMetadata>().withStripped(stripped)
        ?: return if (stripped.text == text) this else copy(text = stripped.text)
    return copy(
        text = stripped.text,
        // 必须合并而不是覆盖: 同一个 part 的 metadata 是多来源共享槽, Google 的 thoughtSignature
        // 之类回传必需的签名不能被语气块顺手抹掉
        metadata = metadata.mergeWith(
            TtsVoiceMetadata(instruct = update.instruct, blockCount = update.blockCount).toMetadata(),
        ),
    )
}

/**
 * 剥离 Reasoning part 里的语气控制块。
 *
 * 场景: provider 没有原生 reasoning, 模型把语气块写进了 `<think>…</think>`, `ThinkTagTransformer`
 * 把整段搬进 Reasoning -> 块文本会出现在思考折叠区。这里同样剥掉, 并把语气写进 Reason part 的
 * metadata(`ttsVoiceInstruct()` 会读到)。
 */
private fun UIMessagePart.Reasoning.stripTtsVoiceControl(): UIMessagePart.Reasoning {
    val stripped = stripTtsVoiceControlBlocks(reasoning)
    // 与 Text 同一套合并规则: 语气沿用旧值、块计数累加, 没有新信息就原样返回
    val update = metadataAs<TtsVoiceMetadata>().withStripped(stripped)
        ?: return if (stripped.text == reasoning) this else copy(reasoning = stripped.text)
    // 其余 key(provider 签名等)靠合并保留
    return copy(
        reasoning = stripped.text,
        metadata = metadata.mergeWith(
            TtsVoiceMetadata(instruct = update.instruct, blockCount = update.blockCount).toMetadata(),
        ),
    )
}

/**
 * 合并 metadata: 同 key 用新的, 其它 key 原样保留。
 *
 * 与 `ai` 模块 `StreamChunkHandler.mergeMetadata` 语义一致(那里是 private, 这里补一份等价实现)。
 *
 * `internal` 而不是 `private`: 编排(见 `AgentOrchestrationApplier`)把块阶段的语气写回 metadata 时
 * 必须与这里用同一套合并规则 —— 各写一份, 「同 key 覆盖、其它 key 保留」的语义迟早会长歪。
 */
internal fun JsonObject?.mergeWith(other: JsonObject?): JsonObject? = when {
    this == null -> other
    other == null -> this
    else -> JsonObject(this + other)
}

/**
 * 剥离结果。
 *
 * @property text 给用户看的正文
 * @property instruct 本 part 内最后一个非空语气描述(没有则为 null)
 * @property blockCount 本次匹配到的完整块数量, **包含内容为空的块**: 空块也要算进「出现了几个块」,
 * 质量审查靠它判断「恰好一个非空块」这条约定
 */
private data class StrippedText(
    val text: String,
    val instruct: String?,
    val blockCount: Int,
)

/**
 * 把文本里的控制块删掉, 同时取出块内的语气描述。
 *
 * 只删尾部空白(`trimEnd`), **不做 `trim`/`trimStart`**: `ThinkTagTransformer` 的 `<think>` 识别是
 * `\A\s*<think>` 锚定的, 它注册在本 transformer 之前, 但消息会被反复转换, 抠掉正文前导空白会让
 * 后续步骤的锚定行为发生变化。
 */
private fun stripTtsVoiceControlBlocks(raw: String): StrippedText {
    // 1. 完整块: 块内 trim 后为空的不算有效语气(模型偶尔会输出空块), 但块本身照样要从正文里删掉。
    //    计数**不过滤**空块, 所以先留住全部匹配再分别处理, 避免为了计数再把正则跑一遍
    val blocks = TTS_VOICE_BLOCK_REGEX.findAll(raw).toList()
    val instructs = blocks
        .map { match -> match.groupValues[1].trim() }
        .filter { instruct -> instruct.isNotEmpty() }
    var text = TTS_VOICE_BLOCK_REGEX.replace(raw, "")
    // 2. 未闭合块: 流式输出时块还没写完, 从残留的开标签一直切到末尾, 半截语气描述不能显示出来。
    //    取第一个残留开标签: 存在多个时也从最早的那个切起, 否则切完仍会留下一个可见的标签本身
    val unclosedOpenTag = TTS_VOICE_OPEN_TAG_START_REGEX.find(text)
    if (unclosedOpenTag != null) {
        text = text.substring(0, unclosedOpenTag.range.first)
    }
    // 3. 落单的闭标签(极端情况下开标签在更早的文本里已丢失)同样删掉: 标签本身不是正文
    text = TTS_VOICE_STRAY_CLOSE_TAG_REGEX.replace(text, "")
    // 4. 尾部半截开标签: 流式时开标签是一个字一个字长出来的(`<`、`<t`、…、`<tts_voice_control`),
    //    这一截前缀既不是正文也会瞬时显示, 若生成在标签内部被切断(截断/手动停止/断网)还会永久落库。
    //    顺序很重要: 必须在上面两步处理完完整/未闭合开标签之后再做, 否则会把还能完整匹配的标签切碎
    text = text.trimIncompleteOpenTagPrefix()
    // 5. 块通常独占一段, 删掉后会剩两个空行, 压成一个空行
    text = text.replace(TTS_VOICE_BLANK_LINES_REGEX, "\n\n").trimEnd()
    return StrippedText(text = text, instruct = instructs.lastOrNull(), blockCount = blocks.size)
}

/**
 * 裁掉文本末尾"开标签还没写完"的真前缀。
 *
 * 只有从**最后一个** `<` 到文本末尾这一段整体构成标签名的真前缀(允许名字两侧夹空白, 与前面的匹配规则
 * 一致)时才裁, 所以 `a < b`、`1<2 成立` 这类正文原样保留——`<` 后面跟的字符不是标签名的前缀。
 *
 * 局限(有意为之): 只处理开标签的半截前缀。落单的闭标签前缀(`</tts_voice_contr`)意味着它的开标签在
 * 更早的 part 里已丢失(块被 part 边界劈开), 属于提示词层约定失效的极端情况, 不在本函数的兜底范围。
 */
private fun String.trimIncompleteOpenTagPrefix(): String {
    val lastLt = lastIndexOf('<')
    if (lastLt < 0) return this
    val tail = substring(lastLt)
    return if (isIncompleteOpenTagPrefix(tail)) substring(0, lastLt) else this
}

/**
 * [fragment](从某个 `<` 起、到文本末尾的那一段)是不是"开标签还没写完"的形态。
 *
 * 与 [TTS_VOICE_BLOCK_REGEX] / [TTS_VOICE_OPEN_TAG_START_REGEX] 共用同一套容忍规则: 不区分大小写,
 * `<` 与名字之间、名字之后允许空白。结尾出现 `>` 或其它字符一律判否——完整标签在上一步已经被剥掉了,
 * 这里只接纳"还没长完"的形态, 避免把正文里恰好以标签名开头的普通文本越界吃掉。
 */
private fun isIncompleteOpenTagPrefix(fragment: String): Boolean {
    if (!fragment.startsWith("<")) return false
    var index = 1
    // `<` 与名字之间的空白
    while (index < fragment.length && fragment[index].isWhitespace()) index++
    var matched = 0
    while (index < fragment.length) {
        val char = fragment[index]
        if (char.isWhitespace()) {
            // 名字还没写完就出现空白: 不是合法前缀(`< tts_vo` 中间不会断行)
            if (matched < TTS_VOICE_TAG_NAME.length) return false
            index++
            continue
        }
        // 名字已写完, 后面只允许空白; 出现别的字符(含 `>`)说明这不是半截标签
        if (matched == TTS_VOICE_TAG_NAME.length) return false
        if (char.lowercaseChar() != TTS_VOICE_TAG_NAME[matched]) return false
        matched++
        index++
    }
    return true
}
