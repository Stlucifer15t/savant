package dev.savant.ui.chat

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.savant.R
import dev.savant.domain.model.EditValidator
import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.Node
import dev.savant.ui.chat.model.ChatEntry
import dev.savant.ui.chat.model.ChatRole
import dev.savant.ui.chat.model.DiffEntry
import dev.savant.ui.chat.model.ExportEntry
import dev.savant.ui.chat.model.FieldsEntry
import dev.savant.ui.chat.model.FileEntry
import dev.savant.ui.chat.model.NoticeEntry
import dev.savant.ui.chat.model.ParseEntry
import dev.savant.ui.chat.model.TextEntry
import dev.savant.ui.components.AccentButton
import dev.savant.ui.components.AssistantText
import dev.savant.ui.components.DiffCard
import dev.savant.ui.components.EmptyState
import dev.savant.ui.components.ExportCard
import dev.savant.ui.components.FieldGroupCard
import dev.savant.ui.components.FileChip
import dev.savant.ui.components.FlatIconButton
import dev.savant.ui.components.Hairline
import dev.savant.ui.components.NoticeCard
import dev.savant.ui.components.ParseProgress
import dev.savant.ui.components.SectionLabel
import dev.savant.ui.components.SessionTopBar
import dev.savant.ui.components.UserBubble
import dev.savant.ui.icons.SavantIcon
import dev.savant.ui.icons.SavantIcons
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantTypography

/**
 * The chat screen: the workbench.
 *
 * A message list that is really a work log — file chip, parse log, field groups, diffs, exports —
 * a flat input bar with a 1px top border, and a header that always says what is open.
 */
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Take the permission so the same file can be reopened from the Files screen later.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.onAttach(uri)
        }
    }

    val createFile = rememberLauncherForActivityResult(
        remember { ActivityResultContracts.CreateDocument("application/octet-stream") },
    ) { uri -> viewModel.onSaveResult(uri) }

    LaunchedEffect(Unit) {
        viewModel.chatEvents.collect { event ->
            when (event) {
                is ChatEvent.Share -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        putExtra(Intent.EXTRA_SUBJECT, event.fileName)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val started = runCatching {
                        context.startActivity(Intent.createChooser(send, "export save"))
                    }.isSuccess
                    if (!started) {
                        Toast.makeText(context, "no app can take that file", Toast.LENGTH_SHORT).show()
                    }
                }

                is ChatEvent.CreateDocument -> createFile.launch(event.fileName)
            }
        }
    }

    val lastKey = state.entries.lastOrNull()?.id
    LaunchedEffect(lastKey) {
        val target = if (state.isEmpty) 0 else state.entries.lastIndex
        if (target >= 0) listState.scrollToItem(target)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Savant.colors.background)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        SessionTopBar(
            fileName = state.fileName,
            formatBadge = state.formatBadge,
            backedUp = state.backedUp,
            readOnly = state.readOnly,
            pendingEdits = state.pendingEditCount,
            canUndo = state.canUndo,
            onUndo = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.onUndo()
            },
            onAttach = { pickFile.launch(arrayOf("*/*")) },
            onClose = viewModel::onCloseFile,
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (state.isEmpty) {
                    item(key = "empty") {
                        EmptyState(
                            onAttach = { pickFile.launch(arrayOf("*/*")) },
                            busy = state.busy,
                            modifier = Modifier.padding(top = 28.dp),
                        )
                    }
                }
                items(state.entries, key = { it.id }, contentType = { it::class.simpleName }) { entry ->
                    EntryRow(
                        entry = entry,
                        state = state,
                        onToggleGroup = viewModel::onToggleGroup,
                        onEdit = viewModel::onInlineEdit,
                        onQuick = viewModel::onQuickValue,
                        onConfirmDiff = { id ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.onConfirmDiff(id)
                        },
                        onCancelDiff = viewModel::onCancelDiff,
                        onRevertRow = viewModel::onRevertRow,
                        onShare = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.onShareExport()
                        },
                        onSave = viewModel::onSaveExport,
                    )
                }
                item(key = "tail") { Spacer(Modifier.height(4.dp)) }
            }

            if (state.busy) {
                BusyBar(Modifier.align(Alignment.TopCenter))
            }
        }

        ChatInputBar(
            text = state.input,
            enabled = !state.busy,
            onTextChange = viewModel::onInputChanged,
            onSend = viewModel::onSend,
            onAttach = { pickFile.launch(arrayOf("*/*")) },
        )
    }

    if (state.showWarning) {
        FirstRunWarning(onDismiss = viewModel::onWarningDismissed)
    }
}

/** One row of the transcript. */
@Composable
private fun EntryRow(
    entry: ChatEntry,
    state: ChatUiState,
    onToggleGroup: (String, FieldCategory) -> Unit,
    onEdit: (Node, String) -> Unit,
    onQuick: (Node, EditValidator.QuickValue) -> Unit,
    onConfirmDiff: (String) -> Unit,
    onCancelDiff: (String) -> Unit,
    onRevertRow: (String, String) -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    when (entry) {
        is TextEntry -> when (entry.role) {
            ChatRole.USER -> UserBubble(text = entry.text)
            ChatRole.ASSISTANT -> AssistantText(lines = entry.lines, isError = entry.isError)
        }

        is FileEntry -> FileChip(
            file = entry.file,
            formatBadge = entry.formatBadge,
            confidence = entry.confidence,
        )

        is ParseEntry -> ParseProgress(log = entry.log, done = entry.done)

        is FieldsEntry -> Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = entry.headline, style = SavantTypography.titleSmall, color = Savant.colors.text)
            Text(
                text = "${entry.totalCount} nodes indexed · tap a value to edit it",
                style = SavantMono.path,
                color = Savant.colors.textMuted,
            )
            entry.groups.forEach { group ->
                FieldGroupCard(
                    group = group,
                    collapsed = group.category.name in entry.collapsed,
                    onToggle = { onToggleGroup(entry.id, group.category) },
                    onEdit = onEdit,
                    onQuick = onQuick,
                    changedPaths = state.changedPaths,
                    readOnly = state.readOnly,
                )
            }
        }

        is DiffEntry -> DiffCard(
            entry = entry,
            onConfirm = { onConfirmDiff(entry.id) },
            onCancel = { onCancelDiff(entry.id) },
            onToggleRevert = { path -> onRevertRow(entry.id, path) },
            busy = state.busy,
        )

        is ExportEntry -> ExportCard(
            artifact = entry.artifact,
            shared = entry.shared,
            savedTo = entry.savedTo,
            onShare = onShare,
            onSave = onSave,
            busy = state.busy,
        )

        is NoticeEntry -> NoticeCard(title = entry.title, lines = entry.lines)
    }
}

/** A 2dp accent rule that slides while savant works. No spinner, no progress circle. */
@Composable
private fun BusyBar(modifier: Modifier = Modifier) {
    val colors = Savant.colors
    val transition = rememberInfiniteTransition(label = "busy")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "busy-shift",
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(SavantDimens.accentRule)
            .clipToBounds(),
    ) {
        val bar = maxWidth * 0.3f
        Box(
            modifier = Modifier
                .offset(x = (maxWidth - bar) * shift)
                .width(bar)
                .fillMaxHeight()
                .background(colors.accent),
        )
    }
}

/**
 * The input bar: attach, a flat text field, send.
 *
 * One pixel on top, no pill, no shadow, no elevation. Send is the only filled accent control on
 * the screen, and it is disabled while savant is working or the field is empty.
 */
@Composable
private fun ChatInputBar(
    text: String,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Savant.colors
    val canSend = enabled && text.isNotBlank()
    Column(modifier = modifier.fillMaxWidth().background(colors.background)) {
        Hairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            FlatIconButton(
                icon = SavantIcons.attach,
                contentDescription = stringResource(R.string.chat_attach),
                onClick = onAttach,
                enabled = enabled,
            )
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(SavantDimens.radius))
                    .background(colors.surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = SavantTypography.bodyMedium.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    maxLines = 4,
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.chat_input_hint),
                                    style = SavantTypography.bodyMedium,
                                    color = colors.textFaint,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(SavantDimens.radius))
                    .background(if (canSend) colors.accent else colors.surface)
                    .then(if (canSend) Modifier.clickable(onClick = onSend) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                SavantIcon(
                    id = SavantIcons.send,
                    tint = if (canSend) colors.onAccent else colors.textFaint,
                    size = 17.dp,
                    contentDescription = stringResource(R.string.chat_send),
                )
            }
        }
    }
}

/** First launch: what savant is for, and what editing a save can do to an online account. */
@Composable
private fun FirstRunWarning(onDismiss: () -> Unit) {
    val colors = Savant.colors
    AlertDialog(
        onDismissRequest = { /* not dismissible by tapping out: this has to be read */ },
        containerColor = colors.surface,
        titleContentColor = colors.text,
        textContentColor = colors.textMuted,
        shape = RoundedCornerShape(SavantDimens.radius),
        title = { Text(text = stringResource(R.string.warning_title), style = SavantTypography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.warning_body),
                    style = SavantMono.path.copy(lineHeight = 18.sp),
                    color = colors.textMuted,
                )
                SectionLabel(text = "offline single player only", color = colors.caution)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.warning_ack), style = SavantMono.value, color = colors.accent)
            }
        },
    )
}
