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
import dev.savant.ai.ProviderModels
import dev.savant.ai.ProviderPing
import dev.savant.ai.ProviderProfile
import dev.savant.ai.ProviderProfiles
import dev.savant.ui.components.AccentButton
import dev.savant.ui.components.ChoiceGrid
import dev.savant.ui.components.GhostButton
import dev.savant.ui.components.Hairline
import dev.savant.ui.components.SavantPanel
import dev.savant.ui.components.ScreenTopBar
import dev.savant.ui.components.Section
import dev.savant.ui.components.SectionLabel
import dev.savant.ui.components.SectionNote
import dev.savant.ui.components.SwitchRow
import dev.savant.ui.components.Tag
import dev.savant.ui.components.gutterPadding
import dev.savant.ui.icons.SavantIcons
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantTypography

/**
 * Providers: what savant can ask, where that goes, and which key it uses.
 *
 * The screen is three panels in one scroll — what is answering, how to add another, and the editor
 * for the one being changed. The editor is where the work went: fields stay unsaved drafts, and a
 * connection panel answers "does this endpoint work" and "what models does it offer" before the
 * profile is used, instead of after the chat has already failed on it.
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
            title = "ai provider",
            subtitle = state.notice ?: state.keyPlace,
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "answering") {
                Section("answering now")
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
                            subtitle = "${profile.provider.label} · ${profile.model.ifBlank { "no model chosen yet" }}",
                            status = if (profile.id == state.activeId) "active" else ProviderProfiles.problem(profile) ?: "ready",
                            selected = profile.id == state.activeId,
                            onClick = { viewModel.onEdit(profile) },
                        )
                    }
                    if (state.profiles.isNotEmpty()) {
                        Hairline()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GhostButton(text = "add another", onClick = { viewModel.onAdd(ProviderKind.CUSTOM) })
                            Spacer(Modifier.weight(1f))
                            if (state.activeId != null) {
                                GhostButton(text = "use on device instead", onClick = { viewModel.onActivate(null) })
                            }
                        }
                    }
                }
            }

            item(key = "add") {
                Section("add a provider")
                SavantPanel(modifier = Modifier.gutterPadding()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "a profile is one provider, one model and its own key. the key is stored " +
                                "separately, so renaming or retargeting a profile never touches it.",
                            style = SavantMono.path,
                            color = colors.textMuted,
                        )
                        ProviderKind.entries.forEach { kind ->
                            AddRow(kind = kind, onClick = { viewModel.onAdd(kind) })
                        }
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
                            connection = state.connection,
                            isActive = draft.id == state.activeId,
                            onChange = viewModel::onEditDraft,
                            onSave = viewModel::onSave,
                            onActivate = { viewModel.onActivate(draft.id) },
                            onKey = { key -> viewModel.onKey(draft, key) },
                            onTest = { key -> viewModel.onTest(draft, key) },
                            onReadModels = { key -> viewModel.onReadModels(draft, key) },
                            onPickModel = { model -> viewModel.onPickModel(draft, model) },
                            onDelete = { viewModel.onDelete(draft) },
                            onClose = viewModel::onCloseEditor,
                        )
                    }
                }
            }

            item(key = "see") {
                Section("what a provider may see")
                SavantPanel(modifier = Modifier.gutterPadding()) {
                    SwitchRow(
                        title = "send field values",
                        subtitle = "off sends paths and types only — useful for a save with names in it",
                        checked = state.sendFieldValues,
                        onChange = viewModel::onSendFieldValues,
                    )
                    Hairline()
                    SectionNote("a provider receives the conversation and that listing. the save file itself never leaves the device, and over 200 kb only a field summary is sent.")
                }
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
            .padding(vertical = 9.dp),
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
 * In three blocks, in the order a profile is actually set up: who it is, where it connects, and
 * what it may see. The key field starts empty every time — a stored key is only ever shown as its
 * mask — and typing one is a separate action from saving the profile, because the two live in
 * different places.
 */
@Composable
private fun ProfileEditor(
    profile: ProviderProfile,
    keyMask: String,
    storageLabel: String,
    connection: ConnectionCheck,
    isActive: Boolean,
    onChange: (ProviderProfile) -> Unit,
    onSave: (ProviderProfile) -> Unit,
    onActivate: (ProviderProfile) -> Unit,
    onKey: (String) -> Unit,
    onTest: (String) -> Unit,
    onReadModels: (String) -> Unit,
    onPickModel: (String) -> Unit,
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
        ChoiceGrid(
            title = "provider",
            options = ProviderKind.entries.map { it.label },
            selected = profile.provider.label,
            onSelect = { label ->
                ProviderKind.entries.firstOrNull { it.label == label }?.let { kind ->
                    // Switching dialect moves the default endpoint with it, unless a base url was
                    // typed on purpose — then it is the user's, and it stays.
                    val defaultWas = ProviderKind.entries.any { profile.baseUrl.isBlank() || profile.baseUrl == it.defaultBaseUrl }
                    onChange(
                        profile.copy(
                            provider = kind,
                            baseUrl = if (defaultWas) kind.defaultBaseUrl else profile.baseUrl,
                            model = if (profile.model.isBlank()) ProviderProfiles.defaultModel(kind) else profile.model,
                        ),
                    )
                }
            },
        )

        Hairline()
        SectionLabel(text = "connection")

        Field(
            label = "base url",
            value = profile.baseUrl,
            hint = profile.provider.defaultBaseUrl.ifBlank { "https://your-endpoint/v1" },
            onChange = { onChange(profile.copy(baseUrl = it.trim())) },
        )

        Text(
            text = if (keyMask.isBlank()) "no key stored" else "key stored · $keyMask",
            style = SavantMono.path,
            color = if (keyMask.isBlank() && ProviderProfiles.needsKey(profile)) colors.caution else colors.textMuted,
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
            if (keyMask.isNotBlank()) {
                GhostButton(text = "clear", onClick = { onKey(""); typedKey = "" })
            }
        }
        Text(
            text = "keys are written to $storageLabel · never to the profile, never to a backup",
            style = SavantMono.tag,
            color = colors.textFaint,
        )

        ConnectionPanel(
            profile = profile,
            connection = connection,
            onTest = { onTest(typedKey.trim()) },
            onReadModels = { onReadModels(typedKey.trim()) },
            onPickModel = onPickModel,
        )

        Field(
            label = "model",
            value = profile.model,
            hint = ProviderProfiles.defaultModel(profile.provider).ifBlank { "the model name this provider expects" },
            onChange = { onChange(profile.copy(model = it.trim())) },
        )

        Hairline()
        SectionLabel(text = "generation")
        ChoiceGrid(
            title = "temperature",
            options = TEMPERATURES,
            selected = profile.temperature.toString(),
            onSelect = { onChange(profile.copy(temperature = it.toFloatOrNull() ?: 0.2f)) },
        )
        ChoiceGrid(
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton(text = "close", onClick = onClose)
            Spacer(Modifier.weight(1f))
            GhostButton(text = "delete", onClick = { onDelete(profile) })
            AccentButton(
                text = if (isActive) "active" else "use this",
                onClick = { onActivate(profile) },
                enabled = !isActive,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ProviderProfiles.problem(profile)?.let { problem ->
                Text(text = "still missing: $problem", style = SavantMono.path, color = colors.caution)
            }
            Spacer(Modifier.weight(1f))
            AccentButton(text = "save", onClick = { onSave(profile) })
        }
    }
}

/**
 * The connection panel.
 *
 * Two buttons, two answers, kept side by side: a connection test says whether the endpoint, the key
 * and the model agree, and the model list says what names this account may ask for. A successful
 * test reads the list too, so one tap usually fills both. Nothing here sends a conversation or a
 * field listing — the only request body is the one a test makes, and it is the word `ok`.
 */
@Composable
private fun ConnectionPanel(
    profile: ProviderProfile,
    connection: ConnectionCheck,
    onTest: () -> Unit,
    onReadModels: () -> Unit,
    onPickModel: (String) -> Unit,
) {
    val colors = Savant.colors
    val test = connection.test
    val models = connection.models

    SavantPanel(
        modifier = Modifier.fillMaxWidth(),
        background = colors.surfaceRaised,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = "connection", style = SavantTypography.bodyMedium, color = colors.text)
                Text(
                    text = "test the endpoint and read the models it offers — neither sends anything about a save file",
                    style = SavantMono.path,
                    color = colors.textMuted,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostButton(
                    text = if (connection.testing) "testing…" else "test connection",
                    icon = SavantIcons.activity,
                    onClick = onTest,
                    enabled = !connection.busy,
                )
                GhostButton(
                    text = if (connection.reading) "reading…" else "read models",
                    icon = SavantIcons.refresh,
                    onClick = onReadModels,
                    enabled = !connection.busy,
                )
            }

            if (test != null) {
                ConnectionResultRow(
                    ok = test.ok,
                    headline = if (test.ok) "answered" else "failed",
                    detail = test.detail,
                    endpoint = test.endpoint,
                    model = test.model,
                    latencyMillis = test.latencyMillis,
                )
            }

            if (models != null) {
                ModelList(
                    models = models,
                    selected = profile.model,
                    onPickModel = onPickModel,
                )
            } else if (test == null) {
                Text(
                    text = connection.prompt,
                    style = SavantMono.tag,
                    color = colors.textFaint,
                )
            }
        }
    }
}

/** One line per answer: what happened, where it happened, and how long it took. */
@Composable
private fun ConnectionResultRow(
    ok: Boolean,
    headline: String,
    detail: String,
    endpoint: String?,
    model: String?,
    latencyMillis: Long,
) {
    val colors = Savant.colors
    val tone = if (ok) colors.positive else colors.negative
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(text = headline, color = tone, emphasis = true)
            endpoint?.let {
                Text(
                    text = it,
                    style = SavantMono.tag,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (ok && latencyMillis > 0) {
                Text(text = "${latencyMillis} ms", style = SavantMono.tag, color = colors.textFaint)
            }
        }
        Text(text = detail, style = SavantMono.path, color = if (ok) colors.textMuted else tone)
        if (ok && !model.isNullOrBlank()) {
            Text(text = "model · $model", style = SavantMono.tag, color = colors.textFaint)
        }
    }
}

/**
 * The model list, as a picker.
 *
 * Long lists are common on openrouter and on a local server with a few dozen pulls, so the list
 * filters as the user types and shows the first [VISIBLE_MODELS] until asked for the rest. Picking
 * a name only changes the draft — nothing is saved until `save`, exactly like every other field.
 */
@Composable
private fun ModelList(
    models: ProviderModels,
    selected: String,
    onPickModel: (String) -> Unit,
) {
    val colors = Savant.colors
    var filter by remember(models) { mutableStateOf("") }
    var showAll by remember(models) { mutableStateOf(false) }

    if (!models.ok) {
        Text(
            text = "model list · ${models.detail}",
            style = SavantMono.path,
            color = colors.caution,
        )
        return
    }

    val matching = remember(models, filter) {
        val needle = filter.trim().lowercase()
        if (needle.isEmpty()) models.models else models.models.filter { it.lowercase().contains(needle) }
    }
    val visible = if (showAll) matching else matching.take(VISIBLE_MODELS)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(text = "models")
            Tag(text = "${matching.size} of ${models.count}", color = colors.textMuted)
        }

        if (models.count > VISIBLE_MODELS) {
            InlineField(
                value = filter,
                hint = "filter ${models.count} models",
                onChange = { filter = it },
            )
        }

        SavantPanel(modifier = Modifier.fillMaxWidth()) {
            if (visible.isEmpty()) {
                Text(
                    text = "nothing matches \"$filter\"",
                    style = SavantMono.path,
                    color = colors.textMuted,
                    modifier = Modifier.padding(10.dp),
                )
            }
            visible.forEachIndexed { index, model ->
                if (index > 0) Hairline()
                ModelRow(
                    model = model,
                    isSelected = model == selected,
                    onClick = { onPickModel(model) },
                )
            }
        }

        if (!showAll && matching.size > visible.size) {
            GhostButton(
                text = "show all ${matching.size}",
                onClick = { showAll = true },
            )
        }
    }
}

@Composable
private fun ModelRow(model: String, isSelected: Boolean, onClick: () -> Unit) {
    val colors = Savant.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = model,
            style = SavantMono.path,
            color = if (isSelected) colors.accent else colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Spacer(Modifier.width(8.dp))
            Tag(text = "in use", color = colors.accent, emphasis = true)
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

/** A shorter field for a filter, with no label above it. */
@Composable
private fun InlineField(
    value: String,
    hint: String,
    onChange: (String) -> Unit,
) {
    val colors = Savant.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background, RoundedCornerShape(SavantDimens.radius))
            .border(SavantDimens.hairline, colors.border, RoundedCornerShape(SavantDimens.radius))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = SavantMono.path.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(text = hint, style = SavantMono.path, color = colors.textFaint)
                }
                inner()
            },
        )
    }
}

/** How many models the picker shows before "show all". */
private const val VISIBLE_MODELS = 6

private val TEMPERATURES = listOf("0.0", "0.1", "0.2", "0.4", "0.7", "1.0")
private val TOKEN_LIMITS = listOf("512", "1024", "2048", "4096")
