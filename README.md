# AI Keyboard — Java implementation handoff

> **Status, 18 Nov 2026.** This folder now holds the implemented Android project as well as the handoff. Milestones 0–9 are built and tested on an emulator and on one physical phone (OnePlus CPH2423, Android 15); the hands-on checks on the phone were done by the owner and rest on their report. Not done: battery and heat, phones with less memory, named chat apps. What passed, failed, is blocked or was not run is in [docs/validation-report.md](docs/validation-report.md); every model's status is in [docs/model-compatibility.md](docs/model-compatibility.md); decisions and open items are in [PROGRESS.md](PROGRESS.md). Since 18 Nov 2026 the same app also holds **Kids Chat**, a one-phone demo of a chat for children; see "Kids Chat (demo)" below. The handoff text below is kept as it was written.

## Build and run

You need Android Studio (for its bundled Java runtime and the Android SDK with platform `android-36.1`) and about 5 GB of free disk for the build. Nothing else is installed globally.

1. Tell Gradle where the Android SDK is. Create `local.properties` in this folder (it is not committed):

   ```properties
   sdk.dir=/Users/<you>/Library/Android/sdk
   ```

2. Build, run the unit tests and lint. Gradle must run on Java 21; Android Studio's bundled runtime is used here because the system Java may be newer than the build tools accept:

   ```sh
   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
   ./gradlew :app:checkDebugAarMetadata assembleDebug testDebugUnitTest lintDebug
   ```

   The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. It is large (about 700 MB) because each of the twelve native model libraries is about 56 MB; it contains no model weights.

3. Install it on an `arm64-v8a` device or emulator running Android 8.0 or later:

   ```sh
   ./gradlew installDebug
   ```

4. On the device: open **AI Keyboard**, tap **Open keyboard settings** and switch the keyboard on, then **Choose keyboard**. In the **Models** tab, download the models you want (each is fetched only when you tap Download) and switch on **AI support**.

To run the device tests (they download models on first run, about 1.6 GB in all, and take around ten minutes):

```sh
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w com.example.aikeyboard.test/androidx.test.runner.AndroidJUnitRunner
```

Use install-then-instrument rather than `connectedDebugAndroidTest`, which uninstalls the app afterwards and throws the downloaded models away. The opt-in probes (offline, crash recovery, microphone refusal, Clear, screenshots) and the emulator used are listed in [docs/validation-report.md](docs/validation-report.md).

Things worth knowing before relying on it:

- **Memory.** The speech model (Voz) needs about 5 GB while it runs (measured on a phone) and is refused on devices that report under 6.5 GiB of memory, which means phones sold as 6 GB and below; the image model (Eye) needs about 1.5 GB. Both are unloaded shortly after use.
- **Emulators.** The stock emulator image has too little memory for Voz and its software GPU crashes Clear. The tests here ran on an emulator with 8 GB of memory started with `-gpu host`.
- **License.** The models are Desert Ant Labs' and are used under their source-available license, which the project owner accepted. Their software makes a license check (about once a month per model); the app says so on its Privacy screen and shows a "Powered by Desert Ant Labs" credit in Settings.
- **Not for a store yet.** The application id is `com.example.aikeyboard`, the build is debug-signed, and the APK size needs a packaging decision.

## Kids Chat (demo)

A first demo of a chat for children, built on the same on-device models. It installs with the app and shows up as a second icon on the home screen, **Kids Chat**. Two pretend users share one phone: Mia, a fox, and Leo, a bear. The child whose turn it is has the big face at the top, and a tap on the other face swaps. The screen is made for early readers: a face and a colour for each child, big round buttons with a picture on each, short words, and a sun helper who gives a tip when something cannot be sent. There is no second phone, account, server or parent side. The conversation is held in memory and is gone when the screen is closed; nothing typed or picked there is written to disk or leaves the phone.

Everything is checked on the phone before it is sent:

| What is being sent | Checked by | What happens |
|---|---|---|
| A message with a swear word | A built-in word list, `app/src/main/res/values/chat_blocked_words.xml` | Not sent. It stays in the field, and the helper says "Let's use kind words" |
| A message with abusive, hateful or threatening wording | Toxic, at its strictest setting | Not sent, with the same tip |
| A message with an email address, phone number, payment or ID number, street address or web link | Redact | A card shows a safer message with each private detail behind a lock chip (as plain text: `[email]`, `[phone number]`, `[address]`). The child picks **Send the safe one**, **Change my message** or **Send mine anyway** |
| A message that is surely not in English | Tongue, which names the language | Not sent. The helper says "I can only read English" |
| A picture, or a drawing from the pad | Moderator, which scores nudity | Not sent if flagged, with a line saying so |

Two more models are there for fun and do not decide anything. While a child types, up to four emoji that fit the message appear above the field (Emo); a tap adds one. A brush button opens a drawing pad with five crayons, undo and a bin, and each stroke the shape model recognises (Shapes) is redrawn tidy: a wobbly circle comes out round. So Kids Chat uses six of the 13 models.

A message can be up to 1,000 characters, the range the checks were tried over. If a check cannot run, nothing is sent. Until Toxic, Redact and Moderator are installed, a getting-ready screen stands in for the chat: it asks for a grown-up, and has a **Get ready** button and a way to the Models screen. The button also fetches the emoji and shape models, which nothing waits for. The rules are fixed in `ChatChecks.kidRules()` and do not follow the keyboard's settings, so they cannot be loosened from the Settings tab.

This is a demo, not a safety product. Its limits:

- **Toxic is a beta model.** It misses slang. At the strict setting used here it refused one friendly message out of ten samples ("You killed it in the match today, great job!").
- **The word list is short and literal.** 52 English entries, whole words in their exact spelling, so "sh1t" passes. It holds no slurs; those are left to the model's "hateful" category. Add words to the file to extend it.
- **The language rule is blunt.** It refuses full sentences in other languages, Hinglish in English letters included (the language model takes it for Dutch or Irish, and is sure of it), and lets very short messages through to checks that cannot read them. A short insult in Hinglish was sent.
- **Redact covers European languages only**, and names are deliberately not treated as personal details, or nearly every message would be stopped. A city or state on its own does raise the card ("We went to London on Saturday").
- **Moderator scores nudity only**, not violence or anything else. No explicit picture was ever shown to it in this project; its refusal was tested with a harmless drawing and a threshold of zero.
- **Send mine anyway** lets a personal detail out by the child's own choice. That was the project owner's decision.
- **Nothing here notices an adult befriending a child.** No model in this set does. A real product would start from contacts a parent approved.

Kids Chat uses two typefaces under the SIL Open Font License: Baloo 2 for names and buttons, and Andika, which is drawn for people learning to read, for messages. Their licence texts are in [docs/licenses](docs/licenses). The faces and the sun are drawn for this app; the small icons are Material Icons (Apache 2.0).

What was tested is in [docs/validation-report.md](docs/validation-report.md) under "Kids Chat demo", "Kids Chat, redesigned for young children" and "Kids Chat: emoji ideas, a drawing pad and a language rule"; the decisions are rulings 111 to 143 in [PROGRESS.md](PROGRESS.md).

This package contains the implementation plan, UI references, and instructions for building an Android AI keyboard with a companion settings app. **Java is selected as the primary language.**

It is a design and implementation package. It does not contain a built Android project, APK, model weights, or completed test results.

## Start here

1. Extract the ZIP into a new project folder.
2. Open the extracted `AI_Keyboard_Claude_Handoff_Java` folder in Claude Code or your coding agent, with file and terminal access.
3. Paste the contents of [CLAUDE_START_PROMPT.md](CLAUDE_START_PROMPT.md).
4. Let the agent inspect the plan and both images, establish the Android build environment, and implement the milestones. Android keyboard activation and physical-device testing require access to a device and its system UI.

If using a chat-only interface, attach the Markdown files and both images. A chat without a build environment cannot verify an APK or real keyboard behavior.

## Included files

| File | Purpose |
|---|---|
| [AI_Keyboard_Implementation_Plan.md](AI_Keyboard_Implementation_Plan.md) | Product scope, Java architecture, workflows, model catalog, source layout, milestones and verification |
| [CLAUDE.md](CLAUDE.md) | Project instructions for the implementation agent |
| [CLAUDE_START_PROMPT.md](CLAUDE_START_PROMPT.md) | Ready-to-copy implementation request |
| [docs/UI_SPEC.md](docs/UI_SPEC.md) | Screen behavior, final colors, animation and reference-image precedence |
| [docs/BUILD_AND_TEST_CHECKLIST.md](docs/BUILD_AND_TEST_CHECKLIST.md) | Build, model, editor, lifecycle and device completion checks |
| [PROGRESS.md](PROGRESS.md) | Unstarted milestone tracker to update during implementation |
| [design/01_Keyboard_Approved.png](design/01_Keyboard_Approved.png) | Approved keyboard: normal typing, AI suggestions and softened purple panel |
| [design/02_Companion_App_Reference.png](design/02_Companion_App_Reference.png) | Companion app layout: model catalog, model settings and keyboard settings |

## Decisions already made

- Native Android, Java, Views/XML, Material Components and View Binding; one app containing an InputMethodService and settings Activity.
- Familiar QWERTY keyboard; charcoal surfaces, restrained purple-blue AI identity and one brief enable animation.
- All 13 Android catalog entries are in scope: Emo, Redact, Toxic, Tongue, Gist, Voz, Clear, Ear, Uhm, Who, Eye, Moderator and Shapes. The first three are an engineering milestone.
- Models run on appropriate events. Voice, image and drawing tools use explicit user input; they do not automatically inspect messaging app content.
- All application logic stays in Java. A minimal isolated Kotlin SDK bridge is allowed only if verified vendor suspend APIs require one. This is not permission to migrate the app to Kotlin or Compose.
- The plan targets milestones 0–9. A keyboard shell, UI demo or Emo-only build is an intermediate result.

The companion image predates the larger catalog. Use its layout, implement the written catalog, and apply the final keyboard gradient to AI controls. [UI_SPEC.md](docs/UI_SPEC.md) lists the specific differences.

Dependency versions, SDK APIs, model licenses and device compatibility must be checked during implementation. The included plan records research and design decisions; it does not replace a successful build and real-device validation.
