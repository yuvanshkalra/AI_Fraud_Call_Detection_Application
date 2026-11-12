package com.fraudcalldetector.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fraudcalldetector.domain.model.CallDirection
import com.fraudcalldetector.domain.model.CallEvent
import com.fraudcalldetector.domain.model.MonitoringStatus
import com.fraudcalldetector.domain.model.RiskLevel
import com.fraudcalldetector.ui.components.*
import com.fraudcalldetector.ui.monitoring.MonitoringStatusBar
import com.fraudcalldetector.ui.monitoring.MonitoringViewModel
import com.fraudcalldetector.ui.preview.PreviewData
import com.fraudcalldetector.ui.theme.FraudCallDetectorTheme
import com.fraudcalldetector.core.utils.DateUtils
import com.fraudcalldetector.core.utils.PhoneUtils

// ─────────────────────────────────────────────────────────────────────────────
// Screen entry point
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onViewHistory     : () -> Unit,
    onOpenSettings    : () -> Unit,
    onViewAlerts      : () -> Unit          = {},
    onCallSelected    : (CallEvent) -> Unit = {},
    onActiveCallClick : (String) -> Unit    = {},
    viewModel         : HomeViewModel       = hiltViewModel(),
    monitoringViewModel: MonitoringViewModel = hiltViewModel(),
    alertViewModel    : com.fraudcalldetector.ui.alerts.AlertHistoryViewModel = hiltViewModel()
) {
    val state          by viewModel.uiState.collectAsStateWithLifecycle()
    val monitorStatus  by monitoringViewModel.status.collectAsStateWithLifecycle()
    val isCallActive   by monitoringViewModel.isCallActive.collectAsStateWithLifecycle()
    val activeCallId   by monitoringViewModel.activeCallId.collectAsStateWithLifecycle()
    val unreadAlerts   by alertViewModel.unreadCount.collectAsStateWithLifecycle()

    HomeContent(
        state             = state,
        monitoringStatus  = monitorStatus,
        unreadAlertCount  = unreadAlerts,
        onViewHistory     = onViewHistory,
        onOpenSettings    = onOpenSettings,
        onViewAlerts      = onViewAlerts,
        onCallSelected    = onCallSelected,
        // Pass the real active callId so navigation lands on the correct call screen.
        // Falls back to empty string (screen handles blank callId gracefully).
        onActiveCallClick = { onActiveCallClick(activeCallId ?: "") }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure-UI content (decoupled for previews)
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state            : HomeState,
    monitoringStatus : MonitoringStatus = MonitoringStatus.ACTIVE,
    unreadAlertCount : Int              = 0,
    onViewHistory    : () -> Unit,
    onOpenSettings   : () -> Unit,
    onViewAlerts     : () -> Unit       = {},
    onCallSelected   : (CallEvent) -> Unit,
    onActiveCallClick: () -> Unit = {}
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary)
                        Text("FraudCall Detector", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                },
                actions = {
                    // Alerts bell — shows unread badge when there are active fraud alerts
                    BadgedBox(
                        badge = {
                            if (unreadAlertCount > 0) {
                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                    Text(unreadAlertCount.toString(), fontSize = 9.sp)
                                }
                            }
                        }
                    ) {
                        IconButton(onClick = onViewAlerts) {
                            Icon(
                                imageVector = if (unreadAlertCount > 0) Icons.Filled.NotificationsActive
                                              else Icons.Outlined.NotificationsNone,
                                contentDescription = "Fraud Alerts",
                                tint = if (unreadAlertCount > 0) MaterialTheme.colorScheme.error
                                       else LocalContentColor.current
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier            = Modifier.fillMaxSize().padding(padding),
            contentPadding      = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {

            // ── Live monitoring status bar ─────────────────────────────────
            item {
                MonitoringStatusBar(status = monitoringStatus)
            }

            // ── Protection banner ─────────────────────────────────────────
            item {
                val onBannerClick: () -> Unit = onActiveCallClick
                ProtectionBanner(
                    isMonitoring    = monitoringStatus != MonitoringStatus.IDLE &&
                                      monitoringStatus != MonitoringStatus.PERMISSION_DENIED,
                    safeRatePercent = state.safeRatePercent,
                    onActiveCallClick = onBannerClick
                )
            }

            // ── Stats row ─────────────────────────────────────────────────
            item {
                StatsSection(state = state)
            }

            // ── Threat summary card ───────────────────────────────────────
            if (state.fraudCalls > 0) {
                item {
                    ThreatSummaryCard(
                        fraudCount  = state.fraudCalls,
                        blockedWeek = state.blockedThisWeek,
                        modifier    = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // ── Recent calls header ───────────────────────────────────────
            item {
                SectionHeader(
                    title    = "Recent Calls",
                    action   = "View All",
                    onAction = onViewHistory,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            // ── Recent call items ─────────────────────────────────────────
            if (state.recentCalls.isEmpty()) {
                item {
                    EmptyState(
                        icon     = Icons.Outlined.PhoneDisabled,
                        title    = "No calls yet",
                        subtitle = "Your analysed calls will appear here",
                        modifier = Modifier.height(180.dp)
                    )
                }
            } else {
                items(state.recentCalls, key = { it.id }) { call ->
                    AnimatedVisibility(
                        visible = true,
                        enter   = fadeIn() + slideInVertically { it / 2 }
                    ) {
                        RecentCallRow(
                            call     = call,
                            onClick  = { onCallSelected(call) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Protection banner with gradient
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProtectionBanner(
    isMonitoring    : Boolean,
    safeRatePercent : Int,
    onActiveCallClick: () -> Unit
) {
    val gradientColors = if (isMonitoring) listOf(Color(0xFF0D47A1), Color(0xFF1565C0), Color(0xFF1976D2))
                         else              listOf(Color(0xFF37474F), Color(0xFF546E7A))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(gradientColors),
                shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)
            )
            .padding(horizontal = 24.dp, vertical = 28.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text       = if (isMonitoring) "Protected" else "Not Protected",
                        fontSize   = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color      = Color.White
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PulsingDot(
                            color = if (isMonitoring) Color(0xFF69F0AE) else Color(0xFFFFAB40),
                            size  = 10.dp
                        )
                        Text(
                            text  = if (isMonitoring) "Monitoring active" else "Monitoring off",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
                    }
                }

                ShieldStatusBadge(isProtected = isMonitoring, size = 72.dp)
            }

            Spacer(Modifier.height(20.dp))

            // Protection rate bar
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Protection rate", color = Color.White.copy(0.7f), fontSize = 12.sp)
                    Text("$safeRatePercent%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                val animPct by animateFloatAsState(
                    targetValue   = safeRatePercent / 100f,
                    animationSpec = tween(1000, easing = FastOutSlowInEasing),
                    label         = "pct"
                )
                LinearProgressIndicator(
                    progress    = { animPct },
                    modifier    = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color       = Color(0xFF69F0AE),
                    trackColor  = Color.White.copy(alpha = 0.2f)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stats row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatsSection(state: HomeState) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatCard(
            modifier  = Modifier.weight(1f),
            value     = state.totalCalls.toString(),
            label     = "Total Calls",
            icon      = Icons.Outlined.Call,
            iconTint  = MaterialTheme.colorScheme.primary
        )
        StatCard(
            modifier  = Modifier.weight(1f),
            value     = state.fraudCalls.toString(),
            label     = "Fraud Detected",
            icon      = Icons.Outlined.GppBad,
            iconTint  = Color(0xFFE53935)
        )
        StatCard(
            modifier  = Modifier.weight(1f),
            value     = state.safeCalls.toString(),
            label     = "Safe Calls",
            icon      = Icons.Outlined.VerifiedUser,
            iconTint  = Color(0xFF2E7D32)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Threat summary card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ThreatSummaryCard(fraudCount: Int, blockedWeek: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
    ) {
        Row(
            modifier              = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFE53935).copy(0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Warning, null, tint = Color(0xFFE53935), modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("$fraudCount fraudulent calls detected",
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color(0xFFB71C1C))
                Text("$blockedWeek flagged this week",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828).copy(0.75f))
            }
            Icon(Icons.Default.ChevronRight, null, tint = Color(0xFFE53935))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Recent call row item
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RecentCallRow(
    call    : CallEvent,
    onClick : () -> Unit,
    modifier: Modifier = Modifier
) {
    val riskColor   = call.fraudAnalysis?.riskLevel?.bgColor() ?: MaterialTheme.colorScheme.primary
    val isFraud     = call.fraudAnalysis?.isFraud == true
    val directionIcon = if (call.direction == CallDirection.INBOUND) Icons.Default.CallReceived
                        else Icons.Default.CallMade

    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape    = RoundedCornerShape(14.dp),
        color    = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Avatar circle
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(riskColor.copy(0.13f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(directionIcon, null, tint = riskColor, modifier = Modifier.size(20.dp))
                }
                if (isFraud) {
                    Box(
                        Modifier.size(13.dp).clip(CircleShape).background(Color(0xFFE53935))
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Warning, null, tint = Color.White,
                            modifier = Modifier.size(8.dp))
                    }
                }
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text       = call.callerName ?: PhoneUtils.formatPhoneNumber(call.phoneNumber),
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 14.sp,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Text(
                    text  = DateUtils.formatDateTime(call.startTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                )
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                call.fraudAnalysis?.let { RiskBadge(it.riskLevel, compact = true) }
                Text(
                    DateUtils.formatDuration(call.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.45f)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────────────────────

@Preview(name = "Home – Light", showBackground = true)
@Composable
private fun HomeScreenLightPreview() {
    FraudCallDetectorTheme(darkTheme = false) {
        HomeContent(
            state = HomeState(
                isMonitoring    = true,
                recentCalls     = PreviewData.allCalls,
                totalCalls      = 47,
                fraudCalls      = 8,
                safeCalls       = 32,
                blockedThisWeek = 3,
                safeRatePercent = 83,
                criticalAlerts  = 2
            ),
            onViewHistory  = {},
            onOpenSettings = {},
            onCallSelected = {}
        )
    }
}

@Preview(name = "Home – Dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeScreenDarkPreview() {
    FraudCallDetectorTheme(darkTheme = true) {
        HomeContent(
            state = HomeState(
                isMonitoring    = true,
                recentCalls     = PreviewData.allCalls,
                totalCalls      = 47,
                fraudCalls      = 8,
                safeCalls       = 32,
                blockedThisWeek = 3,
                safeRatePercent = 83,
                criticalAlerts  = 2
            ),
            onViewHistory  = {},
            onOpenSettings = {},
            onCallSelected = {}
        )
    }
}
