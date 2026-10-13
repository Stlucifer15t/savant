package dev.savant.ui.formats

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import dev.savant.domain.model.SaveFormat
import dev.savant.plugins.GamePlugin
import dev.savant.plugins.PluginSource
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

/**
 * Formats: what savant can open, and what plugins have added to it.
 *
 * The format list is static, because savant's own parsers are. The plugin list is not: import a json
 * document, export one, delete one, or turn the offsets named in the finder into a plugin for that
 * game. A plugin is data — nothing here downloads, generates or runs code.
 */
@Composable
fun FormatsScreen(
    modifier: Modifier = Modifier,
    viewModel: FormatsViewModel = hiltViewModel(),
) {
    val colors = Savant.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ready = SaveFormat.entries.filter { it.implemented }
    val planned = SaveFormat.entries.filterNot { it.implemented }

    val importPlugin = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onImport(uri)
    }
    val createDocument = rememberLauncherForActivityResult(
        remember { ActivityResultContracts.CreateDocument("application/json") },
    ) { uri -> viewModel.onSaveResult(uri) }

    LaunchedEffect(Unit) {
        viewModel.formatsEvents.collect { event ->
            when (event) {
                is FormatsEvent.Share -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        putExtra(Intent.EXTRA_SUBJECT, event.fileName)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val started = runCatching { context.startActivity(Intent.createChooser(send, "export plugin")) }.isSuccess
                    if (!started) Toast.makeText(context, "no app can take that file", Toast.LENGTH_SHORT).show()
                }

                is FormatsEvent.CreateDocument -> createDocument.launch(event.fileName)
            }
        }
    }
    LaunchedEffect(state.notice) {
        if (state.notice != null) viewModel.onNoticeShown()
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "formats",
            subtitle = state.notice ?: buildString {
                append("${ready.size} of ${SaveFormat.entries.size} parsers wired up")
                if (state.plugins.isNotEmpty()) append(" · ${state.plugins.size} plugin(s)")
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "ready-header") { Header("ready") }
            items(ready, key = { it.name }) { format -> FormatRow(format) }

            item(key = "planned-header") { Header("planned") }
            items(planned, key = { it.name }) { format -> FormatRow(format) }

            item(key = "codec-header") { Header("codecs and checksums") }
            item(key = "codecs") {
                SavantPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "every parser can be wrapped in a codec and a checksum",
                            style = SavantTypography.bodySmall,
                            color = colors.text,
                        )
                        Text(
                            text = "codecs: none · gzip · zlib · xor (lz-string and aes arrive with the rpgsave parser)",
                            style = SavantMono.path,
                            color = colors.textMuted,
                        )
                        Text(
                            text = "checksums: crc32 · adler32 · md5 · sha1 · sha256, over any byte range, stored as bytes or text",
                            style = SavantMono.path,
                            color = colors.textMuted,
                        )
                        Text(
                            text = "a sniffed codec savant cannot re-encode opens read only, and says why",
                            style = SavantMono.path,
                            color = colors.caution,
                        )
                    }
                }
            }

            item(key = "plugins-header") { Header("plugins") }
            item(key = "plugins") {
                SavantPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter)) {
                    if (state.plugins.isEmpty()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "no plugins installed",
                                style = SavantTypography.bodySmall,
                                color = colors.text,
                            )
                            Text(
                                text = "a plugin says which files a game writes and where its values live. savant reads it and does the bytes itself.",
                                style = SavantMono.path,
                                color = colors.textMuted,
                            )
                        }
                    } else {
                        state.plugins.forEachIndexed { index, plugin ->
                            if (index > 0) Hairline()
                            PluginRow(
                                plugin = plugin,
                                onExport = { viewModel.onExport(plugin.id) },
                                onSave = { viewModel.onSave(plugin.id) },
                                onDelete = { viewModel.onDelete(plugin.id) },
                            )
                        }
                        Hairline()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GhostButton(text = "export all", onClick = { viewModel.onExport(null) })
                            GhostButton(text = "save all", onClick = { viewModel.onSave(null) })
                            Spacer(Modifier.weight(1f))
                            GhostButton(text = "delete all", onClick = viewModel::onClearAll)
                        }
                    }

                    Hairline()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GhostButton(text = "import json", onClick = { importPlugin.launch(arrayOf("application/json", "*/*")) })
                        Spacer(Modifier.weight(1f))
                        if (state.namedOffsets.isEmpty()) {
                            Text(
                                text = "nothing named in the finder yet",
                                style = SavantMono.path,
                                color = colors.textFaint,
                            )
                        } else if (!state.drafting) {
                            AccentButton(
                                text = "plugin from ${state.namedOffsets.size} named offset(s)",
                                onClick = viewModel::onStartDraft,
                            )
                        }
                    }

                    if (state.drafting) {
                        Hairline()
                        DraftPanel(
                            name = state.draftName,
                            offsets = state.namedOffsets,
                            onNameChange = viewModel::onDraftNameChanged,
                            onCancel = viewModel::onCancelDraft,
                            onCreate = viewModel::onCreateDraft,
                        )
                    }
                }
            }

            item(key = "plugin-header") { Header("plugin schema") }
            item(key = "plugin") {
                SavantPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "a plugin is a json document describing one game",
                            style = SavantTypography.bodySmall,
                            color = colors.text,
                        )
                        Spacer(Modifier.height(2.dp))
                        PLUGIN_SAMPLE.lines().forEach { line ->
                            Text(text = line, style = SavantMono.log, color = colors.textMuted)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "optional keys: magic (hex header bytes), endianness, count, recordOffset, pointerType, " +
                                "pointerBase, name, category, min, max, editable, notes. " +
                                "a plugin is data: nothing is downloaded, generated or executed.",
                            style = SavantMono.path,
                            color = colors.textFaint,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(text = title)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun FormatRow(format: SaveFormat) {
    val colors = Savant.colors
    SavantPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(text = format.badge, color = if (format.implemented) colors.text else colors.textFaint, emphasis = format.implemented)
                Spacer(Modifier.width(9.dp))
                Text(
                    text = format.label,
                    style = SavantTypography.bodyMedium,
                    color = if (format.implemented) colors.text else colors.textMuted,
                    modifier = Modifier.weight(1f),
                )
                Tag(
                    text = if (format.implemented) "ready" else "planned",
                    color = if (format.implemented) colors.positive else colors.textFaint,
                )
            }
            Text(
                text = format.extensions.joinToString(" ") { ".$it" },
                style = SavantMono.path,
                color = colors.textFaint,
            )
            Text(text = format.notes, style = SavantMono.path, color = colors.textMuted)
        }
        Hairline()
    }
}

private val PLUGIN_SAMPLE = """
{
  "gameName": "example quest",
  "fileMatch": ["save*.dat"],
  "parser": "binary",
  "codec": "zlib",
  "checksum": { "strategy": "crc32", "range": [4, -1], "storedAt": [0, 4] },
  "fields": [
    { "path": "player.gold", "offset": 16, "type": "u32" },
    { "path": "party[*].hp", "pointer": 40, "stride": 24, "type": "i16" }
  ]
}
""".trimIndent()

/** One installed plugin, with the three things you can do to it: export it, save it, delete it. */
@Composable
private fun PluginRow(plugin: GamePlugin, onExport: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    val colors = Savant.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = plugin.gameName,
                style = SavantTypography.bodyMedium,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Tag(text = plugin.parser.id, color = colors.textMuted)
            if (plugin.source != PluginSource.IMPORTED) Tag(text = plugin.source.label, color = colors.textFaint)
        }
        Text(
            text = plugin.summary,
            style = SavantMono.path,
            color = colors.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append(plugin.fields.size)
                append(" mapping(s)")
                plugin.checksum?.let { append(" · checksum ${it.strategy}") }
                if (plugin.magic.isNotBlank()) append(" · magic ${plugin.magic}")
                if (plugin.notes.isNotBlank()) append(" · ${plugin.notes}")
            },
            style = SavantMono.tag,
            color = colors.textFaint,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(text = "export", onClick = onExport)
            GhostButton(text = "save", onClick = onSave)
            Spacer(Modifier.weight(1f))
            GhostButton(text = "delete", onClick = onDelete)
        }
    }
}

/**
 * The named offsets from the finder, about to become a plugin.
 *
 * The list is shown before it is saved, because these are somebody's own offsets from their own save
 * and they should see what they are storing.
 */
@Composable
private fun DraftPanel(
    name: String,
    offsets: List<FormatsUiState.NamedOffset>,
    onNameChange: (String) -> Unit,
    onCancel: () -> Unit,
    onCreate: () -> Unit,
) {
    val colors = Savant.colors
    val shape = RoundedCornerShape(SavantDimens.radius)
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        SectionLabel(text = "new plugin from named offsets")
        BasicTextField(
            value = name,
            onValueChange = onNameChange,
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
                    if (name.isEmpty()) {
                        Text(text = "game name", style = SavantMono.value, color = colors.textFaint)
                    }
                    inner()
                }
            },
        )
        offsets.take(8).forEach { offset ->
            Text(
                text = "${offset.label} · ${offset.key} · ${offset.name}",
                style = SavantMono.path,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (offsets.size > 8) {
            Text(text = "+${offsets.size - 8} more", style = SavantMono.path, color = colors.textFaint)
        }
        Text(
            text = "the file match starts as *.bin — edit the exported json to point it at the right files",
            style = SavantMono.tag,
            color = colors.caution,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(text = "cancel", onClick = onCancel)
            Spacer(Modifier.weight(1f))
            AccentButton(text = "create plugin", onClick = onCreate)
        }
    }
}
