package com.fraudcalldetector.ui.alerts

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fraudcalldetector.domain.model.*
import com.fraudcalldetector.ui.components.*
import com.fraudcalldetector.core.utils.DateUtils
import com.fraudcalldetector.core.utils.PhoneUtils

// ─────────────────────────────────────────────────────────────────────────────
// Entry point
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AlertHistoryScreen(
    onBack        : () -> Unit,
    onAlertTapped : (String) -> Unit,          // callId → navigate to FraudWarningScreen
    viewModel     : AlertHistoryViewModel = hiltViewModel()
) {
    val alerts      by viewModel.alerts.collectAsStateWithLifecycle()
    val unread      by viewModel.unreadCount.collectAsStateWithLifecycle()
    val alertConfig by viewModel.alertConfig.collectAsStateWithLifecycle()

    AlertHistoryContent(
        alerts       = alerts,
        unreadCount  = unread,
        alertConfig  = alertConfig,
        onBack       = onBack,
        onAlertTapped = { record ->
            viewModel.markViewed(record.callId)
            onAlertTapped(record.callId)
        },
        onDismiss    = { viewModel.dismissAlert(it.callId) },
        onRemove     = { viewModel.removeAlert(it.callId) },
        onClearAll   = { viewModel.clearAll() },
        onSaveConfig = { viewModel.saveAlertConfig(it) }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure-UI
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertHistoryContent(
    alerts       : List<AlertRecord>,
    unreadCount  : Int,
    alertConfig  : FraudAlertConfig,
    onBack       : () -> Unit,
    onAlertTapped: (AlertRecord) -> Unit,
    onDismiss    : (AlertRecord) -> Unit,
    onRemove     : (AlertRecord) -> Unit,
    onClearAll   : () -> Unit,
    onSaveConfig : (FraudAlertConfig) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Fraud Alerts", fontWeight = FontWeight.Bold)
                        if (unreadCount > 0) {
                            Badge(containerColor = MaterialTheme.colorScheme.error) {
                                Text(unreadCount.toString(), fontSize = 11.sp)
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (alerts.isNotEmpty()) {
                        IconButton(onClick = { showSettings = !showSettings }) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = "Alert settings",
                                tint = if (showSettings) MaterialTheme.colorScheme.primary
                                       else LocalContentColor.current
                            )
                        }
                        IconButton(onClick = onClearAll) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear all")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            // ── Threshold settings panel ────────────────────────────────────
            item {
                AnimatedVisibility(
                    visible = showSettings,
                    enter   = expandVertically() + fadeIn(),
                    exit    = shrinkVertically() + fadeOut()
                ) {
                    AlertThresholdPanel(
                        config    = alertConfig,
                        onSave    = onSaveConfig,
                        modifier  = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            // ── Empty state ─────────────────────────────────────────────────
            if (alerts.isEmpty()) {
                item {
                    EmptyAlertsState()
                }
            }

            // ── Alert rows ──────────────────────────────────────────────────
            items(alerts, key = { it.callId }) { record ->
                AlertRecordRow(
                    record    = record,
                    onTap     = { onAlertTapped(record) },
                    onDismiss = { onDismiss(record) },
                    onRemove  = { onRemove(record) }
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Individual alert row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AlertRecordRow(
    record   : AlertRecord,
    onTap    : () -> Unit,
    onDismiss: () -> Unit,
    onRemove : () -> Unit
) {
    val riskColor = record.riskLevel.bgColor()
    val isUnread  = record.status == AlertStatus.ACTIVE
    var expanded  by remember { mutableStateOf(false) }

    Card(
        onClick   = { expanded = !expanded; onTap() },
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier  = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // ── Header row ──────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Risk icon with optional unread dot
                Box(contentAlignment = Alignment.TopEnd) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(riskColor.copy(0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (record.isFraud) Icons.Default.GppBad
                                          else Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint     = riskColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (isUnread) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                        )
                    }
                }

                // Caller info
                Column(Modifier.weight(1f)) {
                    Text(
                        text = record.callerName
                            ?: PhoneUtils.formatPhoneNumber(record.phoneNumber),
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 14.sp,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    if (record.callerName != null) {
                        Text(
                            text     = PhoneUtils.formatPhoneNumber(record.phoneNumber),
                            style    = MaterialTheme.typography.bodySmall,
                            color    = MaterialTheme.colorScheme.onSurface.copy(0.55f),
                            maxLines = 1
                        )
                    }
                }

                // Risk badge + timestamp
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RiskBadge(record.riskLevel, compact = true)
                    Text(
                        text  = DateUtils.formatRelativeTime(record.firedAtMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.45f)
                    )
                }
            }

            // ── Reason line ────────────────────────────────────────────────
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint     = riskColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text     = record.primaryReason,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurface.copy(0.70f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── Confidence bar ─────────────────────────────────────────────
            ConfidenceBar(confidence = record.confidence, riskColor = riskColor)

            // ── Expanded: all flags ────────────────────────────────────────
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(0.2f))
                    record.analysis.flags.forEach { flag ->
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val sev = flag.severity
                            val sc  = when {
                                sev > 0.8f -> Color(0xFFEF5350)
                                sev > 0.5f -> Color(0xFFFF9800)
                                else       -> Color(0xFFFFC107)
                            }
                            Box(
                                Modifier.size(22.dp).clip(CircleShape).background(sc.copy(0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Warning, null, tint = sc, modifier = Modifier.size(12.dp))
                            }
                            Text(
                                flag.description,
                                style    = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${(sev * 100).toInt()}%",
                                style    = MaterialTheme.typography.labelSmall,
                                color    = sc,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // ── Action buttons (dismiss / remove) ──────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                if (record.status == AlertStatus.ACTIVE) {
                    TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Default.NotificationsOff, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Dismiss", style = MaterialTheme.typography.labelMedium)
                    }
                }
                TextButton(onClick = onRemove, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Remove", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Confidence bar
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConfidenceBar(confidence: Float, riskColor: Color) {
    val animConf by animateFloatAsState(confidence, tween(800), label = "conf_bar")
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Confidence",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
            )
            Text(
                "${(confidence * 100).toInt()}%",
                style      = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color      = riskColor
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape)
                .background(riskColor.copy(alpha = 0.15f))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animConf)
                    .clip(CircleShape)
                    .background(riskColor)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Alert threshold settings panel
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertThresholdPanel(
    config   : FraudAlertConfig,
    onSave   : (FraudAlertConfig) -> Unit,
    modifier : Modifier = Modifier
) {
    var threshold  by remember(config) { mutableStateOf(config.alertThreshold) }
    var minLevel   by remember(config) { mutableStateOf(config.minRiskLevelForAlert) }
    var finalOnly  by remember(config) { mutableStateOf(config.alertOnFinalPassOnly) }

    val isDirty = threshold != config.alertThreshold
               || minLevel  != config.minRiskLevelForAlert
               || finalOnly != config.alertOnFinalPassOnly

    Card(
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(
                    "Alert Threshold Settings",
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            // ── Confidence threshold slider ─────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Confidence threshold",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "${(threshold * 100).toInt()}%",
                        style      = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value         = threshold,
                    onValueChange = { threshold = it },
                    valueRange    = 0.20f..0.99f,
                    steps         = 15,
                    modifier      = Modifier.fillMaxWidth()
                )
                Text(
                    "Alerts fire when the AI model is ≥ ${(threshold * 100).toInt()}% confident",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.55f)
                )
            }

            // ── Minimum risk level picker ───────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Minimum risk level",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(RiskLevel.LOW, RiskLevel.MEDIUM, RiskLevel.HIGH, RiskLevel.CRITICAL).forEach { level ->
                        val selected = level == minLevel
                        val bg = if (selected) level.bgColor() else level.bgColor().copy(0.12f)
                        val fg = if (selected) Color.White     else level.bgColor()
                        Surface(
                            onClick = { minLevel = level },
                            shape   = RoundedCornerShape(8.dp),
                            color   = bg,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = level.name,
                                color = fg,
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }

            // ── Final-pass-only toggle ──────────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Alerts on final analysis only",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Skip mid-call chunk alerts; notify only after call ends",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.55f)
                    )
                }
                Switch(checked = finalOnly, onCheckedChange = { finalOnly = it })
            }

            // ── Save button ─────────────────────────────────────────────────
            AnimatedVisibility(
                visible = isDirty,
                enter   = expandVertically() + fadeIn(),
                exit    = shrinkVertically() + fadeOut()
            ) {
                Button(
                    onClick = {
                        onSave(
                            FraudAlertConfig(
                                alertThreshold       = threshold,
                                minRiskLevelForAlert = minLevel,
                                alertOnFinalPassOnly = finalOnly
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save Settings")
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyAlertsState() {
    Box(
        Modifier.fillMaxWidth().height(280.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Outlined.NotificationsNone,
                contentDescription = null,
                tint     = MaterialTheme.colorScheme.outline.copy(0.4f),
                modifier = Modifier.size(64.dp)
            )
            Text(
                "No fraud alerts yet",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.onSurface.copy(0.5f)
            )
            Text(
                "Alerts will appear here when suspicious\ncalls are detected",
                style     = MaterialTheme.typography.bodySmall,
                color     = MaterialTheme.colorScheme.onSurface.copy(0.35f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
