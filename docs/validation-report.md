# Validation report

Every check is listed as **passed**, **failed**, **blocked** or **not run**. A check counts as passed only if it actually ran, on the target named beside it. Emulator results are never presented as physical-device results.

## Targets

| ID | Target |
|---|---|
| EMU-37 | `Pixel_6_Pro` AVD, Android 17 (API 37), `google_apis_ps16k` arm64-v8a image, 16 KB pages, on an Apple Silicon Mac (macOS 26.4.1) |
| PHONE | Physical Android phone — not connected yet |

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
