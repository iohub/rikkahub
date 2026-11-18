package me.rerere.rikkahub.ui.pages.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant

/**
 * 「加密隐藏」助手的设置密码弹窗。
 *
 * 校验失败时把错误文案显示在输入框下方，并在两个输入框都填了内容之前禁用确认按钮，
 * 避免用户输入空密码或两次密码不一致就把助手锁上（那样就再也解不开了）。
 *
 * @param assistant 待加密的助手（这里只用来展示名字，密码摘要由调用方计算）
 * @param onConfirm 校验通过后回调输入的新密码
 * @param onDismiss 关闭弹窗
 */
@Composable
fun AssistantSetPasswordDialog(
    assistant: Assistant,
    onConfirm: (password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val confirmFocusRequester = remember { FocusRequester() }

    val emptyError = stringResource(R.string.assistant_lock_password_empty)
    val mismatchError = stringResource(R.string.assistant_lock_password_mismatch)

    fun submit() {
        when {
            password.isEmpty() -> errorMessage = emptyError
            password != confirmPassword -> errorMessage = mismatchError
            else -> {
                errorMessage = null
                onConfirm(password)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.assistant_lock_set_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = assistant.name.ifBlank {
                        stringResource(R.string.assistant_page_default_assistant)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.assistant_lock_set_message),
                    style = MaterialTheme.typography.bodyMedium,
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.assistant_lock_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { confirmFocusRequester.requestFocus() },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.assistant_lock_password_confirm)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    isError = errorMessage != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(confirmFocusRequester),
                )

                errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { submit() },
                enabled = password.isNotEmpty() && confirmPassword.isNotEmpty(),
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * 「隐藏手势」触发的解锁弹窗：输入密码后解锁所有密码匹配的加密助手。
 *
 * @param lockedCount 当前加密助手数量，为 0 时只提示没有任何加密助手并禁用确认
 * @param onUnlock 校验并解锁，返回是否至少解锁了一个助手
 * @param onDismiss 关闭弹窗
 */
@Composable
fun AssistantUnlockDialog(
    lockedCount: Int,
    onUnlock: (password: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val hasLockedAssistants = lockedCount > 0
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val emptyError = stringResource(R.string.assistant_lock_password_empty)
    val wrongError = stringResource(R.string.assistant_lock_password_wrong)

    fun submit() {
        if (!hasLockedAssistants) return
        if (password.isEmpty()) {
            errorMessage = emptyError
            return
        }
        if (onUnlock(password)) {
            errorMessage = null
            onDismiss()
        } else {
            errorMessage = wrongError
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.assistant_lock_unlock_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.assistant_lock_unlock_message),
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (hasLockedAssistants) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            errorMessage = null
                        },
                        label = { Text(stringResource(R.string.assistant_lock_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        isError = errorMessage != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.assistant_lock_no_hidden),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { submit() },
                enabled = hasLockedAssistants && password.isNotEmpty(),
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
