package me.rerere.rikkahub.ui.pages.setting

import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * TTS 列表在「内置 TTS 关闭 → 过滤掉 SystemTTS」时的拖拽排序。
 *
 * 拖拽回调给到的是过滤后列表的下标，直接拿去操作原始 ttsProviders 会把隐藏的 SystemTTS 挪错位置，
 * 这里的用例锁住 [reorderVisible] 的映射规则。
 */
class SettingSpeechReorderTest {
    private val remote = TTSProviderSetting.RemoteTTS(id = Uuid.random(), name = "remote")
    private val system = TTSProviderSetting.SystemTTS(id = Uuid.random(), name = "system")
    private val openAI = TTSProviderSetting.OpenAI(id = Uuid.random(), name = "openai")

    @Test
    fun `reorder only touches visible providers`() {
        val all = listOf(remote, system, openAI)
        val visible = all.filterNot { it is TTSProviderSetting.SystemTTS }

        // 界面上看到的是 [remote, openAI]，把 openAI 拖到第一位
        val moved = all.reorderVisible(visible, fromIndex = 1, toIndex = 0)

        // 隐藏的 system 留在原来的槽位（第二个），没有被换到第一位
        assertEquals(listOf(openAI, system, remote), moved)
    }

    @Test
    fun `reorder with every provider visible moves the dragged item`() {
        val all = listOf(remote, system, openAI)

        val moved = all.reorderVisible(all, fromIndex = 2, toIndex = 0)

        assertEquals(listOf(openAI, remote, system), moved)
    }

    // 拖拽期间可见列表被改过（比如刚拖动的 provider 被删除），这次拖拽必须丢弃
    @Test
    fun `stale visible list is ignored`() {
        val all = listOf(remote, system, openAI)
        val deleted = TTSProviderSetting.OpenAI(id = Uuid.random(), name = "deleted")

        val moved = all.reorderVisible(listOf(remote, deleted), fromIndex = 1, toIndex = 0)

        assertEquals(all, moved)
    }

    @Test
    fun `out of range or no-op indexes are ignored`() {
        val all = listOf(remote, system, openAI)

        assertEquals(all, all.reorderVisible(all, fromIndex = 5, toIndex = 0))
        assertEquals(all, all.reorderVisible(all, fromIndex = 0, toIndex = 5))
        assertEquals(all, all.reorderVisible(all, fromIndex = 1, toIndex = 1))
    }
}
