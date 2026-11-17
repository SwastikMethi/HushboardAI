# Model compatibility report

Status of each Desert Ant model in this app, with the evidence behind it. A row says **verified** only when a real adapter call ran on a device or emulator; otherwise it says what is still missing. Last updated at milestone 9.

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
| License Check | Made the first time a model is loaded in a calendar month, not on every load. The SDK keeps one signed license record per model with the month it covers, plus a device identifier, in `shared_prefs/desert-ant.xml` (excluded from backup). Reloading four models whose records were current sent 40 bytes and received none; 125 model runs sent and received none (`HardeningTest`). The request itself was not captured: forcing one would register another activation with the vendor. |

## APK size (debug, `arm64-v8a` only)

| Vendor artifacts included | APK size |
|---|---|
| Emo only | 76.3 MB |
| Emo, Redact, Toxic, Tongue | 192.4 MB |
| Emo, Redact, Toxic, Tongue, Gist and the five voice models | about 587 MB |
| All 13 (the shipped build) | 700.1 MB from a clean build at milestone 9 (700,096,566 bytes; 686.6 MB of it is the 15 native libraries). No model weights are inside |

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
| Eye | `ai.desertant:eye` | beta | bitmap → what sort of picture it is, tags from a fixed list of about 600 with confidences, quality scores, and people / pet / face detections | `v1.0.0`: `eye.tflite` 66,038,240; `eye_meta.json` 280,890 — 66,319,130 total | **Verified** on emulator and working in the Image tools screen: a real capture of the home screen came back as `screenshot` (tags screenshot 100%, low content 87%, …), nobody detected. About 2 s cold, 0.8 s warm. **Memory: about 1.5 GB**, so it is refused below 2 GiB of device memory and unloaded 20 seconds after its last use (1.40 GB → 272 MB). Not tried on real photos of people, pets or places. Vendor beta. No settings: the app uses the model's defaults. |
| Moderator | `ai.desertant:moderator` | stable | bitmap → a nudity score, a flag at the threshold, and per-region scores | `v1.0.0`: `moderator.tflite` 9,618,016 | **Verified** on emulator and working in the Image tools screen: a plain drawing of a house scored 0.30 and was not flagged at the default threshold of 0.50; at a threshold of 0 it is flagged, which is how the warn-before-sharing path was exercised. Memory: about 350 MB. **Not tried on any picture it ought to flag** — no explicit imagery was used — so its detection quality is unverified. Setting: the model's own threshold. |
| Shapes | `ai.desertant:shapes` | stable | one stroke as points → a shape (13 kinds) with a clean outline, or nothing | `v1.0.0`: `shapes.tflite` 974,896; `shapes_meta.json` 1,551 — 976,447 total | **Verified** on emulator and working in the Drawing screen and in the keyboard's drawing panel: a drawn circle → ellipse, a line → line, a rectangle → rectangle, a triangle → triangle, by formula and by real touch gestures; a random scrawl and a tangle come back as nothing. About 0.1 s cold. Memory: about 240 MB. It tidies shapes; it does not read handwriting. |

Each revision above is the one compiled into that model's native library (a `vN.N.N` string in `lib<Model>Android.so`), and every pinned file was fetched from it and hash-checked. Platform-limited models (Align, Clips, Title, Schemer, Face) have no Android SDK entry and are not offered as toggles.

## Findings that shape the design

1. **Redact spans can cut through a character.** In the smoke test the GIVEN_NAME span started inside a family emoji (after its second joiner). Offsets are valid UTF-16, but masking must widen a span to whole grapheme clusters before editing.
2. **Toxic ships its own default thresholds** (HATEFUL 0.46, ABUSIVE 0.44, THREAT 0.35, and one per target group). The result carries them, so sensitivity presets can be defined relative to these.
3. **Tongue has its own telemetry switch** (`ai.desertant.tongue.DesertAnt.setSendsDeviceContext`, `Tongue.flushTelemetry`). It is left at the vendor's default (ruling 36). In the network test, 25 Tongue runs followed by a five-second wait sent nothing; whether anything is sent later, in batches, was not established.
4. **The SDK exposes a logger hook** (`ai.desertant.core.DesertAnt.setLogger` / `setLogLevel`). It must be kept quiet so no input text can reach logs.
5. **Voz needs about 3.9 GB of memory.** Its 1.2 GB encoder is held in memory, not mapped from disk. The Android emulator's default 2–3 GB is not enough: the system killed the app at about 2.3 GB while the model was still loading. The app therefore refuses Voz on devices under 5 GiB and unloads it soon after use (see `ModelRegistry.VOZ_MIN_RAM_BYTES`, `AppContainer.VOZ_IDLE_SECONDS`). The threshold comes from one emulator measurement and needs checking on phones.
6. **Clear depends on a working GPU delegate.** With the emulator's software renderer the model's creation crashes natively; with the host GPU it runs. There is no CPU fallback option in its API.
7. **A crash while a model loads is survived, proved twice with real crashes** (Voz out of memory, Clear on a software GPU): the next start switches the model off, says why in the catalog, and leaves its files installed.
8. **Models that are not used while typing are unloaded after 20 seconds without use.** Voz (3.9 GB) and Eye (1.5 GB) would otherwise sit in the keyboard's process after one use. The typing models stay loaded so suggestions stay quick.
9. **Audio contract for chaining.** Every voice model takes mono float samples plus the sample rate, so each step is told the rate of what it is handed: recordings and imports are brought to mono 16 kHz, and Clear's 48 kHz output goes on to the next step as it is. All calls are batch calls. Word times from Voz are what filler brackets and speaker lines are placed by.

## Pictures and drawings: what is kept

- A picked picture is read once through the system photo picker and held in memory at up to 1600 pixels across. It is not copied to disk, and the app asks for no storage permission.
- A drawing lives in the drawing surface and is gone when the screen or the keyboard's panel is left.
- Sharing or inserting writes one file (JPEG for a picture, PNG for a drawing) to `cache/shared/`, handed over through a content URI with a read grant for that use, and deleted after an hour like shared audio.
- From the keyboard, a drawing is inserted with `commitContent` only where the focused field says it accepts `image/png`; anywhere else, or if the host app turns it down, the system share sheet is offered instead.

## Recordings: what is kept, and for how long

- A recording or an imported file is held in memory only, for the Voice tools screen or the keyboard's dictation that asked for it. It is gone when that screen is left, when the keyboard leaves the screen, or when the user cancels. At most two minutes are processed (60 seconds for dictation).
- Nothing is written to disk unless the user taps **Share cleaned audio**. That writes one WAV file to the app's private cache (`cache/shared/`) and hands it to the chosen app through a content URI with a read grant for that share only.
- Shared files are deleted once they are an hour old. The check runs each time something is shared and each time the app starts.
- Transcripts are never stored. A dictated transcript is typed into the field only when the user taps Insert.
- The microphone is opened only by a tap on Record or on the keyboard's microphone button, and the permission is requested at that tap.

## What every row has in common

- **Platform:** `arm64-v8a` only in this build (the SDK also ships `x86_64`; there is no 32-bit build). minSdk is 26; the SDK needs 24.
- **Offline:** installed models run with the network off, from a cold start. That was checked in airplane mode for Emo, Redact and Shapes (in the keyboard and directly); the other models load the same way and were not separately run offline.
- **Network while working:** none. Model runs send and receive nothing; the only vendor traffic is the license check above and the downloads the user asks for.
- **Where a model cannot run:** a model that needs more memory than the device has is refused before download (Voz under 5 GiB, Eye under 2 GiB). A model that takes the app down while loading is switched off at the next start, with the reason shown, and its files are kept.
- **Languages:** only Redact's coverage is documented precisely by the vendor (European languages) and only that is acted on. Nothing is claimed about which languages Emo, Toxic, Gist, Voz, Ear, Uhm or Who handle well; Ear and Tongue report a language, they do not gate anything except Redact.
- **Evidence level:** everything here is from Android emulators on an Apple Silicon Mac. No model has been run on a physical phone yet.

## License and attribution

Desert Ant Labs Source-Available License v1.1, accepted by the project owner for this project on 17 Nov 2026. The app shows a "Powered by Desert Ant Labs" credit linking to desertant.com (Settings tab) and discloses the License Check in plain words (Settings → Privacy).

## How models are installed

The app downloads each model's pinned files itself (`models/ModelDownloader`), from `https://huggingface.co/desert-ant-labs/<model>/resolve/<revision>/<file>`, into `<no-backup files dir>/models/<model>/<revision>/`. Each file is checked against the size and SHA-256 the vendor SDK pins for SDK 4.3.0 before it takes its final name. The SDK then adopts the directory. The SDK's own `download()` is not used in product code because it cannot be cancelled.
