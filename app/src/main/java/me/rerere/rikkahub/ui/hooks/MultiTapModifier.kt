package me.rerere.rikkahub.ui.hooks

import android.os.SystemClock
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 「多连击」手势修饰符：在 [windowMillis] 时间窗内连续轻点 [taps] 次即触发 [onTriggered]。
 *
 * 用于隐藏入口这类场景（例如 5 秒内连点标题 5 次打开解锁弹窗），因此：
 * - 不产生任何水波纹/点击视觉反馈（没有使用 `Modifier.clickable`）；
 * - 只接管轻点判定，拖动、滑动交给父级滚动容器，不会被拦截；
 * - 连击记录用不可变 `List` 持有，达到阈值后清空重新计数。
 *
 * 用法：`Modifier.multiTap(taps = 5, windowMillis = 5_000L) { showUnlockDialog = true }`
 *
 * @param taps 需要连续轻点的次数，至少为 1
 * @param windowMillis 从第一次轻点到最后一次轻点允许的最大时间跨度（毫秒）
 * @param onTriggered 触发回调
 */
@Composable
fun Modifier.multiTap(
    taps: Int = 5,
    windowMillis: Long = 5_000L,
    onTriggered: () -> Unit,
): Modifier {
    val requiredTaps = taps.coerceAtLeast(1)
    var recentTapTimes by remember(requiredTaps) { mutableStateOf<List<Long>>(emptyList()) }
    val currentOnTriggered by rememberUpdatedState(onTriggered)

    return pointerInput(requiredTaps, windowMillis) {
        detectTapGestures {
            val now = SystemClock.uptimeMillis()
            val window = (recentTapTimes + now).takeLast(requiredTaps)
            if (window.size >= requiredTaps && now - window.first() <= windowMillis) {
                recentTapTimes = emptyList()
                currentOnTriggered()
            } else {
                recentTapTimes = window
            }
        }
    }
}
