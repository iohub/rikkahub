package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.isAwaitingToolApproval
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

/** 会话的持久化，由 ChatService 实现 */
interface ConversationStore {
    /**
     * 落库前对会话的补全（比如把助手配置固定到会话上），结果同样写回内存。
     * 返回的变换在最新状态上原子执行，不能挂起。
     */
    suspend fun prepareWrite(): (Conversation) -> Conversation

    /** [previous] 是上次落库或加载时的状态，从未落库的新会话为 null */
    suspend fun write(previous: Conversation?, current: Conversation)
}

/**
 * 一个会话在内存中的状态，加载之后它就是唯一的权威：所有修改都在最新状态上做变换，
 * 落库时写入内存此刻的内容，不接受外部传入的整份快照，数据库里的旧版本也不会再读回来覆盖内存。
 */
class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val store: ConversationStore,
    private val onIdle: (Uuid) -> Unit,
    private val onGenerationFinished: (Uuid, Throwable?) -> Unit = { _, _ -> },
) {
    // 会话状态
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<Conversation> = _state.asStateFlow()
    private val initializationMutex = Mutex()
    private val writeMutex = Mutex()

    @Volatile
    var isInitialized = false
        private set

    // 上次落库、加载或新建时的状态，内存还是这个实例就说明没有要落库的修改；只在 writeMutex 内修改
    private var lastWritten: Conversation = initial

    @Volatile
    var existsInDatabase = false
        private set

    // 每次 [replace] 加一：跨挂起点的操作据此发现会话已经被删除或整条换掉，不再把结果写进去
    @Volatile
    var incarnation = 0
        private set

    val inbox = ConversationInbox()

    /**
     * 页面切换和 SSE 重连只加载一次。[load] 返回 null 表示数据库里没有这个会话，改用 [create] 新建。
     * 加载完成前会话不接受修改，所以不会出现加载结果覆盖新内容的情况。
     */
    suspend fun initialize(
        load: suspend () -> Conversation?,
        create: suspend () -> Conversation = { throw IllegalStateException("Conversation $id not found") },
    ) {
        initializationMutex.withLock {
            if (isInitialized) return
            val loaded = load()
            val conversation = loaded ?: create()
            require(conversation.id == id)
            writeMutex.withLock {
                lastWritten = conversation
                existsInDatabase = loaded != null
            }
            synchronized(this) {
                _state.value = conversation
                isInitialized = true
            }
        }
    }

    /** 在最新状态上修改会话，只改内存；需要落库时之后调用 [flush] */
    fun update(transform: (Conversation) -> Conversation): Conversation = checkNotNull(tryUpdate(transform))

    /** 同 [update]，[transform] 返回 null 表示没什么可改的，这时不修改并返回 null */
    @Synchronized
    fun tryUpdate(transform: (Conversation) -> Conversation?): Conversation? {
        check(isInitialized) { "Conversation $id is not loaded yet" }
        val updated = transform(_state.value) ?: return null
        require(updated.id == id)
        _state.value = updated
        return updated
    }

    /**
     * 把内存此刻的状态落库。落库串行执行，每次都读取最新状态，后执行的一定带着更新的内容；
     * 和上次落库相比没有修改，或者是还没内容的新会话时不写。
     */
    suspend fun flush(): Conversation {
        if (!isInitialized) return state.value
        return writeMutex.withLock {
            val latest = state.value
            if (latest === lastWritten) return@withLock latest
            // 还没内容的新会话不落库，也不能提前固定配置：会话开始前聊天页的修改要写回助手
            if (!existsInDatabase && latest.title.isBlank() && latest.messageNodes.isEmpty()) {
                return@withLock latest
            }
            val current = update(store.prepareWrite())
            store.write(previous = lastWritten.takeIf { existsInDatabase }, current = current)
            lastWritten = current
            existsInDatabase = true
            current
        }
    }

    /**
     * 只修改还没落库的新会话，只改内存，首次落库时一并写入；会话已经落库时不修改，返回 false。
     * 在 [flush] 的锁内判断，不会和首次落库交错
     */
    internal suspend fun updateDraft(transform: (Conversation) -> Conversation): Boolean {
        return writeMutex.withLock {
            if (existsInDatabase) return@withLock false
            update(transform)
            true
        }
    }

    /**
     * 只更新部分列的元数据：改内存，再按列落库，不重写消息节点。
     * 修改和落库都在 [flush] 的锁内进行，落库的先后与内存修改的先后一致。
     *
     * @param expectedIncarnation 跨挂起点的操作在开始时记下的 [incarnation]。[replace] 在同一把锁内递增它，
     * 所以在锁内比较才可靠：会话在这期间被删除或整条换掉时不写入，返回 false
     */
    internal suspend fun updateMetadata(
        transform: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
        expectedIncarnation: Int = incarnation,
    ): Boolean {
        return writeMutex.withLock {
            if (incarnation != expectedIncarnation) return@withLock false
            val (before, updated) = synchronized(this) {
                val before = _state.value
                before to update(transform)
            }
            persist(updated)
            // 改之前内存和数据库一致，按列写完两边又一致了，回收时不必整份重写；
            // 之后如果又有别的修改，内存已经不是 updated 这个实例，仍然算作没落库
            if (existsInDatabase && lastWritten === before) lastWritten = updated
            true
        }
    }

    /**
     * 整条替换会话（删除、撤销删除）：先停止并等待所有任务，在锁内改写数据库，再把内存换成与之一致的状态。
     * 页面和 SSE 仍然持有同一个会话对象，直接看到新状态，引用计数也不受影响。
     *
     * @param write 改写数据库；参数是替换前的内存状态，会话还没加载时为 null
     */
    internal suspend fun replace(
        conversation: Conversation,
        existsInDatabase: Boolean,
        write: suspend (previous: Conversation?) -> Unit,
    ) {
        require(conversation.id == id)
        cancelJobs().forEach { it.join() }
        // 正在进行的加载结束后才替换，否则加载结果会盖掉替换后的状态
        initializationMutex.withLock {
            writeMutex.withLock {
                write(state.value.takeIf { isInitialized })
                synchronized(this) {
                    _state.value = conversation
                    isInitialized = true
                    incarnation++
                }
                lastWritten = conversation
                this.existsInDatabase = existsInDatabase
            }
        }
        processingStatus.value = null
        replyMessageId.value = null
        generationStartedAt.value = null
    }

    // 失败和取消也必须保存已收到的内容，且保存完成前不能释放生成任务。
    suspend fun finishGeneration(): Conversation = withContext(NonCancellable) {
        update { current ->
            current.copy(
                messageNodes = current.messageNodes.map { node ->
                    node.copy(messages = node.messages.map { it.finishReasoning() })
                },
                updateAt = Instant.now(),
            )
        }
        flush()
    }

    // 从队列取出到写入会话历史之间，附件仍需作为有效引用保留。
    @Volatile
    var submittingMessage: QueuedMessage? = null
        internal set

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 最近一轮正在写的助手消息，这一轮还没有输出时为空。重新生成较早的消息时它不在会话末尾，
    // 生成期间那个节点还可能被切到别的分支，只能按 id 找
    val replyMessageId = MutableStateFlow<Uuid?>(null)

    // 最近一次生成任务开始的时间，用来认出这一轮报的错。在任务启动前同步记下，任务里报的错不会早于它
    val generationStartedAt = MutableStateFlow<Long?>(null)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    private val activeJobs = mutableSetOf<Job>()
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true

    // 任务槽里的任务在做什么；槽空着时是 null。界面靠它区分生成回复和压缩上下文
    private val _activity = MutableStateFlow<SessionActivity?>(null)
    val activity: StateFlow<SessionActivity?> = _activity.asStateFlow()

    /**
     * 能否自动开始下一轮（收件箱派发、自动压缩）：任务槽空着，且这一轮没有在等工具审批。
     * 压缩也占任务槽，所以压缩期间同样不会开始。
     */
    val canStartNextTurn: Boolean
        get() = _generationJob.value == null && !state.value.isAwaitingToolApproval

    // 自动压缩已经为哪条回复试过；压缩失败后不再为同一条回复反复重试
    @Volatile
    var autoCompressedReplyId: Uuid? = null

    // 质量审查没过、等着重生成的回复；非空表示任务槽清空后要拿它再生成一次。
    // 只在任务槽空着时消费：生成 job 里直接重生成会自我取消、自我等待
    @Volatile
    private var pendingReviewRetryMessageId: Uuid? = null

    // 这条回复已经因为审查失败重生成过几次；用户发新消息、审查通过或达到上限时归零
    @Volatile
    private var reviewRetryCount: Int = 0

    // 审查打回时给生成模型的校正指令；只对紧接着那一次重生成有效，取走即清空
    @Volatile
    private var pendingReviewFeedback: String? = null

    /** 本轮回复已因审查失败重生成过几次，用来判断是否还有重试机会 */
    val currentReviewRetryCount: Int get() = reviewRetryCount

    /**
     * 记下审查没过、待重生成的回复，并占用一次重试机会。
     *
     * [feedback] 是给模型看的完整校正指令（「哪里错了、怎么改」）。toast 上那句被截断到 100 字的
     * reason 不够模型用，这里单独存一份；为 null 时清掉旧值，避免上一轮的指令串到这一轮。
     */
    @Synchronized
    fun markPendingReviewRetry(messageId: Uuid, feedback: String? = null) {
        pendingReviewRetryMessageId = messageId
        pendingReviewFeedback = feedback
        reviewRetryCount++
    }

    /** 取出待重生成的回复并清空标记（计数保留，仍算作已用掉一次机会）；没有待重生成时返回 null */
    @Synchronized
    fun consumePendingReviewRetry(): Uuid? =
        pendingReviewRetryMessageId.also { pendingReviewRetryMessageId = null }

    /** 取出待重生成那条回复的校正指令并清空；这次重生成用完就没有了 */
    @Synchronized
    fun consumePendingReviewFeedback(): String? =
        pendingReviewFeedback.also { pendingReviewFeedback = null }

    /** 审查通过、用户发新消息或主动停止时清零：下一轮从头开始计数 */
    @Synchronized
    fun resetReviewRetryState() {
        pendingReviewRetryMessageId = null
        pendingReviewFeedback = null
        reviewRetryCount = 0
    }

    val isInUse: Boolean
        get() = refCount.get() > 0 || _generationJob.value != null ||
            inbox.state.value.items.isNotEmpty()

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    internal fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
    }

    internal fun release(): Int = refCount.decrementAndGet().also {
        if (it <= 0) scheduleIdleCheck()
    }

    @Synchronized
    fun setJob(job: Job?, cancelPrevious: Boolean = true, activity: SessionActivity = SessionActivity.Generating) {
        val previous = _generationJob.value
        if (job != null) generationStartedAt.value = System.currentTimeMillis()
        // 先于任务槽更新，界面看到新任务时类型已经对了
        _activity.value = activity.takeIf { job != null }
        _generationJob.value = job
        if (cancelPrevious) previous?.cancel()
        if (job != null) activeJobs.add(job)
        job?.invokeOnCompletion { cause ->
            synchronized(this) {
                activeJobs.remove(job)
                // Also propagate cancellation when a queued coroutine never entered its body.
                if (!cancelPrevious && cause is CancellationException) previous?.cancel()
                // A replaced job must not clear or advance its successor.
                if (_generationJob.compareAndSet(job, null)) {
                    _activity.value = null
                    onGenerationFinished(id, cause)
                    if (refCount.get() <= 0) scheduleIdleCheck()
                }
            }
        }
        job?.start()
    }

    fun getJob(): Job? = _generationJob.value

    /** 占着任务槽的任务中途换了在做的事（生成回复的中途压缩上下文）；槽空着时不改 */
    @Synchronized
    fun setActivity(activity: SessionActivity) {
        if (_generationJob.value != null) _activity.value = activity
    }

    @Synchronized
    fun cancelJobs(): List<Job> = activeJobs.toList().also { jobs ->
        // Cancel waiters first so a predecessor finishing cannot start the next approval.
        jobs.asReversed().forEach { it.cancel() }
    }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating) {
                // 回收前把只改了内存的内容落库；落库失败就留在内存里，等下次释放引用或生成结束时再检查。
                // 还没落过库的会话不在这里新建：只打开过、没发过消息的新会话不该留下记录
                try {
                    if (existsInDatabase) flush()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist conversation $id before eviction", e)
                    return@launch
                }
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    @Synchronized
    fun cleanup() {
        _generationJob.value = null
        cancelJobs()
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}

/** Serialize approval saves without cancelling earlier decisions; stopping cancels the whole chain. */
/** 会话任务槽里的任务类型 */
enum class SessionActivity {
    /** 生成回复：发送、重新生成、审批后继续 */
    Generating,

    /** 压缩上下文：手动或自动 */
    Compressing,
}

internal suspend fun afterPreviousGeneration(previous: Job?, block: suspend () -> Unit) {
    try {
        previous?.join()
        block()
    } catch (e: CancellationException) {
        previous?.cancel()
        withContext(NonCancellable) { previous?.join() }
        throw e
    }
}
