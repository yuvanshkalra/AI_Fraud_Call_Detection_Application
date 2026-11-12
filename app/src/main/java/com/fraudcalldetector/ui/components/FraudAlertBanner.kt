package com.fraudcalldetector.ui.components

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fraudcalldetector.domain.model.FraudAnalysis
import com.fraudcalldetector.domain.model.RiskLevel

/**
 * In-app fraud alert banner shown at the bottom of [ActiveCallScreen] when
 * a real-time fraud score crosses the threshold during a live call.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Display rules
 * ─────────────────────────────────────────────────────────────────────────────
 *  • Shown when [analysis] is non-null AND [analysis.riskLevel] >= MEDIUM.
 *  • Animates in from the bottom with a slide + fade entrance.
 *  • For CRITICAL/HIGH: uses a solid coloured background with full prominence.
 *  • For MEDIUM: uses a subtle tinted card.
 *  • "View Details" button triggers [onViewDetails].
 *  • "×" dismiss button calls [onDismiss] (hides the banner for this session;
 *    the system notification is unaffected).
 *
 * @param analysis      The fraud analysis result to display; null = banner hidden.
 * @param isFinalResult True when the analysis is from the final full-call pass
 *                      (shown with "Final" label), false for incremental estimates.
 * @param callerName    Optional caller display name.
 * @param phoneNumber   Caller phone number.
 * @param onViewDetails Called when the user taps "View Details".
 * @param onDismiss     Called when the user taps the dismiss (×) button.
 */
@Composable
fun FraudAlertBanner(
    analysis      : FraudAnalysis?,
    isFinalResult : Boolean  = false,
    callerName    : String?  = null,
    phoneNumber   : String   = "",
    onViewDetails : () -> Unit,
    onDismiss     : () -> Unit,
    modifier      : Modifier = Modifier
) {
    val shouldShow = analysis != null && analysis.riskLevel >= RiskLevel.MEDIUM

    AnimatedVisibility(
        visible  = shouldShow,
        enter    = slideInVertically(tween(360)) { it } + fadeIn(tween(300)),
        exit     = slideOutVertically(tween(280)) { it } + fadeOut(tween(220)),
        modifier = modifier
    ) {
        if (analysis == null) return@AnimatedVisibility

        val riskColor   = analysis.riskLevel.bgColor()
        val isCritical  = analysis.riskLevel == RiskLevel.CRITICAL || analysis.riskLevel == RiskLevel.HIGH
        val bgColor     = if (isCritical) riskColor.copy(0.92f) else riskColor.copy(0.15f)
        val fgColor     = if (isCritical) Color.White else riskColor
        val subFgColor  = if (isCritical) Color.White.copy(0.80f) else riskColor.copy(0.75f)
        val confidence  = (analysis.confidence * 100).toInt()
        val callerLabel = callerName?.takeIf { it.isNotBlank() } ?: phoneNumber

        // Pulsing ring for CRITICAL
        val pulse = rememberInfiniteTransition(label = "alert_pulse")
        val ringAlpha by pulse.animateFloat(
            0.25f, 0.60f,
            infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "ring"
        )

        Surface(
            shape  = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color  = bgColor,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── Header ──────────────────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Pulsing icon
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
                        if (isCritical) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(ringAlpha))
                            )
                        }
                        Box(
                            Modifier.size(32.dp).clip(CircleShape)
                                .background(if (isCritical) Color.White.copy(0.22f) else riskColor.copy(0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (analysis.isFraud) Icons.Default.GppBad else Icons.Default.Warning,
                                contentDescription = null,
                                tint     = fgColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Column(Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = when (analysis.riskLevel) {
                                    RiskLevel.CRITICAL -> "⚠ FRAUD CALL DETECTED"
                                    RiskLevel.HIGH     -> "🚨 Suspicious Call"
                                    RiskLevel.MEDIUM   -> "Potentially Suspicious"
                                    else               -> "Low-risk Flag"
                                },
                                color      = fgColor,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize   = 14.sp
                            )
                            if (!isFinalResult) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = fgColor.copy(0.18f)
                                ) {
                                    Text(
                                        "~Live",
                                        color      = fgColor,
                                        fontSize   = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier   = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text     = "$callerLabel · $confidence% confidence",
                            color    = subFgColor,
                            fontSize = 12.sp
                        )
                    }

                    // Dismiss ×
                    IconButton(
                        onClick  = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Dismiss alert",
                            tint     = fgColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // ── Primary reason ──────────────────────────────────────────
                val reason = analysis.flags.firstOrNull()?.description
                    ?: "Suspicious activity detected"

                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint     = subFgColor,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text     = reason,
                        color    = subFgColor,
                        fontSize = 12.sp,
                        maxLines = 2
                    )
                }

                // ── Confidence bar ──────────────────────────────────────────
                val animConf by animateFloatAsState(
                    analysis.confidence, tween(700), label = "banner_conf"
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(fgColor.copy(0.20f))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(animConf)
                            .clip(CircleShape)
                            .background(fgColor)
                    )
                }

                // ── Action button ───────────────────────────────────────────
                Button(
                    onClick = onViewDetails,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    shape  = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCritical) Color.White.copy(0.22f) else riskColor,
                        contentColor   = if (isCritical) Color.White else Color.White
                    )
                ) {
                    Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text       = "View Full Details",
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 13.sp
                    )
                }
            }
        }
    }
}
