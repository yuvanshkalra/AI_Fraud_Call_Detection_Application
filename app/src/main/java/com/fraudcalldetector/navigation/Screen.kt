package com.fraudcalldetector.navigation

/**
 * Type-safe navigation routes.
 */
sealed class Screen(val route: String) {
    object Permissions   : Screen("permissions")
    object Home          : Screen("home")
    object CallHistory   : Screen("call_history")
    object AlertHistory  : Screen("alert_history")
    object CallDetail    : Screen("call_detail/{callId}") {
        fun createRoute(callId: String) = "call_detail/$callId"
    }
    object ActiveCall    : Screen("active_call/{callId}") {
        fun createRoute(callId: String) = "active_call/$callId"
    }
    object FraudWarning  : Screen("fraud_warning/{callId}") {
        fun createRoute(callId: String) = "fraud_warning/$callId"
    }
    object Settings      : Screen("settings")
}
