package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.ui.components.Tooltip
import kotlin.uuid.Uuid

/**
 * 多选之后要做什么。
 *
 * 同一个选择手势服务两件事，靠这个模式区分确认按钮的文案/图标和后续动作：
 * - [Export]：导出选中的消息（原有行为）
 * - [FixateAudio]：为一组消息固化语音
 */
enum class ChatSelectionMode { Export, FixateAudio }

/**
 * 消息多选的状态。放在外面是为了让顶栏菜单也能直接进入多选。
 */
@Stable
class ChatSelectionState {
    var selecting by mutableStateOf(false)
    var mode by mutableStateOf(ChatSelectionMode.Export)
    val selectedItems = mutableStateListOf<Uuid>()

    fun start(ids: List<Uuid>, mode: ChatSelectionMode = ChatSelectionMode.Export) {
        this.mode = mode
        selectedItems.clear()
        selectedItems.addAll(ids)
        selecting = true
    }

    fun cancel() {
        selecting = false
        mode = ChatSelectionMode.Export
        selectedItems.clear()
    }

    fun isAllSelected(ids: List<Uuid>): Boolean =
        selectedItems.isNotEmpty() && ids.all { it in selectedItems }

    // 全部选中时清空，否则补齐成全选
    fun toggleAll(ids: List<Uuid>) {
        val allSelected = isAllSelected(ids)
        selectedItems.clear()
        if (!allSelected) {
            selectedItems.addAll(ids)
        }
    }
}

@Composable
fun rememberChatSelectionState(): ChatSelectionState = remember { ChatSelectionState() }

/**
 * 多选时浮在底部的工具栏：取消、已选数量、全选/取消全选、确认。
 *
 * 确认按钮的图标与提示随 [mode] 变化，这样同一个工具栏既能用于导出，也能用于批量固化语音。
 */
@Composable
internal fun ChatSelectionToolbar(
    state: ChatSelectionState,
    allIds: List<Uuid>,
    mode: ChatSelectionMode,
    selectedCount: Int,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.selecting,
        modifier = modifier,
        enter = slideInVertically(
            initialOffsetY = { it * 2 },
        ),
        exit = slideOutVertically(
            targetOffsetY = { it * 2 },
        ),
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
        ) {
            Tooltip(
                tooltip = {
                    Text(stringResource(R.string.chat_page_clear_selection))
                }
            ) {
                IconButton(onClick = { state.cancel() }) {
                    Icon(HugeIcons.Cancel01, null)
                }
            }
            // 选中数量：只看勾选框数不清选了几条，直接写出来
            Text(
                text = stringResource(R.string.chat_selection_selected_count, selectedCount),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            // 图标看不出是全选，直接写文字；全部选中时变成取消全选
            TextButton(onClick = { state.toggleAll(allIds) }) {
                Text(
                    stringResource(
                        if (state.isAllSelected(allIds)) R.string.chat_page_deselect_all
                        else R.string.chat_page_select_all
                    )
                )
            }
            Tooltip(
                tooltip = {
                    Text(
                        stringResource(
                            if (mode == ChatSelectionMode.FixateAudio) R.string.chat_message_fixate_audio
                            else R.string.confirm
                        )
                    )
                }
            ) {
                FilledIconButton(onClick = onConfirm) {
                    Icon(
                        imageVector = if (mode == ChatSelectionMode.FixateAudio) HugeIcons.VolumeHigh
                        else HugeIcons.Tick01,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}
