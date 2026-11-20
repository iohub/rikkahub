package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.RetryAttempt
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.expandNotices
import me.rerere.ai.agent.withRetry
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.buildMemoryPrompt
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.TtsVoiceControlTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transformThinkTags
import me.rerere.rikkahub.data.ai.transformers.transformTtsVoiceControl
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.tts.provider.TTSManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.SocketTimeoutException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val TAG = "ChatRequestFactory"

/** logcat 单条日志约 4000 字符上限，长文本按这个长度切块输出 */
private const val LOG_CHUNK_SIZE = 3000

/** 分块输出长文本，避免 logcat 单条日志被截断 */
private fun logLongText(label: String, text: String) {
    Log.d(TAG, "===== $label (${text.length} chars) =====")
    if (text.isEmpty()) {
        Log.d(TAG, "<empty>")
    } else {
        var index = 0
        while (index < text.length) {
            val end = minOf(index + LOG_CHUNK_SIZE, text.length)
            Log.d(TAG, text.substring(index, end))
            index = end
        }
    }
    Log.d(TAG, "===== end of $label =====")
}

/**
 * 一轮对话的请求上下文，按会话固定的配置解析一次。
 *
 * 正常对话和压缩用同一份上下文组装请求，系统提示词、工具定义、参数和 sessionId 都相同，
 * 压缩请求的前缀才能和上一轮对话一致，命中提示词缓存。
 */
class ChatTurnContext(
    val settings: Settings,
    val assistant: Assistant,
    val model: Model,
    val providerSetting: ProviderSetting,
    val conversationId: Uuid,
    val conversationSystemPrompt: String?,
    val workspaceCwd: String?,
    val memories: List<MemoryFile>,
    val tools: List<Tool>,
)

/**
 * 把会话变成发给模型的请求：系统提示词、上下文裁剪、输入输出转换都在这里
 *
 * 用 [KoinComponent] 只是为了拿 [TTSManager] 读当前 provider 的语气标记引导（`getPromptGuidance` 是实例方法
 * 且依赖 provider 内部状态，没有伴生入口）。构造签名保持不变 —— 本类由 Koin 用显式参数构造。
 */
class ChatRequestFactory(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val chatToolFactory: ChatToolFactory,
    private val memoryRepository: MemoryRepository,
    private val sshProxyTunnelResolver: SshProxyTunnelResolver,
    templateTransformer: TemplateTransformer,
    workspaceRepository: WorkspaceRepository,
) : KoinComponent {
    /** 惰性解析：本类被构造时不触发 Koin 查找，避免单测里未启动 Koin 时直接炸在构造阶段 */
    private val ttsManager: TTSManager by inject()

    private val inputTransformers: List<InputMessageTransformer> = listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        templateTransformer,
        WorkspaceReminderTransformer(workspaceRepository),
    )

    private val outputTransformers: List<OutputMessageTransformer> = listOf(
        ThinkTagTransformer,
        // 必须排在 ThinkTag 之后：它只 trimEnd，保留正文前导空白，ThinkTag 的 `\A\s*<think>` 才不会失效
        TtsVoiceControlTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )

    /** 解析本轮的助手、模型、记忆和工具；MCP 服务名不合法时抛出 InvalidMcpServerNamesException */
    suspend fun createContext(settings: Settings, conversation: Conversation): ChatTurnContext {
        // 模型、思考级别、搜索、工具等以会话上固定的配置为准
        val assistant = settings.getAssistantOf(conversation)
        val model = settings.getChatModelOf(conversation)
            ?: throw IllegalStateException("No chat model selected")
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        return ChatTurnContext(
            settings = settings,
            assistant = assistant,
            model = model,
            providerSetting = provider,
            conversationId = conversation.id,
            conversationSystemPrompt = conversation.customSystemPrompt,
            workspaceCwd = conversation.workspaceCwd,
            memories = if (assistant.enableMemory && !assistant.promptInjectionOverride.isMemoryDisabled) {
                memoryRepository.getFiles(assistant.memoryId)
            } else {
                emptyList()
            },
            tools = chatToolFactory.createTools(
                settings = settings,
                assistant = assistant,
                model = model,
                conversationId = conversation.id,
                workspaceCwd = conversation.workspaceCwd,
            ),
        )
    }

    /**
     * 本轮交给 runAgent 的请求和钩子。[messages] 的最后一条是助手消息时从它继续，否则新建一条助手消息。
     * [processingStatus] 用来显示 OCR 这类输入转换的进度；[takePendingParts] 取出要在请求模型前写进助手消息的通知。
     * [pauseAfterTools] 在一批工具执行完之后调用，返回 true 时这次运行以 Paused 结束。
     */
    fun createAgent(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        processingStatus: MutableStateFlow<String?>,
        takePendingParts: suspend () -> List<UIMessagePart> = { emptyList() },
        pauseAfterTools: (UIMessage) -> Boolean = { false },
    ): Pair<AgentRequest, AgentHooks> {
        val request = AgentRequest(
            provider = usageRecorder.wrap(
                providerManager.getProviderByType(turn.providerSetting),
                UsageTag(UsageSource.CHAT, turn.assistant.id, turn.conversationId),
            ),
            providerSetting = turn.providerSetting,
            messages = messages,
            tools = turn.tools,
            stream = turn.assistant.streamOutput,
            retry = turn.settings.retryPolicy(),
            requestTimeout = turn.settings.networkSetting.requestTimeoutSeconds
                .takeIf { it > 0 }?.seconds,
            // 上一次尝试因超时/断连失败时，先重建被代理的隧道再重新提交
            beforeAttempt = { attempt -> reconnectTunnelBeforeRetry(turn, attempt) },
        )
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
                Log.i(TAG, "prepareStep: ${turn.model.id}")
                return buildStep(turn, messages, processingStatus)
            }

            override suspend fun previewStep(message: UIMessage, newPartsFrom: Int) =
                message.transformNewParts(newPartsFrom) {
                    it.visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                }

            override suspend fun finishStep(message: UIMessage, newPartsFrom: Int) =
                message.transformNewParts(newPartsFrom) {
                    it.transforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                        .visualTransforms(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                        .onGenerationFinish(outputTransformers, context, turn.model, turn.assistant, turn.settings)
                }

            override suspend fun pendingParts() = takePendingParts()

            override suspend fun shouldPause(message: UIMessage) = pauseAfterTools(message)
        }
        return request to hooks
    }

    /**
     * 每次尝试发起请求之前的准备：上一次是超时或断连，且这次请求经 SSH 隧道代理时，先重建那条隧道。
     *
     * 隧道会话断开后本地端口照样 accept，请求会一直失败到重试次数用尽，重建隧道才能真的恢复。
     * 这里**绝不能抛异常**：它不在 [withRetry] 的请求失败处理里，抛出去会直接结束整轮生成。
     */
    private suspend fun reconnectTunnelBeforeRetry(turn: ChatTurnContext, attempt: RetryAttempt?) {
        // 第一次尝试没有失败信息，也没必要动隧道
        if (attempt == null || !turn.settings.networkSetting.autoReconnectSshTunnel) return
        try {
            if (!sshProxyTunnelResolver.shouldReconnect(attempt.cause)) return
            val tunnelId = sshProxyTunnelResolver.tunnelIdFor(turn.providerSetting, turn.model, turn.settings)
                ?: return
            Log.i(
                TAG,
                "Retry ${attempt.retry}/${attempt.maxRetries} after " +
                    "${attempt.cause.javaClass.simpleName}, reconnecting ssh tunnel $tunnelId",
            )
            val running = sshProxyTunnelResolver.reestablish(tunnelId)
            Log.i(TAG, "SSH tunnel $tunnelId reconnected: running=$running")
        } catch (e: CancellationException) {
            // 用户停止生成时必须继续中断，不能被当成隧道故障吞掉
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reconnect ssh tunnel before retry", e)
        }
    }

    /**
     * 生成压缩摘要。
     *
     * 请求按正常对话组装（系统提示词、工具定义、参数和 sessionId 都相同），只在末尾追加压缩指令，
     * 前缀与上一轮对话一致，可以命中提示词缓存，模型也能看到工具调用和结果。
     * 工具定义必须保留：它属于请求前缀，去掉或改动 tool_choice 都会让缓存失效。
     */
    suspend fun generateCompressionSummary(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        compressPrompt: String,
    ): String {
        // 组装请求要把全部历史过一遍输入转换器，长对话要算好几秒：不能放在调用方的主线程上，
        // 这段纯计算里也检查不到取消。放进独立的协程，调用方取消时 await 立刻返回、不用等它算完；
        // 没算完的那份同时被取消，在下一个挂起点停下，最迟在发出请求之前
        val work = CoroutineScope(Dispatchers.IO).async { requestCompressionSummary(turn, messages, compressPrompt) }
        return try {
            work.await()
        } finally {
            work.cancel()
        }
    }

    private suspend fun requestCompressionSummary(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        compressPrompt: String,
    ): String {
        val (requestMessages, params) = buildStep(turn, messages)
        // 压缩指令不经过输入转换器，模板转换器会把其中的花括号当成模板语法
        val result = withRetry(
            policy = turn.settings.retryPolicy(),
            onRetry = { Log.w(TAG, "Compression request failed, retrying in ${it.delay}", it.cause) },
            // 压缩请求和正常对话走同一条网络路径，隧道断了也要重建
            beforeAttempt = { attempt -> reconnectTunnelBeforeRetry(turn, attempt) },
        ) {
            usageRecorder.wrap(
                providerManager.getProviderByType(turn.providerSetting),
                UsageTag(UsageSource.COMPRESSION, turn.assistant.id, turn.conversationId),
            ).generateText(
                providerSetting = turn.providerSetting,
                messages = requestMessages + UIMessage.user(compressPrompt),
                // 助手的输出上限是给聊天回复定的，结构化摘要写不完会被截断；max_tokens 不属于请求前缀，改了不影响缓存
                params = params.copy(maxTokens = null),
            )
        }
        val summary = result.message.compressionSummaryOrNull()
            ?: error(context.getString(R.string.chat_page_compress_tool_call_response))
        return summary.ifBlank { error("Failed to generate compressed summary") }
    }

    /**
     * 当前选中 TTS provider 自带的语气标记引导（RemoteTTS 的「只吃纯文本、不支持内联情绪标签」、
     * MiMo 的 `(开心 磁性)` 标记语法等）。
     *
     * 语气控制块不再挂在 `text_to_speech` 工具上之后，provider 之间的差异只能在这里补进 system prompt。
     * 解析失败（Koin 未启动、没有可用 provider）时退化为空串：调用方只注入通用规则，请求照常发出，
     * 不能因为读不到语气引导就把整轮对话打断。
     */
    private fun ttsProviderGuidance(turn: ChatTurnContext): String {
        return try {
            turn.settings.getSelectedTTSProvider()
                ?.let { provider -> ttsManager.getPromptGuidance(provider) }
                .orEmpty()
                .trim()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve tts prompt guidance, injecting rules only", e)
            ""
        }
    }

    /** 一步请求实际发出的消息和参数；正常对话和压缩共用，二者的请求前缀必须一致才能命中提示词缓存 */
    private suspend fun buildStep(
        turn: ChatTurnContext,
        messages: List<UIMessage>,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    ): StepRequest {
        val assistant = turn.assistant
        // 开关状态摘要：打印的是 disableAll 与各子项合并后的结果，一眼看出哪些注入被关掉了
        val override = assistant.promptInjectionOverride
        Log.d(
            TAG,
            "promptInjectionOverride[conv=${turn.conversationId}]: " +
                "disableAll=${override.disableAll}, " +
                "memory=${override.isMemoryDisabled}, " +
                "toolPrompts=${override.isToolPromptsDisabled}, " +
                "modeInjections=${override.isModeInjectionsDisabled}, " +
                "workspace=${override.isWorkspaceDisabled}, " +
                "timeReminder=${override.isTimeReminderDisabled}, " +
                "documentAsPrompt=${override.isDocumentAsPromptDisabled}, " +
                "ocr=${override.isOcrDisabled}, " +
                "placeholders=${override.isPlaceholdersDisabled}"
        )
        // 把拼装态的 system 带出 buildList 作用域，供转换前的日志使用
        var assembledSystem = ""
        val requestMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !turn.conversationSystemPrompt.isNullOrBlank()) {
                        turn.conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 记忆
                if (assistant.enableMemory && !assistant.promptInjectionOverride.isMemoryDisabled) {
                    appendLine()
                    append(buildMemoryPrompt(turn.memories))
                }
                // 工具prompt
                if (!assistant.promptInjectionOverride.isToolPromptsDisabled) {
                    turn.tools.forEach { tool ->
                        appendLine()
                        append(tool.systemPrompt(turn.model, messages))
                    }
                }
                // TTS 语气控制块：重构后它不是工具提示词，所以不受 isToolPromptsDisabled 门控，
                // 只看「助手开了 TTS 本地工具」与注入总开关
                if (LocalToolOption.Tts in assistant.localTools && !override.disableAll) {
                    appendLine()
                    append(ttsVoiceControlPrompt(ttsProviderGuidance(turn)))
                }
            }
            assembledSystem = system
            if (system.isNotBlank()) {
                add(UIMessage.system(prompt = system).copy(isSynthetic = true))
            }
            addAll(messages.limitContext(assistant.contextMessageLimit).withContinuationHint())
        }.transforms(
            transformers = inputTransformers,
            context = context,
            model = turn.model,
            assistant = assistant,
            settings = turn.settings,
            processingStatus = processingStatus,
            workspaceCwd = turn.workspaceCwd,
        ).expandNotices()
        // 转换前的 system：用户 systemPrompt + 记忆 + 工具 prompt 三段，验证记忆/工具两个开关最直接
        logLongText("system prompt (assembled, before transformers)[conv=${turn.conversationId}]", assembledSystem)
        // 转换后的终态 system：模式注入/世界书/占位符替换/workspace 等改动后的真实内容
        val finalSystem = requestMessages
            .filter { it.role == MessageRole.SYSTEM }
            .joinToString(separator = "\n\n") { it.toText() }
        logLongText("system prompt (final, after transformers)[conv=${turn.conversationId}]", finalSystem)
        // 工具定义不在 system 里，但同为模型可见指令：补一行名字摘要便于判断工具是否还在
        Log.d(
            TAG,
            "request tools[conv=${turn.conversationId}]: count=${turn.tools.size}, " +
                "names=${turn.tools.joinToString(",") { it.name }}"
        )
        val params = TextGenerationParams(
            model = turn.model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = turn.tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(turn.model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(turn.model.customBodies)
            },
            sessionId = turn.conversationId.toString(),
        )
        return StepRequest(requestMessages, params)
    }
}

private const val CONTINUATION_HINT =
    "The conversation history before this point was replaced by the summary above. " +
        "If a task was in progress, continue it from where it left off: do not reply to the summary itself, " +
        "and do not ask the user to repeat anything."

/**
 * 检查点后面不是用户消息时（一轮中途压缩，后面直接是助手的回复或什么都没有），告诉模型接着干活，
 * 否则它会把摘要当成用户发来的内容去回应。
 *
 * 按"后面不是用户消息"而不是"在末尾"判断：这一轮之后的请求里检查点仍然带着这段话，前缀不变，不影响提示词缓存。
 */
internal fun List<UIMessage>.withContinuationHint(): List<UIMessage> {
    val index = indexOfLast { it.isContextCheckpoint }
    if (index < 0 || getOrNull(index + 1)?.role == MessageRole.USER) return this
    val checkpoint = this[index]
    return toMutableList().also {
        it[index] = checkpoint.copy(parts = checkpoint.parts + UIMessagePart.Text(CONTINUATION_HINT))
    }
}

/**
 * 取出压缩请求回复里的摘要正文。
 *
 * 指令要求不调用工具，模型仍可能发起调用。这时附带的文本只是"我先看看文件"之类的说明，
 * 当成摘要保存会让之后的请求丢掉全部历史，所以返回 null 交给调用方报错。
 * 压缩请求不经过输出转换器，正文开头的 <think> 思考内容与正文之后单独成段的 <tts_voice_control>
 * 语气块也要在这里剥掉，否则会存进摘要（这两条规则在压缩请求的 system prompt 里同样存在）。
 */
internal fun UIMessage.compressionSummaryOrNull(): String? {
    if (getTools().isNotEmpty()) return null
    return listOf(this)
        .transformThinkTags(now = Clock.System.now(), generationFinished = true)
        .transformTtsVoiceControl()
        .single()
        .toText()
        .trim()
}

/**
 * 输出转换只处理这一步新生成的 part：之前步骤的内容已经处理过，再处理一遍会让正则这类改写叠加，
 * 也能让每一步开头的 <think> 都被识别出来。
 */
internal suspend fun UIMessage.transformNewParts(
    newPartsFrom: Int,
    transform: suspend (List<UIMessage>) -> List<UIMessage>,
): UIMessage {
    val transformed = transform(listOf(copy(parts = parts.drop(newPartsFrom)))).single()
    return transformed.copy(parts = parts.take(newPartsFrom) + transformed.parts)
}

/** 标题、建议、记忆整理这类后台请求的参数：只带模型自己的自定义请求头和请求体 */
internal fun backgroundTextGenerationParams(
    model: Model,
    sessionId: Uuid,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
    sessionId = sessionId.toString(),
)

/**
 * 请求模型失败时的重试策略。
 *
 * 请求超时（networkSetting.requestTimeoutSeconds）会把被终止的请求转成 [SocketTimeoutException]
 * 交给这里处理，所以关掉网络自动重试但启用了超时时，仍要保留「超时后自动重新提交」这一条。
 */
internal fun Settings.retryPolicy(): RetryPolicy {
    val timeoutSeconds = networkSetting.requestTimeoutSeconds
    return when {
        networkSetting.enableAutoRetry -> RetryPolicy.NETWORK
        timeoutSeconds > 0 -> RetryPolicy(
            maxRetries = 3,
            retryOn = { it is SocketTimeoutException },
        )
        else -> RetryPolicy.NONE
    }
}
