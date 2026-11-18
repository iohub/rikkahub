package me.rerere.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 请求模型失败时的重试策略：最多重试 [maxRetries] 次，等待时间从 [initialDelay] 开始每次翻倍。
 *
 * 默认只重试 [IOException]，也就是连不上、超时、读到一半断开这类网络故障。
 */
data class RetryPolicy(
    val maxRetries: Int,
    val initialDelay: Duration = 1.seconds,
    val retryOn: (Throwable) -> Boolean = { it is IOException },
) {
    companion object {
        val NONE = RetryPolicy(maxRetries = 0)

        /** 网络自动重试：最多 3 次，间隔 1s、2s、4s */
        val NETWORK = RetryPolicy(maxRetries = 3)
    }
}

/** 第 [retry] 次重试（共 [maxRetries] 次）将在 [delay] 后开始，[cause] 是上一次失败的原因 */
data class RetryAttempt(
    val cause: Throwable,
    val retry: Int,
    val maxRetries: Int,
    val delay: Duration,
)

/**
 * 按 [policy] 执行 [block]：失败且允许重试时先调用 [onRetry]，等待后再执行一次。
 *
 * [beforeAttempt] 在每次尝试发起之前调用，包括第一次：第一次拿到 null，之后每次拿到上一次失败的
 * [RetryAttempt]，可以在重新提交请求前做外部准备（例如重建代理隧道）。
 *
 * [beforeAttempt] 在 `try` 之外调用，它抛出的异常不会被当成一次请求失败而消耗重试次数；
 * 协程取消（[CancellationException]）照常向上抛出，不会重试。
 */
suspend fun <T> withRetry(
    policy: RetryPolicy,
    onRetry: suspend (RetryAttempt) -> Unit = {},
    beforeAttempt: suspend (RetryAttempt?) -> Unit = {},
    block: suspend () -> T,
): T {
    var retries = 0
    var lastAttempt: RetryAttempt? = null
    while (true) {
        // 在 try 之前：钩子自身失败不应该被计成一次请求失败，也不该触发重试
        beforeAttempt(lastAttempt)
        try {
            return block()
        } catch (error: Throwable) {
            // 用户主动停止生成时，底层连接也可能以 IOException("canceled") 收尾；
            // 先检查协程状态，确保取消不会被当作网络波动重新拉起。
            currentCoroutineContext().ensureActive()
            if (error is CancellationException || retries >= policy.maxRetries || !policy.retryOn(error)) {
                throw error
            }
            retries++
            val backoff = policy.initialDelay * (1 shl (retries - 1))
            val attempt = RetryAttempt(error, retries, policy.maxRetries, backoff)
            lastAttempt = attempt
            onRetry(attempt)
            delay(backoff)
        }
    }
}
