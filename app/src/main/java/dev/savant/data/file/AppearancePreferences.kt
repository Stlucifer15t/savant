package dev.savant.data.file

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.savant.ui.theme.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppearancePreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("savant_ui", Context.MODE_PRIVATE)

    fun themeMode(): ThemeMode = runCatching {
        ThemeMode.valueOf(preferences.getString(KEY_THEME, ThemeMode.SYSTEM.name).orEmpty())
    }.getOrDefault(ThemeMode.SYSTEM)

    fun reducedMotion(): Boolean = preferences.getBoolean(KEY_REDUCED_MOTION, false)

    fun setThemeMode(mode: ThemeMode) {
        preferences.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun setReducedMotion(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_REDUCED_MOTION, enabled).apply()
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_REDUCED_MOTION = "reduced_motion"
    }
}
