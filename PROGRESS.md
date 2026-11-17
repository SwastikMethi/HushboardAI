# Implementation progress

The execution plan is `~/.claude/plans/i-have-added-a-whimsical-mist.md` (approved 17 Nov 2026). This file is the running record: milestone status, environment, and every ruling made along the way. Evidence lives in `docs/validation-report.md` and `docs/model-compatibility.md`.

| Milestone | Status | Evidence / blocker |
|---|---|---|
| 0 — Compatibility spike | Done (emulator). Device rows blocked | 17 instrumented tests on the Android 17 emulator; see validation report. Peak memory and a physical-device smoke run wait for the phone. |
| 1 — Reliable keyboard | Not started | — |
| 2 — Companion and assets | Not started | — |
| 3 — First real Emo slice | Not started | — |
| 4 — Redact and Toxic | Not started | — |
| 5 — Tongue and Gist | Not started | — |
| 6 — Voice tools | Not started | — |
| 7 — Image and drawing tools | Not started | — |
| 8 — Visual and accessibility audit | Not started | — |
| 9 — Complete-version hardening | Not started | — |

## Environment

- Host: macOS 26.4.1, Apple Silicon. Gradle runs on Android Studio's JetBrains Runtime 21.0.10 (`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`); the system JDK 26 is not used.
- Gradle 9.4.1, Android Gradle Plugin 9.2.1, Java 17 source/target, compileSdk 36.1, targetSdk 36, minSdk 26, `arm64-v8a`.
- Android SDK at `~/Library/Android/sdk` (path in the git-ignored `local.properties`).
- Desert Ant SDK 4.3.0. Pinned model revisions and files are in `docs/model-compatibility.md`.
- Test target so far: `Pixel_6_Pro` AVD, Android 17 (API 37), arm64-v8a, 16 KB pages. No physical device connected yet.

## Rulings

Decisions taken where the plan or handoff left a choice, each with what it costs if wrong.

1. **Git and workspace.** One repository in this folder on `main`, one commit per milestone, nothing pushed — the owner's choice. No separate worktree: the repository starts empty, so there is nothing to isolate from. Cost if wrong: none; history can be rebased onto a branch.
2. **This file is the ledger.** The executing-plans workflow's own ledger scripts expect a task-numbered plan; the approved plan is milestone-based, and the handoff already requires `PROGRESS.md`. Cost if wrong: none.
3. **Java-only interop; no Kotlin bridge file.** `sdkbridge/DesertAntClient` calls suspend functions through the coroutines library's future builder. Cancellation releases the caller and vendor exceptions surface as the cause of an `ExecutionException` — both proven on the emulator. `SuspendCall<T>` takes `Continuation<T>` (not `? super T`) so method references infer `T`; the single unchecked cast this needs is safe because a continuation only consumes `T`. Cost if wrong: add the one Kotlin file the handoff permits.
4. **The app downloads model files itself; the SDK's `download()` is not used in product code.** The spike showed an SDK download cannot be cancelled: the transfer runs on after the caller is released, which would break the Cancel button and the "unmetered networks only" promise. The app fetches the exact files the SDK pins (public URLs, `Range` resume, SHA-256 check) into its own directory, and the SDK adopts that directory — proven offline. `isDownloaded()` stays the final acceptance check before a model is marked Ready. Cost if wrong: a device that needs a different file set shows the model as Failed with the reason; the fallback is the SDK's own uncancellable download.
5. **Vendor artifacts are added as their adapters land**, not all 13 at once: each native model adds about 56 MB (703 MB for all). Cost if wrong: none; `libs.bundles.desertant` restores the full set.
6. **No release build type and no `proguard-rules.pro`.** The deliverable is the debug-signed APK; a release build needs the owner's keystore. Cost if wrong: add both when a release is wanted.
7. **`emoji2-emojipicker` stays at 1.7.0.** It passes the metadata check at compileSdk 36.1 and works inside the IME window. Cost if wrong: drop to 1.6.0.
8. **Backspace is a `KEYCODE_DEL` key event** so the host editor applies its own grapheme rules. Cost if wrong: an editor that ignores key events needs a `deleteSurroundingText` fallback.
9. **The kill and offline probes are opt-in tests.** They need an adb step between or before them, so they skip in a plain run. Cost if wrong: none; commands are in the validation report.

## Deviations from the handoff file list

- `sdkbridge/DesertAntSuspendBridge.kt` and `sdkbridge/CancellableOperation.java`: not needed (ruling 3).
- `app/proguard-rules.pro`: not created (ruling 6).
- Added: `src/debug/AndroidManifest.xml`, `docs/evidence/`.

## Open items carried forward

- Redact spans can start inside an emoji sequence; milestone 4 must widen spans to whole characters before editing.
- Tongue's telemetry switch (`setSendsDeviceContext`) — what it sends is not established; decide in milestone 5.
- Keep the SDK logger quiet so input text cannot reach logs (milestone 3, when the first model loads in the keyboard).
- All-models APK is 703 MB. Reported to the owner; needs a packaging decision before any store release.

## Next action

Milestone 1: replace the spike keyboard with the real QWERTY keyboard (AI off), onboarding, and the debug editor host checks.
