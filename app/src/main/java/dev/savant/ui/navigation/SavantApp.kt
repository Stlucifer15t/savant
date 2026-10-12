package dev.savant.ui.navigation

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.savant.ui.chat.ChatComposer
import dev.savant.ui.chat.ChatEffect
import dev.savant.ui.chat.ChatScreen
import dev.savant.ui.chat.ChatUiState
import dev.savant.ui.chat.ChatViewModel
import dev.savant.ui.files.FilesScreen
import dev.savant.ui.formats.FormatsScreen
import dev.savant.ui.settings.SettingsScreen

@Composable
fun SavantApp(
    chatState: ChatUiState,
    appState: AppUiState,
    chatViewModel: ChatViewModel,
    appViewModel: AppViewModel,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val navController = rememberNavController()
    val snackbarHost = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = SavantDestination.entries.firstOrNull {
        it.route == backStackEntry?.destination?.route
    } ?: SavantDestination.CHAT

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            chatViewModel.importFile(it)
        }
    }
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let(chatViewModel::saveExport)
    }

    LaunchedEffect(chatViewModel, context) {
        chatViewModel.effects.collect { effect ->
            when (effect) {
                is ChatEffect.ShareFile -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = effect.mimeType
                        putExtra(Intent.EXTRA_STREAM, effect.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ContextCompat.startActivity(
                        context,
                        Intent.createChooser(send, "share edited save"),
                        null,
                    )
                }
                is ChatEffect.Notify -> snackbarHost.showSnackbar(effect.message)
                ChatEffect.HapticTick -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = { SavantTopBar(destination = destination, loaded = chatState.loaded) },
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            Column {
                if (destination == SavantDestination.CHAT) {
                    ChatComposer(
                        enabled = !chatState.isImporting,
                        onAttach = { openDocument.launch(arrayOf("*/*")) },
                        onSend = chatViewModel::submitCommand,
                    )
                }
                SavantBottomNavigation(
                    selected = destination,
                    onSelect = { target ->
                        navController.navigate(target.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { contentPadding ->
        NavHost(navController = navController, startDestination = SavantDestination.CHAT.route) {
            composable(SavantDestination.CHAT.route) {
                ChatScreen(
                    state = chatState,
                    contentPadding = contentPadding,
                    onAttach = { openDocument.launch(arrayOf("*/*")) },
                    onSearchChange = chatViewModel::setSearchQuery,
                    onCategoryFilter = chatViewModel::setCategoryFilter,
                    onToggleCategory = chatViewModel::toggleCategory,
                    onProposeEdit = chatViewModel::proposeEdit,
                    onRemovePending = chatViewModel::removePendingChange,
                    onDismissDiff = chatViewModel::dismissDiff,
                    onConfirmDiff = chatViewModel::confirmChanges,
                    onShare = chatViewModel::shareExport,
                    onSave = {
                        chatState.export?.let { createDocument.launch(it.fileName) }
                    },
                    onAcknowledgeWarning = chatViewModel::acknowledgeSafetyWarning,
                )
            }
            composable(SavantDestination.FILES.route) {
                FilesScreen(loaded = chatState.loaded, contentPadding = contentPadding)
            }
            composable(SavantDestination.FORMATS.route) {
                FormatsScreen(contentPadding = contentPadding)
            }
            composable(SavantDestination.SETTINGS.route) {
                SettingsScreen(
                    contentPadding = contentPadding,
                    themeMode = appState.themeMode,
                    reducedMotion = appState.reducedMotion,
                    onThemeMode = appViewModel::setThemeMode,
                    onReducedMotion = appViewModel::setReducedMotion,
                    onReplayWarning = {
                        chatViewModel.replaySafetyWarning()
                        navController.navigate(SavantDestination.CHAT.route) { launchSingleTop = true }
                    },
                )
            }
        }
    }
}
