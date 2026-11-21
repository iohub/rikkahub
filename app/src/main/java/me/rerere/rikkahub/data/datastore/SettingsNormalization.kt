package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.model.AssistantOrchestration
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

/** 读盘后和每次修改后都要过一遍，保证内存里的设置始终是规整的。 */
internal fun Settings.normalized(): Settings =
    withBuiltInDefaults().withoutInvalidReferences().withValuesInRange().withUsableTTSProvider()

// 补齐缺失的内置提供商/助手/TTS，内置提供商和推荐提供商的描述信息始终以代码里的为准
internal fun Settings.withBuiltInDefaults(): Settings {
    var providers = this.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
    DEFAULT_PROVIDERS.forEach { defaultProvider ->
        if (providers.none { it.id == defaultProvider.id }) {
            providers.add(defaultProvider.copyProvider())
        }
    }
    providers = providers.map { provider ->
        val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
        val recommendedProvider = RECOMMENDED_PROVIDERS.find { it.id == provider.id }
        when {
            defaultProvider != null -> provider.copyProvider(
                builtIn = defaultProvider.builtIn,
                description = defaultProvider.description,
                shortDescription = defaultProvider.shortDescription,
            )

            // 描述不落盘，从推荐列表添加的提供商读盘后按 id 补回
            recommendedProvider != null -> provider.copyProvider(
                description = recommendedProvider.description,
                shortDescription = recommendedProvider.shortDescription,
            )

            else -> provider
        }
    }.toMutableList()
    // 内置助手可以删除，只在一个都不剩时补回，不按 id 逐个补齐
    val assistants = this.assistants.ifEmpty { DEFAULT_ASSISTANTS }
    val ttsProviders = this.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
    DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
        if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
            ttsProviders.add(defaultTTSProvider.copyProvider())
        }
    }
    return copy(
        providers = providers,
        assistants = assistants,
        ttsProviders = ttsProviders,
    )
}

// 去重并清理无效引用
internal fun Settings.withoutInvalidReferences(): Settings {
    val settings = this
    val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
    val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
    val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
    val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
    val validModelIds by lazy { settings.providers.flatMap { it.models }.mapTo(HashSet()) { it.id } }
    val asrProviders = settings.asrProviders.distinctBy { it.id }
    return settings.copy(
        // 选中的助手被删除后改选第一个，避免直接读 assistantId 的地方拿到失效的 ID
        assistantId = settings.assistantId
            .takeIf { id -> settings.assistants.any { it.id == id } }
            ?: settings.assistants.firstOrNull()?.id
            ?: settings.assistantId,
        providers = settings.providers.distinctBy { it.id }.map { provider ->
            provider.copyProvider(models = provider.models.distinctBy { model -> model.id })
        },
        assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
            assistant.copy(
                // 过滤掉不存在的 MCP 服务器 ID
                mcpServers = assistant.mcpServers.filter { serverId ->
                    serverId in validMcpServerIds
                }.toSet(),
                // 过滤掉不存在的模式注入 ID
                modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                    id in validModeInjectionIds
                }.toSet(),
                // 过滤掉不存在的 Lorebook ID
                lorebookIds = assistant.lorebookIds.filter { id ->
                    id in validLorebookIds
                }.toSet(),
                // 过滤掉不存在的快捷消息 ID
                quickMessageIds = assistant.quickMessageIds.filter { id ->
                    id in validQuickMessageIds
                }.toSet(),
                // 编排阶段里指向已删模型的引用同样要清掉
                orchestration = assistant.orchestration.withoutInvalidReferences(validModelIds),
            )
        },
        ttsProviders = settings.ttsProviders.distinctBy { it.id },
        asrProviders = asrProviders,
        selectedASRProviderId = settings.selectedASRProviderId
            ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
            ?: asrProviders.firstOrNull()?.id,
        favoriteModels = settings.favoriteModels.filter { it in validModelIds }.distinct(),
        modeInjections = settings.modeInjections.distinctBy { it.id },
        lorebooks = settings.lorebooks.distinctBy { it.id },
        quickMessages = settings.quickMessages.distinctBy { it.id },
        // 端口转发按 id 定位，id 为空的条目（没保存过的草稿）和重复 id 都留不住运行状态，直接丢弃
        sshTunnels = settings.sshTunnels.filter { it.id.isNotBlank() }.distinctBy { it.id },
    )
}

// 把取值收回合法范围，比如删掉搜索服务后选中的下标可能越界
private fun Settings.withValuesInRange(): Settings = copy(
    searchServiceSelected = searchServiceSelected.coerceIn(0, (searchServices.size - 1).coerceAtLeast(0)),
    defaultTTSPlaybackSpeed = defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f),
    autoCompressThreshold = autoCompressThreshold.coerceIn(AUTO_COMPRESS_THRESHOLD_RANGE),
    // 只把越界的端口收回来：0 表示"还没填"，仍交给校验提示用户，不能悄悄改成别的端口
    sshTunnels = sshTunnels.map { tunnel ->
        tunnel.copy(
            port = tunnel.port.coerceIn(1, 65535),
            localPort = tunnel.localPort.coerceIn(0, 65535),
            remotePort = tunnel.remotePort.coerceIn(0, 65535),
        )
    },
    ttsProviders = ttsProviders.map { provider -> provider.withValuesInRange() },
    // 编排阶段的取值收敛挂在助手下面，与其它助手级配置一样在读盘后统一过一遍
    assistants = assistants.map { assistant ->
        assistant.copy(orchestration = assistant.orchestration.withValuesInRange())
    },
)

/**
 * 编排阶段里失效的模型引用清空。
 *
 * 删掉模型后不清理的话，阶段会一直拿一个已不存在的 id 去生成，只能在运行期报「找不到模型」——
 * 与助手自身的 `chatModelId` 同一种处理：清成 null 就退回「跟随会话模型」。
 *
 * 按 id 去重是为了挡住复制助手时的重复阶段。这里不需要像 `sshTunnels` 那样额外过滤空 id：
 * `AgentStage.id` 是 [Uuid]，落盘的空串在反序列化阶段就已经失败了，走不到归一化。
 */
private fun AssistantOrchestration.withoutInvalidReferences(validModelIds: Set<Uuid>): AssistantOrchestration =
    copy(
        stages = stages.distinctBy { it.id }.map { stage ->
            stage.copy(
                modelId = stage.modelId?.takeIf { it in validModelIds },
                reviewer = stage.reviewer.copy(
                    modelId = stage.reviewer.modelId?.takeIf { it in validModelIds },
                ),
            )
        },
    )

/**
 * 编排阶段的取值收敛：只收越界值，合法值原样保留。
 *
 * - `maxRetry` 收到 0..[MAX_REVIEWER_RETRY]：再多也只是把等待时间翻倍；
 * - `temperature` 收到 0..2，这是各家 API 通用的量程；
 * - `maxTokens` 小于 1 等于让模型什么都不说，直接置 null 走默认；上限只拦明显手滑的巨大值。
 */
private fun AssistantOrchestration.withValuesInRange(): AssistantOrchestration = copy(
    stages = stages.map { stage ->
        stage.copy(
            temperature = stage.temperature?.coerceIn(MIN_STAGE_TEMPERATURE, MAX_STAGE_TEMPERATURE),
            maxTokens = stage.maxTokens
                ?.takeIf { it > 0 }
                ?.coerceAtMost(MAX_STAGE_MAX_TOKENS),
            reviewer = stage.reviewer.copy(
                maxRetry = stage.reviewer.maxRetry.coerceIn(0, MAX_REVIEWER_RETRY),
            ),
        )
    },
)

/** 编排阶段温度的合法区间 */
private const val MIN_STAGE_TEMPERATURE = 0f
private const val MAX_STAGE_TEMPERATURE = 2f

/** 编排阶段输出上限的天花板：超过这个数绝对不是正常配置，只可能是输入错误 */
private const val MAX_STAGE_MAX_TOKENS = 32_768

/** 编排阶段审查的重试次数上限：再多也只是把用户的等待时间翻倍 */
private const val MAX_REVIEWER_RETRY = 3

/**
 * 远程 TTS 的输出格式只有服务端列出的几种。
 *
 * 历史数据里可能残留 `mp3/ogg/opus/aac` 之类（服务端不认，合成会直接失败），
 * 或任意采样率；读盘时统一收回到 wav / 24000。合法值原样保留。
 */
private fun TTSProviderSetting.withValuesInRange(): TTSProviderSetting {
    if (this !is TTSProviderSetting.RemoteTTS) return this
    val supportedFormats = TTSProviderSetting.RemoteTTS.REMOTE_TTS_RESPONSE_FORMATS
    val supportedSampleRates = TTSProviderSetting.RemoteTTS.REMOTE_TTS_SAMPLE_RATES
    return copy(
        responseFormat = responseFormat.trim().lowercase().takeIf { it in supportedFormats } ?: "wav",
        sampleRate = sampleRate.takeIf { it in supportedSampleRates } ?: 24000,
        cacheMaxMb = cacheMaxMb.coerceIn(32, 4096),
    )
}

/**
 * 内置系统 TTS 关闭时不选中它，选中的 ID 已失效时按 [fallbackTTSProvider] 的顺序改选。
 *
 * 放在最后：先补齐内置 TTS、去重，回退时列表里才有默认的远程 TTS 可选。
 */
private fun Settings.withUsableTTSProvider(): Settings {
    val selected = ttsProviders.find { it.id == selectedTTSProviderId }
    if (selected != null && (enableBuiltInTts || selected !is TTSProviderSetting.SystemTTS)) return this
    val fallback = ttsProviders.fallbackTTSProvider(allowSystemTTS = enableBuiltInTts) ?: return this
    return if (fallback.id == selectedTTSProviderId) this else copy(selectedTTSProviderId = fallback.id)
}

/** 自动压缩触发比例的可选范围，设置页的滑块也用它 */
val AUTO_COMPRESS_THRESHOLD_RANGE = 0.5f..0.95f
