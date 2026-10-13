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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.savant.ai.ProviderKind
import dev.savant.ai.ProviderPing
import dev.savant.ai.ProviderProfile
import dev.savant.ai.ProviderProfiles
import dev.savant.ui.components.AccentButton
import dev.savant.ui.components.ChoiceRow
import dev.savant.ui.components.GhostButton
import dev.savant.ui.components.Hairline
import dev.savant.ui.components.SavantPanel
import dev.savant.ui.components.ScreenTopBar
import dev.savant.ui.components.Section
import dev.savant.ui.components.SwitchRow
import dev.savant.ui.components.Tag
import dev.savant.ui.components.gutterPadding
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantTypography

/**
 * Providers: what savant can ask, where that goes, and which key it uses.
 *
 * The screen is a list plus one editor. Editing is explicit — nothing is saved until "save" — so a
 * half typed base url cannot become the endpoint the chat is about to call, and "test connection"
 * tests the draft on screen rather than what happens to be on disk.
 */
@Composable
fun ProvidersScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: ProvidersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = Savant.colors

    LaunchedEffect(state.notice) {
        if (state.notice != null) viewModel.onNoticeShown()
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "ai providers",
            subtitle = state.notice ?: state.keyPlace,
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "engine") {
                Section("engine")
                SavantPanel(modifier = Modifier.gutterPadding()) {
                    EngineRow(
                        title = "on device",
                        subtitle = "the deterministic command parser — no key, no network",
                        status = if (state.activeId == null) "active" else "available",
                        selected = state.activeId == null,
                        onClick = { viewModel.onActivate(null) },
                    )
                    state.profiles.forEach { profile ->
                        Hairline()
                        EngineRow(
                            title = profile.name,
                            subtitle = "${profile.provider.label} · ${profile.model.ifBlank { "no model set" }}",
                            status = if (profile.id == state.activeId) "active" else "tap to edit",
                            selected = profile.id == state.activeId,
                            onClick = { viewModel.onEdit(profile) },
                        )
                        if (profile.id == state.activeId) {
                            Hairline()
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                GhostButton(text = "edit", onClick = { viewModel.onEdit(profile) })
                                Spacer(Modifier.weight(1f))
                                GhostButton(text = "use on device instead", onClick = { viewModel.onActivate(null) })
                            }
                        }
                    }
                }
            }

            item(key = "add") {
                Section("add a provider")
                SavantPanel(modifier = Modifier.gutterPadding()) {
                    Text(
                        text = "a profile is a provider plus a model and a key. the key is stored on its own, " +
                            "so renaming or retargeting a profile never touches it.",
                        style = SavantMono.path,
                        color = colors.textMuted,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                    ProviderKind.entries.forEach { kind ->
                        Hairline()
                        AddRow(
                            kind = kind,
                            onClick = { viewModel.onAdd(kind) },
                        )
                    }
                }
            }

            val draft = state.editing
            if (draft != null) {
                item(key = "editor") {
                    Section("editing ${draft.name}")
                    SavantPanel(modifier = Modifier.gutterPadding()) {
                        ProfileEditor(
                            profile = draft,
                            keyMask = state.keyMask,
                            storageLabel = state.storageLabel,
                            testing = state.testing,
                            test = state.test,
                            isActive = draft.id == state.activeId,
                            onChange = viewModel::onEditDraft,
                            onSave = viewModel::onSave,
                            onActivate = { viewModel.onActivate(draft.id) },
                            onKey = { key -> viewModel.onKey(draft, key) },
                            onTest = { key -> viewModel.onTest(draft, key) },
                            onDelete = { viewModel.onDelete(draft) },
                            onClose = viewModel::onCloseEditor,
                        )
                    }
                }
            }

            item(key = "note") {
                Text(
                    text = "a provider only ever receives a field listing and the conversation. " +
                        "the save file itself never leaves the device, and keys are shown masked everywhere.",
                    style = SavantMono.tag,
                    color = colors.textFaint,
                    modifier = Modifier.padding(horizontal = SavantDimens.gutter, vertical = 6.dp),
                )
            }
        }
    }
}

/** One "make me a profile of this kind" row. */
@Composable
private fun AddRow(kind: ProviderKind, onClick: () -> Unit) {
    val colors = Savant.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = kind.label, style = SavantTypography.bodyMedium, color = colors.text)
            Text(
                text = if (kind == ProviderKind.CUSTOM) {
                    "any openai compatible endpoint — a local server, a proxy, a host you run"
                } else {
                    "${kind.defaultBaseUrl.removePrefix("https://")} · starts on ${ProviderProfiles.defaultModel(kind)}"
                },
                style = SavantMono.path,
                color = colors.textMuted,
            )
        }
        Spacer(Modifier.width(10.dp))
        Tag(text = "add", color = colors.accent)
    }
}

/** One row of the engine list: what it is, and whether it is the one answering. */
@Composable
private fun EngineRow(
    title: String,
    subtitle: String,
    status: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = Savant.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = SavantTypography.bodyMedium, color = colors.text)
            Text(
                text = subtitle,
                style = SavantMono.path,
                color = colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Tag(text = status, color = if (selected) colors.accent else colors.textFaint, emphasis = selected)
    }
}

/**
 * The editor for one profile.
 *
 * The key field starts empty every time — a stored key is only ever shown as its mask — and typing
 * one is a separate action from saving the profile, because the two live in different places.
 */
@Composable
private fun ProfileEditor(
    profile: ProviderProfile,
    keyMask: String,
    storageLabel: String,
    testing: Boolean,
    test: ProviderPing?,
    isActive: Boolean,
    onChange: (ProviderProfile) -> Unit,
    onSave: (ProviderProfile) -> Unit,
    onActivate: (ProviderProfile) -> Unit,
    onKey: (String) -> Unit,
    onTest: (String) -> Unit,
    onDelete: (ProviderProfile) -> Unit,
    onClose: () -> Unit,
) {
    val colors = Savant.colors
    var typedKey by remember(profile.id) { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Field(
            label = "name",
            value = profile.name,
            hint = "what this profile is called on this device",
            onChange = { onChange(profile.copy(name = it)) },
        )
        Field(
            label = "base url",
            value = profile.baseUrl,
            hint = profile.provider.defaultBaseUrl.ifBlank { "https://your-endpoint/v1" },
            onChange = { onChange(profile.copy(baseUrl = it.trim())) },
        )
        Field(
            label = "model",
            value = profile.model,
            hint = "the model name this provider expects",
            onChange = { onChange(profile.copy(model = it.trim())) },
        )
        ChoiceRow(
            title = "temperature",
            options = TEMPERATURES,
            selected = profile.temperature.toString(),
            onSelect = { onChange(profile.copy(temperature = it.toFloatOrNull() ?: 0.2f)) },
        )
        ChoiceRow(
            title = "max output tokens",
            options = TOKEN_LIMITS,
            selected = profile.maxOutputTokens.toString(),
            onSelect = { onChange(profile.copy(maxOutputTokens = it.toIntOrNull() ?: 1024)) },
        )
        SwitchRow(
            title = "stream the answer",
            subtitle = "show the reply while it is being written",
            checked = profile.streaming,
            onChange = { onChange(profile.copy(streaming = it)) },
        )

        Hairline()
        Text(
            text = if (keyMask.isBlank()) "no key stored" else "key stored · $keyMask",
            style = SavantMono.path,
            color = if (keyMask.isBlank()) colors.caution else colors.textMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(colors.background, RoundedCornerShape(SavantDimens.radius))
                    .border(SavantDimens.hairline, colors.borderStrong, RoundedCornerShape(SavantDimens.radius))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                BasicTextField(
                    value = typedKey,
                    onValueChange = { typedKey = it },
                    singleLine = true,
                    textStyle = SavantMono.value.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.accent),
                    visualTransformation = PasswordVisualTransformation(),
                    decorationBox = { inner ->
                        if (typedKey.isEmpty()) {
                            Text(
                                text = profile.provider.keyHint,
                                style = SavantMono.value,
                                color = colors.textFaint,
                            )
                        }
                        inner()
                    },
                )
            }
            GhostButton(text = "save key", onClick = { onKey(typedKey.trim()); typedKey = "" })
        }
        Text(
            text = "keys are written to $storageLabel · never to the profile, never to a backup",
            style = SavantMono.tag,
            color = colors.textFaint,
        )

        Hairline()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton(
                text = if (testing) "testing…" else "test connection",
                onClick = { onTest(typedKey.trim()) },
                enabled = !testing,
            )
            if (keyMask.isNotBlank()) {
                GhostButton(text = "clear key", onClick = { onKey(""); typedKey = "" })
            }
            Spacer(Modifier.weight(1f))
            AccentButton(
                text = if (isActive) "active" else "use this",
                onClick = { onActivate(profile) },
                enabled = !isActive,
            )
        }
        test?.let { result ->
            Text(
                text = buildString {
                    append(if (result.ok) "connected" else "failed")
                    append(" · ").append(result.detail)
                    if (result.ok) append(" · ${result.latencyMillis} ms")
                    result.endpoint?.let { append(" · ").append(it) }
                },
                style = SavantMono.path,
                color = if (result.ok) colors.positive else colors.negative,
            )
        }

        Hairline()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(text = "close", onClick = onClose)
            Spacer(Modifier.weight(1f))
            GhostButton(text = "delete", onClick = { onDelete(profile) })
            AccentButton(text = "save", onClick = { onSave(profile) })
        }
        ProviderProfiles.problem(profile)?.let { problem ->
            Text(text = "still missing: $problem", style = SavantMono.path, color = colors.caution)
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    hint: String,
    onChange: (String) -> Unit,
) {
    val colors = Savant.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, style = SavantTypography.bodyMedium, color = colors.text)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background, RoundedCornerShape(SavantDimens.radius))
                .border(SavantDimens.hairline, colors.borderStrong, RoundedCornerShape(SavantDimens.radius))
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = SavantMono.value.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(text = hint, style = SavantMono.value, color = colors.textFaint)
                    }
                    inner()
                },
            )
        }
    }
}

private val TEMPERATURES = listOf("0.0", "0.2", "0.7", "1.0")
private val TOKEN_LIMITS = listOf("512", "1024", "2048", "4096")
