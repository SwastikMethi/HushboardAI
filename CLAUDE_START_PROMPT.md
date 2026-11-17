# Copy this prompt into Claude Code

Implement the Android AI keyboard and companion app in this folder.

First read `CLAUDE.md`, `AI_Keyboard_Implementation_Plan.md`, `docs/UI_SPEC.md`, `docs/BUILD_AND_TEST_CHECKLIST.md` and `PROGRESS.md`. Inspect both PNGs in `design/`; they are local reference files.

Java is the selected primary language. Use Java, Android Views/XML, Material Components, View Binding and Groovy Gradle scripts. Use Java executors/callbacks for background work, RxJava 3 Preferences DataStore for shared settings, and ListenableWorker for downloads. Inspect Desert Ant's real SDK. Only if required by its suspend APIs, use the smallest isolated Kotlin interoperability bridge; all app logic stays Java. Do not reopen the language choice or switch to Compose.

Create the real Android project in this folder, preserving these documents. Start with the compatibility spike and working keyboard/Emo vertical slice, then continue through milestones 0–9. The target includes all 13 compatible catalog models: Emo, Redact, Toxic, Tongue, Gist, Voz, Clear, Ear, Uhm, Who, Eye, Moderator and Shapes. Run each on its appropriate input event, with explicit voice/image/drawing workflows. Do not treat the first three models as the final scope.

Match the approved keyboard composition and softened purple panel. Animate the purple-blue AI control once when enabled, then keep the active gradient icon static. Use the companion image's structure, updated for the expanded catalog and the written UI specification.

Use real model adapters and explicit downloads, with accurate progress/readiness/error states. Keep typing responsive; invalidate stale suggestions; apply model edits only after acceptance and editor revalidation. Do not invent SDK APIs, fake inference, automatically read full chat history, or send messages.

Add purpose comments to handwritten source files and reusable classes where useful. Maintain `PROGRESS.md`. Record pinned dependencies and per-model compatibility in `docs/model-compatibility.md`. Run build, lint and focused tests; record evidence in `docs/validation-report.md`. Continue all independent work if a device or external dependency blocks a specific test, and identify what remains blocked.

At the end, give me the source/project path, build instructions, debug APK path if built, implemented capabilities, test results, and any precise remaining blockers. The goal is a working Android app, not only a plan or UI prototype. Never claim a device test passed unless it actually ran.
