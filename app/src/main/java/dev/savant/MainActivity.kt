package dev.savant

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.savant.ui.chat.ChatViewModel
import dev.savant.ui.navigation.AppViewModel
import dev.savant.ui.navigation.SavantApp
import dev.savant.ui.theme.SavantTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val chatViewModel: ChatViewModel by viewModels()
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        handleSharedFile(intent)
        setContent {
            val chatState = chatViewModel.state.collectAsStateWithLifecycle().value
            val appState = appViewModel.state.collectAsStateWithLifecycle().value
            val systemReducedMotion = !ValueAnimator.areAnimatorsEnabled()
            SavantTheme(
                themeMode = appState.themeMode,
                reducedMotion = appState.reducedMotion || systemReducedMotion,
            ) {
                SavantApp(
                    chatState = chatState,
                    appState = appState,
                    chatViewModel = chatViewModel,
                    appViewModel = appViewModel,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedFile(intent)
    }

    private fun handleSharedFile(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(incoming, Intent.EXTRA_STREAM, Uri::class.java)
            ?: incoming.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (uri != null) {
            chatViewModel.importFile(uri)
            incoming.action = null
            incoming.removeExtra(Intent.EXTRA_STREAM)
        }
    }
}
