package dev.savant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.savant.ui.components.Hairline
import dev.savant.ui.components.LinkRow
import dev.savant.ui.components.SavantPanel
import dev.savant.ui.components.ScreenTopBar
import dev.savant.ui.components.Section
import dev.savant.ui.components.gutterPadding
import dev.savant.ui.icons.SavantIcons
import dev.savant.ui.theme.Savant


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
                        subtitle = "what may leave the device · keys ${state.keyStorage} · requests ${
                            if (settings.logNetworkRequests) "logged" else "not logged"
                        }",
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
