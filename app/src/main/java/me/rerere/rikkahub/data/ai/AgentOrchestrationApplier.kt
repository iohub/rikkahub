package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.TtsVoiceMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.ai.prompts.ORCHESTRATION_TTS_VOICE_TAG
import me.rerere.rikkahub.data.ai.transformers.mergeWith
import me.rerere.rikkahub.data.model.AssistantOrchestration

/**
 * 把编排块阶段的产出写回回复消息。
 *
 * 两类块的落点刻意不同, 但它们都要「写进同一条消息」而不是另起一条:
 * 1. `tts_voice_control` —— 与内置 TTS 链路**完全同构**: 内容进 part 的 metadata, 正文里一个字都不留。
 *    这样 `ttsVoiceInstruct()` / `ttsVoiceBlockCount()` / TTS 自动播放 / 语音固化都照旧能读到它,
 *    而用户界面上看不到任何标签 —— 编排只是换了「谁来写这段语气」, 没有换「语气怎么被消费」。
 * 2. 其它标签 —— 作为可见文本追加到末尾。这些标签没有内置消费者, 唯一的去处就是正文, 藏起来等于丢失。
 *
 * 幂等: 同一条消息被重复应用(生成重试、状态回写、调用方重跑)时不产生重复的可见块, 已有的语气
 * metadata 被新内容覆盖而不是叠加 —— 语气是「这条消息最终生效的那一份」, 累积起来只会读到过期的描述。
 */
internal fun UIMessage.applyOrchestrationBlocks(blocks: List<ProducedBlock>): UIMessage {
    if (blocks.isEmpty()) return this
    // 这一批块会写回的标签：语气块自己也算在内，它用来识别「哪些 Text 是编排追加的块」，
    // 见 [withTtsVoiceInstruct]。用整批而不是逐个，才让「同样的块再应用一次」落在同一个 part 上。
    val blockTags = blocks.map { it.tag.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
    var parts = this.parts
    for (block in blocks) {
        // 空产出写不进任何有意义的内容: 语气块写了空 instruct 反而会把已有的有效语气抹掉, 直接跳过
        val content = block.content.trim()
        if (content.isEmpty()) continue
        parts = if (block.isTtsVoiceBlock()) {
            parts.withTtsVoiceInstruct(content, blockTags)
        } else {
            parts.withVisibleBlock(block.copy(tag = block.tag.trim(), content = content))
        }
    }
    return if (parts == this.parts) this else copy(parts = parts)
}

/**
 * 这条消息里的**对话正文**: 去掉编排写回的可见块(整段 `<tag>…</tag>`)之后的文本。
 *
 * 对话阶段的审查必须用这个口径: 审查标准里「出现标签块即不合格」针对的是模型自己往正文里写标签,
 * 而写回块是编排刻意追加的产物, 混进去会让每一条编排回复都被判否。
 * 没配非语气块时结果与 [dialogueOnlyText] 完全一致。
 */
internal fun UIMessage.dialogueTextWithoutBlocks(orchestration: AssistantOrchestration): String {
    val tags = orchestration.blockStages().map { it.outputTag.trim() }.filter { it.isNotEmpty() }
    if (tags.isEmpty()) return dialogueOnlyText()
    return parts
        .filterIsInstance<UIMessagePart.Text>()
        .map { it.text.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { text -> tags.any { tag -> text.isWholeBlockOf(tag) } }
        .joinToString(separator = "\n")
}

/** 语气块: 大小写与首尾空白都容忍, 与 `AssistantOrchestration.ownsTag` 同一套比较规则 */
private fun ProducedBlock.isTtsVoiceBlock(): Boolean =
    tag.trim().equals(ORCHESTRATION_TTS_VOICE_TAG, ignoreCase = true)

/**
 * 把语气描述写到承载正文的那个 Text part 上。
 *
 * 「承载正文」取**最后一个非块的 Text**: 多步回复里正文可能是多个 part, 最后一段一定是模型最后写的,
 * 而 `ttsVoiceInstruct()` 是倒序扫 metadata 取第一个非空值 —— 挂在它身上最贴近「这一条消息的语气」。
 *
 * 必须排除「编排自己追加的可见块」([blockTags] 里那些整段 `<tag>…</tag>` 的 part): 它们恒在末尾,
 * 不排除的话第二次应用会把语气写到块上, 同一条消息的两次写入落在不同位置, 幂等性就没了。
 *
 * 一个 Text part 都没有时(理论上不会, 编排要求正文非空)补一个空 Text 承载 metadata:
 * `groupMessageParts` 本来就忽略空文本, 界面上看不出多了一个 part。
 */
private fun List<UIMessagePart>.withTtsVoiceInstruct(
    instruct: String,
    blockTags: List<String>,
): List<UIMessagePart> {
    fun UIMessagePart.isBodyText(): Boolean = this is UIMessagePart.Text &&
        text.isNotBlank() &&
        blockTags.none { tag -> text.trim().isWholeBlockOf(tag) }

    val target = indexOfLast { it.isBodyText() }
        .takeIf { it >= 0 }
        ?: indexOfLast { it is UIMessagePart.Text }
    if (target < 0) {
        return this + UIMessagePart.Text(
            text = "",
            metadata = TtsVoiceMetadata(instruct = instruct, blockCount = 1).toMetadata(),
        )
    }
    return toMutableList().also { list ->
        val part = list[target] as UIMessagePart.Text
        list[target] = part.copy(
            // 必须合并而不是覆盖: 同一个 part 的 metadata 是多来源共享槽, provider 回传必需的签名
            // (如 Google thoughtSignature)不能被语气块顺手抹掉
            metadata = part.metadata.mergeWith(
                TtsVoiceMetadata(instruct = instruct, blockCount = 1).toMetadata(),
            ),
        )
    }
}

/** 未知标签的可见块: 整段追加到末尾, 已经存在同样的块时不重复追加 */
private fun List<UIMessagePart>.withVisibleBlock(block: ProducedBlock): List<UIMessagePart> {
    val text = formatBlockText(block)
    val exists = any { it is UIMessagePart.Text && it.text.trim() == text }
    return if (exists) this else this + UIMessagePart.Text(text = text)
}

/** 这段文本是不是整段就是一个 `<tag>…</tag>` 块(编排写回 `formatBlockText` 的产物) */
private fun String.isWholeBlockOf(tag: String): Boolean {
    val name = Regex.escape(tag)
    return Regex(
        pattern = """\A<\s*$name\s*>[\s\S]*<\s*/\s*$name\s*>\z""",
        option = RegexOption.IGNORE_CASE,
    ).matches(this)
}
