package me.rerere.ai.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RetryTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `io failures are retried with doubling delays`() = runTest {
        var calls = 0
        val attempts = mutableListOf<RetryAttempt>()

        val result = withRetry(RetryPolicy.NETWORK, onRetry = { attempts += it }) {
            calls++
            if (calls < 3) throw IOException("timeout")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf(1, 2), attempts.map { it.retry })
        assertEquals(listOf(1.seconds, 2.seconds), attempts.map { it.delay })
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun `gives up after max retries`() = runTest {
        var calls = 0

        val error = runCatching {
            withRetry(RetryPolicy.NETWORK) {
                calls++
                throw IOException("offline")
            }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(4, calls)
    }

    @Test
    fun `other errors are thrown without retrying`() = runTest {
        var calls = 0

        val error = runCatching {
            withRetry(RetryPolicy.NETWORK) {
                calls++
                throw IllegalStateException("bad request")
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(1, calls)
    }

    @Test
    fun `io failure caused by cancellation is not retried`() = runTest {
        var calls = 0

        val job = launch {
            withRetry(RetryPolicy(maxRetries = 3, initialDelay = Duration.ZERO)) {
                calls++
                // 停止生成时，底层连接会以 IOException("Canceled") 结束请求
                currentCoroutineContext().cancel()
                throw IOException("Canceled")
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, calls)
    }

    @Test
    fun `before attempt runs once per attempt with the previous failure`() = runTest {
        var calls = 0
        val seen = mutableListOf<RetryAttempt?>()

        val result = withRetry(RetryPolicy.NETWORK, beforeAttempt = { seen += it }) {
            calls++
            if (calls < 3) throw IOException("timeout")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, calls)
        // 第一次没有失败信息，之后每次拿到上一次失败
        assertEquals(3, seen.size)
        assertEquals(listOf<Int?>(null, 1, 2), seen.map { it?.retry })
        assertEquals(listOf(Duration.ZERO, 1.seconds, 2.seconds), seen.map { it?.delay ?: Duration.ZERO })
        assertTrue(seen.drop(1).all { it?.cause is IOException })
    }

    @Test
    fun `before attempt failure is not treated as a request failure`() = runTest {
        var calls = 0
        var hookCalls = 0
        var retryCalls = 0

        val error = runCatching {
            withRetry(
                policy = RetryPolicy.NETWORK,
                onRetry = { retryCalls++ },
                beforeAttempt = {
                    hookCalls++
                    throw IOException("tunnel down")
                },
            ) {
                calls++
                "ok"
            }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        // 钩子在 try 之外：抛异常直接结束，不消耗重试次数，也不会被当成请求失败重试
        assertEquals(1, hookCalls)
        assertEquals(0, calls)
        assertEquals(0, retryCalls)
    }

    @Test
    fun `hook runs right before each attempt`() = runTest {
        val events = mutableListOf<String>()
        var calls = 0

        withRetry(
            policy = RetryPolicy(maxRetries = 1, initialDelay = Duration.ZERO),
            onRetry = { events += "retry" },
            beforeAttempt = { events += "before" },
        ) {
            calls++
            events += "block"
            if (calls == 1) throw IOException("boom")
        }

        assertEquals(listOf("before", "block", "retry", "before", "block"), events)
    }
}
