package com.fraudcalldetector.ui.activecall

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fraudcalldetector.domain.model.*
import com.fraudcalldetector.ui.components.*
import com.fraudcalldetector.ui.preview.PreviewData
import com.fraudcalldetector.ui.theme.FraudCallDetectorTheme
import com.fraudcalldetector.core.utils.DateUtils
import com.fraudcalldetector.core.utils.PhoneUtils

// ─────────────────────────────────────────────────────────────────────────────
// Screen entry point
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ActiveCallScreen(
    onBack          : () -> Unit,
    onFraudDetected : (String) -> Unit = {},   // callId → FraudWarningScreen
    viewModel       : ActiveCallViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var bannerDismissed by remember { mutableStateOf(false) }

    // Navigate to fraud warning when final analysis completes with HIGH+
    LaunchedEffect(state.fraudAnalysis) {
        val analysis = state.fraudAnalysis ?: return@LaunchedEffect
        if (analysis.riskLevel in listOf(RiskLevel.CRITICAL, RiskLevel.HIGH)) {
            onFraudDetected(state.call?.id ?: "")
        }
    }

    // Reset banner dismissed flag when a new (different) alert arrives
    LaunchedEffect(state.fraudAnalysis, state.liveRiskAnalysis) {
        bannerDismissed = false
    }

    ActiveCallContent(
        state             = state,
        onBack            = onBack,
        onRequestAnalysis = viewModel::requestAnalysis,
        bannerDismissed   = bannerDismissed,
        onBannerDismissed = {
            bannerDismissed = true
            viewModel.dismissFraudBanner()   // cancel system notification + update history
        },
        onFraudDetected   = { callId ->
            viewModel.markAlertViewed()      // cancel notification + mark VIEWED in history
            onFraudDetected(callId)
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure-UI content
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveCallContent(
    state            : ActiveCallState,
    onBack           : () -> Unit,
    onRequestAnalysis: () -> Unit,
    bannerDismissed  : Boolean       = false,
    onBannerDismissed: () -> Unit    = {},
    onFraudDetected  : (String) -> Unit = {}
) {
    val call = state.call

    // The banner shows the most informative analysis available
    val bannerAnalysis = state.fraudAnalysis ?: state.liveRiskAnalysis
    val isFinalBanner  = state.fraudAnalysis != null
    val showBanner     = !bannerDismissed && bannerAnalysis != null
                        && bannerAnalysis.riskLevel >= RiskLevel.MEDIUM

    // Full-screen gradient bg
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A0E27), Color(0xFF0D1B3E), Color(0xFF0F2459))
                )
            )
    ) {
        Column(
            modifier                = Modifier.fillMaxSize(),
            horizontalAlignment     = Alignment.CenterHorizontally
        ) {

            // ── Top bar ───────────────────────────────────────────────────
            TopAppBar(
                title = {
                    Text("Active Call", color = Color.White, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                    }
                },
                actions = {
                    if (state.isRecording) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            PulsingDot(Color(0xFFEF5350), size = 9.dp)
                            Spacer(Modifier.width(5.dp))
                            Text("REC", color = Color(0xFFEF5350), fontSize = 11.sp,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )

            Spacer(Modifier.height(16.dp))

            // ── Caller avatar ─────────────────────────────────────────────
            Box(
                Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, null, tint = Color.White.copy(0.7f),
                    modifier = Modifier.size(52.dp))
            }

            Spacer(Modifier.height(16.dp))

            // ── Caller info ───────────────────────────────────────────────
            Text(
                text       = call?.callerName ?: "Unknown Caller",
                fontSize   = 24.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text     = call?.let { PhoneUtils.formatPhoneNumber(it.phoneNumber) } ?: "—",
                fontSize = 14.sp,
                color    = Color.White.copy(0.6f)
            )

            Spacer(Modifier.height(10.dp))

            // ── Duration timer ────────────────────────────────────────────
            Text(
                text       = DateUtils.formatDuration(state.elapsedMs),
                fontSize   = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                color      = Color.White,
                letterSpacing = 2.sp
            )

            Spacer(Modifier.height(4.dp))

            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PulsingDot(Color(0xFF69F0AE), size = 8.dp)
                Text(
                    if (state.isCallActive) "In progress" else "Call ended",
                    fontSize = 12.sp,
                    color    = Color.White.copy(0.65f)
                )
            }

            Spacer(Modifier.height(28.dp))

            // ── Waveform ──────────────────────────────────────────────────
            AudioWaveform(
                isActive  = state.isCallActive,
                color     = Color(0xFF42A5F5),
                modifier  = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 20.dp)
            )

            Spacer(Modifier.height(12.dp))

            // ── Recording info strip ───────────────────────────────────────
            RecordingInfoStrip(
                isRecording = state.isRecording,
                chunkCount  = state.chunkCount,
                modifier    = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(Modifier.height(10.dp))

            // ── Live transcript strip ─────────────────────────────────────
            LiveTranscriptStrip(
                text           = state.liveTranscriptText,
                isTranscribing = state.isTranscribing,
                modifier       = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(Modifier.height(10.dp))

            // ── Fraud analysis chip (auto-triggered status) ───────────────
            // Prefer the final analysis; fall back to the live incremental score.
            FraudAnalysisChip(
                status   = state.analysisStatus,
                analysis = state.fraudAnalysis ?: state.liveRiskAnalysis,
                isLive   = state.fraudAnalysis == null && state.liveRiskAnalysis != null,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(Modifier.height(6.dp))

            // ── Analysis status card ──────────────────────────────────────
            AnalysisStatusCard(
                status   = state.analysisStatus,
                analysis = state.fraudAnalysis,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(Modifier.weight(1f))

            // ── Action buttons ────────────────────────────────────────────
            // Reserve space at the bottom for the fraud alert banner when visible
            val bottomPad = if (showBanner) 0.dp else 24.dp
            CallControlRow(
                analysisStatus    = state.analysisStatus,
                hasRecording      = call?.recordingPath != null,
                onRequestAnalysis = onRequestAnalysis,
                modifier          = Modifier.padding(horizontal = 20.dp, vertical = bottomPad)
            )
        }

        // ── Fraud alert banner (anchored to screen bottom) ────────────────
        FraudAlertBanner(
            analysis      = bannerAnalysis,
            isFinalResult = isFinalBanner,
            callerName    = call?.callerName,
            phoneNumber   = call?.phoneNumber ?: "",
            onViewDetails = { onFraudDetected(call?.id ?: "") },
            onDismiss     = onBannerDismissed,
            modifier      = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Fraud analysis chip — compact auto-triggered indicator
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A slim horizontal chip row that shows the *real-time* fraud detection status.
 *
 * Unlike [AnalysisStatusCard] (which handles the manual "Analyse" button flow),
 * this chip reflects the **automatic** [FraudDetectionManager] pipeline state:
 *  - [AnalysisStatus.IDLE]      → hidden
 *  - [AnalysisStatus.ANALYSING] → "Scanning call…" spinner chip; if an incremental
 *    [analysis] score is also available it is shown next to the spinner.
 *  - [AnalysisStatus.COMPLETE]  → risk-level badge chip (colour-coded)
 *
 * @param isLive When true the [analysis] is an incremental mid-call score;
 *               a "~live" prefix is added to signal it is not yet final.
 */
@Composable
private fun FraudAnalysisChip(
    status  : AnalysisStatus,
    analysis: FraudAnalysis?,
    isLive  : Boolean      = false,
    modifier: Modifier = Modifier
) {
    if (status == AnalysisStatus.IDLE) return

    Row(
        modifier              = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        when (status) {
            AnalysisStatus.ANALYSING -> {
                // Spinner chip — always shown
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF1A237E).copy(alpha = 0.55f)
                ) {
                    Row(
                        modifier              = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(12.dp),
                            color       = Color(0xFF42A5F5),
                            strokeWidth = 1.5.dp
                        )
                        Text(
                            text     = "Scanning call…",
                            color    = Color(0xFF42A5F5),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // If an incremental score is already available, show it inline
                if (analysis != null && isLive) {
                    Spacer(Modifier.width(8.dp))
                    val chipColor = analysis.riskLevel.bgColor()
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = chipColor.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier              = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = if (analysis.isFraud) Icons.Default.GppBad
                                              else Icons.Default.VerifiedUser,
                                contentDescription = null,
                                tint     = chipColor,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                // "~" prefix signals this is a live estimate, not yet final
                                text  = "~${(analysis.confidence * 100).toInt()}% ${analysis.riskLevel.label()}",
                                color = chipColor,
                                fontSize   = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
            AnalysisStatus.COMPLETE -> {
                val a = analysis ?: return
                val chipColor = a.riskLevel.bgColor()
                Surface(
                    shape = RoundedCornerShape(50),
                    color = chipColor.copy(alpha = 0.18f)
                ) {
                    Row(
                        modifier              = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Icon(
                            imageVector = if (a.isFraud) Icons.Default.GppBad
                                          else Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint     = chipColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text  = if (a.isFraud) "Fraud ${a.riskLevel.label()}"
                                    else "Safe · ${(a.confidence * 100).toInt()}%",
                            color = chipColor,
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text     = "${(a.confidence * 100).toInt()}% confidence",
                    color    = Color.White.copy(0.40f),
                    fontSize = 10.sp
                )
            }
            AnalysisStatus.IDLE -> Unit
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Analysis status card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AnalysisStatusCard(
    status  : AnalysisStatus,
    analysis: FraudAnalysis?,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState   = status,
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
        label         = "analysis_card"
    ) { currentStatus ->
        when (currentStatus) {
            AnalysisStatus.IDLE -> {
                Surface(
                    modifier = modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(16.dp),
                    color    = Color.White.copy(alpha = 0.07f)
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Outlined.Analytics, null, tint = Color.White.copy(0.6f))
                        Text(
                            "AI analysis will run after the call ends",
                            color    = Color.White.copy(0.6f),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            AnalysisStatus.ANALYSING -> {
                Surface(
                    modifier = modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(16.dp),
                    color    = Color(0xFF1A237E).copy(alpha = 0.7f)
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier  = Modifier.size(20.dp),
                            color     = Color(0xFF42A5F5),
                            strokeWidth = 2.dp
                        )
                        Column {
                            Text("Analysing call…", color = Color.White,
                                fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("AI model processing transcript",
                                color = Color.White.copy(0.6f), fontSize = 12.sp)
                        }
                    }
                }
            }

            AnalysisStatus.COMPLETE -> {
                val a = analysis ?: return@AnimatedContent
                Surface(
                    modifier = modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(16.dp),
                    color    = a.riskLevel.bgColor().copy(alpha = 0.18f)
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(a.riskLevel.bgColor().copy(0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (a.isFraud) Icons.Default.GppBad else Icons.Default.VerifiedUser,
                                null,
                                tint     = a.riskLevel.bgColor(),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (a.isFraud) "Fraud Detected" else "Call Appears Safe",
                                color      = a.riskLevel.bgColor(),
                                fontWeight = FontWeight.Bold,
                                fontSize   = 15.sp
                            )
                            Text(
                                "Confidence ${(a.confidence * 100).toInt()}% · ${a.riskLevel.label()}",
                                color    = a.riskLevel.bgColor().copy(0.75f),
                                fontSize = 12.sp
                            )
                        }
                        RiskBadge(a.riskLevel)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Call control row (end, mute, speaker, analyse)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CallControlRow(
    analysisStatus   : AnalysisStatus,
    hasRecording     : Boolean,
    onRequestAnalysis: () -> Unit,
    modifier         : Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // Secondary controls
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            CallControlButton(icon = Icons.Default.MicOff,     label = "Mute",    onClick = {})
            CallControlButton(icon = Icons.Default.VolumeUp,   label = "Speaker", onClick = {})
            CallControlButton(icon = Icons.Default.RecordVoiceOver, label = "Record",  onClick = {})
            CallControlButton(
                icon      = Icons.Default.Psychology,
                label     = "Analyse",
                onClick   = onRequestAnalysis,
                enabled   = hasRecording && analysisStatus == AnalysisStatus.IDLE,
                tint      = Color(0xFF42A5F5)
            )
        }

        // End call button
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            FilledIconButton(
                onClick  = {},
                modifier = Modifier.size(64.dp),
                colors   = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color(0xFFEF5350)
                )
            ) {
                Icon(Icons.Default.CallEnd, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun CallControlButton(
    icon   : androidx.compose.ui.graphics.vector.ImageVector,
    label  : String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint   : Color   = Color.White
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilledTonalIconButton(
            onClick  = onClick,
            modifier = Modifier.size(52.dp),
            enabled  = enabled,
            colors   = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = Color.White.copy(alpha = 0.1f),
                contentColor   = tint,
                disabledContainerColor = Color.White.copy(alpha = 0.04f),
                disabledContentColor   = Color.White.copy(alpha = 0.25f)
            )
        ) {
            Icon(icon, null, modifier = Modifier.size(22.dp))
        }
        Text(label, color = Color.White.copy(0.6f), fontSize = 11.sp)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Recording info strip
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RecordingInfoStrip(
    isRecording: Boolean,
    chunkCount : Int,
    modifier   : Modifier = Modifier
) {
    val bgColor = if (isRecording) Color(0xFFEF5350).copy(alpha = 0.12f)
                  else Color.White.copy(alpha = 0.05f)
    val textColor = if (isRecording) Color(0xFFEF5350) else Color.White.copy(0.45f)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(10.dp),
        color    = bgColor
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isRecording) {
                    PulsingDot(Color(0xFFEF5350), size = 7.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.MicOff,
                        contentDescription = null,
                        tint     = textColor,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Text(
                    text     = if (isRecording) "Recording in progress" else "Recording stopped",
                    color    = textColor,
                    fontSize = 12.sp
                )
            }
            if (chunkCount > 0) {
                Text(
                    text     = "$chunkCount segment${if (chunkCount != 1) "s" else ""} captured",
                    color    = textColor,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Live transcript strip
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Shows a scrollable transcript strip below the waveform.
 *
 * Behaviour:
 *  - Hidden when [text] is blank and not [isTranscribing].
 *  - Shows a pulsing "Listening…" indicator when [isTranscribing] and text is blank.
 *  - Shows the latest ~200 characters of [text] when available.
 */
@Composable
private fun LiveTranscriptStrip(
    text           : String,
    isTranscribing : Boolean,
    modifier       : Modifier = Modifier
) {
    val show = isTranscribing || text.isNotBlank()
    if (!show) return

    val displayText = when {
        text.isBlank() -> "Listening…"
        text.length <= 200 -> text
        else -> "…" + text.takeLast(197)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(10.dp),
        color    = Color(0xFF1A237E).copy(alpha = 0.25f)
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment     = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Pulsing AI indicator
            Box(modifier = Modifier.padding(top = 3.dp)) {
                if (isTranscribing) {
                    PulsingDot(Color(0xFF42A5F5), size = 7.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        tint = Color(0xFF42A5F5).copy(0.6f),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text  = if (isTranscribing && text.isBlank()) "Transcribing…"
                            else "Live Transcript",
                    color = Color(0xFF42A5F5).copy(0.7f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text     = displayText,
                    color    = Color.White.copy(0.80f),
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────────────────────

@Preview(name = "Active Call – In Progress", showBackground = true)
@Composable
private fun ActiveCallPreview() {
    FraudCallDetectorTheme(darkTheme = true) {
        ActiveCallContent(
            state = ActiveCallState(
                call               = PreviewData.activeCall,
                isCallActive       = true,
                elapsedMs          = 127_000L,
                analysisStatus     = AnalysisStatus.IDLE,
                isRecording        = true,
                isTranscribing     = true,
                liveTranscriptText = "Hello this is a call from the IRS regarding unpaid taxes on your account…"
            ),
            onBack            = {},
            onRequestAnalysis = {}
        )
    }
}

@Preview(name = "Active Call – Fraud Detected", showBackground = true)
@Composable
private fun ActiveCallFraudPreview() {
    FraudCallDetectorTheme(darkTheme = true) {
        ActiveCallContent(
            state = ActiveCallState(
                call               = PreviewData.fraudCall,
                isCallActive       = false,
                elapsedMs          = 185_000L,
                fraudAnalysis      = PreviewData.fraudCall.fraudAnalysis,
                analysisStatus     = AnalysisStatus.COMPLETE,
                isTranscribing     = false,
                liveTranscriptText = PreviewData.fraudCall.transcript ?: ""
            ),
            onBack            = {},
            onRequestAnalysis = {}
        )
    }
}
