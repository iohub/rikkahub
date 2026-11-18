package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.rikkahub.data.model.Assistant
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * 助手「加密隐藏」的会话级锁管理器。
 *
 * 这里只保存**内存态**的已解锁助手集合：进程存活期间有效，重启应用后自动全部重新上锁，
 * 因此不承担任何持久化职责。
 *
 * 持久化部分在数据模型里：
 * - [Assistant.isLocked] 标记该助手是否处于加密隐藏状态；
 * - [Assistant.lockPasswordHash] 保存解锁密码的 SHA-256 十六进制摘要（不存明文）。
 *
 * 之所以用 SHA-256 而不是 Keystore 加密（[me.rerere.rikkahub.utils.SecretCipher]），
 * 是因为摘要与设备无关：备份恢复/换机后依然可以校验密码。
 */
object AssistantLockManager {

    private const val HASH_PREFIX = "rikkahub-assistant-lock"
    private const val HEX_CHARS = "0123456789abcdef"

    private val _unlockedIds = MutableStateFlow<Set<Uuid>>(emptySet())

    /** 当前会话中已解锁的助手 ID（进程重启即清空）。 */
    val unlockedIds: StateFlow<Set<Uuid>> = _unlockedIds.asStateFlow()

    /** 解锁该助手（仅当前会话有效）。 */
    fun unlock(id: Uuid) {
        _unlockedIds.update { it + id }
    }

    /** 重新上锁：把助手从已解锁集合中移除，其对话与入口再次隐藏。 */
    fun relock(id: Uuid) {
        _unlockedIds.update { it - id }
    }

    /** 彻底忘记该助手的解锁状态（取消加密、删除助手时调用）。 */
    fun forget(id: Uuid) {
        _unlockedIds.update { it - id }
    }

    /** 该助手在当前会话中是否已解锁。 */
    fun isUnlocked(id: Uuid): Boolean = _unlockedIds.value.contains(id)

    /** 该助手当前是否应该对用户隐藏：已加密且尚未解锁。 */
    fun isHidden(assistant: Assistant): Boolean = assistant.isLocked && !isUnlocked(assistant.id)

    /**
     * 计算解锁密码的摘要。
     *
     * 摘要输入为 `rikkahub-assistant-lock:{assistantId}:{password}`（UTF-8），
     * 输出小写十六进制字符串（长度 64）。
     */
    fun hashPassword(assistantId: Uuid, password: String): String {
        val raw = "$HASH_PREFIX:$assistantId:$password"
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                append(HEX_CHARS[value ushr 4])
                append(HEX_CHARS[value and 0x0F])
            }
        }
    }

    /** 校验密码是否能解开该助手的锁。 */
    fun verify(assistant: Assistant, password: String): Boolean {
        if (!assistant.isLocked) return false
        if (assistant.lockPasswordHash.isBlank()) return false
        return hashPassword(assistant.id, password) == assistant.lockPasswordHash
    }

    /** 返回所有处于加密状态、且密码匹配的助手。 */
    fun matchLocked(assistants: List<Assistant>, password: String): List<Assistant> =
        assistants.filter { it.isLocked && verify(it, password) }

    /** 过滤掉当前应当隐藏的助手（列表页 / 选择器 / 对话页使用）。 */
    fun filterHidden(assistants: List<Assistant>): List<Assistant> =
        assistants.filterNot { isHidden(it) }
}
