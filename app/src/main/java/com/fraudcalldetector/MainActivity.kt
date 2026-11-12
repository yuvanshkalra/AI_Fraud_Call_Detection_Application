package com.fraudcalldetector

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.fraudcalldetector.feature.notifications.FraudNotificationManager
import com.fraudcalldetector.navigation.FraudCallNavGraph
import com.fraudcalldetector.navigation.Screen
import com.fraudcalldetector.ui.monitoring.MonitoringServiceController
import com.fraudcalldetector.ui.theme.FraudCallDetectorTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { viewModel.isLoading.value }

        setContent {
            val isDarkTheme           by viewModel.isDarkTheme.collectAsStateWithLifecycle()
            val allPermissionsGranted by viewModel.allPermissionsGranted.collectAsStateWithLifecycle()

            // Deep-link state:
            //  - On cold launch: parsed directly from the launch intent (LaunchedEffect below)
            //  - On warm resume (onNewIntent): stored in ViewModel and observed here
            val vmDeepLinkCallId by viewModel.deepLinkCallId.collectAsStateWithLifecycle()
            val vmDeepLinkRoute  by viewModel.deepLinkRoute.collectAsStateWithLifecycle()

            var deepLinkCallId  by remember { mutableStateOf<String?>(null) }
            var deepLinkRoute   by remember { mutableStateOf<String?>(null) }

            // Parse cold-launch intent once
            LaunchedEffect(Unit) {
                intent?.let { parseDeepLink(it) { id, route ->
                    deepLinkCallId = id
                    deepLinkRoute  = route
                }}
            }

            // Apply warm-resume deep-link from ViewModel, then clear it
            LaunchedEffect(vmDeepLinkCallId, vmDeepLinkRoute) {
                if (vmDeepLinkCallId != null) {
                    deepLinkCallId = vmDeepLinkCallId
                    deepLinkRoute  = vmDeepLinkRoute
                    viewModel.clearDeepLink()
                }
            }

            // Start the foreground monitoring service as soon as permissions are confirmed.
            LaunchedEffect(allPermissionsGranted) {
                if (allPermissionsGranted) {
                    MonitoringServiceController.start(this@MainActivity)
                }
            }

            FraudCallDetectorTheme(darkTheme = isDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = MaterialTheme.colorScheme.background
                ) {
                    val navController    = rememberNavController()
                    val startDestination = if (allPermissionsGranted) Screen.Home.route
                                          else                         Screen.Permissions.route

                    FraudCallNavGraph(
                        navController    = navController,
                        startDestination = startDestination,
                        deepLinkCallId   = deepLinkCallId,
                        deepLinkRoute    = deepLinkRoute
                    )
                }
            }
        }
    }

    /**
     * Called when the Activity is already running and receives a new intent
     * (e.g. user taps a notification while the app is in the foreground).
     * We re-parse the deep-link and let the Compose tree react.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // The LaunchedEffect(intent) inside setContent doesn't re-run on onNewIntent,
        // so we store the parsed values in the ViewModel's mutable state instead.
        parseDeepLink(intent) { callId, route ->
            viewModel.setDeepLink(callId, route)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkPermissions()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseDeepLink(intent: Intent, onResult: (callId: String, route: String) -> Unit) {
        val callId = intent.getStringExtra(FraudNotificationManager.EXTRA_CALL_ID)
            ?.takeIf { it.isNotBlank() } ?: return
        val route  = intent.getStringExtra(FraudNotificationManager.EXTRA_NAVIGATE)
            ?.takeIf { it.isNotBlank() } ?: return
        onResult(callId, route)
    }
}
