package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Workflow
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.ORCHESTRATION_REVIEWER_PRESETS
import me.rerere.rikkahub.data.ai.prompts.ORCHESTRATION_STAGE_PRESETS
import me.rerere.rikkahub.data.ai.prompts.ORCHESTRATION_TTS_VOICE_TAG
import me.rerere.rikkahub.data.ai.prompts.OrchestrationReviewerPresetIds
import me.rerere.rikkahub.data.ai.prompts.OrchestrationStagePreset
import me.rerere.rikkahub.data.ai.prompts.OrchestrationStagePresetIds
import me.rerere.rikkahub.data.ai.prompts.findOrchestrationReviewerPreset
import me.rerere.rikkahub.data.ai.prompts.findOrchestrationStagePreset
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.AgentStage
import me.rerere.rikkahub.data.model.AgentStageKind
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantOrchestration
import me.rerere.rikkahub.data.model.StageReviewerSetting
import me.rerere.rikkahub.ui.components.ai.ModelField
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/** 审查不通过时最多再生成几次；与 `SettingsNormalization` 的收敛范围保持一致 */
private val ORCHESTRATION_RETRY_OPTIONS = listOf(0, 1, 2, 3)

/**
 * 当前在给哪个字段选模型。
 *
 * 整页只放一个 `ModelListSheet`：它是模态弹窗，同时存在多份既浪费又可能叠在一起。
 * 用这个标记决定选中模型后写回哪个阶段的哪个字段。
 */
private sealed interface ModelTarget {
    data class StageModel(val stageId: Uuid) : ModelTarget

    data class StageReviewerModel(val stageId: Uuid) : ModelTarget
}

@Composable
fun AssistantOrchestrationPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // 设置还没读出来时 vm.assistant 是一个占位对象（id 是随机生成的），拿它对不上就说明还没加载好
    val assistantId = remember(id) { runCatching { Uuid.parse(id) }.getOrNull() }
    val loaded = settings.init && assistantId != null && assistant.id == assistantId

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_orchestration_title))
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
        if (!loaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                if (settings.init) {
                    Text(
                        text = stringResource(R.string.assistant_orchestration_missing_assistant),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    CircularProgressIndicator()
                }
            }
        } else {
            AssistantOrchestrationContent(
                innerPadding = innerPadding,
                assistant = assistant,
                providers = providers,
                fastModelId = settings.fastModelId,
                onUpdate = { vm.update(it) },
            )
        }
    }
}

@Composable
private fun AssistantOrchestrationContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    providers: List<ProviderSetting>,
    fastModelId: Uuid,
    onUpdate: (Assistant) -> Unit,
) {
    val toaster = LocalToaster.current
    val orchestration = assistant.orchestration
    val stages = orchestration.stages

    var modelTarget by remember { mutableStateOf<ModelTarget?>(null) }
    var deletingStageId by remember { mutableStateOf<Uuid?>(null) }

    fun applyStages(next: List<AgentStage>) {
        onUpdate(assistant.copy(orchestration = orchestration.copy(stages = next)))
    }

    fun updateStage(stageId: Uuid, transform: (AgentStage) -> AgentStage) {
        applyStages(stages.map { stage -> if (stage.id == stageId) transform(stage) else stage })
    }

    // 弹窗显示的是「当前目标字段」的模型：切到另一个字段时传进去的 modelId 会跟着变，
    // rememberModelListState 每次重组都会 update 一次，所以不需要为每个阶段各建一份
    val targetModelId = when (val target = modelTarget) {
        is ModelTarget.StageModel -> stages.find { it.id == target.stageId }?.modelId
        is ModelTarget.StageReviewerModel -> stages.find { it.id == target.stageId }?.reviewer?.modelId
        null -> null
    }
    val modelListState = rememberModelListState(
        modelId = targetModelId,
        providers = providers,
        type = ModelType.CHAT,
    )
    ModelListSheet(
        state = modelListState,
        onSelect = { model ->
            when (val target = modelTarget) {
                is ModelTarget.StageModel -> updateStage(target.stageId) { it.copy(modelId = model.id) }
                is ModelTarget.StageReviewerModel -> updateStage(target.stageId) {
                    it.copy(reviewer = it.reviewer.copy(modelId = model.id))
                }

                null -> Unit
            }
            modelTarget = null
        },
    )

    val exampleDialogueName = stringResource(R.string.assistant_orchestration_example_dialogue_stage)
    val exampleTtsName = stringResource(R.string.assistant_orchestration_example_tts_stage)
    val exampleLoadedMessage = stringResource(R.string.assistant_orchestration_example_loaded)

    /**
     * 一键铺一套「对话 + 语音控制块」：两套预设的 prompt 与审查预设都直接取自后端常量，
     * 界面只负责把它们拼成两个阶段，不再各写一份默认正文。
     */
    fun loadExample() {
        val dialoguePreset = findOrchestrationStagePreset(OrchestrationStagePresetIds.DIALOGUE)
        val ttsPreset = findOrchestrationStagePreset(OrchestrationStagePresetIds.TTS_VOICE)
        val dialogueReviewer = findOrchestrationReviewerPreset(OrchestrationReviewerPresetIds.DIALOGUE)
        val ttsReviewer = findOrchestrationReviewerPreset(OrchestrationReviewerPresetIds.TTS_VOICE)

        val exampleStages = listOf(
            AgentStage(
                name = exampleDialogueName,
                kind = AgentStageKind.DIALOGUE,
                outputTag = "",
                prompt = dialoguePreset?.prompt.orEmpty(),
                reviewer = StageReviewerSetting(
                    enabled = true,
                    presetId = dialogueReviewer?.id,
                    prompt = dialogueReviewer?.prompt.orEmpty(),
                    maxRetry = 1,
                ),
            ),
            AgentStage(
                name = exampleTtsName,
                kind = AgentStageKind.BLOCK,
                outputTag = ttsPreset?.outputTag ?: ORCHESTRATION_TTS_VOICE_TAG,
                prompt = ttsPreset?.prompt.orEmpty(),
                reviewer = StageReviewerSetting(
                    enabled = true,
                    presetId = ttsReviewer?.id,
                    prompt = ttsReviewer?.prompt.orEmpty(),
                    maxRetry = 1,
                ),
            ),
        )
        // 语音块的播放依赖 Tts 选项，缺了它示例里的第 2 个阶段写好指令也播不出来
        val localTools = if (LocalToolOption.Tts in assistant.localTools) {
            assistant.localTools
        } else {
            assistant.localTools + LocalToolOption.Tts
        }
        onUpdate(
            assistant.copy(
                localTools = localTools,
                orchestration = AssistantOrchestration(enabled = true, stages = exampleStages),
            )
        )
        toaster.show(message = exampleLoadedMessage, type = ToastType.Success)
    }

    fun addStage(kind: AgentStageKind) {
        applyStages(stages + AgentStage(kind = kind))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .padding(bottom = 16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardGroup {
            switchItem(
                checked = orchestration.enabled,
                onCheckedChange = { enabled ->
                    onUpdate(assistant.copy(orchestration = orchestration.copy(enabled = enabled)))
                },
                supportingContent = { Text(stringResource(R.string.assistant_orchestration_enable_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_orchestration_enable)) },
            )
        }

        FilledTonalButton(
            onClick = { loadExample() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.assistant_orchestration_load_example))
        }

        // 编排开着却没有可用的对话阶段时，正文不会经过任何阶段审查器，先提醒一句
        if (orchestration.enabled && stages.none { it.enabled && it.kind == AgentStageKind.DIALOGUE }) {
            Text(
                text = stringResource(R.string.assistant_orchestration_missing_dialogue_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        SectionHeader(
            title = stringResource(R.string.assistant_orchestration_stages),
            description = stringResource(R.string.assistant_orchestration_stages_desc),
        )

        if (stages.isEmpty()) {
            Text(
                text = stringResource(R.string.assistant_orchestration_empty_stages),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        stages.forEachIndexed { index, stage ->
            OrchestrationStageCard(
                index = index,
                stage = stage,
                stageModel = stage.modelId?.let { providers.findModelById(it) },
                // 没配审查模型时后端跟随快速模型，这里照着显示，免得用户以为审查没模型可用
                reviewerModel = providers.findModelById(stage.reviewer.modelId ?: fastModelId),
                stageFallbackPreset = stageFallbackPreset(stage),
                onStageChange = { transform -> updateStage(stage.id, transform) },
                onReviewerChange = { transform ->
                    updateStage(stage.id) { it.copy(reviewer = transform(it.reviewer)) }
                },
                onRequestStageModel = {
                    modelTarget = ModelTarget.StageModel(stage.id)
                    modelListState.open()
                },
                onRequestReviewerModel = {
                    modelTarget = ModelTarget.StageReviewerModel(stage.id)
                    modelListState.open()
                },
                onDelete = { deletingStageId = stage.id },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { addStage(AgentStageKind.BLOCK) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.assistant_orchestration_add_block_stage))
            }
            // 只允许有一个对话阶段：它决定交付给用户的正文，多配了也只会用到第一个
            if (stages.none { it.kind == AgentStageKind.DIALOGUE }) {
                OutlinedButton(
                    onClick = { addStage(AgentStageKind.DIALOGUE) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.assistant_orchestration_add_dialogue_stage))
                }
            }
        }
    }

    val deletingStage = deletingStageId?.let { stageId -> stages.find { it.id == stageId } }
    if (deletingStage != null) {
        val index = stages.indexOfFirst { it.id == deletingStage.id }
        AlertDialog(
            onDismissRequest = { deletingStageId = null },
            title = {
                Text(stringResource(R.string.assistant_orchestration_delete_stage_confirm_title))
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.assistant_orchestration_delete_stage_confirm_message,
                        deletingStage.name.ifBlank {
                            stringResource(R.string.assistant_orchestration_stage, index + 1)
                        },
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        applyStages(stages.filterNot { it.id == deletingStage.id })
                        deletingStageId = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingStageId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun OrchestrationStageCard(
    index: Int,
    stage: AgentStage,
    stageModel: Model?,
    reviewerModel: Model?,
    stageFallbackPreset: OrchestrationStagePreset?,
    onStageChange: ((AgentStage) -> AgentStage) -> Unit,
    onReviewerChange: ((StageReviewerSetting) -> StageReviewerSetting) -> Unit,
    onRequestStageModel: () -> Unit,
    onRequestReviewerModel: () -> Unit,
    onDelete: () -> Unit,
) {
    val kindLabel = stringResource(stageKindLabelRes(stage.kind))
    val clearStageModel: (() -> Unit)? = if (stage.modelId != null) {
        { onStageChange { it.copy(modelId = null) } }
    } else null
    val clearReviewerModel: (() -> Unit)? = if (stage.reviewer.modelId != null) {
        { onReviewerChange { it.copy(modelId = null) } }
    } else null
    // 没起名字时用序号占位，免得列表里两张卡片看起来一模一样
    val stageTitle = stage.name.ifBlank {
        stringResource(R.string.assistant_orchestration_stage, index + 1)
    }

    CardGroup {
        formItem(
            headlineContent = { Text(stageTitle) },
            supportingContent = { Text(kindLabel) },
            leadingContent = {
                CardGroupIcon(
                    icon = if (stage.kind == AgentStageKind.DIALOGUE) HugeIcons.Message02 else HugeIcons.Workflow,
                    containerColor = if (stage.enabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (stage.enabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            trailingContent = {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = HugeIcons.Delete02,
                        contentDescription = stringResource(R.string.assistant_orchestration_delete_stage),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            },
        ) {
            OutlinedTextField(
                value = stage.name,
                onValueChange = { value -> onStageChange { it.copy(name = value) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.assistant_orchestration_stage_name)) },
                placeholder = { Text(stringResource(R.string.assistant_orchestration_stage, index + 1)) },
                singleLine = true,
            )
        }

        switchItem(
            checked = stage.enabled,
            onCheckedChange = { enabled -> onStageChange { it.copy(enabled = enabled) } },
            headlineContent = { Text(stringResource(R.string.assistant_orchestration_stage_enable)) },
        )

        formItem(
            headlineContent = { Text(stringResource(R.string.assistant_orchestration_kind)) },
            supportingContent = {
                if (stage.kind == AgentStageKind.DIALOGUE) {
                    Text(stringResource(R.string.assistant_orchestration_kind_dialogue_desc))
                }
            },
        ) {
            ConnectedChoiceButtons(
                options = AgentStageKind.entries,
                selected = stage.kind,
                onSelect = { kind -> onStageChange { it.copy(kind = kind) } },
            ) { kind, selected ->
                ConnectedChoiceLabel(
                    text = stringResource(stageKindLabelRes(kind)),
                    selected = selected,
                )
            }
        }

        // 标签只对块阶段有意义：对话阶段的产出就是正文本身，包标签反而会被当成一块内容
        if (stage.kind == AgentStageKind.BLOCK) {
            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_orchestration_output_tag)) },
                supportingContent = { Text(stringResource(R.string.assistant_orchestration_output_tag_desc)) },
            ) {
                OutlinedTextField(
                    value = stage.outputTag,
                    onValueChange = { value -> onStageChange { it.copy(outputTag = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(ORCHESTRATION_TTS_VOICE_TAG) },
                    singleLine = true,
                )
            }
        }

        formItem(
            headlineContent = { Text(stringResource(R.string.assistant_orchestration_model)) },
            supportingContent = { Text(stringResource(R.string.assistant_orchestration_model_desc)) },
        ) {
            ModelField(
                model = stageModel,
                onClick = onRequestStageModel,
                placeholder = stringResource(R.string.assistant_orchestration_model_follow_chat),
                onClear = clearStageModel,
            )
        }

        formItem(
            headlineContent = { Text(stringResource(R.string.assistant_orchestration_prompt)) },
            supportingContent = { Text(stringResource(R.string.assistant_orchestration_prompt_desc)) },
        ) {
            OutlinedTextField(
                value = stage.prompt,
                onValueChange = { value -> onStageChange { it.copy(prompt = value) } },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                // 留空时后端按类型回落到预设，把落回的那套说清楚，用户才知道不写会发生什么
                supportingText = {
                    Text(
                        text = if (stageFallbackPreset != null) {
                            stringResource(
                                R.string.assistant_orchestration_prompt_use_preset,
                                stageFallbackPreset.name,
                            )
                        } else {
                            stringResource(R.string.assistant_orchestration_prompt_no_preset)
                        }
                    )
                },
            )
        }

        switchItem(
            checked = stage.reviewer.enabled,
            onCheckedChange = { enabled -> onReviewerChange { it.copy(enabled = enabled) } },
            supportingContent = { Text(stringResource(R.string.assistant_orchestration_reviewer_desc)) },
            leadingContent = {
                CardGroupIcon(
                    icon = HugeIcons.Brain02,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            },
            headlineContent = { Text(stringResource(R.string.assistant_orchestration_reviewer)) },
        )

        if (stage.reviewer.enabled) {
            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_orchestration_reviewer_preset)) },
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ORCHESTRATION_REVIEWER_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = stage.reviewer.presetId == preset.id,
                            // 选预设就把整段 prompt 一起换掉，保证 presetId 与正文始终对得上
                            onClick = {
                                onReviewerChange { it.copy(presetId = preset.id, prompt = preset.prompt) }
                            },
                            label = { Text(preset.name) },
                        )
                    }
                    // 自定义只是个标记：选中它保留当前正文，接着在下面自己改
                    FilterChip(
                        selected = findOrchestrationReviewerPreset(stage.reviewer.presetId) == null,
                        onClick = { onReviewerChange { it.copy(presetId = null) } },
                        label = { Text(stringResource(R.string.assistant_orchestration_reviewer_preset_custom)) },
                    )
                }
            }

            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_orchestration_reviewer_prompt)) },
                supportingContent = {
                    Text(stringResource(R.string.assistant_orchestration_reviewer_prompt_desc))
                },
            ) {
                OutlinedTextField(
                    value = stage.reviewer.prompt,
                    onValueChange = { value ->
                        // 正文和某套预设完全一致时认回那套预设，否则标记成自定义
                        val matchedPreset = ORCHESTRATION_REVIEWER_PRESETS.find { it.prompt == value }
                        onReviewerChange { it.copy(prompt = value, presetId = matchedPreset?.id) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                )
            }

            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_orchestration_reviewer_max_retry)) },
                supportingContent = {
                    Text(stringResource(R.string.assistant_orchestration_reviewer_max_retry_desc))
                },
            ) {
                ConnectedChoiceButtons(
                    options = ORCHESTRATION_RETRY_OPTIONS,
                    selected = stage.reviewer.maxRetry.coerceIn(0, 3),
                    onSelect = { retry -> onReviewerChange { it.copy(maxRetry = retry) } },
                ) { option, selected ->
                    ConnectedChoiceLabel(text = option.toString(), selected = selected)
                }
            }

            formItem(
                headlineContent = { Text(stringResource(R.string.assistant_orchestration_reviewer_model)) },
                supportingContent = { Text(stringResource(R.string.assistant_orchestration_reviewer_model_desc)) },
            ) {
                ModelField(
                    model = reviewerModel,
                    onClick = onRequestReviewerModel,
                    placeholder = stringResource(R.string.assistant_orchestration_reviewer_model_follow_fast),
                    onClear = clearReviewerModel,
                )
            }
        }
    }
}

/**
 * 阶段没写 prompt 时后端会回落到哪套预设。
 *
 * 与 `AgentOrchestrator.defaultStagePrompt` 同一套规则：对话阶段用对话预设，块阶段先按 `outputTag`
 * 精确匹配，认不出再退到第一套块预设。界面照着它提示，避免「留空」和「实际用的正文」对不上。
 */
private fun stageFallbackPreset(stage: AgentStage): OrchestrationStagePreset? = when (stage.kind) {
    AgentStageKind.DIALOGUE -> findOrchestrationStagePreset(OrchestrationStagePresetIds.DIALOGUE)

    AgentStageKind.BLOCK -> ORCHESTRATION_STAGE_PRESETS.firstOrNull { preset ->
        preset.kind == AgentStageKind.BLOCK &&
            preset.outputTag.equals(stage.outputTag.trim(), ignoreCase = true)
    } ?: ORCHESTRATION_STAGE_PRESETS.firstOrNull { it.kind == AgentStageKind.BLOCK }
}

@StringRes
private fun stageKindLabelRes(kind: AgentStageKind): Int = when (kind) {
    AgentStageKind.DIALOGUE -> R.string.assistant_orchestration_kind_dialogue
    AgentStageKind.BLOCK -> R.string.assistant_orchestration_kind_block
}
