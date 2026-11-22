package me.rerere.workspace.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.util.Base64

/** 私钥文本的辅助处理，目前只用来派生公钥。 */
object SshKeyUtils {
    /**
     * 从 OpenSSH/PEM 私钥文本派生 OpenSSH 单行公钥。
     *
     * 公钥用于展示和复制到服务器的 `~/.ssh/authorized_keys`；认证本身仍然只用私钥，见 [SshConnection]。
     *
     * 用的是 mwiede 的 JSch，它没有带口令的 `KeyPair.load` 重载：加密私钥要先 `load`
     * （公钥部分是明文，能直接读到），再用 [KeyPair.decrypt] 用口令解开私钥部分。
     *
     * @param passphrase 私钥口令, 无口令传 null; 私钥加密时口令必须正确, 否则无法确认这对凭据可用
     * @return 形如 `ssh-ed25519 AAAAC3Nza...` 的单行公钥; 解析失败或口令不对返回 null
     */
    fun derivePublicKey(privateKey: String, passphrase: String? = null): String? {
        if (privateKey.isBlank()) return null
        val keyPair = try {
            // JSch 要求私钥文本以换行结尾, 用户粘贴的内容通常没有
            KeyPair.load(JSch(), "$privateKey\n".toByteArray(), null)
        } catch (e: Exception) {
            // 解析失败就是没有公钥可用, 不向调用方抛异常; 私钥内容不能进日志
            null
        } ?: return null
        return try {
            if (keyPair.isEncrypted) {
                val secret = passphrase?.takeIf { it.isNotEmpty() }?.toByteArray() ?: return null
                // 口令不对时私钥解不开, 认证一定会失败, 这里就不给出公钥了
                if (!keyPair.decrypt(secret)) return null
            }
            "${keyPair.keyTypeString} ${Base64.getEncoder().encodeToString(keyPair.publicKeyBlob)}"
        } catch (e: Exception) {
            null
        } finally {
            keyPair.dispose()
        }
    }
}
