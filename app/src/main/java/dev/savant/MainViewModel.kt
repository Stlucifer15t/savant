package dev.savant

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.savant.data.session.SessionStore
import dev.savant.data.settings.SavantSettings
import dev.savant.data.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * App-wide state: the theme and the shared-in file.
 *
 * Both have to be known before any screen is composed, which is why they live here rather than in
 * a screen view model.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    sessionStore: SessionStore,
) : ViewModel() {

    val settings: StateFlow<SavantSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, SavantSettings())

    val pendingShare: StateFlow<Uri?> = sessionStore.incomingShare
}
