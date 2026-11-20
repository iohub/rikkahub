package me.rerere.tts.provider.providers

import kotlinx.serialization.json.jsonPrimitive
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildStepSpeechRequest] 的 JVM 单测：重点覆盖 `instruction` 的静态 + 动态合并与 200 字符截断。
 */
class StepTTSProviderTest {
    @Test
    fun `static and dynamic instructions are merged with static first`() {
        val setting = TTSProviderSetting.Step(instruction = "温和一点")

        val body = buildStepSpeechRequest(setting, "你好", instruct = "兴奋一点")

        assertEquals("温和一点; 兴奋一点", body["instruction"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dynamic instruction alone is sent when setting is blank`() {
        val body = buildStepSpeechRequest(
            TTSProviderSetting.Step(instruction = "   "),
            "你好",
            instruct = "低声耳语",
        )

        assertEquals("低声耳语", body["instruction"]!!.jsonPrimitive.content)
    }

    @Test
    fun `static instruction alone is sent when dynamic is blank`() {
        val body = buildStepSpeechRequest(TTSProviderSetting.Step(instruction = "温和"), "你好")

        assertEquals("温和", body["instruction"]!!.jsonPrimitive.content)
    }

    @Test
    fun `instruction key is omitted when both sides are blank`() {
        val body = buildStepSpeechRequest(
            TTSProviderSetting.Step(instruction = "  "),
            "你好",
            instruct = " ",
        )

        assertFalse(body.containsKey("instruction"))
    }

    @Test
    fun `merged instruction is truncated to the 200 char server limit`() {
        // 静态 150 个字符 + 动态 150 个字符，合并后必然超过 200
        val staticPart = "甲".repeat(150)
        val dynamicPart = "乙".repeat(150)

        val body = buildStepSpeechRequest(
            TTSProviderSetting.Step(instruction = staticPart),
            "你好",
            instruct = dynamicPart,
        )
        val sent = body["instruction"]!!.jsonPrimitive.content

        assertEquals(STEP_INSTRUCTION_MAX_LENGTH, sent.length)
        // 动态优先完整保留：被截断的是静态，动态必须一字不少地留在尾部
        assertTrue("动态语气不能被静态挤掉", sent.endsWith(dynamicPart))
        // 预算 = 200 - 150 - 2 = 48 个字符留给静态
        assertTrue(sent.startsWith("甲".repeat(48)))
        assertEquals("甲".repeat(48) + "; ", sent.substring(0, 50))
        assertTrue(sent.length < staticPart.length + dynamicPart.length)
    }

    @Test
    fun `base fields are unchanged`() {
        val setting = TTSProviderSetting.Step(model = "stepaudio-2.5-tts", voice = "cixingnansheng")

        val body = buildStepSpeechRequest(setting, "你好")

        assertEquals("stepaudio-2.5-tts", body["model"]!!.jsonPrimitive.content)
        assertEquals("你好", body["input"]!!.jsonPrimitive.content)
        assertEquals("cixingnansheng", body["voice"]!!.jsonPrimitive.content)
        assertEquals("mp3", body["responseFormat"]!!.jsonPrimitive.content)
        // 默认没有配置静态 instruction、也没传动态语气时不发该键
        assertNull(body["instruction"])
    }
}
