# AGENTS.md — AI Coding Agent & Developer Guide for Tupo Vision TR

This file provides complete architectural context, non-negotiable invariants, build/test commands, and contribution rules for **Codex**, **Claude Code**, **Antigravity**, and human contributors working on `Tupo Vision TR`.

## 1. Project Overview & Identity

- **Repository:** Fork of `KeonyRus/tupo_vision` tailored for blind and low-vision users (Turkish-first UI + TalkBack / Jieshuo screen reader compatibility, zero-cloud-latency offline ML Kit OCR/barcodes, and clean 48 kHz A2DP audio separation).
- **Application ID:** `com.senovali.tupovision` (distinct from upstream so both can be installed side by side; Kotlin package remains `com.keonyrus.tupovision`).
- **Target Hardware & SDKs:** Ray-Ban Meta smart glasses (Gen 1-3), Android 12+ (`minSdk = 31`, `compileSdk = 36`, `targetSdk = 36`), Meta Wearables DAT SDK `1.1.0` (`mwdat-core`, `mwdat-camera`, `mwdat-inputs`, debug-only `mwdat-mockdevice`), Google ML Kit bundled `text-recognition:16.0.1` and `barcode-scanning:17.3.0`.
- **No external gateway / server:** Audio, camera frames, and tokens go directly between the Android device and OpenAI (`/v1/responses` via ChatGPT OAuth PKCE), Google (Gemini Live WebSocket), or on-device ML Kit.

## 2. File Map

All main Kotlin sources live under `app/src/main/java/com/keonyrus/tupovision/`:

- `App.kt`: Application entry point calling `Engine.attach(this)`.
- `Engine.kt`: Central orchestrator, `UiState` `StateFlow`, `SharedPreferences` persistence, ChatGPT/Gemini Live turn loops, `liveAttempt` fallback ladder, and `LocalVision` offline fallback trigger.
- `Voice.kt`: `Stt` (Android `SpeechRecognizer`), `Tts` (`UtteranceProgressListener`, `isBusy`, `awaitIdle`), `AudioRouter` (`AudioProfile.HD_A2DP` vs `AudioProfile.GLASSES_SCO`), and `SentenceChunker`.
- `MediaWatch.kt`: Standby media-session trigger and serialized `EarconPlayer` (24 kHz PCM earcons via `AudioTrack`).
- `GeminiLive.kt`: Direct WebSocket client for Gemini Live (`16 kHz` mic upload, `24 kHz` PCM playback, `1 FPS` JPEG frame upload with `1.5 MiB` queue cap, `echoTailGuardMs` echo suppression).
- `Responses.kt`: Shared `OkHttp 4.12.0` HTTP/2 connection-pooled SSE client for OpenAI Responses API + `parseSseEventLine`.
- `Frames.kt`: YUV/I420, Bitmap, and HEIC JPEG conversion and fast scaling (`FAST_JPEG_QUALITY`, `FAST_HD_MAX_SIDE`, `preview`).
- `LocalVision.kt`: Lazy on-device bundled ML Kit OCR + barcode scanner (`Dispatchers.Default`, `suspendCancellableCoroutine`, `bitmap.recycle()`), plus optional non-blocking JPEG luminance/sharpness check.
- `AssistantHelpers.kt`: Pure-Kotlin (Android-free) logic: `AssistantProfile`, `GeminiVoice`, `VadSensitivity`, `ChatSpeedProfile`, `LocalVoiceCommand` priority matching, `orderOcrBlocks`, `formatBarcodesForSpeech`, `createEarconPcm`, `buildGeminiSetupMessage`, `buildChatInstructions`, `buildLiveInstructions`. Keep all pure logic here so it stays JVM-unit-testable.
- `MainActivity.kt`: Jetpack Compose UI (`internal fun TupoScreen` for direct UI/accessibility testing) with screen-reader semantics.
- `KeepAliveService.kt`: Foreground service (notification ID `1001`, `connectedDevice|microphone`) keeping sessions alive with screen off.
- `ChatGptAuth.kt`, `SecureStore.kt`, `NetDiag.kt`: OAuth PKCE flow, Android Keystore AES-GCM encrypted storage, and network diagnostics.
- `app/src/debug/.../MockBridge.kt` & `app/src/release/.../MockBridge.kt`: Debug-only Meta `MockDeviceKit` bridge with sample Turkish medication label (`PAROL 500 MG`) vs no-op release stub.

## 3. Non-Negotiable Architectural Invariants

1. **Audio Profile Separation (`HD_A2DP` vs `GLASSES_SCO`):**
   - `AudioProfile.HD_A2DP` is the default. In `HD_A2DP`, `AudioManager` MUST remain in `MODE_NORMAL`; never call `setCommunicationDevice` or start the 16 kHz call-audio keep-alive track.
   - Output audio uses `AudioAttributes.USAGE_ASSISTANT`; `GeminiLive.micLoop()` uses `MediaRecorder.AudioSource.VOICE_RECOGNITION` (`TYPE_BUILTIN_MIC`) with `echoTailGuardMs = 350L` when `!bargeIn && !callAudio`.
   - If `AudioProfile.GLASSES_SCO` is requested without a connected Bluetooth SCO/BLE headset, fall back safely to `HD_A2DP` rather than locking `MODE_IN_COMMUNICATION` onto the loudspeaker.
   - `KeepAliveService.kt` (ID `1001`) must be preserved intact. Do NOT re-introduce a duplicate A2DP media keep-alive service inside this app.

2. **Accessibility & Zero-Emoji Policy (Strict):**
   - **Zero emojis** in UI strings, logs, accessibility descriptions, and code comments/tests.
   - During an active session (`state.running && state.quietSessionAccessibility`), do NOT attach `LiveRegionMode` to the phase label (`Dinliyor`/`Düşünüyor`/`Konuşuyor`), or screen readers (Jieshuo / TalkBack) will talk over the assistant. Keep `contentDescription` so users can still inspect status by touch, and keep `LiveRegionMode.Assertive` on critical error cards.
   - Interactive controls must use `SettingRadioGroup` (`selectableGroup`, `Role.RadioButton`, `mergeDescendants = true`, `stateDescription`) or `SettingSwitch` (`Role.Switch`, `mergeDescendants = true`, `stateDescription`). Never create unlabeled clickable icons.
   - Status cues must go through `EarconPlayer` + `createEarconPcm` (`180 ms` pre-roll when `!inCall`, `350 ms` cooldown after SCO teardown), never `ToneGenerator`.

3. **Offline ML Kit, Quality Checks, and Fallback Boundaries:**
   - ML Kit `TextRecognition` and `BarcodeScanning` clients MUST stay **lazy** and bundled (`com.google.mlkit:*`). Do NOT add external product-lookup HTTP calls (e.g., OpenFoodFacts) or `kotlinx-coroutines-play-services`.
   - `qualityWarningEnabled` MUST default to `false` and MUST remain **non-blocking** even when enabled (never abort a cloud request or local scan due to darkness/blur).
   - Automatic offline ML Kit fallback in `Engine.runTurn` triggers ONLY on network `IOException` when `answer.isEmpty()`, never on `401 AuthException` or `429` rate limits.
   - `GeminiLive` `switch_profile` tool call updates active profile and returns the new directive in `sendToolResponse` without tearing down the WebSocket. Keep the 6-step `liveAttempt` (`0..5`) fallback ladder intact.

4. **Gradle & Dependency Guardrails:**
   - Keep `exclude(group = "com.google.protobuf", module = "protobuf-lite")` on `androidx.espresso.accessibility` in `app/build.gradle.kts`. Removing it causes a runtime `NoSuchMethodError` (`GeneratedMessageLite.registerDefaultInstance`) against `mwdat:1.1.0`'s `protobuf-javalite:3.25.5` in `androidTest`.
   - Keep `unitTests.all { it.jvmArgs("-Duser.language=en", "-Duser.country=US") }` in `testOptions` so Robolectric/Conscrypt does not fail on Turkish-locale Windows JVMs (`"wındows"` dotless-i bug).
   - Keep Robolectric on `4.15.1` (`@Config(sdk = [31, 35])`) while the project runs on JDK 17 (Robolectric `4.16+` for SDK 36 requires JDK 21).

## 4. Build, Test, and Verification Gate

Before committing or reporting work done, run and verify:

```bash
# 1. All 46 unit, MockWebServer SSE/WebSocket, Robolectric, and Compose Accessibility tests
./gradlew testDebugUnitTest

# 2. Full APK & AndroidTest compilation (includes lintVitalRelease)
./gradlew assembleDebug assembleRelease assembleDebugAndroidTest

# 3. If an Android device or API 31 emulator is attached:
./gradlew connectedDebugAndroidTest
```

Test suites live under:
- `app/src/test/java/com/keonyrus/tupovision/`:
  - `AssistantHelpersTest.kt` (pure Kotlin helpers, Gemini setup payloads, earcon PCM math)
  - `Phase2LocalAndProfileTest.kt` (profiles, command priority, multi-column OCR ordering, barcode speech formatting, frame quality math)
  - `VoiceAndSseTest.kt` (`SentenceChunker`, `Responses.parseSseEventLine`)
  - `ResponsesNetworkTest.kt` (`MockWebServer` SSE streaming, HTTP errors, connection reuse, cancellation)
  - `GeminiLiveSocketTest.kt` (`MockWebServer` WebSocket handshake, tool calls, `goAway`, session resumption)
  - `AudioRouterAndFramesRobolectricTest.kt` (Robolectric API 31 & 35 `AudioRouter`, `@GraphicsMode(NATIVE)` JPEG quality, `Engine` preference persistence)
  - `MainActivityAccessibilityTest.kt` (Compose semantics, `heading()`, `liveRegion` suppression, `Role.RadioButton`/`Switch`, zero-emoji scan)
- `app/src/androidTest/java/com/keonyrus/tupovision/`:
  - `TupoInstrumentedTest.kt` (`Espresso AccessibilityChecks`, bundled ML Kit `.so` OCR, `MockBridge` sanal glasses, real API 31 `AudioManager`)

## 5. Licensing & Secrets

- **Source code:** MIT License (`Copyright (c) 2026 KeonyRus` and `Copyright (c) 2026 Muhammet Enes Senovali`). Preserve both copyright notices.
- **Proprietary SDKs:** `mwdat` is governed by Meta Wearables Developer Terms; ML Kit binaries/models are governed by Google ML Kit Terms of Service. Never commit `.aar` binaries, extracted models, API keys, OAuth tokens, `.p12`/`.keystore` files, or device serials.
