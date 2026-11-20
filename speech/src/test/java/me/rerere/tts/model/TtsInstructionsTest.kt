package me.rerere.tts.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [mergeTtsInstructions] 的 JVM 单测：只覆盖纯字符串规则，不涉及任何 provider / 网络。
 *
 * 这里锁死的是「静态指令（设置页）在前、动态指令（模型函数调用）在后、'; ' 连接、
 * 空白视同空、超长从尾部截断」这套语义——provider 的下发条件与缓存键都依赖它。
 */
class TtsInstructionsTest {
    @Test
    fun `both empty yields empty string`() {
        assertEquals("", mergeTtsInstructions("", ""))
        // 纯空白同样视为空，否则会拼出 "; " 这种脏串并让 provider 误以为要下发
        assertEquals("", mergeTtsInstructions("   ", "\t\n"))
    }

    @Test
    fun `only static instruction is returned trimmed`() {
        assertEquals("warm and calm", mergeTtsInstructions("  warm and calm  ", ""))
    }

    @Test
    fun `only dynamic instruction is returned trimmed`() {
        assertEquals("excited", mergeTtsInstructions("", "  excited "))
    }

    @Test
    fun `both are joined with static first`() {
        assertEquals("warm and calm; excited", mergeTtsInstructions("warm and calm", "excited"))
        // 两边都带空白时依然只 trim 边界，不留下重复分隔符
        assertEquals("warm; excited", mergeTtsInstructions(" warm ", " excited "))
    }

    @Test
    fun `maxLength truncates the merged tail`() {
        assertEquals("warm; exc", mergeTtsInstructions("warm", "excited", maxLength = 9))
        // 截断发生在合并之后：静态基调优先保留，动态语气被砍掉尾巴
        assertEquals("warm; exci", mergeTtsInstructions("warm", "excited", maxLength = 10))
        // 合并结果本来就够短时不受影响
        assertEquals("warm; excited", mergeTtsInstructions("warm", "excited", maxLength = 200))
    }

    @Test
    fun `maxLength applies to single side too`() {
        assertEquals("warm", mergeTtsInstructions("warm and calm", "", maxLength = 4))
        assertEquals("exci", mergeTtsInstructions("", "excited", maxLength = 4))
        assertEquals("", mergeTtsInstructions("", "", maxLength = 4))
    }
}
