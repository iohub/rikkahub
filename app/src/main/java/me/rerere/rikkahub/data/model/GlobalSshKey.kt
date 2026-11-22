package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * 全局 SSH 密钥，存在设置里，作为端口转发隧道与 SSH 工作区的默认身份。
 *
 * 本仓库没有 ssh-agent（JSch 的 agent 通道在 Android 上不可用），JSch 只接受明文私钥文本，
 * 所以这里存的是 [me.rerere.rikkahub.utils.SecretCipher] 加密后的私钥：
 * 密钥在 Android Keystore 里且不可导出，换设备或重装后恢复备份无法解密，需要重新填写。
 *
 * 字段名即 JSON 键，改动会让已保存的密钥读不回来，需要保持稳定。
 */
@Serializable
data class GlobalSshKey(
    /**
     * OpenSSH 单行公钥，如 `ssh-ed25519 AAAAC3Nza...`。
     *
     * 由私钥派生（见 `SshKeyUtils.derivePublicKey`），可明文保存：它本身就是要复制到服务器
     * `~/.ssh/authorized_keys` 的公开信息。
     */
    val publicKey: String = "",
    /** 加密后的私钥原文；null 表示还没配置全局密钥 */
    val encryptedPrivateKey: String? = null,
    /** 加密后的私钥口令；null 表示私钥没有口令 */
    val encryptedPassphrase: String? = null,
) {
    /** 是否已经配置了可用的全局密钥；口令是可选的，不影响这个判断 */
    val isConfigured: Boolean
        get() = !encryptedPrivateKey.isNullOrBlank()

    // 不把密文带进日志和异常信息；公钥本身是公开信息，可以打印
    override fun toString(): String =
        "GlobalSshKey(publicKey=$publicKey, configured=$isConfigured)"
}
