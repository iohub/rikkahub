package me.rerere.workspace.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class SshKeyUtilsTest {
    @Test
    fun `derive public key from rsa private key`() {
        val keyPair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
        val expected = "${keyPair.keyTypeString} ${Base64.getEncoder().encodeToString(keyPair.publicKeyBlob)}"
        val privateKey = try {
            ByteArrayOutputStream().also { keyPair.writePrivateKey(it) }.toString(Charsets.UTF_8.name())
        } finally {
            keyPair.dispose()
        }

        val publicKey = SshKeyUtils.derivePublicKey(privateKey)

        assertNotNull(publicKey)
        assertEquals(expected, publicKey)
        assertTrue(publicKey!!.startsWith("ssh-rsa "))
        // 复制到 authorized_keys 的必须是单行
        assertFalse(publicKey.contains('\n'))
    }

    @Test
    fun `derive public key from ed25519 private key`() {
        // 和 ssh-keygen 对同一条私钥输出的 .pub 完全一致
        assertEquals(ED25519_PUBLIC_KEY, SshKeyUtils.derivePublicKey(ED25519_PRIVATE_KEY))
    }

    @Test
    fun `derive public key needs the right passphrase`() {
        val passphrase = "s3cret-pass"
        val keyPair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 2048)
        val privateKey = try {
            ByteArrayOutputStream()
                .also { keyPair.writePrivateKey(it, passphrase.toByteArray()) }
                .toString(Charsets.UTF_8.name())
        } finally {
            keyPair.dispose()
        }

        assertNotNull(SshKeyUtils.derivePublicKey(privateKey, passphrase))
        // 加密的私钥没口令解不开, 错误的库内口令同样解不开
        assertNull(SshKeyUtils.derivePublicKey(privateKey))
        assertNull(SshKeyUtils.derivePublicKey(privateKey, "wrong-pass"))
    }

    @Test
    fun `derive public key returns null for garbage input`() {
        assertNull(SshKeyUtils.derivePublicKey("not a key"))
        assertNull(SshKeyUtils.derivePublicKey(""))
    }

    private companion object {
        // 仅用于单测的 ed25519 密钥, 没有在任何真实服务器上使用过
        const val ED25519_PRIVATE_KEY = """-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
QyNTUxOQAAACCNjSy42PpeeSR41aPIoRnGERf5ePJdxu9Qr7OcUiLZcwAAAIiL7gsyi+4L
MgAAAAtzc2gtZWQyNTUxOQAAACCNjSy42PpeeSR41aPIoRnGERf5ePJdxu9Qr7OcUiLZcw
AAAEAgeXC16+js97guHs7Id41sYXOnoW618M6MTJYpup5YcY2NLLjY+l55JHjVo8ihGcYR
F/l48l3G71Cvs5xSItlzAAAAAAECAwQF
-----END OPENSSH PRIVATE KEY-----"""

        const val ED25519_PUBLIC_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII2NLLjY+l55JHjVo8ihGcYRF/l48l3G71Cvs5xSItlz"
    }
}
