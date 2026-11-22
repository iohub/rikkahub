package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.coroutines.delay
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.FavouriteCircle
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.TextSelection
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.WebDesign01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.transformers.ttsVoiceInstruct
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.utils.copyMessageToClipboard
import me.rerere.rikkahub.utils.extractQuotedContentAsText
import me.rerere.rikkahub.utils.removeBracketedContent
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.rikkahub.utils.toMessageTimeString
import me.rerere.ui.components.RikkaConfirmDialog
import java.util.Locale

@Composable
fun ColumnScope.ChatMessageActionButtons(
    message: UIMessage,
    node: MessageNode,
    onUpdate: (MessageNode) -> Unit,
    onRegenerate: () -> Unit,
    onOpenActionSheet: () -> Unit,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    var isPendingDelete by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(isPendingDelete) {
        if (isPendingDelete) {
            delay(3000) // 3秒后自动取消
            isPendingDelete = false
        }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ChatMessageActionButton(
            icon = HugeIcons.Copy01,
            contentDescription = stringResource(R.string.copy),
            onClick = { context.copyMessageToClipboard(message) },
        )

        ChatMessageActionButton(
            icon = HugeIcons.Refresh03,
            contentDescription = stringResource(R.string.regenerate),
            // 任何角色都先弹二次确认，避免误点把这条消息及其下方的消息清掉
            onClick = { showRegenerateConfirm = true },
        )

        if (message.role == MessageRole.ASSISTANT) {
            val tts = LocalTTSState.current
            val isSpeaking by tts.isSpeaking.collectAsState()
            val playbackState by tts.playbackState.collectAsState()
            val isAvailable by tts.isAvailable.collectAsState()
            val fixedAudio = message.fixedAudio
            val messageKey = message.id.toString()
            // isSpeaking 是全局的：正在读别的消息时它也是 true。
            // 再比一次 messageId 才能只点亮「正在播放的那条消息」，而不是把整屏的播放按钮都变成停止。
            val isThisMessageSpeaking = isSpeaking && playbackState.messageId == messageKey
            // 朗读中换成带底色的方角按钮
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                IconToggleButton(
                    checked = isThisMessageSpeaking,
                    onCheckedChange = { speak ->
                        if (speak) {
                            if (fixedAudio != null && fixedAudio.chunkCount > 0) {
                                // 已固化：全程读本地文件，不再请求服务端（这就是「永久固化」的意义）
                                tts.speakFixedAudio(message.id, fixedAudio.chunkCount)
                            } else {
                                val text = message.toText()
                                var textToSpeak = text
                                if (settings.displaySetting.ttsOnlyReadQuoted) {
                                    textToSpeak = textToSpeak.extractQuotedContentAsText() ?: textToSpeak
                                }
                                if (settings.displaySetting.ttsOnlyReadOutsideBrackets) {
                                    textToSpeak = textToSpeak.removeBracketedContent() ?: textToSpeak
                                }
                                // 带上 messageId，播放期间这条消息的按钮才会亮起来；
                                // instruct 沿用这条回复当初的动态语气（`<tts_voice_control>` 块解析出的元数据）：
                                // 缓存键含 instruct，语气一致才能命中同一份音频，否则重播语气会和自动播放时不一样，
                                // 还会多请求一次服务端
                                tts.speak(
                                    textToSpeak,
                                    messageId = messageKey,
                                    instruct = message.ttsVoiceInstruct(),
                                )
                            }
                        } else {
                            tts.stop()
                        }
                    },
                    shapes = IconButtonDefaults.toggleableShapes(),
                    modifier = Modifier.size(IconButtonDefaults.extraSmallContainerSize()),
                    // 固化音频是离线的：没有 provider 也能播，所以不该跟 isAvailable 一起被禁用
                    enabled = isAvailable || fixedAudio != null,
                    colors = IconButtonDefaults.iconToggleButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Icon(
                        imageVector = if (isThisMessageSpeaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
                        contentDescription = stringResource(R.string.tts),
                        modifier = Modifier.size(ActionIconSize),
                    )
                }
            }

            // Translation button
            if (onTranslate != null) {
                ChatMessageActionButton(
                    icon = HugeIcons.Translate,
                    contentDescription = stringResource(R.string.translate),
                    onClick = { showTranslateDialog = true },
                )
            }
        }

        ChatMessageActionButton(
            icon = HugeIcons.MoreVertical,
            contentDescription = stringResource(R.string.more_options),
            onClick = onOpenActionSheet,
        )

        ChatMessageBranchSelector(
            node = node,
            onUpdate = onUpdate,
        )

        if (settings.displaySetting.showDateTimeInMessage) {
            Text(
                text = message.createdAt.toJavaLocalDateTime().toMessageTimeString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    // Translation dialog
    if (showTranslateDialog && onTranslate != null) {
        LanguageSelectionDialog(
            onLanguageSelected = { language ->
                showTranslateDialog = false
                onTranslate(message, language)
            },
            onClearTranslation = {
                showTranslateDialog = false
                onClearTranslation(message)
            },
            onDismissRequest = {
                showTranslateDialog = false
            },
        )
    }

    // Regenerate confirmation dialog
    RikkaConfirmDialog(
        show = showRegenerateConfirm,
        title = stringResource(R.string.regenerate),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showRegenerateConfirm = false
            onRegenerate()
        },
        onDismiss = { showRegenerateConfirm = false },
        text = { Text(stringResource(R.string.regenerate_confirm_message)) }
    )
}

private val ActionIconSize = 18.dp

// 消息下方的小号图标按钮，按下时圆形收成方角
@Composable
internal fun ChatMessageActionButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // 按钮排得密，不让 48dp 的最小触控尺寸把间距撑开
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        IconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
            modifier = modifier.size(IconButtonDefaults.extraSmallContainerSize()),
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(ActionIconSize),
            )
        }
    }
}

private class MessageSheetAction(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun ChatMessageActionsSheet(
    message: UIMessage,
    model: Model?,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onFork: () -> Unit,
    onSelectAndCopy: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    // 语音固化三项动作：都是可空回调，未接线的调用点（如预览）不会显示对应菜单项
    onFixateAudio: (() -> Unit)? = null,
    onRegenerateAudio: (() -> Unit)? = null,
    onUnfixateAudio: (() -> Unit)? = null,
    onWebViewPreview: () -> Unit,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        // WebView Preview (only show if message has text content)
        val hasTextContent = message.parts.filterIsInstance<UIMessagePart.Text>()
            .any { it.text.isNotBlank() }
        val fixedAudio = message.fixedAudio
        val actions = buildList {
            add(MessageSheetAction(HugeIcons.TextSelection, stringResource(R.string.select_and_copy), onClick = onSelectAndCopy))
            if (hasTextContent) {
                add(MessageSheetAction(HugeIcons.WebDesign01, stringResource(R.string.render_with_webview), onClick = onWebViewPreview))
            }
            add(MessageSheetAction(HugeIcons.Edit01, stringResource(R.string.edit), onClick = onEdit))
            add(MessageSheetAction(HugeIcons.Share04, stringResource(R.string.share), onClick = onShare))
            add(MessageSheetAction(HugeIcons.GitFork, stringResource(R.string.create_fork), onClick = onFork))
            if (onToggleFavorite != null) {
                add(
                    MessageSheetAction(
                        icon = HugeIcons.FavouriteCircle,
                        label = stringResource(
                            if (isFavorite) R.string.chat_message_remove_favorite
                            else R.string.chat_message_add_favorite
                        ),
                        onClick = onToggleFavorite,
                    )
                )
            }
            // 语音固化：只对助手消息开放，和行内的播放按钮保持一致（否则固化了却听不到）
            if (message.role == MessageRole.ASSISTANT && hasTextContent) {
                if (fixedAudio == null) {
                    if (onFixateAudio != null) {
                        add(
                            MessageSheetAction(
                                icon = HugeIcons.VolumeHigh,
                                label = stringResource(R.string.chat_message_fixate_audio),
                                onClick = onFixateAudio,
                            )
                        )
                    }
                } else {
                    if (onRegenerateAudio != null) {
                        add(
                            MessageSheetAction(
                                icon = HugeIcons.Refresh03,
                                label = stringResource(R.string.chat_message_regenerate_audio),
                                onClick = onRegenerateAudio,
                            )
                        )
                    }
                    if (onUnfixateAudio != null) {
                        add(
                            MessageSheetAction(
                                icon = HugeIcons.Delete01,
                                label = stringResource(R.string.chat_message_unfixate_audio),
                                destructive = true,
                                onClick = onUnfixateAudio,
                            )
                        )
                    }
                }
            }
            add(MessageSheetAction(HugeIcons.Delete01, stringResource(R.string.delete), destructive = true, onClick = onDelete))
        }

        // 已固化时在菜单顶部给一行次要信息：段数 + 占用 + 合成时的音色，方便判断要不要重新生成
        val fixedAudioSummary = fixedAudio?.let { fixed ->
            val sizeText = String.format(Locale.US, "%.1f MB", fixed.sizeBytes / 1024f / 1024f)
            val summary = stringResource(
                R.string.chat_message_fixed_audio_summary,
                fixed.chunkCount,
                sizeText,
            )
            if (fixed.voice.isNotBlank()) {
                summary + " · " + stringResource(R.string.chat_message_fixed_audio_voice, fixed.voice)
            } else {
                summary
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (fixedAudioSummary != null) {
                Text(
                    text = fixedAudioSummary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                actions.fastForEachIndexed { index, action ->
                    SegmentedListItem(
                        onClick = {
                            onDismissRequest()
                            action.onClick()
                        },
                        shapes = ListItemDefaults.segmentedShapes(index = index, count = actions.size),
                        colors = if (action.destructive) {
                            ListItemDefaults.segmentedColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                leadingContentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else {
                            ListItemDefaults.segmentedColors()
                        },
                        leadingContent = {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                            )
                        },
                    ) {
                        Text(action.label)
                    }
                }
            }

            // Message Info
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    Text(message.createdAt.toJavaLocalDateTime().toLocalString())
                    if (model != null) {
                        Text(model.displayName)
                    }
                }
            }
        }
    }
}
