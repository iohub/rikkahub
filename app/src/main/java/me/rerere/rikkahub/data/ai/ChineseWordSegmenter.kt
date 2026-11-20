package me.rerere.rikkahub.data.ai

/**
 * 轻量中文分词器：正向最大匹配 + 分类兜底，把一段语气描述切成词组序列。
 *
 * 为什么不引第三方分词库：全文搜索曾经用过 jieba，为了压安装包体积把它连同词典一起删掉了
 * （`80845dd9 refactor(search): 全文搜索去掉 jieba 分词和词典`，
 * `2b522f99 chore(search): libsimple.so 换成不带 jieba 的版本`，arm64 APK 因此从 25.8MB 降到 20.8MB）。
 * 这里唯一的用途是判断 `<tts_voice_control>` 块内写得够不够丰满，精度要求极低 ——
 * 「平静地念」这类敷衍短句必须被切出很少的几个词，正常写完六个维度的描述必须超过阈值；
 * 这个目标几十行纯 JVM 代码就能达到，不值得再把一个带词典数据的依赖拉回来。
 *
 * 纯函数、无 Android 依赖、无文件 IO、无协程，所以 `app/src/test/java` 下的 JUnit4 单测可以直接调用。
 */
internal object ChineseWordSegmenter {
    /** 词表里最长的词有几个汉字；正向匹配从这个长度开始逐级往下试 */
    private const val MAX_WORD_LENGTH = 6

    /** 内置词表大小，供测试断言词表没有被误写成空 */
    val dictionarySize: Int get() = DICTIONARY.size

    /**
     * 把一段文本切成词组序列。
     *
     * 逐字符扫描，分三类处理：
     * 1. 分隔符（标点、空白、换行、emoji、`<` `>` `/` 这类标签残留字符）：直接丢掉，不产出词；
     * 2. ASCII 字母/数字段：连同词内的 `.` `+` `#` `-` `_` 整体成词（`TTS`、`SSML`、`0.8`、`C++`）；
     * 3. 汉字段：正向最大匹配，词表里都不命中就退化成单字。
     *
     * 结果保留原顺序且**不做去重**：同一个词出现两次就是两个词组，这正符合
     * 「语气描述里词组越多、信息越密」的语义。
     */
    fun segment(text: String): List<String> {
        val words = ArrayList<String>()
        var index = 0
        while (index < text.length) {
            val current = text[index]
            when {
                isAsciiWordStart(current) -> {
                    // 至少吃掉一个字符，再往后吞词内允许的连接符
                    var end = index + 1
                    while (end < text.length && isAsciiWordBody(text[end])) end++
                    // 以 `.` `-` `_` 结尾基本是紧跟的标点残留（`0.8.` / `TTS-`），回退裁掉；
                    // `+` `#` 例外地保留，否则 `C++` `C#` 会被切成孤零零的 `C`
                    while (end > index + 1 && !isAsciiWordTail(text[end - 1])) end--
                    words += text.substring(index, end)
                    index = end
                }

                current.isCjk() -> {
                    val runEnd = hanRunEnd(text, index)
                    val length = longestMatch(text, index, runEnd)
                    words += text.substring(index, index + length)
                    index += length
                }

                else -> index++
            }
        }
        return words
    }

    /**
     * 从 [start] 开始、在汉字段 [end] 之内找最长的词表命中，返回该词的长度；全不命中返回 1（单字成词）。
     *
     * 下限是 2：单字不可能出现在词表里（单字由「都不命中」这条兜底产生），少试一轮没有意义。
     */
    private fun longestMatch(text: String, start: Int, end: Int): Int {
        val maxLength = minOf(MAX_WORD_LENGTH, end - start)
        for (length in maxLength downTo 2) {
            if (DICTIONARY.contains(text.substring(start, start + length))) return length
        }
        return 1
    }

    /** 从 [start] 起连续的汉字到哪里结束；匹配不能跨过标点或 ASCII 段，否则会切出假词 */
    private fun hanRunEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length && text[end].isCjk()) end++
        return end
    }

    /**
     * 是否汉字。手写码点范围而不用 `Character.UnicodeScript`：后者在低版本 Android 上不可用，
     * 而这个类的定位是「纯 JVM、任何环境结果一致」。CJK 基本区 + 扩展 A 足够覆盖中文语气描述。
     */
    private fun Char.isCjk(): Boolean = this in '\u4E00'..'\u9FFF' || this in '\u3400'..'\u4DBF'

    /** ASCII 字母/数字：全角字母、带音标的字母都当分隔符，避免把不可见字符带进词组 */
    private fun isAsciiWordStart(char: Char): Boolean =
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'

    /** ASCII 词内部允许的字符：字母数字以及 `.` `+` `#` `-` `_` */
    private fun isAsciiWordBody(char: Char): Boolean =
        isAsciiWordStart(char) || char == '.' || char == '+' || char == '#' || char == '-' || char == '_'

    /** ASCII 词尾部允许的字符：连接符不能收尾，但 `+` `#` 要留着（`C++`、`C#`） */
    private fun isAsciiWordTail(char: Char): Boolean =
        isAsciiWordStart(char) || char == '+' || char == '#'

    // ===== 词表 =====
    // 每行一个词，`#` 开头是分组注释。顺序不影响匹配（永远是全局取最长），分组只为以后维护方便。
    // 同一个词只能出现一次：重复行会被 toSet() 静默吃掉，白占行数、也让 dictionarySize 失真。
    private val RAW_DICTIONARY = """
        # 单字词一个都不收：单字本来就由「词表不命中」的兜底逻辑产出，写进来也永远匹配不到，
        # 只会让词表看起来更大、实际不产生任何分词效果。所以下面全是 2~6 字的词。

        # ---- 语气 / 口吻 ----
        语气
        口吻
        语调
        腔调
        慵懒
        傲气
        撒娇
        轻柔
        温柔
        冷淡
        调侃
        试探
        认真
        随意
        平静
        激动
        急促
        缓慢
        不疾不徐
        不紧不慢
        尾音
        上扬
        下沉
        柔声
        耳语
        呢喃
        娇嗔
        嗔怪
        抱怨
        嘟囔
        强调
        轻描淡写
        淡漠
        懒散
        散漫
        漫不经心
        一本正经
        郑重其事
        随口
        敷衍
        挖苦
        讥讽
        讽刺
        打趣
        娇软
        娇憨
        撒娇腔
        催促
        反驳
        抗议
        解释
        辩解
        追问
        自嘲
        喃喃
        低语
        轻语
        故作
        假装
        克制
        夸张
        正经
        戏谑
        亲昵

        # ---- 音量 ----
        音量
        低声
        小声
        轻声
        高声
        高亢
        洪亮
        压低
        放轻
        加大
        提高
        适中
        偏轻
        偏重
        响亮
        微弱
        尖叫
        大喊
        忽高忽低
        高低
        强弱

        # ---- 语速 ----
        语速
        放慢
        加快
        拖长
        利落
        干脆
        连珠炮
        慢条斯理
        一口气
        断断续续
        匀速
        加速
        减速
        飞快
        缓缓
        徐徐
        慢慢
        渐渐
        急速
        平缓
        顿挫

        # ---- 音色 ----
        音色
        声音
        嗓音
        清亮
        低沉
        沙哑
        磁性
        干净
        清澈
        醇厚
        尖细
        圆润
        绵软
        清脆
        浑厚
        空灵
        飘渺
        单薄
        虚弱
        饱满
        通透
        粗糙
        嘶哑
        低哑
        浑浊
        明亮
        厚实
        纤细
        甜美
        奶气
        金属感
        颗粒感
        少年感
        成熟感

        # ---- 气息 ----
        气息
        换气
        呼吸
        喘息
        气声
        鼻音
        笑意
        叹息
        拖尾
        停顿
        断句
        吸气
        屏息
        喉音
        含着笑
        憋着笑
        叹气
        深吸
        呼气
        抽气
        粗喘
        轻喘
        气口
        咽下
        吞咽
        哽住
        抽噎
        哽咽
        鼻腔
        底气

        # ---- 情绪 ----
        情绪
        愤怒
        生气
        不满
        悲伤
        难过
        开心
        雀跃
        委屈
        宠溺
        无奈
        得意
        心疼
        紧张
        厌倦
        期待
        害羞
        傲娇
        心急
        失落
        释然
        关切
        关心
        担心
        挑衅
        欣慰
        感动
        温暖
        甜蜜
        幸福
        满足
        希望
        憧憬
        怀念
        思念
        依恋
        牵挂
        担忧
        焦虑
        不安
        慌乱
        惶恐
        恐惧
        害怕
        惊恐
        震惊
        惊讶
        意外
        疑惑
        困惑
        茫然
        惆怅
        郁闷
        烦闷
        烦躁
        恼怒
        愤慨
        羞愧
        尴尬
        窘迫
        局促
        羞涩
        腼腆
        自豪
        骄傲
        自信
        坚定
        从容
        淡定
        镇定
        冷静
        麻木
        疲惫
        疲倦
        乏力
        孤寂
        寂寞
        孤独
        凄凉
        悲凉
        哀伤
        痛苦
        心酸
        苦涩
        轻松
        放松
        欢快
        活泼
        顽皮
        调皮
        狡黠
        机灵
        古怪
        神秘
        压抑
        隐忍
        强忍

        # ---- 程度 / 修饰 ----
        有点
        略微
        微微
        稍稍
        淡淡
        轻轻
        十分
        非常
        极其
        一点
        几分
        些许
        稍微
        格外
        尤其
        略带
        若有若无
        若有似无
        若隐若现
        大概
        大约
        几乎
        差不多
        相当
        颇为
        稍显
        略略
        愈发
        越发
        无比
        极为
        分外
        特别
        如此
        这么
        那么
        着实
        实在
        真正
        纯粹
        全然
        完全
        彻底
        一点点
        一丝
        一度
        再三
        略微带

        # ---- 虚词 / 连接 / 介副（同样只收 2 字以上的词，单字走兜底）----
        所以
        因为
        因此
        如果
        虽然
        然后
        带着
        透出
        显得
        让人
        感觉
        听起来
        读起来
        装作
        故意
        似乎
        好像
        仿佛
        像是
        之间
        之后
        之前
        时刻
        时候
        并且
        或者
        还是
        要是
        即使
        尽管
        无论
        不管
        只要
        只有
        除非
        接着
        随后
        于是
        终于
        最终
        首先
        其次
        最后
        其实
        实际
        确实
        显然
        当然
        未必
        或许
        也许
        宛如
        犹如
        好比
        恰似
        连同
        以及
        关于
        对于
        根据
        按照
        通过
        起来
        下去
        出来
        上来
        过来
        从来
        向来
        一向
        一直
        偶尔
        有时
        时常
        总是
        从不
        曾经
        立刻
        马上
        赶紧
        急忙
        匆忙
        悄悄
        静静
        默默
        独自
        互相
        彼此
        一起
        一同
        一边
        一面
        哪怕
        就算
        假如
        若是
        以便
        以免
        而且
        反而
        然而
        否则
        不然
        何况
        况且
        甚至
        不仅
        不过
        反倒

        # ---- 常见动词（描述声音与动作）----
        念叨
        私语
        诉说
        倾诉
        低吟
        吟唱
        轻唱
        哼唱
        呼唤
        安慰
        劝说
        叮嘱
        嘱咐
        提醒
        询问
        回答
        回应
        应声
        附和
        重复
        放缓
        收住
        咬字
        吐字
        发音
        清喉
        轻笑
        冷笑
        苦笑
        微笑
        大笑
        嗤笑
        干笑
        抽泣
        呜咽
        颤抖
        发抖
        打颤
        收短
        顿住
        停住
        收紧
        绷紧
        咬紧
        吞吐
        支吾
        想念
        放低
        提升
        转调
        换调
        变调
        收声
        开口
        沉声
        出声
        应和
        哄劝
        念出
        说出
        唱出
        读出
        压下
        屏住
        收尾
        落下
        顿一下
        沉吟
        沉思
        反驳道

        # ---- TTS 领域词 ----
        语音
        朗读
        播报
        台词
        旁白
        口播
        氛围
        质感
        层次
        起伏
        节奏
        韵律
        抑扬顿挫
        语流
        语感
        节奏感
        情绪色彩
        张力
        控制
        调整
        渲染
        演绎
        表演
        表现
        传达
        呈现
        音调
        声调
        重音
        轻读
        重读
        连读
        停连
        合成
        配音
        语料
        语气词
        音质
        音域
        音高

        # ---- 补充：常见名词 ----
        心情
        心态
        表情
        眼神
        神态
        姿态
        动作
        场景
        场面
        故事
        对话
        独白
        内心
        听众
        观众
        角色
        人物
        形象
        性格
        气质
        风格
        味道
        印象
        效果
        问题
        情况
        状态
        过程
        结果
        原因
        方式
        程度
        方向
        位置
        时间
        瞬间
        片刻
        一会儿
        一阵
        一次
        一半
        全部
        整个
        所有
        每个
        各种
        许多
        很多
        部分
        其余
        其他
        另外
        此外
        别人
        自己
        对方
        有人
        没人
        什么
        怎么
        这样
        那样
        这种
        那种

        # ---- 补充：常见形容词 / 副词（描述声音质感与表达方式）----
        一样
        一般
        不同
        相同
        类似
        相似
        简单
        复杂
        明显
        模糊
        清晰
        轻微
        强烈
        剧烈
        沉静
        安静
        寂静
        喧闹
        热闹
        冷清
        柔和
        湿润
        干涩
        迟缓
        轻盈
        沉重
        空白
        平淡
        平直
        短促
        生硬
        僵硬
        流畅
        顺滑
        磕绊
        犹豫
        迟疑
        含糊
        明了
        明确
        果断
        委婉
        直接
        坦率
        诚恳
        真诚
        思考
        回忆
        叙述
        描述
        表达
        语言
        文字
        句子
        段落
        开头
        结尾
        中间
        重点
        细节
        整体
        局部
        普通
        平凡
        特殊
        优秀
        完美
        完整
        简短
        冗长
        具体
        抽象
        真实
        自然
        真是
        说不定
        反正
        到底
        究竟
        居然
        竟然
        果然
        难怪
        怪不得
        分明
        偏偏
        幸好
        可惜
        难得
        顺便
        特意
        专门
        好心
        # 刻意不收「X地」这类派生词：副词和动词分开算两个词组，才如实反映描述里的信息量；
        # 把「平静地」当成一个词反而会让「平静地念」看起来像一句完整描述。
    """.trimIndent()

    private val DICTIONARY: Set<String> = RAW_DICTIONARY
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toSet()
}
