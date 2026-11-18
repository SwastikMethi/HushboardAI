# Validation report

Every check is listed as **passed**, **failed**, **blocked** or **not run**. A check counts as passed only if it actually ran, on the target named beside it. Emulator results are never presented as physical-device results.

## Targets

| ID | Target |
|---|---|
| EMU-37 | `Pixel_6_Pro` AVD, Android 17 (API 37), `google_apis_ps16k` arm64-v8a image, 16 KB pages, 3 GB memory, software GPU, on an Apple Silicon Mac (macOS 26.4.1). Used for milestones 0–5 |
| EMU-37B | `AiKeyboard_Test` AVD: the same Android 17 image and 16 KB pages, with 8 GB memory and 16 GB storage, started with `-gpu host` (the Mac's GPU) unless a row says software GPU. Used from milestone 6 |
| PHONE | OnePlus CPH2423, Android 15 (API 35, build `CPH2423_15.0.0.2001(EX01)`), MediaTek MT6895Z with a Mali-G610 GPU, 12 GB memory (11.2 GiB reported), 4 KB pages, connected over USB. Connected on 18 Nov 2026, after the emulator work and the final review |

EMU-37B exists because the voice models outgrew EMU-37: Voz needs about 3.9 GB of memory and 1.2 GB of storage, and Clear needs a real GPU. It is started with:

```sh
~/Library/Android/sdk/emulator/emulator -avd AiKeyboard_Test -gpu host -no-window -no-snapshot -no-boot-anim
```

## Commands

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:checkDebugAarMetadata assembleDebug testDebugUnitTest lintDebug
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w com.example.aikeyboard.test/androidx.test.runner.AndroidJUnitRunner
```

Install-then-instrument keeps app data, so downloaded models survive between runs. `connectedDebugAndroidTest` would uninstall the app and wipe them.

Opt-in probes:

```sh
# Offline: turn the network off first, back on afterwards.
adb shell cmd connectivity airplane-mode enable
adb shell am instrument -w -e class 'com.example.aikeyboard.ModelSmokeTest#offline_installedModelsRunAndMissingModelFailsVisibly' com.example.aikeyboard.test/androidx.test.runner.AndroidJUnitRunner
adb shell cmd connectivity airplane-mode disable

# Process death: start step 1, run `adb shell am force-stop com.example.aikeyboard` mid-download, then step 2.
adb shell am instrument -w -e killProbe true -e class 'com.example.aikeyboard.ModelSmokeTest#killProbe_startDownload' com.example.aikeyboard.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e killProbe true -e class 'com.example.aikeyboard.ModelSmokeTest#killProbe_afterKillIsNotReadyAndRetryCompletes' com.example.aikeyboard.test/androidx.test.runner.AndroidJUnitRunner

# Clear: only where there is a real GPU (a phone, or an emulator started with -gpu host).
adb shell am instrument -w -e clear true -e class 'com.example.aikeyboard.VoiceModelsTest' com.example.aikeyboard.test/androidx.test.runner.AndroidJUnitRunner

# Crash recovery with a model that really dies on the device (Clear on a software GPU, Voz on
# too little memory). The first run is expected to end with "Process crashed".
adb shell am instrument -w -e crashProbe run    -e crashModel clear -e class com.example.aikeyboard.CrashRecoveryProbeTest com.example.aikeyboard.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e crashProbe verify -e crashModel clear -e class com.example.aikeyboard.CrashRecoveryProbeTest com.example.aikeyboard.test/androidx.test.runner.AndroidJUnitRunner

# Microphone refused: revoke first (this kills the app) and clear the "refused for good" mark
# an earlier run leaves behind, then run.
adb shell pm revoke com.example.aikeyboard android.permission.RECORD_AUDIO
adb shell pm clear-permission-flags com.example.aikeyboard android.permission.RECORD_AUDIO user-set user-fixed
adb shell am instrument -w -e micDenied true -e class com.example.aikeyboard.MicPermissionFlowTest com.example.aikeyboard.test/androidx.test.runner.AndroidJUnitRunner

# Latency, for a named device. The keyboard must already be selected on it; results come back
# as INSTRUMENTATION_STATUS lines (add -r).
adb shell am instrument -w -r -e latency true -e class com.example.aikeyboard.LatencyProbeTest com.example.aikeyboard.test/androidx.test.runner.AndroidJUnitRunner

# The keyboard's picture button through Android's real picker. EMULATOR ONLY: the picker shows
# the device's own pictures. Put one in the emulator's gallery first.
adb shell am instrument -w -e picker true -e class 'com.example.aikeyboard.KeyboardImageTest#theSystemsPickerHandsAChosenPictureBackToTheKeyboard' com.example.aikeyboard.test/androidx.test.runner.AndroidJUnitRunner

# Kids Chat, two measurements: what the tone model alone makes of ten sample messages under the
# chat's rules, and how far into a long message the two text models still see. Results come
# back as INSTRUMENTATION_STATUS lines.
adb shell am instrument -w -r -e chatprobe true -e class com.example.aikeyboard.KidsChatTest com.example.aikeyboard.test/androidx.test.runner.AndroidJUnitRunner

# Screenshots: written to /sdcard/Android/data/com.example.aikeyboard/files/tour
adb shell am instrument -w -e screenshots true -e class com.example.aikeyboard.ScreenshotTourTest com.example.aikeyboard.test/androidx.test.runner.AndroidJUnitRunner
```

## Milestone 0 — Compatibility spike

| Check | Result | Target | Evidence |
|---|---|---|---|
| Empty app builds, unit-tests and lints on the pinned toolchain | passed | host | `BUILD SUCCESSFUL` for `assembleDebug testDebugUnitTest lintDebug` |
| All 13 vendor artifacts resolve; AAR metadata check | passed | host | `:app:checkDebugAarMetadata` with the full bundle |
| Real signatures recorded | passed | host | `docs/evidence/sdk-signatures-4.3.0.txt` |
| 16 KB alignment of every vendor native library | passed | host + EMU-37 | `objdump` shows `align 2**14`; libraries load on the 16 KB image |
| APK size measured (one model / all 13) | passed | host | 76.3 MB / 703.2 MB |
| Emo: explicit download into the app's directory, real suggestions, cancel-then-reuse | passed | EMU-37 | `ModelSmokeTest.emo_downloadsIntoOurDirectoryThenSuggestsEmoji` |
| Redact: spans are UTF-16 offsets across emoji, surrogate pairs and combining marks | passed | EMU-37 | `ModelSmokeTest.redact_offsetsAreUtf16CodeUnits` |
| Toxic: flags a threat, not a friendly message | passed | EMU-37 | `ModelSmokeTest.toxic_scoresAThreat` |
| Tongue: bundled, synchronous | passed | EMU-37 | `ModelSmokeTest.tongue_isBundledAndSynchronous` |
| Java interop: asynchronous call, cancellation releases the caller, vendor exception surfaces | passed | EMU-37 | `DesertAntClient` exercised by every `ModelSmokeTest` |
| SDK download cancellation | **failed (vendor limitation)** | EMU-37 | Transfer continues after cancel. Kept as a characterisation test; the app downloads the pinned files itself instead. |
| Public weight URLs serve the pinned bytes and support resume | passed | EMU-37 | `ModelSmokeTest.huggingFaceUrl_servesThePinnedFile` |
| Installed inference with the network off; self-filled directory adopted; missing model fails visibly | passed | EMU-37, airplane mode | `ModelSmokeTest.offline_installedModelsRunAndMissingModelFailsVisibly` |
| Process killed mid-download does not come back ready; retry completes | passed | EMU-37 | `killProbe_*` with `am force-stop` between the steps |
| `replaceRange` via `setComposingRegion`, including refusal when text changed | passed | EMU-37 | `EditorBridgeTest` (7 tests, real `EditText` connection) |
| Minimal keyboard types and deletes in a real field through the IME window | passed | EMU-37 | `ImeSpikeTest` |
| Bottom inset: IME window is edge-to-edge; keys padded above a 168 px navigation area | passed | EMU-37 | `ImeSpikeTest`; `docs/evidence/screenshots/m0_ime_spike_emoji_picker.png` |
| Material widget inflates in the service through a themed context | passed | EMU-37 | `ImeSpikeTest` |
| `EmojiPickerView` 1.7.0 works inside the IME window; picking inserts the emoji | passed | EMU-37 | `ImeSpikeTest` |
| `AudioRecord` from the service while the keyboard is shown is not silenced | passed | EMU-37 | `ImeSpikeTest` (`state=3 read=8000 silenced=false`) |
| Tests select the keyboard themselves; soft keyboard shown despite the emulator's hardware keyboard | passed | EMU-37 | `ImeSpikeTest.setUp` |
| Peak memory per model | blocked | PHONE | Needs the physical device |
| SDK smoke run on a physical device | blocked | PHONE | Needs the physical device |
| License Check observed on the wire | not run | — | Planned with the milestone 9 network capture |

Full-suite run at the end of the milestone: 17 instrumented tests, 14 executed and passed, 3 skipped by design in that run (the offline probe and the two-step kill probe, each passed in its own run above).

## Milestone 1 — Reliable keyboard (AI off)

Unit tests (JVM): 32 passed — `KeyboardLayoutTest` 7, `ShiftStateTest` 8, `LocalWordSuggesterTest` 9, `InputSessionTest` 4, `SetupStepTest` 4.

| Check | Result | Target | Evidence |
|---|---|---|---|
| Letters, space and punctuation typed through the real IME window | passed | EMU-37 | `KeyboardImeTest.typesLettersSpaceAndPunctuation` |
| Shift capitalises one letter | passed | EMU-37 | `KeyboardImeTest.shiftCapitalisesOnlyTheNextLetter` |
| Caps lock by double tap; manual shift beats auto-caps | passed | host (logic only) | `ShiftStateTest`. Not driven end to end: automated taps are too slow for the double-tap window. |
| Sentence starts capitalised where the field asks for it | passed | EMU-37 | `KeyboardImeTest.sentenceStartsAreCapitalisedWhereTheFieldAsksForIt` |
| Symbol layers and return to letters | passed | EMU-37 | `KeyboardImeTest.symbolLayersTypeDigitsAndReturnToLetters` |
| Backspace: one character per tap; a whole emoji family at once; repeat while held | passed | EMU-37 | `KeyboardImeTest.backspace*`, `holdingBackspaceKeepsDeleting` |
| Enter performs the field's own action and edits nothing; newline in multi-line fields | passed | EMU-37 | `KeyboardImeTest.enterPerformsTheFieldsOwnAction`, `enterInsertsANewLineInAMultiLineField` |
| Emoji panel inserts an emoji and returns to letters | passed | EMU-37 | `KeyboardImeTest.emojiPanelInsertsAnEmojiAndReturnsToLetters` |
| A word suggestion replaces the word being typed (validated replace) | passed | EMU-37 | `KeyboardImeTest.aWordSuggestionReplacesTheWordBeingTyped` |
| Password fields get no word suggestions | passed | EMU-37 | `KeyboardImeTest.passwordFieldsGetNoWordSuggestions` |
| Keyboard keeps working across rotation | passed | EMU-37 | `KeyboardImeTest.keyboardKeepsWorkingAfterRotation` |
| Keys sit above the navigation area | passed | EMU-37 | `KeyboardImeTest.keysSitAboveTheNavigationArea` |
| Editor reads and guarded replace against a real `EditText` connection | passed | EMU-37 | `EditorBridgeTest` (9 tests) |
| Onboarding reflects the real enabled/selected state and updates on return | passed | EMU-37 | `SetupScreenTest` (2 tests) |
| Types into another app's field (Android Settings search) | passed | EMU-37 | `StockAppTypingTest` with `-e stockApps true` |
| Keyboard recovers after its process is killed | passed | EMU-37 | `am force-stop`, then `KeyboardImeTest.typesLettersSpaceAndPunctuation` passes |
| Appearance against the approved mockup | passed (reviewed) | EMU-37 | `docs/evidence/screenshots/m1_keyboard_typing.png`, `m1_onboarding.png`. Tool row is intentionally sparse until the AI control (M3) and microphone (M6) exist. |
| TalkBack reading and activating keys by voice-over | not run | — | Each key is exposed as its own labelled node (the tests find keys that way), but TalkBack itself has not been driven. Milestone 8. |
| Haptics felt, key sound heard | blocked | PHONE | An emulator cannot show either |
| Typing in WhatsApp, Telegram, Discord, a browser, a notes app | blocked | PHONE | Needs the phone and the owner's signed-in apps |
| Key-handling latency (p95) | blocked | PHONE | Needs the physical device |

Full-suite run at the end of the milestone: 35 instrumented tests, 31 executed and passed, 4 skipped by design in that run (offline probe, two-step kill probe, stock-app probe — each passed in its own run).

## Milestone 2 — Companion app and assets

Unit tests (JVM): 55 passed in total. New in this milestone: `ModelDownloaderTest` 10 (against a real local socket), `ModelRegistryTest` 8, `AppSettingsTest` 5.

| Check | Result | Target | Evidence |
|---|---|---|---|
| Downloader: complete transfer, progress up to the total | passed | host | `ModelDownloaderTest.downloadsEveryFileAndReportsProgressUpToTheTotal` |
| Downloader: resumes a partial file with a `Range` request; restarts if the server ignores it | passed | host | `resumesAPartialFileWithARangeRequest`, `restartsFromZeroWhenTheServerIgnoresTheRange` |
| An interrupted transfer never looks complete | passed | host | `anInterruptedTransferIsANetworkFailureAndNeverLooksComplete` |
| Corrupt content is deleted and never looks complete | passed | host | `corruptContentIsDeletedAndNeverLooksComplete` |
| Cancelling stops the transfer part way | passed | host | `cancellingStopsTheTransferPartWay` |
| Server error, already-present files, wrong-sized file, first download into a new directory | passed | host | remaining `ModelDownloaderTest` cases |
| Catalog: 13 models, grouped, vendor beta labels, pinned sizes add up | passed | host | `ModelRegistryTest` |
| Settings snapshot: safe defaults (AI off), pause keeps choices, immutable | passed | host | `AppSettingsTest` |
| A download runs through the WorkManager worker; the vendor SDK accepts the files and runs | passed | EMU-37 | `ModelDownloadDeviceTest.aDownloadGoesThroughTheWorkerAndTheVendorSdkAcceptsTheFiles` |
| Cancel really stops the transfer and removes partial files | passed | EMU-37 | `ModelDownloadDeviceTest.cancellingReallyStopsTheTransferAndRemovesWhatItFetched` |
| "Unmetered networks only": Wi-Fi off pauses the download (no bytes over mobile data), partial file kept; Wi-Fi on resumes to Ready | passed | EMU-37 | `ModelDownloadDeviceTest.losingTheUnmeteredNetworkPausesTheDownloadAndItResumesWhenItReturns` |
| Readiness comes from the files: a truncated file is not Ready, and a re-download repairs it | passed | EMU-37 | `ModelDownloadDeviceTest.readinessComesFromTheFiles_…` |
| Every settings observer sees the same snapshot and revision | passed | EMU-37 | `SettingsSyncTest.everyObserverSeesTheSameSnapshotWithTheSameRevision` |
| The open keyboard follows a height change made elsewhere | passed | EMU-37 | `SettingsSyncTest.theKeyboardFollowsAHeightChangeWithoutBeingReopened` |
| Three tabs show their screens | passed | EMU-37 | `CompanionAppTest.theThreeTabsShowTheirScreens` |
| Catalog lists all 13 models with honest states (Included, Coming later) | passed | EMU-37 | `CompanionAppTest.theCatalogListsAllThirteenModelsWithHonestStates` |
| Download from the catalog ends in Ready and the model can be switched on | passed | EMU-37 | `CompanionAppTest.downloadingFromTheCatalogEndsInReadyAndTheModelCanBeTurnedOn` (real download) |
| Master switch pauses everything but keeps each model's choice; row says Paused | passed | EMU-37 | `CompanionAppTest.theMasterSwitchPausesEverythingButKeepsEachChoice` |
| Model detail shows only that model's own settings | passed | EMU-37 | `CompanionAppTest.modelDetailShowsOnlyThatModelsRealSettings` |
| Privacy screen discloses the vendor License Check | passed | EMU-37 | `CompanionAppTest.thePrivacyScreenDisclosesTheLicenseCheck`; `docs/evidence/screenshots/m2_privacy.png` |
| Appearance against the companion mockup | passed (reviewed) | EMU-37 | `m2_models.png`, `m2_settings.png`. Model rows show an initial instead of a drawn icon; grouped settings are flat lists rather than rounded cards. Both are milestone 8 items. |
| Two taps on Download start only one transfer | not run | — | Guaranteed by WorkManager unique work with `KEEP`; no test drives it |
| Low storage reported as its own failure | not run | — | The code path exists (`Failure.STORAGE`); filling a disk was not simulated |
| Model delete from the detail screen, reset of one model's settings | not run | — | Implemented; not yet driven by a test |
| Download speed and behaviour on a real mobile network | blocked | PHONE | Needs the physical device |

Two findings from running on the device, both fixed and covered by tests:
- The downloader checked free space before the model folder existed and read zero. `ModelDownloaderTest.createsTheModelDirectoryOnAFirstDownload` reproduces it.
- Android defers background jobs for apps that are not on screen, sometimes by minutes. Downloads are only started from the companion app while it is visible, and the device tests now do the same.

Full-suite run at the end of the milestone: 47 instrumented tests; 43 executed and passed, 4 skipped by design (offline probe, two-step kill probe, stock-app probe).

## Milestone 3 — Emo vertical slice

Unit tests (JVM): 87 passed in total. New in this milestone: `AiCoordinatorTest` 12, `InputEligibilityTest` 7, `SuggestionPolicyTest` 5, `CrashBreakerTest` 4, `InferenceSchedulerTest` 4.

| Check | Result | Target | Evidence |
|---|---|---|---|
| A result for an older draft is dropped | passed | host | `AiCoordinatorTest.aResultForAnOlderDraftIsDropped` |
| A result computed under older settings is dropped, and the draft is analysed again | passed | host | `AiCoordinatorTest.aResultComputedUnderOlderSettingsIsDropped` |
| A new draft clears what was shown for the old one | passed | host | `AiCoordinatorTest.aNewDraftClearsWhatWasShownForTheOldOne` |
| Turning AI off clears suggestions and cancels pending work | passed | host | `AiCoordinatorTest.turningAiOffClearsSuggestionsAndCancelsPendingWork` |
| A model that is switched off, not installed, or in a disallowed field is never called | passed | host | `AiCoordinatorTest` (three tests) |
| A failing model shows nothing and typing continues | passed | host | `AiCoordinatorTest.aFailingModelShowsNothingAndDoesNotThrow` |
| Only text before the cursor goes to the emoji model; a late result after the session ended is ignored | passed | host | `AiCoordinatorTest` (two tests) |
| Debounce: only the newest request per model reaches the inference thread; work never runs on the timer thread | passed | host | `InferenceSchedulerTest` |
| Field eligibility: password, no-learning, non-text, blocked app, AI off | passed | host | `InputEligibilityTest` |
| Crash breaker: an unfinished load is reported once by the next start | passed | host | `CrashBreakerTest` |
| Real Emo result → visible suggestion → tap inserts it | passed | EMU-37 | `KeyboardAiTest.anEmojiIsSuggestedForTheDraftAndATapInsertsIt`; `docs/evidence/screenshots/m3_ai_suggestions.png` (🍕 😋 🔥 for "Pizza sounds good") |
| The same flow with the network off | passed | EMU-37, airplane mode | Same test run with `cmd connectivity airplane-mode enable`; "Active default network: none" confirmed |
| Nothing suggested with AI off, in a password field, in a no-learning field, in a blocked app | passed | EMU-37 | `KeyboardAiTest` (four tests) |
| The AI panel's master switch writes the setting the companion app uses; the tool-row control follows | passed | EMU-37 | `KeyboardAiTest.theAiPanelSwitchIsTheSameSettingTheCompanionAppUses` |
| A model switched on but not installed offers setup, never runs, and typing is unaffected | passed | EMU-37 | `KeyboardAiTest.aModelSwitchedOnButNotInstalledOffersSetupInsteadOfRunning` |
| A model whose load never finished is switched off at the next start; re-enabling clears the note | passed | EMU-37 | `KeyboardAiTest.aModelThatCrashedTheKeyboardIsSwitchedOffAtTheNextStart` (marker planted by the test; no real crash was induced) |
| Bounded draft read with offset and partial flag | passed | EMU-37 | `EditorBridgeTest.readDraft_*` (API 31+ path only; the older-API fallback is not run — no image below API 35 here) |
| Appearance: gradient AI control, emoji strip, muted violet panel at keyboard height | passed (reviewed) | EMU-37 | `m3_typing_ai_off.png`, `m3_ai_suggestions.png`, `m3_ai_panel.png` |
| The 600 ms enable sweep plays once and is skipped with animations off | not run | — | Implemented (`AiToggleView.playEnableSweep`, guarded by `ValueAnimator.areAnimatorsEnabled()`); not asserted by a test. Milestone 8. |
| Releasing models under memory pressure | not run | — | Implemented (`onTrimMemory`); not simulated |
| Per-app AI switches screen, suggestion-frequency setting | not run | — | Implemented; the blocked-app rule itself is tested above |
| Suggestion latency after the last key | blocked | PHONE | Needs the physical device. On the emulator, warm Emo inference measured about 3 ms plus the 150 ms pause. |
| No typing stalls while a model loads | blocked | PHONE | Needs the physical device |

Full-suite run at the end of the milestone: 60 instrumented tests; 53 passed, 6 skipped by design (offline probe, kill probe ×2, stock-app probe, screenshot tour ×2), 1 failed. The failure was the privacy-screen test, which needed to scroll now that Settings has more rows; fixed, and `CompanionAppTest` then passed 6 of 6 in its own run.

## Milestone 4 — Redact and Toxic

Unit tests (JVM): 120 passed in total. New or extended in this milestone: `AiCoordinatorTest` 19, `TextRangeMapperTest` 9, `ModelRulesTest` 9, `SuggestionPolicyTest` 9, `RedactionPlanTest` 4.

| Check | Result | Target | Evidence |
|---|---|---|---|
| A span that starts inside an emoji family or a surrogate pair starts after it; one that ends before a combining mark takes the mark | passed | host | `TextRangeMapperTest` (5 cases) |
| A span in a partial window maps to its place in the editor; no offset means no editor range | passed | host | `TextRangeMapperTest.aSpanInAPartialWindowMapsToItsPlaceInTheEditor`, `withoutAKnownWindowOffsetThereIsNoEditorRange` |
| Mask edits carry the text they expect; undo edits find the placeholders where they moved to | passed | host | `RedactionPlanTest` |
| Categories map to the vendor's labels; every category off means the model is not asked | passed | host | `ModelRulesTest` |
| Alert frequency shifts the model's own thresholds; a switched-off kind is never flagged | passed | host | `ModelRulesTest` |
| One notice at a time: personal information first, then tone; dismissing reveals the next | passed | host | `SuggestionPolicyTest`, `AiCoordinatorTest.privacyComesFirstAndDismissingItRevealsTheToneNotice` |
| A privacy result for an older draft is dropped | passed | host | `AiCoordinatorTest.aPrivacyResultForAnOlderDraftIsDropped` |
| A dismissed notice stays away while the text is unchanged and returns when it changes | passed | host + EMU-37 | `AiCoordinatorTest` (two tests); `KeyboardAiTest.aDismissedNoticeStaysAwayUntilTheTextChanges` |
| Several replacements apply as one edit, or none: nothing changes if any range no longer matches | passed | EMU-37 | `EditorBridgeTest.replaceRanges_*` (3 tests) |
| Undo is guarded: a second undo finds no placeholders and refuses | passed | EMU-37 | `EditorBridgeTest.replaceRanges_aMaskCanBeUndoneAndTheUndoIsGuardedToo` |
| Real Redact: notice → review (original and masked shown) → mask → undo, with nothing changed until the user chooses | passed | EMU-37 | `KeyboardAiTest.personalInformationCanBeReviewedMaskedAndPutBack`; `m4_privacy_notice.png`, `m4_review_panel.png` |
| Warn-only shows what was found and offers no change | passed | EMU-37 | `KeyboardAiTest.warnOnlyShowsWhatWasFoundButOffersNoChange` |
| Real Toxic: a threat is flagged for review; the notice offers no edit; Send still performs the field's action and the text is untouched | passed | EMU-37 | `KeyboardAiTest.aThreatIsFlaggedForReviewAndSendingIsNeverBlocked` |
| "Try it" runs the real model on typed text and shows its real answer | passed | EMU-37 | `CompanionAppTest.tryItRunsTheRealModelOnWhatTheUserTyped` |
| Copy-only preview when the editor hides where its text starts | not run | — | Implemented (`review_copy` path when the window offset is unknown); no editor here hides it, so it was not exercised |
| Partial-context wording in the review panel | not run | — | Implemented (`review_scope_partial`); needs a draft longer than the 1,000-character window to show, not driven by a test |
| Masking applied after the text changed under the open review panel | not run on device | — | The guard is unit- and bridge-tested (stale draft, all-or-nothing replace); the full UI race was not staged |
| A "Try it" run that fails shows the real error | not run | — | Implemented; no failure was induced |
| Usefulness on a consented or synthetic English set (false positives, misses) | not run | — | Only a handful of fixed sentences were used. Redact detecting something is not proof that all personal information was found; Toxic is a prompt to re-read, not a verdict. |
| Notice latency after a pause | blocked | PHONE | Needs the physical device. Emulator: Redact about 33 ms, Toxic about 25 ms, plus the 400–450 ms pause. |

Full-suite run at the end of the milestone: 69 instrumented tests, all green; 62 executed and passed, 7 skipped by design (offline probe, kill probe ×2, stock-app probe, screenshot tour ×3).

## Milestone 5 — Tongue and Gist

Unit tests (JVM): 129 passed in total. Extended in this milestone: `AiCoordinatorTest` 26, `ModelRulesTest` 11.

| Check | Result | Target | Evidence |
|---|---|---|---|
| Gist's pinned files captured from a real SDK download | passed | EMU-37 | `ManifestCaptureTest` with `-e captureModel gist`: six files, 84,574,548 bytes |
| Gist through the app's own download; the SDK accepts the files; topics for a clear message; the limit is respected | passed | EMU-37 | `ModelSmokeTest.gist_tagsAMessageFromItsFixedListAndRespectsTheLimit` ("Food & Cooking" 0.998; about 1 s cold, 4 ms warm) |
| A confident language outside Redact's coverage skips Redact and is reported | passed | host | `AiCoordinatorTest.aConfidentLanguageRedactDoesNotCoverSkipsRedactAndSaysSo` |
| An unsure language guess changes nothing; a covered language runs Redact; hint off always runs it | passed | host | `AiCoordinatorTest` (three tests) |
| Redact's documented languages only (24 EU + Norwegian + Icelandic) | passed | host | `ModelRulesTest.redactCoversEuropeanLanguagesOnly` |
| Topics are computed only when asked; late topics for an older draft are dropped; never where AI may not run | passed | host | `AiCoordinatorTest` (three tests) |
| Real Tongue: Japanese text → no privacy notice; the panel says "Looks like Japanese" and "Does not cover Japanese" | passed | EMU-37 | `KeyboardAiTest.aLanguageThePrivacyCheckDoesNotCoverIsSkippedAndThePanelSaysSo` |
| With the hint off, the same text is still checked | passed | EMU-37 | `KeyboardAiTest.withTheLanguageHintOffTheSameTextIsStillChecked` |
| Real Gist: "Tag this draft" shows topics without changing anything; "Insert as hashtags" adds them at the cursor | passed | EMU-37 | `KeyboardAiTest.topicsAreComputedOnlyOnRequestAndInsertedOnlyOnTap` |
| "Try it" for Tongue and Gist in the companion app | not run | — | Implemented through the same path as the Redact "Try it" test; not driven separately |
| Language hint on short or mixed-script text on a device | not run | — | The rule (unsure hints change nothing) is unit-tested with a fake; real short and mixed inputs were not sampled |
| Topic quality across a range of messages | not run | — | One food sentence was used |

Full-suite run at the end of the milestone: 74 instrumented tests; 66 passed, 7 skipped by design, 1 failed. The failure was a flaky scroll in a companion-app test (a fling still moving when the tap landed); the swipe was replaced with a direct scroll-to-row, a second timing assumption in the same class was fixed, and `CompanionAppTest` then passed 7 of 7 four runs in a row.

## Milestone 6 — Voice tools

Unit tests (JVM): 154 passed in total. New or extended in this milestone: `AudioPipelineTest` 12 (mix-down, resampling, WAV encoding, filler and speaker wording, what the preview shows), `VoicePipelineTest` 10 (step order, what each step is given, failure and cancel rules, with stand-in models), `SdkDefaultsProbeTest` 2, `ModelRegistryTest` 9. `VoicePipelineTest` was written after the pipeline; it was checked against a deliberately broken pipeline (cleaned audio not passed on) and failed as it should.

All device rows ran on EMU-37B. Speech for the model tests is one English sentence written to a WAV file by Android's own text-to-speech, read back through the app's decoder; in the keyboard and Voice tools screen tests it stands in for the microphone (`FakeCapture`), with every model, the keyboard and the editor real.

| Check | Result | Target | Evidence |
|---|---|---|---|
| Pinned files for the five voice models captured from real SDK downloads | passed | EMU-37 | `ManifestCaptureTest` for voz, clear, ear, uhm, who; sizes asserted in `ModelRegistryTest` |
| Voz through the app's own download; the sentence comes back word for word, with a time for every word | passed | EMU-37B | `VoiceModelsTest.voz_transcribesSynthesisedSpeech` (22 words; 4.3 s cold, about 1 s warm for 6.8 s of audio) |
| Voz's memory is given back once it has sat unused | passed | EMU-37B | `VoiceModelsTest.voz_givesItsMemoryBackOnceItHasSatUnused` (resident memory 4.85 GB → 1.2 GB after 20 s) |
| A model too big for the device is refused before any download and can never load | passed | EMU-37B | `ModelMemoryGuardTest` (2): Voz refused at 3 GiB, offered at 8 GiB; small models unaffected |
| **Real crash, real recovery (out of memory):** Voz loaded on a 3 GB device, the system killed the app, and the next start switched Voz off and flagged it, files intact | passed | EMU-37 | `CrashRecoveryProbeTest` verify step after the kill; `lowmemorykiller` line in logcat |
| **Real crash, real recovery (native crash):** Clear on a software GPU dies in the vendor GPU library; the next start switched it off and flagged it | passed | EMU-37B, software GPU | `CrashRecoveryProbeTest` run step ("Process crashed", SIGSEGV in `libLiteRtClGlAccelerator.so`), then the verify step |
| A model switched off after a crash says so in the catalog until it is switched on again | passed | EMU-37B | `CompanionAppTest.aModelSwitchedOffAfterStoppingTheAppSaysSoUntilItIsSwitchedOnAgain` |
| Clear cleans speech; the 48 kHz result still transcribes correctly through Voz | passed | EMU-37B | `VoiceModelsTest.clear_cleansSpeechAtItsOwnRateAndTheResultStillTranscribes` (`-e clear true`; about 0.5 s for 6.8 s) |
| Ear identifies English and says it is reliable | passed | EMU-37B | `VoiceModelsTest.ear_hearsEnglish` |
| Uhm runs on speech and does not flag a fluent sentence | passed | EMU-37B | `VoiceModelsTest.uhm_runsOnSpeechWithAndWithoutWords` |
| Who marks turns in 20 s of speech (one speaker), and returns none for a 7 s clip without failing | passed | EMU-37B | `VoiceModelsTest.who_*` (2) |
| The pipeline runs only the steps that are on and installed, in order, and reports each part; with Clear on it cleans first and keeps the cleaned audio | passed | EMU-37B | `VoiceModelsTest.thePipelineRunsTheChosenStepsAndReportsEachPart`, `thePipelineCleansFirstAndKeepsTheCleanedAudioForSharing` |
| A failed step is named and the rest still run; later steps hear the cleaned audio; the filler step gets the transcript's words; a cancelled run returns nothing | passed | host + EMU-37B | `VoicePipelineTest` (10, stand-in models); `VoiceModelsTest.cancellingStopsThePipelineBetweenSteps` (real models) |
| Recording from the real microphone: mono 16 kHz, stops itself at the limit, cancel discards, no second recording while one runs | passed | EMU-37B | `AudioCaptureTest` (4). The emulator's microphone delivers silence, so this proves the recorder, not what was said |
| Imports: WAV and compressed (AAC) files are decoded to mono 16 kHz; a long file is cut at the limit; a file that is not audio is refused | passed | EMU-37B | `AudioImportTest` (4) |
| Sharing: a readable WAV behind a one-off read grant; files over an hour old are removed | passed | EMU-37B | `AudioImportTest` (2) |
| Keyboard: the microphone button shows only while Voz is on, installed and allowed; never in a password field | passed | EMU-37B | `KeyboardVoiceTest` (2) |
| Keyboard: dictation is shown as Listening → transcript preview, and typed only when Insert is tapped | passed | EMU-37B | `KeyboardVoiceTest.dictationIsPreviewedAndOnlyInsertedOnATap`; `docs/evidence/screenshots/m6_dictation_*.png` |
| Keyboard: Cancel discards the recording; hiding the keyboard closes the microphone | passed | EMU-37B | `KeyboardVoiceTest` (2) |
| Voice tools: reached from the Models tab and from a voice model's own screen; lists only the steps that will run; says so when none can | passed | EMU-37B | `VoiceToolsScreenTest` (4) |
| Voice tools: an imported recording is transcribed and previewed with its language; Copy puts the plain transcript on the clipboard | passed | EMU-37B | `VoiceToolsScreenTest.anImportedRecordingIsTranscribedPreviewedAndCanBeCopied`; `m6_voice_tools_result.png` |
| Voice tools: Record → Stop runs the steps; Cancel drops a run's result; leaving the screen closes the microphone; a non-audio file gets a plain reason | passed | EMU-37B | `VoiceToolsScreenTest` (4) |
| Voice tools: cleaned audio can be played, stopped, and shared through the system sheet | passed | EMU-37B | `VoiceToolsScreenTest.withClearOnTheCleanedAudioCanBeSharedThroughTheSystemSheet` (`-e clear true`) |
| Microphone refused: explained with "Ask again"; a second refusal leads to the app's Settings page; allowing there lets recording start — from the keyboard and from Voice tools | passed | EMU-37B | `MicPermissionFlowTest` (opt-in; the permission is revoked over adb first) |
| The permission screen steps aside when access is already granted | passed | EMU-37B | `AudioCaptureTest.thePermissionScreenStepsAsideWhenAccessIsAlreadyGranted` |
| Quick writes to one setting land in the order they were made | passed | EMU-37B | `SettingsSyncTest.quickWritesToOneSettingLandInTheOrderTheyWereMade` — failed first (an earlier value landed last), then fixed |
| Dictation with a real voice through a real microphone | blocked | PHONE | The emulator's microphone is silent |
| Clear on a phone's GPU; Voz on phones with 6, 8 and 12 GB | blocked | PHONE | Emulator memory and a desktop GPU say nothing certain about phones |
| Speech with real fillers (Uhm), a real multi-speaker recording (Who), other languages, a recording over two minutes | not run | — | Only the one synthesised English sentence was used |
| Microphone taken by another app mid-recording; sharing with no storage left | not run | — | Handled in code (`AudioCaptureController`, the share error message); not driven |
| Microphone permission revoked while the app is running | not run | — | Android ends the app's process when a permission is revoked; the next tap asks again |

Two things found while testing that are not the app's doing but shaped the tests:

- After "Copy transcript", Android shows its own clipboard preview with share buttons over the bottom-left of the screen for a few seconds. A later test's tap on a tab landed on its Quick Share button. The copy test now waits for the preview to go.
- The first emulator ran out of storage (5.8 GB) and memory (3 GB) for the voice models, so testing moved to EMU-37B.

Full-suite run at the end of the milestone (EMU-37B, with `-e clear true`): 119 instrumented tests; 107 passed, 12 skipped by design (the opt-in probes: file capture, the kill and offline probes, crash recovery, the refused-microphone flow, stock-app typing and the four screenshot tours, each run separately as listed above), none failed. Lint: 0 errors, 30 warnings (dependency-update notices and style hints). An earlier full run had one failure — the tab tap that landed on Android's clipboard preview, described above.

## Milestone 7 — Image and drawing tools

Unit tests (JVM): 160 passed in total. New or extended in this milestone: `VisionTest` 4 (how far a large picture is scaled down; which shapes are closed outlines), `SdkDefaultsProbeTest` 3, `ModelRegistryTest` 10 (all 13 models now have pinned files).

All device rows ran on EMU-37B. No explicit imagery was created or downloaded for any test.

| Check | Result | Target | Evidence |
|---|---|---|---|
| Pinned files for Eye, Moderator and Shapes captured from real SDK downloads; revisions read from each native library | passed | EMU-37B | `ManifestCaptureTest` for eye, moderator, shapes; sizes asserted in `ModelRegistryTest` |
| All 13 vendor artifacts in one build pass the metadata check, build and install | passed | host, EMU-37B | `:app:checkDebugAarMetadata assembleDebug installDebug` |
| Eye through the app's own download: a real capture of the screen is called a screenshot, tags are ordered by confidence, nobody is detected | passed | EMU-37B | `VisionModelsTest.eye_describesARealScreenshot` (about 2 s cold, 0.8 s warm) |
| Eye's memory is given back once it has sat unused | passed | EMU-37B | `VisionModelsTest.eye_givesItsMemoryBackOnceItHasSatUnused` (1.40 GB → 272 MB) — failed first, when only Voz was unloaded |
| Moderator passes a harmless picture (score 0.30); its own threshold decides the flag | passed | EMU-37B | `VisionModelsTest.moderator_passesAHarmlessPictureAndItsThresholdDecidesTheFlag` |
| Shapes: circle → ellipse, line → line, rectangle → rectangle with an outline where the stroke was; a random scrawl and a single tap are not turned into a shape | passed | EMU-37B | `VisionModelsTest.shapes_*` (3) |
| Image tools: reached from the Models tab; lists the checks that will run; says so and disables choosing when none can | passed | EMU-37B | `ImageToolsScreenTest` (2) |
| Image tools: a picked picture is shown with what Eye found and the sensitivity result | passed | EMU-37B | `ImageToolsScreenTest.aPickedPictureIsShownWithWhatTheModelsFound`; `docs/evidence/screenshots/m7_image_tools_result.png` |
| Image tools: closing the picker without choosing changes nothing; a file that is not a picture gets a plain reason; a 6000×4500 picture is scaled down and still analysed | passed | EMU-37B | `ImageToolsScreenTest` (3) |
| Image tools: an unflagged picture is shared straight away; a flagged one asks first, and declining shares nothing | passed | EMU-37B | `ImageToolsScreenTest` (2). The flag was forced by setting Moderator's threshold to zero |
| Drawing screen: a stroke drawn by touch is recognised and tidied; Clear starts again; a tangle says "No clear shape" and can be retried; a recognised drawing is shared through the system sheet | passed | EMU-37B | `DrawingTest` (3); `m7_drawing_result.png` |
| Keyboard: the draw tool shows only while Shapes is on, installed and allowed; never in a password field | passed | EMU-37B | `DrawingTest.theDrawToolIsOfferedOnlyWhereItCanRun` |
| Keyboard: the drawing panel stays within the keyboard's own height, with the app still in view | passed | EMU-37B | `DrawingTest.theDrawingPanelStaysWithinTheKeyboardsOwnHeight` — failed first (the panel filled the screen); `m7_keyboard_draw_panel.png` |
| Keyboard: a drawn shape is inserted into a field that takes pictures, as `image/png` the field can open, only when Insert is tapped | passed | EMU-37B | `DrawingTest.aShapeDrawnInTheKeyboardIsInsertedIntoAnEditorThatTakesPictures` (the debug host's picture field) |
| Keyboard: in a field that takes no pictures the button says "Share…" and opens the share sheet; the field is not handed anything | passed | EMU-37B | `DrawingTest.whereTheEditorTakesNoPicturesTheShapeIsOfferedThroughTheShareSheet` |
| Keyboard: leaving the drawing panel brings typing back untouched | passed | EMU-37B | `DrawingTest.leavingTheDrawingPanelBringsTypingBackUntouched` |
| The catalog shows no model as a placeholder | passed | EMU-37B | `CompanionAppTest.theCatalogListsAllThirteenModelsWithHonestStates` |
| Moderator on a picture it ought to flag | not run | — | No explicit imagery was used; the model's detection quality is unverified |
| Eye on real photos (people, pets, places, documents, rotated camera pictures) | not run | — | One screenshot and one drawn picture were used |
| The real system photo picker driven end to end | not run | — | The screen's pick handler is called directly with a file; the picker itself is the system's |
| Inserting a drawing into a real chat app | blocked | PHONE | Needs WhatsApp, Telegram or Discord signed in; only the debug host was used |
| A host that says it takes pictures but turns the insert down | not run | — | The fallback to the share sheet is in code (`KeyboardController.insertDrawing`); no such host was available |

Full-suite run at the end of the milestone (EMU-37B, with `-e clear true`): 142 instrumented tests; 129 passed, 13 skipped by design (opt-in probes), none failed. Lint: 0 errors, 32 warnings.

## Milestone 8 — Visual and accessibility audit

The screens were captured and set beside `design/01_Keyboard_Approved.png` and `design/02_Companion_App_Reference.png`. What the audit found and what was done:

| Found | Done |
|---|---|
| The AI panel was about 110dp taller than the keyboard once it listed five models, pushing the app up | Fixed at the keyboard's root: every panel is exactly the keyboard's height and scrolls inside (`KeyboardFootprintTest`, failed first) |
| The panel reopened wherever it had been scrolled to | It opens at the top each time (test failed first) |
| Switches had dark thumbs; sliders, headings and field underlines were lavender | White thumb on a blue track everywhere; the master AI switch carries the AI gradient; one accent colour |
| Model rows showed a letter | A drawn icon per model, in the catalog and the keyboard's panel; chevrons on rows that open a screen |
| Shift and backspace were font glyphs | Drawn icons (outlined, solid, and solid with a bar for caps lock) |
| Emoji suggestions were bare | "Emo" label and chips, as in the approved design |
| Settings and model screens were flat lists | Grouped rounded cards with small-capital headings |
| "Theme" and "Languages" looked like controls but did nothing | They now say, in words, that they are the only option in this version |
| A settings title was cut off with "…" | Titles wrap |
| Tool buttons were 44dp high | 48dp; the strip went from 40dp to 44dp |
| The app had no icon | An adaptive icon drawn for it |

Still different from the references, on purpose or for later: no clipboard or grid button in the tool row (no behaviour is specified for them); a choice setting opens a list instead of the two-part toggle; settings rows have no icons; "Try it" shows text rather than a chip; the microphone sits before settings, not after.

| Check | Result | Target | Evidence |
|---|---|---|---|
| The AI panel covers the keyboard and no more; the app above does not move | passed | EMU-37B | `KeyboardFootprintTest.theAiPanelCoversTheKeyboardAndNoMore` |
| Every typing model can be reached in the panel, with the master switch and both ways out still in view | passed | EMU-37B | `KeyboardFootprintTest` (2) |
| The drawing panel also stays within the keyboard's height | passed | EMU-37B | `DrawingTest.theDrawingPanelStaysWithinTheKeyboardsOwnHeight` |
| The three tabs pass Google's accessibility checks (labels, 48dp touch targets, contrast, clipped text) | passed | EMU-37B | `AccessibilityAuditTest.theThreeTabsPassTheAccessibilityChecks` |
| Model detail, Privacy, per-app switches, Voice tools, Image tools and Drawing pass the same checks | passed | EMU-37B | `AccessibilityAuditTest.theScreensOpenedFromTheTabsPassTheAccessibilityChecks` |
| The checks can fail: an unlabelled 20dp button is rejected | passed | EMU-37B | `AccessibilityAuditTest.theAuditItselfRejectsAnUnlabelledUndersizedButton` |
| Every tappable thing in the keyboard and its AI panel has a spoken label; keys are announced by name; tool and panel buttons are full-size targets | passed | EMU-37B | `AccessibilityAuditTest.everyKeyboardControlHasASpokenLabelAndToolsAreBigEnoughToHit` |
| Text pairs are at least 4.5 to 1 and meaningful shapes at least 3 to 1 | passed | EMU-37B | `AccessibilityAuditTest.textAndControlsMeetTheContrastRatios` (13 text pairs, 4 shape pairs) |
| The enable sweep plays once when asked, never when the saved state is merely shown, and not at all with system animations off | passed | EMU-37B | `AccessibilityAuditTest.theEnableSweepPlaysOnceOnRequestAndNeverWithAnimationsOff` |
| Large text (font size 1.3×): keyboard, AI panel, Models, model detail and Settings | passed (by eye) | EMU-37B | `docs/evidence/screenshots/m8_*_large_text.png`; titles wrap, nothing is cut off, the panel keeps its height and scrolls |
| Screens beside the two reference images | passed (by eye) | EMU-37B | `docs/evidence/screenshots/m8_*.png` |
| Key shapes against the keyboard background reach 3 to 1 | failed, accepted | — | About 1.5 to 1, as on stock keyboards; the label carries the meaning (ruling 69) |
| A pass with TalkBack switched on | not run | PHONE | Labels and roles were checked through the accessibility tree, not by listening |
| Font sizes above 1.3×, display-size scaling, right-to-left | not run | — | — |
| Rotation does not replay the enable sweep | not run | — | The sweep is only ever started by the off-to-on change in `KeyboardController.onSettings`; not driven on a rotating device |

Full-suite run at the end of the milestone (EMU-37B, with `-e clear true`): 151 instrumented tests; 137 passed, 13 skipped by design (opt-in probes), 1 failed. The failure was a catalog test that assumed Tongue was switched off when an earlier test had left it on (the screen rightly said "Paused"); the test now sets its own starting state, and the two classes involved then passed together, 23 of 23. After two lint-driven renames the affected screens were run again: 19 of 19. Unit tests: 160. Lint: 0 errors, 37 warnings (version notices and style hints).

## Milestone 9 — Hardening and reports

Unit tests (JVM): 161 passed. Added: `ModelDownloaderTest.aModelThatCannotFitOnTheDeviceIsRefusedBeforeAnythingIsFetched`.

No physical phone was connected, so every row that needs one is **blocked**, not passed.

| Check | Result | Target | Evidence |
|---|---|---|---|
| Clean build: metadata check, debug APK, unit tests, lint | passed | host | `./gradlew clean`, then `:app:checkDebugAarMetadata assembleDebug testDebugUnitTest lintDebug`: 161 tests, lint 0 errors and 37 warnings, `app/build/outputs/apk/debug/app-debug.apk` 700,096,566 bytes |
| A fresh clone of the repository builds by the README alone | passed | host | `git clone` into an empty folder, `local.properties` written as the README says, then the README's build command: BUILD SUCCESSFUL, 161 unit tests, lint 0 errors, the same 700,096,566-byte APK |
| Running the installed text models sends and receives nothing | passed | EMU-37B | `HardeningTest.runningInstalledModelsOnTextSendsAndReceivesNothing`: 125 runs across Emo, Redact, Toxic, Gist and Tongue, 0 bytes each way for the app's own network identity. The test then proves the meter moves with one small request; a device that does not meter per app would skip, not pass |
| Running the voice, image and drawing models sends and receives nothing | failed once, then passed | EMU-37B | `HardeningTest.runningTheVoiceImageAndDrawingModelsSendsAndReceivesNothing` (added after the review): three rounds of the voice pipeline with Clear, Ear, Voz, Uhm and Who, plus Eye, Moderator and Shapes, 0 bytes each way. In one full run it measured 80 bytes sent. That was not a model: a download finished a minute earlier had left its connection open, and the server was closing it. Fixed in the downloader (next section) |
| What the license check does | observed in part | EMU-37B | The SDK's own record shows one signed license per model per calendar month and a device identifier. Reloading four models with current records sent 40 bytes and received none. The request itself was not captured (see the compatibility report) |
| What is typed is not written to the app's storage or the system log | passed | EMU-37B | `HardeningTest.whatIsTypedIsNotWrittenToTheAppsStorageOrToTheLog`: a unique word typed with Emo, Redact, Toxic and Tongue on; every file in the app's data directory except model weights, and `logcat`, searched for it. Dictated transcripts and Gist were not part of this search; the code keeps them in memory only and has no logging |
| Deleting a model waits for a run in progress, then leaves nothing behind and switches it off | passed | EMU-37B | `HardeningTest.deletingAModelWaitsForARunInProgressAndLeavesNothingBehind` |
| Asking for a download twice starts one transfer | passed | EMU-37B | `HardeningTest.askingForADownloadTwiceStartsOneTransfer` (one WorkManager job) |
| Deleting from a model's own screen asks first; Cancel keeps it | passed | EMU-37B | `HardeningTest.deletingFromAModelsScreenAsksFirstAndCancelKeepsIt` |
| Resetting one model's settings leaves other models' settings alone | passed | EMU-37B | `HardeningTest.resettingOneModelsSettingsLeavesTheOthersAlone` |
| Models dropped under memory pressure load again on their next use | passed | EMU-37B | `HardeningTest.modelsDroppedUnderMemoryPressureLoadAgainOnTheirNextUse` (the app's own release call; Android was not actually starved of memory) |
| A model that cannot fit on the disk is refused before anything is fetched | passed | host | `ModelDownloaderTest` (unit) |
| The same on a device whose storage is nearly full | not run | — | The emulator has 13 GB free; the rule is unit-tested |
| The keyboard's process is killed while typing in another app, and typing carries on | passed | EMU-37B | By hand over adb in Android's Settings search: typed "q", killed the process (`run-as … kill -9`, pid 27710 → 28239), Android restarted the keyboard, typed "w"; the field read "qw". `docs/evidence/screenshots/m9_process_death_settings_search.png` |
| Typing works after the device sleeps and is woken | passed | EMU-37B | `input keyevent KEYCODE_SLEEP`, `KEYCODE_WAKEUP`, `wm dismiss-keyguard`, then two `KeyboardImeTest` tests |
| Cold start with the network off: emoji suggestions, privacy review with mask and undo, and shape recognition all work | passed | EMU-37B | Airplane mode on, app force-stopped, then `ModelSmokeTest#offline_…`, two `KeyboardAiTest` tests and `VisionModelsTest#shapes_recognisesACircleALineAndARectangle`: 4 of 4 |
| The keyboard keeps working after rotation | passed | EMU-37B | `KeyboardImeTest.keyboardKeepsWorkingAfterRotation` |
| Typing in WhatsApp, Telegram, Discord, a browser field and a notes app | passed in part, by the owner's report | PHONE | See "Phone session", part 3: the owner typed in real apps and reported that it worked, without naming them. Typing into another app's field (Settings search) was also driven over adb |
| Key handling time (p95) and time from last key to suggestion | measured | PHONE | See "Phone session", part 2: 12 ms and 200 ms at the 95th percentile on a OnePlus CPH2423 |
| Peak memory on a constrained phone; Voz on 6, 8 and 12 GB phones; Clear on a phone GPU | measured on one phone | PHONE | See "Phone session": a 12 GB phone held Voz at about 5 GB, and Clear runs on its GPU. No 6 or 8 GB phone was tried |
| Battery and heat during long typing with AI on | not run | PHONE | Not done in the phone session |
| Haptics and key sound felt and heard | passed, by the owner's report | PHONE | See "Phone session", part 3 |
| Dictation with a real voice; a recording with fillers and several speakers | dictation passed, by the owner's report; the rest not run | PHONE | See "Phone session", part 3. The phone's real microphone was also recorded from by `AudioCaptureTest` |
| A pass with TalkBack switched on | passed, by the owner's report | PHONE | See "Phone session", part 3 |
| A release-signed build | not run | — | Needs the owner's keystore; the deliverable is the debug-signed APK |

Full-suite run at the end of the milestone, on the clean build (EMU-37B, with `-e clear true`): 158 instrumented tests; 145 passed, 13 skipped by design (opt-in probes, each run separately as listed), none failed. Unit tests: 161. Lint: 0 errors, 37 warnings.

## Final review — fixes

An independent review of the whole branch found one critical problem, six important ones and fifteen minor ones. `PROGRESS.md` ("Final review") lists what was fixed, what was deferred and why. The rows below are the checks added or changed in the fix pass; each "failed, then passed" row was watched failing on the code as reviewed.

| Check | Result | Target | Evidence |
|---|---|---|---|
| A password typed while the on-screen keyboard is hidden is never read for AI | failed, then passed | EMU-37B | `EditorReadsTest.aPasswordTypedWithTheKeyboardHiddenIsNeverRead`. The debug host's password field counts every request for its text: more than zero before the fix, zero after |
| A password field is not read with the keyboard shown either | passed | EMU-37B | `EditorReadsTest.aPasswordFieldIsNotReadWithTheKeyboardShownEither` (the control; passed before and after) |
| Selecting a whole long note does not hand it to the models | failed, then passed | EMU-37B | `EditorReadsTest.selectingAWholeLongNoteDoesNotHandItToTheModels` |
| A model loaded twice in one process leaves no crash mark | failed, then passed | EMU-37B | `HardeningTest.aModelLoadedTwiceInOneProcessLeavesNoCrashMark` |
| Switching a stopped model back on from its catalog row clears the note | failed, then passed | EMU-37B | `CompanionAppTest.aModelSwitchedOffAfterStoppingTheAppSaysSoUntilItIsSwitchedOnAgain`, now tapping the row's own switch |
| Two downloads in progress do not hold up other file work | failed, then passed | EMU-37B | `HardeningTest.twoDownloadsInProgressDoNotHoldUpOtherFileWork` |
| Edits that overlap are refused whole and change nothing | failed, then passed | EMU-37B | `EditorBridgeTest.replaceRanges_refusesEditsThatOverlapAndChangesNothing` |
| A status label in the strip is not a button; Tag is offered only with text to tag | failed, then passed | EMU-37B | `KeyboardVoiceTest.theListeningLabelDoesNotPresentItselfAsAButton`, `KeyboardAiTest.theTagActionIsOfferedOnlyWhenThereIsSomethingToTag` |
| A damaged settings file starts again from defaults | failed, then passed | EMU-37B | `SettingsSyncTest.aDamagedSettingsFileStartsFromDefaultsInsteadOfFailing` |
| A finished download leaves no connection open | failed, then passed | EMU-37B | `HardeningTest.aFinishedDownloadLeavesNoConnectionOpen`: the app's open sockets are counted before and after fetching one small model file from the real server. One more after the download before the fix; the same number after it. Not found by the review; found by the full run |
| Backspace and Enter leave the host app in touch mode | passed | EMU-37B | `KeyboardImeTest.backspaceAndEnterLeaveTheAppInTouchMode`. Passed before the change as well on Android 17, so it guards the behaviour without having proved a fault |
| Moving to another field ends a dictation | passed | EMU-37B | `KeyboardVoiceTest.movingToAnotherFieldEndsTheDictation`. Written to reproduce a review finding; it passed without any change, so nothing was changed for it |
| A key pressed while the keyboard has no session starts one | not run | — | The code is there (`KeyboardController.onKey`); no test produces that order of events |
| The keyboard carries on after a settings read error | not run | — | An error handler was added to the keyboard's subscription; no test forces the error |
| Clean build after the fix pass: metadata check, debug APK, unit tests, lint | passed | host | `clean :app:checkDebugAarMetadata assembleDebug testDebugUnitTest lintDebug`: 161 tests, lint 0 errors and 37 warnings, `app/build/outputs/apk/debug/app-debug.apk` 700,096,810 bytes |
| A fresh clone of the final commit builds by the README alone | passed | host | `git clone` of commit `5131f30` into an empty folder, `local.properties` written as the README says, then the README's build command: BUILD SUCCESSFUL, 161 unit tests, lint 0 errors, the same 700,096,810-byte APK |

The whole device suite was run three times on that work (EMU-37B, with `-e clear true`):

1. 170 tests: 155 passed, 13 skipped, **2 failed**. Both were faults in the tests, not the app: a delete test that raced the run it was meant to interrupt, and an accessibility audit that used "close the keyboard" as its trigger and timed out waiting for the system seconds after a reinstall (it passed five times out of five alone). Both tests were corrected.
2. 170 tests: 156 passed, 13 skipped, **1 failed**: the 80 bytes described above, a real fault in the downloader. Fixed, with a new test.
3. 171 tests: **158 passed, 13 skipped by design (opt-in probes), none failed.**

## Phone session (PHONE)

Started 18 Nov 2026 on the owner's phone. This section is filled in as the session goes; rows still marked **blocked** wait for a step only the owner can take on the phone.

Part 1 needed nothing from the owner: the debug APK was installed over adb (40 seconds) and the tests that run models, without touching the keyboard or any system setting, were run. The automated tests that select the keyboard or change a setting were not run on the phone at this stage.

| Check | Result | Target | Evidence |
|---|---|---|---|
| The debug APK installs and the models download over Wi-Fi | passed | PHONE | `adb install`; all 12 downloadable models fetched by the app's own downloader, 1.5 GB on disk |
| Emo, Redact, Toxic, Gist and Tongue give real answers | passed | PHONE | `ModelSmokeTest`: 7 passed, 3 opt-in probes skipped |
| Eye, Moderator and Shapes give real answers; Eye gives its memory back when idle | passed, one failed | PHONE | `VisionModelsTest`: 5 of 6 passed. **Failed:** `eye_describesARealScreenshot`. Eye called a capture of the phone's screen "illustration or art"; the test expects "screenshot", which is what it says on the emulator. A wrong label from the model on a real screen, not a crash |
| Voz, Ear, Uhm and Who give real answers; the pipeline runs the chosen steps | passed | PHONE | `VoiceModelsTest`: 9 passed without Clear |
| Clear runs on the phone's GPU and its output still transcribes | passed | PHONE | `VoiceModelsTest#clear_…` and `#thePipelineCleansFirst…` with `-e clear true`, on a Mali-G610. On the emulator's software GPU the same call crashes |
| The memory guard, audio import and the editor bridge | passed | PHONE | `ModelMemoryGuardTest` 2, `AudioImportTest` 6, `EditorBridgeTest` 16 |
| Peak memory with Voz loaded | measured | PHONE | The app's total (`dumpsys meminfo`, sampled every two seconds) peaked at 5.2 GB with Voz loaded and at 5.9 GB with Clear and the other voice models loaded as well. The emulator figure was 3.9 GB. The phone had 3.9 GB available beforehand and held it. The line below which Voz is refused was raised from 5 GiB to 6.5 GiB on this evidence (`ModelRegistryTest`, failed first) |
| Peak memory with Eye and Moderator loaded | measured | PHONE | About 1.2 GB resident (1.5 GB on the emulator) |
| Running the typing models sends and receives nothing | passed | PHONE | `HardeningTest.runningInstalledModelsOnTextSendsAndReceivesNothing`, on the phone's own Wi-Fi |
| Running the voice, image and drawing models, Clear included, sends and receives nothing | passed | PHONE | `HardeningTest.runningTheVoiceImageAndDrawingModelsSendsAndReceivesNothing` with `-e clear true`: 0 bytes each way |
| A finished download leaves no connection open | passed | PHONE | `HardeningTest.aFinishedDownloadLeavesNoConnectionOpen` |
| A model loaded twice leaves no crash mark; models dropped under memory pressure load again; a delete waits for a run | passed | PHONE | Three `HardeningTest` methods |
| How much the license check sends | not captured | PHONE | Each model's first load on this phone made the vendor's check. The traffic around Clear's first load was logged, but the phone's log had rolled over before it was read. Not repeated: it would cost a second activation |

Part 2 followed the owner's steps on the phone: they approved OnePlus's install prompt, enabled and selected the keyboard with the app's own two setup buttons, and granted the microphone from Voice tools. Do Not Disturb was asked for and was off during the run; the owner said to go ahead.

| Check | Result | Target | Evidence |
|---|---|---|---|
| The app's setup screen gets the keyboard enabled and selected on OxygenOS | passed | PHONE | Done by the owner by hand; Android then named AI Keyboard as the selected keyboard |
| Key press to the character being in the field | measured | PHONE | `LatencyProbeTest`, 200 keys each, about ten keys a second, in the debug host. AI off: median 9 ms, 95th percentile 12 ms, longest 13 ms. AI on (Emo, Redact, Toxic, Tongue): 11 ms, 13 ms, 14 ms. Measured from the finger leaving the key (an injected touch) to the field's text changing. The debug host shares the keyboard's process, so the hop to another app is not in these figures |
| Last key to an emoji suggestion | measured | PHONE | 30 words with the models loaded: median 175 ms, 95th percentile 200 ms, longest 419 ms, of which 150 ms is the pause before a model is run. With no model loaded yet: 453 ms (one trial) |
| The longest the main thread was held up | measured | PHONE | 13 ms while typing with AI on; 29 ms while the models were being loaded in the background. A model never ran on it |
| How long each typing model takes (median / 95th percentile) | measured | PHONE | 57-character draft: Emo 9 / 11 ms, Redact 50 / 60, Toxic 53 / 65, Gist 25 / 30, Tongue 1 / 2. 944-character draft: Emo 11 / 13, Redact 512 / 552, Toxic 102 / 125, Gist 67 / 72, Tongue 2 / 5. 40 runs each |
| Memory while typing with AI on | measured | PHONE | The app's total was about 550 MB, 673 MB at most, with the four typing models loaded |
| Memory with the keyboard selected and nothing in use | measured | PHONE | 32 to 41 MB over 45 minutes with the phone left alone (620 samples); the same process throughout, the keyboard still selected at the end, nothing in the crash log |
| Typing, shift, symbols, backspace over an emoji family, Enter actions, rotation | passed | PHONE | `KeyboardImeTest` 15 of 15 |
| Emoji, privacy notice, review, mask and undo, tone notice, tag, AI off in password and no-learning fields | passed | PHONE | `KeyboardAiTest` 16 of 16; `EditorReadsTest` 2 of 3 (the third does not apply, below) |
| The drawing panel, the keyboard's fixed footprint, the image tools screen | passed | PHONE | `DrawingTest` 8, `KeyboardFootprintTest` 3, `ImageToolsScreenTest` 8 |
| The companion app, settings reaching the keyboard, downloads through the worker, the accessibility checks | passed | PHONE | `CompanionAppTest` 8, `SettingsSyncTest` 4, `ModelDownloadDeviceTest` 3 of 3 run, `AccessibilityAuditTest` 5 of 5 run, `SetupScreenTest` 1 of 1 run |
| Hardening: network silence, nothing typed kept, downloads and deletes overlapping | passed | PHONE | `HardeningTest` 11 of 11 |
| All model tests again, with Clear | passed | PHONE | `ModelSmokeTest` 7, `VisionModelsTest` 6 of 6, `VoiceModelsTest` 11 of 11. The screenshot test that failed in part 1 passed here: the app was on screen this time, not the phone's home screen |
| Recording from the microphone; dictation in the keyboard; the Voice tools screen | failed in the tests, then passed | PHONE | First run: `AudioCaptureTest` 5, `KeyboardVoiceTest` 7 and `VoiceToolsScreenTest` 10 all stopped before they began, because each test tried to grant the microphone itself and the phone refused (`SecurityException: Error granting runtime permission`) although the owner had already granted it. The tests now leave an existing grant alone. Re-run with the corrected test package: `AudioCaptureTest` 5 of 5 on the phone's real microphone, `VoiceToolsScreenTest` 10 of 10, `KeyboardVoiceTest` 6 of 7. The seventh failed once with "keyboard did not appear" and then passed seven times out of seven, alone and paired with the test before it (see the row on the keyboard not appearing) |
| A password typed with the on-screen keyboard hidden | does not apply | PHONE | The test hides the keyboard the way a hardware keyboard does; this phone has none, so the keyboard stayed up. The test now skips where there are no hardware keys. The same guarantee with the keyboard shown passed |
| Typing into Android's Settings search (another app's field) | passed by hand; the test was wrong | PHONE | By hand over adb: Settings search opened (`com.android.settings.intelligence`), the keyboard came up, and tapping its q and w keys put "qw" in that app's field. `StockAppTypingTest` had failed because it looked for an `EditText` and OnePlus's field is an `AutoCompleteTextView`; it now accepts both and passes on the emulator. It was not re-run on the phone |
| The keyboard's process is killed while typing in another app, and typing carries on | passed | PHONE | In the same Settings search field: typed "q", killed the keyboard's process (`run-as … kill -9`), Android had a new process bound and the keyboard showing 1.8 seconds later, typed "w"; the field read "qw" |
| What a force-stop of the app does | observed | PHONE and EMU-37B | `adb shell am force-stop com.example.aikeyboard` makes Android select another keyboard (Gboard on both) within a second, whether or not the keyboard was showing. AI Keyboard stays enabled and has to be chosen again. This is Android's own behaviour: the Android 17 emulator does the same. Whether clearing the app from OnePlus's recents screen counts as a force-stop has not been tried; it needs the owner's hand |
| The keyboard appears when a field is tapped | **failed four times, not explained** | PHONE | Minutes after the keyboard was first enabled on the phone, `LatencyProbeTest` run on its own tapped a field and no keyboard came within eight seconds, three times out of three; later tests in the same run got one. Hours later the same run showed the keyboard 1.2 seconds after the tap. One dictation test also failed this way once in eight runs. Ruled out: the stop that starting a test run causes does not change the selected keyboard, and a killed keyboard is back in under two seconds. Not ruled out: something slow the first times a newly enabled keyboard is shown on this phone, or a fault in the keyboard. Never seen on the emulator |
| Peak memory over the whole run | measured | PHONE | 6.1 GB, with Voz, Clear and the other voice models loaded together |
| Battery temperature over the 12-minute run | observed | PHONE | 31.5 °C before, 34.2 °C after, on USB power. Not a battery or heat test |
| Left out on the phone on purpose | not run | PHONE | The tests that switch Wi-Fi off, turn animations off, or disable the keyboard (ruling 91). `show_ime_with_hard_keyboard`, which the tests set to 1, was put back to 0. The force-stop experiments switched the selected keyboard to Gboard; AI Keyboard was selected again afterwards with `ime set` |
| Typing in WhatsApp, Telegram, Discord, a browser and a notes app | blocked when this table was written | PHONE | Done by the owner afterwards; see part 3 |
| Dictation with a real voice; haptics and key sound; a pass with TalkBack | blocked when this table was written | PHONE | Done by the owner afterwards; see part 3 |

Totals for the suite on the phone (`-e clear true -e stockApps true`, three tests left out): 171 run, 132 passed, 15 skipped, 24 failed. 22 of the 24 are the microphone-grant fault in the tests.

The test changes made during the phone session were then run as the whole suite on the emulator (EMU-37B, `-e clear true`): 174 run, 158 passed, 16 skipped by design (13 opt-in probes and the three latency measurements), none failed.

Part 3 is the hands-on part, done by the owner on their own phone on 18 Nov 2026 with the build of commit `bcd3ccb`. **These rows rest on the owner's word.** They were given a list of five checks (clear the app from recents; type in real apps and watch emoji suggestions, a privacy notice with mask and undo, Enter, and the keyboard coming up on the first tap; dictate with their own voice; haptics and key sound; TalkBack) and answered "All worked". Their apps were not read or captured, and they did not say which apps they used. The last column says what the phone itself could and could not confirm.

| Check | Result | Target | What the phone shows |
|---|---|---|---|
| Typing in real apps: keys, emoji suggestions, privacy notice with mask and undo, Enter, the keyboard on the first tap | passed, by the owner's report | PHONE | The typing models were loaded and in use from 13:59 to 14:12 and again after 14:21 (the keyboard's process at about 510 to 540 MB). No crash. Which apps were used was not said, so no named chat app can be listed as tried |
| Clearing AI Keyboard from the recents screen | passed, by the owner's report | PHONE | Afterwards AI Keyboard was still the selected keyboard and its process had run without a restart since 14:16. Whether OnePlus's recents force-stops other apps is not something this shows |
| Dictation with the owner's own voice | passed, by the owner's report | PHONE | Not confirmed by anything on the phone: the watch that would have shown the speech model loading had ended |
| Haptics and key sound | passed, by the owner's report | PHONE | Cannot be confirmed from outside |
| A pass with TalkBack | passed, by the owner's report | PHONE | Cannot be confirmed from outside; TalkBack was off when the phone was read afterwards |
| The app over the session | observed | PHONE | Nothing naming the app in the phone's crash log at any point from the first install to the end |
| Battery and heat over a long typing session | not run | — | Only the battery temperature around the 12-minute test run was noted |
| A recording with fillers and several speakers; speech in a language other than English | not run | — | — |

## Picture panel in the keyboard (added 18 Nov 2026)

Asked for by the owner during the phone session: a picture button on the keyboard while the image models are on, with the result shown in the keyboard. Android's picker is a full-screen system screen, so the keyboard steps aside while a picture is chosen and comes back with the result. The panel shows what Eye and Moderator found. At the owner's next request it also sends the picture: Insert where the field takes pictures, Share elsewhere, and a flagged picture only after a warning and a second tap.

| Check | Result | Target | Evidence |
|---|---|---|---|
| The picture button shows only while Eye or Moderator is on and installed | failed, then passed | EMU-37B | `KeyboardImageTest.thePictureButtonIsOfferedOnlyWhileAnImageModelCanRun` |
| It is not offered in a password field | passed | EMU-37B | `KeyboardImageTest.thePictureButtonIsNotOfferedInAPasswordField`. Passed before the button existed too; it guards the rule from here on |
| Tapping it opens the picker through the app's own screen; closing the picker changes nothing | failed, then passed | EMU-37B | `KeyboardImageTest.tappingTheButtonOpensThePickerScreenAndClosingItChangesNothing` |
| A chosen picture is described and checked in the keyboard; the draft is untouched; Back returns to the keys | failed, then passed | EMU-37B | `KeyboardImageTest.aChosenPictureIsDescribedAndCheckedInTheKeyboard` |
| A picture chosen while the keyboard is away is shown when it returns | failed, then passed | EMU-37B | `KeyboardImageTest.aPictureChosenWhileTheKeyboardIsAwayIsShownWhenItReturns`. The first two versions of the feature failed this: Android starts a field's input twice within milliseconds on the way back, and a window with nothing to type into can hold the keyboard for a moment. A picture is now tied to the field the picker was opened from |
| Moving to another field closes the panel and forgets the picture | failed, then passed | EMU-37B | `KeyboardImageTest.movingToAnotherFieldClosesThePanelAndForgetsThePicture` |
| A checked picture is inserted where the field takes pictures; nothing is sent before the tap; the panel then closes | failed, then passed | EMU-37B | `KeyboardImageTest.aCheckedPictureIsInsertedWhereTheFieldTakesPictures`: the debug host's picture field received an `image/jpeg` it could open |
| Where the field takes no pictures the button says Share and opens the system share sheet | failed, then passed | EMU-37B | `KeyboardImageTest.whereTheFieldTakesNoPicturesTheShareSheetIsOffered` |
| A flagged picture is sent only after a warning and a second tap | failed, then passed | EMU-37B | `KeyboardImageTest.aFlaggedPictureIsSentOnlyAfterAWarningAndASecondTap`, with Moderator's threshold at zero so that a harmless picture is flagged (no explicit imagery is used) |
| The drawing panel still inserts and shares after its send path was shared with the picture panel | passed | EMU-37B | `DrawingTest` 8 of 8 |
| The whole trip through Android's real picker | passed | EMU-37B | `KeyboardImageTest.theSystemsPickerHandsAChosenPictureBackToTheKeyboard`, opt-in with `-e picker true`, three runs. Emulator only: the picker shows the device's own pictures, so it must not be run on a phone |
| From another app's field: the picker returns to that app, not to AI Keyboard, and the keyboard comes back by itself with the result | passed by hand | EMU-37B | Settings search, driven over adb: picture button, picker, a picture, Done. Focus returned to Settings search; the panel read "Looks like: screenshot" and "Sensitive-content check: nothing flagged." `docs/evidence/screenshots/picture_panel_keyboard.png` |
| On the phone, the first version (analysis only) | observed, not reported | PHONE | Installed on 18 Nov 2026. A minute later the app's memory on the phone rose from about 140 MB to 1.0 GB for some twenty seconds and fell back: the image models loading, running and being let go of in the keyboard's process. No crash, the same process throughout, the keyboard still selected. The owner then asked for sending from the panel. What they saw on screen was not reported |
| On the phone, the build with sending | passed, by the owner's report | PHONE | Installed at 14:12 on 18 Nov 2026 (commit `bcd3ccb`). Asked how sending a picture from the keyboard went, the owner answered "It worked". What the phone shows agrees: at 14:17 the app's memory rose to 1.0 GB for about twenty-five seconds (the image models), and one JPEG of 104 KB was written to the app's share folder in the same minute, which only a tap on Insert or Share does. No crash; the keyboard still selected. Which app it was sent in, and whether that app attached the picture or sent it at once, was not said |
| What a chat app does with an inserted picture | not recorded | PHONE | Attach it to the draft or send it at once is that app's choice. The owner sent a picture on the phone but did not say in which app or what the app did with it |
| Typing with AI on, on the phone, by the owner | observed, not reported | PHONE | Between 13:59 and 14:12 the keyboard's process held about 540 MB, which is the typing models loaded and in use. No crash. What the owner saw (suggestions, notices, mask and undo) has not been reported, so the typing rows above stay blocked |
| A file that cannot be read as a picture; the screen turned while the picker is open | not run | — | The code is there (a message is shown; the picker is not opened twice); no test drives either |

After the feature, with sending: 161 unit tests, lint 0 errors and 37 warnings, and the whole suite on the emulator (EMU-37B, `-e clear true`): 184 run, 167 passed, 17 skipped by design (13 opt-in probes, the three latency measurements and the real-picker test), none failed.

## Microphone screen's return path (18 Nov 2026)

Noticed while building the picture panel, fixed at the owner's request.

| Check | Result | Target | Evidence |
|---|---|---|---|
| With AI Keyboard open in the background, the microphone screen opened from another app's field returns to that field when closed | failed, then passed | EMU-37B | By hand over adb, in Settings search with the microphone not yet allowed: microphone button, refuse in the system dialog, Close. Before: the screen that came up was AI Keyboard's own. After giving the microphone screen a task of its own: Settings search again |
| The screens the keyboard opens have a task of their own | failed, then passed | EMU-37B | `AudioCaptureTest.theScreensTheKeyboardOpensHaveATaskOfTheirOwn` (the microphone screen failed it; the picture picker screen already passed) |
| The refusal flow opened from inside the app still works | passed | EMU-37B | `MicPermissionFlowTest` (opt-in, with the microphone revoked first) |
| On the phone | not run | PHONE | The owner had already allowed the microphone there, so the screen does not appear |

After the fix: 161 unit tests, lint 0 errors and 37 warnings, and the whole suite on the emulator (EMU-37B, `-e clear true`): 185 run, 168 passed, 17 skipped by design, none failed.

## Kids Chat demo (18 Nov 2026)

Asked for by the owner after the keyboard was finished: a first demo of a chat for children, built on the same models. Their decisions: one phone with two pretend users, no parent side, unkind wording and nude pictures cannot be sent, and personal details are shown masked with the choice to send masked, change the message or send it as written. It is a screen of this app with its own home-screen icon. It was installed on the owner's phone on the evening of 18 Nov 2026; what it does there has not been reported yet.

| Check | Result | Target | Evidence |
|---|---|---|---|
| The sending rules, with stand-in models: a listed word is refused whatever its case or punctuation and is not matched inside a longer word; wording the tone model flags is refused, also below the model's own threshold; personal details come back masked; unkind wording wins over personal details; an ordinary message is clear; a model that fails means not sent; a flagged or uncheckable picture is refused | failed, then passed | unit | `ChatChecksTest`, first 13 tests. Against an empty implementation 10 failed and the 3 that guard against refusing too much passed |
| A message longer than 1,000 characters is not sent; one of exactly that length is | failed, then passed | unit | `ChatChecksTest.aMessageLongerThanTheChecksWereTriedAtIsNotSent` |
| Unkind words at the very end, or in the middle, of the longest message allowed are still refused; an email address at its end is still found | passed | EMU-37B | `KidsChatTest.unkindWordsAnywhereInTheLongestMessageAreStillRefused`, `personalDetailsAtTheEndOfTheLongestMessageAreStillFound`. They passed on their first run: the models already saw that far (see the second measurement below) |
| The field takes no more than 1,000 characters | passed | EMU-37B | `KidsChatTest.theFieldTakesNoMoreThanTheChecksWereTriedAt`. First run after the limit was in, so never seen failing on the device; the rule itself was seen failing as a unit test |
| The same rules through the real models: a threat is refused; a swear word aimed at nobody is refused by the word list; a friendly message is clear; an email address comes back as `[EMAIL_1]`; a harmless drawing may be sent and is refused at a threshold of zero | passed | EMU-37B | `KidsChatTest`, first five tests. They passed on their first run, the rules having been built against the unit tests |
| A threat stays in the field under a warning and is not sent | failed, then passed | EMU-37B | `KidsChatTest.aThreatStaysInTheFieldWithAWarningAndIsNotSent`, first run against an empty screen |
| A clean message is sent; for the other user it sits on the other side, and they can reply | failed, then passed | EMU-37B | `KidsChatTest.aCleanMessageIsSentAndTheOtherUserSeesItAndCanReply`, first run against an empty screen |
| The chat is locked while a model is missing, and its Download button brings it back | failed, then passed | EMU-37B | `KidsChatTest.theChatIsLockedUntilEverySafetyModelIsInstalled`: Moderator is deleted, the screen shows the lock with Send and the field off, Download fetches it, the lock goes. First run against an empty screen |
| Personal details raise the card; **Send like this** sends the masked text and empties the field; **Change it** sends nothing and gives the message back; **Send as written** sends it as typed | passed | EMU-37B | Three `KidsChatTest` tests. First run against the finished screen, so they were never seen failing |
| The card of the longest message allowed can still be answered | failed, then passed | EMU-37B | `KidsChatTest.theCardForTheLongestMessageCanStillBeAnswered`. Found by the review: the message is shown twice above the buttons, which pushed them off the screen with everything else switched off. The card now scrolls |
| A message that could not be checked is not sent and the sender is told; the same for a picture | passed, and seen failing | EMU-37B | `aMessageThatCouldNotBeCheckedIsNotSentAndTheSenderIsTold`, `aPictureThatCouldNotBeCheckedIsNotSent`, with a stand-in model that fails. Added after the review; both failed against a copy of the screen broken on purpose to send such things |
| Loosening the keyboard's settings does not loosen the chat | passed, and seen failing | EMU-37B | `looseningTheKeyboardsSettingsDoesNotLoosenTheChat`: with email addresses switched off for the keyboard, the chat still raises the card. Failed against a copy broken on purpose to read the keyboard's settings |
| What is sent is what was checked, whatever is typed while the check runs | passed | EMU-37B | `whatIsSentIsWhatWasCheckedWhateverIsTypedMeanwhile`, with a stand-in model that waits. Never seen failing |
| The locked chat leads to the Models screen, where the reason for a waiting or failed download is shown | failed, then passed | EMU-37B | `theLockedChatLeadsToTheModelsScreenWhereTheReasonIsShown`. Found by the review: the lock said "Downloading" for as long as a download waited for Wi-Fi |
| Turning the phone keeps the conversation | passed | EMU-37B | `KidsChatTest.turningThePhoneKeepsTheConversation`. Also first run against the finished screen |
| A harmless picture is sent as a picture; a picture the check refuses is not sent and the sender is told; a file that is not a picture is not sent | failed, then passed | EMU-37B | Three `KidsChatTest` tests, all first run against a screen whose picture handler did nothing. The refusal uses a harmless drawing under rules whose threshold is zero: no explicit picture exists in this project |
| The screen passes the accessibility checks when empty, with a message and a warning, and with the card | failed, then passed | EMU-37B | `AccessibilityAuditTest.kidsChatPassesTheAccessibilityChecksInEachOfItsStates`. It failed on the empty conversation list, which had no label for a screen reader |
| By hand: two users exchange messages, an insult is refused, an email raises the card | passed by hand | EMU-37B | Driven over adb. `docs/evidence/screenshots/kids_chat_warning.png`, `kids_chat_personal_card.png` |
| By hand: a picture through Android's real picker | passed by hand | EMU-37B | Picture button, the picker, a drawn beach scene from the emulator's gallery, Done. It appeared in the chat. `docs/evidence/screenshots/kids_chat_picture.png` |
| This project's keyboard stays quiet in the chat's field | not run | — | The field carries the "no personalised learning" mark, which `InputEligibilityTest` shows the keyboard obeys. No test types in the chat with the keyboard's AI on |
| On the phone: installed | passed | PHONE | Installed at 20:50 on 18 Nov 2026 at the owner's request (commit `882b9cf`, the build the emulator suite ran on). The phone lists both icons, AI Keyboard and Kids Chat; the three models the chat needs are on it with the sizes the registry expects; the keyboard stayed selected; no crash was logged |
| On the phone: using the chat | passed, by the owner's report | PHONE | Given five things to try (two users exchanging messages, a swear word or insult, a phone number or email address, a picture, and the friendly message the strict setting refuses), the owner answered "Its working". They did not say which of the five they tried. Nothing on the phone's screen was read or captured |
| Moderator on a picture it should flag | not run | — | Never shown one, here or anywhere in this project (ruling 55). That it flags real nudity is the vendor's claim |
| Large text, landscape, a long conversation, many pictures, TalkBack by ear, a keyboard other than this project's | not run | — | |

What the tone model made of ten sample messages under the chat's rules (strictest setting), and what the chat then did. "Model" is what Toxic alone flagged:

| Sample | Model | Scores (hateful / abusive / threat) | Chat |
|---|---|---|---|
| You are a stupid idiot and I hate you | abusive | 0.02 / 0.93 / 0.01 | refused |
| Nobody likes you, loser | abusive | 0.01 / 0.99 / 0.00 | refused |
| I will beat you up after school | abusive, threat | 0.05 / 0.50 / 0.82 | refused |
| I know where you live and I will hurt you | abusive | 0.02 / 0.46 / 0.17 | refused |
| People like you should not exist | abusive, threat | 0.13 / 0.83 / 0.50 | refused |
| This game is so damn hard | nothing | 0.00 / 0.02 / 0.00 | sent ("damn" is not on the list, on purpose) |
| That was a shitty game | nothing | 0.00 / 0.19 / 0.00 | refused, by the word list alone |
| What the fuck was that | nothing | 0.00 / 0.03 / 0.00 | refused, by the word list alone |
| Do you want to play football after school? | nothing | 0.00 / 0.01 / 0.00 | sent |
| You killed it in the match today, great job! | threat | 0.01 / 0.03 / 0.26 | **refused, wrongly** |

Read with care: ten sentences in English, written for this test. They show that the word list is needed (the model let both swear words through), that the strict setting is what caught the fourth sample, and that the same setting refuses a friendly message that sounds violent. They say nothing about slang, misspellings, other languages or how often either mistake happens.

How far into a long message the two text models still see. Each message is friendly filler ("We had so much fun at the park today.") with the threat, or the email address, as its last words:

| Length in characters | Threat at the end | Email address at the end |
|---|---|---|
| 250 | refused | found |
| 500 | refused | found |
| 1,000 | refused | found |
| 2,000 | refused | found |
| 4,000 | refused | found |

Nothing longer was tried, so the chat allows 1,000.

Whether naming a place raises the personal-details card (addresses are watched, and the model's address labels include city and state):

| Message | Chat |
|---|---|
| We went to London on Saturday | card: "We went to [CITY_1] on Saturday" |
| I live in Mumbai | card: "I live in [CITY_1]" |
| My house is 12 Park Street | card: "My house is [BUILDING_NUMBER_1] [STREET_NAME_1]" |
| Let's meet at the park after school | sent |

The first row is the cost of watching addresses: a harmless mention of a city is stopped too. Left as it is, for the owner to decide (ruling 129).

An independent review of the feature found no way for a message or picture to reach the conversation unchecked or changed. Its two faults and its test gaps are the rows above marked as found by the review or added after it; three small findings were left (PROGRESS.md, "Review of Kids Chat").

After the feature and the review's fixes: 175 unit tests, lint 0 errors and 39 warnings (two more than before, both the note about `accessibilityHeading` that the app's other screens already carry), a 701.8 MB debug APK, and the whole suite on the emulator (EMU-37B, `-e clear true`): 213 run, 193 passed, 20 skipped by design (the 17 as before and the chat's three measurements), none failed. The suite was run in full three times as the feature grew, each time with no failure.

## Kids Chat, redesigned for young children (18 Nov 2026)

The owner tried the first version on the phone, said it looked like a demo, and asked for a screen made for little children, to be shown to them before it was built. A mockup of four screens was shown and approved. The checks did not change: the same word list, models and rules decide what is sent. What changed is the look and the words, and how a hidden detail reads ("[email]" drawn as a lock chip, where the section above still says `[EMAIL_1]`).

| Check | Result | Target | Evidence |
|---|---|---|---|
| A hidden detail reads as a plain word in brackets; a street and its number become one "[address]"; different kinds each get their own word; a kind with no word of its own is still hidden | failed, then passed | unit | Four `ChatChecksTest` tests, all four seen failing before the words existed |
| The child whose turn it is is named and has the big face; a tap on the other face swaps | passed, and seen failing | EMU-37B | `KidsChatTest.theChildWhoseTurnItIsIsNamedAndHasTheBigFace`, failed against a copy of the screen broken on purpose to leave the turn unnamed and the faces the same size |
| A refusal comes as a tip that says what to do | passed, and seen failing | EMU-37B | `KidsChatTest.aRefusalComesAsATipThatSaysWhatToDo`, failed against the same broken copy |
| A hidden detail is drawn as a lock chip in the chat, and the words underneath stay plain | passed, and seen failing | EMU-37B | `KidsChatTest.aHiddenDetailIsDrawnAsALockChipInTheChat`, failed against the same broken copy |
| Everything the first version's tests checked still holds under the new screen | passed | EMU-37B | The rest of `KidsChatTest`, with three expectations changed to the new words: the masked email, what stays in the field while the card is up, and the line for a file that is not a picture |
| The new, light screen passes the accessibility checks when empty, with a message and a tip, and with the card | passed | EMU-37B | `AccessibilityAuditTest.kidsChatPassesTheAccessibilityChecksInEachOfItsStates`, first run. Text contrast is part of those checks |
| By hand, set beside the mockup: chatting with a picture, the tip for unkind words, the card for private details, the getting-ready screen | passed by hand | EMU-37B | Driven over adb; the picture went through Android's real picker. `docs/evidence/screenshots/kids_chat_new_chatting.png`, `kids_chat_new_kind_words.png`, `kids_chat_new_private.png`, `kids_chat_new_getting_ready.png`. The getting-ready screen was reached by setting Moderator's files aside and putting them back |
| The two typefaces are what they claim to be | passed | — | Read from the files' own fields: "Baloo 2 Medium", weight 500, copyright The Baloo 2 Project Authors; "Andika", weight 400, copyright SIL International; both name the SIL Open Font License |
| On the phone: installed | passed | PHONE | Installed at 22:09 on 18 Nov 2026 at the owner's request (commit `7ba4738`, the build the emulator tests ran on). Both icons are listed, the three models the chat needs are on the phone, and no crash was logged. The phone's selected keyboard was Gboard before and after; AI Keyboard stayed enabled |
| On the phone: how it looks and works | not reported yet | PHONE | The owner's to try. Nothing on the phone's screen was read or captured |
| Large text, landscape, a small screen, TalkBack by ear, a real child | not run | — | |

After the redesign: 178 unit tests, lint 0 errors and 40 warnings (one more than before, the same note about `accessibilityHeading`), a 701.8 MB debug APK, and all 216 device tests on the emulator (EMU-37B, `-e clear true`): 196 passed, 20 skipped by design (the 17 as before and the chat's three measurements), none failed.

That took four runs, not one. The full run stopped at its 175th test, `ModelSmokeTest.sdkDownload_cancelReleasesCallerButTransferRunsOn`, an older test of the vendor's own downloader that waits, with no time limit, for a 100 MB transfer from Hugging Face. The transfer stalled at 44 MB while the emulator's connection was dropping packets. The run was ended there with 174 tests finished (161 passed, 13 skipped); the 41 tests it had not reached were run next (34 passed, 7 skipped); and the stalled test passed by itself a few minutes later. Nothing in that test touches Kids Chat, and it passed in each of the three full runs earlier the same day.

## The handoff checklist, item by item

Each line of `docs/BUILD_AND_TEST_CHECKLIST.md`, with where it stands. "Emulator" means it ran and passed on an emulator only; the phone rows above still apply.

### Environment and compatibility

| Item | Status | Where |
|---|---|---|
| OS, JDK, SDK, Gradle/AGP and ABIs recorded | passed | `PROGRESS.md` (Environment), compatibility report (Pinned toolchain) |
| Dependencies pinned; working wrapper; a clean checkout builds from the README | passed | `gradle/libs.versions.toml`; clean clone built at the end of milestone 9 (below) |
| minSdk and Java configuration against vendor needs | passed | minSdk 26 (SDK needs 24), Java 17, no desugaring needed |
| Real Desert Ant artifacts resolved; Java-callable; Kotlin bridge documented | passed | All 13 at 4.3.0; called from Java through `DesertAntClient` with no Kotlin source (ruling 3) |
| SDK download, revisions, offline loading, cleanup and options verified | passed (emulator) | Milestones 0, 2 and the per-model rows; the SDK's own download is not used because it cannot be cancelled (ruling 4) |
| License and attribution recorded | passed | Compatibility report; Privacy screen and Settings credit in the app |

### Editor and inference correctness

| Item | Status | Where |
|---|---|---|
| No AI in password and no-learning fields; typing still works | passed (emulator) | `InputEligibilityTest`, `KeyboardAiTest` (2), `KeyboardVoiceTest`, `DrawingTest`, and `EditorReadsTest`, where the field itself counts the keyboard's reads: zero for a password field, with the keyboard shown or hidden. The hidden case failed until the final review's fix |
| Empty, long, partial or unavailable editor context | passed in part | Unit-tested (`TextRangeMapperTest`, `AiCoordinatorTest`); a draft over 1,000 characters and an editor that hides its offsets were not driven on a device |
| Fast typing, paste, selection moves, app switches and settings changes drop stale work | passed | `AiCoordinatorTest` (26), `InferenceSchedulerTest`; on device through the notice and topics tests |
| Main thread never waits on a model; p95 key handling and suggestion latency measured on a named device | passed | One inference thread (ruling 23). On a OnePlus CPH2423: key press to text 12 ms, last key to emoji 200 ms, both at the 95th percentile; the main thread was never held more than 29 ms, and that was while models loaded |
| One call at a time per model; late results suppressed | passed in part | `InferenceSchedulerTest`, `AiCoordinatorTest`, dictation and voice-tools cancel tests. The review found a narrow window in which a shape answer can be applied to the next stroke (deferred, see `PROGRESS.md`) |
| Redaction offsets: UTF-16, surrogate pairs, emoji families, combining marks, partial windows | passed | `TextRangeMapperTest` (9), `ModelSmokeTest.redact_offsetsAreUtf16CodeUnits` |
| Apply and Undo both re-check the text; acceptance required; never auto-send | passed (emulator) | `EditorBridgeTest` (16, including edits that overlap being refused whole), `KeyboardAiTest` mask/undo, Enter test |
| Model failure, timeout, missing files and low memory leave typing working | passed in part (emulator) | Not-installed, crashed-model and memory-pressure tests; two real crash recoveries (Voz, Clear). A model call that times out is handled in code and was not driven by a test |

### Settings, assets and lifecycle

| Item | Status | Where |
|---|---|---|
| One settings store; subscriptions and executors owned and disposed | passed in part | One store, same snapshots for every observer, ordered writes and a damaged file recovering are tested (`SettingsSyncTest`, 4). That every subscription is disposed with its owner was checked by reading the code, not by a test |
| Pausing AI keeps choices and files, clears suggestions, cancels pending work | passed (emulator) | `CompanionAppTest` master-switch test, `KeyboardAiTest`, `AiCoordinatorTest` |
| Work unique per model revision; duplicate requests harmless | passed | `HardeningTest.askingForADownloadTwiceStartsOneTransfer` |
| Interrupted or corrupt downloads never Ready; retry, low storage and incompatible device are distinct | passed | `ModelDownloaderTest` (11), `ModelDownloadDeviceTest` (4), `ModelMemoryGuardTest` |
| Installed inference works offline; typing never starts a download or uploads text | passed (emulator) | Offline cold start; both network tests (0 bytes for all 13 models once loaded); models are only constructed from verified files |
| Release, deletion and revision replacement respect work in progress | passed in part | Delete-during-run test. Replacing a model with a newer revision was not exercised: there is only one revision. A model call that has run past its time limit is not protected from a release (deferred, see `PROGRESS.md`) |
| Drafts and redaction maps absent from logs and backups; model files excluded from backup | passed (emulator) | `HardeningTest.whatIsTyped…`; models live in the no-backup directory; the SDK's identifier is excluded by both backup rule files. An actual backup was not taken and inspected. Settings are backed up, and they include the package ids of apps the keyboard has been used in |
| Configuration change, keyboard reconnect, process death, offline start, lock/unlock | passed (emulator) | Rows above in milestone 9 |

### Media and interface

| Item | Status | Where |
|---|---|---|
| Microphone refusal and revocation, interrupted capture, invalid formats | passed (emulator) | `MicPermissionFlowTest`, `AudioCaptureTest`, `AudioImportTest`; a microphone taken by another app mid-recording was not driven |
| Audio chaining: sample rate, channels, language, timestamps, stream or batch | passed | Compatibility report finding 9; `VoicePipelineTest`, `VoiceModelsTest` |
| Picker cancel, malformed and large pictures, unclear strokes, sharing refused | passed (emulator) | `ImageToolsScreenTest`, `DrawingTest`; a host that refuses an insert was not available |
| Temporary media policy documented; exports need a tap | passed | Compatibility report ("what is kept"); `AudioImportTest` |
| Every control works or says it is unavailable; no placeholder responses | passed | Milestone 8 audit; catalog test |
| Reference composition, dark theme, muted panel, gradient icon, single 600 ms enable sweep | passed (by eye and test) | Milestone 8 |
| Animations off, larger fonts, TalkBack, contrast, touch targets | passed; TalkBack by the owner's report | Milestone 8; the TalkBack pass was done by the owner on the phone and reported as working, without detail |

### Final delivery

| Item | Status | Where |
|---|---|---|
| Runnable project, pinned dependencies, wrapper, build instructions | passed | `README.md` |
| Debug APK with its real location | passed | `app/build/outputs/apk/debug/app-debug.apk`, 700.1 MB |
| Compatibility report, validation report, progress file | passed | `docs/` and `PROGRESS.md` |
| Passed, failed, blocked and not-run kept apart, with commands and targets | passed | This file |
| Unsupported features and device-only gaps explicit; no whole-app completion claim | passed | The blocked rows above; `PROGRESS.md` open items |

**Where it stands.** All ten milestones are built and have been run on an emulator and on one physical phone (a OnePlus CPH2423, Android 15, 12 GB). On that phone the automated checks and the measurements were run and recorded here; the hands-on checks (typing in real apps, dictation, haptics and sound, TalkBack, recents) were done by the owner and rest on their report that all of them worked. Still open, and not to be read as passed: battery and heat over a long session; any phone with 6 or 8 GB of memory; which chat apps were typed in; four unexplained cases of the keyboard not appearing after a tap; the ten findings deferred from the final review. Whether that is complete enough is the owner's call.
