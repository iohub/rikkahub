package me.rerere.rikkahub.data.event

import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

sealed class AppEvent {
    /**
     * 请求朗读一段文本。
     *
     * @property text 要朗读的纯文本（不含 markdown 标记）。
     * @property instruct 本轮动态语气指令（情绪 / 语速 / 场景，如「excited and fast」），
     *   由模型在调用 `text_to_speech` 时生成；它会与设置页配置的静态 instructions 合并后下发。
     *   留空表示只使用静态配置。
     */
    data class Speak(
        val text: String,
        val instruct: String = "",
    ) : AppEvent()
    data object OpenUsageAccessSettings : AppEvent()

    /** 聊天生成过程中的流式更新，由 ChatNotificationManager 消费用于 Live Update 通知。 */
    data class ChatGenerationUpdate(
        val conversationId: Uuid,
        val lastMessage: UIMessage,
        val senderName: String,
    ) : AppEvent()

    /**
     * 回复质量审查未通过，正在自动重新生成。
     *
     * @property attempt 第几次重试（从 1 开始）
     * @property maxAttempt 允许重试的上限
     * @property reason 审查给出的理由，已截断
     */
    data class ReplyReviewRetry(
        val conversationId: Uuid,
        val attempt: Int,
        val maxAttempt: Int,
        val reason: String,
    ) : AppEvent()

    /**
     * 聊天生成结束（完成、失败或取消）。
     * [contentPreview] 为 null 时仅取消 Live Update 通知，不发送完成通知。
     */
    data class ChatGenerationEnded(
        val conversationId: Uuid,
        val senderName: String,
        val contentPreview: String?,
    ) : AppEvent()
}
