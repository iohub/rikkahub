package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.remote.toProxyOrNull
import me.rerere.rikkahub.service.SshTunnelManager
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private const val TAG = "SshProxyTunnelResolver"

/**
 * 判断一次模型请求是否经由某条 SSH 隧道代理, 并在请求失败重试前重建那条隧道。
 *
 * 仓库里没有「provider/model ↔ 隧道」的显式绑定, 这里用端口匹配自动推断:
 * [me.rerere.rikkahub.data.datastore.NetworkSetting.proxyUrl] 的端口, 或指向本机回环地址的
 * baseUrl 端口 ([ProviderSetting.ApiKey.baseUrl]), 与某条隧道实际监听的本地端口一致,
 * 就认为这次请求走的是那条隧道。远端域名端口相同没有意义, 所以 baseUrl 只认回环地址。
 */
class SshProxyTunnelResolver(
    private val settingsStore: SettingsStore,
    private val sshTunnelManager: SshTunnelManager,
) {
    /**
     * 这次请求经过的隧道 id；没有命中任何隧道时返回 null。
     *
     * [providerSetting] 应当是已经应用了模型覆盖后的生效配置（[Model.findProvider] 返回的那份）。
     * [settings] 传调用方手里那份即可，不传时读当前设置。
     */
    suspend fun tunnelIdFor(
        providerSetting: ProviderSetting,
        model: Model? = null,
        settings: Settings? = null,
    ): String? {
        val current = settings ?: settingsStore.awaitLoaded()
        val proxyPort = proxyPortOf(current.networkSetting.proxyUrl)
        val baseUrlPort = (providerSetting as? ProviderSetting.ApiKey)
            ?.baseUrl
            ?.let(::baseUrlPortOf)
        if (proxyPort == null && baseUrlPort == null) return null
        val tunnelId = matchTunnelByPort(proxyPort, baseUrlPort, tunnelPorts(current))
        Log.d(
            TAG,
            "Tunnel for model ${model?.modelId}: proxy=${proxyPort ?: "-"}, " +
                "baseUrl=${baseUrlPort ?: "-"} -> ${tunnelId ?: "none"}",
        )
        return tunnelId
    }

    /** 重建一条隧道并等建连结束, 返回结束时是否 RUNNING */
    suspend fun reestablish(tunnelId: String): Boolean = sshTunnelManager.ensureRunning(tunnelId)

    /** 失败原因是否属于「超时/断连」这类可能由隧道失效引起的故障 */
    fun shouldReconnect(cause: Throwable): Boolean = isReconnectableCause(cause)

    /** 各隧道监听的本地端口: 运行时端口优先, 没有运行时信息才用配置里的端口 */
    private fun tunnelPorts(settings: Settings): List<Pair<String, Int>> {
        val runningPorts = sshTunnelManager.localPorts()
        return settings.sshTunnels.mapNotNull { tunnel ->
            val port = runningPorts[tunnel.id] ?: tunnel.localPort.takeIf { it in 1..65535 }
            port?.let { tunnel.id to it }
        }
    }
}

/**
 * 从候选端口里找出正在监听它的隧道 id。
 *
 * 命中多条时取 [tunnels] 里靠前的那条, 调用方的隧道列表是有序的, 结果可预测。
 */
internal fun matchTunnelByPort(
    proxyPort: Int?,
    baseUrlPort: Int?,
    tunnels: List<Pair<String, Int>>,
): String? {
    val ports = listOfNotNull(proxyPort, baseUrlPort).filter { it in 1..65535 }
    if (ports.isEmpty()) return null
    return tunnels.firstOrNull { (_, port) -> port in ports }?.first
}

/** [proxyUrl] 里的端口；解析不出来返回 null */
internal fun proxyPortOf(proxyUrl: String): Int? {
    val value = proxyUrl.trim()
    if (value.isEmpty()) return null
    val address = value.toProxyOrNull()?.address() as? InetSocketAddress
    if (address != null && address.port in 1..65535) return address.port
    // 带认证信息这类 toProxyOrNull 不接受的写法, 退回按 URI 只取端口
    val normalized = if ("://" in value) value else "http://$value"
    return runCatching { URI(normalized) }.getOrNull()?.port?.takeIf { it in 1..65535 }
}

/**
 * baseUrl 指向本机回环地址时它的端口, 否则 null。
 *
 * 只有回环地址才可能指向本机的隧道; 远端域名和隧道端口相同纯属巧合, 不能据此重建隧道。
 */
internal fun baseUrlPortOf(baseUrl: String): Int? {
    val value = baseUrl.trim()
    if (value.isEmpty()) return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    val host = uri.host?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() } ?: return null
    if (!host.isLoopbackHost()) return null
    return uri.port.takeIf { it in 1..65535 } ?: defaultPortOf(uri.scheme)
}

internal fun String.isLoopbackHost(): Boolean {
    val host = removeSurrounding("[", "]").lowercase()
    return host == "localhost" ||
        host == "::1" ||
        host == "0:0:0:0:0:0:0:1" ||
        host.startsWith("127.")
}

private fun defaultPortOf(scheme: String?): Int? = when (scheme?.lowercase()) {
    "http" -> 80
    "https" -> 443
    else -> null
}

/**
 * 只有「超时 / 连接断开」才可能是隧道失效造成的, 其它异常（模型返回 400、解析失败）重建隧道没用。
 *
 * 判据与 [me.rerere.rikkahub.service.ChatService] 显示重试原因时的分类一致:
 * - [InterruptedIOException] 覆盖 SocketTimeoutException, 也就是请求超时;
 * - [SocketException] 覆盖 ConnectException、NoRouteToHostException, 也就是连不上;
 * - [EOFException]、[SSLException]、[UnknownHostException] 是连接中途断开或代理端口不可用。
 */
internal fun isReconnectableCause(cause: Throwable): Boolean {
    // 用户「停止生成」必须继续生效: 取消不是网络故障, 也绝不能触发重连
    if (cause is CancellationException) return false
    return when (cause) {
        is InterruptedIOException,
        is SocketException,
        is EOFException,
        is UnknownHostException,
        is SSLException -> true

        else -> false
    }
}
