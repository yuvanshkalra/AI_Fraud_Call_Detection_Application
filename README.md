# FraudCall Detector

An Android application that monitors phone calls in real-time, records audio, transcribes speech, and runs AI-powered fraud detection — all with a clean modular architecture that lets teammates swap in a production AI model without touching any other module.

---

## Architecture overview

```
┌─────────────────────────────────────────────────────┐
│                      :app                           │
│  MainActivity · NavGraph · Screens · ViewModels     │
└─────────────┬───────────────────────────────────────┘
              │ depends on
   ┌──────────┼──────────────────────────────────┐
   │          │  Feature modules                 │
   │  :feature:callmonitor                       │
   │  :feature:audiorecorder                     │
   │  :feature:stt          ────────────────┐    │
   │  :feature:frauddetection  ◄────────────┘    │
   │  :feature:notifications                     │
   │  :feature:callhistory                       │
   └──────────┬──────────────────────────────────┘
              │ all depend on
   ┌──────────┼──────────────────────────────────┐
   │          │  Foundation layers               │
   │          ├── :domain  (pure Kotlin — no Android) │
   │          ├── :data    (Room, repository impls)   │
   │          └── :core    (network, DI, utils)       │
   └─────────────────────────────────────────────┘
```

### Call pipeline (data flow)

```
Phone call begins
      │
      ▼
:feature:callmonitor
  TelephonyStateListener / TelephonyCallback (API 31+)
  ↓ MonitoringEvent.CallStarted(callId, phoneNumber)
      │
      ▼
:feature:audiorecorder
  AudioCaptureManager subscribes to MonitoringEvent
  MediaRecorder → AudioChunk(callId, filePath, isFinal)
      │
      ▼
:feature:stt
  SttManagerImpl subscribes to AudioCaptureRepository.chunks
  SttEngine.analyse(chunk) → SttStreamEvent → liveTranscript
  On isFinal chunk → SttManagerState.CallComplete(callId, fullTranscript)
      │
      ▼
:feature:frauddetection
  FraudDetectionManagerImpl subscribes to SttManager.state
  FraudDetectionEngine.analyse(request) → FraudDetectionEngineResult
  → persist via FraudDetectionRepository
  → update CallRepository (call.fraudAnalysis)
  → FraudNotificationManager.showFraudAlert (HIGH+)
  → FraudDetectionState.Complete(callId, analysis) → UI
```

---

## Module guide

| Module | Purpose | Key classes |
|--------|---------|-------------|
| `:domain` | Pure Kotlin contracts — **no Android**. All interfaces live here. | `CallRepository`, `AudioCaptureRepository`, `SttManager`, `FraudDetectionManager`, `FraudDetectionEngine`, model classes |
| `:data` | Room database for call events. | `FraudCallDatabase`, `CallEventDao`, `CallRepositoryImpl` |
| `:core` | Retrofit client, base ViewModel, Hilt network module, utilities. | `NetworkClient`, `SafeApiCall`, `BaseViewModel` |
| `:feature:callmonitor` | Foreground service + TelephonyCallback that emit `MonitoringEvent` on the domain bus. | `CallMonitorService`, `TelephonyStateListener`, `CallLogResolver` |
| `:feature:audiorecorder` | Rotates `MediaRecorder` segments during a call; emits `AudioChunk`s. | `AudioCaptureManager`, `MediaRecorderAudioSource`, `MockAudioSource` |
| `:feature:stt` | Transcribes audio chunks; streams partial results to the UI. | `SttManagerImpl`, `MockSttEngine`, `RemoteSttEngine` |
| `:feature:frauddetection` | AI fraud analysis; auto-triggered after STT completes. | `FraudDetectionManagerImpl`, `MockFraudDetectionEngine`, `RemoteFraudDetectionEngine` |
| `:feature:notifications` | Risk-level-driven notification channels. | `FraudNotificationManager` |
| `:feature:callhistory` | Displays call history with fraud analysis badges. | `CallHistoryViewModel`, `CallHistoryScreen`, `CallDetailScreen` |
| `:app` | Navigation, screens, ViewModels, theme. | `NavGraph`, `ActiveCallViewModel`, `HomeViewModel` |

---

## Teammate integration guide

### Swapping in your AI model

All AI model integration points are in **`:feature:frauddetection`**. No other module needs to change.

#### Step 1 — Point to your API

Edit [`FraudDetectionModule.kt`](feature/frauddetection/src/main/java/com/fraudcalldetector/feature/frauddetection/di/FraudDetectionModule.kt):

```kotlin
private const val FRAUD_API_BASE_URL = "https://your-model-api.example.com/"
```

#### Step 2 — Verify the request/response contract

Your endpoint must accept `POST /v1/detect` with a JSON body:

```json
{
  "call_id": "abc-123",
  "transcript": "Please share your OTP immediately.",
  "caller_number": "+15551234567",
  "duration_ms": 45000,
  "is_final_pass": true
}
```

And respond with:

```json
{
  "is_fraud": true,
  "risk_score": 0.92,
  "risk_level": "HIGH",
  "reason": "Suspicious OTP request",
  "signals": [
    { "type": "SUSPICIOUS_KEYWORDS", "description": "OTP request detected", "severity": 0.45 }
  ]
}
```

Valid `risk_level` values: `SAFE`, `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`
Valid `signal.type` values: `SUSPICIOUS_KEYWORDS`, `AI_DETECTED_MANIPULATION`, `UNUSUAL_PATTERNS`, `KNOWN_SCAM_NUMBER`, `VOICE_PATTERN`

#### Step 3 — Switch the engine binding

In [`FraudDetectionModule.kt`](feature/frauddetection/src/main/java/com/fraudcalldetector/feature/frauddetection/di/FraudDetectionModule.kt), change:

```kotlin
// Before (development — no API calls)
@Binds abstract fun bindEngine(impl: MockFraudDetectionEngine): FraudDetectionEngine
```

to:

```kotlin
// After (production — calls your remote API)
@Binds abstract fun bindEngine(impl: RemoteFraudDetectionEngine): FraudDetectionEngine
```

That's it. The rest of the pipeline (STT → manager → persistence → notification → UI) is untouched.

---

### Swapping in your STT engine

Same pattern in **`:feature:stt`**:

1. Update `STT_API_BASE_URL` in [`SttModule.kt`](feature/stt/src/main/java/com/fraudcalldetector/feature/stt/di/SttModule.kt)
2. Change `@Binds` from `MockSttEngine` to `RemoteSttEngine`

Your endpoint must accept `POST /v1/transcribe` as multipart with:
- `audio` — the `.aac` / `.m4a` recording file
- `call_id`, `chunk_index`, `is_final` — metadata fields

And respond with:

```json
{
  "text": "Transcribed text here",
  "confidence": 0.97,
  "segments": [
    { "text": "Hello", "start_ms": 0, "end_ms": 500, "speaker": "CALLER" }
  ]
}
```

---

## Permissions reference

| Permission | Why needed | Runtime? |
|---|---|---|
| `READ_PHONE_STATE` | Detect call state (ringing / active / ended) | ✅ Yes |
| `READ_CALL_LOG` | Recover caller number and call direction | ✅ Yes |
| `RECORD_AUDIO` | Capture audio during a call | ✅ Yes |
| `POST_NOTIFICATIONS` | Show fraud alert notifications (API 33+) | ✅ Yes |
| `CALL_PHONE` | Initiate calls from history screen | ✅ Yes |
| `INTERNET` | Send audio/transcript to AI APIs | No (install-time) |
| `FOREGROUND_SERVICE_PHONE_CALL` | Keep monitoring service alive during calls | No (install-time) |
| `RECEIVE_BOOT_COMPLETED` | Restart monitoring after device reboot | No (install-time) |

> `PROCESS_OUTGOING_CALLS` was removed intentionally — it was deprecated in API 29 and removed from enforcement in API 33. Outgoing calls are detected via `CALL_STATE_OFFHOOK` + `CallLog`.

---

## Tech stack

| Layer | Library |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Clean Architecture |
| DI | Hilt 2.51 |
| Async | Kotlin Coroutines + Flow |
| Networking | Retrofit 2 + OkHttp 4 + Gson |
| Local DB | Room 2.6 |
| Navigation | Navigation Compose |
| Testing | JUnit 4 + MockK + Turbine + coroutines-test |
| Logging | Timber |

---

## Build & run

```bash
# Clone the repo
git clone https://github.com/your-org/fraud-call-detector.git
cd fraud-call-detector

# Run unit tests
./gradlew test

# Build debug APK
./gradlew assembleDebug
```

> **Note:** The app requires a physical device for full call-monitoring and audio-recording functionality. All AI engines fall back to mock implementations in development so the app runs without a remote API.

---

## Project structure

```
fraud call detection/
├── app/                        # Navigation, screens, themes
├── core/                       # Retrofit, base VM, utilities
├── data/                       # Room DB (call events)
├── domain/                     # Pure Kotlin: models, interfaces, use-cases
└── feature/
    ├── audiorecorder/          # MediaRecorder audio capture
    ├── callhistory/            # Call history UI
    ├── callmonitor/            # Telephony listener + foreground service
    ├── frauddetection/         # AI fraud analysis pipeline
    ├── notifications/          # Notification channels
    └── stt/                    # Speech-to-text pipeline
```
