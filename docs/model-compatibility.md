# Model compatibility report

Status of each Desert Ant model in this app, with the evidence behind it. A row says **verified** only when a real adapter call ran on a device or emulator; otherwise it says what is still missing. Last updated at milestone 6.

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
| Emo, Redact, Toxic, Tongue, Gist and the five voice models | about 587 MB |
| All 13 | 703.2 MB |

Each native model library is about 56 MB uncompressed because a Swift runtime is compiled into every one. Models are therefore added to the build as their adapters land. A store release would need compressed native libraries or per-model feature delivery; neither is in this plan.

## Models

Weights are listed as the SDK itself pinned them (file, bytes). Timings and memory figures are from the Android 17 arm64 emulator on an Apple Silicon host and are **not** device performance evidence. Memory is the app process's peak resident memory (`VmHWM`) in a fresh process that loaded only that model; the app alone sits at about 210 MB.

| Model | Artifact | Vendor status | Input → output | Weights (pinned revision) | State |
|---|---|---|---|---|---|
| Emo | `ai.desertant:emo` | stable | text → up to `limit` emoji with confidence | `v0.7.0`: `emo.tflite` 10,222,736; `emo_meta.json` 6,280; `emo_tokenizer.bin` 733,197 — 10,962,213 total | **Verified** on emulator and working in the keyboard: real suggestions (🍕 0.83 for "Pizza sounds good") shown in the strip and inserted on tap, online and in airplane mode. About 6 ms cold, 3 ms warm. Settings: number of suggestions, skin tone. |
| Redact | `ai.desertant:redact` | stable | text → spans (label, UTF-16 start/end, placeholder, confidence) | `v0.4.2`: `redact.tflite` 23,929,696; `redact_tokenizer.bin` 391,416; `labels.json` 3,842 — 24,324,954 total | **Verified** on emulator and working in the keyboard: notice, review with original and masked text, guarded mask and undo, warn-only mode. EMAIL, PHONE and GIVEN_NAME spans seen; offsets are UTF-16. About 33 ms cold. Settings: warn only / offer masking, seven categories mapped to the model's 21 offered labels, minimum confidence. European languages only. |
| Toxic | `ai.desertant:toxic` | beta | text → per-label scores, thresholds, flagged set | `v0.2.2`: `litert-mixed/toxic.tflite` 44,193,264; `litert-mixed/toxic_meta.json` 2,381; `toxic_tokenizer.bin` 9,412,432; `toxic_table.bin` 49,664,021 — 103,272,098 total | **Verified** on emulator and working in the keyboard: a plain threat raises a review notice (ABUSIVE 0.95, THREAT 0.76), a friendly message does not; the notice never blocks Send. About 25 ms. Settings: three kinds of wording, and an app-defined alert frequency relative to the model's thresholds. Vendor beta. |
| Tongue | `ai.desertant:tongue` (JAR) | stable | text → ranked languages with reliability | Bundled in the 1.7 MB JAR; no download | **Verified** on emulator and working in the keyboard: French → `fr`, Japanese → `ja`, both `CONFIDENT`. Synchronous, pure JVM. Used as a hint: a confident language outside Redact's coverage makes the privacy check stand down, and the AI panel says so. Never used to switch layouts or translate. |
| Gist | `ai.desertant:gist` | stable | text → topics from a fixed list (slug, name, score) | `v2.2.0`: `gist.tflite` 13,034,484; `gist_tokenizer.bin` 4,625,812; `gist_embedding.i8` 66,905,344; `gist_embedding.json` 67; `gist_config.json` 823; `taxonomy.json` 8,018 — 84,574,548 total | **Verified** on emulator and working in the keyboard: "Tag this draft" on request, then "Insert as hashtags" on a second tap. "Food & Cooking" 0.998 for a dinner message. About 1 s cold (loads a 67 MB table), 4 ms warm. Setting: number of topics. |
| Voz | `ai.desertant:voz` | stable | mono PCM floats + sample rate → text with a start and end time for every word. One batch call; no streaming and no language parameter | `v0.5.0`: `encoder.tflite` 1,207,525,456; `mel.tflite` 1,663,424; `decoder.tflite` 24,503,072; `meta.litert.json` 579; `vocab.json` 78,654; `embedding.f16` 10,487,040 — 1,244,258,225 total | **Verified** on emulator and working in the keyboard and in Voice tools: a synthesised 6.8-second sentence came back word for word with 22 timed words; 4.3 s cold, about 1 s warm. **Memory: 3.9 GB.** A 3 GB device killed the app while loading it, so the app refuses Voz below 5 GiB of device memory and unloads it 20 seconds after its last use (resident memory fell from 4.85 GB to 1.2 GB). No settings: the API has none. |
| Clear | `ai.desertant:clear` | stable | mono PCM floats + sample rate → cleaned audio at 48 kHz, with strength and loudness-mastering options | `v0.3.0`: `clear-studio.tflite` 49,285,108 | **Verified** on an emulator using the host's GPU: 6.8 s of speech cleaned in about 0.5 s, and the cleaned 48 kHz audio still transcribes correctly through Voz. Memory: about 570 MB. **Crashes the process on an emulator drawing in software** (SIGSEGV in `libLiteRtClGlAccelerator.so` while the model is created); the crash breaker switches it off at the next start. Needs a phone to confirm on real GPUs. Setting: strength (a real model parameter). |
| Ear | `ai.desertant:ear` | stable | mono PCM floats + sample rate → ranked languages, with the model's own "reliable" flag | `v0.2.0`: `ear.tflite` 10,671,552; `languages.json` 628; `ear_meta.json` 2,190; `mel_filters.f32` 64,328 — 10,738,698 total | **Verified** on emulator: English speech → `en`, reliable. Memory: about 340 MB. Shown as information next to the transcript; nothing is gated on it. |
| Uhm | `ai.desertant:uhm` | stable | mono PCM floats + sample rate (+ optional timed words) → filler spans with type and confidence | `v1.2.0`: `uhm.tflite` 23,919,472 | **Verified** on emulator: runs on speech and flags nothing in a fluent sentence. Memory: about 885 MB. Fillers are bracketed in the preview; the audio is never cut. Setting: the model's own precision / balanced / recall bias. Not yet tried on speech that contains fillers. |
| Who | `ai.desertant:who` | beta | mono PCM floats + sample rate → speaker turns (speaker number, start, end) | `v0.3.4`: `litert-int8/who.tflite` 30,574,096; `who_meta.json` 4,090; `who_mel_banks.f32` 23,652; `who_voice_banks.f32` 82,248; `who_embed.f16` 2,621,960 — 33,306,046 total | **Verified** on emulator: 20 s of one voice → 5 turns, all one speaker. Returns no turns for a 7-second clip, in which case no speaker labels are shown. Memory: about 440 MB. Vendor beta. Not yet tried on a real multi-speaker recording. |
| Eye | `ai.desertant:eye` | beta | bitmap → image analysis | `v1.0.0` — not captured | Resolves, aligned. Pending (milestone 7). |
| Moderator | `ai.desertant:moderator` | stable | bitmap → NSFW score and regions | `v1.0.0` — not captured | Resolves, aligned. Pending (milestone 7). |
| Shapes | `ai.desertant:shapes` | stable | stroke points → shape | `v1.0.0` — not captured | Resolves, aligned. Pending (milestone 7). |

Revisions for the three pending models (Eye, Moderator, Shapes) come from vendor source research and are confirmed when each model's files are first fetched. Platform-limited models (Align, Clips, Title, Schemer, Face) have no Android SDK entry and are not offered as toggles.

## Findings that shape the design

1. **Redact spans can cut through a character.** In the smoke test the GIVEN_NAME span started inside a family emoji (after its second joiner). Offsets are valid UTF-16, but masking must widen a span to whole grapheme clusters before editing.
2. **Toxic ships its own default thresholds** (HATEFUL 0.46, ABUSIVE 0.44, THREAT 0.35, and one per target group). The result carries them, so sensitivity presets can be defined relative to these.
3. **Tongue has its own telemetry switch** (`ai.desertant.tongue.DesertAnt.setSendsDeviceContext`, `Tongue.flushTelemetry`). What it sends is not established yet; decided in milestone 5.
4. **The SDK exposes a logger hook** (`ai.desertant.core.DesertAnt.setLogger` / `setLogLevel`). It must be kept quiet so no input text can reach logs.
5. **Voz needs about 3.9 GB of memory.** Its 1.2 GB encoder is held in memory, not mapped from disk. The Android emulator's default 2–3 GB is not enough: the system killed the app at about 2.3 GB while the model was still loading. The app therefore refuses Voz on devices under 5 GiB and unloads it soon after use (see `ModelRegistry.VOZ_MIN_RAM_BYTES`, `AppContainer.VOZ_IDLE_SECONDS`). The threshold comes from one emulator measurement and needs checking on phones.
6. **Clear depends on a working GPU delegate.** With the emulator's software renderer the model's creation crashes natively; with the host GPU it runs. There is no CPU fallback option in its API.
7. **A crash while a model loads is survived, proved twice with real crashes** (Voz out of memory, Clear on a software GPU): the next start switches the model off, says why in the catalog, and leaves its files installed.
8. **Audio contract for chaining.** Every voice model takes mono float samples plus the sample rate, so each step is told the rate of what it is handed: recordings and imports are brought to mono 16 kHz, and Clear's 48 kHz output goes on to the next step as it is. All calls are batch calls. Word times from Voz are what filler brackets and speaker lines are placed by.

## Recordings: what is kept, and for how long

- A recording or an imported file is held in memory only, for the Voice tools screen or the keyboard's dictation that asked for it. It is gone when that screen is left, when the keyboard leaves the screen, or when the user cancels. At most two minutes are processed (60 seconds for dictation).
- Nothing is written to disk unless the user taps **Share cleaned audio**. That writes one WAV file to the app's private cache (`cache/shared/`) and hands it to the chosen app through a content URI with a read grant for that share only.
- Shared files are deleted once they are an hour old. The check runs each time something is shared and each time the app starts.
- Transcripts are never stored. A dictated transcript is typed into the field only when the user taps Insert.
- The microphone is opened only by a tap on Record or on the keyboard's microphone button, and the permission is requested at that tap.

## License and attribution

Desert Ant Labs Source-Available License v1.1, accepted by the project owner for this project on 17 Nov 2026. The app shows a "Powered by Desert Ant Labs" credit linking to desertant.com (Settings tab) and discloses the License Check in plain words (Settings → Privacy).

## How models are installed

The app downloads each model's pinned files itself (`models/ModelDownloader`), from `https://huggingface.co/desert-ant-labs/<model>/resolve/<revision>/<file>`, into `<no-backup files dir>/models/<model>/<revision>/`. Each file is checked against the size and SHA-256 the vendor SDK pins for SDK 4.3.0 before it takes its final name. The SDK then adopts the directory. The SDK's own `download()` is not used in product code because it cannot be cancelled.
