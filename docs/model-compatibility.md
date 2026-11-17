# Model compatibility report

Status of each Desert Ant model in this app, with the evidence behind it. A row says **verified** only when a real adapter call ran on a device or emulator; otherwise it says what is still missing. Last updated at milestone 0.

## Pinned toolchain

| Item | Version |
|---|---|
| Gradle / Android Gradle Plugin | 9.4.1 / 9.2.1 |
| JDK running Gradle | JetBrains Runtime 21.0.10 (bundled with Android Studio) |
| Java source/target | 17 |
| compileSdk / targetSdk / minSdk | 36.1 / 36 / 26 |
| ABI | `arm64-v8a` only (the SDK also ships `x86_64`; no 32-bit) |
| Desert Ant SDK | 4.3.0 for every model artifact and `ai.desertant:core` |
| Other libraries | See `gradle/libs.versions.toml` |

## What the SDK really is (reproduced in milestone 0)

| Fact | Evidence |
|---|---|
| All 13 Android artifacts resolve from Maven Central at 4.3.0 and pass `checkDebugAarMetadata` | `./gradlew :app:checkDebugAarMetadata assembleDebug` with `libs.bundles.desertant` |
| minSdk 24; every native model merges the `INTERNET` permission | Each AAR's `AndroidManifest.xml` |
| Inference and `download()` are Kotlin `suspend`; constructor, `isDownloaded()`, `close()` are plain | `docs/evidence/sdk-signatures-4.3.0.txt` (`javap -p` on the resolved classes) |
| Java can call them with no Kotlin source | `sdkbridge/DesertAntClient` + `ModelSmokeTest` |
| Every native library is 16 KB page-aligned | `objdump -p … \| grep LOAD` shows `align 2**14` for all 12 model libraries and both LiteRT libraries; smoke tests ran on a 16 KB Android 17 image |
| Weights come from `https://huggingface.co/desert-ant-labs/<model>/resolve/<revision>/<file>`; the endpoint supports `Range` | `ModelSmokeTest.huggingFaceUrl_servesThePinnedFile` (HTTP 206, SHA-256 equals the SDK's own manifest) |
| The SDK records each model's files, sizes and SHA-256 in `<dir>/.dal-meta/manifest` | Read from the emulator after a real SDK download |
| A directory we fill ourselves (model files only) is adopted as downloaded and runs offline | `ModelSmokeTest.offline_installedModelsRunAndMissingModelFailsVisibly` in airplane mode |
| Inference on a missing model with no network fails with the vendor exception, it does not hang | Same test: `EmoException: suggestion failed` |
| **`download()` cannot be cancelled.** Cancelling releases the caller, but the native transfer keeps running to completion and holds the model's lock (`isDownloaded()` blocked ~10 s) | `ModelSmokeTest.sdkDownload_cancelReleasesCallerButTransferRunsOn` |
| A download killed with the process does not report ready afterwards | `killProbe_*` tests with an `adb shell am force-stop` between them |
| Whether the SDK resumes its own partial file after a kill | Inconclusive (sample taken too early). Not needed: the app's own downloader resumes with `Range`. |
| License Check on model load | Not observed on the wire yet. The SDK created its install-ID store (`shared_prefs/desert-ant.xml`). Network capture is a milestone 9 item. |

## APK size (debug, `arm64-v8a` only)

| Vendor artifacts included | APK size |
|---|---|
| Emo only | 76.3 MB |
| Emo, Redact, Toxic, Tongue | 192.4 MB |
| All 13 | 703.2 MB |

Each native model library is about 56 MB uncompressed because a Swift runtime is compiled into every one. Models are therefore added to the build as their adapters land. A store release would need compressed native libraries or per-model feature delivery; neither is in this plan.

## Models

Weights are listed as the SDK itself pinned them (file, bytes). Timings are from the Android 17 arm64 emulator on an Apple Silicon host and are **not** device performance evidence.

| Model | Artifact | Vendor status | Input → output | Weights (pinned revision) | State |
|---|---|---|---|---|---|
| Emo | `ai.desertant:emo` | stable | text → up to `limit` emoji with confidence | `v0.7.0`: `emo.tflite` 10,222,736; `emo_meta.json` 6,280; `emo_tokenizer.bin` 733,197 — 10,962,213 total | **Verified** on emulator: real suggestions (🍕 0.83 for "Pizza sounds good"), offline, cancel-then-reuse. About 6 ms cold, 3 ms warm. |
| Redact | `ai.desertant:redact` | stable | text → spans (label, UTF-16 start/end, placeholder, confidence) | `v0.4.2`: `redact.tflite` 23,929,696; `redact_tokenizer.bin` 391,416; `labels.json` 3,842 — 24,324,954 total | **Verified** on emulator: EMAIL, PHONE and GIVEN_NAME spans; `text.substring(start, end)` equals `original` for every span. About 33 ms cold. |
| Toxic | `ai.desertant:toxic` | beta | text → per-label scores, thresholds, flagged set | `v0.2.2`: `litert-mixed/toxic.tflite` 44,193,264; `litert-mixed/toxic_meta.json` 2,381; `toxic_tokenizer.bin` 9,412,432; `toxic_table.bin` 49,664,021 — 103,272,098 total | **Verified** on emulator: a plain threat is flagged (ABUSIVE 0.95, THREAT 0.76), a friendly message is not. About 25 ms. |
| Tongue | `ai.desertant:tongue` (JAR) | stable | text → ranked languages with reliability | Bundled in the 1.7 MB JAR; no download | **Verified** on emulator: French sentence → `fr`, `CONFIDENT`. Synchronous. |
| Gist | `ai.desertant:gist` | stable | text → topics (fixed taxonomy) | `v2.2.0` — files not captured yet | Resolves, 16 KB-aligned. Adapter and smoke test pending (milestone 5). |
| Voz | `ai.desertant:voz` | stable | PCM floats + sample rate → transcription | `v0.5.0` — not captured | Resolves, aligned. Pending (milestone 6). |
| Clear | `ai.desertant:clear` | stable | PCM floats → enhanced audio | `v0.3.0` — not captured | Resolves, aligned. Pending (milestone 6). |
| Ear | `ai.desertant:ear` | stable | PCM floats → spoken language | `v0.2.0` — not captured | Resolves, aligned. Pending (milestone 6). |
| Uhm | `ai.desertant:uhm` | stable | PCM floats → filler spans | `v1.2.0` — not captured | Resolves, aligned. Pending (milestone 6). |
| Who | `ai.desertant:who` | beta | PCM floats → speaker turns | `v0.3.4` — not captured | Resolves, aligned. Pending (milestone 6). |
| Eye | `ai.desertant:eye` | beta | bitmap → image analysis | `v1.0.0` — not captured | Resolves, aligned. Pending (milestone 7). |
| Moderator | `ai.desertant:moderator` | stable | bitmap → NSFW score and regions | `v1.0.0` — not captured | Resolves, aligned. Pending (milestone 7). |
| Shapes | `ai.desertant:shapes` | stable | stroke points → shape | `v1.0.0` — not captured | Resolves, aligned. Pending (milestone 7). |

Revisions for the ten pending models come from vendor source research and are confirmed when each model's files are first fetched. Platform-limited models (Align, Clips, Title, Schemer, Face) have no Android SDK entry and are not offered as toggles.

## Findings that shape the design

1. **Redact spans can cut through a character.** In the smoke test the GIVEN_NAME span started inside a family emoji (after its second joiner). Offsets are valid UTF-16, but masking must widen a span to whole grapheme clusters before editing.
2. **Toxic ships its own default thresholds** (HATEFUL 0.46, ABUSIVE 0.44, THREAT 0.35, and one per target group). The result carries them, so sensitivity presets can be defined relative to these.
3. **Tongue has its own telemetry switch** (`ai.desertant.tongue.DesertAnt.setSendsDeviceContext`, `Tongue.flushTelemetry`). What it sends is not established yet; decided in milestone 5.
4. **The SDK exposes a logger hook** (`ai.desertant.core.DesertAnt.setLogger` / `setLogLevel`). It must be kept quiet so no input text can reach logs.

## License and attribution

Desert Ant Labs Source-Available License v1.1, accepted by the project owner for this project on 17 Nov 2026. The app must show a "Powered by Desert Ant Labs" credit and disclose the License Check in its privacy text; both land with the companion app in milestone 2.
