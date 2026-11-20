# Frontend status

- **Date:** 2026-11-20
- **Scope:** the Compose UI in `:app` (`com.magichandy.mobile.ui`). The backend's status is in `docs/backend/status.md`.

In debug builds, the v1 UI runs on the Pixel 10 emulator (API 37) against the real core (M0–M4), with simulated devices, a canned model, and the real foreground service and notification. `./gradlew check` passes ktfmt formatting, Android Lint (with warnings treated as errors), the debug and release builds, the release contents check and **700 JVM tests**: 225 in `:core`, 122 in `:transport` and 353 in `:app`, which include 268 palette contrast checks and the 60 preview screenshots.

The [2026-11-19 review](../reviews/2026-11-19-ui-fidelity-and-implementation-review.md) compared every screen with its board and triaged Gemini's implementation review. Its fixes and its two open decisions are settled: forms wait for the core, and the motor test stays at 1.5 seconds (plan §12).

## What is built

| Area | Done |
|---|---|
| App frame | Stacks content, the deck, the tabs and Stop (the last row). While typing, the tabs and play actions give way so Stop stays directly above the keyboard. Sheets open inside the content area and never cover Stop. Pressing Stop triggers haptic feedback, and TalkBack provides a "Stop all motion" action from any screen, reported as `StopSource.TalkBackAction`. |
| Palettes | A minimal dark set for phones (plan §12, decision 3): Steel Azure, the default; OLED black, with true black behind everything that fills the screen; and High contrast. A saved Lowlight shows as OLED black. App settings says what each one is for. `PaletteContrastTest` holds every palette to the desktop's contrast rules (ADR 0073): 268 checks of text, muted text, warning and danger text, inks and status marks on every ground. |
| Theme | Uses the Graphite tokens with fixed semantic colours. Bundles Inter, plus JetBrains Mono for readouts, both under the OFL (`third_party/fonts`). Every spacing and size comes from `MagicHandyDimens`, with no raw dp or sp values in screens. Uses light system-bar icons on every palette. |
| Brand | The desktop's mark (ADR 0073), from the masters in [design/brand](../../design/brand): an adaptive launcher icon with a themed variant, the splash screen, the notification's star, the Stop square on the Quick Settings tile, and the mark on Before you start, painted by the palette. |
| Languages | English, Spanish, Brazilian Portuguese, Japanese and Simplified Chinese, with terms from the desktop's catalogues (`fixtures/content/locales`). The Language row shows which language System resolves to. Choosing one sets Android's per-app language on Android 13 and later, the notification included; on Android 12 it applies inside the app only. |
| Routes | Before you start, Chat, Live, Devices, device setup, device pages, App settings, Models, adding a LAN server, OpenRouter, ChatGPT and Run interrupted. Device setup covers The Handy over Bluetooth, Handy Cloud or Intiface Central; other strokers and vibrators through Intiface; the Coyote's safety notes; and Autoblow devices by token. Each setup page pins its main action above Stop, as the boards draw it. |
| Forms | Every form waits for the core (`submit`): adding a device, saving a server or an OpenRouter key, renaming or removing a device and removing a connection. The form keeps what was typed while the core decides, closes only once it has saved, and shows the reason beside its button when refused. |
| Sheets | Chats; compact settings with its six rows and App settings; motion mode, style, Autopilot, planner model, persona, chat model with its refusal fallback, and memory with the saved details; the device-type chooser, which now offers other strokers. |
| Stop receipt | Displays a panel above the footer over a dimmed screen, listing devices individually with uncertain outcomes first, and changes the footer label to "Stop again" while visible. Long lists scroll. When nothing is connected, Stop shows a short toast instead. |
| Unresolved devices | Devices shows a banner for each device whose last Stop is unconfirmed, and keeps it after the receipt and Run interrupted are dismissed. Its "I've checked it" action sends `UserIntent.ReconcileDevice`. |
| Wiring | `MagicHandyApp` owns one core, its process scope and the Android host; `MainActivity` hosts only the UI. The core's direct calls (finding Intiface devices, form submission, the diagnostics report, Coyote controls and the refusal fallback) reach screens through `CoreCalls`. A tap on the ongoing notification opens Live above Chat on the normal back stack. |
| System access | App settings, Devices and the device pages read notification and battery access from the core's snapshot. App settings asks Android to add the Stop tile (Android 13 and later; before that it explains how) and shows Added from the tile's own report. Share diagnostics writes the redacted report to the app's cache and opens the share sheet. |
| Accessibility | Every touch target is at least 48 dp. Rows, buttons and the Stop row reflow at 200% font scale without breaking words or hiding Stop. TalkBack hears a streaming reply a sentence at a time, never a sentence still being written, while the Stop receipt stays assertive. |
| Device and model controls | Boards V-CoyotePage, V-CoyoteLive, V-AddVibrator, V-RefusalFallback and V-AddHandyIntiface. The Coyote's page sets its channel limits while it's disconnected, and Live steps a playing Coyote's channels within them. The Intiface picker finds vibrators or strokers at an address and connects the one picked, with the bridge's Stop caveat first for strokers. A generic stroker's page has Reverse direction and no model. Test motion's wording fits each device; the Coyote has Check alone, since a test at zero strength couldn't be felt. |
| Boundaries | `UiBoundaryTest` (ArchUnit, in `:app`) fails the build if the UI depends on `:transport` or on anything in `:core` outside `core.api`. |
| Previews and screenshots | Every screen's main states in Steel Azure and OLED black, plus Chat and the Stop receipt at 200% font scale, in the debug source set. `PreviewScreenshotTest` draws all 60 on Robolectric and compares each with its recorded image in `app/src/test/screenshots`. `check` fails on any change bigger than stray anti-aliasing. The debug `SampleStateActivity` renders the real UI over the same sample states. |

## Checked on the emulator

On 2026-11-20, on the real core with simulated devices:

- the launcher icon in the app drawer, Before you start with the mark, and the ongoing notification with the brand icon;
- App settings: the three palettes with their descriptions, OLED black, "System · English", version and update in one row, the Stop tile request through Android's own dialog with Added afterwards (also after leaving and reopening App settings), and Share diagnostics opening the share sheet with one redacted text file;
- switching the language to Spanish and Japanese, and back to System; in Japanese the palette control moves under its label rather than splitting a word;
- The Handy through Intiface Central: the caveat, finding the simulated stroker, connecting it, and its page with Reverse direction;
- the first connection's notification prompt, with the notification posted as soon as it was allowed;
- a refused rename that kept the name typed and said another device has it;
- Coyote setup, Add vibrator and Add server with their main actions above Stop, and each device's Test motion wording.

Earlier checks (2026-11-19 and the morning of 2026-11-20) covered every board side by side at 100% and Chat, the Stop receipt, Settings, App settings and Live at 200%; the Coyote's limits and Live steps; finding a vibrator; the refusal fallback page; and Stop confirmed over the simulated Handy's Bluetooth session in 4–5 ms.

## Left to do

| Item | Who |
|---|---|
| Phone acceptance on real devices and accounts ([checklist](../backend/device-checklist.md)) | Owner, with the release build |
| Create the release keystore and publish a signed release ([release guide](../release.md)) | Owner |
| Check TalkBack's sentence-by-sentence reading with TalkBack on a phone (the splitting is unit-tested) | Owner, with phone acceptance |

## Deviations from the boards

- The receipt's heading reads "Stop sent · 2 confirmed, 1 unconfirmed" unless every device confirmed. The board's "Stopped · …" would claim more than the evidence supports (system design §2).
- An acknowledgement from Intiface Central alone shows as unconfirmed (system design §2), whereas V-StopReceipt and V-ConnectionLost show it as stopped.
- While typing, the chat header also gives way so more of the transcript stays visible above the keyboard.
- Device pages include a Disconnect button, and Devices adds "leads" to the lead device's status.
- The palette control is 42 dp tall rather than about 36, to keep 48 dp touch targets, and moves under its label when the names don't fit beside it.
- The VacuGlide motor-test note says "for a moment" because the board's three seconds doesn't match the core's 1.5 seconds.

## Run it

Start the emulator from Android Studio's Device Manager (or `emulator -avd Pixel_10_API_37`), then, with `JAVA_HOME` set to Android Studio's JBR:

```powershell
./gradlew :app:installDebug
```

After a deliberate change to a screen, record its new screenshots:

```powershell
./gradlew :app:recordRoborazziDebug
```
