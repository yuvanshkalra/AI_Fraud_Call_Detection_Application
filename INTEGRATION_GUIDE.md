# FraudCall Detector — Teammate Integration Guide

This document is the single reference for integrating your AI/ML components into the
FraudCall Detector app.  It covers the two primary integration points (STT engine and
Fraud Detection engine), authentication, configurable alert thresholds, the full data
contracts, known limitations, and a quick-start checklist.

---

## Table of contents

1. [Architecture recap](#1-architecture-recap)
2. [Integration point A — Fraud Detection AI](#2-integration-point-a--fraud-detection-ai)
3. [Integration point B — Speech-to-Text engine](#3-integration-point-b--speech-to-text-engine)
4. [Authentication](#4-authentication)
5. [Configuring alert thresholds](#5-configuring-alert-thresholds)
6. [How the alert system works (end-to-end)](#6-how-the-alert-system-works-end-to-end)
7. [Domain model reference](#7-domain-model-reference)
8. [Known limitations & accepted gaps](#8-known-limitations--accepted-gaps)
9. [Quick-start checklist](#9-quick-start-checklist)

---

## 1. Architecture recap

```
Phone call begins
      │
      ▼
:feature:callmonitor          → MonitoringEvent.CallStarted(callId, phoneNumber)
      │
      ▼
:feature:audiorecorder        → AudioChunk(callId, filePath, chunkIndex, isFinal)
      │
      ▼
:feature:stt                  → SttEngine  ← ★ INTEGRATION POINT B
  SttManagerImpl.analyse()
  liveTranscript / SttManagerState.CallComplete(fullTranscript)
      │
      ▼
:feature:frauddetection       → FraudDetectionEngine  ← ★ INTEGRATION POINT A
  FraudDetectionManagerImpl.runAnalysis()
  ├─ incremental: FraudDetectionState.IncrementalResult → UI live badge
  └─ final:       FraudDetectionState.Complete → persist + notification + history
      │
      ▼
:feature:notifications
  FraudNotificationManager.showFraudAlert()   → system notification
  AlertHistoryRepositoryImpl.addAlert()       → in-app history
      │
      ▼
:app
  ActiveCallScreen  — FraudAlertBanner (in-app banner, real-time)
  FraudWarningScreen — full fraud detail view (tap notification or banner)
  AlertHistoryScreen — browsable alert history
```

The app is **fully functional without either remote API** — both engines have mock
implementations that work offline.  You can run the app, exercise all screens, and
observe the complete notification/alert flow before your API is ready.

---

## 2. Integration point A — Fraud Detection AI

### What you need to implement

Your backend must expose a single REST endpoint:

```
POST  /v1/detect
Content-Type: application/json
```

### Request body

```json
{
  "call_id":    "a1b2c3d4",
  "transcript": "Please verify your identity by sharing the one-time password.",
  "language":   "en-US",
  "metadata": {
    "caller_number": "+15551234567",
    "duration_ms":   42000,
    "chunk_index":   2,
    "is_final":      true
  }
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `call_id` | string | ✅ | Stable per-call UUID |
| `transcript` | string | ✅ | Accumulated text (all chunks so far) |
| `language` | string | no | BCP-47, default `"en-US"` |
| `metadata.caller_number` | string | no | E.164 phone number when available |
| `metadata.duration_ms` | int | no | Call duration so far (ms) |
| `metadata.chunk_index` | int | no | Audio chunk counter (0-based) |
| `metadata.is_final` | bool | no | `true` on the last chunk of the call |

### Response body (HTTP 200)

```json
{
  "is_fraud":      true,
  "risk_score":    0.92,
  "risk_level":    "HIGH",
  "reason":        "Suspicious OTP request with urgency language",
  "signals": [
    {
      "type":        "SUSPICIOUS_KEYWORDS",
      "description": "OTP keyword with urgency pattern",
      "severity":    0.88
    },
    {
      "type":        "AI_DETECTED_MANIPULATION",
      "description": "High-pressure social engineering",
      "severity":    0.61
    }
  ],
  "model_version": "v2.1.0"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `is_fraud` | bool | ✅ | Whether the call is classified as fraud |
| `risk_score` | float | ✅ | Confidence 0.0–1.0 |
| `risk_level` | string | ✅ | `SAFE` \| `LOW` \| `MEDIUM` \| `HIGH` \| `CRITICAL` |
| `reason` | string | ✅ | Human-readable primary reason (shown in notification) |
| `signals` | array | no | Detailed signal breakdown (shown in expanded notification and detail screen) |
| `signals[].type` | string | no | One of the `FraudFlagType` values — see §7 |
| `signals[].description` | string | ✅ if signals | Short description for the user |
| `signals[].severity` | float | ✅ if signals | 0.0–1.0 severity weight |
| `model_version` | string | no | Logged for traceability |

### Error responses

| HTTP code | Meaning | App behaviour |
|---|---|---|
| 4xx | Bad request / auth failure | `FraudDetectionEngineException(retryable=false)` — engine logs, manager falls back to `SAFE` |
| 5xx | Server error | `FraudDetectionEngineException(retryable=true)` — manager logs but does not retry automatically |
| Network timeout | No response | Same as 5xx — manager returns `SAFE` fallback and logs |

The app **never crashes** on a failing AI endpoint.  Fraudulent calls are silently
classified as `SAFE` on engine errors — make sure your server is reliable for high
risk-level classifications.

### How to switch from mock to production

**Step 1 — Set your base URL**

Open [`FraudDetectionModule.kt`](feature/frauddetection/src/main/java/com/fraudcalldetector/feature/frauddetection/di/FraudDetectionModule.kt):

```kotlin
// line 43 — FraudDetectionNetworkModule
private const val FRAUD_API_BASE_URL = "https://your-model-api.example.com/"
```

**Step 2 — Change the Hilt binding**

In the same file, in `FraudDetectionEngineModule` (~line 91):

```kotlin
// Before — mock (no network calls)
@Binds
@Singleton
abstract fun bindFraudDetectionEngine(impl: MockFraudDetectionEngine): FraudDetectionEngine

// After — production (calls your endpoint)
@Binds
@Singleton
abstract fun bindFraudDetectionEngine(impl: RemoteFraudDetectionEngine): FraudDetectionEngine
```

That is the **only change required**.  No other file needs to be touched.

### Incremental (per-chunk) scoring

The pipeline calls your endpoint **once per audio chunk** (roughly every 10–15 s during
a live call) with `is_final = false` and the accumulated transcript to date.  This
drives the live risk badge in `ActiveCallScreen`.

If your model only supports **batch analysis** (full transcript at the end), set:

```kotlin
override val supportsIncrementalAnalysis: Boolean = false
```

in your engine, and configure the alert threshold to fire on the final pass only (see §5).

---

## 3. Integration point B — Speech-to-Text engine

### What you need to implement

```
POST  /v1/transcribe
Content-Type: multipart/form-data
```

### Request parts

| Part name | Type | Notes |
|---|---|---|
| `audio` | binary file | The `.m4a` / `.aac` audio chunk recorded by the device |
| `call_id` | string | Stable per-call UUID |
| `chunk_index` | string (int) | 0-based chunk counter |
| `is_final` | string (bool) | `"true"` on the last chunk of the call |
| `language` | string | BCP-47, default `"en-US"` |

### Response body (HTTP 200)

```json
{
  "text": "Please share your one-time password immediately.",
  "confidence": 0.97,
  "segments": [
    {
      "id":          "seg-001",
      "text":        "Please share your one-time password immediately.",
      "speaker":     "CALLER",
      "start_ms":    0,
      "end_ms":      3200,
      "confidence":  0.97
    }
  ],
  "words": [
    { "word": "Please",    "start_ms": 0,    "end_ms": 320,  "confidence": 0.99, "speaker": "CALLER" },
    { "word": "share",     "start_ms": 330,  "end_ms": 580,  "confidence": 0.98, "speaker": "CALLER" }
  ],
  "detected_language": "en-US",
  "audio_duration_ms": 3200,
  "overall_confidence": 0.97
}
```

`segments` and `words` are optional but strongly recommended — they enable
speaker-labelled transcripts in the active-call screen and per-word fraud signal
highlighting in future versions.

Valid `speaker` values: `CALLER` | `USER` | `UNKNOWN`

### How to switch from mock to production

**Step 1 — Set your base URL**

Open [`SttModule.kt`](feature/stt/src/main/java/com/fraudcalldetector/feature/stt/di/SttModule.kt):

```kotlin
// line 42 — SttNetworkModule
private const val STT_BASE_URL = "https://api.your-stt-backend.com/"
```

**Step 2 — Change the Hilt binding**

In the same file, in `SttEngineModule` (~line 96):

```kotlin
// Before — mock (deterministic fake, no network)
@Binds
@Singleton
abstract fun bindSttEngine(impl: MockSttEngine): SttEngine

// After — production (calls your remote endpoint)
@Binds
@Singleton
abstract fun bindSttEngine(impl: RemoteSttEngine): SttEngine
```

Again — that is the **only change required**.

---

## 4. Authentication

API keys / Bearer tokens are injected by
[`AuthInterceptor.kt`](core/src/main/java/com/fraudcalldetector/core/network/AuthInterceptor.kt)
and applied to **every** outbound OkHttp request (both the STT and Fraud Detection
Retrofit clients share the same `NetworkClient`).

Uncomment and fill in the header:

```kotlin
class AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer $YOUR_API_KEY")
            // or .header("X-Api-Key", YOUR_API_KEY) — depends on your backend
            .build()
        return chain.proceed(request)
    }
}
```

> **Security note:** Never hard-code API keys in source.  Read them from
> `BuildConfig` (set via `local.properties`) or Android `EncryptedSharedPreferences` /
> Jetpack DataStore.

---

## 5. Configuring alert thresholds

Alert thresholds are defined in
[`FraudAlertConfig`](domain/src/main/java/com/fraudcalldetector/domain/model/FraudAlertConfig.kt)
and persisted to `SharedPreferences` by
[`AlertHistoryRepositoryImpl`](feature/notifications/src/main/java/com/fraudcalldetector/feature/notifications/AlertHistoryRepositoryImpl.kt).

Users can change thresholds at runtime from `AlertHistoryScreen` (Settings panel).

### Config fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `alertThreshold` | Float | `0.60` | Minimum `risk_score` (0.0–1.0) to fire an alert |
| `minRiskLevelForAlert` | `RiskLevel` | `MEDIUM` | Categorical floor — even if `risk_score` exceeds `alertThreshold`, no alert fires below this level |
| `alertOnFinalPassOnly` | Boolean | `false` | Skip mid-call incremental alerts; only alert on the final full-transcript pass |

### Pre-built presets

```kotlin
FraudAlertConfig.DEFAULT        // threshold 0.60, minimum MEDIUM
FraudAlertConfig.STRICT         // threshold 0.85, minimum HIGH
FraudAlertConfig.PERMISSIVE     // threshold 0.40, minimum LOW
FraudAlertConfig.FINAL_PASS_ONLY // wait for call end before alerting
```

### When an alert fires

`FraudDetectionManagerImpl.maybeFireAlert()` checks **all four conditions** before
posting a notification:

1. `analysis.confidence >= config.alertThreshold`
2. `analysis.riskLevel >= config.minRiskLevelForAlert`
3. `alertOnFinalPassOnly == false` **or** this is the final analysis pass
4. No alert has already fired for this `callId` (deduplication — one alert per call)

---

## 6. How the alert system works (end-to-end)

### Notification channels

| Channel ID | Name | Importance | Used for |
|---|---|---|---|
| `fraud_alerts_critical` | Fraud Alerts — Critical | HIGH | CRITICAL + HIGH risk |
| `fraud_alerts_medium` | Fraud Alerts — Medium | DEFAULT | MEDIUM risk |
| `fraud_alerts_low` | Fraud Alerts — Low | LOW | LOW risk |
| `call_status` | Call Monitor Status | LOW | Foreground-service persistent notification |

### Notification anatomy

```
┌──────────────────────────────────────────────────┐
│  ⚠️ FRAUD CALL DETECTED                          │  ← headlineForLevel(riskLevel)
│  +15551234567 · 92% confidence                   │  ← collapsed body
│ ─────────────────────────────────────────────────│
│  Caller: John Unknown (+15551234567)             │  ← expanded BigText
│  Risk Level: HIGH                                │
│  Confidence: 92%                                 │
│                                                  │
│  Reason: Suspicious OTP request                  │
│  Detected signals:                               │
│    • OTP keyword with urgency pattern (88%)      │
│    • High-pressure social engineering (61%)      │
│                                                  │
│  [ View Details ]    [ Dismiss ]                 │  ← action buttons
└──────────────────────────────────────────────────┘
```

### User interaction flows

**"View Details" tapped (from notification shade)**

1. `FraudNotificationManager.buildDeepLinkIntent()` fires an explicit `Intent` to
   `MainActivity` with extras `EXTRA_CALL_ID` and `EXTRA_NAVIGATE = "fraud_warning"`.
2. `MainActivity.onNewIntent()` reads the extras and navigates to `FraudWarningScreen`.
3. `FraudWarningViewModel` loads the `CallEvent` from `CallRepository` by `callId`.
4. `AlertHistoryRepository.updateStatus(callId, VIEWED)` is called — unread badge clears.

**"Dismiss" tapped (from notification shade)**

1. `NotificationDismissReceiver` (Hilt `@AndroidEntryPoint`) receives
   `ACTION_DISMISS = "com.fraudcalldetector.notifications.DISMISS_ALERT"`.
2. System notification is cancelled via `NotificationManagerCompat.cancel(notifId)`.
3. `AlertHistoryRepository.updateStatus(callId, DISMISSED)` is called via
   `goAsync()` coroutine — status is updated even if the app is not in the foreground.

**In-app banner dismiss (while call is active)**

1. User taps ✕ on `FraudAlertBanner` in `ActiveCallScreen`.
2. `ActiveCallViewModel.dismissFraudBanner()` is called.
3. `FraudNotificationManager.dismissFraudAlert(callId)` cancels the system notification.
4. `AlertHistoryRepository.updateStatus(callId, DISMISSED)` is called.

**In-app "View Details" (while call is active)**

1. User taps "View Details" on `FraudAlertBanner`.
2. `ActiveCallViewModel.markAlertViewed()` is called.
3. Same notification cancel + `updateStatus(callId, VIEWED)`.
4. Navigation to `FraudWarningScreen` passes `preloadedCall` (no DB round-trip needed).

### Alert history

`AlertHistoryRepositoryImpl` maintains an **in-memory list** (newest-first, capped at
100 entries) backed by `SharedPreferences` for the `FraudAlertConfig` only.

Alert records survive screen rotation and process death within the same session, but
**do not survive app reinstall or process kill** — this is an accepted MVP limitation
(see §8).

Each `AlertRecord` has three lifecycle states:

| Status | Meaning |
|---|---|
| `ACTIVE` | Notification is still in the shade; user has not interacted |
| `DISMISSED` | User tapped "Dismiss" in the shade or the banner |
| `VIEWED` | User opened the in-app detail screen |

---

## 7. Domain model reference

### `RiskLevel` enum

```
SAFE < LOW < MEDIUM < HIGH < CRITICAL
```

The `>=` operator is defined on this enum — threshold comparisons are safe.

### `FraudFlagType` enum

| Value | Typical use |
|---|---|
| `SUSPICIOUS_KEYWORDS` | Keyword match (OTP, SSN, CVV, gift card, …) |
| `UNUSUAL_PATTERNS` | Statistical anomaly (unusual call duration, rapid delivery) |
| `KNOWN_SCAM_NUMBER` | Phone number matches a known scam database |
| `AI_DETECTED_MANIPULATION` | Manipulation / social-engineering pattern |
| `RAPID_SPEECH` | Unusually fast speech rate (urgency tactic) |
| `VOICE_SPOOFING` | Voice cloning / deepfake signal |

If your model uses signal types not in this list, pass the string value anyway — the
mapper in `FraudDetectionManagerImpl` falls back to `SUSPICIOUS_KEYWORDS` for unknown
strings and preserves your `description` and `severity`.

### `FraudDetectionRequest` fields

Passed from `FraudDetectionManagerImpl` to `FraudDetectionEngine.analyse()`:

```kotlin
data class FraudDetectionRequest(
    val callId        : String,          // stable UUID per call
    val transcript    : String,          // accumulated full transcript
    val language      : String,          // "en-US"
    val audioFilePath : String?,         // absolute path to last audio chunk
    val callerNumber  : String?,         // E.164 phone number
    val durationMs    : Long,            // call duration so far (ms)
    val chunkIndex    : Int,             // 0-based chunk counter
    val isFinalPass   : Boolean,         // true on call-end analysis
    val extraContext  : Map<String,String> // model-specific extras
)
```

### `FraudDetectionEngineResult` fields

Your engine must return:

```kotlin
data class FraudDetectionEngineResult(
    val isFraud      : Boolean,
    val riskScore    : Float,            // 0.0–1.0
    val riskLevel    : String,           // "SAFE"|"LOW"|"MEDIUM"|"HIGH"|"CRITICAL"
    val reason       : String,
    val signals      : List<FraudSignal> = emptyList(),
    val modelVersion : String            = "unknown",
    val analysedAtMs : Long              = System.currentTimeMillis()
)
```

---

## 8. Known limitations & accepted gaps

| Item | Detail | Impact |
|---|---|---|
| Alert history not persisted across app reinstall | `AlertHistoryRepositoryImpl` is in-memory; `SharedPreferences` only stores the config | Low — alerts are ephemeral UX signals |
| `FraudFlag` details lost on cold DB restart | `CallEventEntity.toDomain()` deserialises `fraudAnalysis.flags` as `emptyList()` | Low — fraud result metadata (isFraud, riskLevel, confidence) is preserved; flag detail is not |
| No retry on engine failure | `FraudDetectionManagerImpl` does not retry after `FraudDetectionEngineException` | Medium — implement retry with exponential back-off in your engine if needed |
| `READ_CALL_LOG` permission may be denied | Caller name/number resolution falls back to the raw number string | Low |
| Audio capture requires physical device | `AudioCaptureManager` uses `MediaRecorder` — emulator audio capture does not work | N/A for production |
| Mock STT generates synthetic dialogue | `MockSttEngine` cycles through 5 hardcoded fraud scripts — disable for real-call testing | N/A for production |

---

## 9. Quick-start checklist

```
□ 1. Implement POST /v1/detect on your server
      — Request: { call_id, transcript, language, metadata }
      — Response: { is_fraud, risk_score, risk_level, reason, signals?, model_version? }

□ 2. Set FRAUD_API_BASE_URL in FraudDetectionModule.kt

□ 3. Switch the Hilt binding:
      MockFraudDetectionEngine  →  RemoteFraudDetectionEngine

□ 4. (Optional) Implement POST /v1/transcribe on your STT server
      — Request: multipart { audio, call_id, chunk_index, is_final, language }
      — Response: { text, confidence, segments?, words? }

□ 5. (Optional) Set STT_BASE_URL in SttModule.kt

□ 6. (Optional) Switch the Hilt binding:
      MockSttEngine  →  RemoteSttEngine

□ 7. Set your API key in AuthInterceptor.kt
      .header("Authorization", "Bearer $YOUR_KEY")

□ 8. Build & run on a physical device
      ./gradlew assembleDebug

□ 9. Make a real inbound call — watch Logcat for:
      FraudDetectionManager: fraud alert fired & recorded callId=… riskLevel=… confidence=…
```

---

*Questions?  Open an issue in the repo or ping the Android team.*
