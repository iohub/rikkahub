package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.rikkahub.ui.components.richtext.CodeHighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.ChartLineData01
import me.rerere.hugeicons.stroke.ChartScatter
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.CalendarAdd01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.MemoryToolNames
import me.rerere.rikkahub.data.ai.tools.local.TEXT_TO_SPEECH_TOOL_NAME
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.tts.model.mergedInstructionsFor
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.ui.components.FormItem
import org.koin.compose.koinInject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 记忆库工具: 标题按读/写/删区分, 摘要显示操作的文件路径, 点路径跳到该文件的详情页
 */
class MemoryFileToolUI(override val toolName: String) : ToolUIRenderer {
    override fun icon(context: ToolUIContext): ImageVector = when (toolName) {
        MemoryToolNames.READ, MemoryToolNames.LIST -> HugeIcons.Book02
        MemoryToolNames.DELETE -> HugeIcons.Eraser
        else -> HugeIcons.QuillWrite01
    }

    @Composable
    override fun title(context: ToolUIContext): String =
        memoryToolTitle(toolName, context.arguments)
            ?: stringResource(R.string.chat_message_tool_call_generic, toolName)

    // memory_read 的 path 是数组, 其余工具是单个路径
    private fun paths(context: ToolUIContext): List<String> =
        when (val path = context.arguments.jsonObjectOrNull?.get("path")) {
            is JsonArray -> path.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
            else -> listOfNotNull(path?.jsonPrimitiveOrNull?.contentOrNull)
        }

    override fun hasSummary(context: ToolUIContext): Boolean = paths(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val navController = LocalNavController.current
        val paths = paths(context)
        // 参数还在生成时路径可能不完整; 删掉的文件没有详情可看
        val memoryId = context.assistant?.memoryId
            ?.takeIf { !context.loading && toolName != MemoryToolNames.DELETE }
        Column {
            paths.take(MAX_SUMMARY_PATHS).fastForEach { path ->
                Row(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable(enabled = memoryId != null) {
                            memoryId?.let { navController.navigate(Screen.MemoryFile(it, path)) }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .shimmer(isLoading = context.loading),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (memoryId != null) {
                        Icon(
                            imageVector = HugeIcons.ArrowRight01,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }
            if (paths.size > MAX_SUMMARY_PATHS) {
                Text(
                    text = "+${paths.size - MAX_SUMMARY_PATHS}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }

    companion object {
        private const val MAX_SUMMARY_PATHS = 5

        val all = listOf(
            MemoryToolNames.READ,
            MemoryToolNames.WRITE,
            MemoryToolNames.STR_REPLACE,
            MemoryToolNames.APPEND,
            MemoryToolNames.DELETE,
            MemoryToolNames.LIST,
        ).map(::MemoryFileToolUI)
    }
}

/** 记忆库工具的标题, 聊天消息与导出图片共用; 不是记忆库工具时返回 null */
@Composable
internal fun memoryToolTitle(toolName: String, arguments: JsonElement?): String? = when (toolName) {
    MemoryToolNames.READ -> stringResource(R.string.chat_message_tool_read_memory)
    MemoryToolNames.LIST -> stringResource(R.string.chat_message_tool_list_memory)
    MemoryToolNames.WRITE -> if (arguments.getStringContent("if_version") == MemoryRepository.NEW_FILE_VERSION) {
        stringResource(R.string.chat_message_tool_create_memory)
    } else {
        stringResource(R.string.chat_message_tool_edit_memory)
    }

    MemoryToolNames.STR_REPLACE, MemoryToolNames.APPEND -> stringResource(R.string.chat_message_tool_edit_memory)
    MemoryToolNames.DELETE -> stringResource(R.string.chat_message_tool_delete_memory)
    else -> null
}

/**
 * 网络搜索: 标题带查询词, 摘要显示 answer 与结果数, 详情为结果列表
 */
object SearchWebToolUI : ToolUIRenderer {
    override val toolName: String = "search_web"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(
        R.string.chat_message_tool_search_web,
        context.arguments.getStringContent("query") ?: ""
    )

    private fun items(context: ToolUIContext): List<JsonElement> =
        context.content?.jsonObjectOrNull?.get("items")?.jsonArray ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.content.getStringContent("answer") != null || items(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        context.content.getStringContent("answer")?.let { answer ->
            Text(
                text = answer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.shimmer(isLoading = context.loading),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val items = items(context)
        if (items.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FaviconRow(
                    urls = items.mapNotNull { it.getStringContent("url") },
                    size = 18.dp,
                )
                Text(
                    text = stringResource(R.string.chat_message_tool_search_results_count, items.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        SearchWebPreview(arguments = context.arguments, content = content)
    }
}

/**
 * 网页抓取: 摘要显示 URL, 详情为各网页的 Markdown 内容
 */
object ScrapeWebToolUI : ToolUIRenderer {
    override val toolName: String = "scrape_web"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.GlobalSearch

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_scrape_web)

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.arguments.getStringContent("url") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        Text(
            text = context.arguments.getStringContent("url") ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val content = context.content
        if (content == null) {
            DefaultToolPreview(context = context)
            return
        }
        ScrapeWebPreview(content = content)
    }
}

/**
 * 获取时间信息
 */
object GetTimeInfoToolUI : ToolUIRenderer {
    override val toolName: String = "get_time_info"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Time02

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_get_time)
}

/**
 * 剪贴板: 按 action 区分读/写标题
 */
object ClipboardToolUI : ToolUIRenderer {
    private const val ACTION_READ = "read"
    private const val ACTION_WRITE = "write"

    override val toolName: String = "clipboard_tool"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Clipboard

    @Composable
    override fun title(context: ToolUIContext): String =
        when (context.arguments.getStringContent("action")) {
            ACTION_READ -> stringResource(R.string.chat_message_tool_clipboard_read)
            ACTION_WRITE -> stringResource(R.string.chat_message_tool_clipboard_write)
            else -> stringResource(R.string.chat_message_tool_call_generic, toolName)
        }
}

/**
 * 「原始日志」里 TTS 特有的补充字段。
 *
 * 三个 instruct 值必须同屏才解释得清「这一轮为什么是这个语气」：
 * - `staticInstructions`：设置页里配置的静态语气，对整个 provider 生效；
 * - `dynamicInstruct`：模型这次通过工具参数给的动态语气，只对这一条回复生效；
 * - `mergedInstructions`：两者按 provider 规则合并后**真正下发**的值（见 `mergedInstructionsFor`），
 *   为 JSON null 表示该 provider 不消费 instruct（如 OpenAI / 系统 TTS），
 *   为空串表示消费但这次没有内容可下发（请求体里连键都不写）。
 *
 * @param provider 当前选中的 TTS provider，为 null 时说明一个都没配置
 */
internal fun buildTtsRawLogExtra(
    provider: TTSProviderSetting?,
    dynamicInstruct: String,
): JsonObject = buildJsonObject {
    if (provider == null) {
        // 没配置 provider 时请求根本发不出去，用 none 明确区分「没配」与「配了但不支持 instruct」
        put("provider", "none")
        put("staticInstructions", "")
        put("dynamicInstruct", dynamicInstruct)
        put("mergedInstructions", JsonNull)
        return@buildJsonObject
    }
    put("provider", provider.javaClass.simpleName)
    put("staticInstructions", provider.staticTtsInstructions())
    put("dynamicInstruct", dynamicInstruct)
    val merged = provider.mergedInstructionsFor(dynamicInstruct)
    if (merged == null) put("mergedInstructions", JsonNull) else put("mergedInstructions", merged)
}

/** 设置页里配置的静态语气；不消费 instruct 的 provider 返回空串 */
private fun TTSProviderSetting.staticTtsInstructions(): String = when (this) {
    is TTSProviderSetting.RemoteTTS -> instructions
    is TTSProviderSetting.Step -> instruction
    else -> ""
}

/** 取当前设置里的 TTS provider 与本轮的动态语气，拼成「原始日志」的补充字段 */
@Composable
private fun rememberTtsRawLogExtra(context: ToolUIContext): JsonObject {
    val settings = LocalSettings.current
    val provider = settings.getSelectedTTSProvider()
    val dynamicInstruct = context.arguments.getStringContent("instruct").orEmpty()
    return remember(provider, dynamicInstruct) {
        buildTtsRawLogExtra(provider, dynamicInstruct)
    }
}

/**
 * 文本转语音: 摘要显示朗读文本与重播按钮
 */
object TextToSpeechToolUI : ToolUIRenderer {
    override val toolName: String = TEXT_TO_SPEECH_TOOL_NAME

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.VolumeHigh

    @Composable
    override fun title(context: ToolUIContext): String {
        val preview = context.arguments.getStringContent("text")?.let { text ->
            if (text.length > 24) text.take(24) + "…" else text
        } ?: ""
        return stringResource(R.string.tool_ui_speaking, preview)
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.arguments.getStringContent("text") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val eventBus: AppEventBus = koinInject()
        val scope = rememberCoroutineScope()
        val text = context.arguments.getStringContent("text") ?: ""
        // 模型为本轮生成的动态语气（情绪/语速/场景）；旧消息没有这个参数，留空则整行不渲染
        val instruct = context.arguments.getStringContent("instruct").orEmpty()
        // 摘要行的显式日志入口：点标题行也能开详情，但没人猜得到，这里给一个明说的按钮
        var showRawLog by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(
                    // 重播沿用同一条动态语气：否则听到的和当初那次不一样，也命中不了按语气区分的缓存
                    onClick = { scope.launch { eventBus.emit(AppEvent.Speak(text, instruct)) } },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Refresh01,
                        contentDescription = stringResource(R.string.tool_ui_replay),
                        modifier = Modifier.size(14.dp),
                    )
                }
                FilledTonalIconButton(
                    // 显式入口，点了直接弹原始日志面板，不依赖「点标题行」这个隐藏交互
                    onClick = { showRawLog = true },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Code,
                        contentDescription = stringResource(R.string.tool_raw_log_toggle),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (instruct.isNotBlank()) {
                // 前缀本地化标签: 否则中文用户不知道这串英文描述词是"语气"
                Text(
                    text = stringResource(R.string.tool_ui_tts_instruct_label) + " · " +
                        (if (instruct.length > 40) instruct.take(40) + "…" else instruct),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // 独立日志面板: 摘要行点按钮直接弹出, 不再依赖「点标题行 → 详情 → 展开原始日志」三步
        if (showRawLog) {
            ModalBottomSheet(onDismissRequest = { showRawLog = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight(0.8f)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ToolRawLogSection(
                        context = context,
                        extra = rememberTtsRawLogExtra(context),
                        initiallyExpanded = true,
                    )
                }
            }
        }
    }

    /**
     * 详情页: 只展示朗读文本与语气, 不把工具入参/输出的原始 JSON 暴露给用户
     */
    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val eventBus: AppEventBus = koinInject()
        val scope = rememberCoroutineScope()
        val text = context.arguments.getStringContent("text").orEmpty()
        // 旧消息没有 instruct 参数, 留空时提示"未指定"而不是留白
        val instruct = context.arguments.getStringContent("instruct").orEmpty()
        val displayText = if (text.isBlank()) {
            stringResource(R.string.tool_ui_tts_text_empty)
        } else {
            text
        }

        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.tool_ui_tts_preview_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            FormItem(
                label = { Text(stringResource(R.string.tool_ui_tts_text_label)) },
            ) {
                SelectionContainer {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (text.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            FormItem(
                label = { Text(stringResource(R.string.tool_ui_tts_instruct_label)) },
            ) {
                Text(
                    text = if (instruct.isBlank()) {
                        stringResource(R.string.tool_ui_tts_instruct_empty)
                    } else {
                        instruct
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (instruct.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    // 重播沿用同一条动态语气, 与 Summary 内的按钮行为保持一致
                    onClick = {
                        scope.launch { eventBus.emit(AppEvent.Speak(text, instruct)) }
                        onDismissRequest()
                    },
                    enabled = text.isNotBlank(),
                ) {
                    Icon(
                        imageVector = HugeIcons.Refresh01,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.tool_ui_replay))
                }
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(R.string.tool_ui_tts_close))
                }
            }
            // 调试逃生口：默认收起，想查原始入参/输出与三段语气时才展开
            ToolRawLogSection(
                context = context,
                extra = rememberTtsRawLogExtra(context),
            )
        }
    }
}

/**
 * 技能调用: 标题显示技能名与路径
 */
object UseSkillToolUI : ToolUIRenderer {
    override val toolName: String = "use_skill"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.MagicWand01

    @Composable
    override fun title(context: ToolUIContext): String {
        val skillName = context.arguments.getStringContent("name") ?: ""
        val path = context.arguments.getStringContent("path")
        return if (path != null) "Skill: $skillName / $path" else "Skill: $skillName"
    }
}

/**
 * 最近聊天: 标题固定, 摘要列出最近对话的标题
 */
object RecentChatsToolUI : ToolUIRenderer {
    override val toolName: String = "recent_chats"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Message02

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_recent_chats)

    private fun chats(context: ToolUIContext): List<JsonElement> =
        (context.content as? JsonArray) ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean = chats(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val titles = chats(context).mapNotNull { it.getStringContent("title") }
        if (titles.isEmpty()) return
        Text(
            text = titles.joinToString(", "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.shimmer(isLoading = context.loading),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 对话历史搜索: 标题带查询词, 摘要显示命中数
 */
object ConversationSearchToolUI : ToolUIRenderer {
    override val toolName: String = "conversation_search"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(
        R.string.chat_message_tool_conversation_search,
        context.arguments.getStringContent("query") ?: ""
    )

    private fun results(context: ToolUIContext): List<JsonElement> =
        (context.content as? JsonArray) ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean = results(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val results = results(context)
        if (results.isEmpty()) return
        Text(
            text = stringResource(R.string.chat_message_tool_search_results_count, results.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
        )
    }
}

/**
 * 屏幕使用时间: 摘要显示总时长与用时最多的应用, 详情为按时长排序的应用列表 (带占比条);
 * 无权限时回退到默认 JSON 详情
 */
object GetScreenTimeToolUI : ToolUIRenderer {
    private const val SUMMARY_MAX_APPS = 3

    override val toolName: String = "get_screen_time"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.SmartPhone01

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_screen_time)

    private fun apps(context: ToolUIContext): List<JsonElement> =
        context.content?.jsonObjectOrNull?.get("apps")?.let { it as? JsonArray } ?: emptyList()

    private fun isNoPermission(context: ToolUIContext): Boolean =
        context.content.getStringContent("error") == "NO_PERMISSION"

    override fun hasSummary(context: ToolUIContext): Boolean =
        isNoPermission(context) || apps(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        if (isNoPermission(context)) {
            Text(
                text = stringResource(R.string.assistant_page_local_tools_screen_time_permission_required),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            return
        }
        val apps = apps(context)
        if (apps.isEmpty()) return
        val totalMinutes = context.content?.jsonObjectOrNull?.get("total_minutes")
            ?.jsonPrimitiveOrNull?.longOrNull ?: 0
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.shimmer(isLoading = context.loading),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.tool_ui_screen_time_total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatMinutes(totalMinutes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            apps.take(SUMMARY_MAX_APPS).forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = app.getStringContent("app_name")
                            ?: app.getStringContent("package") ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatMinutes(app.appMinutes()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val apps = apps(context)
        if (apps.isEmpty()) {
            DefaultToolPreview(context = context)
            return
        }
        ScreenTimePreview(content = context.content!!, apps = apps)
    }
}

object CalendarQueryToolUI : ToolUIRenderer {
    override val toolName: String = "calendar_query"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Calendar03

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.chat_message_tool_calendar_query)

    private fun events(context: ToolUIContext): List<JsonElement> =
        context.content?.jsonObjectOrNull?.get("events")?.let { it as? JsonArray } ?: emptyList()

    override fun hasSummary(context: ToolUIContext): Boolean = events(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val events = events(context)
        if (events.isEmpty()) return
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.shimmer(isLoading = context.loading),
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_search_results_count, events.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            events.take(3).forEach { event ->
                val title = event.getStringContent("title") ?: return@forEach
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

object CalendarCreateToolUI : ToolUIRenderer {
    override val toolName: String = "calendar_create"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.CalendarAdd01

    @Composable
    override fun title(context: ToolUIContext): String {
        val eventTitle = context.arguments.getStringContent("title") ?: ""
        return stringResource(R.string.chat_message_tool_calendar_create, eventTitle)
    }
}

object ChartDisplayToolUI : ToolUIRenderer {
    override val toolName: String = "chart_display"

    override fun icon(context: ToolUIContext): ImageVector =
        when (context.arguments.getStringContent("style")) {
            "bar" -> HugeIcons.ChartColumn
            "scatter" -> HugeIcons.ChartScatter
            else -> HugeIcons.ChartLineData01
        }

    @Composable
    override fun title(context: ToolUIContext): String {
        val chartTitle = context.arguments.getStringContent("title")
        return if (chartTitle.isNullOrBlank()) {
            stringResource(R.string.chat_message_tool_chart_display)
        } else {
            stringResource(R.string.chat_message_tool_chart_display_with_title, chartTitle)
        }
    }
}

@Composable
private fun ScreenTimePreview(content: JsonElement, apps: List<JsonElement>) {
    val totalMinutes = content.jsonObjectOrNull?.get("total_minutes")
        ?.jsonPrimitiveOrNull?.longOrNull ?: 0
    val maxAppMs = apps.maxOfOrNull { it.appMs() }?.takeIf { it > 0 } ?: 1L
    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.tool_ui_screen_time_total),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatMinutes(totalMinutes),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                val begin = content.getStringContent("start")
                val finish = content.getStringContent("end")
                if (begin != null && finish != null) {
                    Text(
                        text = "${formatRangeTime(begin)} → ${formatRangeTime(finish)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
        items(apps) { app ->
            val name = app.getStringContent("app_name")
                ?: app.getStringContent("package") ?: return@items
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatMinutes(app.appMinutes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                LinearProgressIndicator(
                    progress = { (app.appMs().toFloat() / maxAppMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 读取单个应用条目的前台时长 (毫秒) */
private fun JsonElement.appMs(): Long =
    jsonObjectOrNull?.get("total_ms")?.jsonPrimitiveOrNull?.longOrNull ?: 0

/** 读取单个应用条目的前台时长 (分钟) */
private fun JsonElement.appMinutes(): Long =
    jsonObjectOrNull?.get("total_minutes")?.jsonPrimitiveOrNull?.longOrNull ?: (appMs() / 60000)

private val SCREEN_TIME_RANGE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 将工具返回的 ISO 时间字符串格式化为 "MM-dd HH:mm", 解析失败时原样返回.
 *
 * 工具用 ZonedDateTime.toString() 输出, 区域 ID 时会带 "[Asia/Shanghai]" 后缀,
 * 故优先用 ZonedDateTime.parse, 再回退到 offset / 本地日期时间.
 */
private fun formatRangeTime(iso: String): String = runCatching {
    ZonedDateTime.parse(iso).format(SCREEN_TIME_RANGE_FORMATTER)
}.recoverCatching {
    OffsetDateTime.parse(iso).format(SCREEN_TIME_RANGE_FORMATTER)
}.recoverCatching {
    LocalDateTime.parse(iso).format(SCREEN_TIME_RANGE_FORMATTER)
}.getOrDefault(iso)

/** 将分钟数格式化为 "Xh Ym" / "Xh" / "Ym" */
private fun formatMinutes(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

@Composable
private fun SearchWebPreview(
    arguments: JsonElement,
    content: JsonElement,
) {
    val context = LocalContext.current
    val items = content.jsonObject["items"]?.jsonArray ?: emptyList()
    val answer = content.getStringContent("answer")
    val query = arguments.getStringContent("query") ?: ""
    val parameters = arguments.jsonObjectOrNull?.entries?.filter { it.key != "query" }.orEmpty()
    val images = content.jsonObject["images"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(stringResource(R.string.chat_message_tool_search_prefix, query))
        }

        if (parameters.isNotEmpty()) {
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    parameters.forEach { (name, value) ->
                        val displayValue = when (value) {
                            is JsonArray -> value.joinToString(", ") {
                                it.jsonPrimitiveOrNull?.contentOrNull ?: it.toString()
                            }
                            else -> value.jsonPrimitiveOrNull?.contentOrNull ?: value.toString()
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Text(
                                text = "$name: $displayValue",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }

        if (answer != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    MarkdownBlock(
                        content = answer,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (images.isNotEmpty()) {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(images) { imageUrl ->
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .height(120.dp)
                                .width(160.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { context.openUrl(imageUrl) },
                        )
                    }
                }
            }
        }

        if (items.isNotEmpty()) {
            items(items) { item ->
                val url = item.getStringContent("url") ?: return@items
                val title = item.getStringContent("title") ?: return@items
                val text = item.getStringContent("text") ?: return@items
                val publishedDate = item.getStringContent("publishedDate")?.takeIf { it.isNotBlank() }
                val dateLabel = remember(publishedDate) {
                    publishedDate?.let { value ->
                        runCatching {
                            LocalDate.parse(value, DateTimeFormatter.ISO_DATE_TIME)
                        }.recoverCatching {
                            LocalDate.parse(value, DateTimeFormatter.ISO_DATE)
                        }.getOrNull()?.toLocalString(includeYear = true) ?: value
                    }
                }

                Card(
                    onClick = { context.openUrl(url) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Favicon(
                            url = url,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(text = title, maxLines = 1)
                            if (dateLabel != null) {
                                Text(
                                    text = dateLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = url,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        } else {
            item {
                CodeHighlightText(
                    code = JsonInstantPretty.encodeToString(content),
                    language = "json",
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ScrapeWebPreview(content: JsonElement) {
    val urls = content.jsonObject["urls"]?.jsonArray ?: emptyList()

    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = stringResource(
                    R.string.chat_message_tool_scrape_prefix,
                    urls.joinToString(", ") { it.getStringContent("url") ?: "" }
                )
            )
        }

        items(urls) { url ->
            val urlObject = url.jsonObject
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = urlObject["url"]?.jsonPrimitive?.content ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxWidth()
                )
                Card {
                    MarkdownBlock(
                        content = urlObject["content"]?.jsonPrimitive?.content ?: "",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}
