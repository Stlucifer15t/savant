package dev.savant.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.savant.data.storage.Backup
import dev.savant.data.storage.ImportRecord
import dev.savant.domain.model.formatBytes
import dev.savant.ui.components.AccentButton
import dev.savant.ui.components.FlatIconButton
import dev.savant.ui.components.GhostButton
import dev.savant.ui.components.Hairline
import dev.savant.ui.components.SavantPanel
import dev.savant.ui.components.ScreenTopBar
import dev.savant.ui.components.SectionLabel
import dev.savant.ui.components.Tag
import dev.savant.ui.icons.SavantIcons
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantTypography

/**
 * The Files screen.
 *
 * Three lists, no decoration: the open file, the backups savant took before touching anything, and
 * the recent imports. Restore is one tap and always reopens the untouched original.
 */
@Composable
fun FilesScreen(
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notice by viewModel.noticeText.collectAsStateWithLifecycle()
    val colors = Savant.colors

    LaunchedEffect(notice) {
        if (notice != null) viewModel.onNoticeShown()
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "files",
            subtitle = notice ?: "${state.backups.size} backup${if (state.backups.size == 1) "" else "s"} · ${formatBytes(state.backupBytes)}",
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "open") {
                SectionHeader("open now")
                val session = state.session
                if (session == null) {
                    EmptyNote("nothing is open — attach a save in chat")
                } else {
                    SavantPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = session.file.name,
                                style = SavantTypography.titleSmall,
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Tag(text = session.formatBadge, color = colors.text, emphasis = true)
                                Text(
                                    text = "${session.file.sizeLabel} · ${session.tree.editableCount} editable",
                                    style = SavantMono.path,
                                    color = colors.textMuted,
                                )
                            }
                            Text(
                                text = when {
                                    session.readOnly -> session.tree.readOnlyReason ?: "read only"
                                    session.history.isEmpty() -> "no edits applied yet"
                                    else -> "${session.appliedEdits.size} edit(s) applied in ${session.history.size} batch(es)"
                                },
                                style = SavantMono.path,
                                color = if (session.readOnly) colors.caution else colors.textMuted,
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GhostButton(text = "close", icon = SavantIcons.close, onClick = viewModel::onCloseFile)
                                if (session.backupId != null) {
                                    AccentButton(
                                        text = "restore original",
                                        icon = SavantIcons.undo,
                                        onClick = {
                                            viewModel.onRestore(session.backupId)
                                            onOpenChat()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "backups-header") {
                SectionHeader(
                    "backups",
                    trailing = if (state.backups.isNotEmpty()) {
                        { GhostButton(text = "clear", onClick = viewModel::onClearBackups) }
                    } else {
                        null
                    },
                )
            }
            if (state.backups.isEmpty()) {
                item(key = "backups-empty") { EmptyNote("savant backs up every file before the first edit") }
            } else {
                items(state.backups, key = { it.id }) { backup ->
                    BackupRow(
                        backup = backup,
                        onRestore = {
                            viewModel.onRestore(backup.id)
                            onOpenChat()
                        },
                        onDelete = { viewModel.onDeleteBackup(backup.id) },
                    )
                }
            }

            item(key = "imports-header") {
                SectionHeader(
                    "recent imports",
                    trailing = if (state.imports.isNotEmpty()) {
                        { GhostButton(text = "clear", onClick = viewModel::onClearImports) }
                    } else {
                        null
                    },
                )
            }
            if (state.imports.isEmpty()) {
                item(key = "imports-empty") { EmptyNote("nothing imported yet") }
            } else {
                items(state.imports, key = { it.uri }) { record ->
                    ImportRow(
                        record = record,
                        onOpen = {
                            viewModel.onReopen(record.uri)
                            onOpenChat()
                        },
                        onForget = { viewModel.onForgetImport(record.uri) },
                    )
                }
            }

            item(key = "note") {
                EmptyNote("history, sessions and search land in the local database in step 4")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: (@Composable () -> Unit)? = null) {
    val colors = Savant.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SavantDimens.gutter, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(text = title, color = colors.textMuted)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun BackupRow(backup: Backup, onRestore: () -> Unit, onDelete: () -> Unit) {
    val colors = Savant.colors
    SavantPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = backup.fileName,
                    style = SavantTypography.bodyMedium,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${backup.ageLabel} · ${formatBytes(backup.sizeBytes)} · ${backup.formatBadge}",
                    style = SavantMono.path,
                    color = colors.textMuted,
                )
                if (backup.note.isNotBlank()) {
                    Text(
                        text = backup.note,
                        style = SavantMono.tag,
                        color = colors.caution,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            GhostButton(text = "restore", icon = SavantIcons.undo, onClick = onRestore)
            FlatIconButton(
                icon = SavantIcons.trash,
                contentDescription = "delete backup",
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun ImportRow(record: ImportRecord, onOpen: () -> Unit, onForget: () -> Unit) {
    val colors = Savant.colors
    SavantPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.name,
                    style = SavantTypography.bodyMedium,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${record.whenLabel} · ${formatBytes(record.sizeBytes)}",
                    style = SavantMono.path,
                    color = colors.textMuted,
                )
            }
            Spacer(Modifier.width(8.dp))
            Tag(text = record.formatBadge, color = colors.textMuted)
            FlatIconButton(
                icon = SavantIcons.folder,
                contentDescription = "open this file again",
                onClick = onOpen,
            )
            FlatIconButton(
                icon = SavantIcons.close,
                contentDescription = "forget this import",
                onClick = onForget,
            )
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter, vertical = 2.dp)) {
        Hairline()
        Spacer(Modifier.height(8.dp))
        Text(text = text, style = SavantMono.path, color = Savant.colors.textFaint)
    }
}
