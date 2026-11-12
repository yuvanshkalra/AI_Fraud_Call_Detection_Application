package com.fraudcalldetector.ui.preview

import com.fraudcalldetector.domain.model.*

/**
 * Shared mock data used by all @Preview composables and mock ViewModels.
 * Never imported in production code paths.
 */
object PreviewData {

    val fraudCall = CallEvent(
        id = "1",
        phoneNumber = "18005551234",
        callerName = null,
        startTime = System.currentTimeMillis() - 185_000,
        endTime   = System.currentTimeMillis() - 2_000,
        direction = CallDirection.INBOUND,
        state     = CallState.ENDED,
        transcript= "Hello, this is Amazon support. Your account has been compromised. " +
                    "Please provide your social security number immediately to avoid suspension.",
        fraudAnalysis = FraudAnalysis(
            isFraud    = true,
            confidence = 0.94f,
            riskLevel  = RiskLevel.CRITICAL,
            flags      = listOf(
                FraudFlag(FraudFlagType.SUSPICIOUS_KEYWORDS,  "Keywords: 'account compromised', 'social security'", 0.92f),
                FraudFlag(FraudFlagType.AI_DETECTED_MANIPULATION, "Urgency manipulation pattern detected", 0.88f),
                FraudFlag(FraudFlagType.KNOWN_SCAM_NUMBER,    "Number matches known scam database",                0.97f),
                FraudFlag(FraudFlagType.RAPID_SPEECH,         "Unusually rapid speech cadence",                    0.71f)
            )
        )
    )

    val highRiskCall = CallEvent(
        id = "2",
        phoneNumber = "14155558888",
        callerName = "Unknown Caller",
        startTime = System.currentTimeMillis() - 3_600_000,
        endTime   = System.currentTimeMillis() - 3_540_000,
        direction = CallDirection.INBOUND,
        state     = CallState.ENDED,
        transcript= "We are calling about your car's extended warranty…",
        fraudAnalysis = FraudAnalysis(
            isFraud    = true,
            confidence = 0.78f,
            riskLevel  = RiskLevel.HIGH,
            flags      = listOf(
                FraudFlag(FraudFlagType.SUSPICIOUS_KEYWORDS, "Warranty scam keywords detected", 0.80f),
                FraudFlag(FraudFlagType.UNUSUAL_PATTERNS,    "Robocall pattern signature", 0.74f)
            )
        )
    )

    val mediumRiskCall = CallEvent(
        id = "3",
        phoneNumber = "12025559012",
        callerName  = "IRS Notice",
        startTime   = System.currentTimeMillis() - 86_400_000,
        endTime     = System.currentTimeMillis() - 86_340_000,
        direction   = CallDirection.INBOUND,
        state       = CallState.ENDED,
        fraudAnalysis = FraudAnalysis(
            isFraud    = false,
            confidence = 0.41f,
            riskLevel  = RiskLevel.MEDIUM,
            flags      = listOf(
                FraudFlag(FraudFlagType.UNUSUAL_PATTERNS, "Caller claims to be government agency", 0.55f)
            )
        )
    )

    val safeCall = CallEvent(
        id = "4",
        phoneNumber = "16505551234",
        callerName  = "Mom",
        startTime   = System.currentTimeMillis() - 7_200_000,
        endTime     = System.currentTimeMillis() - 7_080_000,
        direction   = CallDirection.INBOUND,
        state       = CallState.ENDED,
        fraudAnalysis = FraudAnalysis(
            isFraud    = false,
            confidence = 0.03f,
            riskLevel  = RiskLevel.SAFE,
            flags      = emptyList()
        )
    )

    val outgoingCall = CallEvent(
        id = "5",
        phoneNumber = "18888675309",
        callerName  = "Jenny",
        startTime   = System.currentTimeMillis() - 600_000,
        endTime     = System.currentTimeMillis() - 480_000,
        direction   = CallDirection.OUTBOUND,
        state       = CallState.ENDED,
        fraudAnalysis = FraudAnalysis(false, 0.02f, RiskLevel.SAFE, emptyList())
    )

    val activeCall = CallEvent(
        id = "6",
        phoneNumber = "12125551099",
        callerName  = null,
        startTime   = System.currentTimeMillis() - 45_000,
        endTime     = null,
        direction   = CallDirection.INBOUND,
        state       = CallState.ACTIVE,
        fraudAnalysis = null
    )

    val allCalls = listOf(fraudCall, highRiskCall, mediumRiskCall, safeCall, outgoingCall)
}
