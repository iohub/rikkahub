package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ImageGenerationConfig
import me.rerere.rikkahub.ui.components.ai.ModelField
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

// 出图尺寸只认「宽x高」，其它写法原样透传给服务端也没意义，干脆不落盘
private val SizePattern = Regex("^\\d+x\\d+$")

// 步数空着或写乱时回到服务端推荐的默认值
private const val DEFAULT_STEPS = 4
private const val MIN_STEPS = 1
private const val MAX_STEPS = 50

// 和 ImageGenerationConfig 的默认超时保持一致，冷启动长的服务端可以再往上调
private const val MIN_TIMEOUT_SECONDS = 60
private const val MAX_TIMEOUT_SECONDS = 3600

// 和服务端一致：地址已经带上该路径时不再重复拼
private const val GENERATE_PATH = "/api/generate"

@Composable
fun SettingImageGenerationPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.imageGenerationConfig
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 输入框以本地草稿为准：设置要写回存储再回流，直接绑配置的话打字快了会被旧值顶掉
    var baseUrlDraft by remember { mutableStateOf(config.baseUrl) }
    var tokenDraft by remember { mutableStateOf(config.authToken) }
    var stepsDraft by remember { mutableStateOf(config.steps.toString()) }
    var sizeDraft by remember { mutableStateOf(config.defaultSize) }
    var timeoutDraft by remember { mutableStateOf(config.timeoutSeconds.toString()) }
    var tokenVisible by remember { mutableStateOf(false) }

    // CardGroup 里放不了弹窗，模型选择的状态先在这里建好，弹窗统一挂在页面末尾
    val promptModelState = rememberModelListState(
        modelId = config.promptModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    // 一律基于最新配置改写，别拿当前帧的快照去覆盖别处刚改过的字段
    fun update(transform: (ImageGenerationConfig) -> ImageGenerationConfig) {
        vm.updateSettings { latest ->
            latest.copy(imageGenerationConfig = transform(latest.imageGenerationConfig))
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_image_generation))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                )
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (config.baseUrl.isBlank()) {
                        R.string.setting_image_generation_not_configured
                    } else {
                        R.string.setting_page_image_generation_desc
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            CardGroup {
                formItem(
                    headlineContent = {
                        Text(stringResource(R.string.setting_image_generation_base_url))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_image_generation_base_url_desc))
                    },
                ) {
                    OutlinedTextField(
                        value = baseUrlDraft,
                        onValueChange = { input ->
                            baseUrlDraft = input
                            val baseUrl = input.trim()
                            update { latest ->
                                if (latest.baseUrl == baseUrl) latest else latest.copy(baseUrl = baseUrl)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("http://192.168.1.10:8080") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                    )
                }

                formItem(
                    headlineContent = {
                        Text(stringResource(R.string.setting_image_generation_token))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_image_generation_token_desc))
                    },
                ) {
                    OutlinedTextField(
                        value = tokenDraft,
                        onValueChange = { input ->
                            tokenDraft = input
                            val token = input.trim()
                            update { latest ->
                                if (latest.authToken == token) latest else latest.copy(authToken = token)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (tokenVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { tokenVisible = !tokenVisible },
                            ) {
                                Icon(
                                    imageVector = if (tokenVisible) HugeIcons.ViewOff else HugeIcons.View,
                                    contentDescription = null,
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )
                }

                formItem(
                    headlineContent = {
                        Text(stringResource(R.string.setting_image_generation_prompt_model))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_image_generation_prompt_model_desc))
                    },
                ) {
                    // 允许清空：清掉之后跟着快速模型走
                    ModelField(
                        model = promptModelState.currentModel,
                        onClick = { promptModelState.open() },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = stringResource(R.string.setting_image_generation_prompt_model_follow_fast),
                        onClear = {
                            update { latest -> latest.copy(promptModelId = null) }
                        },
                    )
                }

                switchItem(
                    checked = config.enablePromptAgent,
                    onCheckedChange = { enabled ->
                        update { latest -> latest.copy(enablePromptAgent = enabled) }
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_image_generation_enable_prompt_agent_desc))
                    },
                    headlineContent = {
                        Text(stringResource(R.string.setting_image_generation_enable_prompt_agent))
                    },
                )

                formItem(
                    headlineContent = {
                        Text(stringResource(R.string.setting_image_generation_steps))
                    },
                ) {
                    OutlinedTextField(
                        value = stepsDraft,
                        onValueChange = { input ->
                            // 只留数字，最多两位，避免粘进来一长串东西
                            val digits = input.filter { it.isDigit() }.take(2)
                            stepsDraft = digits
                            // 空着或者写成 0 之类时回到默认步数
                            val steps = (digits.toIntOrNull() ?: DEFAULT_STEPS)
                                .coerceIn(MIN_STEPS, MAX_STEPS)
                            update { latest ->
                                if (latest.steps == steps) latest else latest.copy(steps = steps)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(DEFAULT_STEPS.toString()) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }

                // 强制使用默认尺寸：默认开启，开启后模型在工具调用里填的 size 一律被忽略
                switchItem(
                    checked = config.forceDefaultSize,
                    onCheckedChange = { forced ->
                        update { latest -> latest.copy(forceDefaultSize = forced) }
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_image_generation_force_default_size_desc))
                    },
                    headlineContent = {
                        Text(stringResource(R.string.setting_image_generation_force_default_size))
                    },
                )

                formItem(
                    headlineContent = {
                        Text(stringResource(R.string.setting_image_generation_default_size))
                    },
                ) {
                    OutlinedTextField(
                        value = sizeDraft,
                        onValueChange = { input ->
                            sizeDraft = input
                            // 写完整了才落盘，中间态保持原值，免得每敲一个字符就抖动一次
                            if (SizePattern.matches(input)) {
                                update { latest ->
                                    if (latest.defaultSize == input) latest else latest.copy(defaultSize = input)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("1024x1024") },
                        singleLine = true,
                    )
                }

                formItem(
                    headlineContent = {
                        Text(stringResource(R.string.setting_image_generation_timeout))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_image_generation_timeout_desc))
                    },
                ) {
                    OutlinedTextField(
                        value = timeoutDraft,
                        onValueChange = { input ->
                            val digits = input.filter { it.isDigit() }.take(4)
                            timeoutDraft = digits
                            // 空着时保留原值，别把用户原本配好的超时悄悄改成默认
                            digits.toIntOrNull()?.let { seconds ->
                                val timeout = seconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
                                update { latest ->
                                    if (latest.timeoutSeconds == timeout) latest else latest.copy(timeoutSeconds = timeout)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("1200") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }

                item(
                    headlineContent = {
                        Text(
                            text = buildString {
                                append("POST ")
                                append(config.baseUrl.trim().trimEnd('/').ifEmpty { "…" })
                                append(GENERATE_PATH)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }

    ModelListSheet(state = promptModelState) { model ->
        update { latest -> latest.copy(promptModelId = model.id) }
    }
}
