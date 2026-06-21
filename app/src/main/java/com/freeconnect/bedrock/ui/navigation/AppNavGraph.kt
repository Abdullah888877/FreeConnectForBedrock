package com.freeconnect.bedrock.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.freeconnect.bedrock.ui.addserver.AddServerScreen
import com.freeconnect.bedrock.ui.home.HomeScreen
import com.freeconnect.bedrock.ui.resourcepack.ResourcePackScreen
import com.freeconnect.bedrock.ui.settings.SettingsScreen

/** Sealed destination routes for type-safe navigation. */
sealed class Screen(val route: String) {
    data object Home         : Screen("home")
    data object AddServer    : Screen("add_server")
    data object EditServer   : Screen("edit_server/{serverId}") {
        fun buildRoute(serverId: Long) = "edit_server/$serverId"
    }
    data object Settings     : Screen("settings")
    data object ResourcePack : Screen("resource_pack/{serverId}") {
        fun buildRoute(serverId: Long) = "resource_pack/$serverId"
    }
}

/**
 * Root navigation graph.
 * All screens are registered here; individual screens push/pop the back stack.
 */
@Composable
fun AppNavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onAddServer    = { navController.navigate(Screen.AddServer.route) },
                onEditServer   = { id -> navController.navigate(Screen.EditServer.buildRoute(id)) },
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                onResourcePack = { id -> navController.navigate(Screen.ResourcePack.buildRoute(id)) }
            )
        }

        composable(Screen.AddServer.route) {
            AddServerScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.EditServer.route,
            arguments = listOf(navArgument("serverId") { type = NavType.LongType })
        ) { backStack ->
            val serverId = backStack.arguments?.getLong("serverId") ?: return@composable
            AddServerScreen(
                serverId       = serverId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.ResourcePack.route,
            arguments = listOf(navArgument("serverId") { type = NavType.LongType })
        ) { backStack ->
            val serverId = backStack.arguments?.getLong("serverId") ?: return@composable
            ResourcePackScreen(
                serverId       = serverId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
