package dev.savant.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.savant.BuildConfig
import dev.savant.ui.theme.LocalSavantColors
import dev.savant.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    themeMode: ThemeMode,
    reducedMotion: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onReducedMotion: (Boolean) -> Unit,
    onReplayWarning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("settings", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "local controls and integration status",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalSavantColors.current.muted,
                )
            }
        }
        item {
            SettingsSection("AI provider") {
                SettingsRow("active profile", "not configured")
                SettingsRow("providers", "Claude · OpenAI · OpenRouter · Gemini · custom")
                MutedNote("provider profiles, encrypted keys, model selection, and exact connection errors are added with tool calling in step 5.")
            }
        }
        item {
            SettingsSection("generation") {
                SettingsRow("streaming", "not configured")
                SettingsRow("temperature · token limit · prompt", "provider required")
            }
        }
        item {
            SettingsSection("connectors (MCP)") {
                SettingsRow("remote servers", "0 enabled")
                MutedNote("remote HTTP and SSE connectors are not active in this offline build.")
            }
        }
        item {
            SettingsSection("search API") {
                SettingsRow("provider", "not configured")
            }
        }
        item {
            SettingsSection("privacy") {
                SettingsRow("send trimmed field names to AI", "on when AI is configured")
                SettingsRow("allow web tools", "off")
                SettingsRow("domain allowlist", "empty")
                MutedNote("save bytes and raw field values stay on device.")
            }
        }
        item {
            SettingsSection("data") {
                SettingsRow("edit behavior", "always export a new copy")
                SettingsRow("session original protection", "on")
                MutedNote("persistent Room backups, restore, and settings transfer arrive in step 4 and later milestones. secrets will never be exported.")
            }
        }
        item {
            SettingsSection("network activity log") {
                SettingsRow("outbound calls", "0")
                MutedNote("this build has no internet permission and makes no network calls.")
            }
        }
        item {
            SettingsSection("appearance") {
                Text("theme", style = MaterialTheme.typography.bodySmall, color = LocalSavantColors.current.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        ThemeChoice(
                            label = mode.name.lowercase(),
                            selected = themeMode == mode,
                            onClick = { onThemeMode(mode) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                SettingsToggle("reduced motion", reducedMotion, onReducedMotion)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("accent", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.size(18.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Spacer(Modifier.size(8.dp))
                    Text("amber", style = MaterialTheme.typography.labelMedium, color = LocalSavantColors.current.muted)
                }
            }
        }
        item {
            SettingsSection("about") {
                SettingsRow("version", BuildConfig.VERSION_NAME)
                SettingsRow("licenses", "JetBrains Mono · Space Grotesk")
                OutlinedButton(onClick = onReplayWarning, modifier = Modifier.fillMaxWidth()) {
                    Text("replay first-launch warning")
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = LocalSavantColors.current.muted)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, LocalSavantColors.current.border),
        ) {
            Column(
                modifier = Modifier.padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodySmall, color = LocalSavantColors.current.muted)
    }
}

@Composable
private fun MutedNote(text: String) {
    HorizontalDivider(color = LocalSavantColors.current.border)
    Text(text, style = MaterialTheme.typography.bodySmall, color = LocalSavantColors.current.muted)
}

@Composable
private fun SettingsToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ThemeChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) LocalSavantColors.current.raisedSurface else MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, LocalSavantColors.current.border),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onSurface else LocalSavantColors.current.muted,
        )
    }
}
