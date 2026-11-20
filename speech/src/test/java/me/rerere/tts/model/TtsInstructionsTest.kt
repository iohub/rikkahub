package me.rerere.tts.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `maxLength keeps the dynamic instruction and shrinks the static part`() {
        // 动态已经占满预算（只剩连接符都放不下）时只下发动态：静态完全让位，动态一字不丢
        assertEquals("excited", mergeTtsInstructions("warm", "excited", maxLength = 9))
        // 还塞得下连接符时，剩下的 1 个字符给静态
        assertEquals("w; excited", mergeTtsInstructions("warm", "excited", maxLength = 10))
        // 合并结果本来就够短时不受影响
        assertEquals("warm; excited", mergeTtsInstructions("warm", "excited", maxLength = 200))
    }

    @Test
    fun `maxLength keeps short dynamic intact when static is at the limit`() {
        // 静态 200 字符（刚好等于上限）+ 动态 30 字符：旧实现会 take(200) 把动态整段砍掉
        val staticPart = "甲".repeat(200)
        val dynamicPart = "乙".repeat(30)

        val merged = mergeTtsInstructions(staticPart, dynamicPart, maxLength = 200)

        assertEquals(200, merged.length)
        // 动态完整保留（尾部一字不少），被截断的是静态
        assertTrue("动态指令必须完整保留", merged.endsWith(dynamicPart))
        // 预算 = 200 - 30 - 2 = 168 个字留给静态
        assertTrue(merged.startsWith("甲".repeat(168)))
        assertEquals("甲".repeat(168) + "; ", merged.substring(0, 170))
    }

    @Test
    fun `short static and long dynamic both survive when under the limit`() {
        val staticPart = "甲".repeat(10)
        val dynamicPart = "乙".repeat(60)

        val merged = mergeTtsInstructions(staticPart, dynamicPart, maxLength = 200)

        // 10 + "; " + 60 = 72，远没到上限，两边都原样保留
        assertEquals("$staticPart; $dynamicPart", merged)
        assertEquals(72, merged.length)
    }

    @Test
    fun `maxLength narrower than the joiner keeps only the dynamic instruction`() {
        assertEquals("ab", mergeTtsInstructions("static", "abcde", maxLength = 2))
        // 上限非正数时什么都下发不了，也不能因为 take(负数) 抛异常
        assertEquals("", mergeTtsInstructions("static", "abcde", maxLength = 0))
        assertEquals("", mergeTtsInstructions("static", "abcde", maxLength = -1))
    }

    @Test
    fun `maxLength applies to single side too`() {
        assertEquals("warm", mergeTtsInstructions("warm and calm", "", maxLength = 4))
        assertEquals("exci", mergeTtsInstructions("", "excited", maxLength = 4))
        assertEquals("", mergeTtsInstructions("", "", maxLength = 4))
    }
}
