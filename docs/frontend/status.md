# Frontend status

- **Date:** 2026-11-20
- **Scope:** the Compose UI in `:app` (`com.magichandy.mobile.ui`). The backend's status is in `docs/backend/status.md`.

In debug builds, the v1 UI runs on the Pixel 10 emulator (API 37) against the real core (M0–M4), with five simulated devices, a canned model, and the real foreground service and notification. `./gradlew check` passes: ktfmt formatting, Android Lint (with warnings treated as errors), debug and release builds, the backend's 326 JVM tests and the app's 16 (the UI boundary tests and the platform's Robolectric tests).

The [2026-11-19 review](../reviews/2026-11-19-ui-fidelity-and-implementation-review.md) compared every screen with its board and triaged Gemini's implementation review. Both its UI fixes and its backend fixes are in place.

## What is built

| Area | Done |
|---|---|
| App frame | Stacks content, the deck, the tabs and Stop (the last row). While typing, the tabs and play actions give way so Stop stays directly above the keyboard. Sheets open inside the content area and never cover Stop. Pressing Stop triggers haptic feedback, and TalkBack provides a "Stop all motion" action from any screen, reported as `StopSource.TalkBackAction`. |
| Theme | Uses the Graphite tokens in Steel Azure, Lowlight and High Contrast, with fixed semantic colours. Bundles Inter, plus JetBrains Mono for readouts, both under the OFL (`third_party/fonts`). Every spacing and size comes from `MagicHandyDimens`: the 4 dp scale, the boards' half steps (`tight`, `snug`, `cozy`, `roomy`) and named sizes, with no raw dp or sp values in screens. Uses light system-bar icons on every palette. |
| Routes | Before you start, Chat, Live, Devices, device setup (The Handy over Bluetooth or Cloud, the Coyote's safety notes, vibrators through Intiface, Autoblow devices by token), device pages (The Handy, VacuGlide, others), App settings, Models, adding a LAN server, OpenRouter, ChatGPT and Run interrupted. |
| Sheets | Chats; compact settings with its six rows and App settings; motion mode, style, Autopilot, planner model, persona, chat model and memory, which lists the saved details; the device-type chooser. |
| Stop receipt | Displays a panel above the footer over a dimmed screen, listing devices individually with uncertain outcomes first, and changes the footer label to "Stop again" while visible. Long lists scroll. Tapping Done hides the panel even while the core keeps an unconfirmed device; it reappears on the next Stop or when another device becomes unconfirmed. When nothing is connected, Stop shows a short toast instead. |
| Unresolved devices | Devices shows a banner for each device whose last Stop is unconfirmed, and keeps it after the receipt and Run interrupted are dismissed. Its "I've checked it" action sends `UserIntent.ReconcileDevice` after the person checks the device locally. |
| Wiring | `MagicHandyApp` owns one core, its process scope and the Android host; `MainActivity` hosts only the UI, through the platform's `ActivityConnection`, which asks for Bluetooth at explicit discovery and for notifications on the first connection. Debug builds call `createMagicHandyCore` with the simulators from the backend's test fixtures (`debugImplementation(testFixtures(…))`) and the real Android host. Release builds use the platform graph. A tap on the ongoing notification opens Live above Chat on the normal back stack (`MagicHandyRoot(openLive = …)`). |
| System access | App settings, Devices and the device pages read notification and battery access from the core's snapshot in `AppSettingsState`, which the platform refreshes whenever the app comes to the foreground. "Lock-screen Stop is off" appears only once notifications were refused, not before the app has asked. |
| Action notices | `AppState.backendNotice` displays as a short notice above the footer and any receipt, for example when a control's backend isn't built yet. |
| Accessibility | Every touch target is at least 48 dp. Rows, buttons and the Stop row reflow at 200% font scale without breaking words or hiding Stop (`LabelValue`, `ButtonRow`). |
| Boundaries | `UiBoundaryTest` (ArchUnit, in `:app`) fails the build if the UI depends on `:transport` or on anything in `:core` outside `core.api`. |
| Device and model controls | Boards V-CoyotePage, V-CoyoteLive, V-AddVibrator and V-RefusalFallback (2026-11-20). The Coyote's page sets its channel limits while it's disconnected; Live steps a playing Coyote's channels within them; vibrator setup finds and picks a vibrator at the Intiface address; and the Chat model sheet chooses a local server to retry a refused message. The core's calls that aren't intents reach the screens through `CoreCalls`. |
| Previews | Covers every screen's main states in Steel Azure and Lowlight, plus Chat and the Stop receipt at 200% font scale, in the debug source set (`app/src/debug/.../ui/preview`). Includes idle, playing and failure states: Chat without a model, a lost device and a failed server check. The debug `SampleStateActivity` renders the real UI over these same sample states for comparing with the boards. |

## Checked on the emulator

Compared side by side with every board over the sample states (2026-11-19): all screens at 100%, plus Chat, the Stop receipt, Settings, App settings and Live at 200% font scale. See the [review](../reviews/2026-11-19-ui-fidelity-and-implementation-review.md) for details.

On the real core with simulated devices:

- after the M1–M4 merge (2026-11-20): a cold start in 1.5 s with no crashes; the notification prompt on the first connection, the Devices warning after refusing it, and the warning clearing after notifications were allowed; the foreground service with its notification ("MagicHandy · Active", then "The Handy · Pace 25" while playing); the notification's Live entry from Devices, with Back returning to Chat; Stop confirmed over Bluetooth in 5 ms;
- the device and model controls (2026-11-20): the Coyote's limits stepped from 0 to 40 and 25 while it was disconnected, locked once it connected, and the hint for unread safety notes; Live's strength card with "0 / 40" and the minus keys dimmed at zero; finding the simulated Intiface vibrator, picked automatically, and adding it; and the refusal page over the sample state, with Off and the local Ollama server. The debug simulator's Coyote doesn't take strength steps, so its reported strength stays at 0; `CoyoteSessionTest` covers steps on the Bluetooth session;
- the Stop rehearsal, sending a message and the streamed reply, which also names the chat;
- connecting the simulated Handy, starting motion from Live, then Stop, confirmed over its Bluetooth session in 4 ms;
- the notice for a control whose backend isn't built yet (Autopilot).

Earlier, against the scripted demo core that this replaced:

- the Stop rehearsal on Before you start;
- Chat while playing, with the Autopilot countdown;
- Live;
- the settings sheet with motion mode and Autopilot;
- Devices and the VacuGlide page;
- the device-type sheet;
- a Stop while playing, with its receipt;
- typing with the keyboard open;
- App settings, including the blocked-notifications warning;
- switching to Lowlight.

## Left to do

| Item | When |
|---|---|
| Generate the other four locales from the desktop catalogs in `fixtures/content` | M1 |
| Make TalkBack read streamed replies as complete sentences | M1 |
| Offer the Quick Settings tile from App settings | M2 |
| Fit the device page's Test motion copy ("a short, slow stroke") to the Coyote and the vibrators, or hide the test where it doesn't apply | M1 |
| Replace the system pause glyph that the notification and the tile use with a Stop or brand icon | M1 |
| Add the diagnostics share row, once the core exposes the redacted export | M1 |
| Use the brand mark and star as the launcher icon (a placeholder is currently in place) | M1 |
| Add screenshot tests covering the previews | M1 |
| Pin the main action above Stop on Coyote setup and Add server, matching the boards | M1 |
| Close forms only once the core accepts them (review §6, decision 4) | M1 |
| Check contrast for every state in each palette | M4 |

## Deviations from the boards

- The receipt's heading reads "Stop sent · 2 confirmed, 1 unconfirmed" unless every device confirmed. The board's "Stopped · …" would claim more than the evidence supports (system design §2).
- An acknowledgement from Intiface Central alone shows as unconfirmed (system design §2), whereas V-StopReceipt and V-ConnectionLost show it as stopped. Decided on 2026-11-19: the system design's rule stands (review §6).
- While typing, the chat header also gives way so more of the transcript stays visible above the keyboard.
- Device pages include a Disconnect button, and Devices adds "leads" to the lead device's status.
- The palette control is 42 dp tall rather than about 36, to keep 48 dp touch targets.
- The VacuGlide motor-test note says "for a moment" because the board's three seconds doesn't match the core's 1.5 seconds (review §6).

## Run it

Start the emulator from Android Studio's Device Manager (or `emulator -avd Pixel_10_API_37`), then, with `JAVA_HOME` set to Android Studio's JBR:

```powershell
./gradlew :app:installDebug
```
