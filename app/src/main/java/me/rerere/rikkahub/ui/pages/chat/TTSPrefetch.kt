package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.collectLatest
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.ai.transformers.ttsVoiceInstruct
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.utils.extractQuotedContentAsText
import me.rerere.rikkahub.utils.removeBracketedContent
import me.rerere.tts.provider.TTSProviderSetting

/**
 * 远程 TTS 自动预生成：助手消息生成完成后，在后台用与手动播放**完全相同**的文本处理与合成链路
 * 调用远程 TTS 并写入加密缓存，**不播放**。之后用户点播放直接命中缓存，省掉网络与推理等待。
 *
 * 与 [TTSAutoPlay] 的分工：那个是「生成完就直接出声」，这个是「先把声音准备好」，两者互不依赖；
 * 同时打开时同一段话会被合成两次，但缓存键相同、覆盖写，正确性无损。
 */
@Composable
fun TTSPrefetch(vm: ChatVM, setting: Settings, conversation: Conversation) {
    val tts = LocalTTSState.current
    val currentConversation by rememberUpdatedState(conversation)
    val updatedSetting by rememberUpdatedState(setting)
    LaunchedEffect(Unit) {
        // collectLatest：连发多条消息时自动取消上一次预生成，避免后台堆积合成请求（与 controller 侧取消策略叠加）
        vm.generationDoneFlow.collectLatest { conversationId ->
            // 1. 只处理当前会话
            if (conversationId != currentConversation.id) return@collectLatest
            // 2. 开关
            if (!updatedSetting.displaySetting.prefetchTTSAfterGeneration) return@collectLatest
            // 3. 语音模式进行中不预生成，避免同一段话被请求两遍
            if (vm.voiceSession.state.value.isActive) return@collectLatest

            // 4. 只有「远程 TTS + 该 provider 缓存已开启」才有落盘的意义，本地 TTS 没有缓存
            val provider = updatedSetting.getSelectedTTSProvider()
            if (provider !is TTSProviderSetting.RemoteTTS || !provider.cacheEnabled) return@collectLatest

            // 5. 取最后一条助手消息，并做与 TTSAutoPlay 完全一致的文本过滤（顺序不能变，否则缓存键对不上）
            val lastMessage = currentConversation.currentMessages.lastOrNull()
            if (lastMessage == null || lastMessage.role != MessageRole.ASSISTANT) return@collectLatest
            val text = lastMessage.toText()
            var textToSpeak = text
            if (updatedSetting.displaySetting.ttsOnlyReadQuoted) {
                textToSpeak = textToSpeak.extractQuotedContentAsText() ?: textToSpeak
            }
            if (updatedSetting.displaySetting.ttsOnlyReadOutsideBrackets) {
                textToSpeak = textToSpeak.removeBracketedContent() ?: textToSpeak
            }
            if (textToSpeak.isNotBlank()) {
                // instruct 必须与随后 speak 用的那一份相同（同样来自消息上的 `<tts_voice_control>` 元数据）：
                // 它参与音频缓存键，语气不一致的话这份预取在播放时就命中不了，等于白跑一次合成
                tts.prefetch(textToSpeak, instruct = lastMessage.ttsVoiceInstruct())
            }
        }
    }
}
