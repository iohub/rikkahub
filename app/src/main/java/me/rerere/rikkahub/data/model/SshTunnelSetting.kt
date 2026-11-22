package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.utils.SecretCipher
import me.rerere.workspace.ssh.SshAuthType
import me.rerere.workspace.ssh.SshConnectionConfig
import me.rerere.workspace.ssh.SshHostKey
import java.io.IOException

/**
 * SSH 本地端口转发（等价 `ssh -L`）的持久化配置，作为 JSON 存在设置里。
 *
 * 字段名即 JSON 键，改动会让已保存的转发配置读不回来，需要保持稳定。
 * 凭据字段只存 [SecretCipher] 加密后的密文：密钥在 Android Keystore 里且不可导出，
 * 换设备或重装后恢复备份无法解密，此时连接会失败并提示重新填写凭据。
 */
@Serializable
data class SshTunnelSetting(
    /** 稳定标识，用 UUID 字符串；空表示还没保存过 */
    val id: String = "",
    /** 展示名，留空时回退成 username@host */
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authType: SshAuthType = SshAuthType.PASSWORD,
    val encryptedPassword: String? = null,
    /** OpenSSH / PEM 格式私钥原文的密文 */
    val encryptedPrivateKey: String? = null,
    val encryptedPassphrase: String? = null,
    /** 用户确认过的主机公钥；为 null 时首次连接需要先确认指纹 */
    val hostKey: SshHostKey? = null,
    /** 本地监听地址：127.0.0.1 只允许本机访问，0.0.0.0 局域网内可访问 */
    val bindAddress: String = LOOPBACK,
    /** 本地监听端口，0 表示由系统分配 */
    val localPort: Int = 0,
    /** 远端侧的目标主机，通常就是 sshd 所在机器上的 localhost */
    val remoteHost: String = LOOPBACK,
    val remotePort: Int = 0,
    /** 用户期望这条转发运行；应用或服务重启后据此自动恢复 */
    val enabled: Boolean = false,
) {
    // 不把密文带进日志和异常信息
    override fun toString(): String =
        "SshTunnelSetting(id=$id, name=$name, address=$username@$host:$port, " +
            "local=$bindAddress:$localPort, remote=$remoteHost:$remotePort, enabled=$enabled)"

    companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}

/**
 * 转成 SSH 连接配置，顺带解密凭据。
 *
 * [globalKey] 是设置里的全局密钥：私钥认证且本实体没自带私钥时用它回退，其余情况（含密码认证）不读它。
 * 端口转发用不到远程目录，但 [SshConnectionConfig.remoteDir] 必填且要求绝对路径，这里固定传 `/`。
 * 解密失败只可能是密钥不可用（换设备、重装后恢复备份），抛 [IOException] 让调用方提示重新填写。
 */
fun SshTunnelSetting.toConnectionConfig(globalKey: GlobalSshKey? = null): SshConnectionConfig {
    // 只有私钥认证、本实体没填私钥、且全局密钥确实配置过时才回退；否则一律用本实体的凭据
    val fallbackKey = globalKey?.takeIf {
        authType == SshAuthType.PRIVATE_KEY && encryptedPrivateKey.isNullOrBlank() && it.isConfigured
    }
    val privateKeySecret = if (fallbackKey != null) fallbackKey.encryptedPrivateKey else encryptedPrivateKey
    // 回退时口令也只跟全局密钥走：本实体的口令是给本实体私钥用的，混用会导致认证失败
    val passphraseSecret = if (fallbackKey != null) fallbackKey.encryptedPassphrase else encryptedPassphrase
    return SshConnectionConfig(
        host = host.trim(),
        port = port,
        username = username.trim(),
        authType = authType,
        password = encryptedPassword?.let(::decryptSecret),
        privateKey = privateKeySecret?.let(::decryptSecret),
        passphrase = passphraseSecret?.let(::decryptSecret),
        remoteDir = "/",
        hostKey = hostKey,
    )
}

/** 列表和通知里显示的名字，没填名字时用连接地址 */
fun SshTunnelSetting.displayName(): String = name.ifBlank { "$username@$host" }

/**
 * 保存前和连接前的校验，返回第一条错误说明，全部合法时返回 null。
 *
 * 这里只检查能不能发起连接，端口是否已被占用等运行时问题由连接过程报出。
 */
fun SshTunnelSetting.validationError(): String? = when {
    host.isBlank() -> "主机地址不能为空"
    port !in 1..65535 -> "SSH 端口必须在 1-65535 之间"
    username.isBlank() -> "用户名不能为空"
    authType == SshAuthType.PASSWORD && encryptedPassword.isNullOrEmpty() -> "请填写密码"
    // 私钥可以留空：留空表示用设置里的全局 SSH 密钥，是否真的能连由建连结果决定
    localPort !in 0..65535 -> "本地端口必须在 0-65535 之间"
    remoteHost.isBlank() -> "目标主机不能为空"
    remotePort !in 1..65535 -> "目标端口必须在 1-65535 之间"
    else -> null
}

private fun decryptSecret(value: String): String = try {
    SecretCipher.decrypt(value)
} catch (e: Exception) {
    throw IOException("已保存的 SSH 凭据无法解密，请重新填写凭据", e)
}
