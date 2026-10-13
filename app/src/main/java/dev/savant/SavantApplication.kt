package dev.savant

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.savant.data.plugins.PluginManager
import javax.inject.Inject

/**
 * Hilt entry point.
 *
 * Almost nothing is warmed up at startup: no database to open, no provider to ping, no file to read.
 * The first save a user attaches is the first real work savant does.
 *
 * The one exception is the plugin registry. A file can be attached from any screen — or shared in from
 * another app before any screen exists — so the parsers described by installed plugins have to be
 * registered before the first detection runs, not after some screen happens to ask for them.
 */
@HiltAndroidApp
class SavantApplication : Application() {

    @Inject
    lateinit var plugins: PluginManager

    override fun onCreate() {
        super.onCreate()
        plugins.start()
    }
}
