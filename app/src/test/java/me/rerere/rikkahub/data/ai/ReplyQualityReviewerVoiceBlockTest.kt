package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `<tts_voice_control>` 块的确定性校验。
 *
 * 块本体已经被 `TtsVoiceControlTransformer` 从正文里剥掉，`toText()` 里一个标签都看不到，
 * 所以这条格式约定必须由代码判定，不能让辅助模型去猜。这里盯的是五种情形：
 * 要求有块却没有、块内是空的、多出第二个块、块内写得太简略（分词后不足 5 个词组）、
 * 以及根本不需要块时不能乱判否。另外还要盯住「简短确认可以省略块」（注入规范第 6 条）这条豁免
 * 只作用于「缺块」，不能顺手放过另外三条。
 */
class ReplyQualityReviewerVoiceBlockTest {
    @Test
    fun `missing block is rejected when required`() {
        val result = checkVoiceControlBlock(requiresBlock = true, blockCount = 0, instruct = "")

        assertNotNull(result)
        assertFalse(result!!.passed)
        assertEquals(0, result.score)
        assertTrue(result.issues.single().contains("缺少"))
        assertTrue(result.suggestion.contains("tts_voice_control"))
        assertEquals("voice-block-check", result.raw)
    }

    @Test
    fun `missing block passes when not required`() {
        // 助手没开 TTS 本地工具、system prompt 里也没有这条规范：不能要求必须有块，否则会死循环重生成
        val result = checkVoiceControlBlock(requiresBlock = false, blockCount = 0, instruct = "")

        assertNull(result)
    }

    @Test
    fun `single block with content passes`() {
        val result = checkVoiceControlBlock(requiresBlock = true, blockCount = 1, instruct = INSTRUCT)

        assertNull(result)
    }

    @Test
    fun `single block with blank content is rejected`() {
        val result = checkVoiceControlBlock(requiresBlock = true, blockCount = 1, instruct = "")

        assertNotNull(result)
        assertFalse(result!!.passed)
        assertTrue(result.issues.single().contains("没有内容"))
        assertTrue(result.suggestion.contains("不能留空"))
    }

    @Test
    fun `two blocks are rejected even when one of them has content`() {
        val result = checkVoiceControlBlock(requiresBlock = true, blockCount = 2, instruct = INSTRUCT)

        assertNotNull(result)
        assertFalse(result!!.passed)
        assertTrue(result.issues.single().contains("只能有 1 个"))
        assertTrue(result.issues.single().contains("2"))
        assertTrue(result.suggestion.contains("删掉多余的块"))
    }

    @Test
    fun `extra block is rejected even when block is not required`() {
        // 没要求有块时模型仍在正文里自说自话地输出标签，同样算违规：一个块都不该有
        val result = checkVoiceControlBlock(requiresBlock = false, blockCount = 2, instruct = INSTRUCT)

        assertNotNull(result)
        assertFalse(result!!.passed)
        assertTrue(result.issues.single().contains("只能有 1 个"))
    }

    @Test
    fun `blank instruct check only applies when block exists`() {
        // 0 块且 instruct 为空、又不需要块：两个前置条件都不满足，不能因为 instruct 空就判否
        assertNull(checkVoiceControlBlock(requiresBlock = false, blockCount = 0, instruct = ""))
        // 有块但空内容，即使不需要块也放行（没注入规范时不该挑剔内容）
        assertNull(checkVoiceControlBlock(requiresBlock = false, blockCount = 1, instruct = ""))
    }

    @Test
    fun `too short instruct is rejected`() {
        // 只有动词加一个副词：TTS 拿到的几乎是空指令，等于没写风格
        val result = checkVoiceControlBlock(requiresBlock = true, blockCount = 1, instruct = "平静地念")

        assertNotNull(result)
        assertFalse(result!!.passed)
        assertEquals(0, result.score)
        assertTrue(result.issues.single().contains("太简略"))
        // 打回是为了让模型改对：反馈里必须给出「至少几个词组」这个可执行标准
        assertTrue(result.suggestion.contains("至少 5 个词组"))
        assertEquals("voice-block-check", result.raw)
    }

    @Test
    fun `detailed instruct passes`() {
        val result = checkVoiceControlBlock(requiresBlock = true, blockCount = 1, instruct = DETAILED_INSTRUCT)

        assertNull(result)
    }

    @Test
    fun `instruct with exactly five words passes`() {
        // 边界：`音量 偏轻 语速 缓慢 气息` 正好 5 个词组，判定用的是 `< 5`，落在阈值上要放行
        val result = checkVoiceControlBlock(requiresBlock = true, blockCount = 1, instruct = EXACTLY_FIVE_INSTRUCT)

        assertNull(result)
    }

    @Test
    fun `short reply may omit the block`() {
        // 用户只说了一句「嗯」、模型按注入规范第 6 条回一句「好的」不带块：必须放行，
        // 否则开了 TTS 本地工具的助手（requiresBlock 恒为 true）每轮都要白白重生成一次
        val result = checkVoiceControlBlock(
            requiresBlock = true,
            blockCount = 0,
            instruct = "",
            isShortReply = true,
        )

        assertNull(result)
    }

    @Test
    fun `two blocks are rejected even for a short reply`() {
        // 豁免只覆盖「缺块」：正文再短，多写一个标签依旧是违规，模型在自说自话地输出标签
        val result = checkVoiceControlBlock(
            requiresBlock = true,
            blockCount = 2,
            instruct = INSTRUCT,
            isShortReply = true,
        )

        assertNotNull(result)
        assertFalse(result!!.passed)
        assertTrue(result.issues.single().contains("只能有 1 个"))
    }

    @Test
    fun `too short instruct is rejected even for a short reply`() {
        // 模型既然主动写了块，就没有「正文太短所以块可以写得敷衍」的道理：块内空 / 太简略照样打回
        val result = checkVoiceControlBlock(
            requiresBlock = true,
            blockCount = 1,
            instruct = "平静地念",
            isShortReply = true,
        )

        assertNotNull(result)
        assertFalse(result!!.passed)
        assertTrue(result.issues.single().contains("太简略"))
    }

    companion object {
        private const val INSTRUCT =
            "以极度悲伤、带着明显哭腔的语气，用较小的音量缓缓诉说，语速缓慢，声音颤抖而压抑。"

        /** 六个维度齐全的正常描述，分词后远超阈值 */
        private const val DETAILED_INSTRUCT =
            "以略带慵懒和不满的语气，音量偏轻，语速不疾不徐，音色低沉柔和，" +
                "气息里带一点叹息，情绪是关心却装作不在意"

        /**
         * 恰好 5 个词组的边界样本。
         *
         * 这五个词都在分词器词表里，且相邻字组合不出更长的词，所以正向最大匹配正好切出 5 个 ——
         * 用来验证「阈值比较是 `< 5` 而不是 `<= 5`」。（构造依据在 [ChineseWordSegmenterTest] 里也断言了一次。）
         */
        private const val EXACTLY_FIVE_INSTRUCT = "音量偏轻语速缓慢气息"
    }
}
