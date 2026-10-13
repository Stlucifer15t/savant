package dev.savant.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.savant.ai.ProviderKind
import dev.savant.ai.ProviderProfile
import dev.savant.ai.provider.NetworkEvent
import dev.savant.ai.provider.NetworkPolicy
import dev.savant.ai.provider.ProfileSet
import dev.savant.ai.web.SearchProvider
import dev.savant.mcp.McpAuth
import dev.savant.mcp.McpServer
import dev.savant.data.settings.ThemeMode
import dev.savant.data.storage.ageSince
import dev.savant.domain.model.SaveFormat
import dev.savant.domain.model.formatBytes
import dev.savant.ui.components.AccentButton
import dev.savant.ui.components.GhostButton
import dev.savant.ui.components.Hairline
import dev.savant.ui.components.SavantPanel
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

            item(key = "providers") {
                Section("providers")
                SavantPanel(modifier = Modifier.panelPadding()) {
                    ValueRow(
                        title = "engine",
                        value = state.engineLabel,
                        subtitle = if (state.active == null) {
                            "on device: a deterministic command parser, no key, no network"
                        } else {
                            "every edit a provider proposes still lands in the diff preview before anything is written"
                        },
                    )
                    Hairline()

                    if (state.profiles.isEmpty()) {
                        ValueRow(
                            title = "profiles",
                            value = "none",
                            subtitle = "add one to talk to a model: openai compatible, anthropic, google, or a local http endpoint",
                            muted = true,
                        )
                    } else {
                        state.profiles.forEachIndexed { index, profile ->
                            if (index > 0) Hairline()
                            ProfileRow(
                                profile = profile,
                                active = profile.id == state.activeId,
                                keyMask = state.keyMasks[profile.id].orEmpty(),
                                verdict = state.verdictFor(profile),
                                onEdit = { viewModel.onEditProfile(profile) },
                                onActivate = {
                                    viewModel.onActivate(if (profile.id == state.activeId) null else profile.id)
                                },
                                onDelete = { viewModel.onDeleteProfile(profile.id) },
                            )
                        }
                    }

                    if (state.editing == null) {
                        Hairline()
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionLabel(text = "add a profile")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ProviderKind.entries.forEach { kind ->
                                    GhostButton(text = kind.label, onClick = { viewModel.onAddProfile(kind) })
                                }
                            }
                            if (state.active != null) {
                                GhostButton(text = "switch back to on device", onClick = { viewModel.onActivate(null) })
                            }
                        }
                    } else {
                        Hairline()
                        ProfileEditor(state = state, viewModel = viewModel)
                    }
                }
            }

            item(key = "web") {
                Section("web tools")
                WebToolsSection(state = state, viewModel = viewModel)
            }

            item(key = "connectors") {
                Section("connectors")
                ConnectorsSection(state = state, viewModel = viewModel)
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
                        subtitle = "keys are redacted to their last four characters",
                        checked = settings.logNetworkRequests,
                        onChange = viewModel::onLogNetwork,
                    )
                    Hairline()
                    ValueRow(
                        title = "api keys",
                        value = if (state.secretsEncrypted) "encrypted" else "memory only",
                        subtitle = if (state.secretsEncrypted) {
                            "EncryptedSharedPreferences behind a keystore master key; names encrypted too, and app backup is off"
                        } else {
                            "the keystore refused, so keys live in memory for this process only and are gone on restart"
                        },
                        muted = !state.secretsEncrypted,
                    )
                    Hairline()
                    ValueRow(
                        title = "cleartext http",
                        value = "local only",
                        subtitle = "http is refused unless it points at loopback, link local or a private range — a local model box, not the internet",
                    )
                    Hairline()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Spacer(Modifier.weight(1f))
                        GhostButton(text = "forget every key", onClick = viewModel::onForgetKeys)
                    }
                }
            }

            item(key = "network") {
                Section("network log")
                SavantPanel(modifier = Modifier.panelPadding()) {
                    when {
                        !settings.logNetworkRequests -> ValueRow(
                            title = "network log",
                            value = "off",
                            subtitle = "turn on logging under privacy to see the shape of every provider call",
                            muted = true,
                        )

                        state.network.isEmpty() -> ValueRow(
                            title = "network log",
                            value = "empty",
                            subtitle = "no provider call yet · entries hold urls, statuses and timings, never prompts or values",
                            muted = true,
                        )

                        else -> {
                            state.network.forEachIndexed { index, event ->
                                if (index > 0) Hairline()
                                NetworkRow(event)
                            }
                            Hairline()
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "${state.network.size} entries · kept in memory, newest first",
                                    style = SavantMono.path,
                                    color = colors.textFaint,
                                    modifier = Modifier.weight(1f),
                                )
                                GhostButton(text = "clear", onClick = viewModel::onClearNetworkLog)
                            }
                        }
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

@Composable
private fun Section(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(text = title)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Savant.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = { onChange(!checked) })
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = SavantTypography.bodyMedium,
                color = if (enabled) colors.text else colors.textFaint,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = SavantMono.path,
                    color = colors.textMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                checkedBorderColor = colors.accent,
                uncheckedThumbColor = colors.textMuted,
                uncheckedTrackColor = colors.surfaceRaised,
                uncheckedBorderColor = colors.border,
                disabledCheckedThumbColor = colors.onAccent.copy(alpha = 0.4f),
                disabledCheckedTrackColor = colors.accent.copy(alpha = 0.3f),
                disabledUncheckedThumbColor = colors.textFaint,
                disabledUncheckedTrackColor = colors.surfaceRaised,
            ),
        )
    }
}

@Composable
private fun ValueRow(
    title: String,
    value: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    val colors = Savant.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = SavantTypography.bodyMedium, color = colors.text)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = SavantMono.path,
                    color = colors.textMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Tag(text = value, color = if (muted) colors.textFaint else colors.text, emphasis = !muted)
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val colors = Savant.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp)) {
        Text(text = title, style = SavantTypography.bodyMedium, color = colors.text)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) colors.accentDim else colors.surfaceRaised)
                        .clickable { onSelect(option) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = option,
                        style = SavantMono.value,
                        color = if (isSelected) colors.accent else colors.textMuted,
                    )
                }
            }
        }
    }
}

/**
 * One provider profile.
 *
 * The second line is the part that matters: which endpoint, and whether savant will actually call it.
 * A profile pointing at a plain http url on the internet is shown as blocked here rather than failing
 * later with a socket error nobody can read.
 */
@Composable
private fun ProfileRow(
    profile: ProviderProfile,
    active: Boolean,
    keyMask: String,
    verdict: NetworkPolicy.Verdict,
    onEdit: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Savant.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = profile.name.ifBlank { profile.provider.label },
                style = SavantTypography.bodyMedium,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Tag(text = profile.provider.label, color = colors.textMuted)
            if (active) Tag(text = "active", color = colors.accent, emphasis = true)
        }
        Text(
            text = "${profile.model.ifBlank { "no model" }} · ${NetworkPolicy.redactUrl(profile.effectiveBaseUrl)}",
            style = SavantMono.path,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append(
                    when (verdict) {
                        NetworkPolicy.Verdict.Secure -> "https"
                        NetworkPolicy.Verdict.LocalCleartext -> "http · local · unencrypted"
                        is NetworkPolicy.Verdict.Refused -> "blocked"
                    },
                )
                append(" · ")
                append(if (keyMask.isBlank()) "no key stored" else "key $keyMask")
            },
            style = SavantMono.tag,
            color = when (verdict) {
                is NetworkPolicy.Verdict.Refused -> colors.negative
                NetworkPolicy.Verdict.LocalCleartext -> colors.caution
                else -> if (keyMask.isBlank()) colors.caution else colors.textFaint
            },
        )
        if (verdict is NetworkPolicy.Verdict.Refused) {
            Text(text = verdict.reason, style = SavantMono.path, color = colors.negative)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(text = "edit", onClick = onEdit)
            GhostButton(text = "delete", onClick = onDelete)
            Spacer(Modifier.weight(1f))
            if (active) {
                GhostButton(text = "stop using", onClick = onActivate)
            } else {
                AccentButton(text = "use", onClick = onActivate, enabled = keyMask.isNotBlank() && verdict.allowed)
            }
        }
    }
}

/**
 * The profile editor, inline in the panel.
 *
 * Flat fields, chip selectors for the two numbers, and the endpoint verdict under them. The key field
 * is blank on purpose: a stored key is never read back into a text field, and leaving it blank keeps
 * whatever is already in encrypted storage.
 */
@Composable
private fun ProfileEditor(state: SettingsUiState, viewModel: SettingsViewModel) {
    val colors = Savant.colors
    val profile = state.editing ?: return
    val verdict = state.verdictFor(profile)
    val ping = state.ping

    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SectionLabel(text = if (state.profiles.any { it.id == profile.id }) "edit profile" else "new profile")

        TextFieldRow(label = "name", value = profile.name, placeholder = "work", onValueChange = viewModel::onEditingName)
        ChoiceRow(
            title = "provider",
            options = ProviderKind.entries.map { it.label },
            selected = profile.provider.label,
            onSelect = { label ->
                ProviderKind.entries.firstOrNull { it.label == label }?.let(viewModel::onEditingKind)
            },
        )
        TextFieldRow(
            label = "model",
            value = profile.model,
            placeholder = ProfileSet.MODEL_HINT[profile.provider] ?: "model id",
            onValueChange = viewModel::onEditingModel,
        )
        TextFieldRow(
            label = "base url",
            value = profile.baseUrl,
            placeholder = profile.provider.defaultBaseUrl.ifBlank { "https://…" },
            onValueChange = viewModel::onEditingBaseUrl,
        )
        TextFieldRow(
            label = "api key",
            value = state.keyDraft,
            placeholder = if (state.keyIsSet) "stored — type to replace" else profile.provider.keyHint,
            onValueChange = viewModel::onKeyDraft,
        )
        ChoiceRow(
            title = "temperature",
            options = TEMPERATURES,
            selected = TEMPERATURES.closestTo(profile.temperature),
            onSelect = { label -> label.toFloatOrNull()?.let(viewModel::onEditingTemperature) },
        )
        ChoiceRow(
            title = "max output tokens",
            options = TOKEN_LIMITS,
            selected = TOKEN_LIMITS.closestTo(profile.maxOutputTokens.toFloat()),
            onSelect = { label -> label.toIntOrNull()?.let(viewModel::onEditingMaxTokens) },
        )

        Text(
            text = when (verdict) {
                NetworkPolicy.Verdict.Secure -> "https · key travels in a header"
                NetworkPolicy.Verdict.LocalCleartext -> (verdict as NetworkPolicy.Verdict.LocalCleartext).warning
                is NetworkPolicy.Verdict.Refused -> verdict.reason
            },
            style = SavantMono.path,
            color = when (verdict) {
                is NetworkPolicy.Verdict.Refused -> colors.negative
                NetworkPolicy.Verdict.LocalCleartext -> colors.caution
                else -> colors.textMuted
            },
        )
        if (ping != null) {
            Text(
                text = when {
                    ping.running -> "testing…"
                    ping.ok == true -> "reachable · ${ping.detail}"
                    else -> "not reachable · ${ping.detail}"
                },
                style = SavantMono.path,
                color = when (ping.ok) {
                    null -> colors.textMuted
                    true -> colors.positive
                    false -> colors.negative
                },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(text = "cancel", onClick = viewModel::onCancelEdit)
            GhostButton(
                text = "test",
                onClick = viewModel::onTestConnection,
                enabled = ping?.running != true && verdict.allowed,
            )
            Spacer(Modifier.weight(1f))
            AccentButton(text = "save", onClick = viewModel::onSaveProfile, enabled = profile.model.isNotBlank())
        }
    }
}

/** A flat one line mono field with its label above it. */
@Composable
private fun TextFieldRow(label: String, value: String, placeholder: String, onValueChange: (String) -> Unit) {
    val colors = Savant.colors
    val shape = RoundedCornerShape(SavantDimens.radius)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = SavantMono.tag, color = colors.textMuted)
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
}

/** One provider call, as the log shows it: shape and timing, never contents. */
@Composable
private fun NetworkRow(event: NetworkEvent) {
    val colors = Savant.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(
            text = "${event.method} ${event.url}",
            style = SavantMono.path,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append(if (event.status == 0) "failed" else event.status.toString())
                append(" · ${event.latencyMillis} ms")
                append(" · ${event.requestBytes}/${event.responseBytes} bytes")
                if (event.tokensIn != null || event.tokensOut != null) append(" · ${event.tokensIn ?: 0}/${event.tokensOut ?: 0} tokens")
                append(" · key ${event.keyMask.ifBlank { "none" }}")
                if (event.cleartext) append(" · cleartext")
                append(" · ${event.whenLabel}")
            },
            style = SavantMono.tag,
            color = if (event.ok) colors.textMuted else colors.negative,
        )
        if (event.error != null) {
            Text(
                text = event.error,
                style = SavantMono.path,
                color = colors.caution,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val TEMPERATURES = listOf("0", "0.1", "0.2", "0.4", "0.7", "1")
private val TOKEN_LIMITS = listOf("256", "512", "1024", "2048", "4096")

/** Chips are discrete and a stored number is not, so the nearest one is shown as selected. */
private fun List<String>.closestTo(value: Float): String =
    minByOrNull { kotlin.math.abs((it.toFloatOrNull() ?: 0f) - value) } ?: first()

/**
 * `web_search` and `fetch_url`.
 *
 * Off by default, and the copy says what each switch actually changes: offering a tool to a model is
 * not the same as letting it run, and letting it run is not the same as letting it run unasked.
 */
@Composable
private fun WebToolsSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val colors = Savant.colors
    val settings = state.settings
    SavantPanel(modifier = Modifier.panelPadding()) {
        SwitchRow(
            title = "web tools",
            subtitle = "offer web_search and fetch_url to the model. off means neither is mentioned in the prompt",
            checked = settings.webToolsEnabled,
            onChange = viewModel::onWebTools,
        )
        Hairline()
        SwitchRow(
            title = "web search",
            subtitle = "web_search calls a search api. fetch_url works without it",
            checked = settings.searchApiEnabled,
            onChange = viewModel::onSearchApi,
            enabled = settings.webToolsEnabled,
        )
        Hairline()
        ChoiceRow(
            title = "search api",
            options = SearchProvider.entries.map { it.label },
            selected = state.searchProvider.label,
            onSelect = { label ->
                SearchProvider.entries.firstOrNull { it.label == label }?.let(viewModel::onSearchProvider)
            },
        )
        if (state.searchProvider.needsKey) {
            TextFieldRow(
                label = "search api key",
                value = state.searchKeyDraft,
                placeholder = if (state.searchKeyIsSet) "stored — type to replace" else "key",
                onValueChange = viewModel::onSearchKeyDraft,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(Modifier.weight(1f))
                GhostButton(text = "save key", onClick = viewModel::onSaveSearchKey)
            }
        }
        Hairline()
        SwitchRow(
            title = "confirm every web call",
            subtitle = "a card in the chat shows the query or url before anything is sent. off means the model may search and fetch on its own",
            checked = settings.confirmWebCalls,
            onChange = viewModel::onConfirmWebCalls,
            enabled = settings.webToolsEnabled,
        )
        Hairline()
        TextFieldRow(
            label = "allowlisted domains",
            value = settings.allowListedDomains,
            placeholder = "empty means ask every time",
            onValueChange = viewModel::onAllowListChanged,
        )
        Text(
            text = buildString {
                if (state.allowList.isEmpty()) {
                    append("no allowlist: fetch_url may ask for any https url, and asks first every time")
                } else {
                    append("fetch_url is limited to ${state.allowList.joinToString()} and their subdomains")
                }
                append(" · 500 kb · 15 s · results are handed to the model marked as untrusted")
            },
            style = SavantMono.path,
            color = colors.textMuted,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * Remote MCP connectors.
 *
 * Http and sse only: there is no stdio transport and savant never starts a program. A connector's
 * tools are listed here and cached, so the model can be told what it may call without a round trip.
 */
@Composable
private fun ConnectorsSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val colors = Savant.colors
    SavantPanel(modifier = Modifier.panelPadding()) {
        if (state.connectors.isEmpty()) {
            ValueRow(
                title = "connectors",
                value = "none",
                subtitle = "remote mcp servers over http or sse. no stdio, no subprocess, nothing downloaded",
                muted = true,
            )
        } else {
            state.connectors.forEachIndexed { index, server ->
                if (index > 0) Hairline()
                ConnectorRow(
                    server = server,
                    tools = state.toolsFor(server.id),
                    connecting = state.connectingId == server.id,
                    onConnect = { viewModel.onConnect(server.id) },
                    onEdit = { viewModel.onEditConnector(server) },
                    onDelete = { viewModel.onDeleteConnector(server.id) },
                    onToggle = { enabled -> viewModel.onToggleConnector(server.id, enabled) },
                    onToggleConfirm = { confirm -> viewModel.onToggleConnectorConfirm(server.id, confirm) },
                )
            }
        }

        Hairline()
        if (state.editingServer == null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GhostButton(text = "add connector", onClick = viewModel::onAddConnector)
                Spacer(Modifier.weight(1f))
            }
        } else {
            ConnectorEditor(state = state, viewModel = viewModel)
        }

        state.connectResult?.let { result ->
            Text(
                text = result,
                style = SavantMono.path,
                color = if (result.contains("did not connect")) colors.negative else colors.textMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ConnectorRow(
    server: McpServer,
    tools: List<dev.savant.mcp.McpTool>,
    connecting: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onToggleConfirm: (Boolean) -> Unit,
) {
    val colors = Savant.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = server.name,
                style = SavantTypography.bodyMedium,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Tag(text = server.auth.label, color = colors.textMuted)
            if (server.confirmEveryCall) Tag(text = "confirms", color = colors.textMuted)
            Tag(
                text = if (server.enabled) "on" else "off",
                color = if (server.enabled) colors.positive else colors.textFaint,
                emphasis = server.enabled,
            )
        }
        Text(
            text = NetworkPolicy.redactUrl(server.url),
            style = SavantMono.path,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = when {
                tools.isEmpty() -> "no tools listed yet — connect to ask the server"
                else -> "${tools.size} tool(s): " + tools.joinToString { it.name }
            },
            style = SavantMono.tag,
            color = colors.textFaint,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        server.serverVersion?.let {
            Text(text = "server $it · last seen ${server.lastSeen?.let { at -> ageSince(at) } ?: "never"}", style = SavantMono.tag, color = colors.textFaint)
        }
        SwitchRow(
            title = "confirm every call",
            subtitle = null,
            checked = server.confirmEveryCall,
            onChange = onToggleConfirm,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(text = if (connecting) "connecting" else "connect", onClick = onConnect, enabled = !connecting)
            GhostButton(text = "edit", onClick = onEdit)
            GhostButton(text = "delete", onClick = onDelete)
            Spacer(Modifier.weight(1f))
            GhostButton(text = if (server.enabled) "disable" else "enable", onClick = { onToggle(!server.enabled) })
        }
    }
}

@Composable
private fun ConnectorEditor(state: SettingsUiState, viewModel: SettingsViewModel) {
    val colors = Savant.colors
    val server = state.editingServer ?: return
    val verdict = NetworkPolicy.assess(server.url.trim().ifBlank { "https://" })
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SectionLabel(text = if (server.id.isBlank()) "new connector" else "edit connector")
        TextFieldRow(label = "name", value = server.name, placeholder = "the wiki", onValueChange = viewModel::onServerName)
        TextFieldRow(label = "url", value = server.url, placeholder = "https://mcp.example.com/mcp", onValueChange = viewModel::onServerUrl)
        ChoiceRow(
            title = "auth",
            options = McpAuth.entries.map { it.label },
            selected = server.auth.label,
            onSelect = { label -> McpAuth.entries.firstOrNull { it.label == label }?.let(viewModel::onServerAuth) },
        )
        if (server.auth == McpAuth.HEADER) {
            TextFieldRow(label = "header", value = server.authHeader, placeholder = "x-api-key", onValueChange = viewModel::onServerAuthHeader)
        }
        if (server.auth != McpAuth.NONE) {
            TextFieldRow(
                label = "key",
                value = state.serverKeyDraft,
                placeholder = if (state.serverKeyIsSet) "stored — type to replace" else "key",
                onValueChange = viewModel::onServerKeyDraft,
            )
        }
        Text(
            text = when (verdict) {
                NetworkPolicy.Verdict.Secure -> "https · the key travels in a header"
                NetworkPolicy.Verdict.LocalCleartext -> (verdict as NetworkPolicy.Verdict.LocalCleartext).warning
                is NetworkPolicy.Verdict.Refused -> verdict.reason
            },
            style = SavantMono.path,
            color = when (verdict) {
                is NetworkPolicy.Verdict.Refused -> colors.negative
                NetworkPolicy.Verdict.LocalCleartext -> colors.caution
                else -> colors.textMuted
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(text = "cancel", onClick = viewModel::onCancelServer)
            Spacer(Modifier.weight(1f))
            AccentButton(text = "save", onClick = viewModel::onSaveConnector, enabled = server.url.isNotBlank() && verdict.allowed)
        }
    }
}
