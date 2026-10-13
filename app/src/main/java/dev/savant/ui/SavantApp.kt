package dev.savant.ui

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.savant.ui.chat.ChatScreen
import dev.savant.ui.components.Hairline
import dev.savant.ui.files.FilesScreen
import dev.savant.ui.finder.FinderScreen
import dev.savant.ui.formats.FormatsScreen
import dev.savant.ui.icons.SavantIcon
import dev.savant.ui.icons.SavantIcons
import dev.savant.ui.settings.NetworkScreen
import dev.savant.ui.settings.ProvidersScreen
import dev.savant.ui.settings.SettingsScreen
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantMono

/** The five destinations. Labels are lowercase; the icons are the same thin outline family. */
enum class SavantTab(
    val route: String,
    val label: String,
    @DrawableRes val icon: Int,
) {
    CHAT("chat", "chat", SavantIcons.message),
    FILES("files", "files", SavantIcons.folder),
    FORMATS("formats", "formats", SavantIcons.layers),
    FINDER("finder", "finder", SavantIcons.search),
    SETTINGS("settings", "settings", SavantIcons.settings),
}

/**
 * The two settings pages that are not tabs.
 *
 * They are pushed onto the same stack as the tabs so the back gesture works, and the bottom bar
 * keeps `settings` lit while they are open — a sub-page of settings is still settings.
 */
object SavantRoutes {
    const val PROVIDERS = "settings/providers"
    const val NETWORK = "settings/network"

    /** The tab that owns a route, so the bar stays lit on a pushed page. */
    fun tabOf(route: String?): SavantTab? = when (route) {
        PROVIDERS, NETWORK -> SavantTab.SETTINGS
        else -> SavantTab.entries.firstOrNull { it.route == route }
    }
}

/**
 * The app shell: five tabs, one bottom bar, no drawer and no hero screen.
 *
 * [pendingShareUri] is a file shared in from another app; arriving on any tab takes the user to the
 * chat, because that is where an attached file is parsed and shown.
 */
@Composable
fun SavantApp(
    navController: NavHostController = rememberNavController(),
    pendingShareUri: Uri? = null,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(pendingShareUri) {
        if (pendingShareUri != null && currentRoute != SavantTab.CHAT.route) {
            navController.goToTab(SavantTab.CHAT)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Savant.colors.background)
            .statusBarsPadding(),
    ) {
        NavHost(
            navController = navController,
            startDestination = SavantTab.CHAT.route,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            composable(SavantTab.CHAT.route) {
                ChatScreen()
            }
            composable(SavantTab.FILES.route) {
                FilesScreen(onOpenChat = { navController.goToTab(SavantTab.CHAT) })
            }
            composable(SavantTab.FORMATS.route) {
                FormatsScreen()
            }
            composable(SavantTab.FINDER.route) {
                FinderScreen(onOpenChat = { navController.goToTab(SavantTab.CHAT) })
            }
            composable(SavantTab.SETTINGS.route) {
                SettingsScreen(
                    onOpenProviders = { navController.navigate(SavantRoutes.PROVIDERS) },
                    onOpenNetwork = { navController.navigate(SavantRoutes.NETWORK) },
                )
            }
            composable(SavantRoutes.PROVIDERS) {
                ProvidersScreen(onBack = { navController.popBackStack() })
            }
            composable(SavantRoutes.NETWORK) {
                NetworkScreen(onBack = { navController.popBackStack() })
            }
        }
        BottomTabBar(
            currentTab = SavantRoutes.tabOf(currentRoute),
            onSelect = { tab -> navController.goToTab(tab) },
        )
    }
}

/** Tab switching keeps each destination's state, so the chat transcript survives a detour. */
private fun NavHostController.goToTab(tab: SavantTab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * The bottom bar.
 *
 * Flat, 1px rule on top, no pill and no elevation. The selected tab is the accent colour and
 * nothing else on the bar moves or animates.
 */
@Composable
private fun BottomTabBar(
    currentTab: SavantTab?,
    onSelect: (SavantTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Savant.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .navigationBarsPadding(),
    ) {
        Hairline()
        Row(
            modifier = Modifier.fillMaxWidth().height(58.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SavantTab.entries.forEach { tab ->
                val selected = tab == currentTab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (selected) colors.surface else colors.background)
                        .clickable(onClick = { onSelect(tab) })
                        .padding(top = 9.dp, bottom = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SavantIcon(
                        id = tab.icon,
                        size = 18.dp,
                        tint = if (selected) colors.accent else colors.textMuted,
                        contentDescription = tab.label,
                    )
                    Text(
                        text = tab.label,
                        style = SavantMono.tag,
                        color = if (selected) colors.accent else colors.textFaint,
                    )
                }
            }
        }
    }
}
