package me.rerere.rikkahub.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.AssistantLockManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

private const val TAG = "HistoryVM"

class HistoryVM(
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
) : ViewModel() {
    val assistant = settingsStore.settingsFlow
        .map { it.getCurrentAssistant() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // 当前助手是否处于「加密隐藏」状态：已加密且本会话尚未解锁。
    // AssistantLockManager.unlockedIds 参与 combine，解锁/重新上锁后历史列表会立刻跟着变。
    private val currentAssistantHidden: Flow<Boolean> = combine(
        settingsStore.settingsFlow,
        AssistantLockManager.unlockedIds,
    ) { settings, unlockedIds ->
        val current = settings.getCurrentAssistant()
        current.isLocked && current.id !in unlockedIds
    }.distinctUntilChanged()

    val conversations: StateFlow<List<Conversation>> =
        combine(assistant, currentAssistantHidden) { current, hidden -> current to hidden }
            .distinctUntilChanged()
            .flatMapLatest { (current, hidden) ->
                // 隐藏助手的会话一条都不给，解锁后自动重新订阅 Room Flow
                if (current == null || hidden) {
                    flowOf(emptyList())
                } else {
                    conversationRepo.getConversationsOfAssistant(current.id)
                }
            }
            .catch {
                Log.e(TAG, "Error: ${it.message}")
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun deleteConversations(conversationIds: Collection<Uuid>) {
        viewModelScope.launch {
            conversationIds.forEach { chatService.deleteConversation(it) }
        }
    }

    fun deleteAllConversations() {
        val assistant = assistant.value ?: return
        viewModelScope.launch {
            chatService.deleteConversationsOfAssistant(assistant.id)
        }
    }

    fun togglePinStatus(conversationId: Uuid) {
        viewModelScope.launch {
            chatService.toggleConversationPinned(conversationId)
        }
    }

    fun getPinnedConversations(): Flow<List<Conversation>> =
        combine(currentAssistantHidden, conversationRepo.getPinnedConversations()) { hidden, pinned ->
            // 当前助手被加密隐藏时，置顶列表同样不能泄露内容
            if (hidden) emptyList() else pinned
        }
}
