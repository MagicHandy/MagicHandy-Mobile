# Voice model choice and hidden settings review

- **Date:** 2026-11-25
- **Scope:**
  - the fixes to the five bugs the [first review](2026-11-25-voice-sketch-review.md) found in the voice test's code;
  - the choice between a compact and a full-precision model ([design note §5.1](../voice-design.md#51-choosing-the-model), boards V-VoiceModel*);
  - the hidden settings (§5.2, board V-VoiceAdvanced);
  - whether the documents agree with each other.
- **Reviewers:**
  - Gemini 4 Neon at `xhigh` reasoning in the Antigravity CLI, read-only in a disposable copy of the repo;
  - Claude: triage, checking each finding against the code, and the fixes.
- **Status:** every finding is fixed in the code or reflected in the design, with eight more tests. Three of the five simplifications are adopted.

## 1. Summary

Gemini reported 12 findings, 5 high, 6 medium and 1 low, and 5 simplifications. After checking each against the code and documents:

- **All 12 are confirmed, one in part:** finding 3's `stop()` against `release()` race can't corrupt memory, since Android's `AudioRecord` locks its native side, but a lock now makes the order plain.
- **Three high findings were races in the first round's fixes:** stopping while the recorder started, a clip ending at once, and the recording's threading.
- **Finding 6:** one gain problem was a regression from the first round's limit on how fast the noise floor falls.
- **Finding 8:** the first triage said each download and noise-floor fix had a test, which wasn't yet true for two of them. Those tests now exist.
- **Two simplifications are not adopted** (§4): the owner asked to keep every planned voice setting integrated. Those settings stay, in test builds only, outside `core.api`, and behind the Stop rules.

## 2. Findings

| # | Severity | Finding | Verdict | What changed |
|---|---|---|---|---|
| 1 | High | Listening showed before the recorder started. Stopping in that moment left the recording and its service running, and a recorder that failed at once could restart the service after teardown. Two teardowns could also race. | Confirmed | The session is claimed with compare-and-set. The service starts before recording, and a recorder that starts after Stop listening is stopped at once. Teardown runs off the main thread and leaves a newer session's service alone. |
| 2 | High | A clip whose recorder failed at once could leave the page stuck on Recording | Confirmed | The clip is claimed with compare-and-set, and Recording shows before the recorder starts. One `finish` ends a clip once, whichever of Finish, the time limit or a lost microphone comes first. |
| 3 | High | `MicCapture`'s threading had several problems: a call into the audio service on the main thread; racy end states; missed silencing when a call was already running; a crash reported as a normal stop; and `stop()` waiting on its own thread | Confirmed, one part in part | One atomic end state. The silencing callback is registered before recording starts and only notes the reason. A recording that starts silenced ends at once. A crash in what the audio feeds ends the recording as failed. A lock guards stopping and releasing, `stop()` never waits on its own thread, and a slow stop is logged. |
| 4 | High | Full precision needed gates: spoken-Stop latency, recovery from running out of memory, and memory thresholds that allow for what Android reserves | Accepted | Full precision ships only after the Pixel shows better recognition, a spoken Stop within about 1 s, and 30 minutes without throttling. A failure offers **Switch to Compact**. The gates are 5 and 10.5 GiB as Android reports it. |
| 5 | High | Hidden settings could slow or weaken a spoken Stop | Accepted | The Stop checks keep their own values: they start at confidence 0.5 or lower, and run from the start of speech through the pause, whatever the silence setting. TalkBack's call path can't be overridden, and the Advanced section warns. A release only offers choices that keep Stop as quick and sure. |
| 6 | Medium | A first window of speech or a tap could pin the noise floor high, and digital silence stopped the speech level being forgotten | Confirmed | The floor comes only from windows without speech, and drops at once while the gain settles. Zeros count as time without speech. Each has a test. |
| 7 | Medium | Downloads had four problems: an oversized part skipped the space check; the space message could read "needs 500 MB, has 500 MB"; a faulty server could fill the disk; and the rename wasn't portable | Confirmed | An oversized part counts the whole file, and the need rounds up while the room rounds down. A body longer than its file is cut off and deleted. The file is moved into place with `Files.move`. Each has a test. |
| 8 | Medium | The detector could be used after release; two claimed tests were missing, and some assertions were weak | Confirmed | `accept` checks for release. New tests cover a part kept when there's room, a part deleted when the disk is nearly full, and the noise floor's rise. The space message is asserted exactly. The distant-voice test now shows the gain moving, and a short pause keeping the speech level. A KDoc line was corrected. |
| 9 | Medium | Putting the hidden settings in `core.api` and the saved settings breaks the additive contract and the one settings store, and the "one-line change" was unrealistic | Confirmed | They move to `VoiceConfig`, internal to `:app`, with test-build choices kept apart. `core.api` gains the chosen model only if full precision ships. The design note lists what bringing one back really takes. |
| 10 | Medium | Model-choice states were missing | Confirmed | The design note now covers: the picker's Settings form (V-VoiceModelSwitch); a plain row when there's no choice; and switch progress with Cancel and Retry. The old model is removed when the new one is ready, with a start-up clean-up. Cancel deletes the part. Remove ends listening and deletes everything. The space figures are computed per model. |
| 11 | Medium | The documents and boards disagreed in places | Confirmed | Fixed: plan §19's memory figure, plan §20, design note §3, §4, §5.2, §7 and §8, the setup boards' permission note, the Settings boards' help and Remove rows, and the TalkBack wording. |
| 12 | Low | Some board controls were under 48 dp, and the Test builds tag used a non-theme colour | Confirmed | The link and Cancel are 48 dp, and the tag uses the theme's warn colour. Scrolling sheets and progress semantics are stated in §5.1. The 46 px setting rows match the existing App settings board; the app's rows use the 48 dp touch size. |

## 3. The model choice

Gemini recommended option B, with option D's progress row in Settings for switching, and a Compact-only release until the Pixel shows full precision is worth it.

All three are adopted as the recommendation:

- Compact alone for now;
- then B, with the picker's Settings form, newly drawn as V-VoiceModelSwitch.

## 4. Simplifications

| # | Simplification | Verdict |
|---|---|---|
| 1 | Drop **Check the microphone** | Not adopted: the owner asked to keep every planned voice setting integrated. It stays in test builds only, disabled while listening, and never sends anything or stops a device. Releases still have no microphone check, as the first review recommended. |
| 2 | Drop the TEN detector | Not adopted, for the same reason. It stays in test builds only, downloading its 332 KB model when chosen. |
| 3 | Keep **Show levels while listening** out of `core.api` and releases | Adopted: it's in test builds only, through `VoiceConfig`. |
| 4 | An internal `VoiceConfig` instead of hidden fields in `core.api` | Adopted |
| 5 | Option B for first use, with option D's progress row for switching in Settings | Adopted, after a Compact-only start |
