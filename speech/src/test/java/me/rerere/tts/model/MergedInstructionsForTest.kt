package me.rerere.tts.model

import kotlinx.serialization.json.jsonPrimitive
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.providers.STEP_INSTRUCTION_MAX_LENGTH
import me.rerere.tts.provider.providers.buildRemoteSpeechRequest
import me.rerere.tts.provider.providers.buildStepSpeechRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mergedInstructionsFor] 的 JVM 单测：UI 调试面板用它预览「真正下发出去的 instruct」，
 * 所以这里不只覆盖分支，还把它和真实请求体做了字符串级对齐——
 * 一旦预览规则与 provider 请求体漂移（改了上限、改了字段名、改了合并顺序），这些用例会先炸。
 */
class MergedInstructionsForTest {
    @Test
    fun `remote tts joins static and dynamic`() {
        val setting = TTSProviderSetting.RemoteTTS(instructions = "warm")

        assertEquals("warm; excited", setting.mergedInstructionsFor("excited"))
    }

    @Test
    fun `remote tts falls back to the single non blank side`() {
        val noStatic = TTSProviderSetting.RemoteTTS(instructions = "")
        val noDynamic = TTSProviderSetting.RemoteTTS(instructions = "warm")

        assertEquals("excited", noStatic.mergedInstructionsFor("excited"))
        assertEquals("warm", noDynamic.mergedInstructionsFor(""))
    }

    @Test
    fun `remote tts with both sides blank yields empty string`() {
        // 空串而不是 null：说明该 provider 消费 instruct，只是这次没有内容可下发（请求体里连键都不写）
        val setting = TTSProviderSetting.RemoteTTS(instructions = "   ")

        assertEquals("", setting.mergedInstructionsFor(" \t\n"))
    }

    @Test
    fun `remote tts preview matches the real request body`() {
        val setting = TTSProviderSetting.RemoteTTS(instructions = "warm")

        val preview = setting.mergedInstructionsFor("excited")
        val sent = buildRemoteSpeechRequest(setting, "Hello", instruct = "excited")["instructions"]

        assertEquals(preview, sent?.jsonPrimitive?.content)
    }

    @Test
    fun `step tts caps the merged value and keeps the dynamic part`() {
        // 静态 190 字（逼近 200 上限）+ 动态 30 字：合并必然超限，考验截断策略
        val staticPart = "甲".repeat(STEP_INSTRUCTION_MAX_LENGTH - 10)
        val dynamicPart = "乙".repeat(30)
        val setting = TTSProviderSetting.Step(instruction = staticPart)

        val merged = setting.mergedInstructionsFor(dynamicPart)!!

        assertTrue("长度不能超过服务端上限，实际 ${merged.length}", merged.length <= STEP_INSTRUCTION_MAX_LENGTH)
        // 动态优先：静态再长也不能把这一轮的语气挤掉（旧实现会整段砍掉动态）
        assertTrue("动态指令必须完整保留，实际末尾=${merged.takeLast(30)}", merged.endsWith(dynamicPart))
        assertEquals(STEP_INSTRUCTION_MAX_LENGTH, merged.length)
    }

    @Test
    fun `step tts preview matches the real request body when truncated`() {
        val setting = TTSProviderSetting.Step(instruction = "甲".repeat(STEP_INSTRUCTION_MAX_LENGTH - 10))
        val dynamicPart = "乙".repeat(30)

        val preview = setting.mergedInstructionsFor(dynamicPart)
        val sent = buildStepSpeechRequest(setting, "你好", instruct = dynamicPart)["instruction"]

        assertEquals(preview, sent?.jsonPrimitive?.content)
    }

    @Test
    fun `providers without instruct field return null`() {
        assertNull(TTSProviderSetting.OpenAI().mergedInstructionsFor("excited"))
        assertNull(TTSProviderSetting.SystemTTS().mergedInstructionsFor("excited"))
        // 动态为空也不该被当成「支持 instruct」：null 的含义是 provider 压根不消费这个字段
        assertNull(TTSProviderSetting.OpenAI().mergedInstructionsFor(""))
    }
}
