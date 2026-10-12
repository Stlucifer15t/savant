package dev.savant.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.savant.ui.chat.LoadedSaveUi
import dev.savant.ui.chat.TypeTag
import dev.savant.ui.components.SavantIcon
import dev.savant.ui.components.SavantIconName
import dev.savant.ui.theme.LocalSavantColors

enum class SavantDestination(
    val route: String,
    val label: String,
    val icon: SavantIconName,
) {
    CHAT("chat", "chat", SavantIconName.CHAT),
    FILES("files", "files", SavantIconName.FILES),
    FORMATS("formats", "formats", SavantIconName.FORMATS),
    SETTINGS("settings", "settings", SavantIconName.SETTINGS),
}

@Composable
fun SavantTopBar(
    destination: SavantDestination,
    loaded: LoadedSaveUi?,
    modifier: Modifier = Modifier,
) {
    val border = LocalSavantColors.current.border
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .drawBehind { drawLine(border, start = androidx.compose.ui.geometry.Offset(0f, size.height), end = androidx.compose.ui.geometry.Offset(size.width, size.height), strokeWidth = 1.dp.toPx()) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (destination == SavantDestination.CHAT) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        loaded?.fileName ?: "untitled save",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (loaded == null) "local workbench" else "working copy",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalSavantColors.current.muted,
                    )
                }
                loaded?.let {
                    TypeTag(it.formatName.uppercase())
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (it.backupReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
            } else {
                Text(
                    destination.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

@Composable
fun SavantBottomNavigation(
    selected: SavantDestination,
    onSelect: (SavantDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = LocalSavantColors.current.border
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .drawBehind { drawLine(border, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(56.dp)
            .padding(horizontal = 5.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SavantDestination.entries.forEach { destination ->
            val active = destination == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .background(
                        if (active) LocalSavantColors.current.raisedSurface else Color.Transparent,
                        RoundedCornerShape(4.dp),
                    )
                    .semantics { this.selected = active }
                    .clickable(role = Role.Tab) { onSelect(destination) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                SavantIcon(
                    destination.icon,
                    contentDescription = destination.label,
                    modifier = Modifier.size(19.dp),
                    tint = if (active) MaterialTheme.colorScheme.onBackground else LocalSavantColors.current.muted,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    destination.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.onBackground else LocalSavantColors.current.muted,
                )
            }
        }
    }
}
