# AI Keyboard — Java implementation handoff

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
