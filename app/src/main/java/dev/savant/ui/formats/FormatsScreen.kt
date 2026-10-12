package dev.savant.ui.formats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.savant.ui.chat.TypeTag
import dev.savant.ui.theme.LocalSavantColors

@Composable
fun FormatsScreen(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("formats", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "deterministic parsers installed on this device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalSavantColors.current.muted,
                )
            }
        }
        item { FormatRow("JSON", "objects, arrays, scalar values", "json · sav · save") }
        item { FormatRow("XML", "elements, attributes, CDATA", "xml · plist · sav · save") }
        item {
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("import plugin from JSON")
            }
        }
        item {
            Text(
                "plugin validation and sharing arrive with the plugin-system milestone. plugins contain mappings only—never executable code.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalSavantColors.current.muted,
            )
        }
    }
}

@Composable
private fun FormatRow(name: String, summary: String, extensions: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, LocalSavantColors.current.border),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = LocalSavantColors.current.muted)
                Text(extensions, style = MaterialTheme.typography.labelSmall, color = LocalSavantColors.current.muted)
            }
            TypeTag("installed")
        }
    }
}
