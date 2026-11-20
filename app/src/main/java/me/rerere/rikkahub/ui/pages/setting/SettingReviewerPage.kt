package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.drop
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Repeat
import me.rerere.hugeicons.stroke.SlidersHorizontal
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_REVIEWER_PROMPT
import me.rerere.rikkahub.data.ai.prompts.REVIEWER_PRESETS
import me.rerere.rikkahub.data.ai.prompts.ReviewerPresetIds
import me.rerere.rikkahub.ui.components.ai.ModelField
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.ModelListState
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.TextArea
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/** 审查不通过时最多再重生成几次；0 表示只审查、不重生成 */
private val REVIEWER_MAX_RETRY_OPTIONS = listOf(0, 1, 2, 3)

// 审查 prompt 里换行很多，列表里预览时压成一行
private val ReviewerPromptWhitespace = Regex("\\s+")

@Composable
fun SettingReviewerPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val reviewer = settings.reviewerSetting

    // 模型选择弹窗和 prompt 编辑弹窗都放在列表外面：CardGroup / LazyColumn 里放不了弹窗
    val reviewerModel = rememberModelListState(
        modelId = reviewer.modelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )
    var editingPrompt by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_reviewer_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ① 总开关：关掉之后下面的配置仍然可以改，重新打开时直接生效
            item {
                CardGroup {
                    switchItem(
                        checked = reviewer.enabled,
                        onCheckedChange = { enabled ->
                            vm.updateSettings { latest ->
                                latest.copy(
                                    reviewerSetting = latest.reviewerSetting.copy(enabled = enabled)
                                )
                            }
                        },
                        supportingContent = { Text(stringResource(R.string.setting_reviewer_enable_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_reviewer_enable)) },
                    )
                }
            }

            // ② 审查模型：没设置时跟快速模型走，所以要能清掉
            item {
                val containerColor = MaterialTheme.colorScheme.tertiaryContainer
                val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                CardGroup {
                    reviewerModelItem(
                        icon = HugeIcons.Brain02,
                        title = R.string.setting_reviewer_model,
                        description = R.string.setting_reviewer_model_desc,
                        state = reviewerModel,
                        iconContainerColor = containerColor,
                        iconContentColor = contentColor,
                        placeholder = R.string.setting_reviewer_model_follow_fast,
                        onClear = {
                            vm.updateSettings { latest ->
                                latest.copy(reviewerSetting = latest.reviewerSetting.copy(modelId = null))
                            }
                        },
                    )
                }
            }

            // ③ 审查强度：选预设时把整段 prompt 一起换掉，保证 presetId 与 prompt 始终对得上
            item {
                val containerColor = MaterialTheme.colorScheme.primaryContainer
                val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                CardGroup {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.setting_reviewer_strength)) },
                        supportingContent = {
                            // 只展示当前选中预设的说明，三行全列太长
                            Text(stringResource(reviewerPresetDescriptionRes(reviewer.presetId)))
                        },
                        leadingContent = {
                            CardGroupIcon(
                                HugeIcons.SlidersHorizontal,
                                containerColor = containerColor,
                                contentColor = contentColor,
                            )
                        },
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            REVIEWER_PRESETS.fastForEach { preset ->
                                FilterChip(
                                    selected = reviewer.presetId == preset.id,
                                    onClick = {
                                        vm.updateSettings { latest ->
                                            latest.copy(
                                                reviewerSetting = latest.reviewerSetting.copy(
                                                    presetId = preset.id,
                                                    prompt = preset.prompt,
                                                )
                                            )
                                        }
                                    },
                                    label = { Text(stringResource(reviewerPresetLabelRes(preset.id))) },
                                )
                            }
                            // 自定义只是个标记：选它不动 prompt，用户接着在下面自己写
                            FilterChip(
                                selected = reviewer.presetId == ReviewerPresetIds.CUSTOM,
                                onClick = {
                                    vm.updateSettings { latest ->
                                        latest.copy(
                                            reviewerSetting = latest.reviewerSetting.copy(
                                                presetId = ReviewerPresetIds.CUSTOM,
                                            )
                                        )
                                    }
                                },
                                label = { Text(stringResource(R.string.setting_reviewer_preset_custom)) },
                            )
                        }
                    }
                }
            }

            // ④ 审查 prompt：列表里只显示压成一行的预览，全文在弹窗里编辑
            item {
                val containerColor = MaterialTheme.colorScheme.secondaryContainer
                val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                CardGroup {
                    reviewerPromptItem(
                        prompt = reviewer.prompt,
                        icon = HugeIcons.AiEditing,
                        iconContainerColor = containerColor,
                        iconContentColor = contentColor,
                        onClick = { editingPrompt = true },
                    )
                }
            }

            // ⑤ 高级：重试次数与放行策略
            item {
                val containerColor = MaterialTheme.colorScheme.primaryContainer
                val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardGroup {
                        formItem(
                            headlineContent = { Text(stringResource(R.string.setting_reviewer_max_retry)) },
                            supportingContent = { Text(stringResource(R.string.setting_reviewer_max_retry_desc)) },
                            leadingContent = {
                                CardGroupIcon(
                                    HugeIcons.Repeat,
                                    containerColor = containerColor,
                                    contentColor = contentColor,
                                )
                            },
                        ) {
                            ConnectedChoiceButtons(
                                options = REVIEWER_MAX_RETRY_OPTIONS,
                                selected = reviewer.maxRetry,
                                onSelect = { retry ->
                                    vm.updateSettings { latest ->
                                        latest.copy(reviewerSetting = latest.reviewerSetting.copy(maxRetry = retry))
                                    }
                                },
                            ) { option, selected ->
                                ConnectedChoiceLabel(
                                    text = option.toString(),
                                    selected = selected,
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.setting_reviewer_fail_open_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }

    ModelListSheet(state = reviewerModel) { model ->
        vm.updateSettings { latest ->
            latest.copy(reviewerSetting = latest.reviewerSetting.copy(modelId = model.id))
        }
    }

    if (editingPrompt) {
        ReviewerPromptEditSheet(
            prompt = reviewer.prompt,
            onPromptChange = { value ->
                // 正文和某套预设完全一致时认回那套预设（重置为默认就走这条路），否则标记成自定义
                val matchedPreset = REVIEWER_PRESETS.find { it.prompt == value }
                vm.updateSettings { latest ->
                    latest.copy(
                        reviewerSetting = latest.reviewerSetting.copy(
                            prompt = value,
                            presetId = matchedPreset?.id ?: ReviewerPresetIds.CUSTOM,
                        )
                    )
                }
            },
            onDismiss = { editingPrompt = false },
        )
    }
}

private fun reviewerPresetLabelRes(presetId: String): Int = when (presetId) {
    ReviewerPresetIds.STRICT -> R.string.setting_reviewer_preset_strict
    ReviewerPresetIds.LOOSE -> R.string.setting_reviewer_preset_loose
    ReviewerPresetIds.CUSTOM -> R.string.setting_reviewer_preset_custom
    else -> R.string.setting_reviewer_preset_balanced
}

private fun reviewerPresetDescriptionRes(presetId: String): Int = when (presetId) {
    ReviewerPresetIds.STRICT -> R.string.setting_reviewer_preset_strict_desc
    ReviewerPresetIds.LOOSE -> R.string.setting_reviewer_preset_loose_desc
    ReviewerPresetIds.BALANCED -> R.string.setting_reviewer_preset_balanced_desc
    // 自定义没有固定阈值，用通用的说明
    else -> R.string.setting_reviewer_strength_desc
}

// 一项默认模型：图标、用途说明，下面是显示当前模型的选择按钮
// SettingModelPage 里同名的是 private，这里留一份等价的实现，不去改那边的可见性
private fun CardGroupScope.reviewerModelItem(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes description: Int,
    state: ModelListState,
    iconContainerColor: Color,
    iconContentColor: Color,
    @StringRes placeholder: Int = R.string.model_list_select_model,
    onClear: (() -> Unit)? = null,
) = formItem(
    headlineContent = { Text(stringResource(title)) },
    supportingContent = { Text(stringResource(description)) },
    leadingContent = {
        CardGroupIcon(icon, containerColor = iconContainerColor, contentColor = iconContentColor)
    },
) {
    ModelField(
        model = state.currentModel,
        onClick = { state.open() },
        placeholder = stringResource(placeholder),
        onClear = onClear,
    )
}

// 一项提示词：标题下面预览当前内容，点击后在弹窗里编辑
private fun CardGroupScope.reviewerPromptItem(
    prompt: String,
    icon: ImageVector,
    iconContainerColor: Color,
    iconContentColor: Color,
    onClick: () -> Unit,
) = item(
    onClick = onClick,
    leadingContent = {
        CardGroupIcon(icon, containerColor = iconContainerColor, contentColor = iconContentColor)
    },
    headlineContent = { Text(stringResource(R.string.setting_reviewer_prompt)) },
    supportingContent = {
        Text(
            // prompt 里换行很多，压成一行才看得到实际内容
            text = remember(prompt) { prompt.trim().replace(ReviewerPromptWhitespace, " ") },
            // 只留一行：超过两行 ListItem 会把右侧箭头顶到上面去
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    },
    trailingContent = {
        Icon(
            HugeIcons.ArrowRight01,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
    },
)

@Composable
private fun ReviewerPromptEditSheet(
    prompt: String,
    onPromptChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 长 prompt 用 TextArea：自带全屏编辑，键盘弹起时也不至于只剩两行
    val state = rememberTextFieldState(initialText = prompt)
    LaunchedEffect(Unit) {
        // drop(1)：跳过首次回填，否则一打开弹窗就会把预设改成自定义
        snapshotFlow { state.text.toString() }
            .drop(1)
            .collect(onPromptChange)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 直接全展开，半展开时键盘会把输入框整个盖住
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_reviewer_prompt),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.setting_reviewer_prompt_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextArea(
                state = state,
                modifier = Modifier.weight(1f, fill = false),
                label = stringResource(R.string.setting_reviewer_prompt),
                minLines = 8,
                maxLines = 15,
            )
            Text(
                text = stringResource(R.string.setting_reviewer_prompt_sheet_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    // 只回填输入框：文本变化会经 snapshotFlow 写回设置，正文与平衡版一致时预设自动认回平衡
                    state.setTextAndPlaceCursorAtEnd(DEFAULT_REVIEWER_PROMPT)
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.setting_model_page_reset_to_default))
            }
        }
    }
}
