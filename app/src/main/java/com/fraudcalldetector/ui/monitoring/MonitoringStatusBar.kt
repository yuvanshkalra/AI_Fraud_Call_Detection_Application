package com.fraudcalldetector.ui.monitoring

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fraudcalldetector.domain.model.MonitoringStatus
import com.fraudcalldetector.ui.theme.FraudCallDetectorTheme

/**
 * Compact status bar shown at the top of the Home dashboard.
 * Animates between monitoring states and renders a pulsing dot when IN_CALL.
 */
@Composable
fun MonitoringStatusBar(
    status  : MonitoringStatus,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState   = status,
        transitionSpec = {
            (slideInVertically { -it } + fadeIn()) togetherWith
            (slideOutVertically { it } + fadeOut())
        },
        label         = "monitoring_status_bar"
    ) { currentStatus ->
        MonitoringStatusContent(status = currentStatus, modifier = modifier)
    }
}

@Composable
private fun MonitoringStatusContent(status: MonitoringStatus, modifier: Modifier) {
    val (bgColor, dotColor, icon, label, sublabel) = when (status) {
        MonitoringStatus.ACTIVE ->
            StatusBarConfig(
                bg      = Color(0xFF1B5E20),
                dot     = Color(0xFF69F0AE),
                icon    = Icons.Default.Shield,
                label   = "Monitoring Active",
                sub     = "All calls are being analysed"
            )
        MonitoringStatus.IN_CALL ->
            StatusBarConfig(
                bg      = Color(0xFF0D47A1),
                dot     = Color(0xFF40C4FF),
                icon    = Icons.Default.Call,
                label   = "Call In Progress",
                sub     = "Analysing for fraud signals…"
            )
        MonitoringStatus.STARTING ->
            StatusBarConfig(
                bg      = Color(0xFF1A237E),
                dot     = Color(0xFFE8EAF6),
                icon    = Icons.Default.HourglassTop,
                label   = "Starting Monitor",
                sub     = "Registering telephony listener…"
            )
        MonitoringStatus.PERMISSION_DENIED ->
            StatusBarConfig(
                bg      = Color(0xFF4E342E),
                dot     = Color(0xFFFFCC02),
                icon    = Icons.Default.LockOpen,
                label   = "Permission Required",
                sub     = "Grant Phone State permission"
            )
        MonitoringStatus.STOPPING,
        MonitoringStatus.IDLE ->
            StatusBarConfig(
                bg      = Color(0xFF263238),
                dot     = Color(0xFF78909C),
                icon    = Icons.Default.ShieldMoon,
                label   = "Monitoring Inactive",
                sub     = "Calls are not being analysed"
            )
    }

    val isAnimating = status == MonitoringStatus.ACTIVE || status == MonitoringStatus.IN_CALL

    Surface(
        color    = bgColor,
        shape    = RoundedCornerShape(bottomStart = 0.dp, bottomEnd = 0.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Pulsing dot + icon
            Box(contentAlignment = Alignment.Center) {
                if (isAnimating) {
                    PulsingRing(dotColor)
                }
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }

            Column(Modifier.weight(1f)) {
                Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(sublabel, color = Color.White.copy(0.65f), fontSize = 11.sp)
            }

            // Status dot
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(dotColor)
            )
        }
    }
}

@Composable
private fun PulsingRing(color: Color) {
    val inf = rememberInfiniteTransition(label = "ring")
    val scale by inf.animateFloat(
        1f, 2.0f,
        infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        "ring_scale"
    )
    val alpha by inf.animateFloat(
        0.4f, 0f,
        infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        "ring_alpha"
    )
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Data helper
// ─────────────────────────────────────────────────────────────────────────────

private data class StatusBarConfig(
    val bg    : Color,
    val dot   : Color,
    val icon  : androidx.compose.ui.graphics.vector.ImageVector,
    val label : String,
    val sub   : String
)

// ─────────────────────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────────────────────

@Preview(name = "Status – Active", showBackground = true)
@Composable
private fun StatusActivePreview() {
    FraudCallDetectorTheme { MonitoringStatusBar(MonitoringStatus.ACTIVE) }
}

@Preview(name = "Status – In Call", showBackground = true)
@Composable
private fun StatusInCallPreview() {
    FraudCallDetectorTheme { MonitoringStatusBar(MonitoringStatus.IN_CALL) }
}

@Preview(name = "Status – Permission Denied", showBackground = true)
@Composable
private fun StatusPermissionPreview() {
    FraudCallDetectorTheme { MonitoringStatusBar(MonitoringStatus.PERMISSION_DENIED) }
}

@Preview(name = "Status – Idle", showBackground = true)
@Composable
private fun StatusIdlePreview() {
    FraudCallDetectorTheme { MonitoringStatusBar(MonitoringStatus.IDLE) }
}
