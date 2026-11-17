# Build and test checklist

All items start **not run**. These are completion requirements for the future implementation, not results from this handoff package. Track outcomes and evidence in `validation-report.md` when the Android project exists.

## Environment and compatibility

- [ ] Record OS, JDK, Android SDK/build tools, Gradle/AGP versions and supported device ABIs.
- [ ] Pin resolved dependencies and generate a working Gradle wrapper. Verify a clean checkout can build using the README.
- [ ] Confirm the chosen minSdk and Java/desugaring configuration against vendor requirements.
- [ ] Resolve actual Desert Ant Android artifacts and verify Java-callable APIs; document any isolated Kotlin bridge and its cancellation behavior.
- [ ] Verify SDK download, cache/revision, offline loading, cleanup and model option behavior. Do not infer Android support from available weights alone.
- [ ] Record license/attribution and model distribution requirements for the chosen SDK revisions.

## Milestone evidence

| Milestone | Required evidence |
|---|---|
| 0 — Compatibility | Real SDK loading/inference, Java interoperability, asset behavior, offset units and device/ABI findings |
| 1 — Keyboard | Selection, editing, repeat/delete, emoji, enter actions, switching and lifecycle tests |
| 2 — Companion/assets | Live settings synchronization, explicit downloads, error/retry/delete and readiness validation |
| 3 — Emo slice | Real local result → visible suggestion → accepted editor insertion, with offline evidence |
| 4 — Redact/Toxic | Supported categories/settings, review workflow, guarded ranges/Undo and uncertainty handling |
| 5 — Expanded text | Tongue language hints and Gist fixed-taxonomy topic actions |
| 6 — Voice | Record/import, format handling, all five adapters, preview/cancel/export and timestamps |
| 7 — Image/drawing | Explicit selection, all three adapters, previews and host-compatible sharing |
| 8 — Visual design | Reference comparisons, restrained gradient, enable/reduced-motion behavior and accessibility |
| 9 — Hardening | Device/app coverage, network, storage, process-death, performance and license evidence |

## Model compatibility report

Create one row for every model below. Record exact SDK/model revision, availability, ABI/minSdk, input/output format, languages or taxonomy, real smoke-test evidence, offline behavior, disk/peak memory, beta status and known limitations. Use **blocked** when support cannot be verified.

| Group | Required rows |
|---|---|
| Text | Emo, Redact, Toxic, Tongue, Gist |
| Voice | Voz, Clear, Ear, Uhm, Who |
| Images | Eye, Moderator |
| Drawing | Shapes |

Do not present the catalog as fully implemented merely because descriptors and switches exist. Each released entry needs a working adapter, valid input flow and real error handling.

## Editor and inference correctness

- [ ] Password and plan-defined no-learning fields do not invoke AI; normal typing remains available.
- [ ] Empty, long, partial or unavailable editor context is handled without crashes or whole-draft claims.
- [ ] Fast typing, external paste, selection moves, conversation/app switches and settings changes invalidate stale work/results.
- [ ] Main-thread key handling does not block on model work. Measure named-device p95 key handling and end-to-end suggestion latency separately.
- [ ] Each model instance is serialized. Cancellation suppresses stale results even when native inference cannot be interrupted.
- [ ] Redaction offsets handle UTF-16, surrogate pairs, emoji families, combining marks and partial text windows.
- [ ] Applying an edit and Undo both validate current text/session/selection. User acceptance is required for model-driven changes; no automatic send.
- [ ] Model failure, load timeout, asset absence and low-memory conditions leave ordinary typing functional.

## Settings, assets and lifecycle

- [ ] IME and companion use one settings store per file; subscriptions and executors are managed through their owners' lifecycles.
- [ ] Pausing AI preserves model choices/assets, clears suggestions and cancels pending work.
- [ ] Work is unique per model revision; duplicate requests do not corrupt assets.
- [ ] Interrupted/corrupt downloads never become Ready; retry, low storage and incompatible-device errors are distinct.
- [ ] Installed inference works with networking disabled; typing never starts an unrequested download or text upload.
- [ ] Runtime release, model deletion and revision replacement respect in-flight operations and file ownership.
- [ ] Drafts and redaction maps are absent from persistent logs/analytics/backups. Model assets are excluded from backup as designed.
- [ ] Configuration change, IME reconnect, app process death, offline startup and device lock/unlock recover safely.

## Media and interface checks

- [ ] Microphone permission denial/revocation, interrupted capture and imported invalid formats are handled.
- [ ] Audio model chaining verifies sample rate/channels, language compatibility, timestamps and whether stream/batch mode is supported.
- [ ] Image picker cancellation, malformed/large inputs, stroke ambiguity and sharing rejection have working recovery paths.
- [ ] Temporary media follows a documented retention/cleanup policy; exports require a user action.
- [ ] All controls have working behavior or an explicit unavailable state, with no production placeholder responses.
- [ ] Verify reference composition, dark theme, muted panel, gradient icon and a single 600 ms user-enable animation.
- [ ] Test disabled animations, larger font sizes, TalkBack, contrast and touch targets.

## Build commands after the project exists

Run from the project root. Adjust variant task names only if the implemented Gradle configuration requires it, and record the exact commands.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

With a compatible connected emulator/device and test setup:

```sh
./gradlew connectedDebugAndroidTest
```

Use `gradlew.bat` on Windows. These commands have not been run against an app in this package, because it contains the implementation handoff only. The future debug APK normally appears under `app/build/outputs/apk/debug/`; report its actual generated path.

Test on an own debug editor host, WhatsApp, Telegram, Discord, browser fields and a notes app. Record Android and host app versions. Cover a constrained physical device and a current device; emulator coverage does not replace device memory, heat, battery and input-latency measurements.

## Final delivery

- [ ] Runnable project, pinned dependencies, real wrapper and clear setup/build instructions.
- [ ] Debug APK when build tooling is available, with actual artifact location and build result.
- [ ] `model-compatibility.md`, `validation-report.md` and updated `PROGRESS.md`.
- [ ] Validation report separates passed, failed, blocked and not-run checks, with commands/device identifiers and logs or concise evidence.
- [ ] Remaining unsupported features and device-only gaps are explicit. No whole-app completion claim if required milestones are unfinished.
