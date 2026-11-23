package me.rerere.rikkahub.data.ai.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断网续传状态机的纯函数单测：状态 → 下一步动作、退避序列、client_job_id 合法性。
 *
 * 这些判断决定「还要不要继续等」，一旦写错就是要么提前放弃（丢掉已经跑完的图），
 * 要么永远等下去，所以单独固化，不依赖任何网络与 Android framework。
 */
class ImageJobStateMachineTest {

    // region 状态 → 动作

    @Test
    fun `queued and running keep waiting`() {
        assertEquals(JobStatusKind.WAITING, jobStatusKind(JOB_STATUS_QUEUED))
        assertEquals(JobStatusKind.WAITING, jobStatusKind(JOB_STATUS_RUNNING))
    }

    @Test
    fun `unknown or missing status keeps waiting instead of failing`() {
        // 服务端将来新增状态（比如 cancelling）时不能误判成失败，交给整体截止时间收口
        assertEquals(JobStatusKind.WAITING, jobStatusKind("cancelling"))
        assertEquals(JobStatusKind.WAITING, jobStatusKind(""))
        assertEquals(JobStatusKind.WAITING, jobStatusKind("   "))
        assertEquals(JobStatusKind.WAITING, jobStatusKind(null))
    }

    @Test
    fun `succeeded failed and interrupted are terminal`() {
        assertEquals(JobStatusKind.SUCCEEDED, jobStatusKind(JOB_STATUS_SUCCEEDED))
        assertEquals(JobStatusKind.FAILED, jobStatusKind(JOB_STATUS_FAILED))
        assertEquals(JobStatusKind.INTERRUPTED, jobStatusKind(JOB_STATUS_INTERRUPTED))
    }

    @Test
    fun `status matching tolerates case and padding`() {
        // 服务端是 Python，大小写/空白完全可能因为中间层被改动，不该因此卡住整条链路
        assertEquals(JobStatusKind.SUCCEEDED, jobStatusKind(" SUCCEEDED "))
        assertEquals(JobStatusKind.FAILED, jobStatusKind("Failed"))
        assertEquals(JobStatusKind.INTERRUPTED, jobStatusKind("interrupted\n"))
    }

    // endregion

    // region 退避

    @Test
    fun `backoff follows the documented sequence and caps at ten seconds`() {
        assertEquals(2_000L, retryBackoffMs(0))
        assertEquals(3_000L, retryBackoffMs(1))
        assertEquals(5_000L, retryBackoffMs(2))
        assertEquals(8_000L, retryBackoffMs(3))
        assertEquals(10_000L, retryBackoffMs(4))
        // 断网一小时后不该退避到几分钟才试一次
        assertEquals(10_000L, retryBackoffMs(5))
        assertEquals(10_000L, retryBackoffMs(500))
        // 负数没有意义，但不能抛异常
        assertEquals(2_000L, retryBackoffMs(-1))
    }

    // endregion

    // region client_job_id

    @Test
    fun `generated client job id matches the server charset`() {
        val ids = (1..20).map { newClientJobId() }

        ids.forEach { id ->
            assertTrue("client_job_id 必须满足服务端字符集：$id", CLIENT_JOB_ID_PATTERN.matches(id))
            assertTrue("长度必须在 1..128 之间：$id", id.length in 1..128)
        }
        assertEquals("每次生成的 id 必须不同，否则会幂等命中别人的任务", ids.size, ids.toSet().size)
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun `client job id pattern rejects the shapes the server rejects`() {
        listOf(
            "",
            "   ",
            "has space",
            "slash/inside",
            "question?mark",
            "中文 id",
            "a".repeat(129),
        ).forEach { raw ->
            assertTrue("服务端会 400 的 id 不该通过本地校验：$raw", !CLIENT_JOB_ID_PATTERN.matches(raw))
        }
        // UUID 形态与带前缀的形态都要能通过
        assertTrue(CLIENT_JOB_ID_PATTERN.matches("123e4567-e89b-12d3-a456-426614174000"))
        assertTrue(CLIENT_JOB_ID_PATTERN.matches("rikkahub:task-1_2024.11"))
        assertTrue(CLIENT_JOB_ID_PATTERN.matches("a".repeat(128)))
    }

    // endregion
}
