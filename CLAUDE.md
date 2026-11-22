# CLAUDE.md

Read and follow [AGENTS.md](AGENTS.md) as the canonical engineering, architecture, accessibility, and testing guide for this repository.

## Quick Reminders for Claude Code

- Never violate the **AudioProfile.HD_A2DP** (`MODE_NORMAL`), **zero-emoji UI/logs**, or **quiet active-session accessibility** (`liveRegion` suppression) invariants documented in `AGENTS.md`.
- Keep pure business logic in `AssistantHelpers.kt` so it remains covered by JVM tests, and keep `exclude(group = "com.google.protobuf", module = "protobuf-lite")` on `espresso-accessibility`.
- Always run `./gradlew testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest` (and `connectedDebugAndroidTest` when a device/emulator is connected) before finishing a task.
