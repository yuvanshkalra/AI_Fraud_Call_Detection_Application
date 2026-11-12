package com.fraudcalldetector.navigation

import androidx.compose.runtime.*
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.fraudcalldetector.domain.model.CallEvent
import com.fraudcalldetector.feature.callhistory.ui.CallDetailScreen
import com.fraudcalldetector.feature.callhistory.ui.CallHistoryScreen
import com.fraudcalldetector.ui.activecall.ActiveCallScreen
import com.fraudcalldetector.ui.alerts.AlertHistoryScreen
import com.fraudcalldetector.ui.fraudwarning.FraudWarningScreen
import com.fraudcalldetector.ui.home.HomeScreen
import com.fraudcalldetector.ui.permissions.PermissionsScreen
import com.fraudcalldetector.ui.settings.SettingsScreen

@Composable
fun FraudCallNavGraph(
    navController    : NavHostController,
    startDestination : String,
    /**
     * When the app is launched from a notification deep-link, the callId is
     * passed here so the graph can navigate directly to FraudWarningScreen.
     * Null = normal launch (no deep-link).
     */
    deepLinkCallId   : String?  = null,
    deepLinkRoute    : String?  = null
) {
    // Shared call state across routes (passed via SavedStateHandle pattern)
    val selectedCall = remember { mutableStateOf<CallEvent?>(null) }

    // Handle notification deep-link: navigate to fraud_warning when a callId arrives
    LaunchedEffect(deepLinkCallId, deepLinkRoute) {
        if (deepLinkCallId != null && deepLinkRoute == "fraud_warning") {
            navController.navigate(Screen.FraudWarning.createRoute(deepLinkCallId)) {
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController    = navController,
        startDestination = startDestination
    ) {

        // ── Permissions onboarding ────────────────────────────────────────
        composable(Screen.Permissions.route) {
            PermissionsScreen(
                onAllGranted = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Permissions.route) { inclusive = true }
                    }
                }
            )
        }

        // ── Home dashboard ────────────────────────────────────────────────
        composable(Screen.Home.route) {
            HomeScreen(
                onViewHistory     = { navController.navigate(Screen.CallHistory.route) },
                onOpenSettings    = { navController.navigate(Screen.Settings.route) },
                onViewAlerts      = { navController.navigate(Screen.AlertHistory.route) },
                onCallSelected    = { call ->
                    selectedCall.value = call
                    navController.navigate(Screen.CallDetail.createRoute(call.id))
                },
                onActiveCallClick = { callId ->
                    navController.navigate(Screen.ActiveCall.createRoute(callId))
                }
            )
        }

        // ── Call history ──────────────────────────────────────────────────
        composable(Screen.CallHistory.route) {
            CallHistoryScreen(
                onCallSelected = { call ->
                    selectedCall.value = call
                    navController.navigate(Screen.CallDetail.createRoute(call.id))
                },
                onBack = { navController.popBackStack() }
            )
        }

        // ── Alert history ─────────────────────────────────────────────────
        composable(Screen.AlertHistory.route) {
            AlertHistoryScreen(
                onBack        = { navController.popBackStack() },
                onAlertTapped = { callId ->
                    // Navigate to FraudWarning for this call (the call may be
                    // in selectedCall if we came from history, or via deep-link)
                    navController.navigate(Screen.FraudWarning.createRoute(callId))
                }
            )
        }

        // ── Call detail ───────────────────────────────────────────────────
        composable(
            route = Screen.CallDetail.route,
            arguments = listOf(navArgument("callId") { type = NavType.StringType })
        ) { backStackEntry ->
            val callId = backStackEntry.arguments?.getString("callId") ?: return@composable
            val call   = selectedCall.value?.takeIf { it.id == callId }
                ?: return@composable

            CallDetailScreen(
                call   = call,
                onBack = { navController.popBackStack() },
                onFraudWarning = {
                    navController.navigate(Screen.FraudWarning.createRoute(call.id))
                }
            )
        }

        // ── Active call monitoring ─────────────────────────────────────────
        composable(
            route = Screen.ActiveCall.route,
            arguments = listOf(navArgument("callId") { type = NavType.StringType })
        ) {
            ActiveCallScreen(
                onBack          = { navController.popBackStack() },
                onFraudDetected = { callId ->
                    navController.navigate(Screen.FraudWarning.createRoute(callId))
                }
            )
        }

        // ── Fraud warning ─────────────────────────────────────────────────
        // preloadedCall is used when the call is already in memory (in-app nav).
        // When null (notification deep-link), FraudWarningScreen loads the call
        // from the repository via FraudWarningViewModel.
        composable(
            route = Screen.FraudWarning.route,
            arguments = listOf(navArgument("callId") { type = NavType.StringType })
        ) { backStackEntry ->
            val callId       = backStackEntry.arguments?.getString("callId") ?: return@composable
            val preloadedCall = selectedCall.value?.takeIf { it.id == callId }

            FraudWarningScreen(
                preloadedCall = preloadedCall,
                onBack        = { navController.popBackStack() },
                onViewDetail  = {
                    // Only navigate to detail if we have a call in memory
                    preloadedCall?.let {
                        navController.navigate(Screen.CallDetail.createRoute(it.id))
                    }
                }
            )
        }

        // ── Settings ──────────────────────────────────────────────────────
        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
