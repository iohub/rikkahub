package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
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
        assertEquals("root", parsed.username)
        assertEquals("127.0.0.1", parsed.bindAddress)
        assertEquals(6008, parsed.localPort)
        assertEquals("localhost", parsed.remoteHost)
        assertEquals(6008, parsed.remotePort)
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
    }

    @Test
    fun `inline option values and quoted command are supported`() {
        val parsed = parseSshCommand("\"ssh -L6009:localhost:6009 -p15061 -lroot host\"")

        assertNotNull(parsed)
        parsed!!
        assertEquals(6009, parsed.localPort)
        assertEquals(15061, parsed.port)
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
        assertTrue(parsed.warnings.any { it.contains("-L") })
    }

    @Test
    fun `missing forward or host is reported through warnings`() {
        val noForward = parseSshCommand("ssh root@host")!!
        assertNull(noForward.localPort)
        assertNull(noForward.remotePort)
        assertTrue(noForward.warnings.any { it.contains("-L") })

        val noHost = parseSshCommand("ssh -L 6008:localhost:6008")!!
        assertEquals(6008, noHost.localPort)
        assertEquals("", noHost.host)
        assertTrue(noHost.warnings.isNotEmpty())
    }

    @Test
    fun `out of range ports are ignored with a warning`() {
        val parsed = parseSshCommand("ssh -p 70000 -L 0:localhost:99999 root@host")!!

        assertEquals(22, parsed.port)
        assertNull(parsed.localPort)
        assertNull(parsed.remotePort)
        assertTrue(parsed.warnings.size >= 2)
    }
}
