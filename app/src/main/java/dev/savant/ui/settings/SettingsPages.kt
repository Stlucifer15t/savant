package dev.savant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.savant.domain.model.SaveFormat
import dev.savant.domain.model.formatBytes
import dev.savant.ui.components.GhostButton
import dev.savant.ui.components.Hairline
import dev.savant.ui.components.KeyValueRow
import dev.savant.ui.components.LinkRow
import dev.savant.ui.components.SavantPanel
import dev.savant.ui.components.ScreenTopBar
import dev.savant.ui.components.Section
import dev.savant.ui.components.SectionNote
import dev.savant.ui.components.SwitchRow
import dev.savant.ui.components.Tag
import dev.savant.ui.components.ChoiceGrid
import dev.savant.ui.components.gutterPadding
import dev.savant.ui.icons.SavantIcons
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantTypography

/** Shown in the hub and on the about page; kept next to the gradle `versionName`. */
internal const val SAVANT_VERSION = "0.1.0 · build step 6"

/**
 * Settings, as a table of contents.
 *
 * The root used to be every switch savant has, stacked in one scroll — which meant the thing a user
 * came for was always below five things they did not. Now each page is one subject behind one row,
 * and the row carries the current value, so the root answers "what is set?" without being opened.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenProviders: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenEditing: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = Savant.colors
    val settings = state.settings

    LaunchedEffect(state.notice) {
        if (state.notice != null) viewModel.onNoticeShown()
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenTopBar(title = "settings", subtitle = state.notice ?: SAVANT_VERSION)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "engine") {
                Section("engine")
                SavantPanel(modifier = Modifier.gutterPadding()) {
                    LinkRow(
                        title = "ai provider",
                        subtitle = "which model answers, and where its key and endpoint live",
                        value = state.engineLabel,
                        icon = SavantIcons.cpu,
                        valueColor = if (state.isRemote) colors.accent else colors.textMuted,
                        onClick = onOpenProviders,
                    )
                    Hairline()
                    LinkRow(
                        title = "privacy & logs",
                        subtitle = "what may leave the device · keys ${
                            state.keyStorage
                        } · requests ${if (settings.logNetworkRequests) "logged" else "not logged"}",
                        value = null,
                        icon = SavantIcons.shield,
                        onClick = onOpenPrivacy,
                    )
                }
            }

            item(key = "workbench") {
                Section("workbench")
                SavantPanel(modifier = Modifier.gutterPadding()) {
                    LinkRow(
                        title = "appearance",
                        subtitle = "theme, motion, monospace values",
                        value = settings.theme.label,
                        icon = SavantIcons.eye,
                        onClick = onOpenAppearance,
                    )
                    Hairline()
                    LinkRow(
                        title = "editing",
                        subtitle = "the diff gate, the parse log, keeping originals",
                        value = if (settings.showParseLog) "log on" else null,
                        icon = SavantIcons.edit,
                        onClick = onOpenEditing,
                    )
                    Hairline()
                    LinkRow(
                        title = "storage",
                        subtitle = "backups, recent imports, history",
                        value = when (state.backupCount) {
                            0 -> "empty"
                            1 -> "1 backup"
                            else -> "${state.backupCount} backups"
                        },
                        icon = SavantIcons.drive,
                        onClick = onOpenStorage,
                    )
                }
            }

            item(key = "about") {
                Section("about")
                SavantPanel(modifier = Modifier.gutterPadding()) {
                    LinkRow(
                        title = "about savant",
                        subtitle = "what it is, which formats it reads, the offline warning",
                        value = null,
                        icon = SavantIcons.info,
                        onClick = onOpenAbout,
                    )
                }
            }
        }
    }
}

/** Theme, motion and type. */
@Composable
fun AppearanceSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings

    SettingsPage(
        title = "appearance",
        subtitle = "how savant looks and moves",
        modifier = modifier,
        onBack = onBack,
    ) {
        item(key = "look") {
            Section("look")
            SavantPanel(modifier = Modifier.gutterPadding()) {
                ChoiceGrid(
                    title = "theme",
                    options = dev.savant.data.settings.ThemeMode.entries.map { it.label },
                    selected = settings.theme.label,
                    onSelect = { label ->
                        dev.savant.data.settings.ThemeMode.entries.firstOrNull { it.label == label }
                            ?.let(viewModel::onTheme)
                    },
                )
                Hairline()
                SwitchRow(
                    title = "reduced motion",
                    subtitle = "no tickers, no flashes, no staggered parse lines",
                    checked = settings.reducedMotion,
                    onChange = viewModel::onReducedMotion,
                )
                Hairline()
                SwitchRow(
                    title = "monospace values",
                    subtitle = "jetbrains mono for every value, path and log line",
                    checked = settings.monoValues,
                    onChange = viewModel::onMonoValues,
                )
            }
        }
    }
}

/** The rules an edit goes through. */
@Composable
fun EditingSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings

    SettingsPage(
        title = "editing",
        subtitle = "how a change is confirmed and written",
        modifier = modifier,
        onBack = onBack,
    ) {
        item(key = "gate") {
            Section("the gate")
            SavantPanel(modifier = Modifier.gutterPadding()) {
                SwitchRow(
                    title = "always show the diff first",
                    subtitle = "off would apply a confirmed command straight away — savant does not offer that yet",
                    checked = settings.confirmBeforeApply,
                    onChange = viewModel::onConfirmBeforeApply,
                    enabled = false,
                )
                Hairline()
                SwitchRow(
                    title = "show parse log",
                    subtitle = "the monospace lines printed while a file is read",
                    checked = settings.showParseLog,
                    onChange = viewModel::onShowParseLog,
                )
                Hairline()
                SwitchRow(
                    title = "keep the original",
                    subtitle = "copy every imported file before the first edit, inside savant's own storage",
                    checked = settings.keepBackups,
                    onChange = viewModel::onKeepBackups,
                )
            }
        }
        item(key = "note") {
            SectionNote(
                "every change goes through the diff, and nothing is offered for export until the rebuilt " +
                    "file has been parsed again and the change is present in it.",
            )
        }
    }
}

/** What leaves the device, where keys live, and the request log. */
@Composable
fun PrivacySettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenNetwork: () -> Unit = {},
    onOpenProviders: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val colors = Savant.colors

    SettingsPage(
        title = "privacy & logs",
        subtitle = state.keyStorage,
        modifier = modifier,
        onBack = onBack,
    ) {
        item(key = "leaving") {
            Section("leaving the device")
            SavantPanel(modifier = Modifier.gutterPadding()) {
                KeyValueRow(label = "save files", value = "never")
                Text(
                    text = "a save file is read, edited and rebuilt on this device. exports go through the " +
                        "share sheet or a folder you pick — never through a provider.",
                    style = SavantMono.tag,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = SavantDimens.gutter, vertical = 4.dp),
                )
                Hairline()
                SwitchRow(
                    title = "log network requests",
                    subtitle = "keeps the last 60 requests in memory for you to read; off writes nothing",
                    checked = settings.logNetworkRequests,
                    onChange = viewModel::onLogNetwork,
                )
                Hairline()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GhostButton(text = "view network activity", onClick = onOpenNetwork)
                }
            }
        }
        item(key = "keys") {
            Section("api keys")
            SavantPanel(modifier = Modifier.gutterPadding()) {
                KeyValueRow(label = "stored in", value = state.keyStorage)
                KeyValueRow(label = "shown as", value = "****1234 — the last four characters")
                Text(
                    text = if (state.keyStorage == "memory only") {
                        "this device had no usable keystore, so keys are re-entered on every launch."
                    } else {
                        "keys never appear in a profile, a backup or a log. clearing one is on the profile's own page."
                    },
                    style = SavantMono.tag,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = SavantDimens.gutter, vertical = 4.dp),
                )
                Hairline()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GhostButton(text = "manage providers", onClick = onOpenProviders)
                }
            }
        }
        item(key = "prompt") {
            SectionNote(
                "what a provider is allowed to receive is set on the ai provider page: the conversation, " +
                    "and a field listing that can be reduced to names and types.",
            )
        }
    }
}

/** Backups, imports and history. */
@Composable
fun StorageSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = Savant.colors

    SettingsPage(
        title = "storage",
        subtitle = "what savant keeps on this device",
        modifier = modifier,
        onBack = onBack,
    ) {
        item(key = "counts") {
            Section("kept")
            SavantPanel(modifier = Modifier.gutterPadding()) {
                KeyValueRow(label = "backups", value = "${state.backupCount} · ${formatBytes(state.backupBytes)}")
                KeyValueRow(label = "recent imports", value = state.importCount.toString())
                KeyValueRow(label = "history", value = state.counts.summary)
                KeyValueRow(label = "saved names", value = state.counts.namedFields.toString())
                Text(
                    text = "backups are copies of imported originals, in savant's own storage. recent imports " +
                        "hold a uri and its metadata; the file itself is never copied there.",
                    style = SavantMono.tag,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = SavantDimens.gutter, vertical = 4.dp),
                )
            }
        }
        item(key = "clearing") {
            Section("clearing")
            SavantPanel(modifier = Modifier.gutterPadding()) {
                ClearRow(
                    title = "clear backups",
                    subtitle = "frees the space the copied originals use",
                    onClick = viewModel::onClearBackups,
                )
                Hairline()
                ClearRow(
                    title = "clear recent imports",
                    subtitle = "forgets the list; the files stay where they are",
                    onClick = viewModel::onClearImports,
                )
                Hairline()
                ClearRow(
                    title = "clear history",
                    subtitle = "chat transcripts, imports and backups in one go",
                    onClick = viewModel::onClearHistory,
                )
                Hairline()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GhostButton(text = "reset settings", onClick = viewModel::onResetSettings)
                }
            }
        }
    }
}

/** What savant is, and which formats it can actually rebuild. */
@Composable
fun AboutSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val colors = Savant.colors
    SettingsPage(
        title = "about",
        subtitle = SAVANT_VERSION,
        modifier = modifier,
        onBack = onBack,
    ) {
        item(key = "about") {
            Section("savant")
            SavantPanel(modifier = Modifier.gutterPadding()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = "a workbench for game save files", style = SavantTypography.titleSmall, color = colors.text)
                    Text(text = SAVANT_VERSION, style = SavantMono.path, color = colors.textMuted)
                    Text(
                        text = "parsing and writing are deterministic kotlin. an assistant can propose edits; " +
                            "it never writes a byte, and the diff is what writes one.",
                        style = SavantMono.path,
                        color = colors.textMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SaveFormat.entries.filter { it.implemented }.forEach { format ->
                            Tag(text = format.badge, color = colors.text, emphasis = true)
                        }
                        Tag(text = "${SaveFormat.entries.count { !it.implemented }} planned", color = colors.textFaint)
                    }
                }
            }
        }
        item(key = "warning") {
            Section("warning")
            SavantPanel(modifier = Modifier.gutterPadding()) {
                Text(
                    text = "for offline single player saves. writing to a save used by an online game can " +
                        "corrupt it and can get an account banned.",
                    style = SavantMono.tag,
                    color = colors.caution,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

/** One page of settings: a header, one scroll, the same gutter as every other screen. */
@Composable
private fun SettingsPage(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenTopBar(title = title, subtitle = subtitle, onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/** A row that clears something: the action is the whole row, and it says what it costs. */
@Composable
private fun ClearRow(title: String, subtitle: String, onClick: () -> Unit) {
    val colors = Savant.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = SavantTypography.bodyMedium, color = colors.text)
            Text(text = subtitle, style = SavantMono.path, color = colors.textMuted)
        }
        Spacer(Modifier.height(0.dp))
        GhostButton(text = "clear", onClick = onClick)
    }
}
