# Voice input sketch review

- **Date:** 2026-11-25
- **Scope:**
  - the voice input sketch: [docs/voice-design.md](../voice-design.md), plan §20, and the five V-Voice boards;
  - the voice test code the sketch would reuse.
- **Reviewers:**
  - Gemini 4 Neon at `xhigh` reasoning in the Antigravity CLI, read-only in a disposable copy of the repo;
  - Claude: triage, checking each finding against the code, and the revisions.
- **Status:**
  - the sketch and its boards are revised;
  - the five findings that are bugs in the voice test's code were fixed the same day, with eight new tests (§3);
  - five decisions remain open ([design note §9](../voice-design.md#9-open-decisions)).

## 1. Summary

Gemini reported 17 findings, 9 high, 5 medium and 3 low, and 7 simplifications. After checking each against the code, plan and boards:

- **15 are confirmed,** and 2 are confirmed in part:
  - **Finding 11:** its "bypasses `HttpsOnly`" is overstated. `HttpURLConnection` won't follow a redirect from HTTPS to plain HTTP, but moving to the app's `OkHttpClient` is still the simpler design.
  - **Finding 14:** its 16 KB page-alignment worry is already met. Both native libraries load at 16 KB alignment.
- **None are false positives.**
- **One correction:** Parakeet int8 takes about 0.8 GB once loaded, measured on the emulator, not the 1.3 GB the review took from the plan. The conclusion stands.
- **Every simplification is adopted.**
- **Side finding:** the older V-StopReceipt board shows an Intiface Central reply as "Stopped", which the app has reported as "Stop unconfirmed" since decision 1 (plan §12). The new V-VoiceStop board follows the app.

## 2. Findings

| # | Severity | Finding | Verdict | What changed |
|---|---|---|---|---|
| 1 | High | `AutoLevel` keeps its speech level forever, so after close speech the gain can sit at 0 dB while you speak from across the room | Confirmed: the speech level updates only on windows judged speech | A test-code bug (§3) |
| 2 | High | Parakeet and onnxruntime in the main process break plan §9.2's rule for native runtimes | Confirmed; memory corrected to about 0.8 GB | Voice moves to its own `:voice` process (design note §7), and the choice is decision 1 |
| 3 | High | TalkBack reads replies aloud through the speaker, and the microphone would send them back as the user's words | Confirmed: `ReplyAnnouncer` uses a polite live region | The call path's echo cancellation while TalkBack speaks; the voice bar is no longer a live region |
| 4 | High | A turn heard before a Stop is stamped with the new epoch when dispatched, and could restart a device | Confirmed: `dispatch` stamps the epoch when the turn is sent, and `AppState` has no epoch | `AppState.stopEpoch` fences turns (§6) |
| 5 | High | A short "Stop!" can be dropped as a click, and Stop can wait behind a long turn or a queue | Plausible for the first; confirmed for the others | Short spans are checked for Stop, the tail of a long turn is checked, and Stop checks go first (§6) |
| 6 | High | The clause rule false-stops on "Don't. Stop." and on ambiguous words, and misses common spoken Stops; Settings implied 25 languages | Confirmed | A voice-specific `isSpokenStop` with negation, clear and ambiguous phrases; Stop languages stated (§6, V-VoiceSettings) |
| 7 | High | Adding `microphone` to `CoreService` can fail after an async download or load, and a refused start tears down a device run; background replies are cancelled | Confirmed: `CoreService` treats a refused start as the service lost, and `appBackgrounded()` interrupts chat when no device needs the engine | A separate `VoiceService`, started on the tap; `setVoiceActive` keeps replies going (§7) |
| 8 | High | `MicCapture` loops on read errors, misses start failures and silencing, and may release the detector while the thread is still in it | Confirmed | A test-code bug (§3); the app's capture reports errors and silencing (§7) |
| 9 | High | V-VoiceStop showed a bridge reply as confirmed, the word "Stop" as a message, and the voice bar outside the receipt's dimming | Confirmed | Board redrawn: honest outcomes, no message, listening ends |
| 10 | Medium | Listening after a Stop lets room speech restart devices, and nothing ends a forgotten session | Accepted | Any Stop ends listening, and so do 15 quiet minutes with nothing playing |
| 11 | Medium | The model download can get stuck on a finished but unrenamed file (HTTP 416), has no space check, and checks only file sizes | Confirmed in part | A test-code bug (§3); the app's download checks space and uses `OkHttpClient` (§8) |
| 12 | Medium | The noise floor drops to any single quiet window, so the gain pumps with a stroker running; clip levelling changes its own fixed gain | Confirmed | A test-code bug (§3); clips aren't carried over |
| 13 | Medium | Missing states: download and load, permission, other screens, switching chats, an unreachable model, Bluetooth, Chinese and Japanese, check-ins | Confirmed | A state table and rules (§4), Bluetooth and languages (§5, §7) |
| 14 | Medium | `core.api` couldn't drive the Stop board's text; release packaging, R8 and APK size were unstated | Confirmed in part: 16 KB alignment is already met | Standard receipt; packaging and the APK decision (§8) |
| 15 | Low | Turn timestamps count samples from just after recording starts, so they drift over long sessions | Confirmed | Moot once the receipt shows no recognition time; the test page's latency figure is slightly off |
| 16 | Low | Large text, reduced motion, "End to stop" wording, and removing the model without confirmation | Confirmed | Boards and §4–§5 updated |
| 17 | Low | Parakeet decodes empty audio, releases aren't guarded, and the thread count is fixed at 4 | Confirmed | A test-code bug (§3) |

## 3. Bugs in the voice test's code

All five were fixed the same day in the voice test's code, which the app would reuse:

1. **Gain (finding 1):** `AutoLevel` forgets the speech level after 10 s without speech, so the gain returns to what the noise floor allows. Tested with close speech followed by distant speech.
2. **Recording (finding 8):** `MicCapture`:
   - starts recording before reporting success;
   - ends with a reason on a read error, or when Android silences the recording (`AudioRecordingCallback`);
   - stops the recorder to unblock a waiting read;
   - calls back on its own thread once it has stopped. The voice test flushes and releases the detector there, so nothing frees it while in use.
3. **Download (finding 11):**
   - a part that's already whole is checked and renamed without a request;
   - a refused range (HTTP 416) starts the file over;
   - a download needs room for itself plus 500 MB;
   - a failure on a nearly full disk deletes the part.

   Each has a test.
4. **Noise floor (finding 12):**
   - the floor falls at no more than 10 dB a second;
   - it never rises above the current level;
   - the first 0.2 s of sound is ignored;
   - the limiter no longer changes a clip's fixed gain.

   Each has a test.
5. **Parakeet (finding 17):** empty audio returns no text without decoding, and double release is guarded. The thread count is half the cores, from two to four.

## 4. Simplifications adopted

1. **No microphone check in Settings.** Chat's meter shows you're heard.
2. **No "How voice works" card.** Two lines of help replace it.
3. **No recognition time on the Stop receipt.** The standard receipt is used unchanged.
4. **Any Stop ends listening.**
5. **Silero in the APK**, instead of a second download.
6. **The app's `OkHttpClient` and `HttpsOnly`** for the model download.
7. **Only the pipeline comes over from the voice test.** ML Kit, clips, scoring, the report, the vitals and the page stay behind ([design note §8](../voice-design.md#8-architecture)).
