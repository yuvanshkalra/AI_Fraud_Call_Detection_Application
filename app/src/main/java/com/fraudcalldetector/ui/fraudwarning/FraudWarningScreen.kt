package com.fraudcalldetector.ui.fraudwarning

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
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
import com.fraudcalldetector.core.utils.PhoneUtils

// ─────────────────────────────────────────────────────────────────────────────
// Screen entry point (ViewModel-driven — works for deep-link and in-app nav)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Fraud warning detail screen.
 *
 * Loads the [CallEvent] via [FraudWarningViewModel] so it can be reached from
 * both in-app navigation and notification deep-links.  An optional [preloadedCall]
 * can be supplied by the NavGraph when the call is already in memory, saving the
 * round-trip to the repository.
 *
 * @param preloadedCall If non-null, bypasses the ViewModel fetch and renders immediately.
 *                      Used when navigating from [CallDetailScreen] or [ActiveCallScreen].
 * @param onBack        Navigate back.
 * @param onViewDetail  Navigate to [CallDetailScreen] for this call.
 * @param viewModel     Injected by Hilt; reads callId from [SavedStateHandle].
 */
@Composable
fun FraudWarningScreen(
    preloadedCall: CallEvent?             = null,
    onBack       : () -> Unit,
    onViewDetail : () -> Unit             = {},
    viewModel    : FraudWarningViewModel  = hiltViewModel()
) {
    if (preloadedCall != null && preloadedCall.fraudAnalysis != null) {
        // Fast path — call already in memory (e.g. from call history tap)
        FraudWarningContent(
            call         = preloadedCall,
            analysis     = preloadedCall.fraudAnalysis!!,
            onBack       = onBack,
            onViewDetail = onViewDetail
        )
        return
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        is FraudWarningUiState.Loading -> {
            Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A)), Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        is FraudWarningUiState.Error -> {
            Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A)), Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint     = Color.White.copy(0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        "Call data unavailable",
                        color      = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 18.sp
                    )
                    Text(
                        state.message,
                        color    = Color.White.copy(0.55f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onBack,
                        colors  = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Go Back")
                    }
                }
            }
        }

        is FraudWarningUiState.Success -> {
            val call = state.call
            val analysis = call.fraudAnalysis ?: run {
                // Call has no fraud analysis — show a safe state
                FraudWarningContent(
                    call         = call,
                    analysis     = FraudAnalysis(false, 0f, RiskLevel.SAFE, emptyList()),
                    onBack       = onBack,
                    onViewDetail = onViewDetail
                )
                return
            }
            FraudWarningContent(
                call         = call,
                analysis     = analysis,
                onBack       = onBack,
                onViewDetail = onViewDetail
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure-UI
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FraudWarningContent(
    call        : CallEvent,
    analysis    : FraudAnalysis,
    onBack      : () -> Unit,
    onViewDetail: () -> Unit
) {
    val riskBg    = analysis.riskLevel.bgColor()
    val riskFg    = analysis.riskLevel.onColor()
    val container = analysis.riskLevel.containerColor()
    val content   = analysis.riskLevel.contentColor()

    // Alert entrance animation
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        // Coloured radial glow behind the header
        Box(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(
                    Brush.radialGradient(
                        listOf(riskBg.copy(alpha = 0.35f), Color.Transparent),
                        radius = 700f
                    )
                )
        )

        Column(Modifier.fillMaxSize()) {
            // ── Top bar ───────────────────────────────────────────────────
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = onViewDetail) {
                        Icon(Icons.Default.OpenInNew, null, tint = Color.White.copy(0.7f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {

                // ── Hero alert section ────────────────────────────────────
                AnimatedVisibility(
                    visible = visible,
                    enter   = fadeIn(tween(500)) + slideInVertically(tween(600)) { -60 }
                ) {
                    HeroAlertSection(analysis = analysis, riskBg = riskBg, riskFg = riskFg)
                }

                // ── Caller info card ──────────────────────────────────────
                AnimatedVisibility(visible = visible, enter = fadeIn(tween(600, 150))) {
                    CallerInfoCard(call = call, content = content, container = container)
                }

                // ── Confidence arc + risk stats ───────────────────────────
                AnimatedVisibility(visible = visible, enter = fadeIn(tween(600, 250))) {
                    ConfidenceSection(analysis = analysis)
                }

                // ── Fraud flags ───────────────────────────────────────────
                if (analysis.flags.isNotEmpty()) {
                    AnimatedVisibility(visible = visible, enter = fadeIn(tween(600, 350))) {
                        FraudFlagsCard(flags = analysis.flags, content = content, container = container)
                    }
                }

                // ── Transcript snippet ────────────────────────────────────
                call.transcript?.let { transcript ->
                    AnimatedVisibility(visible = visible, enter = fadeIn(tween(600, 450))) {
                        TranscriptCard(transcript = transcript)
                    }
                }

                // ── Action buttons ────────────────────────────────────────
                AnimatedVisibility(visible = visible, enter = fadeIn(tween(600, 500))) {
                    ActionButtons(riskBg = riskBg, onBack = onBack)
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Hero alert (icon + title + badge)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HeroAlertSection(analysis: FraudAnalysis, riskBg: Color, riskFg: Color) {
    val pulse = rememberInfiniteTransition(label = "hero_pulse")
    val ringScale by pulse.animateFloat(
        1f, 1.3f,
        infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "ring"
    )
    val ringAlpha by pulse.animateFloat(
        0.2f, 0.5f,
        infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "alpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Pulsing icon
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
            if (analysis.isFraud) {
                Box(
                    Modifier.size(120.dp).scale(ringScale).clip(CircleShape)
                        .background(riskBg.copy(alpha = ringAlpha))
                )
            }
            Box(
                Modifier.size(88.dp).clip(CircleShape).background(riskBg.copy(0.2f))
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (analysis.isFraud) Icons.Default.GppBad else Icons.Default.VerifiedUser,
                    null,
                    tint     = riskBg,
                    modifier = Modifier.size(44.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text       = if (analysis.isFraud) "Fraud Detected" else "Call Cleared",
            fontSize   = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            color      = Color.White,
            textAlign  = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text      = if (analysis.isFraud)
                "This call has been identified as fraudulent"
            else
                "No significant fraud signals detected",
            fontSize  = 14.sp,
            color     = Color.White.copy(0.6f),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        RiskBadge(analysis.riskLevel)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Caller info card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CallerInfoCard(call: CallEvent, content: Color, container: Color) {
    Surface(
        color    = Color.White.copy(alpha = 0.06f),
        shape    = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    call.callerName ?: "Unknown Caller",
                    color      = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 15.sp
                )
                Text(
                    PhoneUtils.formatPhoneNumber(call.phoneNumber),
                    color    = Color.White.copy(0.55f),
                    fontSize = 13.sp
                )
            }
            Icon(Icons.Default.CallReceived, null, tint = Color.White.copy(0.4f))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Confidence arc + stat chips
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConfidenceSection(analysis: FraudAnalysis) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ConfidenceArcMeter(
            confidence = analysis.confidence,
            riskLevel  = analysis.riskLevel,
            size       = 170.dp
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RiskStatChip(
                modifier = Modifier.weight(1f),
                icon     = Icons.Default.Flag,
                label    = "Flags",
                value    = analysis.flags.size.toString(),
                color    = analysis.riskLevel.bgColor()
            )
            RiskStatChip(
                modifier = Modifier.weight(1f),
                icon     = Icons.Default.Speed,
                label    = "Severity",
                value    = analysis.riskLevel.name,
                color    = analysis.riskLevel.bgColor()
            )
            RiskStatChip(
                modifier = Modifier.weight(1f),
                icon     = Icons.Default.QueryStats,
                label    = "Score",
                value    = "${(analysis.confidence * 100).toInt()}%",
                color    = analysis.riskLevel.bgColor()
            )
        }
    }
}

@Composable
private fun RiskStatChip(
    label   : String,
    value   : String,
    icon    : androidx.compose.ui.graphics.vector.ImageVector,
    color   : Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape    = RoundedCornerShape(12.dp),
        color    = color.copy(alpha = 0.12f)
    ) {
        Column(
            Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(label, color = Color.White.copy(0.5f), fontSize = 10.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Fraud flags list
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FraudFlagsCard(flags: List<FraudFlag>, content: Color, container: Color) {
    Surface(
        color    = Color.White.copy(alpha = 0.06f),
        shape    = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.BugReport, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(18.dp))
                Text("Detected Signals", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            HorizontalDivider(color = Color.White.copy(0.1f))
            flags.forEach { flag ->
                FraudFlagRow(flag = flag)
            }
        }
    }
}

@Composable
private fun FraudFlagRow(flag: FraudFlag) {
    val severityColor = when {
        flag.severity > 0.8f -> Color(0xFFEF5350)
        flag.severity > 0.5f -> Color(0xFFFF9800)
        else                 -> Color(0xFFFFEB3B)
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(severityColor.copy(0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = severityColor, modifier = Modifier.size(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                flag.type.name.replace('_', ' ').lowercase()
                    .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
                color      = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize   = 13.sp
            )
            Text(flag.description, color = Color.White.copy(0.55f), fontSize = 12.sp)
        }
        // Severity bar
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${(flag.severity * 100).toInt()}%", color = severityColor, fontSize = 11.sp,
                fontWeight = FontWeight.Bold)
            val animSev by animateFloatAsState(flag.severity, tween(800), label = "sev")
            Box(
                Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(0.1f))
            ) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(animSev).clip(CircleShape)
                        .background(severityColor)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Transcript snippet
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TranscriptCard(transcript: String) {
    Surface(
        color    = Color.White.copy(0.06f),
        shape    = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Transcribe, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(18.dp))
                Text("Transcript Snippet", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            HorizontalDivider(color = Color.White.copy(0.1f))
            Text(
                text     = transcript.take(320) + if (transcript.length > 320) "…" else "",
                color    = Color.White.copy(0.65f),
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Action buttons
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ActionButtons(riskBg: Color, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick  = onBack,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape    = RoundedCornerShape(14.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = riskBg)
        ) {
            Icon(Icons.Default.Block, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Block Number", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick  = onBack,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape    = RoundedCornerShape(14.dp),
            colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
        ) {
            Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Back to Dashboard", fontWeight = FontWeight.Medium)
        }
    }
}

// needed for Modifier.scale()
private fun Modifier.scale(scale: Float) = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        val x = ((placeable.width  * (1 - scale)) / 2).toInt()
        val y = ((placeable.height * (1 - scale)) / 2).toInt()
        placeable.placeRelative(-x, -y)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────────────────────

@Preview(name = "Fraud Warning – CRITICAL", showBackground = true)
@Composable
private fun FraudWarningCriticalPreview() {
    FraudCallDetectorTheme(darkTheme = true) {
        FraudWarningContent(
            call        = PreviewData.fraudCall,
            analysis    = PreviewData.fraudCall.fraudAnalysis!!,
            onBack      = {},
            onViewDetail= {}
        )
    }
}

@Preview(name = "Fraud Warning – HIGH", showBackground = true)
@Composable
private fun FraudWarningHighPreview() {
    FraudCallDetectorTheme(darkTheme = true) {
        FraudWarningContent(
            call        = PreviewData.highRiskCall,
            analysis    = PreviewData.highRiskCall.fraudAnalysis!!,
            onBack      = {},
            onViewDetail= {}
        )
    }
}

@Preview(name = "Call Safe", showBackground = true)
@Composable
private fun FraudWarningSafePreview() {
    FraudCallDetectorTheme(darkTheme = false) {
        FraudWarningContent(
            call        = PreviewData.safeCall,
            analysis    = PreviewData.safeCall.fraudAnalysis!!,
            onBack      = {},
            onViewDetail= {}
        )
    }
}
