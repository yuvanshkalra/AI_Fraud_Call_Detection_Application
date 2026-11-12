package com.fraudcalldetector.feature.callhistory.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fraudcalldetector.domain.model.CallDirection
import com.fraudcalldetector.domain.model.CallEvent
import com.fraudcalldetector.domain.model.FraudFlag
import com.fraudcalldetector.domain.model.RiskLevel
import com.fraudcalldetector.core.utils.DateUtils
import com.fraudcalldetector.core.utils.PhoneUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDetailScreen(
    call          : CallEvent,
    onBack        : () -> Unit,
    onFraudWarning: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Call Details") },
                actions = {
                    if (call.fraudAnalysis?.isFraud == true) {
                        TextButton(onClick = onFraudWarning) {
                            Text("Fraud Report", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header card
            CallHeaderCard(call)

            // Fraud analysis card
            call.fraudAnalysis?.let { analysis ->
                FraudAnalysisCard(analysis = analysis)
            }

            // Transcript card
            call.transcript?.let { transcript ->
                TranscriptCard(transcript = transcript)
            }
        }
    }
}

@Composable
private fun CallHeaderCard(call: CallEvent) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (call.direction == CallDirection.INBOUND)
                        Icons.Default.CallReceived else Icons.Default.CallMade,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = call.callerName ?: "Unknown",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Text(
                        text = PhoneUtils.formatPhoneNumber(call.phoneNumber),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            InfoRow(
                icon = Icons.Default.AccessTime,
                label = "Started",
                value = DateUtils.formatDateTime(call.startTime)
            )
            InfoRow(
                icon = Icons.Default.Timer,
                label = "Duration",
                value = DateUtils.formatDuration(call.durationMs)
            )
            InfoRow(
                icon = Icons.Default.CallEnd,
                label = "Status",
                value = call.state.name
            )
            call.recordingPath?.let {
                InfoRow(
                    icon = Icons.Default.FiberManualRecord,
                    label = "Recording",
                    value = "Available"
                )
            }
        }
    }
}

@Composable
private fun FraudAnalysisCard(analysis: com.fraudcalldetector.domain.model.FraudAnalysis) {
    val (bgColor, textColor) = when (analysis.riskLevel) {
        RiskLevel.CRITICAL, RiskLevel.HIGH -> Pair(Color(0xFFFFEBEE), Color(0xFFB00020))
        RiskLevel.MEDIUM -> Pair(Color(0xFFFFF3E0), Color(0xFFE65100))
        RiskLevel.LOW -> Pair(Color(0xFFFFFDE7), Color(0xFFE65100))
        RiskLevel.SAFE -> Pair(Color(0xFFE8F5E9), Color(0xFF1B5E20))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "AI Fraud Analysis",
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    fontSize = 16.sp
                )
                RiskBadge(level = analysis.riskLevel)
            }

            Spacer(Modifier.height(12.dp))

            // Confidence meter
            Text(
                "Confidence: ${(analysis.confidence * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = textColor
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { analysis.confidence },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = textColor,
                trackColor = textColor.copy(alpha = 0.2f)
            )

            if (analysis.flags.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Detected Signals",
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                analysis.flags.forEach { flag ->
                    FraudFlagRow(flag = flag, textColor = textColor)
                }
            }
        }
    }
}

@Composable
private fun FraudFlagRow(flag: FraudFlag, textColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = textColor
        )
        Spacer(Modifier.width(8.dp))
        Text(
            flag.description,
            style = MaterialTheme.typography.bodySmall,
            color = textColor
        )
    }
}

@Composable
private fun TranscriptCard(transcript: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Transcribe, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Transcript", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = transcript,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.width(80.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
