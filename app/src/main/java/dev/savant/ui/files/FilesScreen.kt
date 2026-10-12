package dev.savant.ui.files

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.savant.ui.chat.LoadedSaveUi
import dev.savant.ui.chat.TypeTag
import dev.savant.ui.chat.formatBytes
import dev.savant.ui.theme.LocalSavantColors

@Composable
fun FilesScreen(
    loaded: LoadedSaveUi?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("files", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "working copies and protected originals",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalSavantColors.current.muted,
                )
            }
        }
        item {
            Text("current session", style = MaterialTheme.typography.labelMedium, color = LocalSavantColors.current.muted)
        }
        if (loaded == null) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, LocalSavantColors.current.border),
                ) {
                    Text(
                        "no save is open",
                        modifier = Modifier.padding(18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalSavantColors.current.muted,
                    )
                }
            }
        } else {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, LocalSavantColors.current.border),
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(loaded.fileName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TypeTag(loaded.formatName)
                        }
                        Text(
                            "${formatBytes(loaded.size)} · ${loaded.tree.editableNodes().size} editable values",
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalSavantColors.current.muted,
                        )
                        Text(
                            if (loaded.backupReady) "original copy protected for this session"
                            else "original copy unavailable · editing blocked",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (loaded.backupReady) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("backup history", style = MaterialTheme.typography.labelMedium, color = LocalSavantColors.current.muted)
                Text(
                    "persistent backup history and one-tap restore are reserved for the Room milestone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalSavantColors.current.muted,
                )
            }
        }
    }
}
