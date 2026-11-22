# GEMINI.md

Read and follow [AGENTS.md](AGENTS.md) as the canonical engineering, architecture, accessibility, and testing guide for this repository.

## Quick Reminders for Antigravity / Gemini Agents

- Preserve `AudioProfile.HD_A2DP` (`MODE_NORMAL` + `USAGE_ASSISTANT` + `VOICE_RECOGNITION`), `KeepAliveService` (ID `1001`), lazy bundled ML Kit (`LocalVision.kt`), and non-blocking `qualityWarningEnabled` defaults.
- Enforce **zero emojis** across all Kotlin/XML strings and verify Compose semantics (`heading()`, `Role.RadioButton`, `Role.Switch`, `stateDescription`, and `liveRegion` suppression during active sessions).
- Always run `./gradlew testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` (plus `connectedDebugAndroidTest` if a device or API 31 emulator is available) before reporting completion.
