package dev.savant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.savant.ai.NetworkLog
import dev.savant.ai.NetworkRecord
import dev.savant.data.settings.SecretStore
import dev.savant.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.savant.ui.components.GhostButton
import dev.savant.ui.components.Hairline
import dev.savant.ui.components.SavantPanel
import dev.savant.ui.components.ScreenTopBar
import dev.savant.ui.components.SwitchRow
import dev.savant.ui.components.gutterPadding
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NetworkUiState(
    val records: List<NetworkRecord> = emptyList(),
    val enabled: Boolean = false,
    val keyStorage: String = "memory only",
)

/**
 * What left the device, in order.
 *
 * This screen exists because "no uploads" is a claim, and a claim is worth less than a list. Every
 * request savant made is here with its endpoint, its size, whether values were included and which
 * key was used — masked to four characters. It holds nothing when logging is off, and it is memory
 * only: the list is gone when the process is.
 */
@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val log: NetworkLog,
    private val settings: SettingsRepository,
    secrets: SecretStore,
) : ViewModel() {

    private val keyStorage = secrets.storageLabel

    val state: StateFlow<NetworkUiState> = combine(settings.settings, log.records) { prefs, records ->
        NetworkUiState(records = records, enabled = prefs.logNetworkRequests, keyStorage = keyStorage)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkUiState(keyStorage = keyStorage))

    fun onLogging(enabled: Boolean) = viewModelScope.launch { settings.setLogNetworkRequests(enabled) }

    fun onClear() = log.clear()
}

@Composable
fun NetworkScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: NetworkViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = Savant.colors
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "network activity",
            subtitle = "${state.records.size} request${if (state.records.size == 1) "" else "s"} · keys in ${state.keyStorage}",
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "switch") {
                SavantPanel(modifier = Modifier.gutterPadding()) {
                    SwitchRow(
                        title = "log network requests",
                        subtitle = "off by default · the list is in memory only and never written to disk",
                        checked = state.enabled,
                        onChange = viewModel::onLogging,
                    )
                }
            }

            if (state.records.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (state.enabled) {
                            "nothing has been sent yet. a provider request appears here the moment it leaves."
                        } else {
                            "logging is off, so nothing is recorded. the chat still works."
                        },
                        style = SavantMono.path,
                        color = colors.textMuted,
                        modifier = Modifier.padding(horizontal = SavantDimens.gutter, vertical = 8.dp),
                    )
                }
            }

            items(state.records, key = { "${it.atMillis}-${it.endpoint}-${it.status}" }) { record ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "${clock.format(Date(record.atMillis))} · ${record.provider}",
                        style = SavantMono.tag,
                        color = colors.textFaint,
                    )
                    Text(
                        text = record.summary,
                        style = SavantMono.path,
                        color = if (record.ok) colors.text else colors.negative,
                    )
                    record.detail?.let { detail ->
                        Text(text = detail, style = SavantMono.path, color = colors.textMuted)
                    }
                }
            }

            if (state.records.isNotEmpty()) {
                item(key = "clear") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Hairline()
                        GhostButton(text = "clear the list", onClick = viewModel::onClear)
                    }
                }
            }
        }
    }
}
