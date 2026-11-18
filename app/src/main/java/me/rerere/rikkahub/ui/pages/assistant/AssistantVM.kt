package me.rerere.rikkahub.ui.pages.assistant

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.repository.AssistantLockManager
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.service.ChatService

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val chatService: ChatService,
    private val filesManager: FilesManager,
    private val appScope: AppScope,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun updateSettings(fn: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(fn)
        }
    }

    fun addAssistant(assistant: Assistant) {
        viewModelScope.launch {
            settingsStore.update { it.copy(assistants = it.assistants.plus(assistant)) }
        }
    }

    fun removeAssistant(assistant: Assistant) {
        // 助手从列表移除后用户无法再次触发清理，放到应用级作用域里，离开页面也要跑完
        appScope.launch {
            // 在同一次更新里按最新列表检查，连续删除时不会把最后一个助手也删掉
            var removed: Assistant? = null
            settingsStore.update { settings ->
                val target = settings.assistants.find { it.id == assistant.id }
                if (target == null || settings.assistants.size <= 1) return@update settings
                removed = target
                settings.copy(assistants = settings.assistants.filter { it.id != target.id })
            }
            // 只有真的删掉了才清理关联数据
            val target = removed ?: return@launch
            cleanupAssistantFiles(target)
            memoryRepository.deleteFilesOfAssistant(target.id.toString())
            chatService.deleteConversationsOfAssistant(target.id)
        }
    }

    private fun cleanupAssistantFiles(assistant: Assistant) {
        val uris = buildList {
            (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
            assistant.background?.let { add(it.toUri()) }
        }

        if (uris.isNotEmpty()) {
            filesManager.deleteChatFiles(uris)
        }
    }

    fun copyAssistant(assistant: Assistant, copyMemories: Boolean = false) {
        viewModelScope.launch {
            val copiedAssistant = assistant.copy(
                id = kotlin.uuid.Uuid.random(),
                name = "${assistant.name} (Clone)",
                avatar = if (assistant.avatar is Avatar.Image) Avatar.Dummy else assistant.avatar,
            )
            settingsStore.update { it.copy(assistants = it.assistants.plus(copiedAssistant)) }
            if (copyMemories) {
                memoryRepository.copyFiles(
                    fromAssistantId = assistant.id.toString(),
                    toAssistantId = copiedAssistant.id.toString(),
                )
            }
        }
    }

    /**
     * 加密隐藏助手。
     *
     * 语义：加密成功后**立即**从助手列表/选择器消失（不保留本会话解锁态），
     * 之后只能通过「5 秒内点击标题 5 次」的隐藏手势输入密码重新显示。
     *
     * 如果被加密的正好是当前正在使用的助手，则同时把当前助手切换到第一个未加密的其他助手，
     * 避免聊天页继续停留在已经隐藏的助手（以及它的会话）上。
     * 会话本身不删除：解密后原来的对话还在。
     *
     * @return 是否真的加密成功。至少要保留一个未加密助手，被拒绝时返回 false，界面据此不能谎报成功。
     */
    suspend fun lockAssistant(assistant: Assistant, password: String): Boolean {
        // 先清掉可能残留的解锁态，保证加密后立刻不可见
        AssistantLockManager.forget(assistant.id)
        var locked = false
        settingsStore.update { settings ->
            // 按最新列表取一次，避免用到调用方传入的过期副本
            val target = settings.assistants.find { it.id == assistant.id }
                ?: return@update settings
            // 边界保护：不允许把所有助手都加密，否则当前助手指向隐藏助手会泄漏会话内容。
            // 这里静默跳过时必须让界面知道，否则会误报「已加密隐藏」
            if (settings.assistants.count { !it.isLocked } <= 1) return@update settings
            locked = true
            val lockedAssistant = target.copy(
                isLocked = true,
                lockPasswordHash = AssistantLockManager.hashPassword(target.id, password),
            )
            val assistants = settings.assistants.map { if (it.id == lockedAssistant.id) lockedAssistant else it }
            val fallback = assistants.firstOrNull { !it.isLocked && it.id != lockedAssistant.id }
            settings.copy(
                assistants = assistants,
                assistantId = if (settings.assistantId == lockedAssistant.id && fallback != null) {
                    fallback.id
                } else {
                    settings.assistantId
                },
            )
        }
        return locked
    }

    /** 取消加密：永久清除加密标记与密码摘要，助手重新常驻列表。 */
    fun removeLock(assistant: Assistant) {
        // 顺手清掉会话内解锁态，避免残留 id 影响到之后的使用
        AssistantLockManager.forget(assistant.id)
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { current ->
                        if (current.id == assistant.id) {
                            current.copy(isLocked = false, lockPasswordHash = "")
                        } else {
                            current
                        }
                    }
                )
            }
        }
    }

    /** 本会话内解锁（仅内存态，应用重启后自动重新上锁）。 */
    fun unlockAssistant(assistant: Assistant) {
        AssistantLockManager.unlock(assistant.id)
    }

    /** 重新上锁：把已解锁的加密助手再次隐藏。 */
    fun relockAssistant(assistant: Assistant) {
        AssistantLockManager.relock(assistant.id)
        // 与 lockAssistant 保持一致：重新上锁后它就是隐藏助手了，不能继续作为「当前助手」
        viewModelScope.launch {
            settingsStore.update { settings ->
                if (settings.assistantId != assistant.id) return@update settings
                val fallback = settings.assistants.firstOrNull { !it.isLocked && it.id != assistant.id }
                    ?: return@update settings
                settings.copy(assistantId = fallback.id)
            }
        }
    }

    fun getMemories(assistant: Assistant) = memoryRepository.getFilesFlow(assistant.memoryId)
}
