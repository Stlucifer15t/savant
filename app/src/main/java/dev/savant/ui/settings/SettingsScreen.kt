package dev.savant.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.savant.data.settings.ThemeMode
import dev.savant.domain.model.SaveFormat
import dev.savant.domain.model.formatBytes
import dev.savant.ui.components.GhostButton
import dev.savant.ui.components.Hairline
import dev.savant.ui.components.ChoiceRow
import dev.savant.ui.components.SavantPanel
import dev.savant.ui.components.Section
import dev.savant.ui.components.SwitchRow
import dev.savant.ui.components.ValueRow
import dev.savant.ui.components.ScreenTopBar
import dev.savant.ui.components.SectionLabel
import dev.savant.ui.components.Tag
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantTypography

/** Version shown in the about block; kept next to the gradle `versionName`. */
private const val VERSION = "0.1.0 · build step 6"

/**
 * Settings, grouped the way the work is grouped: how it looks, how edits are confirmed, where the
 * AI comes from, what leaves the device, and what is stored.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenProviders: () -> Unit = {},
    onOpenNetwork: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = Savant.colors
    val settings = state.settings

    LaunchedEffect(state.notice) {
        if (state.notice != null) viewModel.onNoticeShown()
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenTopBar(title = "settings", subtitle = state.notice ?: VERSION)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "appearance") {
                Section("appearance")
                SavantPanel(modifier = Modifier.panelPadding()) {
                    ChoiceRow(
                        title = "theme",
                        options = ThemeMode.entries.map { it.label },
                        selected = settings.theme.label,
                        onSelect = { label ->
                            ThemeMode.entries.firstOrNull { it.label == label }?.let(viewModel::onTheme)
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

            item(key = "editing") {
                Section("editing")
                SavantPanel(modifier = Modifier.panelPadding()) {
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
                        title = "keep backups",
                        subtitle = "copy every imported original before the first edit",
                        checked = settings.keepBackups,
                        onChange = viewModel::onKeepBackups,
                    )
                }
            }

            item(key = "ai") {
                Section("ai provider")
                SavantPanel(modifier = Modifier.panelPadding()) {
                    ValueRow(
                        title = "engine",
                        value = state.engineLabel,
                        subtitle = if (state.isRemote) {
                            "a remote provider answers here; it can propose edits and nothing else"
                        } else {
                            "on device: a deterministic command parser, no key, no network"
                        },
                    )
                    Hairline()
                    ValueRow(
                        title = "profiles",
                        value = when (state.providerCount) {
                            0 -> "none yet"
                            1 -> "1 configured"
                            else -> "${state.providerCount} configured"
                        },
                        subtitle = "a provider, a model and a key — the key is stored separately",
                        muted = true,
                    )
                    Hairline()
                    SwitchRow(
                        title = "send field values",
                        subtitle = "off sends paths and types only — useful for a save with names in it",
                        checked = settings.sendFieldValues,
                        onChange = viewModel::onSendFieldValues,
                    )
                    Hairline()
                    ValueRow(
                        title = "large files",
                        value = "trimmed",
                        subtitle = "over 200 kb only a field summary is sent, never the file",
                        muted = true,
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

            item(key = "privacy") {
                Section("privacy")
                SavantPanel(modifier = Modifier.panelPadding()) {
                    ValueRow(
                        title = "uploads",
                        value = "none",
                        subtitle = "a save file never leaves the device; exports go through the share sheet or a folder you pick",
                    )
                    Hairline()
                    SwitchRow(
                        title = "log network requests",
                        subtitle = "keeps the last 60 requests in memory for you to read; off writes nothing",
                        checked = settings.logNetworkRequests,
                        onChange = viewModel::onLogNetwork,
                    )
                    Hairline()
                    ValueRow(
                        title = "api keys",
                        value = state.keyStorage,
                        subtitle = if (state.keyStorage == "memory only") {
                            "this device had no usable keystore, so keys are re-entered each launch"
                        } else {
                            "never written to a profile, a backup or a log — only the last four characters are shown"
                        },
                        muted = true,
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

            item(key = "data") {
                Section("data")
                SavantPanel(modifier = Modifier.panelPadding()) {
                    ValueRow(
                        title = "backups",
                        value = "${state.backupCount} · ${formatBytes(state.backupBytes)}",
                        subtitle = "internal storage, never the original location",
                    )
                    Hairline()
                    ValueRow(title = "recent imports", value = state.importCount.toString(), subtitle = "uris and metadata only")
                    Hairline()
                    ValueRow(
                        title = "history",
                        value = state.counts.summary,
                        subtitle = "sessions and messages in an on device database",
                    )
                    if (state.counts.namedFields > 0) {
                        Hairline()
                        ValueRow(
                            title = "saved names",
                            value = state.counts.namedFields.toString(),
                            subtitle = "labels you gave cryptic keys, reused on the next parse",
                        )
                    }
                    Hairline()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GhostButton(text = "clear backups", onClick = viewModel::onClearBackups)
                        GhostButton(text = "clear imports", onClick = viewModel::onClearImports)
                        GhostButton(text = "clear history", onClick = viewModel::onClearHistory)
                        Spacer(Modifier.weight(1f))
                        GhostButton(text = "reset", onClick = viewModel::onResetSettings)
                    }
                }
            }

            item(key = "about") {
                Section("about")
                SavantPanel(modifier = Modifier.panelPadding()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(text = "savant", style = SavantTypography.titleMedium, color = colors.text)
                        Text(text = VERSION, style = SavantMono.path, color = colors.textMuted)
                        Text(
                            text = "a workbench for game save files. parsing and writing are deterministic kotlin; " +
                                "an assistant can propose edits but never writes a byte.",
                            style = SavantMono.path,
                            color = colors.textMuted,
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SaveFormat.entries.filter { it.implemented }.forEach { format ->
                                Tag(text = format.badge, color = colors.text, emphasis = true)
                            }
                            Tag(text = "${SaveFormat.entries.count { !it.implemented }} planned", color = colors.textFaint)
                        }
                        Text(
                            text = "for offline single player saves. writing to a save used by an online game can corrupt it and can get an account banned.",
                            style = SavantMono.tag,
                            color = colors.caution,
                        )
                    }
                }
            }
        }
    }
}

private fun Modifier.panelPadding(): Modifier = this
    .fillMaxWidth()
    .padding(horizontal = SavantDimens.gutter)
