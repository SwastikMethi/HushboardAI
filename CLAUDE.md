# Implementation instructions

## Objective and order of authority

Build the Android AI keyboard and companion app described in `AI_Keyboard_Implementation_Plan.md`. Read it in full before coding. Read `docs/UI_SPEC.md`, `docs/BUILD_AND_TEST_CHECKLIST.md` and both local design images.

The user's current instructions take precedence, followed by the written plan and UI specification, then the reference images. The images illustrate layout and appearance; they are not executable product behavior. Preserve the supplied handoff files as project documentation.

## Language and architecture

- Java is final. Use Java for the IME, Activity/Fragments, controllers, adapters, repositories and tests, with Android Views/XML, Material Components and View Binding.
- Use Groovy Gradle build scripts and a version catalog. Pin a compatible toolchain and generate a real Gradle wrapper.
- Keep one app module initially. Use the planned packages and an AppContainer for shared dependencies. Do not run the IME in a separate process.
- Use bounded Java executors for inference, scheduled debounce, lifecycle-aware UI observation and callbacks/futures with cancellation. The main thread never waits for inference, model loading or file transfer.
- Use one RxJava 3 Preferences DataStore instance per settings file. Share it between the companion app and IME.
- Download models through a Java ListenableWorker and ModelRepository. `startWork()` must return promptly; schedule the actual work off the main thread.
- Inspect the vendor's actual Android APIs. Prefer Java-callable APIs. Only if required, put a small Kotlin suspend bridge in `sdkbridge/DesertAntSuspendBridge.kt`, exposing Java-friendly callbacks and cancellation. Document the need. Keep all product logic Java; do not add Compose or embedded Python.

## Product requirements

Implement milestones 0–9, including the full compatible 13-model catalog and each modality's input workflow. Track compatibility per model; do not silently reduce scope to the first three. An SDK or device blocker must be explicit, not hidden behind a functioning-looking toggle.

Normal typing remains usable with AI off, absent model assets, errors and slow inference. Use real inference in production paths; synthetic responses are limited to clearly separated tests. Model-ready state requires validated assets. Downloads require a user action, and installed inference must work without a network.

The IME can use bounded text exposed by the active editor. It does not automatically see whole conversations, received messages, attachments or recipient identities. Do not add accessibility scraping, notification reading, a custom backend or a cloud inference path. Microphone, image and audio flows begin from explicit actions.

Only EditorBridge edits the host text. Revalidate the input session, generation, selection, settings revision and target text before applying a suggestion. User acceptance is required for model-driven edits. Never auto-send. Disable AI in password fields and follow the plan's no-learning-field policy. Keep draft content out of disk persistence, logs, analytics and crash payloads.

## Design and code quality

Follow `design/01_Keyboard_Approved.png` for the final keyboard. Keep the support panel's purple gradient subtle. Use the purple-blue active icon and a single 600 ms enable sweep; respect disabled system animations. Use `design/02_Companion_App_Reference.png` for companion composition, with the written catalog and corrections in the UI specification.

Use a short purpose comment at the top of every handwritten source file, including XML where valid. Explain lifecycle/cancellation decisions near the relevant code. Reuse classes for shared behavior; avoid layers that add no ownership or abstraction value. Never insert comments into JSON. Generated files and the Gradle wrapper do not need custom comments.

## Working method and evidence

1. Inspect the environment and supplied documents. Record the chosen JDK, Android SDK, Gradle/AGP versions, supported ABIs and actual dependency resolution.
2. Prove SDK loading, download behavior and Java interoperability before relying on guessed methods or constructors.
3. Build the keyboard vertical slice, then continue through the remaining milestones. Update `PROGRESS.md` with concrete evidence and blockers.
4. Add focused tests for editor mutation, stale results, eligibility, asset state, settings propagation and lifecycle behavior. Run the applicable build, lint and test tasks after material changes.
5. Implement all independent work when physical hardware or a vendor feature is unavailable; identify exactly which remaining gates cannot be verified. Do not mark them passed.
6. Finish with runnable source, build/setup instructions, a debug APK if the environment can build it, a compatibility report, and a validation report separating passed, failed, blocked and not-run checks. Report the actual artifact paths and commands used.

Do not present a successful compilation, emulator run or static screenshot as proof of physical-device performance or compatibility with all chat apps. Do not declare the whole app complete while required milestones remain unimplemented or blocked.
