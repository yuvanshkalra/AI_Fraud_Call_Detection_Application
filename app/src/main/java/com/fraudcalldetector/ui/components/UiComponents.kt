package com.fraudcalldetector.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fraudcalldetector.domain.model.RiskLevel
import kotlin.math.cos
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// Risk level colour helpers (single source of truth)
// ─────────────────────────────────────────────────────────────────────────────

fun RiskLevel.bgColor() = when (this) {
    RiskLevel.CRITICAL -> Color(0xFFB71C1C)
    RiskLevel.HIGH     -> Color(0xFFE53935)
    RiskLevel.MEDIUM   -> Color(0xFFF57C00)
    RiskLevel.LOW      -> Color(0xFFF9A825)
    RiskLevel.SAFE     -> Color(0xFF2E7D32)
}

fun RiskLevel.onColor() = when (this) {
    RiskLevel.LOW -> Color.Black
    else          -> Color.White
}

fun RiskLevel.containerColor() = when (this) {
    RiskLevel.CRITICAL -> Color(0xFFFFEBEE)
    RiskLevel.HIGH     -> Color(0xFFFCE4EC)
    RiskLevel.MEDIUM   -> Color(0xFFFFF3E0)
    RiskLevel.LOW      -> Color(0xFFFFFDE7)
    RiskLevel.SAFE     -> Color(0xFFE8F5E9)
}

fun RiskLevel.contentColor() = when (this) {
    RiskLevel.CRITICAL -> Color(0xFFB71C1C)
    RiskLevel.HIGH     -> Color(0xFFC62828)
    RiskLevel.MEDIUM   -> Color(0xFFE65100)
    RiskLevel.LOW      -> Color(0xFFF57F17)
    RiskLevel.SAFE     -> Color(0xFF1B5E20)
}

fun RiskLevel.label() = when (this) {
    RiskLevel.CRITICAL -> "CRITICAL"
    RiskLevel.HIGH     -> "HIGH RISK"
    RiskLevel.MEDIUM   -> "MEDIUM"
    RiskLevel.LOW      -> "LOW RISK"
    RiskLevel.SAFE     -> "SAFE"
}

// ─────────────────────────────────────────────────────────────────────────────
// RiskBadge  (pill chip)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun RiskBadge(level: RiskLevel, modifier: Modifier = Modifier, compact: Boolean = false) {
    val bg   = level.bgColor()
    val fg   = level.onColor()
    val text = level.label()
    Surface(
        color = bg,
        shape = RoundedCornerShape(if (compact) 4.dp else 6.dp),
        modifier = modifier
    ) {
        Text(
            text  = text,
            color = fg,
            fontSize    = if (compact) 9.sp else 11.sp,
            fontWeight  = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp,
            modifier    = Modifier.padding(
                horizontal = if (compact) 5.dp else 8.dp,
                vertical   = if (compact) 2.dp else 3.dp
            )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pulsing status dot
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_dot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "alpha"
    )
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f, targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "scale"
    )
    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Audio waveform  (animated bars)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AudioWaveform(
    isActive: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 24
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val heights = (0 until barCount).map { i ->
        if (isActive) {
            infiniteTransition.animateFloat(
                initialValue = 0.1f,
                targetValue  = 0.9f,
                animationSpec = infiniteRepeatable(
                    animation   = tween(
                        durationMillis = 300 + i * 40,
                        easing         = FastOutSlowInEasing
                    ),
                    repeatMode  = RepeatMode.Reverse
                ),
                label = "bar_$i"
            )
        } else {
            remember { mutableStateOf(0.12f) }
        }
    }

    Canvas(modifier = modifier) {
        val barWidth   = (size.width - (barCount - 1) * 4f) / barCount
        val maxBarH    = size.height
        heights.forEachIndexed { i, h ->
            val barH   = maxBarH * h.value
            val x      = i * (barWidth + 4f)
            val y      = (maxBarH - barH) / 2f
            drawRoundRect(
                color       = color,
                topLeft     = Offset(x, y),
                size        = androidx.compose.ui.geometry.Size(barWidth, barH),
                cornerRadius= androidx.compose.ui.geometry.CornerRadius(barWidth / 2)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Confidence arc meter
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ConfidenceArcMeter(
    confidence: Float,         // 0..1
    riskLevel: RiskLevel,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp
) {
    val animatedConfidence by animateFloatAsState(
        targetValue  = confidence,
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label        = "confidence"
    )
    val arcColor  = riskLevel.bgColor()
    val trackColor = arcColor.copy(alpha = 0.15f)
    val sweepAngle = animatedConfidence * 240f        // 240° sweep
    val startAngle = 150f

    Box(
        modifier        = modifier.size(size),
        contentAlignment= Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke  = 14.dp.toPx()
            val inset   = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(
                width  = this.size.width  - inset * 2,
                height = this.size.height - inset * 2
            )
            // Track
            drawArc(
                color      = trackColor,
                startAngle = startAngle,
                sweepAngle = 240f,
                useCenter  = false,
                topLeft    = Offset(inset, inset),
                size       = arcSize,
                style      = Stroke(stroke, cap = StrokeCap.Round)
            )
            // Progress
            drawArc(
                color      = arcColor,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter  = false,
                topLeft    = Offset(inset, inset),
                size       = arcSize,
                style      = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text       = "${(animatedConfidence * 100).toInt()}%",
                fontSize   = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color      = arcColor
            )
            Text(
                text       = "Confidence",
                fontSize   = 11.sp,
                color      = arcColor.copy(alpha = 0.7f)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Gradient header banner
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GradientBanner(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(colors),
                shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
            )
            .padding(horizontal = 20.dp, vertical = 28.dp),
        content = content
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Section header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (action != null && onAction != null) {
            TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) {
                Text(action, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Full-screen empty state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                icon, contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stat card (number + label + icon)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun StatCard(
    value: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier  = modifier,
        shape     = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
            }
            Text(value, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            Text(label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                textAlign = TextAlign.Center
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shield protection badge (large, animated)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ShieldStatusBadge(
    isProtected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 80.dp
) {
    val pulse = rememberInfiniteTransition(label = "shield_pulse")
    val scale by pulse.animateFloat(
        initialValue = 0.92f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "shield_scale"
    )
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.15f, targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "ring_alpha"
    )
    val activeColor = Color(0xFF00C853)
    val inactiveColor = MaterialTheme.colorScheme.outline

    Box(modifier = modifier.size(size + 24.dp), contentAlignment = Alignment.Center) {
        if (isProtected) {
            // Outer ring
            Box(
                Modifier
                    .size(size + 24.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(activeColor.copy(alpha = ringAlpha))
            )
        }
        // Inner circle
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (isProtected) activeColor else inactiveColor.copy(alpha = 0.15f))
                .border(2.dp, if (isProtected) activeColor else inactiveColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isProtected) Icons.Default.Shield else Icons.Default.ShieldMoon,
                contentDescription = null,
                tint = if (isProtected) Color.White else inactiveColor,
                modifier = Modifier.size(size * 0.5f)
            )
        }
    }
}
