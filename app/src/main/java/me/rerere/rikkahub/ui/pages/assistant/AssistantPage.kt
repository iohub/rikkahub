package me.rerere.rikkahub.ui.pages.assistant

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Lock
import me.rerere.hugeicons.stroke.LockKey
import me.rerere.hugeicons.stroke.LockOpen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.repository.AssistantLockManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.longPressReorder
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.EditState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.heroAnimation
import me.rerere.rikkahub.ui.hooks.multiTap
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantImporter
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

@Composable
fun AssistantPage(vm: AssistantVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val createState = useEditState<Assistant> {
        vm.addAssistant(it)
    }
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 搜索关键词状态
    var searchQuery by remember { mutableStateOf("") }
    // 标签过滤状态
    var selectedTagIds by remember { mutableStateOf(emptySet<Uuid>()) }
    // 待删除的助手
    var deleteTarget by remember { mutableStateOf<Assistant?>(null) }
    // 待复制的助手(仅在有独立记忆时需要询问是否一并复制)
    var cloneTarget by remember { mutableStateOf<Assistant?>(null) }
    var cloneWithMemories by remember { mutableStateOf(false) }
    // 待加密隐藏的助手
    var lockTarget by remember { mutableStateOf<Assistant?>(null) }
    // 顶栏 5 连击触发的解锁弹窗
    var showUnlockDialog by remember { mutableStateOf(false) }

    val toaster = LocalToaster.current
    val lockedToastText = stringResource(R.string.assistant_lock_locked_toast)

    // 本会话内已解锁的助手 ID：解锁/重新上锁后列表要立即刷新，所以必须作为过滤的 key
    val unlockedIds by AssistantLockManager.unlockedIds.collectAsStateWithLifecycle()

    // 根据搜索关键词和选中的标签过滤助手，并排除当前处于隐藏状态的加密助手
    val filteredAssistants = remember(settings.assistants, selectedTagIds, searchQuery, unlockedIds) {
        settings.assistants.filter { assistant ->
            val visible = !AssistantLockManager.isHidden(assistant)
            val matchesSearch = searchQuery.isBlank() ||
                assistant.name.contains(searchQuery, ignoreCase = true)
            val matchesTags = selectedTagIds.isEmpty() ||
                assistant.tags.any { tagId -> tagId in selectedTagIds }
            visible && matchesSearch && matchesTags
        }
    }

    // 有助手处于隐藏状态时，列表下标和 settings.assistants 的下标不再一一对应，拖拽排序必须禁用
    val hasHiddenAssistants = remember(settings.assistants, unlockedIds) {
        settings.assistants.any { AssistantLockManager.isHidden(it) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    // 隐藏手势：5 秒内连点标题 5 次打开解锁弹窗（无涟漪，避免暴露入口）
                    Text(
                        text = stringResource(R.string.assistant_page_title),
                        modifier = Modifier.multiTap(taps = 5, windowMillis = 5_000L) {
                            showUnlockDialog = true
                        },
                    )
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = {
                            createState.open(Assistant())
                        }) {
                        Icon(HugeIcons.Add01, stringResource(R.string.assistant_page_add))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it)
                .padding(top = 16.dp)
                .consumeWindowInsets(it),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val lazyListState = rememberLazyListState()
            val isFiltering = selectedTagIds.isNotEmpty() || searchQuery.isNotBlank()
            // 列表被过滤（搜索/标签）或存在隐藏助手时，列表下标和 settings.assistants 的下标不一致，
            // 拖拽重排会把助手插到错误的位置，因此这两种情况下都禁用排序
            val reorderEnabled = !isFiltering && !hasHiddenAssistants
            val reorderEnabledState = rememberUpdatedState(reorderEnabled)
            val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                if (reorderEnabledState.value) {
                    val newAssistants = settings.assistants.toMutableList().apply {
                        add(to.index, removeAt(from.index))
                    }
                    vm.updateSettings { it.copy(assistants = newAssistants) }
                }
            }

            // 搜索框
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.assistant_page_search_placeholder)) },
                leadingIcon = {
                    Icon(HugeIcons.Search01, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(HugeIcons.Cancel01, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )

            // 标签过滤器
            AssistantTagsFilterRow(
                settings = settings,
                vm = vm,
                selectedTagIds = selectedTagIds,
                onUpdateSelectedTagIds = { ids ->
                    selectedTagIds = ids
                }
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                state = lazyListState,
            ) {
                items(filteredAssistants, key = { assistant -> assistant.id }) { assistant ->
                    ReorderableItem(
                        state = reorderableState,
                        key = assistant.id,
                    ) { isDragging ->
                        val memories by vm.getMemories(assistant).collectAsStateWithLifecycle(
                            initialValue = emptyList(),
                        )
                        AssistantItem(
                            assistant = assistant,
                            settings = settings,
                            memories = memories,
                            onEdit = {
                                navController.navigate(Screen.AssistantDetail(id = assistant.id.toString()))
                            },
                            onCopy = {
                                // 全局记忆是共享的, 复制后的助手本来就能读到, 无需询问
                                if (!assistant.useGlobalMemory && memories.isNotEmpty()) {
                                    cloneWithMemories = false
                                    cloneTarget = assistant
                                } else {
                                    vm.copyAssistant(assistant)
                                }
                            },
                            onDelete = {
                                deleteTarget = assistant
                            },
                            onLock = {
                                lockTarget = assistant
                            },
                            onRemoveLock = {
                                vm.removeLock(assistant)
                            },
                            onRelock = {
                                vm.relockAssistant(assistant)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .then(longPressReorder(isDragging, enabled = reorderEnabled))
                        )
                    }
                }
            }
        }
    }

    AssistantCreationSheet(createState)

    // 加密隐藏：设置密码，确认后该助手立即从列表消失
    lockTarget?.let { target ->
        AssistantSetPasswordDialog(
            assistant = target,
            onConfirm = { password ->
                vm.lockAssistant(target, password)
                lockTarget = null
                toaster.show(lockedToastText, type = ToastType.Success)
            },
            onDismiss = { lockTarget = null },
        )
    }

    // 隐藏手势（连点标题 5 次）触发的解锁弹窗
    if (showUnlockDialog) {
        AssistantUnlockDialog(
            lockedCount = settings.assistants.count { it.isLocked },
            onUnlock = { password ->
                val matched = AssistantLockManager.matchLocked(settings.assistants, password)
                matched.forEach { vm.unlockAssistant(it) }
                matched.isNotEmpty()
            },
            onDismiss = { showUnlockDialog = false },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.assistant_page_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            deleteTarget?.let { vm.removeAssistant(it) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.assistant_page_delete_dialog_text))
    }

    RikkaConfirmDialog(
        show = cloneTarget != null,
        title = stringResource(R.string.assistant_page_clone),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            cloneTarget?.let { vm.copyAssistant(it, copyMemories = cloneWithMemories) }
            cloneTarget = null
        },
        onDismiss = { cloneTarget = null },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = cloneWithMemories,
                    role = Role.Checkbox,
                    onValueChange = { cloneWithMemories = it },
                ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = cloneWithMemories, onCheckedChange = null)
            Text(stringResource(R.string.assistant_page_clone_with_memories))
        }
    }
}

@Composable
private fun AssistantTagsFilterRow(
    settings: Settings,
    vm: AssistantVM,
    selectedTagIds: Set<Uuid>,
    onUpdateSelectedTagIds: (Set<Uuid>) -> Unit
) {
    if (settings.assistantTags.isNotEmpty()) {
        val tagsListState = rememberLazyListState()
        val tagsReorderableState = rememberReorderableLazyListState(tagsListState) { from, to ->
            val newTags = settings.assistantTags.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            vm.updateSettings { it.copy(assistantTags = newTags) }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
            state = tagsListState
        ) {
            items(items = settings.assistantTags, key = { tag -> tag.id }) { tag ->
                ReorderableItem(
                    state = tagsReorderableState, key = tag.id
                ) { isDragging ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            onClick = {
                                onUpdateSelectedTagIds(
                                    if (tag.id in selectedTagIds) {
                                        selectedTagIds - tag.id
                                    } else {
                                        selectedTagIds + tag.id
                                    }
                                )
                            },
                            label = {
                                Text(tag.name)
                            },
                            selected = tag.id in selectedTagIds,
                            leadingIcon = if (tag.id in selectedTagIds) {
                                {
                                    Icon(
                                        imageVector = HugeIcons.Tick01,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else null,
                            shape = CircleShape,
                            modifier = longPressReorder(isDragging)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantCreationSheet(
    state: EditState<Assistant>,
) {
    state.EditStateContent { assistant, update ->
        ModalBottomSheet(
            onDismissRequest = {
                state.dismiss()
            },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
            dragHandle = {},
            sheetGesturesEnabled = false
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = assistant.name,
                    onValueChange = {
                        update(
                            assistant.copy(
                                name = it
                            )
                        )
                    },
                    label = { Text(stringResource(R.string.assistant_page_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                AssistantImporter(
                    onUpdate = {
                        update(it)
                        state.confirm()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = {
                            state.dismiss()
                        }) {
                        Text(stringResource(R.string.assistant_page_cancel))
                    }
                    Button(
                        onClick = {
                            state.confirm()
                        }) {
                        Text(stringResource(R.string.assistant_page_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantItem(
    assistant: Assistant,
    settings: Settings,
    modifier: Modifier = Modifier,
    memories: List<MemoryFile>,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onLock: () -> Unit,
    onRemoveLock: () -> Unit,
    onRelock: () -> Unit,
) {
    // 已加密但本会话内已解锁（隐藏中的助手不会渲染到列表里，所以这里 assistant.isLocked 即代表已解锁）
    val isUnlocked = assistant.isLocked && AssistantLockManager.isUnlocked(assistant.id)
    val cloneLabel = stringResource(R.string.assistant_page_clone)
    val deleteLabel = stringResource(R.string.assistant_page_delete)
    val lockLabel = stringResource(R.string.assistant_lock_action)
    val removeLockLabel = stringResource(R.string.assistant_lock_remove)
    val relockLabel = stringResource(R.string.assistant_lock_relock)
    val lockBadgeLabel = stringResource(R.string.assistant_lock_badge)

    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onEdit,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UIAvatar(
                name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                value = assistant.avatar,
                modifier = Modifier
                    .size(48.dp)
                    .heroAnimation("assistant_${assistant.id}")
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {

                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (assistant.isLocked) {
                        Tag(type = TagType.WARNING) {
                            Text(lockBadgeLabel)
                        }
                    }

                    if (assistant.enableMemory) {
                        Tag(type = TagType.SUCCESS) {
                            Text(stringResource(R.string.assistant_page_memory_count, memories.size))
                        }
                    }

                    if (assistant.tags.isNotEmpty()) {
                        assistant.tags.take(2).fastForEach { tagId ->
                            val tag = settings.assistantTags.find { it.id == tagId }
                                ?: return@fastForEach
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                Text(
                                    text = tag.name,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        if (assistant.tags.size > 2) {
                            Text(
                                text = "+${assistant.tags.size - 2}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            ItemActionMenu(
                actions = buildList {
                    add(
                        ItemAction(
                            text = cloneLabel,
                            icon = HugeIcons.Copy01,
                            onClick = onCopy,
                        )
                    )
                    if (!assistant.isLocked) {
                        add(
                            ItemAction(
                                text = lockLabel,
                                icon = HugeIcons.LockKey,
                                onClick = onLock,
                            )
                        )
                    } else if (isUnlocked) {
                        add(
                            ItemAction(
                                text = removeLockLabel,
                                icon = HugeIcons.LockOpen,
                                onClick = onRemoveLock,
                            )
                        )
                        add(
                            ItemAction(
                                text = relockLabel,
                                icon = HugeIcons.Lock,
                                onClick = onRelock,
                            )
                        )
                    }
                    add(
                        ItemAction(
                            text = deleteLabel,
                            icon = HugeIcons.Delete01,
                            destructive = true,
                            enabled = settings.assistants.size > 1,
                            onClick = onDelete,
                        )
                    )
                }
            )
        }
    }
}
