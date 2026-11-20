package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.FixedAudioInfo
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.transformers.ttsVoiceInstruct
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import kotlin.uuid.Uuid

/**
 * 批量固化语音的进度状态。
 *
 * 只保存「进度条要显示的东西」，协程本身由页面持有——这样旋转/离开页面时协程被取消，
 * 状态对象跟着一起消失，不会出现一个还在跑却没人看的后台任务。
 */
@Stable
class BatchFixateAudioState {
    /** 是否有批量任务在跑（决定进度对话框是否显示） */
    var running by mutableStateOf(false)

    /** 需要合成的消息总数（不含跳过的） */
    var total by mutableStateOf(0)

    /** 已经处理完的消息数（成功 + 失败） */
    var completed by mutableStateOf(0)

    /** 合成失败的消息数 */
    var failed by mutableStateOf(0)

    /** 直接跳过的消息数（已固化 / 无文本） */
    var skipped by mutableStateOf(0)

    /** 当前正在合成的消息文本，用于进度对话框里的预览 */
    var previewText by mutableStateOf("")

    /**
     * 用户点了取消。
     *
     * 采用协作式取消：当前这一条会做完再跳出循环，已完成的条目照常落库，
     * 避免为了立刻中断而丢掉已经花掉的合成结果。
     */
    var cancelRequested by mutableStateOf(false)

    fun start(total: Int, skipped: Int) {
        running = true
        this.total = total
        this.skipped = skipped
        completed = 0
        failed = 0
        previewText = ""
        cancelRequested = false
    }

    fun finish() {
        running = false
        previewText = ""
    }
}

/**
 * 逐条固化 [targets] 的语音，返回成功项的元数据（键为消息 id）。
 *
 * 必须串行：`fixateMessageAudio` 共用同一个 controller，并发调用会互相打断。
 *
 * [targets] 里已经过滤掉「已固化」和「无文本」的消息，跳过数量由调用方算好写进
 * [BatchFixateAudioState.skipped]，本函数只负责合成与计数。
 *
 * 合成的文本直接传 `message.toText()` 原文：`fixateMessageAudio` 内部会用与播放完全相同的
 * 清理链路（引号/括号过滤 + Markdown），在这里再处理一遍会让分片边界和朗读路径对不上。
 */
internal suspend fun runBatchFixate(
    tts: CustomTtsState,
    targets: List<UIMessage>,
    state: BatchFixateAudioState,
): Map<Uuid, FixedAudioInfo> {
    val updates = LinkedHashMap<Uuid, FixedAudioInfo>()
    for (message in targets) {
        // 取消后当前条已完成的结果仍然保留，只是不再开始下一条
        if (state.cancelRequested) break
        val text = message.toText()
        // 进度里只显示开头一小段：整条长文本进 Compose 状态会让每次重组都比较一个巨大的字符串
        state.previewText = text.take(PREVIEW_LENGTH)
        val info = tts.fixateMessageAudio(
            messageId = message.id,
            text = text,
            forceRegenerate = false,
            // 沿用这条回复的动态语气（`<tts_voice_control>` 块解析出的元数据）：
            // 缓存键含 instruct，语气一致才能命中播放时已经合成过的音频
            instruct = message.ttsVoiceInstruct(),
        )
        if (info != null) {
            updates[message.id] = info
        } else {
            state.failed++
        }
        state.completed++
    }
    return updates
}

/** 进度对话框里当前文本预览的字数上限 */
private const val PREVIEW_LENGTH = 30

/**
 * 批量固化进度对话框。
 *
 * 不能点外部或返回键关掉：合成已经花掉的钱不能因为误触白费，要退出只能点「取消」。
 */
@Composable
internal fun BatchFixateAudioDialog(
    state: BatchFixateAudioState,
    onCancel: () -> Unit,
) {
    if (!state.running) return

    AlertDialog(
        // 强制走取消按钮，避免误触把进度对话框关掉却留下还在跑的任务
        onDismissRequest = {},
        title = {
            Text(stringResource(R.string.chat_fixate_progress_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = {
                        if (state.total > 0) {
                            state.completed.toFloat() / state.total
                        } else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        R.string.chat_fixate_progress_detail,
                        state.completed.coerceAtMost(state.total),
                        state.total,
                        state.previewText,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
