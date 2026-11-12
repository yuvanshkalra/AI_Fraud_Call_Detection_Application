package com.fraudcalldetector.feature.callhistory.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fraudcalldetector.domain.model.*
import com.fraudcalldetector.feature.callhistory.CallHistoryEvent
import com.fraudcalldetector.feature.callhistory.CallHistoryState
import com.fraudcalldetector.feature.callhistory.CallHistoryViewModel
import com.fraudcalldetector.feature.callhistory.HistoryFilter
import com.fraudcalldetector.core.utils.DateUtils
import com.fraudcalldetector.core.utils.PhoneUtils
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// Risk-level colour helpers  (self-contained — no :app dependency)
// ─────────────────────────────────────────────────────────────────────────────

internal fun RiskLevel.bgColor() = when (this) {
    RiskLevel.CRITICAL -> Color(0xFFB71C1C)
    RiskLevel.HIGH     -> Color(0xFFE53935)
    RiskLevel.MEDIUM   -> Color(0xFFF57C00)
    RiskLevel.LOW      -> Color(0xFFF9A825)
    RiskLevel.SAFE     -> Color(0xFF2E7D32)
}

private fun RiskLevel.onColor() = when (this) {
    RiskLevel.LOW -> Color.Black
    else          -> Color.White
}

private fun RiskLevel.label() = when (this) {
    RiskLevel.CRITICAL -> "CRITICAL"
    RiskLevel.HIGH     -> "HIGH RISK"
    RiskLevel.MEDIUM   -> "MEDIUM"
    RiskLevel.LOW      -> "LOW RISK"
    RiskLevel.SAFE     -> "SAFE"
}

// ─────────────────────────────────────────────────────────────────────────────
// RiskBadge  (self-contained pill chip)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun RiskBadge(level: RiskLevel, modifier: Modifier = Modifier, compact: Boolean = false) {
    Surface(
        color    = level.bgColor(),
        shape    = RoundedCornerShape(if (compact) 4.dp else 6.dp),
        modifier = modifier
    ) {
        Text(
            text          = level.label(),
            color         = level.onColor(),
            fontSize      = if (compact) 9.sp else 11.sp,
            fontWeight    = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp,
            modifier      = Modifier.padding(
                horizontal = if (compact) 5.dp else 8.dp,
                vertical   = if (compact) 2.dp else 3.dp
            )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// EmptyState  (self-contained full-screen placeholder)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier            = Modifier.padding(32.dp)
        ) {
            Icon(
                icon, contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint     = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                title,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign  = TextAlign.Center,
                color      = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                subtitle,
                style     = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen entry point
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CallHistoryScreen(
    onCallSelected: (CallEvent) -> Unit,
    onBack        : () -> Unit = {},
    viewModel     : CallHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(CallHistoryEvent.ClearError)
        }
    }

    CallHistoryContent(
        state          = state,
        snackbarHost   = snackbarHostState,
        onCallSelected = onCallSelected,
        onBack         = onBack,
        onEvent        = viewModel::onEvent
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure-UI
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CallHistoryContent(
    state         : CallHistoryState,
    snackbarHost  : SnackbarHostState = remember { SnackbarHostState() },
    onCallSelected: (CallEvent) -> Unit,
    onBack        : () -> Unit,
    onEvent       : (CallHistoryEvent) -> Unit
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Call History", fontWeight = FontWeight.Bold)
                        Text(
                            "${state.allCalls.size} calls",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                        )
                    }
                },
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
        Column(
            Modifier.fillMaxSize().padding(padding)
        ) {
            // ── Search bar ────────────────────────────────────────────────
            SearchTextField(
                query    = state.searchQuery,
                onChange = { onEvent(CallHistoryEvent.SearchQueryChanged(it)) },
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )

            // ── Filter chips ──────────────────────────────────────────────
            FilterRow(
                active   = state.activeFilter,
                counts   = buildFilterCounts(state.allCalls),
                onSelect = { onEvent(CallHistoryEvent.FilterChanged(it)) },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(8.dp))

            // ── Content ───────────────────────────────────────────────────
            AnimatedContent(
                targetState   = Triple(state.isLoading, state.calls.isEmpty(), state.calls),
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                label         = "history_content"
            ) { (loading, empty, calls) ->
                when {
                    loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    empty -> EmptyState(
                        icon     = Icons.Outlined.HistoryToggleOff,
                        title    = "No calls found",
                        subtitle = if (state.activeFilter != HistoryFilter.ALL)
                            "Try a different filter" else "Calls appear here after analysis"
                    )
                    else -> GroupedCallList(
                        calls          = calls,
                        onCallSelected = onCallSelected,
                        onDelete       = { onEvent(CallHistoryEvent.DeleteCall(it)) }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Search TextField
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTextField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value         = query,
        onValueChange = onChange,
        modifier      = modifier,
        placeholder   = { Text("Search by number or name…", style = MaterialTheme.typography.bodyMedium) },
        leadingIcon   = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
        trailingIcon  = {
            AnimatedVisibility(query.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, null, Modifier.size(18.dp)) }
            }
        },
        singleLine    = true,
        shape         = RoundedCornerShape(14.dp),
        colors        = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(0.4f)
        )
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Filter chips with counts
// ─────────────────────────────────────────────────────────────────────────────

private fun buildFilterCounts(calls: List<CallEvent>): Map<HistoryFilter, Int> = mapOf(
    HistoryFilter.ALL        to calls.size,
    HistoryFilter.FRAUD_ONLY to calls.count { it.fraudAnalysis?.isFraud == true },
    HistoryFilter.SAFE_ONLY  to calls.count { it.fraudAnalysis?.riskLevel == RiskLevel.SAFE },
    HistoryFilter.HIGH_RISK  to calls.count {
        it.fraudAnalysis?.riskLevel in listOf(RiskLevel.HIGH, RiskLevel.CRITICAL)
    }
)

@Composable
private fun FilterRow(
    active  : HistoryFilter,
    counts  : Map<HistoryFilter, Int>,
    onSelect: (HistoryFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier              = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(HistoryFilter.values()) { filter ->
            val count = counts[filter] ?: 0
            FilterChip(
                selected = filter == active,
                onClick  = { onSelect(filter) },
                label    = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(filter.label)
                        if (count > 0) {
                            Surface(
                                color = if (filter == active) MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.onSurface.copy(0.15f),
                                shape = CircleShape,
                                modifier = Modifier.size(18.dp)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(count.toString(), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                        color = if (filter == active) MaterialTheme.colorScheme.secondaryContainer
                                                else MaterialTheme.colorScheme.onSurface.copy(0.6f))
                                }
                            }
                        }
                    }
                },
                leadingIcon = if (filter == active) {
                    { Icon(Icons.Default.Check, null, Modifier.size(14.dp)) }
                } else null
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Grouped list  (calls grouped by calendar date)
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupedCallList(
    calls         : List<CallEvent>,
    onCallSelected: (CallEvent) -> Unit,
    onDelete      : (String) -> Unit
) {
    val dayFmt    = remember { SimpleDateFormat("EEEE, MMM d", Locale.getDefault()) }
    val todayCal  = Calendar.getInstance().also { it.set(Calendar.HOUR_OF_DAY, 0); it.set(Calendar.MINUTE, 0) }
    val yestCal   = Calendar.getInstance().also {
        it.set(Calendar.HOUR_OF_DAY, 0); it.set(Calendar.MINUTE, 0); it.add(Calendar.DAY_OF_YEAR, -1)
    }

    fun dayLabel(ts: Long): String {
        val cal = Calendar.getInstance().also { it.timeInMillis = ts }
        return when {
            cal.after(todayCal) -> "Today"
            cal.after(yestCal)  -> "Yesterday"
            else                -> dayFmt.format(Date(ts))
        }
    }

    val grouped = calls.groupBy { dayLabel(it.startTime) }

    LazyColumn(
        contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        grouped.forEach { (day, dayCalls) ->
            stickyHeader(key = day) {
                Box(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(vertical = 6.dp)
                ) {
                    Text(
                        day,
                        style      = MaterialTheme.typography.labelMedium,
                        color      = MaterialTheme.colorScheme.onSurface.copy(0.45f),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            items(dayCalls, key = { it.id }) { call ->
                SwipeToDeleteBox(
                    onDelete = { onDelete(call.id) }
                ) {
                    HistoryCallCard(call = call, onClick = { onCallSelected(call) })
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Swipe-to-delete wrapper
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteBox(
    onDelete: () -> Unit,
    content : @Composable () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { it == SwipeToDismissBoxValue.EndToStart }
    )

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
        }
    }

    SwipeToDismissBox(
        state            = dismissState,
        backgroundContent = {
            val bg by animateColorAsState(
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                    MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                label = "swipe_bg"
            )
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(bg),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.DeleteOutline, null,
                    tint     = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(end = 20.dp).size(24.dp)
                )
            }
        },
        enableDismissFromStartToEnd = false
    ) {
        content()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Individual call card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HistoryCallCard(call: CallEvent, onClick: () -> Unit) {
    val riskColor     = call.fraudAnalysis?.riskLevel?.bgColor() ?: MaterialTheme.colorScheme.primary
    val isFraud       = call.fraudAnalysis?.isFraud == true
    val directionIcon = if (call.direction == CallDirection.INBOUND) Icons.Default.CallReceived
                        else Icons.Default.CallMade

    Card(
        onClick   = onClick,
        shape     = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(0.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier  = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Avatar
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(riskColor.copy(0.13f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(directionIcon, null, tint = riskColor, modifier = Modifier.size(20.dp))
                }
                if (isFraud) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFFEF5350)))
                }
            }

            // Text
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    call.callerName ?: PhoneUtils.formatPhoneNumber(call.phoneNumber),
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 14.sp,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                if (call.callerName != null) {
                    Text(
                        PhoneUtils.formatPhoneNumber(call.phoneNumber),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (call.direction == CallDirection.INBOUND) Icons.Default.CallReceived
                        else Icons.Default.CallMade,
                        null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(0.4f)
                    )
                    Text(
                        "${DateUtils.formatDuration(call.durationMs)}  ·  ${formatTime(call.startTime)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.45f)
                    )
                }
            }

            // Right side
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                call.fraudAnalysis?.let { RiskBadge(it.riskLevel, compact = true) }
                if (call.recordingPath != null) {
                    Icon(Icons.Default.FiberManualRecord, null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(0.6f))
                }
            }
        }
    }
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))

// ─────────────────────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────────────────────

// Inline preview data — avoids importing PreviewData from :app
private val previewFraudCall = CallEvent(
    id = "p1", phoneNumber = "18005551234", callerName = null,
    startTime = System.currentTimeMillis() - 185_000,
    endTime   = System.currentTimeMillis() - 2_000,
    direction = CallDirection.INBOUND, state = CallState.ENDED,
    fraudAnalysis = FraudAnalysis(
        isFraud = true, confidence = 0.94f, riskLevel = RiskLevel.CRITICAL,
        flags = listOf(
            FraudFlag(FraudFlagType.SUSPICIOUS_KEYWORDS, "OTP request detected", 0.92f),
            FraudFlag(FraudFlagType.AI_DETECTED_MANIPULATION, "Urgency manipulation", 0.88f)
        )
    )
)
private val previewSafeCall = CallEvent(
    id = "p2", phoneNumber = "16505551234", callerName = "Mom",
    startTime = System.currentTimeMillis() - 7_200_000,
    endTime   = System.currentTimeMillis() - 7_080_000,
    direction = CallDirection.INBOUND, state = CallState.ENDED,
    fraudAnalysis = FraudAnalysis(false, 0.03f, RiskLevel.SAFE, emptyList())
)
private val previewCalls = listOf(previewFraudCall, previewSafeCall)

@Preview(name = "Call History – Light", showBackground = true)
@Composable
private fun CallHistoryLightPreview() {
    MaterialTheme {
        CallHistoryContent(
            state = CallHistoryState(
                allCalls     = previewCalls,
                calls        = previewCalls,
                activeFilter = HistoryFilter.ALL,
                isLoading    = false
            ),
            onCallSelected = {},
            onBack         = {},
            onEvent        = {}
        )
    }
}

@Preview(name = "Call History – Dark", showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CallHistoryDarkPreview() {
    MaterialTheme {
        CallHistoryContent(
            state = CallHistoryState(
                allCalls     = previewCalls,
                calls        = previewCalls.filter { it.fraudAnalysis?.isFraud == true },
                activeFilter = HistoryFilter.FRAUD_ONLY,
                isLoading    = false
            ),
            onCallSelected = {},
            onBack         = {},
            onEvent        = {}
        )
    }
}
