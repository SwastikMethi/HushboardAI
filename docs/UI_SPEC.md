# AI Keyboard — UI implementation specification

Status: design requirements, not a tested interface. The implementation plan governs scope and technical behavior.

## Visual references and precedence

| Reference | Use |
|---|---|
| [Approved keyboard](../design/01_Keyboard_Approved.png) | Final keyboard composition, AI suggestion strip, muted purple panel, gradient active control |
| [Companion app](../design/02_Companion_App_Reference.png) | Navigation, grouped settings rows, model details and test field |

Follow the written requirements when a mockup contains older behavior:

- Populate Text, Voice, Images and Drawing with the full catalog rather than limiting the app to visible example rows.
- Retain model choices automatically when AI is paused; remove the extra preference for this.
- Label the WorkManager network policy **Unmetered networks only** unless literal Wi-Fi transport enforcement is implemented.
- Keep AI-off icon and border neutral. Any colored edge in the mockup is not a requirement for an inactive glow.
- Model sizes, confidence controls, categories, coverage and beta status must reflect verified SDK capabilities. Illustrative sample results are not production responses.
- Apply the final keyboard's AI gradient identity to the companion app's master AI control. Keep ordinary controls pale blue.

The chat conversation above the keyboard in the reference is context for the mockup. The product owns only the keyboard region; it does not render or decorate another app's messages.

## Color and motion tokens

| Token | Starting value or rule |
|---|---|
| Canvas | Near-black charcoal; use the reference to tune neutral surfaces |
| Keys and grouped settings | Slightly lighter charcoal, soft corners, restrained separation |
| AI panel background | `#392847` → `#272230` → `#181B25`; subtle, not a saturated purple sheet |
| Active AI identity | `#A970FF` → `#568DFF` on sparkle, thin border and master control |
| Ordinary enabled switches | Pale blue |
| Disabled controls | Neutral gray with an explicit state label |
| Text | High-contrast white primary and readable gray secondary text |
| Enable transition | One gradient sweep, 600 ms, only when the user changes AI off → on |
| Steady active state | Static gradient sparkle/border; no repeated pulse |
| Disabled system animations | Skip the sweep; show the final active state immediately |

Use shared resources/DesignTokens. Construct gradients and animation with native Android Views/drawing. Do not use a WebView. Rotation, opening a panel or recreating an Activity must not replay an enable animation. Do not illuminate an active state until the setting has been committed successfully.

The images are visual references rather than a pixel specification. Use dp/sp, system insets, a coherent spacing scale and responsive constraints. Validate contrast after implementation. Settings actions should provide accessible touch targets; dense keyboard keys need suitable hit regions, TalkBack behavior and device testing. Support larger text without truncating essential state or controls.

## Keyboard states

### Normal typing

Keep the familiar QWERTY structure: three letter rows, shift, backspace, number/symbol toggle, comma, emoji, language/space, period and editor action. Respect the host's action label; Enter/Send/Next behavior follows EditorInfo and an explicit user keypress.

The toolbar provides a compact AI on/off control and relevant keyboard tools. Show a microphone only when real recording/transcription exists. Optional toolbar tools must have real implementations or be hidden. Basic local word suggestions use a separately licensed dictionary; they are not Emo output.

### AI suggestions

Maintain the keyboard footprint. Emoji candidates appear in a compact suggestion strip. At most one Redact/Toxic notice may appear alongside a small candidate set; privacy notices take priority over tone notices, then emoji suggestions. Use restrained copy, such as **Personal information detected**, with Review/Dismiss actions. Detection is not a guarantee of coverage.

Selecting a suggestion triggers editor validation before insertion or replacement. Reviewable redaction previews show original and masked spans, an explicit apply action, and guarded Undo. A stale draft clears the proposal and recomputes; it must never replace newly typed text. If only partial context is available, label that scope.

If AI is enabled but assets are missing, show a brief setup state with a route to models. Do not obstruct keys or silently download. While a model is loading or fails, ordinary typing stays available.

### AI support panel

Open from the AI toolbar control. Replace the key area at a comparable height with:

1. Back control, sparkle, **AI support** title and master switch.
2. Short explanatory line: **Choose what helps while you type**.
3. Relevant model rows with icon, name, one-line purpose and switch/status.
4. **Manage models** and **Back to keyboard** actions.

The initial text controls emphasize Emo, Redact and Toxic; offer the other text capabilities without crowding the first view. Voice/image tools belong to their explicit modes and companion pages. Use scrolling when needed, keeping the back/master controls reachable.

The master switch pauses invocation and clears suggestions, while preserving all individual choices and downloaded assets. When off, show **Paused** rather than implying each model has been deleted or disabled. Password/unsupported fields show the relevant unavailable state without exposing field contents.

## Companion app screens

### Onboarding and Keyboard tab

Explain the current-draft boundary, then link to Android keyboard settings and the system input picker. Verify enabled/selected state when the app resumes. Include a real editable practice field and model setup action. The app cannot silently grant itself keyboard or microphone access.

### Models tab

Keep the master AI card above grouped model rows, with bottom navigation **Keyboard / Models / Settings**. Use a list rather than a dense grid of large cards. Show concise metadata, a status/action, and an entry into model details.

| Group | Models |
|---|---|
| Text | Emo, Redact, Toxic, Tongue, Gist |
| Voice | Voz, Clear, Ear, Uhm, Who |
| Images | Eye, Moderator |
| Drawing | Shapes |

Asset state and the user's enable preference are distinct. Before download, show Download and verified size if known. During transfer, show real progress or an indeterminate indicator when no total is known, plus Cancel. After validation, show Ready and the enable control. A bundled model shows Included. Failed transfers offer Retry with a concise reason; incompatible devices show Unavailable with the reason. A disabled downloaded model still appears as installed.

Do not imply that every installed model is loaded in memory or that enabling an image/audio model gives access to another app's media. Platform-limited models can be documented separately; do not present them as working Android toggles.

### Model detail

Show model purpose, status, enable control, supported languages/input, model-specific settings, local storage information and an explicit test action. Offer only options supported by the verified SDK or clearly defined application behavior.

For Redact, retain the reference's **Warn only / Offer masking** choice, supported category controls, editable test field and preview. Masking always needs user acceptance. Show advanced confidence controls only where meaningful. A reset action resets this model's settings, not every model's configuration.

### Voice, image and drawing tools

Voice starts with Record/Import; request microphone access at the recording action. Show selected processing steps, progress, cancellation and a preview before committing a transcript or exporting audio. Do not represent filler-word detection as audio editing unless cutting is implemented and verified.

Image tools use an explicit image picker/import and show the chosen image plus analysis results. Sharing uses supported Android mechanisms with a fallback if a host rejects rich content. Drawing uses a stroke surface, clear/retry, recognized-shape preview and an explicit use/export action. Shapes is not handwriting-to-text.

### Settings tab

Group keyboard settings (theme, supported languages, height, typing/autocorrect, sound/haptics), AI preferences (frequency and app-specific use), and model/storage/privacy controls. Show only implemented theme/language choices. Configure app-specific AI preferences using packages observed during keyboard use rather than requesting a full installed-app inventory.

Downloads/storage supports accurate installed sizes, retries and model deletion. Deleting assets first disables the model and releases any active runtime safely. Explain bundled-asset limitations. Privacy controls explain on-device inference, temporary draft handling, explicit media retention/cleanup, and model downloads without claiming that Android input methods see nothing sensitive.

## Interaction acceptance

- Keyboard and companion switches synchronize from the same persisted settings.
- Turning AI off clears pending suggestions immediately and preserves per-model choices.
- Background processing never freezes keys, back navigation or cancellation controls.
- Text, switches, rows and icons have meaningful TalkBack labels and focus order.
- Loading, errors, offline status and disabled states have textual cues, not color alone.
- The reduced purple panel remains readable on a real device; verify it with the keyboard visible in a host app.
- Capture implementation screenshots of normal typing, active suggestions, support panel, catalog, model detail and settings for comparison with the supplied references.
