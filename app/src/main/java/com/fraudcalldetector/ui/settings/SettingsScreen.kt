package com.fraudcalldetector.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fraudcalldetector.ui.theme.FraudCallDetectorTheme

// ─────────────────────────────────────────────────────────────────────────────
// State
// ─────────────────────────────────────────────────────────────────────────────

data class SettingsState(
    val recordCalls       : Boolean = true,
    val autoAnalyse       : Boolean = true,
    val fraudAlertsEnabled: Boolean = true,
    val blockOnCritical   : Boolean = false,
    val darkTheme         : Boolean = false,
    val dynamicColour     : Boolean = true,
    val notifyCriticalOnly: Boolean = false,
    val saveTranscripts   : Boolean = true,
    val apiEndpoint       : String  = "https://api.your-ai-model.com/",
    val sttEndpoint       : String  = "https://api.your-stt-backend.com/"
)

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var state by remember { mutableStateOf(SettingsState()) }
    SettingsContent(state = state, onBack = onBack, onChange = { state = it })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state   : SettingsState,
    onBack  : () -> Unit,
    onChange: (SettingsState) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {

            // ── Profile card ──────────────────────────────────────────────
            item {
                ProfileCard()
                Spacer(Modifier.height(8.dp))
            }

            // ── Call Monitoring ───────────────────────────────────────────
            item { SettingsSectionLabel("Call Monitoring") }
            item {
                SettingsGroup {
                    SettingsToggleRow(
                        icon     = Icons.Default.FiberManualRecord,
                        iconTint = Color(0xFFEF5350),
                        title    = "Record Calls",
                        subtitle = "Capture audio for fraud analysis",
                        checked  = state.recordCalls,
                        onChange = { onChange(state.copy(recordCalls = it)) }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        icon     = Icons.Default.AutoAwesome,
                        iconTint = Color(0xFF7C4DFF),
                        title    = "Auto Analyse",
                        subtitle = "Send recordings to AI after each call",
                        checked  = state.autoAnalyse,
                        onChange = { onChange(state.copy(autoAnalyse = it)) }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        icon     = Icons.Default.SaveAlt,
                        iconTint = Color(0xFF00ACC1),
                        title    = "Save Transcripts",
                        subtitle = "Store call transcripts locally",
                        checked  = state.saveTranscripts,
                        onChange = { onChange(state.copy(saveTranscripts = it)) }
                    )
                }
            }

            // ── Alerts ────────────────────────────────────────────────────
            item { SettingsSectionLabel("Alerts & Blocking") }
            item {
                SettingsGroup {
                    SettingsToggleRow(
                        icon     = Icons.Default.NotificationsActive,
                        iconTint = Color(0xFFFFA000),
                        title    = "Fraud Alerts",
                        subtitle = "Notify when fraud is detected",
                        checked  = state.fraudAlertsEnabled,
                        onChange = { onChange(state.copy(fraudAlertsEnabled = it)) }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        icon     = Icons.Default.Block,
                        iconTint = Color(0xFFE53935),
                        title    = "Auto-block on CRITICAL",
                        subtitle = "Automatically block CRITICAL-risk numbers",
                        checked  = state.blockOnCritical,
                        onChange = { onChange(state.copy(blockOnCritical = it)) }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        icon     = Icons.Outlined.NotificationImportant,
                        iconTint = Color(0xFFFF7043),
                        title    = "Critical Alerts Only",
                        subtitle = "Only notify for CRITICAL risk level",
                        checked  = state.notifyCriticalOnly,
                        onChange = { onChange(state.copy(notifyCriticalOnly = it)) }
                    )
                }
            }

            // ── Appearance ────────────────────────────────────────────────
            item { SettingsSectionLabel("Appearance") }
            item {
                SettingsGroup {
                    SettingsToggleRow(
                        icon     = Icons.Default.DarkMode,
                        iconTint = Color(0xFF455A64),
                        title    = "Dark Theme",
                        subtitle = "Use dark colour scheme",
                        checked  = state.darkTheme,
                        onChange = { onChange(state.copy(darkTheme = it)) }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        icon     = Icons.Default.Palette,
                        iconTint = Color(0xFF43A047),
                        title    = "Dynamic Colour",
                        subtitle = "Use Material You wallpaper colours (Android 12+)",
                        checked  = state.dynamicColour,
                        onChange = { onChange(state.copy(dynamicColour = it)) }
                    )
                }
            }

            // ── Backend Integration ───────────────────────────────────────
            item { SettingsSectionLabel("Backend Integration") }
            item {
                SettingsGroup {
                    SettingsInfoRow(
                        icon     = Icons.Default.Psychology,
                        iconTint = Color(0xFF3949AB),
                        title    = "AI Model Endpoint",
                        value    = state.apiEndpoint
                    )
                    SettingsDivider()
                    SettingsInfoRow(
                        icon     = Icons.Default.RecordVoiceOver,
                        iconTint = Color(0xFF00897B),
                        title    = "STT Endpoint",
                        value    = state.sttEndpoint
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon     = Icons.Default.Key,
                        iconTint = Color(0xFFFF8F00),
                        title    = "API Keys",
                        subtitle = "Manage authentication tokens",
                        onClick  = {}
                    )
                }
            }

            // ── About ─────────────────────────────────────────────────────
            item { SettingsSectionLabel("About") }
            item {
                SettingsGroup {
                    SettingsInfoRow(
                        icon     = Icons.Default.Info,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title    = "Version",
                        value    = "1.0.0 (Build 1)"
                    )
                    SettingsDivider()
                    SettingsInfoRow(
                        icon     = Icons.Default.Architecture,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        title    = "Architecture",
                        value    = "Clean Arch · MVVM · Compose"
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon     = Icons.Default.PrivacyTip,
                        iconTint = Color(0xFF0277BD),
                        title    = "Privacy Policy",
                        onClick  = {}
                    )
                    SettingsDivider()
                    SettingsNavRow(
                        icon     = Icons.Default.DeleteForever,
                        iconTint = MaterialTheme.colorScheme.error,
                        title    = "Clear All Data",
                        subtitle = "Delete all call history and recordings",
                        onClick  = {}
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Profile card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProfileCard() {
    Card(
        shape  = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier.size(56.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Security, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Column {
                Text("FraudCall Detector",
                    fontWeight = FontWeight.Bold,
                    fontSize   = 16.sp,
                    color      = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Protection Active  •  v1.0.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.65f))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Building blocks
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsSectionLabel(title: String) {
    Text(
        text     = title.uppercase(),
        style    = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 60.dp, end = 16.dp),
        color    = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    )
}

@Composable
private fun SettingsToggleRow(
    icon    : ImageVector,
    iconTint: Color,
    title   : String,
    subtitle: String,
    checked : Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(iconTint.copy(0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(0.55f))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsInfoRow(
    icon    : ImageVector,
    iconTint: Color,
    title   : String,
    value   : String
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(iconTint.copy(0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(value, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(0.55f),
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SettingsNavRow(
    icon    : ImageVector,
    iconTint: Color,
    title   : String,
    subtitle: String? = null,
    onClick : () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(iconTint.copy(0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.55f))
            }
        }
        Icon(Icons.Default.ChevronRight, null,
            tint     = MaterialTheme.colorScheme.onSurface.copy(0.4f),
            modifier = Modifier.size(18.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────────────────────

@Preview(name = "Settings – Light", showBackground = true)
@Composable
private fun SettingsLightPreview() {
    FraudCallDetectorTheme(darkTheme = false) {
        SettingsContent(state = SettingsState(), onBack = {}, onChange = {})
    }
}

@Preview(name = "Settings – Dark", showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsDarkPreview() {
    FraudCallDetectorTheme(darkTheme = true) {
        SettingsContent(state = SettingsState(), onBack = {}, onChange = {})
    }
}
