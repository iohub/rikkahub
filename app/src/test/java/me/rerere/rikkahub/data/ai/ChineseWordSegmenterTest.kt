package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轻量中文分词器。
 *
 * 它是 `<tts_voice_control>` 块「写得够不够丰满」这条规则的唯一判据，所以这里盯两件事：
 * 敷衍短句必须被切出很少的词（否则规则形同虚设），正常写完六个维度的描述必须切出足够多的词
 * （否则正常回复会被无限打回）。混排、纯标点这些边角一并锁住。
 *
 * 词表是手写的，用 `dictionarySize` 兜住「不小心把词表写空 / 写残」这种低级失误。
 */
class ChineseWordSegmenterTest {
    @Test
    fun `a full six-dimension description yields at least twenty words`() {
        val words = ChineseWordSegmenter.segment(FULL_DESCRIPTION)

        assertTrue("词组数=${words.size}，正常描述不该低于 20：$words", words.size >= 20)
    }

    @Test
    fun `a three-word lazy description stays below the threshold`() {
        // 本规则要拦的典型：只有动词和一个副词，TTS 拿到的几乎是空指令
        val words = ChineseWordSegmenter.segment("平静地念")

        assertTrue("词组数=${words.size}：$words", words.size < 5)
    }

    @Test
    fun `a two-word flat description stays below the threshold`() {
        val words = ChineseWordSegmenter.segment("语气平淡")

        assertTrue("词组数=${words.size}：$words", words.size < 5)
    }

    @Test
    fun `punctuation whitespace and empty input produce no words`() {
        assertTrue(ChineseWordSegmenter.segment("").isEmpty())
        assertTrue(ChineseWordSegmenter.segment("   \n\t  ").isEmpty())
        // 全角标点 + 英文标点 + 标签残留字符 + emoji：全部只是分隔符，不产出词
        assertTrue(ChineseWordSegmenter.segment("，。！？；：、「」『』（）《》…—～·!?,.;:/<>😀").isEmpty())
    }

    @Test
    fun `english words and numbers survive mixed chinese input`() {
        val words = ChineseWordSegmenter.segment("语速放慢到0.8倍，音色SSML般的清亮")

        assertTrue("应把 0.8 当成一个词：$words", "0.8" in words)
        assertTrue("应把 SSML 当成一个词：$words", "SSML" in words)
        // 中文部分照常成词，混排不该把整句退化成逐字切分
        assertTrue("语气词应命中词表：$words", "语速" in words && "放慢" in words && "音色" in words)
    }

    @Test
    fun `technology names keep their suffix`() {
        // `+` `#` 留在词尾，否则 C++ / C# 会被切成孤零零的 C —— 这类词出现在描述里时不该被拆散
        assertEquals(listOf("C++"), ChineseWordSegmenter.segment("C++"))
        assertEquals(listOf("C#"), ChineseWordSegmenter.segment("C#"))
        // `-` `.` `_` 不能收尾：那是紧跟的标点残留，必须裁掉
        assertEquals(listOf("0.8"), ChineseWordSegmenter.segment("0.8."))
    }

    @Test
    fun `repeated words are kept as separate items`() {
        // 不去重是刻意的：同一个词出现两次就是两个词组，语义上「信息更多」
        assertEquals(listOf("语气", "语气"), ChineseWordSegmenter.segment("语气语气"))
    }

    @Test
    fun `exactly five words sit on the boundary and pass`() {
        // 构造依据：这五个词都在内置词表里，且任意相邻两字、三字组合都不构成更长的词，
        // 所以正向最大匹配会正好切出 5 个词组 —— 规则的阈值比较是 `< 5`，这一条不该被打回。
        val words = ChineseWordSegmenter.segment("音量偏轻语速缓慢气息")

        assertEquals(5, words.size)
        assertEquals(listOf("音量", "偏轻", "语速", "缓慢", "气息"), words)
    }

    @Test
    fun `dictionary is large enough to be useful`() {
        assertTrue(
            "词表只有 ${ChineseWordSegmenter.dictionarySize} 个词，正常描述会被切成太多单字",
            ChineseWordSegmenter.dictionarySize >= 500,
        )
    }

    companion object {
        /** 覆盖语气 / 音量 / 语速 / 音色 / 气息 / 情绪六个维度的正常描述 */
        private const val FULL_DESCRIPTION =
            "以略带慵懒和不满的语气，音量偏轻，语速不疾不徐，音色低沉柔和，" +
                "气息里带一点叹息，情绪是关心却装作不在意"
    }
}
