package dev.savant.ui.finder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.savant.domain.finder.BinaryValues
import dev.savant.domain.finder.Candidate
import dev.savant.domain.model.Endianness
import dev.savant.domain.model.displayText
import dev.savant.domain.parser.ParseLogLine
import dev.savant.ui.components.AccentButton
import dev.savant.ui.components.CandidateRow
import dev.savant.ui.components.GhostButton
import dev.savant.ui.components.Hairline
import dev.savant.ui.components.ParseProgress
import dev.savant.ui.components.SavantPanel
import dev.savant.ui.components.SectionLabel
import dev.savant.ui.components.Tag
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono

/**
 * The byte level finder: two saves compared, or one file scanned for a known value.
 *
 * Same visual language as the rest of savant — flat panels, hairlines, monospace values, accent
 * only where something changed or where the primary action is. Every candidate row states where it
 * is, as what type, what it held before and after, and why it ranks where it does, because the next
 * step is an edit and "trust me" is not a reason.
 */
@Composable
fun ScanPanel(
    state: FinderUiState,
    viewModel: FinderViewModel,
    onPickFile: (ScanSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Savant.colors
    val compare = state.mode == FinderMode.COMPARE

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "files") {
            SavantPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        text = if (compare) {
                            "two saves of the same game: one before the change, one after"
                        } else {
                            "one file, and a value you know is in it"
                        },
                        style = SavantMono.path,
                        color = colors.textMuted,
                    )
                    SlotRow(
                        slot = ScanSlot.A,
                        file = state.fileA,
                        onPick = { onPickFile(ScanSlot.A) },
                        onUseOpen = if (state.hasSession) ({ viewModel.onUseOpenFile(ScanSlot.A) }) else null,
                    )
                    if (compare) {
                        SlotRow(
                            slot = ScanSlot.B,
                            file = state.fileB,
                            onPick = { onPickFile(ScanSlot.B) },
                            onUseOpen = if (state.hasSession) ({ viewModel.onUseOpenFile(ScanSlot.B) }) else null,
                        )
                        SlotRow(
                            slot = ScanSlot.C,
                            file = state.fileC,
                            onPick = { onPickFile(ScanSlot.C) },
                            onUseOpen = null,
                            optional = true,
                        )
                    }
                }
            }
        }

        if (!compare) {
            item(key = "target") {
                SavantPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        SectionLabel(text = "value to find")
                        ScanTextField(
                            value = state.scanTarget,
                            onValueChange = viewModel::onScanTargetChanged,
                            placeholder = "1234 · 42.5 · 0x1f",
                        )
                        Text(
                            text = "numbers are read as every type below; text has to be quoted in the save to be found",
                            style = SavantMono.path,
                            color = colors.textFaint,
                        )
                    }
                }
            }
        }

        item(key = "spec") {
            SavantPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(text = "read as")
                        Spacer(Modifier.weight(1f))
                        GhostButton(text = "integers", onClick = viewModel::onOnlyIntegers)
                        Spacer(Modifier.width(6.dp))
                        GhostButton(text = "all", onClick = viewModel::onAllTypes)
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(BinaryValues.scannable, key = { it.name }) { type ->
                            ToggleChip(
                                label = type.tag,
                                active = type in state.types,
                                onClick = { viewModel.onToggleType(type) },
                            )
                        }
                    }
                    Hairline()
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Endianness.entries.forEach { endian ->
                            ToggleChip(
                                label = endian.tag,
                                active = endian in state.endianness,
                                onClick = { viewModel.onToggleEndianness(endian) },
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        ToggleChip(
                            label = "aligned only",
                            active = state.alignedOnly,
                            onClick = viewModel::onToggleAligned,
                        )
                    }
                }
            }
        }

        item(key = "actions") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (compare) {
                    AccentButton(
                        text = if (state.working) "comparing" else "find candidates",
                        onClick = viewModel::onRunCompare,
                        enabled = state.canCompare,
                    )
                    if (state.fileC != null && state.candidates.isNotEmpty()) {
                        GhostButton(
                            text = "narrow with save c",
                            onClick = viewModel::onNarrow,
                            enabled = state.canNarrow,
                        )
                    }
                } else {
                    AccentButton(
                        text = if (state.working) "scanning" else "scan",
                        onClick = viewModel::onRunScan,
                        enabled = state.canScan,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (state.fileA != null || state.candidates.isNotEmpty()) {
                    GhostButton(text = "clear", onClick = viewModel::onForgetScan)
                }
            }
        }

        if (state.notice != null) {
            item(key = "notice") {
                Text(
                    text = state.notice,
                    style = SavantMono.path,
                    color = colors.caution,
                    modifier = Modifier.padding(horizontal = SavantDimens.gutter),
                )
            }
        }

        if (state.scanLog.isNotEmpty()) {
            item(key = "log") {
                ParseProgress(
                    log = state.scanLog.map { ParseLogLine(it) },
                    done = !state.working,
                    modifier = Modifier.padding(horizontal = SavantDimens.gutter),
                )
            }
        }

        if (state.candidates.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = when {
                        state.working -> "reading bytes…"
                        compare && state.fileB == null -> "pick both saves, then find candidates"
                        compare -> "nothing ranked yet"
                        state.fileA == null -> "pick a file, type a value, scan"
                        else -> "nothing found yet"
                    },
                    style = SavantMono.path,
                    color = colors.textFaint,
                    modifier = Modifier.padding(horizontal = SavantDimens.gutter, vertical = 14.dp),
                )
            }
        } else {
            item(key = "count") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel(
                        text = "${state.candidates.size} candidate${if (state.candidates.size == 1) "" else "s"}" +
                            if (state.narrowed) " · narrowed" else "",
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "nothing has been written to any file",
                        style = SavantMono.tag,
                        color = colors.textFaint,
                    )
                }
            }
            items(state.candidates, key = { it.key }) { candidate ->
                CandidateBlock(
                    candidate = candidate,
                    top = candidate === state.candidates.first(),
                    savedName = state.namedOffsets[candidate.key],
                    naming = state.namingKey == candidate.key,
                    onNameIt = { viewModel.onNameIt(candidate) },
                    onSaveName = { name -> viewModel.onSaveName(candidate, name) },
                )
            }
        }
    }
}

@Composable
private fun SlotRow(
    slot: ScanSlot,
    file: ScanFileState?,
    onPick: () -> Unit,
    onUseOpen: (() -> Unit)?,
    optional: Boolean = false,
) {
    val colors = Savant.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = slot.label + if (optional) " · optional" else "",
                    style = SavantMono.tag,
                    color = colors.textMuted,
                )
                if (file != null) Tag(text = file.sizeLabel, color = colors.textMuted)
            }
            Text(
                text = file?.name ?: "not picked",
                style = SavantMono.value,
                color = if (file == null) colors.textFaint else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (onUseOpen != null) GhostButton(text = "use open", onClick = onUseOpen)
        Spacer(Modifier.width(6.dp))
        GhostButton(text = if (file == null) "pick" else "replace", onClick = onPick)
    }
}

@Composable
private fun CandidateBlock(
    candidate: Candidate,
    top: Boolean,
    savedName: String?,
    naming: Boolean,
    onNameIt: () -> Unit,
    onSaveName: (String) -> Unit,
) {
    val colors = Savant.colors
    SavantPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter)) {
        CandidateRow(
            title = savedName ?: candidate.offsetLabel,
            subtitle = buildString {
                if (savedName != null) append("${candidate.offsetLabel} · ")
                append(candidate.changeText)
                if (candidate.runLength > 0) append(" · ${candidate.runLength} byte run")
            },
            typeTag = candidate.typeTag,
            value = candidate.after?.displayText() ?: "?",
            confidenceLabel = candidate.confidenceLabel,
            actionLabel = if (savedName == null) "name it" else "rename",
            onAction = onNameIt,
            highlighted = top,
        )
        if (naming) {
            Hairline()
            NameField(
                offset = candidate.offsetLabel,
                initial = savedName ?: "",
                onCancel = onNameIt,
                onSave = onSaveName,
            )
        }
    }
}

/**
 * The name field for a candidate.
 *
 * Naming is the only thing the byte finder produces: it stores offset, type and byte order against a
 * name, per format, and step 7 exports those as a plugin field mapping.
 */
@Composable
private fun NameField(offset: String, initial: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    val colors = Savant.colors
    var draft by remember(offset) { mutableStateOf(initial) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "name the value at $offset",
            style = SavantMono.path,
            color = colors.textMuted,
        )
        ScanTextField(value = draft, onValueChange = { draft = it }, placeholder = "gold")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(text = "cancel", onClick = onCancel)
            Spacer(Modifier.weight(1f))
            AccentButton(text = "save name", onClick = { onSave(draft) }, enabled = draft.isNotBlank())
        }
    }
}

/** One flat mono text field, shared by the scan target and the name field. */
@Composable
private fun ScanTextField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val colors = Savant.colors
    val shape = RoundedCornerShape(SavantDimens.radius)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = SavantMono.value.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background, shape)
            .border(SavantDimens.hairline, colors.borderStrong, shape)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(text = placeholder, style = SavantMono.value, color = colors.textFaint)
                }
                inner()
            }
        },
    )
}

/** A chip that is either on or off; the accent border is the only thing that changes. */
@Composable
private fun ToggleChip(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = Savant.colors
    GhostButton(
        text = label,
        onClick = onClick,
        borderColor = if (active) colors.accent else colors.border,
        contentColor = if (active) colors.accent else colors.textMuted,
    )
}

/** The mode bar, kept next to the panel so the labels stay in one place. */
@Composable
fun FinderModeBar(mode: FinderMode, onMode: (FinderMode) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SavantDimens.gutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FinderMode.entries.forEach { entry ->
            ToggleChip(label = entry.label, active = entry == mode, onClick = { onMode(entry) })
        }
        Spacer(Modifier.weight(1f))
    }
}
