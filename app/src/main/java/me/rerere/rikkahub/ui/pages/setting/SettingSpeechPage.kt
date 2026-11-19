package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.asr.ASRProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DEFAULT_SYSTEM_TTS_ID
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.fallbackTTSProvider
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.longPressReorder
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.pages.setting.components.ASRProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.ProviderEditSheet
import me.rerere.rikkahub.ui.pages.setting.components.ProviderTypeSheet
import me.rerere.rikkahub.ui.pages.setting.components.TTSProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.typeName
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private enum class SpeechTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    TTS(HugeIcons.VolumeHigh, R.string.speech_tab_tts),
    ASR(HugeIcons.Mic01, R.string.speech_tab_asr);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
fun SettingSpeechPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editingTTSProvider by remember { mutableStateOf<TTSProviderSetting?>(null) }
    var editingASRProvider by remember { mutableStateOf<ASRProviderSetting?>(null) }
    val pagerState = rememberPagerState { SpeechTab.entries.size }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.speech_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    when (SpeechTab.entries[pagerState.targetPage]) {
                        SpeechTab.TTS -> AddTTSProviderButton(
                            // 内置 TTS 关闭时默认新建远程 TTS：否则新条目一保存就被列表过滤掉，像是没保存上
                            defaultProvider = {
                                if (settings.enableBuiltInTts) TTSProviderSetting.SystemTTS()
                                else TTSProviderSetting.RemoteTTS()
                            },
                            allowSystemTts = settings.enableBuiltInTts,
                        ) { newProvider ->
                            vm.updateSettings { latest ->
                                latest.copy(
                                    ttsProviders = listOf(newProvider) + latest.ttsProviders
                                )
                            }
                        }

                        SpeechTab.ASR -> AddASRProviderButton {
                            vm.updateSettings { latest ->
                                latest.copy(
                                    asrProviders = listOf(it) + latest.asrProviders,
                                    selectedASRProviderId = latest.selectedASRProviderId ?: it.id
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = SpeechTab.entries,
                modifier = Modifier.padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (SpeechTab.entries[page]) {
                    SpeechTab.TTS -> TTSProviderList(
                        settings = settings,
                        onUpdateSettings = vm::updateSettings,
                        onEdit = { editingTTSProvider = it },
                    )

                    SpeechTab.ASR -> ASRProviderList(
                        settings = settings,
                        onUpdateSettings = vm::updateSettings,
                        onEdit = { editingASRProvider = it },
                    )
                }
            }
        }
    }

    editingTTSProvider?.let { provider ->
        ProviderEditSheet(
            title = stringResource(R.string.setting_tts_page_edit_provider),
            initial = provider,
            confirmText = stringResource(R.string.chat_page_save),
            onConfirm = { edited ->
                vm.updateSettings { latest ->
                    latest.copy(ttsProviders = latest.ttsProviders.map { if (it.id == provider.id) edited else it })
                }
            },
            onDismiss = { editingTTSProvider = null },
        ) { value, onValueChange, modifier ->
            TTSProviderConfigure(
                setting = value,
                // 内置 TTS 关闭时不给「系统 TTS」这个类型选项，否则切过去条目就从列表里消失了
                allowSystemTts = settings.enableBuiltInTts,
                onValueChange = onValueChange,
                modifier = modifier,
            )
        }
    }

    editingASRProvider?.let { provider ->
        ProviderEditSheet(
            title = stringResource(R.string.setting_asr_page_edit_provider),
            initial = provider,
            confirmText = stringResource(R.string.chat_page_save),
            onConfirm = { edited ->
                vm.updateSettings { latest ->
                    latest.copy(asrProviders = latest.asrProviders.map { if (it.id == provider.id) edited else it })
                }
            },
            onDismiss = { editingASRProvider = null },
        ) { value, onValueChange, modifier ->
            ASRProviderConfigure(setting = value, onValueChange = onValueChange, modifier = modifier)
        }
    }
}

@Composable
private fun TTSProviderList(
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onEdit: (TTSProviderSetting) -> Unit,
    modifier: Modifier = Modifier
) {
    // 内置 TTS 关闭时不展示系统 TTS：条目仍留在 ttsProviders 里，重新打开开关就能再选
    val visibleProviders = remember(settings.ttsProviders, settings.enableBuiltInTts) {
        if (settings.enableBuiltInTts) settings.ttsProviders
        else settings.ttsProviders.filterNot { it is TTSProviderSetting.SystemTTS }
    }
    val visibleIds = remember(visibleProviders) { visibleProviders.map { it.id } }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // from/to 是过滤后列表的下标，不能直接拿去操作原始 ttsProviders：
        // 先把可见项按新顺序重排，再填回它们在原始列表里占的槽位，被隐藏的系统 TTS 留在原位
        onUpdateSettings { latest ->
            val latestVisible = if (latest.enableBuiltInTts) {
                latest.ttsProviders
            } else {
                latest.ttsProviders.filterNot { it is TTSProviderSetting.SystemTTS }
            }
            // 拖拽期间列表变过（增删或切换了内置开关），这次拖拽的下标已经对不上，丢弃
            if (latestVisible.map { it.id } != visibleIds) latest
            else latest.copy(
                ttsProviders = latest.ttsProviders.reorderVisible(latestVisible, from.index, to.index)
            )
        }
    }
    var deleteTarget by remember { mutableStateOf<TTSProviderSetting?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        state = lazyListState
    ) {
        items(visibleProviders, key = { it.id }) { provider ->
            ReorderableItem(
                state = reorderableState,
                key = provider.id
            ) { isDragging ->
                val isSelected = settings.selectedTTSProviderId == provider.id
                SpeechProviderItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(longPressReorder(isDragging)),
                    name = provider.name.ifEmpty { stringResource(R.string.setting_tts_page_default_name) },
                    typeName = provider.typeName,
                    isSelected = isSelected,
                    deletable = provider.id != DEFAULT_SYSTEM_TTS_ID,
                    onSelect = {
                        onUpdateSettings { it.copy(selectedTTSProviderId = provider.id) }
                    },
                    onEdit = {
                        onEdit(provider)
                    },
                    onDelete = {
                        deleteTarget = provider
                    },
                    action = if (isSelected) {
                        { TTSTestButton() }
                    } else null,
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { target ->
                val newProviders = settings.ttsProviders.filter { it.id != target.id }
                // 删的正好是选中项时按门禁顺序回退：默认远程 TTS → 第一个非系统 TTS，
                // 只有内置 TTS 打开时才允许回退到系统 TTS
                val newSelectedId = if (settings.selectedTTSProviderId == target.id) {
                    newProviders.fallbackTTSProvider(allowSystemTTS = settings.enableBuiltInTts)?.id
                        ?: settings.selectedTTSProviderId
                } else {
                    settings.selectedTTSProviderId
                }
                onUpdateSettings {
                    it.copy(
                        ttsProviders = newProviders,
                        selectedTTSProviderId = newSelectedId
                    )
                }
            }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.common_delete_confirm_message, deleteTarget?.name.orEmpty()))
    }
}

/**
 * 把过滤后列表上发生的拖拽套回原始列表。
 *
 * [visible] 是界面上看到的顺序，[fromIndex] / [toIndex] 也是它的下标。重排后可见项按新顺序依次
 * 填回它们在原始列表里占的槽位，被隐藏的内置系统 TTS 保持在原位置 —— 直接用过滤后的下标去操作
 * 原始列表会把它挪到错误的位置。
 *
 * internal 而非 private：索引映射最容易出错，单元测试要直接覆盖它。
 */
internal fun List<TTSProviderSetting>.reorderVisible(
    visible: List<TTSProviderSetting>,
    fromIndex: Int,
    toIndex: Int,
): List<TTSProviderSetting> {
    if (fromIndex == toIndex) return this
    if (fromIndex !in visible.indices || toIndex !in visible.indices) return this
    val reordered = visible.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    val byId = associateBy { it.id }
    // 拖拽期间数据被别处改过（比如刚拖动的提供商被删除），这次拖拽丢弃
    if (reordered.any { it.id !in byId }) return this
    val visibleIdSet = visible.mapTo(HashSet()) { it.id }
    val order = reordered.iterator()
    return map { provider ->
        if (provider.id in visibleIdSet) byId.getValue(order.next().id) else provider
    }
}

@Composable
private fun ASRProviderList(
    settings: Settings,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    onEdit: (ASRProviderSetting) -> Unit,
    modifier: Modifier = Modifier
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val newProviders = settings.asrProviders.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        onUpdateSettings { it.copy(asrProviders = newProviders) }
    }
    var deleteTarget by remember { mutableStateOf<ASRProviderSetting?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        state = lazyListState
    ) {
        items(settings.asrProviders, key = { it.id }) { provider ->
            ReorderableItem(
                state = reorderableState,
                key = provider.id
            ) { isDragging ->
                SpeechProviderItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(longPressReorder(isDragging)),
                    name = provider.name.ifEmpty { stringResource(R.string.setting_asr_page_default_name) },
                    typeName = provider.typeName,
                    isSelected = settings.selectedASRProviderId == provider.id,
                    onSelect = {
                        onUpdateSettings { it.copy(selectedASRProviderId = provider.id) }
                    },
                    onEdit = {
                        onEdit(provider)
                    },
                    onDelete = {
                        deleteTarget = provider
                    }
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { target ->
                val newProviders = settings.asrProviders.filter { it.id != target.id }
                val newSelectedId =
                    if (settings.selectedASRProviderId == target.id) {
                        newProviders.firstOrNull()?.id
                    } else {
                        settings.selectedASRProviderId
                    }
                onUpdateSettings {
                    it.copy(
                        asrProviders = newProviders,
                        selectedASRProviderId = newSelectedId
                    )
                }
            }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.common_delete_confirm_message, deleteTarget?.name.orEmpty()))
    }
}

// 类型在配置面板里选，新建时的默认类型由调用方决定（内置 TTS 关闭时给远程 TTS）
@Composable
private fun AddTTSProviderButton(
    defaultProvider: () -> TTSProviderSetting,
    allowSystemTts: Boolean,
    onAdd: (TTSProviderSetting) -> Unit,
) {
    var newProvider by remember { mutableStateOf<TTSProviderSetting?>(null) }

    IconButton(
        onClick = { newProvider = defaultProvider() }
    ) {
        Icon(HugeIcons.Add01, stringResource(R.string.setting_tts_page_add_provider_content_description))
    }

    newProvider?.let { provider ->
        ProviderEditSheet(
            title = stringResource(R.string.setting_tts_page_add_provider),
            initial = provider,
            confirmText = stringResource(R.string.setting_tts_page_add),
            onConfirm = onAdd,
            onDismiss = { newProvider = null },
        ) { value, onValueChange, modifier ->
            TTSProviderConfigure(
                setting = value,
                // 新建时同样不提供系统 TTS 类型选项
                allowSystemTts = allowSystemTts,
                onValueChange = onValueChange,
                modifier = modifier,
            )
        }
    }
}

// 新建时先选类型，再进配置面板
@Composable
private fun AddASRProviderButton(onAdd: (ASRProviderSetting) -> Unit) {
    var showTypeSheet by remember { mutableStateOf(false) }
    var newProvider by remember { mutableStateOf<ASRProviderSetting?>(null) }
    val factories = remember {
        listOf<() -> ASRProviderSetting>(
            { ASRProviderSetting.OpenAIRealtime() },
            { ASRProviderSetting.DashScope() },
            { ASRProviderSetting.Volcengine() },
            { ASRProviderSetting.MiMo() },
            { ASRProviderSetting.Step() },
            { ASRProviderSetting.DeepSeek() },
        )
    }

    IconButton(
        onClick = { showTypeSheet = true }
    ) {
        Icon(HugeIcons.Add01, stringResource(R.string.setting_asr_page_add_provider))
    }

    if (showTypeSheet) {
        ProviderTypeSheet(
            title = stringResource(R.string.setting_asr_page_add_provider),
            types = factories,
            typeName = { create -> remember(create) { create() }.typeName },
            onSelect = { create ->
                showTypeSheet = false
                newProvider = create()
            },
            onDismiss = { showTypeSheet = false },
        )
    }

    newProvider?.let { provider ->
        ProviderEditSheet(
            title = stringResource(R.string.setting_asr_page_add_provider),
            initial = provider,
            confirmText = stringResource(R.string.setting_tts_page_add),
            onConfirm = onAdd,
            onDismiss = { newProvider = null },
        ) { value, onValueChange, modifier ->
            ASRProviderConfigure(setting = value, onValueChange = onValueChange, modifier = modifier)
        }
    }
}

private val TTSProviderSetting.typeName: String
    @Composable get() = when (this) {
        is TTSProviderSetting.OpenAI -> stringResource(R.string.setting_tts_page_provider_openai)
        is TTSProviderSetting.Gemini -> stringResource(R.string.setting_tts_page_provider_gemini)
        is TTSProviderSetting.MiniMax -> "MiniMax"
        is TTSProviderSetting.SystemTTS -> stringResource(R.string.setting_tts_page_provider_system)
        is TTSProviderSetting.Qwen -> "Qwen"
        is TTSProviderSetting.Groq -> "Groq"
        is TTSProviderSetting.XAI -> "xAI"
        is TTSProviderSetting.MiMo -> "MiMo"
        is TTSProviderSetting.Step -> "Step"
        is TTSProviderSetting.ElevenLabs -> "ElevenLabs"
        is TTSProviderSetting.FishAudio -> "Fish Audio"
        is TTSProviderSetting.Volcengine -> "火山引擎"
        is TTSProviderSetting.DeepSeek -> "DeepSeek"
        is TTSProviderSetting.RemoteTTS -> stringResource(R.string.setting_tts_page_provider_remote)
    }

// TTS 和 ASR 列表共用的服务商卡片，点卡片编辑，点单选圈设为当前使用
@Composable
private fun SpeechProviderItem(
    name: String,
    typeName: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    deletable: Boolean = true,
    // 跟在标签后面的操作，比如试听
    action: (@Composable () -> Unit)? = null,
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            CustomColors.listItemColors.containerColor
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )

    Card(
        onClick = onEdit,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoAIIcon(
                name = name,
                modifier = Modifier.size(40.dp)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = typeName,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f)
                )
                if (isSelected) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tag(type = TagType.SUCCESS) {
                            Text(stringResource(R.string.setting_tts_page_selected))
                        }
                        action?.invoke()
                    }
                }
            }

            RadioButton(
                selected = isSelected,
                onClick = onSelect
            )

            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        enabled = deletable,
                        onClick = onDelete,
                    ),
                )
            )
        }
    }
}

// 用当前选中的 TTS 念一句测试文本，播放中再点一次停止
@Composable
private fun TTSTestButton() {
    val tts = LocalTTSState.current
    val isSpeaking by tts.isSpeaking.collectAsState()
    val isAvailable by tts.isAvailable.collectAsState()
    if (!isAvailable) return

    val testText = stringResource(R.string.setting_tts_page_test_text)
    val height = ButtonDefaults.ExtraSmallContainerHeight
    Button(
        onClick = {
            if (!isSpeaking) {
                tts.speak(testText)
            } else {
                tts.stop()
            }
        },
        // 按下时圆角收紧
        shapes = ButtonDefaults.shapesFor(height),
        modifier = Modifier.heightIn(min = height),
        colors = if (isSpeaking) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else ButtonDefaults.buttonColors(),
        contentPadding = ButtonDefaults.contentPaddingFor(height, hasStartIcon = true),
    ) {
        Icon(
            imageVector = if (isSpeaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)),
        )
        Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
        Text(
            text = if (isSpeaking) stringResource(R.string.stop) else stringResource(R.string.test_tts),
            style = ButtonDefaults.textStyleFor(height),
        )
    }
}
