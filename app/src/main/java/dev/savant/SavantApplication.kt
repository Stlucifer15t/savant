package dev.savant

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt entry point.
 *
 * Nothing is warmed up at startup on purpose: no database to open, no provider to ping. The first
 * file a user attaches is the first real work savant does.
 */
@HiltAndroidApp
class SavantApplication : Application()
