package dev.savant.ui.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.savant.data.file.AppearancePreferences
import dev.savant.ui.theme.ThemeMode
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@HiltViewModel
class AppViewModel @Inject constructor(
    private val preferences: AppearancePreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(
        AppUiState(
            themeMode = preferences.themeMode(),
            reducedMotion = preferences.reducedMotion(),
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        preferences.setThemeMode(mode)
        _state.update { it.copy(themeMode = mode) }
    }

    fun setReducedMotion(enabled: Boolean) {
        preferences.setReducedMotion(enabled)
        _state.update { it.copy(reducedMotion = enabled) }
    }
}

data class AppUiState(
    val themeMode: ThemeMode,
    val reducedMotion: Boolean,
)
