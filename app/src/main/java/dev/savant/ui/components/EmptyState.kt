package dev.savant.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.savant.R
import dev.savant.ui.icons.SavantIcons
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantTypography

/**
 * The empty state: a dashed drop zone and nothing else.
 *
 * No mascot, no emoji, no suggestion chips. The copy says what the app is for and what it can
 * already read; the only action is attach.
 */
@Composable
fun EmptyState(
    onAttach: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val colors = Savant.colors
    DashedPanel(
        modifier = modifier
            .fillMaxWidth()
            .padding(SavantDimens.gutter),
    ) {
        SectionLabel(text = stringResource(R.string.app_name), color = colors.textMuted)
        Text(
            text = stringResource(R.string.chat_empty_title),
            style = SavantTypography.titleLarge,
            color = colors.text,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.chat_empty_body),
            style = SavantMono.path,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GhostButton(
                text = stringResource(R.string.chat_attach),
                icon = SavantIcons.attach,
                onClick = onAttach,
                enabled = !busy,
            )
            Text(
                text = "json and xml parse today · rpgsave, sqlite, ini and raw binary arrive in later steps",
                style = SavantMono.tag,
                color = colors.textFaint,
                textAlign = TextAlign.Center,
            )
        }
    }
}
