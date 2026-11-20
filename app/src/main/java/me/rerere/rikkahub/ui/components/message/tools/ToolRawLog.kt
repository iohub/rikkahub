package me.rerere.rikkahub.ui.components.message.tools

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.JsonTree
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.writeClipboardText

/** 原始日志展开后的最大高度: 再长就在区块内部滚动, 别把详情页整段顶下去 */
private val RAW_LOG_MAX_HEIGHT = 420.dp

/**
 * 工具调用的「原始日志」区块, 调试「这次调用到底发了什么/回来了什么」时用的逃生口。
 *
 * 与 [DefaultToolPreview] 的区别:
 * - [DefaultToolPreview] 展示的是**解析后**的入参与输出, 解析失败时退回原文, 图片还会渲染成缩略图;
 * - 这里展示的是**原样字符串**: `inputRaw` 是模型给出的原始参数文本(半截 JSON、空串、非 JSON 都如实呈现),
 *   `outputRaw` 是输出文本部件的原串数组, 不美化不改写。排查 provider 协议问题、看模型到底写了什么时只能看这个。
 *
 * 默认收起, 不点开就与改动前的界面完全一致, 所以不需要额外的开发者模式开关。
 *
 * 本组件对所有工具通用: 任何一个 [ToolUIRenderer] 只要在 `Preview` 尾部加一行
 * `ToolRawLogSection(context = context)` 就能获得这个入口; 工具特有的补充信息用 [extra] 并入顶层即可。
 *
 * @param extra 额外并入日志顶层的键值, 为 null 时不加任何键
 * @param initiallyExpanded 用于「从摘要行直接弹出的独立日志面板」: 打开即展开, 且不渲染那条
 * 分割线与折叠入口行(此时它本身就是面板内容, 再给一个「查看原始日志」的入口只会自相矛盾);
 * 默认 false, 保持详情页底部「一行入口 + 点开才展开」的形态
 */
@Composable
fun ToolRawLogSection(
    context: ToolUIContext,
    modifier: Modifier = Modifier,
    extra: JsonObject? = null,
    initiallyExpanded: Boolean = false,
) {
    // 用 initiallyExpanded 作 key: 同一位置从折叠形态切成独立面板时, 状态要跟着初始值重置
    var expanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    // tool 是 data class, extra 由调用方 remember, 两者都没变时不必重新拼 JSON
    val rawLog = remember(context.tool, extra) { buildToolRawLogJson(context.tool, extra) }
    val localContext = LocalContext.current
    val copiedHint = stringResource(R.string.tool_raw_log_copied)

    Column(modifier = modifier.fillMaxWidth()) {
        // 独立面板形态下日志已经摊开, 折叠入口本身没有意义, 干脆不渲染
        if (!initiallyExpanded) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.tool_raw_log_toggle),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.tool_raw_log_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(
                        onClick = {
                            // 复制的是完整 pretty JSON: 粘到 issue 里比一堆片段有用
                            localContext.writeClipboardText(JsonInstantPretty.encodeToString(rawLog))
                            Toast.makeText(localContext, copiedHint, Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = HugeIcons.Copy01,
                            contentDescription = stringResource(R.string.tool_raw_log_copy),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = RAW_LOG_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                ) {
                    JsonTree(json = rawLog, initialExpandLevel = 2)
                }
            }
        }
    }
}

/**
 * 把一个工具调用摊平成可折叠展示的 JSON 日志。
 *
 * 只做字段搬运, 不解析 [UIMessagePart.Tool.input] / `output` 的文本内容: 一旦内容不是合法 JSON
 * (流式半截、工具直接回纯文本), 解析路径会丢掉信息, 而调试恰恰要看这些坏数据。
 *
 * 键顺序固定, 便于肉眼比对两次调用、也便于单测断言:
 * `toolName` / `toolCallId` / `approvalState` / `status` / `startedAt` / `finishedAt` / `durationMs` /
 * `inputRaw` / `outputRaw` / `metadata` / (extra 的键)。
 *
 * @param extra 额外并入顶层的键值, 同名的键会被它覆盖
 */
internal fun buildToolRawLogJson(
    tool: UIMessagePart.Tool,
    extra: JsonObject? = null,
): JsonObject = buildJsonObject {
    put("toolName", tool.toolName)
    put("toolCallId", tool.toolCallId)
    put("approvalState", tool.approvalState.toRawLogLabel())
    put(
        "status",
        when {
            tool.isExecuted -> "executed"
            // 未执行但有输出之外的可能: 等用户批准
            tool.isPending -> "pending"
            else -> "not_executed"
        }
    )

    val startedAt = tool.startedAt
    val finishedAt = tool.finishedAt
    // 计时只在真正跑过工具时才有值, 拒绝/问答/旧消息为空, 此时连键都不写, 避免出现 0ms 的假象
    if (startedAt != null) put("startedAt", startedAt.toString())
    if (finishedAt != null) put("finishedAt", finishedAt.toString())
    if (startedAt != null && finishedAt != null) {
        put("durationMs", (finishedAt - startedAt).inWholeMilliseconds)
    }

    put("inputRaw", tool.input)
    put(
        "outputRaw",
        buildJsonArray {
            tool.output.forEach { part ->
                // 只收文本部件: 图片/文档是 URL, 在日志里没有诊断价值
                if (part is UIMessagePart.Text) add(part.text)
            }
        }
    )
    tool.metadata?.let { metadata -> put("metadata", metadata) }

    // extra 放最后: 工具特有的字段紧跟在通用字段之后, 同名字段以 extra 为准(调用方最清楚该覆盖什么)
    extra?.forEach { (key, value) -> put(key, value) }
}

/** [ToolApprovalState] 的纯文本形式, 拒绝/问答带上模型看到的原因/答案, 便于解释「为什么没执行」 */
private fun ToolApprovalState.toRawLogLabel(): String = when (this) {
    ToolApprovalState.Auto -> "auto"
    ToolApprovalState.Pending -> "pending"
    ToolApprovalState.Approved -> "approved"
    is ToolApprovalState.Denied -> "denied: $reason".trimEnd()
    is ToolApprovalState.Answered -> "answered: $answer".trimEnd()
}
