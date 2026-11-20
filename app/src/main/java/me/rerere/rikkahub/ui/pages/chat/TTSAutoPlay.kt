package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.ai.transformers.ttsVoiceInstruct
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.utils.extractQuotedContentAsText
import me.rerere.rikkahub.utils.removeBracketedContent

@Composable
fun TTSAutoPlay(vm: ChatVM, setting: Settings, conversation: Conversation) {
    // Auto-play TTS after generation completes
    val tts = LocalTTSState.current
    val currentConversation by rememberUpdatedState(conversation)
    val updatedSetting by rememberUpdatedState(setting)
    LaunchedEffect(Unit) {
        vm.generationDoneFlow.collect { conversationId ->
            if (conversationId == currentConversation.id &&
                !vm.voiceSession.state.value.isActive &&
                updatedSetting.displaySetting.autoPlayTTSAfterGeneration
            ) {
                val lastMessage = currentConversation.currentMessages.lastOrNull()
                if (lastMessage != null && lastMessage.role == MessageRole.ASSISTANT) {
                    val text = lastMessage.toText()
                    var textToSpeak = text
                    if (updatedSetting.displaySetting.ttsOnlyReadQuoted) {
                        textToSpeak = textToSpeak.extractQuotedContentAsText() ?: textToSpeak
                    }
                    if (updatedSetting.displaySetting.ttsOnlyReadOutsideBrackets) {
                        textToSpeak = textToSpeak.removeBracketedContent() ?: textToSpeak
                    }
                    if (textToSpeak.isNotBlank()) {
                        // 带上 messageId：自动朗读期间这条消息的播放按钮也会高亮，与手动点播放一致。
                        // instruct 是本轮回复 `<tts_voice_control>` 块解析出的动态语气（由 transformer 写进 part 元数据），
                        // 手动播放 / 预取 / 固化都读同一份，语气才会前后一致、缓存键也才对得上
                        tts.speak(
                            textToSpeak,
                            messageId = lastMessage.id.toString(),
                            instruct = lastMessage.ttsVoiceInstruct(),
                        )
                    }
                }
            }
        }
    }
}
