# AI Keyboard — Java implementation handoff

> **Status, 17 Nov 2026.** This folder now holds the implemented Android project as well as the handoff. Milestones 0–8 are built and tested on an emulator; milestone 9's checks that need a physical phone are still open. What passed, failed, is blocked or was not run is in [docs/validation-report.md](docs/validation-report.md); every model's status is in [docs/model-compatibility.md](docs/model-compatibility.md); decisions and open items are in [PROGRESS.md](PROGRESS.md). The handoff text below is kept as it was written.

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

- **Memory.** The speech model (Voz) needs about 4 GB while it runs and is refused on devices with under 5 GiB; the image model (Eye) needs about 1.5 GB. Both are unloaded shortly after use.
- **Emulators.** The stock emulator image has too little memory for Voz and its software GPU crashes Clear. The tests here ran on an emulator with 8 GB of memory started with `-gpu host`.
- **License.** The models are Desert Ant Labs' and are used under their source-available license, which the project owner accepted. Their software makes a license check (about once a month per model); the app says so on its Privacy screen and shows a "Powered by Desert Ant Labs" credit in Settings.
- **Not for a store yet.** The application id is `com.example.aikeyboard`, the build is debug-signed, and the APK size needs a packaging decision.

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
