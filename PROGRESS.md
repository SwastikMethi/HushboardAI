# Implementation progress

The execution plan is `~/.claude/plans/i-have-added-a-whimsical-mist.md` (approved 17 Nov 2026). This file is the running record: milestone status, environment, and every ruling made along the way. Evidence lives in `docs/validation-report.md` and `docs/model-compatibility.md`.

| Milestone | Status | Evidence / blocker |
|---|---|---|
| 0 — Compatibility spike | Done (emulator). Device rows blocked | 17 instrumented tests on the Android 17 emulator; see validation report. Peak memory and a physical-device smoke run wait for the phone. |
| 1 — Reliable keyboard | Done (emulator). Device rows blocked | 32 unit tests, 35 instrumented tests; typing verified in the debug host and in Android Settings search. Haptics, sound, latency and third-party chat apps wait for the phone. |
| 2 — Companion and assets | Done (emulator). Device rows blocked | 55 unit tests, 47 instrumented tests. Real downloads, a cancel that stops, pause on leaving Wi-Fi and resume, live settings sync, three-tab companion app with the privacy disclosure. |
| 3 — First real Emo slice | Done (emulator). Device rows blocked | 87 unit tests, 60 instrumented tests. Real Emo suggestions in the keyboard, online and in airplane mode; stale results dropped; AI toggle and panel; crash breaker; per-app AI switches. Latency on a real device waits for the phone. |
| 4 — Redact and Toxic | Done (emulator). Device rows blocked | 120 unit tests, 69 instrumented tests. Real Redact and Toxic in the keyboard: notice, review, guarded mask and undo, warn-only mode, dismiss; Send is never blocked. "Try it" in the companion app. |
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

10. **The bundled word list is written for this project** (`assets/dictionaries/en_common.txt`, about 900 common English words in rough frequency order), so it carries no third-party license. Ceiling: single-word completion and one-edit repair only, small vocabulary, no next-word prediction. Upgrade path: AOSP LatinIME's Apache-2.0 word list, which needs the owner's go-ahead to download. Cost if wrong: suggestions are sparse; typing is unaffected.
11. **No composing region.** Characters are committed directly and a picked suggestion replaces the current word through the guarded `replaceRange`. Cost if wrong: none seen; revisit only if an editor mishandles `setComposingRegion`.
12. **A restart of the same field keeps the current layer and shift state.** Some apps restart input on every change; resetting to letters each time would make symbols untypeable. Cost if wrong: a stale layer after a programmatic text change.
13. **Long-press on space opens Android's keyboard picker.** The system already shows its own switch button in the navigation area; this is the in-keyboard route. Cost if wrong: add a dedicated globe key.
14. **Key glyphs are text characters for now** (shift, backspace, enter, the emoji key). The mockup's drawn icons are a milestone 8 item. Cost if wrong: a device font missing a glyph shows a blank key; the audit checks this on the phone.
15. **Haptics default on, key sound off, height standard.** The mechanics exist in milestone 1; the saved settings that control them arrive with the settings store in milestone 2.
16. **The tool row shows only the settings button until there is something real to add** — the AI control in milestone 3, the microphone in milestone 6. The mockup's clipboard and grid icons have no specified behaviour and stay out.

17. **Readiness is file-based, and the SDK's `isDownloaded()` is checked when a model is first loaded, not when it finishes downloading.** Every file is verified against the size and SHA-256 the SDK itself pins, so loading the 56 MB native library just to ask again at download time would cost memory for nothing. If the SDK ever disagrees at load time, the model is marked Unavailable on this device and is never called — which also keeps the rule that typing can never start a download. Cost if wrong: a device needing a different file set finds out at first use instead of at download.
18. **No foreground service for downloads.** The app's downloader resumes, so when WorkManager's 10-minute limit or a network change stops a transfer it continues later from where it stopped (proved by the Wi-Fi test). Cost if wrong: very large models on slow links take several runs to finish.
19. **Cancel removes the partial download; a failure or a pause keeps it.** Cancel is the user saying "not now", so storage is given back. Cost if wrong: a cancelled large download starts again from zero.
20. **Settings and model-detail screens use `androidx.preference`** backed by the shared settings store, rather than hand-built rows. One new dependency replaces a lot of UI code and gives the detail screen its "render any model from its definition" behaviour. Cost if wrong: less visual control; the mockup's rounded group cards are deferred to milestone 8.
21. **Models without a captured file list show "Coming later"** and offer no download. That is nine of the thirteen today; each flips to downloadable when its adapter lands. Cost if wrong: none — the alternative is a button that does nothing.
22. **Android glue (repository, worker, settings store) was written together with its device tests**, not strictly after a failing test. Their decision logic lives in classes that were test-first (`ModelDownloader`, `AppSettings`, `ModelRegistry`), and each device test was watched failing before the wiring that makes it pass. Cost if wrong: thinner unit coverage of the glue itself.

23. **Adapters block the inference thread while the SDK works.** `ModelAdapter.run` waits on the SDK's future there, and only there; the main thread never waits. One thread means one model call at a time, which is the "one heavy slot" the plan asks for and what serialises access to each native model. Cost if wrong: a slow voice model would delay an emoji suggestion; split the slot then.
24. **Emoji fill the strip only when no word is being completed.** While a word is half-typed the strip shows completions; in the pause after it, emoji. The mockup shows emoji after a finished word, which is this case. Cost if wrong: emoji appear less often than some would like.
25. **Text is not read for AI at all in a field where AI may not run.** Eligibility is decided first; only then is the bounded window around the cursor read (1,000 characters before, 200 after). Cost if wrong: none.
26. **The AI panel lists only models that act while typing and have a working adapter** — Emo today; Redact and Toxic join in milestone 4. Cost if wrong: none; a listed switch that did nothing would be worse.
27. **The master switch in the keyboard uses a solid AI-blue track rather than a drawn gradient.** The gradient identity is on the tool-row control (sparkle and border). Cost if wrong: a milestone 8 polish item.
28. **`ModelInput`, `ModelResult`, `ActivityEvent` and `CapabilityRouter` were not created.** `ModelAdapter<I, O>` is generic over its input and output, so wrapper types add nothing yet; routing is one check in `AiCoordinator`. They are added only if the voice and image milestones show a real need. Cost if wrong: a small refactor when a second input kind arrives.

29. **The app masks span by span; the SDK's own masked string is not used.** Each replacement is checked against the editor's current text and all of them apply as one edit or not at all. Cost if wrong: none; the placeholders are the SDK's own.
30. **A span that starts inside a character begins after it** (and after the space that follows); one that ends inside a character takes the rest of it. Seen with the real model: a name span that began inside a family emoji. Cost if wrong: in the rare case where the cut character was part of the item (a decomposed first letter), that letter is left unmasked.
31. **A notice or an undo offer outranks word completions in the strip.** They appear only after typing pauses, and a notice hidden behind completions (an email address at the end of a draft completes like a word) would be missed. Cost if wrong: completions are unavailable while a notice is showing; Dismiss brings them back.
32. **Toxic "alert frequency" is the app's own setting.** The model runs with its shipped thresholds and returns them; the app shifts them (+0.15 for fewer, −0.10 for more) when deciding what to flag. The settings screen says this is set by the app. Only the wording labels (hateful, abusive, threat) are used; the "who is targeted" labels are not surfaced. Cost if wrong: the shifts are untuned starting values.
33. **The undo offer lasts until the next key press.** It is guarded (the placeholders must still be exactly where they were put) and is forgotten when the field loses the keyboard. Cost if wrong: an undo wanted after more typing is not offered.
34. **Tone notices offer no edit.** Review shows which kinds of wording were flagged and says the model is in beta; there is nothing to apply. Cost if wrong: none.

## Deviations from the handoff file list

- `sdkbridge/DesertAntSuspendBridge.kt` and `sdkbridge/CancellableOperation.java`: not needed (ruling 3).
- `app/proguard-rules.pro`: not created (ruling 6).
- Added: `src/debug/AndroidManifest.xml`, `docs/evidence/`, `ime/ShiftState.java` (the shift state machine, split out so it is unit-tested without Android).
- `ime/view/KeyboardRootView.java`: folded into `res/layout/keyboard_view.xml` plus `KeyboardController`; a class that only held child views had no job.
- `ui/onboarding/SetupViewModel.java`: not created; the screen re-reads system state on every return, so there is nothing to hold.
- `ui/models/ModelsViewModel.java`, `ui/settings/SettingsViewModel.java`: not created; screens observe the app-wide repositories directly.
- `data/PreferenceKeys.java` → constants in `AppSettings`. `core/AppExecutors.java` → `AppContainer`. `models/ModelStorage.java` → `ModelRepository`. `assets/models/catalog.json` → `ModelRegistry` (one source for the 13 entries). `ui/AppNavigator.java` → `MainActivity`. `ui/components/ModelRowViewHolder.java` → `ModelLibraryFragment` (a fixed list of 13 rows needs no recycler). `ui/keyboard/KeyboardPreviewFragment.java` → the practice field in `SetupFragment`.
- Added: `models/ModelDownloader.java` (the app's own downloader, ruling 4), `ui/settings/SettingsStore.java`, `ui/settings/PrivacyFragment.java`.
- Added in milestone 3: `ai/CrashBreaker.java`, `ui/settings/AppPreferencesFragment.java` (as the handoff lists).
- `ime/view/AiPanelView.java` → `res/layout/view_ai_panel.xml` plus `KeyboardController`, for the same reason as `KeyboardRootView`.
- `ai/ModelInput.java`, `ModelResult.java`, `ActivityEvent.java`, `CapabilityRouter.java`: not created (ruling 28).

## Open items carried forward

- Redact spans can start inside an emoji sequence; milestone 4 must widen spans to whole characters before editing.
- Tongue's telemetry switch (`setSendsDeviceContext`) — what it sends is not established; decide in milestone 5.
- Keep the SDK logger quiet so input text cannot reach logs (milestone 3, when the first model loads in the keyboard).
- All-models APK is 703 MB. Reported to the owner; needs a packaging decision before any store release.

- Reinstalling the app makes Android switch away from the keyboard (it stays enabled). Onboarding reports this correctly; tests re-select it in their own setup.
- TalkBack has not been driven yet; keys are exposed as labelled nodes. Milestone 8.

- Android defers background jobs for apps that are not on screen. A download started and then immediately left may sit at "Waiting to start" for some minutes.
- Model rows show an initial rather than a drawn icon, and settings groups are flat lists rather than rounded cards. Milestone 8.
- Not yet driven by a test: deleting a model from its detail screen, resetting one model's settings, low-storage failure, a double tap on Download.

- The SDK's logging is switched off at process start (`DesertAntClient.silenceSdkLogs`). Done; the open item from milestone 0 is closed.
- Whole-character span handling is done (ruling 30); that milestone 0 item is closed.
- Added in milestone 4: `core/TextEdit.java`, `ai/RedactionPlan.java`, `ai/RedactCategories.java`, `ai/ToxicPolicy.java`, `ai/NoticeText.java`, `ui/models/TryItPreference.java`, `ui/models/ModelTryIt.java`.
- Not exercised yet: the copy-only preview (needs an editor that hides its text offset), the partial-draft wording (needs a draft over 1,000 characters), and model usefulness on a real sample set.
- Not yet asserted by a test: the single enable sweep and its reduced-motion skip, model release under memory pressure, the per-app switches screen.

## Next action

Milestone 5: Tongue (language hint, bundled) and Gist (topic tags) — read Gist's real API, capture its files, adapters, and a useful action for each.
