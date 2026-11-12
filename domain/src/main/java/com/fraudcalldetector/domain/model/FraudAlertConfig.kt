package com.fraudcalldetector.domain.model

/**
 * Configures when the fraud-detection pipeline fires alerts and stops processing.
 *
 * Injected into [FraudDetectionManager] so callers can tune sensitivity without
 * rebuilding the engine or changing the notification channel.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * Threshold semantics
 * ─────────────────────────────────────────────────────────────────────────────
 *  • [alertThreshold]  — confidence score (0.0–1.0) above which a real-time
 *    per-chunk alert is fired immediately during the call.
 *  • [minRiskLevelForAlert] — categorical floor; even if confidence exceeds
 *    [alertThreshold] a notification is suppressed below this level.
 *    Default: [RiskLevel.MEDIUM] (alerts on MEDIUM, HIGH, CRITICAL).
 *  • [alertOnFinalPassOnly] — when true, skip per-chunk alerts and only fire
 *    after the full call transcript is analysed.  Use when the AI model is
 *    batch-only and incremental results are unreliable.
 */
data class FraudAlertConfig(
    /**
     * Confidence score threshold in [0.0, 1.0].
     * An alert fires when [FraudAnalysis.confidence] >= this value AND
     * [FraudAnalysis.riskLevel] >= [minRiskLevelForAlert].
     *
     * Default: 0.60 (60% confidence = alert).
     */
    val alertThreshold       : Float     = DEFAULT_THRESHOLD,

    /**
     * Minimum [RiskLevel] required to trigger an alert.
     * Acts as a categorical floor over the numeric [alertThreshold].
     *
     * Default: [RiskLevel.MEDIUM].
     */
    val minRiskLevelForAlert : RiskLevel = RiskLevel.MEDIUM,

    /**
     * When true, per-chunk incremental scoring is performed but alerts are
     * deferred until the final [SttManagerState.CallComplete] event.
     *
     * Default: false (alert as soon as any chunk exceeds the threshold).
     */
    val alertOnFinalPassOnly : Boolean   = false
) {
    companion object {
        const val DEFAULT_THRESHOLD = 0.60f

        /** Strict — only CRITICAL-confidence calls generate alerts. */
        val STRICT = FraudAlertConfig(alertThreshold = 0.85f, minRiskLevelForAlert = RiskLevel.HIGH)

        /** Permissive — surface any suspicious signal immediately. */
        val PERMISSIVE = FraudAlertConfig(alertThreshold = 0.40f, minRiskLevelForAlert = RiskLevel.LOW)

        /** Batch-only — wait for the full transcript before alerting. */
        val FINAL_PASS_ONLY = FraudAlertConfig(alertOnFinalPassOnly = true)

        val DEFAULT = FraudAlertConfig()
    }
}
