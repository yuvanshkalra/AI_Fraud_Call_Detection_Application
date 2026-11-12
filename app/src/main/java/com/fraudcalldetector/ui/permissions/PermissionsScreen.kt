package com.fraudcalldetector.ui.permissions

import android.Manifest
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.*
import com.fraudcalldetector.ui.theme.FraudCallDetectorTheme

// ─────────────────────────────────────────────────────────────────────────────
// Data
// ─────────────────────────────────────────────────────────────────────────────

data class PermissionItem(
    val permission: String,
    val icon      : ImageVector,
    val title     : String,
    val rationale : String,
    val critical  : Boolean = true
)

private val REQUIRED_PERMISSIONS: List<PermissionItem> get() = buildList {
    add(PermissionItem(
        Manifest.permission.READ_PHONE_STATE,
        Icons.Default.Phone,
        "Phone State",
        "Detect incoming and outgoing calls in real time",
        critical = true
    ))
    add(PermissionItem(
        Manifest.permission.READ_CALL_LOG,
        Icons.Default.History,
        "Call Log",
        "Access call history for comprehensive fraud analysis",
        critical = true
    ))
    add(PermissionItem(
        Manifest.permission.RECORD_AUDIO,
        Icons.Default.Mic,
        "Microphone",
        "Record calls to send to the AI speech analysis engine",
        critical = true
    ))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(PermissionItem(
            Manifest.permission.POST_NOTIFICATIONS,
            Icons.Default.Notifications,
            "Notifications",
            "Alert you immediately when a fraudulent call is detected",
            critical = false
        ))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen (uses Accompanist)
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsScreen(onAllGranted: () -> Unit) {
    val permStates = rememberMultiplePermissionsState(REQUIRED_PERMISSIONS.map { it.permission })

    LaunchedEffect(permStates.allPermissionsGranted) {
        if (permStates.allPermissionsGranted) onAllGranted()
    }

    val grantedSet = permStates.permissions.filter { it.status.isGranted }.map { it.permission }.toSet()
    val grantedCount = grantedSet.size
    val totalCount   = REQUIRED_PERMISSIONS.size

    PermissionsContent(
        items        = REQUIRED_PERMISSIONS,
        grantedSet   = grantedSet,
        grantedCount = grantedCount,
        totalCount   = totalCount,
        onGrant      = { permStates.launchMultiplePermissionRequest() }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure-UI  (also used directly in the static preview)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PermissionsContent(
    items       : List<PermissionItem>,
    grantedSet  : Set<String>,
    grantedCount: Int,
    totalCount  : Int,
    onGrant     : () -> Unit
) {
    val allGranted = grantedCount == totalCount

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0D1B3E), Color(0xFF1A2550), Color(0xFF0D0D0D)))
        )
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            // ── Animated shield ───────────────────────────────────────────
            AnimatedShield(progress = grantedCount.toFloat() / totalCount.toFloat())

            Spacer(Modifier.height(24.dp))

            // ── Headline ──────────────────────────────────────────────────
            AnimatedContent(
                targetState   = allGranted,
                transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) },
                label         = "headline"
            ) { granted ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text       = if (granted) "You're Protected!" else "Setup Permissions",
                        fontSize   = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color      = Color.White,
                        textAlign  = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text      = if (granted)
                            "All permissions granted. FraudCall Detector is active."
                        else
                            "Grant these permissions so FraudCall Detector can protect your calls.",
                        fontSize  = 14.sp,
                        color     = Color.White.copy(0.65f),
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Progress indicator ────────────────────────────────────────
            ProgressPill(granted = grantedCount, total = totalCount)

            Spacer(Modifier.height(24.dp))

            // ── Permission items ──────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach { item ->
                    val isGranted = item.permission in grantedSet
                    PermissionCard(item = item, isGranted = isGranted)
                }
            }

            Spacer(Modifier.weight(1f))

            // ── CTA Button ────────────────────────────────────────────────
            AnimatedContent(
                targetState   = allGranted,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label         = "cta"
            ) { granted ->
                if (granted) {
                    FilledTonalButton(
                        onClick  = onGrant,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape    = RoundedCornerShape(14.dp),
                        colors   = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF00C853),
                            contentColor   = Color.White
                        )
                    ) {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Continue  →", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                } else {
                    Button(
                        onClick  = onGrant,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape    = RoundedCornerShape(14.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                    ) {
                        Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Grant Permissions", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Animated shield ring
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AnimatedShield(progress: Float) {
    val animProgress by animateFloatAsState(
        targetValue   = progress,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label         = "shield_prog"
    )
    val pulse = rememberInfiniteTransition(label = "shield")
    val glow by pulse.animateFloat(
        0.6f, 1f,
        infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        "glow"
    )
    val isComplete = progress >= 1f
    val shieldColor = if (isComplete) Color(0xFF00C853) else Color(0xFF1565C0)

    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        // Background circle
        Box(
            Modifier.size(110.dp).clip(CircleShape)
                .background(shieldColor.copy(alpha = 0.1f * glow))
        )
        Box(
            Modifier.size(88.dp).clip(CircleShape)
                .background(shieldColor.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isComplete) Icons.Default.Shield else Icons.Default.Security,
                null,
                tint     = shieldColor,
                modifier = Modifier.size(44.dp)
            )
        }
        // Progress arc drawn via canvas would require Canvas — show as ring fraction text instead
        Box(
            Modifier.size(110.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Text(
                "${(animProgress * 100).toInt()}%",
                color      = shieldColor,
                fontSize   = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.padding(bottom = 6.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Progress pill
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProgressPill(granted: Int, total: Int) {
    Surface(
        color    = Color.White.copy(0.07f),
        shape    = CircleShape
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(total) { i ->
                val isGranted = i < granted
                Box(
                    Modifier
                        .size(if (isGranted) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isGranted) Color(0xFF00C853) else Color.White.copy(0.25f)
                        )
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "$granted / $total granted",
                color    = Color.White.copy(0.7f),
                fontSize = 12.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Individual permission card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PermissionCard(item: PermissionItem, isGranted: Boolean) {
    val bgColor     = if (isGranted) Color(0xFF00C853).copy(0.08f)
                      else            Color.White.copy(0.05f)
    val borderColor = if (isGranted) Color(0xFF00C853).copy(0.4f)
                      else            Color.White.copy(0.08f)

    Surface(
        color  = bgColor,
        shape  = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(
                        if (isGranted) Color(0xFF00C853).copy(0.15f)
                        else Color.White.copy(0.08f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    item.icon, null,
                    tint     = if (isGranted) Color(0xFF00C853) else Color.White.copy(0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (!item.critical) {
                        Surface(color = Color.White.copy(0.1f), shape = CircleShape) {
                            Text("optional", color = Color.White.copy(0.5f), fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                Text(item.rationale, color = Color.White.copy(0.5f), fontSize = 12.sp, lineHeight = 18.sp)
            }
            AnimatedContent(targetState = isGranted, label = "perm_icon") { granted ->
                if (granted) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF00C853), modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Outlined.Circle, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────────────────────

@Preview(name = "Permissions – None Granted", showBackground = true)
@Composable
private fun PermissionsNonePreview() {
    FraudCallDetectorTheme(darkTheme = true) {
        PermissionsContent(
            items        = REQUIRED_PERMISSIONS,
            grantedSet   = emptySet(),
            grantedCount = 0,
            totalCount   = REQUIRED_PERMISSIONS.size,
            onGrant      = {}
        )
    }
}

@Preview(name = "Permissions – Partial", showBackground = true)
@Composable
private fun PermissionsPartialPreview() {
    FraudCallDetectorTheme(darkTheme = true) {
        PermissionsContent(
            items        = REQUIRED_PERMISSIONS,
            grantedSet   = setOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.RECORD_AUDIO),
            grantedCount = 2,
            totalCount   = REQUIRED_PERMISSIONS.size,
            onGrant      = {}
        )
    }
}

@Preview(name = "Permissions – All Granted", showBackground = true)
@Composable
private fun PermissionsAllPreview() {
    FraudCallDetectorTheme(darkTheme = true) {
        PermissionsContent(
            items        = REQUIRED_PERMISSIONS,
            grantedSet   = REQUIRED_PERMISSIONS.map { it.permission }.toSet(),
            grantedCount = REQUIRED_PERMISSIONS.size,
            totalCount   = REQUIRED_PERMISSIONS.size,
            onGrant      = {}
        )
    }
}
