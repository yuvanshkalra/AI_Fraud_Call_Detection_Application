package com.fraudcalldetector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isDarkTheme = MutableStateFlow(false)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _allPermissionsGranted = MutableStateFlow(false)
    val allPermissionsGranted: StateFlow<Boolean> = _allPermissionsGranted.asStateFlow()

    init {
        viewModelScope.launch {
            checkPermissions()
            _isLoading.value = false
        }
    }

    fun checkPermissions() {
        val required = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        _allPermissionsGranted.value = required.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun setDarkTheme(dark: Boolean) {
        _isDarkTheme.value = dark
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Deep-link state (set from onNewIntent when the app is already running)
    // ─────────────────────────────────────────────────────────────────────────

    private val _deepLinkCallId = MutableStateFlow<String?>(null)
    val deepLinkCallId: StateFlow<String?> = _deepLinkCallId.asStateFlow()

    private val _deepLinkRoute = MutableStateFlow<String?>(null)
    val deepLinkRoute: StateFlow<String?> = _deepLinkRoute.asStateFlow()

    fun setDeepLink(callId: String, route: String) {
        _deepLinkCallId.value = callId
        _deepLinkRoute.value  = route
    }

    /** Clear after the navigation has been handled to avoid double-navigation on recompose. */
    fun clearDeepLink() {
        _deepLinkCallId.value = null
        _deepLinkRoute.value  = null
    }
}
