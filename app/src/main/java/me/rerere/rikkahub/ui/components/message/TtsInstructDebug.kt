package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import me.rerere.ai.ui.TtsVoiceMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.rikkahub.R

/** 语气控制块的标签名: 面板要还原成模型写的原始形态, 复制出来才能和提示词、请求日志逐字对照 */
private const val TTS_VOICE_TAG = "tts_voice_control"

/** 面板展开后的最大高度: 语气描述可能很长, 再长就在面板内部滚动, 别把整段对话顶下去 */
private val TTS_INSTRUCT_MAX_HEIGHT = 320.dp

/**
 * 一条消息里的一个语气块。
 *
 * [partIndex] 是该块所在的 part 在 `UIMessage.parts` 里的下标: 语气块会被 `TtsVoiceControlTransformer`
 * 从正文里剥掉, 正文本身看不出它来自哪一段, 排查「模型在第几步改了语气」时下标是唯一的定位线索。
 */
internal data class TtsInstructEntry(val partIndex: Int, val instruct: String)

/**
 * 收集一条消息里所有非空语气描述, 按 parts 顺序返回。
 *
 * 与 `UIMessage.ttsVoiceInstruct()`(播放链路用的那个)的区别: 那里只取**最后一个**, 因为用户听到的就是
 * 最后一段; 调试面板要的是**全部**, 所以把每个 part 的 metadata 都过一遍。
 *
 * 语气块可能落在 `Text` 上, 也可能落在 `Reasoning` 上(模型把它写进了 `<think>` 里), 所以不去限定 part 类型,
 * 只认 metadata。
 */
internal fun List<UIMessagePart>.collectTtsInstructs(): List<TtsInstructEntry> =
    mapIndexedNotNull { index, part ->
        part.metadataAs<TtsVoiceMetadata>()?.instruct
            // metadata 里存的已经是 trim 过的, 这里只是防御: 手工编辑过的会话不该多出一行空行
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { TtsInstructEntry(partIndex = index, instruct = it) }
    }

/** 还原成模型写出来的原始标签形态, 供复制到别处比对 */
private fun TtsInstructEntry.toRawBlockText(): String =
    "<$TTS_VOICE_TAG>\n$instruct\n</$TTS_VOICE_TAG>"

/**
 * 语音语气块的调试面板: 把 `TtsVoiceControlTransformer` 从正文里剥掉的 `<tts_voice_control>` 原始内容
 * 按需还原出来。
 *
 * 为什么需要它: 语气描述是旁路信息(给本地 TTS 的动态 instruct), 正文里永远看不到, 出问题时既不知道
 * 模型到底写了什么, 也不知道它写在了哪个 part 上。调试模式下点一下正文就能看到原文。
 *
 * 默认收起, 不点开就与改动前的界面完全一致, 所以不需要额外的开发者模式开关
 * (与工具详情页的「原始日志」同一原则)。
 *
 * 这条消息没有语气块时整个组件不渲染任何东西, 点击入口也由调用方按 `entries.isNotEmpty()` 关掉。
 */
@Composable
internal fun TtsInstructDebugPanel(
    entries: List<TtsInstructEntry>,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    // AnimatedVisibility 放在最外层: 收起时不向外发射任何布局节点, 本组件就完全不占位。
    // 若外面再包一层 Column, 那个 0 高度的子项会让父级的 spacedBy(4.dp) 多出一段空隙,
    // 等于「不点开也改变了界面」。
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 展开后条目会随流式生成增长, 用 animateContentSize 收敛高度变化, 避免整页跟着抖
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.message_tts_instruct_debug_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 调试要能把原文复制出来, 给自己留一个可选中的容器
            SelectionContainer {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = TTS_INSTRUCT_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        entries.fastForEach { entry ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                // 只有一段时不显示「第 N 段」: 分段气泡模式下才需要靠它区分是哪一段
                                if (entries.size > 1) {
                                    Text(
                                        // partIndex 从 0 起, 展示成人话要 +1
                                        text = stringResource(
                                            R.string.message_tts_instruct_debug_part,
                                            entry.partIndex + 1,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = entry.toRawBlockText(),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
