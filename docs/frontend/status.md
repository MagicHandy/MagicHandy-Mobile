# Frontend status

- **Date:** 2026-11-25
- **Scope:** the Compose UI in `:app` (`com.magichandy.mobile.ui`). The backend's status is in `docs/backend/status.md`.

In debug builds, the v1 UI runs on the Pixel 10 emulator (API 37) against the real core (M0–M4), with simulated devices, a canned model, and the real foreground service and notification. `./gradlew check` passes ktfmt formatting, Android Lint (with warnings treated as errors), the debug and release builds, the release contents check and **1,064 JVM tests**: 315 in `:core`, 247 in `:transport` and 502 in `:app`, which include 268 palette contrast checks, the 106 preview screenshots, voice input's 59 and the voice test's 10. Version 0.1.6 is the first public release (2026-11-21), and 0.1.8 adds voice input.

The [2026-11-19 review](../reviews/2026-11-19-ui-fidelity-and-implementation-review.md) compared every screen with its board and triaged Gemini's implementation review. Its fixes and its two open decisions are settled: forms wait for the core, and the motor test stays at 1.5 seconds (plan §12).

## What is built

| Area | Done |
|---|---|
| App frame | Stacks content, the deck, the tabs and Stop (the last row). While typing, the tabs and play actions give way so Stop stays directly above the keyboard. Sheets open inside the content area and never cover Stop. Pressing Stop triggers haptic feedback, and TalkBack provides a "Stop all motion" action from any screen, reported as `StopSource.TalkBackAction`. |
| Palettes | A minimal dark set for phones (plan §12, decision 3): Steel Azure, the default; OLED black, with true black behind everything that fills the screen; and High contrast. A saved Lowlight shows as OLED black. App settings says what each one is for. `PaletteContrastTest` holds every palette to the desktop's contrast rules (ADR 0073): 268 checks of text, muted text, warning and danger text, inks and status marks on every ground. |
| Theme | Uses the Graphite tokens with fixed semantic colours. Bundles Inter, plus JetBrains Mono for readouts, both under the OFL (`third_party/fonts`). Every spacing and size comes from `MagicHandyDimens`, with no raw dp or sp values in screens. Uses light system-bar icons on every palette. |
| Brand | The desktop's mark (ADR 0073), from the masters in [design/brand](../../design/brand): an adaptive launcher icon with a themed variant, the splash screen, the notification's star, the Stop square on the Quick Settings tile, and the mark on Before you start, painted by the palette. |
| Languages | English, Spanish, Brazilian Portuguese, Japanese and Simplified Chinese, with terms from the desktop's catalogues (`fixtures/content/locales`). The Language row shows which language System resolves to. Choosing one sets Android's per-app language on Android 13 and later, the notification included; on Android 12 it applies inside the app only. |
| Setup | First-run setup in four steps (plan §14.3; boards V-Onboarding and V-SetupModel to V-SetupReadyNoModel). Before you start is step 1. Choose your AI opens the existing provider pages, and a provider saved there moves on to Your companion. Your companion sets MagicHandy's level, style and description, saved on Continue. You're set reads back what was saved, and either Start chatting or Finish setup ends setup. Skip for now moves one step on, Back walks back, the tabs stay hidden, and a tap on the notification doesn't open Live mid-setup. |
| Personas | Personas lists MagicHandy first, then the person's own (board V-Personas). Each editor (V-PersonaDefault and V-PersonaCustom) keeps a draft until Save. The level and style pickers give every option its definition (V-PersonaStyle). The description counts characters as the core does and says how many to remove when over 500. A persona the person made is renamed in its title and offers Use persona, or Save and use while it has changes. More options duplicates it as "Kit copy" or deletes it after a confirmation naming what goes. Leaving with unsaved changes asks first. MagicHandy has Reset to default. Confirmations and the menu are drawn inside the content area, so Stop stays in reach. |
| Routes | Before you start, the setup steps, Choose your AI, Personas, a persona's editor, Chat, Live, Devices, device setup, device pages, App settings, Models, adding a LAN server, OpenRouter, ChatGPT and Run interrupted. Device setup covers The Handy over Bluetooth, Handy Cloud or Intiface Central; other strokers and vibrators through Intiface; the Coyote's safety notes; and Autoblow devices by token. Each setup page pins its main action above Stop, as the boards draw it. |
| Forms | Every form waits for the core (`submit`): adding a device, saving a server or an OpenRouter connection, changing a saved OpenRouter connection, renaming or removing a device and removing a connection. The form keeps what was typed while the core decides, closes only once it has saved, and shows the reason beside its button when refused. |
| Sheets | Chats; compact settings with its six rows and App settings; motion mode, style, Autopilot, planner model, persona (choosing one returns to Settings, and Manage personas opens Personas), chat model with its refusal fallback, and memory with the saved details; the device-type chooser, which now offers other strokers. |
| Chat | Each reply keeps the name of the persona that wrote it after a switch. With no chat model, Choose an AI sits above the composer. Each page keeps what it saved, such as Chat's draft, while it's on the back stack, so the draft survives Choose your AI or Live. |
| Stop receipt | Displays a panel above the footer over a dimmed screen, listing devices individually with uncertain outcomes first, and changes the footer label to "Stop again" while visible. Long lists scroll. When nothing is connected, Stop shows a short toast instead. |
| Unresolved devices | Devices shows a banner for each device whose last Stop is unconfirmed, and keeps it after the receipt and Run interrupted are dismissed. Its "I've checked it" action sends `UserIntent.ReconcileDevice`. |
| Wiring | `MagicHandyApp` owns one core, its process scope and the Android host; `MainActivity` hosts only the UI. The core's direct calls (finding Intiface devices, form submission, the diagnostics report, Coyote controls and the refusal fallback) reach screens through `CoreCalls`. A tap on the ongoing notification opens Live above Chat on the normal back stack. |
| System access | App settings, Devices and the device pages read notification and battery access from the core's snapshot. App settings asks Android to add the Stop tile (Android 13 and later; before that it explains how) and shows Added from the tile's own report. Share diagnostics writes the redacted report to the app's cache and opens the share sheet. |
| Accessibility | Every touch target is at least 48 dp. Rows, buttons and the Stop row reflow at 200% font scale without breaking words or hiding Stop. TalkBack hears a streaming reply a sentence at a time, never a sentence still being written, while the Stop receipt stays assertive. |
| Device and model controls | Boards V-CoyotePage, V-CoyoteLive, V-AddVibrator, V-RefusalFallback and V-AddHandyIntiface. The Coyote's page sets its channel limits while it's disconnected, and Live steps a playing Coyote's channels within them. The Intiface picker finds vibrators or strokers at an address and connects the one picked, with the bridge's Stop caveat first for strokers. A generic stroker's page has Reverse direction and no model. Test motion's wording fits each device; the Coyote has Check alone, since a test at zero strength couldn't be felt. Adding The Handy labels Handy Cloud **Recommended**. Choosing a nearby device ends the scan and keeps the list, and a device that couldn't be verified says so in its row and offers Retry. |
| Boundaries | `UiBoundaryTest` (ArchUnit, in `:app`) fails the build if the UI depends on `:transport` or on anything in `:core` outside `core.api`. |
| Voice test | Debug and phone-test builds only ([guide](../voice-test.md), plan §19; boards V-VoiceLab, V-VoiceLabListen and V-VoiceLabClip). A separate **Voice test** launcher entry compares Parakeet v3 (int8, 4-bit and full precision, through sherpa-onnx) with Google ML Kit's Basic and Advanced speech recognition. It works hands-free, with a speech detector and the screen off, or runs one clip on every engine and scores it against the line read out. It raises quiet speech with its own automatic gain, cuts turns with padding before and after, and shows the microphone's level and the detector's confidence while it listens. It never sends a message or moves a device, and the Stop bar stops every device directly. It runs voice input's own capture, gain, segmenter, model store and recognizer, and Settings › Voice › Advanced › Check the microphone opens it. The release build has voice input but not this page. |
| Voice input | Hands-free speech to text in Chat (plan §20, [design](../voice-design.md); boards V-VoiceIdle to V-VoiceAdvanced). With nothing typed, the microphone stands in for Send. The first tap opens Talk instead of typing: **Download and start** fetches Compact, and phones with 12 GB or more also get **Choose another model** for full precision. While listening, the voice bar replaces the composer with Listening or Hearing you, the meter and End, and other screens show a Listening · End chip. A spoken Stop stops every device directly, any Stop ends listening, and the receipt says "voice ended". Downloads, failures and the reasons listening ended sit above the composer, each with its next step. App settings gains Voice, which shows the model, switches it with a progress row, removes it and links to microphone access; test builds add the hidden settings. Every board was compared with the built screen before release. Listening runs in its own `:voice` process as a microphone foreground service. |
| Previews and screenshots | Every screen's main states in Steel Azure and OLED black, plus Chat, the Stop receipt and a persona's editor at 200% font scale, in the debug source set. `PreviewScreenshotTest` draws all 106, voice input's states among them, on Robolectric and compares each with its recorded image in `app/src/test/screenshots`. `check` fails on any change bigger than stray anti-aliasing. The debug `SampleStateActivity` renders the real UI over the same sample states. |

## Checked on the emulator

On 2026-11-25, voice input in the debug build, on the Pixel 10 emulator started with 12 GB of memory, with simulated devices:

- **First use:** Chat offered the microphone, and its first tap opened Talk instead of typing with Choose another model. The picker opened over it, and Back returned to it.
- **Download and start:** the 671 MB of Compact and Silero downloaded in about 2 minutes and passed their checksums. Listening began about 3 s later, in the `:voice` process at about 930 MB, as a microphone foreground service bound to the main process.
- **Listening:** the voice bar showed Listening, the meter and End, and Devices showed the Listening · End chip above the tabs. Stop ended listening and freed the model; so did End in the voice bar and in the notification.
- **The notification:** "Listening" with End, once the app had notification access. Without it, as on a fresh install with no device connected yet, Android hides it while listening carries on; the app asks for notifications when a device first connects.
- **Settings › Voice:** Ready in App settings. Switching to full precision showed its progress row, paused when the app left and resumed on return, and Cancel deleted what it had fetched. Remove asked for a second tap and deleted the model.
- **The microphone refused:** Android's prompt, then "Voice needs microphone access" with Open settings, which opened the app's page in Android's settings.
- **Not checked:** speech, a spoken Stop and the 15-minute timeout, since the emulator had no audio input. They're for the phone.

Earlier on 2026-11-25, the voice test in the debug build:

- **The page:** it matches its boards, and ML Kit's Basic and Advanced modes both report "Not on this phone", as the emulator has neither model.
- **Parakeet int8:** it downloaded and passed its checksums, loaded in 2.3 s at about 800 MB, and transcribed sherpa-onnx's 3.85 s sample word for word in 0.25 s on the PC's CPU.
- **Hands-free:** it started a microphone foreground service, with its notification and Android's microphone indicator, and stopped cleanly.
- **Share:** it opened the share sheet.
- **Not checked:** speech itself, since the emulator had no audio input. Speech is checked on the phone.
- **Detection, after the first phone test:** a temporary probe played sherpa-onnx's sample through the page's own pipeline at −40 to −63 dBFS, clean and in a simulated room at 1 m and 2 m. The first build lost words from −55 dBFS and heard nothing at 2 m. The gain, the new turn cutting and the padding heard the whole sentence every time, with the int8 and 4-bit builds alike (plan §19).

On the evening of 2026-11-20, after merging the persona backend, from a fresh install:

- setup: Before you start as step 1 of 4; Back from Choose your AI to step 1 with the box still ticked; Skip for now; Warm, Playful and a typed description on Your companion, with the keyboard opening with the description sheet and Stop above it; You're set reading back the saved choices; and Start chatting ending setup;
- Personas: a new persona with the name focused, its level and style, Save; Use persona; Duplicate making "Kit copy"; Back with changes asking "Discard changes?" and Keep editing keeping them; Delete after its confirmation, returning to Personas;
- the Persona sheet returning to Settings after a choice, and Chat replies labelled with the persona that wrote them, the earlier one keeping its name after a switch;
- a Chat draft surviving a trip to Live and back.

Earlier on 2026-11-20, on the real core with simulated devices:

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
| Compare Bluetooth and Cloud motion on the same Handy ([checklist](../backend/device-checklist.md#verify-handy-bluetooth-on-the-phone)) | Owner, with 0.1.7 |
| Planned: read the slider over Bluetooth so runs ease in, as on Cloud ([plan §11](../../android-architecture-plan.md#11-milestones-and-exit-criteria)) | Backend, then owner |
| Run the voice test on the Pixel 10 Pro XL and share its results ([guide](../voice-test.md#a-test-session)) | Owner, with MagicHandy Test |
| Try voice input on the Pixel: how quickly a spoken Stop stops, a short sharp "Stop!", 30 minutes with the screen off, the call path with TalkBack, and full precision against Compact ([design §10](../voice-design.md#10-before-building)) | Owner, with 0.1.8 |
| Check TalkBack's sentence-by-sentence reading with TalkBack on a phone (the splitting is unit-tested) | Owner, with phone acceptance |
| Check that a ChatGPT sign-in, an OpenRouter key and a LAN server each move setup on to Your companion (only the simulated model was available here) | Owner, with phone acceptance |

## Deviations from the boards

- The receipt's heading reads "Stop sent · 2 confirmed, 1 unconfirmed" unless every device confirmed. The board's "Stopped · …" would claim more than the evidence supports (system design §2).
- An acknowledgement from Intiface Central alone shows as unconfirmed (system design §2), whereas V-StopReceipt and V-ConnectionLost show it as stopped.
- While typing, the chat header also gives way so more of the transcript stays visible above the keyboard.
- Device pages include a Disconnect button, and Devices adds "leads" to the lead device's status.
- Adding The Handy labels Handy Cloud "Recommended", which V-AddHandy doesn't show. The board has no failed verification; here the row says "Couldn't verify" and offers Retry.
- The palette control is 42 dp tall rather than about 36, to keep 48 dp touch targets, and moves under its label when the names don't fit beside it.
- The VacuGlide motor-test note says "for a moment" because the board's three seconds doesn't match the core's 1.5 seconds.
- Before you start, as setup's first step, has the setup header and Continue in place of Start chatting; V-Onboarding still draws the one-page first run.
- You're set shows the chat model's full name, such as "ChatGPT · GPT-6.1 Terra", as V-SetupReady does, and lists connected devices instead of Optional.
- On Your companion, the description opens in a sheet, which the boards don't draw, and once written it shows on its row.
- Duplicate makes the copy straight away and returns to Personas, rather than opening an editor filled in with the copy.

## Run it

Start the emulator from Android Studio's Device Manager (or `emulator -avd Pixel_10_API_37`), then, with `JAVA_HOME` set to Android Studio's JBR:

```powershell
./gradlew :app:installDebug
```

After a deliberate change to a screen, record its new screenshots:

```powershell
./gradlew :app:recordRoborazziDebug
```
