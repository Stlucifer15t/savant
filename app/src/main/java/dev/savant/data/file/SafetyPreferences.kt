package dev.savant.data.file

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafetyPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("savant_ui", Context.MODE_PRIVATE)

    fun shouldShowWarning(): Boolean = !preferences.getBoolean(KEY_WARNING_ACCEPTED, false)

    fun acknowledgeWarning() {
        preferences.edit().putBoolean(KEY_WARNING_ACCEPTED, true).apply()
    }

    fun requestWarningReplay() {
        preferences.edit().putBoolean(KEY_WARNING_ACCEPTED, false).apply()
    }

    private companion object {
        const val KEY_WARNING_ACCEPTED = "offline_warning_accepted"
    }
}
