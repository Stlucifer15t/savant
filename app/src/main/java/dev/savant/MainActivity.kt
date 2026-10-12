package dev.savant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.savant.data.session.SessionStore
import dev.savant.ui.SavantApp
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * One activity, five destinations.
 *
 * Edge to edge, with Compose owning the insets. The activity also handles "share to savant": the
 * uri goes to [SessionStore] so it survives a cold start, a warm restart and a share that lands
 * while another tab is on screen.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var sessions: SessionStore

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        handleIncoming(intent)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val pendingShare by viewModel.pendingShare.collectAsStateWithLifecycle()

            SavantTheme(
                themeMode = settings.theme,
                reducedMotion = settings.reducedMotion,
            ) {
                SystemBarIcons()
                SavantApp(pendingShareUri = pendingShare)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.getItemAt(0)?.uri
            ?: return
        // Best effort: a share from a file manager usually grants read permission for the session.
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        sessions.submitShare(uri)
    }
}

/** Status and navigation bar icons follow the theme; the bars themselves stay transparent. */
@Composable
private fun SystemBarIcons() {
    val view = LocalView.current
    val dark = Savant.colors.isDark
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? ComponentActivity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}
