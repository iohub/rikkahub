package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshCommandParserTest {
    @Test
    fun `parses the full command`() {
        val parsed = parseSshCommand("ssh -L 6008:localhost:6008 -p 15061 root@region-41.seetacloud.com")

        assertNotNull(parsed)
        parsed!!
        assertEquals("region-41.seetacloud.com", parsed.host)
        assertEquals(15061, parsed.port)
        assertTrue(parsed.hasPort)
        assertEquals("root", parsed.username)
        assertEquals("127.0.0.1", parsed.bindAddress)
        assertEquals(6008, parsed.localPort)
        assertEquals("localhost", parsed.remoteHost)
        assertEquals(6008, parsed.remotePort)
        assertTrue(parsed.hasForward)
        assertNull(parsed.identityFile)
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun `username from -l option and default port`() {
        val parsed = parseSshCommand("ssh -N -f -v -l admin -L 8080:127.0.0.1:80 example.com")

        assertNotNull(parsed)
        parsed!!
        assertEquals("admin", parsed.username)
        assertEquals("example.com", parsed.host)
        assertEquals(22, parsed.port)
        // 命令里没有 -p, 默认 22 只是兜底值, 不表示用户显式指定了端口
        assertFalse(parsed.hasPort)
        assertEquals(8080, parsed.localPort)
        assertEquals("127.0.0.1", parsed.remoteHost)
        assertEquals(80, parsed.remotePort)
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun `identity file is reported but not used`() {
        val parsed = parseSshCommand("ssh -i ~/.ssh/id_ed25519 -L 5432:db:5432 dev@server")

        assertNotNull(parsed)
        parsed!!
        assertEquals("~/.ssh/id_ed25519", parsed.identityFile)
        assertEquals("dev", parsed.username)
        assertFalse(parsed.hasPort)
        assertTrue(parsed.warnings.any { it.contains("私钥") })
    }

    @Test
    fun `four segment forward spec keeps bind address`() {
        val parsed = parseSshCommand("ssh -L 0.0.0.0:8080:db.internal:5432 root@host")

        assertNotNull(parsed)
        parsed!!
        assertEquals("0.0.0.0", parsed.bindAddress)
        assertEquals(8080, parsed.localPort)
        assertEquals("db.internal", parsed.remoteHost)
        assertEquals(5432, parsed.remotePort)
        assertFalse(parsed.hasPort)
    }

    @Test
    fun `inline option values and quoted command are supported`() {
        val parsed = parseSshCommand("\"ssh -L6009:localhost:6009 -p15061 -lroot host\"")

        assertNotNull(parsed)
        parsed!!
        assertEquals(6009, parsed.localPort)
        assertEquals(15061, parsed.port)
        assertTrue(parsed.hasPort)
        assertEquals("root", parsed.username)
        assertEquals("host", parsed.host)
    }

    @Test
    fun `ssh url form is supported`() {
        val parsed = parseSshCommand("ssh -L 8080:localhost:80 ssh://someone@example.com:2222")

        assertNotNull(parsed)
        parsed!!
        assertEquals("someone", parsed.username)
        assertEquals("example.com", parsed.host)
        assertEquals(2222, parsed.port)
        assertTrue(parsed.hasPort)
    }

    @Test
    fun `ssh url without -L reports port as explicit`() {
        val parsed = parseSshCommand("ssh://someone@example.com:2222")

        assertNotNull(parsed)
        parsed!!
        assertEquals("someone", parsed.username)
        assertEquals("example.com", parsed.host)
        assertEquals(2222, parsed.port)
        // URL 形式的端口同样是用户显式写的, 应该回填到端口框
        assertTrue(parsed.hasPort)
        assertFalse(parsed.hasForward)
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun `invalid input returns null`() {
        assertNull(parseSshCommand(""))
        assertNull(parseSshCommand("   "))
        assertNull(parseSshCommand("ls -la"))
        assertNull(parseSshCommand("ssh -L 8080:localhost:80 'unterminated"))
    }

    @Test
    fun `multiple forward specs only keep the first and warn`() {
        val parsed = parseSshCommand("ssh -L 6008:a:1 -L 6009:b:2 host")

        assertNotNull(parsed)
        parsed!!
        assertEquals(6008, parsed.localPort)
        assertEquals("a", parsed.remoteHost)
        assertEquals(1, parsed.remotePort)
        assertFalse(parsed.hasPort)
        assertTrue(parsed.warnings.any { it.contains("-L") })
    }

    @Test
    fun `missing forward is fine but missing host is reported`() {
        // 纯登录命令 (没有 -L) 是合法输入, 不应该再因为缺 -L 产生告警
        val noForward = parseSshCommand("ssh root@host")!!
        assertNull(noForward.localPort)
        assertNull(noForward.remotePort)
        assertFalse(noForward.hasForward)
        // 没有 -p 时端口取默认 22, 但不算用户显式指定, 不应该回填到端口框
        assertEquals(22, noForward.port)
        assertFalse(noForward.hasPort)
        assertTrue(noForward.warnings.isEmpty())

        // 没有目标主机才是真正的错误
        val noHost = parseSshCommand("ssh -L 6008:localhost:6008")!!
        assertEquals(6008, noHost.localPort)
        assertEquals("", noHost.host)
        assertTrue(noHost.warnings.isNotEmpty())
        assertTrue(noHost.warnings.any { it.contains("主机") })
    }

    @Test
    fun `pure login command fills connection fields without warnings`() {
        val parsed = parseSshCommand("ssh -p 36724 root@region-42.seetacloud.com")

        assertNotNull(parsed)
        parsed!!
        assertEquals("region-42.seetacloud.com", parsed.host)
        assertEquals(36724, parsed.port)
        assertTrue(parsed.hasPort)
        assertEquals("root", parsed.username)
        assertFalse(parsed.hasForward)
        // 没有 -L 就不要臆造转发配置
        assertNull(parsed.localPort)
        assertNull(parsed.remotePort)
        assertEquals("", parsed.remoteHost)
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun `plain login command without -p keeps default port but no explicit flag`() {
        val parsed = parseSshCommand("ssh root@region-42.seetacloud.com")

        assertNotNull(parsed)
        parsed!!
        assertEquals("region-42.seetacloud.com", parsed.host)
        assertEquals("root", parsed.username)
        // 解析结果里仍要有一个可用端口, 缺省保留 22
        assertEquals(22, parsed.port)
        // 但命令里没写端口, 界面不应该拿这个 22 去覆盖用户已填的端口
        assertFalse(parsed.hasPort)
        assertFalse(parsed.hasForward)
        assertEquals("", parsed.remoteHost)
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun `forward command is flagged as tunnel while plain login is not`() {
        val tunnel = parseSshCommand("ssh -L 8080:localhost:80 root@host")!!
        assertTrue(tunnel.hasForward)
        assertFalse(tunnel.hasPort)

        val login = parseSshCommand("ssh -p 2222 root@host")!!
        assertFalse(login.hasForward)
        assertTrue(login.hasPort)
        assertTrue(login.warnings.isEmpty())
    }

    @Test
    fun `out of range ports are ignored with a warning`() {
        val parsed = parseSshCommand("ssh -p 70000 -L 0:localhost:99999 root@host")!!

        assertEquals(22, parsed.port)
        // -p 70000 越界已被忽略并写入 warning, 因此不算显式指定端口
        assertFalse(parsed.hasPort)
        assertNull(parsed.localPort)
        assertNull(parsed.remotePort)
        assertTrue(parsed.warnings.size >= 2)
    }
}
