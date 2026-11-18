package me.rerere.rikkahub.utils

/**
 * 从用户粘贴的 ssh 命令里解析出的连接参数, 供编辑页的“从命令填充”使用。
 *
 * 支持两种形态: 纯登录命令 (`ssh -p 22 root@host`) 和带本地转发的隧道命令
 * (`ssh -L 6008:localhost:6008 -p 22 root@host`), 后者用 [hasForward] 区分。
 *
 * 只做纯文本解析, 不依赖 Android 也不需要网络, 方便在 JVM 单测里覆盖。
 * 解析不出来的字段保持默认值, 由界面提示用户补齐。
 */
data class ParsedSshTunnel(
    val host: String = "",
    val port: Int = DEFAULT_SSH_PORT,
    /** 命令里是否显式写了 `-p` 或 `ssh://host:port` 形式的端口; 纯 `ssh user@host` 为 false */
    val hasPort: Boolean = false,
    val username: String = "",
    /** 本地监听地址, 命令里没写 `bind_address` 时就是 127.0.0.1; 没有 `-L` 时为空串 */
    val bindAddress: String = DEFAULT_LOOPBACK,
    /** `-L` 里的本地端口, 没写或写错时为 null */
    val localPort: Int? = null,
    /** `-L` 里的目标主机; 没有 `-L` 时为空串 */
    val remoteHost: String = DEFAULT_LOOPBACK,
    /** `-L` 里的目标端口, 没写或写错时为 null */
    val remotePort: Int? = null,
    /** 命令里是否带了 `-L` 本地端口转发; 纯登录命令 (如 `ssh -p 22 root@host`) 为 false */
    val hasForward: Boolean = false,
    /** `-i` 指定的私钥路径, 只用来提示用户手动粘贴私钥内容 (Android 读不到这个路径) */
    val identityFile: String? = null,
    /** 解析过程中的提示, 界面直接就地展示 */
    val warnings: List<String> = emptyList(),
)

private const val DEFAULT_SSH_PORT = 22
private const val DEFAULT_LOOPBACK = "127.0.0.1"
private const val ANY_ADDRESS = "0.0.0.0"
private const val SCHEME_PREFIX = "ssh://"
private val PORT_RANGE = 1..65535

/** 需要跟一个值的 ssh 选项, 取值后统一忽略 (个别选项在下面单独处理) */
private val VALUE_OPTIONS = setOf(
    "B", "b", "c", "D", "E", "e", "F", "I", "i", "J", "L", "l",
    "m", "O", "o", "p", "Q", "R", "S", "W", "w",
)

/**
 * 解析一条 openssh 命令, 支持 `-L [bind_address:]port:host:hostport`、`-p`、`-l`、`-i`
 * 以及 `user@host` / `ssh://user@host:port` 形式的目标主机 (整条命令就是 ssh:// 地址时也算)。
 *
 * 其它选项 (`-N`、`-f`、`-v`、`-o Key=Value`、`-J host` ...) 一律忽略, 需要提醒用户的地方写进
 * [ParsedSshTunnel.warnings]。
 *
 * 返回 null 表示这压根不是一条能解析的 ssh 命令 (空串、不是 ssh 开头、引号没闭合);
 * 能认出是 ssh 命令但没有 `-L` 时仍返回结果 ([ParsedSshTunnel.hasForward] 为 false),
 * 纯登录命令属于合法输入不会产生警告; 缺目标主机时返回结果并写入 warnings。
 */
fun parseSshCommand(raw: String): ParsedSshTunnel? {
    var tokens = tokenize(raw) ?: return null
    // 整条命令被引号包起来时 ("ssh -L ...") 会分词成一个参数, 拆开再解析一次
    if (tokens.size == 1 && tokens.first().any { it.isWhitespace() }) {
        tokens = tokenize(tokens.first()) ?: return null
    }
    if (tokens.isEmpty()) return null
    // 裸的 ssh://user@host:port (不带 ssh 命令前缀) 也是一种常见粘法, 补个占位让后面的
    // 位置参数解析照常走 parseDestination
    if (tokens.size == 1 && tokens.first().startsWith(SCHEME_PREFIX, ignoreCase = true)) {
        tokens = listOf("ssh") + tokens
    }
    if (!tokens.first().substringAfterLast('/').equals("ssh", ignoreCase = true)) return null

    val warnings = mutableListOf<String>()
    var host = ""
    var username = ""
    var optionPort: Int? = null
    var urlPort: Int? = null
    var bindAddress = DEFAULT_LOOPBACK
    var localPort: Int? = null
    var remoteHost = DEFAULT_LOOPBACK
    var remotePort: Int? = null
    var identityFile: String? = null
    var forwardSeen = false

    var index = 1
    while (index < tokens.size) {
        val token = tokens[index]
        index += 1
        if (token.length > 1 && token.startsWith("-")) {
            val key = token.substring(1, 2)
            // 支持 -p22 这种连写, 也支持 -p 22 分开写
            var value = token.substring(2).ifEmpty { null }
            if (value == null && key in VALUE_OPTIONS && index < tokens.size) {
                value = tokens[index]
                index += 1
            }
            when (key) {
                "L" -> when {
                    value == null -> warnings += "`-L` 缺少参数, 请检查命令"
                    forwardSeen -> warnings += "只支持一条 `-L` 转发, 已忽略: $value"
                    else -> {
                        forwardSeen = true
                        val spec = value.substringBefore(',')
                        if (spec != value) {
                            warnings += "只支持一条 `-L` 转发, 已只取第一段: $spec"
                        }
                        parseForwardSpec(spec, warnings)?.let { forward ->
                            bindAddress = forward.bindAddress
                            localPort = forward.localPort
                            remoteHost = forward.remoteHost
                            remotePort = forward.remotePort
                        }
                    }
                }

                "p" -> {
                    val parsed = value?.toIntOrNull()
                    if (parsed == null || parsed !in PORT_RANGE) {
                        warnings += "SSH 端口 `$value` 无效, 已忽略 (应为 1-65535)"
                    } else {
                        optionPort = parsed
                    }
                }

                "l" -> if (!value.isNullOrBlank()) username = value
                "i" -> if (!value.isNullOrBlank()) {
                    identityFile = value
                    warnings += "无法读取本机私钥文件 $value, 请在下方手动粘贴私钥内容"
                }

                "J" -> warnings += "暂不支持 `-J` 跳板机, 请确认能直连目标主机"
                // 其它选项 (-N -f -v -o StrictHostKeyChecking=no ...) 连同取值一起忽略
                else -> Unit
            }
        } else {
            // 位置参数: user@host 或 ssh://user@host:port
            val destination = parseDestination(token, warnings)
            when {
                destination == null -> warnings += "无法解析目标主机: $token"
                host.isNotBlank() -> warnings += "命令中存在多个目标主机, 已只使用第一个: $host"
                else -> {
                    host = destination.host
                    if (destination.username.isNotBlank()) username = destination.username
                    urlPort = destination.port
                }
            }
        }
    }

    // 没有 -L 不代表命令有问题: `ssh -p 22 root@host` 这种纯登录命令同样是合法输入.
    // 只有真的写了 -L 却解析失败时才有警告, 那些由 parseForwardSpec/上面的分支就地写入.
    if (host.isBlank()) warnings += "没有找到目标主机 (形如 user@host)"

    return ParsedSshTunnel(
        host = host,
        port = optionPort ?: urlPort ?: DEFAULT_SSH_PORT,
        // 只有命令里真的写了有效端口 (选项端口或 URL 端口) 才算显式指定;
        // 缺省时的 22 只是给模型层兜底, 界面不应该拿它去覆盖用户已填的端口
        hasPort = optionPort != null || urlPort != null,
        username = username,
        // 纯登录命令没有转发, 监听地址/目标主机保持为空, 不要臆造 127.0.0.1 之类的默认目标
        bindAddress = if (forwardSeen) bindAddress else "",
        localPort = localPort,
        remoteHost = if (forwardSeen) remoteHost else "",
        remotePort = remotePort,
        hasForward = forwardSeen,
        identityFile = identityFile,
        warnings = warnings,
    )
}

/**
 * 简单的 shell 分词: 处理单引号、双引号和反斜杠转义, 引号外的空白分隔参数。
 *
 * 引号没闭合或末尾是孤立的反斜杠时返回 null, 这种命令没法可靠解析。
 */
private fun tokenize(raw: String): List<String>? {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var inSingle = false
    var inDouble = false
    var escaped = false
    var hasContent = false
    for (char in raw) {
        when {
            escaped -> {
                current.append(char)
                escaped = false
                hasContent = true
            }

            char == '\\' && !inSingle -> {
                escaped = true
                hasContent = true
            }

            char == '\'' && !inDouble -> {
                inSingle = !inSingle
                hasContent = true
            }

            char == '"' && !inSingle -> {
                inDouble = !inDouble
                hasContent = true
            }

            char.isWhitespace() && !inSingle && !inDouble -> {
                if (hasContent) {
                    tokens += current.toString()
                    current.clear()
                    hasContent = false
                }
            }

            else -> {
                current.append(char)
                hasContent = true
            }
        }
    }
    if (escaped || inSingle || inDouble) return null
    if (hasContent) tokens += current.toString()
    return tokens
}

/** `-L` 规格的解析结果 */
private data class ForwardSpec(
    val bindAddress: String,
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
)

/**
 * 解析 `[bind_address:]port:host:hostport`, 支持 3 段和 4 段两种写法。
 * `[addr]` 里的冒号不当作分隔符, 这样 IPv6 地址也能写。
 */
private fun parseForwardSpec(spec: String, warnings: MutableList<String>): ForwardSpec? {
    val parts = splitForwardSpec(spec)
    val bindText: String
    val localText: String
    val remoteText: String
    val remotePortText: String
    when (parts.size) {
        3 -> {
            bindText = ""
            localText = parts[0]
            remoteText = parts[1]
            remotePortText = parts[2]
        }

        4 -> {
            bindText = parts[0]
            localText = parts[1]
            remoteText = parts[2]
            remotePortText = parts[3]
        }

        else -> {
            warnings += "无法解析 `-L $spec`, 格式应为 [bind_address:]port:host:hostport"
            return null
        }
    }
    val localPort = localText.trim().toIntOrNull()
    if (localPort == null || localPort !in PORT_RANGE) {
        warnings += "本地端口 `$localText` 无效, 应为 1-65535"
        return null
    }
    val remotePort = remotePortText.trim().toIntOrNull()
    if (remotePort == null || remotePort !in PORT_RANGE) {
        warnings += "目标端口 `$remotePortText` 无效, 应为 1-65535"
        return null
    }
    val targetHost = unbracket(remoteText).trim()
    if (targetHost.isBlank()) {
        warnings += "`-L` 里的目标主机为空"
        return null
    }
    val bindAddress = when (val bind = unbracket(bindText).trim()) {
        "" -> DEFAULT_LOOPBACK
        "*" -> ANY_ADDRESS
        else -> bind
    }
    return ForwardSpec(
        bindAddress = bindAddress,
        localPort = localPort,
        remoteHost = targetHost,
        remotePort = remotePort,
    )
}

/** 按冒号切分 `-L` 规格, `[addr]` 里的冒号不切 */
private fun splitForwardSpec(spec: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var inBracket = false
    for (char in spec) {
        when {
            char == '[' -> {
                inBracket = true
                current.append(char)
            }

            char == ']' -> {
                inBracket = false
                current.append(char)
            }

            char == ':' && !inBracket -> {
                parts += current.toString()
                current.clear()
            }

            else -> current.append(char)
        }
    }
    parts += current.toString()
    return parts
}

private fun unbracket(value: String): String =
    value.removeSurrounding("[", "]").trim()

/** 目标主机的解析结果 */
private data class Destination(
    val username: String,
    val host: String,
    val port: Int?,
)

/** 解析 `user@host`、`host`、`ssh://user@host:port`、`user@[::1]:22` */
private fun parseDestination(token: String, warnings: MutableList<String>): Destination? {
    var target = token.trim()
    if (target.startsWith(SCHEME_PREFIX, ignoreCase = true)) {
        // ssh:// 形式允许带路径, 这里只取主机部分
        target = target.substring(SCHEME_PREFIX.length).substringBefore('/')
    }
    if (target.isBlank()) return null
    val at = target.lastIndexOf('@')
    val username = if (at >= 0) target.substring(0, at) else ""
    val hostPort = if (at >= 0) target.substring(at + 1) else target
    if (hostPort.startsWith("[")) {
        val end = hostPort.indexOf(']')
        if (end <= 0) return null
        val host = hostPort.substring(1, end)
        val rest = hostPort.substring(end + 1)
        val port = rest.removePrefix(":").toIntOrNull()
        return Destination(username, host, port.takeIf { it in PORT_RANGE })
    }
    val colon = hostPort.lastIndexOf(':')
    if (colon < 0) return Destination(username, hostPort, null)
    val port = hostPort.substring(colon + 1).toIntOrNull()
    if (port == null) {
        // 不像端口的冒号, 多半是没加中括号的 IPv6 地址
        warnings += "地址 `$hostPort` 里的冒号不是端口, IPv6 请写成 [addr]:port"
        return Destination(username, hostPort, null)
    }
    if (port !in PORT_RANGE) {
        warnings += "SSH 端口 `$port` 无效, 已忽略 (应为 1-65535)"
        return Destination(username, hostPort.substring(0, colon), null)
    }
    return Destination(username, hostPort.substring(0, colon), port)
}
