package dev.savant.ui.formats

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.savant.domain.model.SaveFormat
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
 * Formats: what savant can open today, and what a plugin will be able to add.
 *
 * Deliberately a static list. There is no plugin runtime yet — that is step 7 — but the schema is
 * written down here so a game can be described before the importer exists.
 */
@Composable
fun FormatsScreen(modifier: Modifier = Modifier) {
    val colors = Savant.colors
    val ready = SaveFormat.entries.filter { it.implemented }
    val planned = SaveFormat.entries.filterNot { it.implemented }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "formats",
            subtitle = "${ready.size} of ${SaveFormat.entries.size} parsers wired up",
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

            item(key = "plugin-header") { Header("plugin schema · step 7") }
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
                            text = "import and export of plugin bundles arrives in step 7; nothing is downloaded or executed",
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
