package dev.savant.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.savant.domain.model.formatBytes
import dev.savant.domain.session.ExportArtifact
import dev.savant.ui.icons.SavantIcon
import dev.savant.ui.icons.SavantIcons
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantTypography

/**
 * The rebuilt file.
 *
 * Carries the verification verdict: savant re-parses what it just wrote and shows that result, so a
 * broken rebuild is visible here instead of in the game. Share and Save are disabled until the
 * report is clean.
 */
@Composable
fun ExportCard(
    artifact: ExportArtifact,
    shared: Boolean,
    savedTo: String?,
    onShare: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val colors = Savant.colors
    val report = artifact.report
    SavantPanel(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SavantDimens.gutter, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SavantIcon(SavantIcons.file, size = 15.dp, tint = colors.text)
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artifact.fileName,
                    style = SavantTypography.titleSmall,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatBytes(artifact.sizeBytes)} · ${report.format.badge}",
                    style = SavantMono.path,
                    color = colors.textMuted,
                )
            }
            Tag(
                text = if (report.ok) "verified" else "blocked",
                color = if (report.ok) colors.positive else colors.negative,
                emphasis = true,
            )
        }
        Hairline()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SavantIcon(
                id = if (report.ok) SavantIcons.checkCircle else SavantIcons.alert,
                size = 14.dp,
                tint = if (report.ok) colors.positive else colors.negative,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = report.summary,
                style = SavantMono.path,
                color = if (report.ok) colors.textMuted else colors.negative,
            )
        }
        report.warnings.take(3).forEach { warning ->
            Text(
                text = "! $warning",
                style = SavantMono.path,
                color = colors.caution,
                modifier = Modifier.padding(start = 34.dp, end = 12.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (shared || savedTo != null) {
            Text(
                text = when {
                    savedTo != null -> "wrote to $savedTo"
                    else -> "handed to the share sheet"
                },
                style = SavantMono.path,
                color = colors.positive,
                modifier = Modifier.padding(start = 34.dp, end = 12.dp, bottom = 6.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Hairline()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.weight(1f))
            GhostButton(
                text = "share",
                icon = SavantIcons.share,
                onClick = onShare,
                enabled = report.ok && !busy,
            )
            GhostButton(
                text = "save to folder",
                icon = SavantIcons.download,
                onClick = onSave,
                enabled = report.ok && !busy,
            )
        }
    }
}
