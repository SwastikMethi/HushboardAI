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
