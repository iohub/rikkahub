package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.model.GlobalSshKey
import me.rerere.rikkahub.data.model.SshTunnelSetting
import me.rerere.rikkahub.data.model.displayName
import me.rerere.rikkahub.data.model.toConnectionConfig
import me.rerere.rikkahub.data.model.validationError
import me.rerere.workspace.ssh.SshAuthType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SshTunnelSettingTest {
    @Test
    fun `tunnel without id is dropped`() {
        val settings = Settings(sshTunnels = listOf(tunnel(id = ""), tunnel(id = "a"))).normalized()

        assertEquals(listOf("a"), settings.sshTunnels.map { it.id })
    }

    @Test
    fun `duplicate ids keep the first`() {
        val settings = Settings(
            sshTunnels = listOf(
                tunnel(id = "a", localPort = 6008),
                tunnel(id = "a", localPort = 6009),
            )
        ).normalized()

        assertEquals(listOf(6008), settings.sshTunnels.map { it.localPort })
    }

    @Test
    fun `out of range ports are clamped`() {
        val settings = Settings(
            sshTunnels = listOf(tunnel(id = "a", port = 70000, localPort = -1, remotePort = 70000))
        ).normalized()

        val tunnel = settings.sshTunnels.single()
        assertEquals(65535, tunnel.port)
        assertEquals(0, tunnel.localPort)
        assertEquals(65535, tunnel.remotePort)
    }

    @Test
    fun `validation accepts a complete tunnel`() {
        assertNull(tunnel(id = "a").validationError())
    }

    @Test
    fun `validation reports missing fields`() {
        assertNotNull(tunnel(id = "a", host = "").validationError())
        assertNotNull(tunnel(id = "a", username = "").validationError())
        assertNotNull(tunnel(id = "a", port = 0).validationError())
        assertNotNull(tunnel(id = "a", remoteHost = "").validationError())
        // 目标端口不能留空：0 表示还没填
        assertNotNull(tunnel(id = "a", remotePort = 0).validationError())
        assertNotNull(tunnel(id = "a", encryptedPassword = null).validationError())
    }

    @Test
    fun `private key may be left empty to use the global key`() {
        // 留空私钥表示回退到设置里的全局 SSH 密钥, 不能当成「还没填完」
        assertNull(
            tunnel(id = "a", authType = SshAuthType.PRIVATE_KEY, encryptedPrivateKey = null)
                .validationError()
        )
    }

    @Test
    fun `password auth ignores the global key`() {
        val config = tunnel(id = "a", authType = SshAuthType.PASSWORD, encryptedPassword = null)
            .toConnectionConfig(GlobalSshKey(encryptedPrivateKey = "v1:global-key"))

        assertEquals(SshAuthType.PASSWORD, config.authType)
        assertNull(config.privateKey)
    }

    @Test
    fun `display name falls back to address`() {
        assertEquals("dev@example.com", tunnel(id = "a", name = "").displayName())
        assertEquals("db", tunnel(id = "a", name = "db").displayName())
    }
}

private fun tunnel(
    id: String,
    name: String = "",
    host: String = "example.com",
    port: Int = 22,
    username: String = "dev",
    authType: SshAuthType = SshAuthType.PASSWORD,
    encryptedPassword: String? = "v1:password",
    encryptedPrivateKey: String? = null,
    localPort: Int = 0,
    remoteHost: String = "127.0.0.1",
    remotePort: Int = 5432,
) = SshTunnelSetting(
    id = id,
    name = name,
    host = host,
    port = port,
    username = username,
    authType = authType,
    encryptedPassword = encryptedPassword,
    encryptedPrivateKey = encryptedPrivateKey,
    localPort = localPort,
    remoteHost = remoteHost,
    remotePort = remotePort,
)
