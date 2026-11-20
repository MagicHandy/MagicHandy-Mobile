# UI fidelity and implementation review

- **Date:** 2026-11-19
- **Scope:** the Compose UI against the v1 boards in [design/canvas](../../design/canvas), and the whole M0 implementation (`:core`, `:transport`, `:app`).
- **Reviewers:** Claude (fidelity review, triage and the UI fixes); Gemini 4 Neon at `xhigh` reasoning in the Antigravity CLI (the implementation review).
- **Status:** everything below is fixed except two items: finding 12's form handling, which waits on decision 4 in §6, and the board drift listed in §2.3. `./gradlew check` passes with 105 tests. The UI fixes landed first; the backend fixes in §5 followed later the same day from a second Astra Max run, along with decisions 1 and 2 in §6. Decisions 3 and 4 remain open.

## 1. Summary

The first UI followed the boards' structure and copy closely, but it looked plainer than the sketches. The main causes were using the system font instead of Inter, looser spacing, generic icons instead of the boards' device icons, extra controls the boards don't have and two rendering bugs. After the fidelity pass, side-by-side captures closely match every board, apart from the drift listed in §2.3.

Gemini reported 17 findings. After checking each against the code:

- 9 are confirmed and owned by the UI, and are fixed (§4);
- 9 are confirmed or plausible and owned by the backend, including 3 critical Stop-path issues (§5);
- 2 are false positives, and 3 were already fixed by the fidelity pass;
- 4 need a decision from you (§6).

Some findings have parts with different owners, so the counts overlap.

## 2. UI fidelity

### 2.1 What fell short

- **Type.** The app drew with the system font. The boards use Inter, with a monospaced face for paces, timers, latencies and signal strengths.
- **Spacing and density.** Rows, cards and headers were looser than the boards, so screens fit less content and looked unfinished.
- **Icons.** Devices used generic icons; the boards have one per family (Handy, vibrator, Coyote bolt, Ultra, VacuGlide).
- **Extra controls.** Device pages had a run section with a lead toggle that the boards don't show.
- **Bugs.** The chat transcript wasn't anchored to the bottom, and the status-bar icons were dark on the dark UI.
- **Stop receipt.** It didn't dim the screen behind it, as the board does.

### 2.2 What changed

- Inter (400–700) and JetBrains Mono (400, 600) are bundled under the SIL Open Font License, with the licences in [third_party/fonts](../../third_party/fonts). Readouts are set in mono through `withMonoReadouts`.
- Chat, the deck, Live, Devices, the device pages, adding a device, Before you start, the Stop receipt, App settings and Run interrupted were rebuilt against their boards.
- The boards' device icons and the warning icon are in `AppIcons`.
- A debug-only `SampleStateActivity` shows the real UI over fixed sample states, so each screen can be compared with its board.

### 2.3 Remaining drift

| Board | Difference |
|---|---|
| V-Coyote, V-AddServer | The main action sits after the content rather than pinned above Stop. The Coyote header lacks the device tile and its "Before you connect" subtitle. |
| V-AppSettings | No Quick Settings tile or diagnostics rows yet (M1, with the platform layer). The version and "Check for update" are two rows rather than one. Language shows "System" without the resolved language. |
| V-StopReceipt, V-ConnectionLost | An acknowledgement from Intiface Central alone now shows as unconfirmed, following system design §2. The boards show it as stopped. See §6. |
| V-VacuGlide | The motor-test note says "for a moment" rather than "three seconds". See §6. |
| V-AppSettings | The palette control is 42 dp tall rather than about 36, to keep 48 dp touch targets. |

## 3. Implementation review

Gemini ran read-only on a disposable copy (`.work/review`), using the brief in `.work/gemini-review-prompt-2.md`; its full report is in `.work/gemini-review.md`. That copy predated the fidelity pass, so some UI findings were already fixed.

| # | Finding | Severity | Verdict | Owner | Status |
|---|---|---|---|---|---|
| 1 | Bluetooth Stop retires the link without sending Stop when GATT is busy | Critical | Confirmed | Backend | Fixed |
| 2 | Lost-device disconnect races its recovery Stop; Stop again skips detached devices | Critical | Confirmed | Backend | Fixed |
| 3 | Exceptions other than `IOException` skip recovery or crash | Critical | Confirmed | Backend | Fixed |
| 4 | Receipt over Run interrupted; Done ignored while a device is unconfirmed; no scroll; hides notices | High | Confirmed | UI | Fixed |
| 4 | Routine stops create receipts; disconnecting one idle device stops all | High | Confirmed | Backend | Fixed |
| 5 | A stale fence before any write is treated as uncertain and retires the link | High | Confirmed | Backend | Fixed |
| 6 | `Devices.connect` shares the process job and holds the session queue | High | Plausible | Backend | Fixed |
| 7 | `TypedStop` lowercases with the default locale | High | False positive | — | — |
| 8 | A rotated ChatGPT refresh token can be lost on cancellation | High | Confirmed | Backend | Fixed |
| 9 | The TalkBack Stop action reports the footer and gives no haptic | High | Confirmed | UI | Fixed |
| 10 | Devices hides unresolved outcomes; Intiface replies shown as stopped | Medium | Confirmed | UI | Fixed |
| 11 | Targets under 48 dp; clipping at 200% font scale | Medium | Confirmed | UI | Fixed |
| 12 | Sheet Back, Coyote checkbox, receipt stroke bounds, name draft | Medium | Confirmed | UI | Fixed |
| 12 | Forms close before the core accepts them | Medium | Confirmed | UI and contract | Open |
| 13 | No architecture test for `:app`; fakes in `src/main` | Medium | Confirmed | Both | Fixed |
| 14 | `ChatTurns.finish` can mark a committed reply interrupted | Medium | Confirmed | Backend | Fixed |
| 15 | Locale caching, sentence joins, motor-test copy, board drift | Medium | Confirmed | UI | Fixed |
| 16 | A Dispatcher per Cloud connection; SSE without a final blank line | Low | Partly | Backend | Fixed |
| 17 | §5.5 deviations: file and function size, raw dp, previews, swallowed causes, KDoc | Low | Confirmed | Both | Fixed |

Notes on the verdicts:

- **#7 is a false positive.** Kotlin's `String.lowercase()` always uses the invariant locale; only the deprecated `toLowerCase()` used the default locale.
- **#16, second part, is a false positive.** The SSE specification discards any event that isn't ended by a blank line, so a stream cut off after its last `data:` line is truncated rather than complete. The OkHttp part is minor: the extra dispatcher's threads exit after 60 seconds idle.
- **#6 was plausible.** The owner scope shared the process job, so it couldn't be cancelled with the device, and a slow connect held up queued intents; Stop was unaffected, because it never queues. The fix gives each owner its own child job and runs connect outside the queue (§5, item 8).

## 4. Fixed in the UI

- **Stop source and haptic (#9).** `AppFrame` and `StopRow` now pass `StopSource`. The TalkBack action reports `TalkBackAction` and triggers the same haptic as the footer.
- **Receipt (#4).** Tapping Done now hides the panel even when the core keeps the receipt for an unconfirmed device. It reappears when Stop is pressed again or another device turns out unconfirmed (`ReceiptDismissal.kt`). The panel scrolls, stays below the status bar, and lets action notices stack above it instead of hiding them.
- **Unresolved devices (#10).** Devices shows a danger banner for each device whose last Stop is unconfirmed, explaining what the device does on its own. The UI no longer relabels a bridge acknowledgement as stopped, and the sample states model it as unconfirmed.
- **Touch targets and large text (#11).**
  - Compact buttons and menus keep a 48 dp target.
  - Segments span the full height of their well, and the send button has a 48 dp target.
  - The Stop row, tabs and strip grow with the text instead of clipping.
  - `LabelValue` stops a value from squeezing its label mid-word, and `ButtonRow` stacks buttons whose labels won't fit side by side.
  - Checked at 200% on Chat, the receipt, Settings, App settings and Live. Stop stays in view on all of them.
- **Smaller fixes (#12, #15).**
  - Back from a nested sheet goes one page back and names that page.
  - The Coyote checkbox can't lock itself.
  - Receipts show only the stroke end that changed.
  - The name field resets on each edit.
  - Times follow the app's language and the phone's 12- or 24-hour setting.
  - Sentences join through a translatable template, and the receipt footer uses full-sentence strings.
  - App settings has its breadcrumb and status dots.
- **Boundaries and size (#13, #17).**
  - `UiBoundaryTest` (ArchUnit, in `:app`) fails the build if the UI depends on `:transport` or on anything in `:core` outside `core.api`.
  - Previews and sample states moved to the debug source set.
  - `Controls.kt` (713 lines) became five files.
  - The model, sheet, add-device and Live files were split.
  - The largest UI file is now 402 lines.
  - Dead code is gone.

## 5. For the backend

These items went to Astra Max at the start of a second run, before M1. All nine are now fixed and tested; `docs/backend/status.md` ("Review fixes") lists the tests for each. Line numbers refer to the code at the time of this review.

1. **Bluetooth Stop when GATT is busy.** In `HandyBluetoothSession.request` (`transport/.../handy/HandyBluetoothSession.kt`, around line 154), a priority Stop that can't acquire the GATT lock closes the link and returns without sending `StopCurrentMode`. System design §3.2 retires only a *stalled* generation, whereas a normal write holds the lock for at most one second. Wait for the lock within the Stop deadline, then send. Also keep `WriteFailed` and `NoReply` distinct, since every `IOException` currently maps to `NoReply`.
2. **Lost devices and Stop again.**
   - `Devices.disconnect(lost = true)` starts a recovery Stop, then detaches and closes the transport while that Stop may still be writing.
   - `emergencyStop` targets only attached owners, so pressing Stop again never reaches a detached device or a saved cloud device. Include saved cloud devices whose last outcome is unresolved, matching the cold-start rule.
3. **Exceptions other than `IOException`.**
   - `MotionEngine.write` and `sendStop` catch only `IOException`.
   - A `SecurityException` (for example, if Bluetooth permission is revoked mid-run) skips `recover(owner)` and escapes into the process scope, crashing the app.
   - Catch and map these exceptions on the Stop path, and give the scope a handler that records them.
4. **Routine stops create receipts.**
   - Pause, disconnect, motion mode Off, a lead change and the end of a motor test all call `engine.stop(null)`. Each increments `pressCount` and publishes a receipt, causing the UI to show a Stop receipt after every motor test or pause.
   - Only a Stop press (`source != null`) should create or update a receipt.
   - Separately, disconnecting one idle device shouldn't stop the others.
5. **A stale fence before any write.** In `HandyTransport.sendBatch`, `send()` returns false when the fence is already stale, causing the batch to report `Uncertain`, retire a healthy link and trigger a recovery Stop. Return `Stale` when no bytes were sent.
6. **ChatGPT refresh.** `ChatGptAccount.refresh` runs in the caller's coroutine, so cancelling a turn can occur after the token endpoint rotates the refresh token and before `storeTokens` saves it. Run the exchange and store under `NonCancellable`, or in a process-scoped job that the turn only awaits.
7. **A committed reply marked interrupted.** In `ChatTurns.finish`, a Stop between `chats.append` and `active.compareAndSet(turn, null)` marks a committed reply as interrupted and offers to send it again. Clear `active` atomically with the commit.
8. **Connect.** Give each device's owner scope its own child job, and move the slow connect off the session queue (#6).
9. **Clean-up.**
   - Mirror every driver package in the `ArchitectureTest` provider rule.
   - Move `core.testing` and `transport.simulated` out of `src/main`.
   - Share one priority dispatcher for Handy Cloud.
   - Split the 60–90-line functions §5.5 flags.
   - Record redacted causes where an `IOException` is swallowed.
   - Add KDoc to the undocumented `UserIntent` entries and `CoreDependencies` properties.

## 6. Decisions needed

1. **Does an Intiface acknowledgement count as stopped?** System design §2 says no: a bridge's reply alone is unconfirmed. Boards V-StopReceipt and V-ConnectionLost show it as stopped. The UI shows whatever the core reports, and the samples now follow the system design. Recommendation: keep the system design. Intiface replies once it has handed the command over, which doesn't prove the device stopped. **Decided 2026-11-19: keep the system design.**
2. **How is an unconfirmed device resolved?** With rule 1, an Intiface device can never confirm, so its warning on Devices would stay until it reconnects. The contract has no "I checked it" action. Recommendation: add the intent the backend already proposed for confirming a device was checked locally (`docs/frontend/status.md`, Left to do). **Decided 2026-11-19: added as `UserIntent.ReconcileDevice`, with an "I've checked it" action on each unconfirmed device on Devices.**
3. **How long is the motor test?** The VacuGlide board says three seconds; the core stops every motor test after 1.5 seconds. The copy now says "for a moment" until this is decided.
4. **Should forms wait for the core?** Saving a server or an OpenRouter key, adding a cloud device and removing a device currently close the page as soon as they're tapped, so a refused or failed save loses the input. Closing on the resulting state change needs either a result per intent in the contract or the UI watching for the new item.

## 7. Verification

- `./gradlew spotlessCheck check :app:assembleDebug` passes, covering formatting, Android Lint (with warnings treated as errors) and 105 JVM tests (103 backend tests plus 2 UI boundary tests).
- On the Pixel 10 emulator (API 37), each board was compared side by side with the app over the sample states at 100% and, for the main screens, at 200% font scale. Checked screens:
  - Chat, Live and the deck;
  - the Stop receipt and its dismissal, and Devices with unresolved devices;
  - Connection lost and Run interrupted;
  - Before you start;
  - the settings sheet, Motion mode, Autopilot and App settings;
  - the VacuGlide page, Coyote setup and Add server;
  - Lowlight.
- Not checked: TalkBack at runtime (only its semantics were reviewed) and real devices, which no test or tool may use.
