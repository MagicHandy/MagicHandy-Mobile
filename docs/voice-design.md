# Voice input: design

**Status:** built on 2026-11-25 and released in 0.1.8, with the model choice of option B (§5.1). §9 lists the decisions and §11 what the build does differently from this sketch.

- **What it covers:** how voice input fits Chat and Settings.
- **Evidence:** the [voice test](voice-test.md) (plan §19). On the owner's Pixel 10 Pro XL, its second build heard speech far more reliably than the first.
- **Review:** revised the same day after a Gemini 4 Neon review ([triage](reviews/2026-11-25-voice-sketch-review.md)).
- **Boards:** in two rows of the canvas's v1 page:
  - "Voice input": V-VoiceIdle, V-VoiceSetup, V-VoiceListen, V-VoiceStop and V-VoiceSettings;
  - "Voice input · choosing the model and hidden settings": V-VoiceModelA, V-VoiceModelPick, V-VoiceModelB, V-VoiceModelC, V-VoiceModelSwitch, V-VoiceModelD and V-VoiceAdvanced.
- **Second review:** the model choice and hidden settings were reviewed again the same day ([triage](reviews/2026-11-25-voice-model-and-settings-review.md)).

## 1. What it does

- **Speech to text, into chat.** MagicHandy doesn't speak back. TalkBack can, though, which §7 covers.
- **One mode, hands-free:**
  - tap the microphone in Chat to start, and End when you're done;
  - each thing you say is sent when you pause, like a typed message;
  - listening carries on with the screen off.
- **A spoken "Stop" stops every device at once,** as the Stop button does.
- **Any Stop, spoken or not, also ends listening.** After 15 minutes without speech, listening ends if motion is idle or paused. Playing and edging reset the timer.
- **On the phone:** Parakeet v3 recognizes the speech. Audio never leaves the phone and is never saved.

## 2. What the voice test settled

- **The pipeline that worked on the Pixel:**
  - Standard audio (`MIC`) with the page's own automatic gain;
  - Silero, starting speech at 0.5 and ending a turn after 0.6 s of silence;
  - 0.3 s of padding before each turn and 0.2 s after it.
- **ML Kit can't do hands-free.** Google allows it only while the app is on screen, so it stops when the screen does.
- **One Parakeet build is enough.** The emulator's probe found int8 and 4-bit transcribing alike, at every level and in both simulated rooms. Loaded, int8 takes about 0.8 GB of memory.
- **Still to measure on the phone, before building (§10):**
  - how long a spoken Stop takes, and whether a short, sharp "Stop!" is caught;
  - battery over 30 minutes with the screen off;
  - false turns and recognition with a device running nearby;
  - the call path's recognition, for TalkBack users.

## 3. Simplifications

| Voice test | App | Why |
|---|---|---|
| Five engines | A compact Parakeet v3 build; full precision only once it earns its place (§5.1) | ML Kit stops with the screen and is a closed alpha. int8 and 4-bit transcribed alike, so one compact build is enough. |
| Silero or TEN, downloaded | Silero, downloaded with the speech model (644 KB); TEN is a hidden setting (§5.2) | One small model, and it worked. It comes in the same checked download as Parakeet, so the repository and the APK hold no model files. |
| Four audio paths | Standard, or the call path while TalkBack is on (§7); the others are hidden settings | Standard worked; the others are for diagnosis |
| A gain switch, and menus for where speech starts and the silence that ends a turn | Hidden settings with the tested defaults: gain on, 0.5 and 0.6 s | The defaults worked. The desktop's sensitivity, delay, noise suppression and input menus aren't needed in a release. |
| Hands-free and One clip | Hands-free only | Hold-to-talk, which the desktop also has, would add a second gesture and its states |
| The dBFS, noise, gain and confidence readout | The meter's bars; the readout is a hidden setting | People need to know they're heard, not the numbers |
| A microphone check and an explainer | Neither in a release: Chat's meter, and two lines of help in Settings. The check is a hidden setting. | The check is a second recording pipeline, so it can't run during a live session |
| Recognition time on the Stop receipt | The standard receipt | A measurement, not something to read during a Stop |
| Listening after a Stop | Any Stop ends listening | Nothing heard after a Stop can restart motion (§6) |
| Distance and noise tags, the script, scoring, the report and vitals | None | They exist to measure |
| An ML Kit language menu | None | Parakeet v3 recognizes 25 European languages by itself |
| A "spoken" label on messages | Ordinary messages | No new message type or database column |
| 3,779 lines | About 900 (an estimate) | Capture, gain, segmenter, recognizer, model download, the voice service and its link. No ML Kit pairing, clips, scoring or report. |

## 4. Chat

**The composer** (V-VoiceIdle): with nothing typed, the button beside the field is the microphone. Typing turns it into Send.

**The first tap** (V-VoiceSetup) opens a sheet that covers:

- what voice does, and how to end it;
- that the voice stays on the phone;
- that "Stop" works and ends listening, though the button is faster;
- the 670 MB download.

Its buttons are **Download and start** and **Not now**, and it notes that Android asks for the microphone the first time. If full precision ships, the sheet also gets **Choose another model** (§5.1).

**Listening** (V-VoiceListen):

- the field becomes the voice bar, with **Listening** or **Hearing you**, the meter, and **End**;
- the deck above stays as it is, and so does Stop.

**The voice bar's states:**

| State | The voice bar says | Next step |
|---|---|---|
| Downloading | Downloading, with progress and Cancel. The download pauses when you leave the app and resumes when you return. | — |
| Download failed | The cause: no connection, not enough space (it names the space needed: the model plus 500 MB, so 1.2 GB for Compact and 3.1 GB for full precision), or a damaged file | Retry |
| Getting ready | Getting ready… (2–5 s while the model loads; a spoken Stop isn't heard yet) | The Stop button works throughout |
| Listening or hearing you | The meter | End |
| Waiting | A turn waits while a reply is still streaming, then goes when it ends. Several waiting turns join into one message. | — |
| Can't send | The cause, such as the chat model being unreachable. A spoken Stop still works. | The usual fix, such as Choose an AI |
| Ended | A notice with the reason: you ended it, a Stop, 15 quiet minutes, another app took the microphone, microphone access was removed, or the speech model failed | Start again, Open settings, or Remove and download again. If full precision failed to load or ran out of memory, the step is **Switch to Compact**. |

**Other rules:**

- **Other screens:** while listening, every screen but Chat shows a **Listening · End** chip above the tabs, or above Stop where there are no tabs (V-VoiceSettings).
- **Which chat:** a turn goes to the chat that's open when you stop speaking. Opening another chat, deleting all chats, or an interrupted reply discards any turn not yet sent.
- **Listening never restarts by itself,** not even after a download finishes in the background.
- **No chat model:** the microphone is disabled like Send, and Choose an AI stays above the composer.
- **Check-ins:** voice doesn't answer VacuGlide's check-ins. The check-in works as it does now.
- **Accessibility:**
  - the voice bar isn't a live region; TalkBack announces only start, end and errors;
  - with large text, the meter gives way to the words;
  - with reduced motion, a still dot replaces the meter.

## 5. Settings

- **App settings** gains one row under Models: Voice, reading **Ready** or **Not set up**.
- **The Voice page** (V-VoiceSettings) has:
  - **Speech model:** the model in use. It opens the picker when there's a choice (§5.1), and is a plain row when there isn't;
  - **Remove from this phone,** which asks for a second tap like Delete all chats;
  - microphone access;
  - two lines on when listening ends and which languages Stop works in.
- **Remove:**
  - it ends listening and cancels any download;
  - it deletes every model and partial file;
  - voice returns to Not set up, with Compact as the model.

  Its second tap says listening will end.
- **Chinese and Japanese:** when the app's language is one of these, voice isn't offered, but Remove stays.
- **The compact settings sheet** doesn't change.

### 5.1 Choosing the model

**The two models:**

- **Compact:** whichever of int8 and 4-bit the Pixel's results favour. It's about a 670 MB download and takes about 0.8 GB of memory, and gives the quickest replies.
- **Full precision:** Parakeet v3 as published. It's a 2.5 GB download, takes about 3.6 GB of memory, and is slower.

**The memory gates:** Android reports a little less memory than a phone is sold with, so they're set below the nominal size.

- **Voice at all:** needs at least 5 GiB reported, which covers 6 GB phones.
- **Full precision:** needs at least 10.5 GiB reported, which covers 12 GB phones.

**Full precision has to earn its place.** It ships only if the Pixel shows all of these:

- clearly better recognition than Compact;
- a spoken Stop within about 1 s (§6);
- 30 minutes without throttling.

Until then it stays in test builds, and release builds offer Compact alone.

**The options** (boards V-VoiceModelA to V-VoiceModelD):

| Option | First use | Taps before listening | For | Against |
|---|---|---|---|---|
| **0. Compact only** | The first sheet as it is (V-VoiceSetup) | 2 | Nothing to choose, and no picker, memory gate or switch-over to build | No full precision in releases |
| **A. Choose a model** | The sheet's button is **Choose a model**, and the picker follows (V-VoiceModelA, then V-VoiceModelPick) | 3 | Everyone chooses knowingly | A choice most people can't make well before trying voice, and an extra step |
| **B. Recommended first** | **Download and start** gets Compact. **Choose another model** opens the picker, and the link is hidden below the memory gate. (V-VoiceModelB) | 2 | One decision for most people; the full model is a link away | The full model is easy to miss |
| **C. Both in the sheet** | Two cards in the first sheet, with Compact chosen (V-VoiceModelC) | 2 | One step, with the choice in view | A crowded sheet, worse with large text |
| **D. Compact now, full later** | Like B without the link. Settings › Voice offers the full model as an upgrade. (V-VoiceModelD) | 2 | The quickest start, with an upgrade once voice has proved useful | Someone who wants full precision downloads Compact first: 3.2 GB in all |

**Whichever option is chosen:**

- **From Settings,** Speech model opens the picker in its Settings form (V-VoiceModelSwitch):
  - the model in use is marked **In use**;
  - the button names the download, such as **Download 2.5 GB**;
  - the note gives the space it needs.
- **A switch** downloads the new model first, shown in Settings › Voice with its progress and **Cancel**, or **Retry** after a failure (V-VoiceModelD). Voice keeps the old model meanwhile.
- **The old model** is removed as soon as the new one is ready, or when listening ends if it's in use. At start-up, any model that isn't the chosen one is removed, in case the app stopped mid-switch.
- **Cancel** deletes the partial file. A failure, or leaving the app, keeps it to resume.
- **Every download** is checked, and needs room for itself plus 500 MB. When there isn't room for full precision, the message says that removing Compact first would make room.
- **Accessibility:**
  - the sheets scroll with large text, and Stop stays put;
  - the download's progress is announced as a progress bar, not as a live region;
  - every control is at least 48 dp.

**Recommendation: 0 now, then B.** Release Compact alone until the Pixel shows full precision is worth it. If it is, offer it as in B, with D's progress row in Settings for switching later. The first tap stays one decision, download or not, and the full model is one link away for whoever wants it.

**Decided: B, now** (the owner, 2026-11-25). Phones that report 10.5 GiB or more see **Choose another model**, and Settings › Voice switches models with D's progress row. Full precision still has the measurements above to pass on the Pixel (§10).

### 5.2 Hidden settings

**Every setting the voice test tunes stays in the app, fully wired, with the tested values as defaults.**

- Release builds hide them, and always use the defaults.
- Debug and phone-test builds show them in an **Advanced** section on the Voice page (V-VoiceAdvanced). The section warns that they can make a spoken Stop slower or less reliable.

| Setting | Default | Choices |
|---|---|---|
| Audio | Standard | Standard, Speech, Echo cancel, Raw. While TalkBack is on, the call path takes over regardless (§7). |
| Raise quiet speech | On | On, Off |
| Speech detector | Silero | Silero, or TEN, which downloads 332 KB when chosen |
| Speech starts at | 0.5 | 0.3, 0.4, 0.5, 0.6 |
| Silence that ends a turn | 0.6 s | 0.4, 0.6, 0.8, 1.0, 1.5 s |
| Show levels while listening | Off | The voice test's readout under the voice bar: the microphone's level, the noise floor, the gain and the detector's confidence |
| Check the microphone | — | Shows the phrase it heard, with its level. Disabled while listening, since it records too, and it never sends anything or stops devices. |

**Where they live:**

- **`VoiceConfig`,** an internal type in `:app`'s `voice` package, holds them, and the release defaults are in code.
- **No new `core.api` fields or saved settings** for them, so the one settings store (plan §5.4) and the additive contract don't grow for things releases don't use.
- **In test builds,** the Advanced section's choices are kept apart from the app's saved settings, as the voice test keeps its own.
- **The voice test page** builds its sessions from the same `VoiceConfig` and pipeline, so it keeps measuring the code the app ships.

**Bringing one back to a release:**

- move it from `VoiceConfig` into the saved settings, as an additive `core.api` field;
- give its row translations, large-text layout and TalkBack labels;
- check it against the Stop rules below.

The setting itself, its wiring and its row already exist.

**The Stop rules don't bend to these settings** (§6):

- the Stop checks keep their own fixed values;
- TalkBack's call path can't be overridden;
- a release only ever offers a choice that keeps a spoken Stop at least as quick and as sure as the defaults.

**What stays fixed in code:**

- the padding (0.3 s before and 0.2 s after);
- the gain's target and limits;
- the 20-second turn limit and the 15-minute timeout;
- the Stop rules and their own values.

## 6. Stop and safety

**A spoken Stop is a Stop surface.**

- The voice service sends it over its link to the main process, which calls `MagicHandyCore.emergencyStop(StopSource.SpokenStop)` at once, before chat sees the words.
- It never goes through `dispatch`, the chat queue or UI state (`AGENTS.md`).
- The words never become a chat message, as with a typed Stop (V-VoiceStop).

**Any Stop ends listening.** Every Stop surface counts, including the footer, the notification, the tile and a check-in timing out. After a Stop, nothing heard in the room can reach chat and restart a device.

**The Stop fence:**

- Every Stop advances the motion engine's epoch synchronously. The UI observes the same value later in `AppState.stopEpoch`.
- An explicit start captures `MagicHandyCore.currentStopEpoch`. A transcript carries that value and its chat ID into the core. The core checks them at admission and checks the epoch again when saving the message and admitting motion.
- Stop, End, cancellation or leaving the app revokes a pending microphone start. Retrying a download preserves the original microphone choice.

**Hearing Stop quickly.** The Stop checks have their own fixed values, which the hidden settings (§5.2) don't change:

- **Short sounds:** spans of 0.1–0.25 s are checked for Stop only, and are never sent to chat, so a short, sharp "Stop!" isn't thrown away as a click.
- **Starting the checks:** they begin as soon as the detector's confidence reaches 0.5, or the turn's own threshold if that's lower. A stricter "Speech starts at" can't hide a quiet Stop.
- **During a turn:** from the start of speech, and through the pause before a turn ends, the last 1.5 s is checked for Stop every 0.4 s, keeping only the latest check. So Stop doesn't wait for the chosen silence, or for a long turn to finish.
- The worker alternates short-sound and recent-audio Stop checks. When a chat turn is waiting, it gets a decode slice after at most two checks. Ordinary sentences up to six seconds are recognized whole. Longer turns yield between slices of at most six seconds of audio. The split favors a quiet interval between words and preserves enough context for the final slice. The complete transcript is delivered once, in order.
- The queue holds up to 45 seconds of waiting turns, eight waiting turns, eight short sounds and one latest tail, in addition to the current turn. If the worker cannot keep up, listening ends with a visible notice. The user can start a new session.
- **Target:** Stop within about 1 s of the word, measured in the voice test, with the compact model and with full precision.

**What counts as Stop: `isSpokenStop(text)`.** It's in `core.api`, with its own list rather than the typed one.

- **Clear phrases** count as a clause anywhere in a transcript, when repeated, and after fillers such as "okay", "no", "wait", "hey" and "please". Examples: "stop", "stop it", "stop now", "make it stop", "turn it off", "emergency stop", and their Spanish and Portuguese equivalents.
- **Ambiguous single words** ("pause", "end", "enough", "para") count only when said alone.
- **A negation** ("don't", "do not", "never") cancels the Stop that follows it, even across the punctuation Parakeet adds: "Don't. Stop." is not a Stop.
- **Languages:** spoken Stop works in English, Spanish and Portuguese. Parakeet transcribes 22 more European languages, but doesn't recognize Stop in them, and Settings says so.
- **Why not the typed rule:** it needs the whole message to be one phrase, so a typed "Stop, stop, stop." doesn't stop. People repeat Stop when they say it.
- **Tests:** the voice test's script adds the hard cases: "Don't… stop", "Enough, go faster" and "Stop it".

**Voice never moves a device by itself.** What you say reaches chat as text and goes through the same checks, limits and Autopilot rules as typed text.

**Mistakes:** a false Stop only stops. A misheard request meets chat's usual limits. The voice test's 10-minute run with a device running counts false turns.

## 7. Platform, lifecycle and privacy

**Its own process.** Plan §9.2 puts native inference runtimes in a separate process behind a bound service.

- A `:voice` process runs capture, Silero and Parakeet, so running out of memory or a native crash ends voice with a notice and can't take down the process that owns motion and Stop.
- The voice process binds to the main process, which keeps the main process as important as the voice service while listening.

**Foreground service:**

- `VoiceService` runs in the voice process as a `microphone` foreground service.
- It starts synchronously on the tap, while the app is visible: before the model loads, and before recording.
- `CoreService` doesn't change, so a refused microphone start can never stop a device run.
- **Its notification** says it's listening and offers **End**. The public lock-screen version says only "MagicHandy · Active". Tapping it opens Chat.

**Chat in the background:** while voice is on, the core keeps replies streaming when the app goes to the background, instead of interrupting them (`MagicHandyCore.setVoiceActive`, additive).

**Losing the microphone:**

- **Silenced:** `AudioRecordingCallback` notices when Android silences the recording, for a call, another app, or the privacy toggle. The session ends with a notice.
- **Read errors** end the session too, rather than retrying in a loop.

Recording requests the phone's built-in microphone. Audio reaches speech detection only after Android reports that input as the actual route. Startup allows up to one second for the route to resolve and discards those samples. A later route loss or a switch to an external input ends listening with a notice. This applies with TalkBack and with a wired or paired headset.

**TalkBack:** TalkBack reads replies aloud, and the microphone would hear them and send them back as your words.

- So while TalkBack is on, voice records the call path, whose echo cancellation removes the phone's own speech. The source is chosen when listening starts, and no setting overrides it.
- The call path must stay on the phone's own microphone, never a Bluetooth headset's.
- The voice test's Echo cancel option measures how well recognition holds up on it.

**Memory:**

- the voice process releases Parakeet when listening ends and when Android asks for memory;
- voice is offered only on phones that report at least 5 GiB of memory, which covers 6 GB phones (§5.1).

**Privacy:**

- audio lives only in memory, in a 30-second ring, and is never written or sent;
- diagnostics never hold audio or transcripts;
- a transcript becomes an ordinary chat message, stored and sent like a typed one.

**Battery:** Silero judges about 30 windows a second on one core, and Parakeet runs per turn and for the Stop checks. The voice test's 30-minute screen-off run gives the cost.

## 8. Architecture

**`:app` gains a `voice` package,** in two parts.

The voice process holds:

- `VoiceService`;
- `MicCapture`, which records at 16 kHz, verifies the input route and reports read errors and silencing;
- `AutoLevel`, `Endpointer`, `AudioHistory` and `SpeechSegmenter`, which are pure Kotlin with JVM tests, all set from `VoiceConfig` (§5.2);
- `Transcriber`, which runs Parakeet through sherpa-onnx, and `VoiceDecoder`, which processes the bounded queue;
- `SpeechModel`, the pinned, checked and resumable download. It uses the app's `OkHttpClient` with `HttpsOnly`, checks free space first, and checks the files again when loading fails.

The voice process sends events to `VoiceLinkService` in the main process. The service and controller:

- calls `emergencyStop` for a spoken Stop;
- check the listening session's original epoch against the motion engine;
- dispatch a transcript with `UserIntent.SendVoiceMessage` and the original epoch and destination;
- publish voice state through `MagicHandyCore.voice` in `core.api`.

**`core.api`, additively:**

- `StopSource.SpokenStop`;
- `isSpokenStop(text)`;
- `AppState.stopEpoch`;
- `setVoiceActive(Boolean)`;
- the chosen speech model in `AppState`, with the intent that changes it, but only if full precision ships (§5.1).

`core.api` owns the UI-facing `VoiceControls`, `VoiceState`, and `VoiceConfig` types. Android owns service lifetime, model-file specifications, downloads and preference storage. The selected configuration crosses the process boundary when listening starts.

**From the voice test:**

- **Kept:** the capture, gain, endpointer, history and segmenter code, the model store and Parakeet's loader, after the fixes in §10.
- **Dropped:**
  - ML Kit (`KitEngine`, `KitStream` and the pairing of its text with turns);
  - clips, `levelClip` and `WordScore`;
  - the script, the report, the vitals, and the page, activity and service;
  - its `SharedPreferences` settings, which plan §5.4 rules out for the app. Its audio, gain, detector and timing choices become `VoiceConfig` and the hidden settings (§5.2) instead.

**The release build:**

- **Native code:** sherpa-onnx becomes a release dependency, for `arm64-v8a` only. Its C and C++ API libraries are left out, since the JNI library needs only onnxruntime.
- **R8:** the release R8 rules gain `-keep class com.k2fsa.sherpa.onnx.** { *; }`.
- **16 KB pages:** both native libraries already use 16 KB page alignment, which Android 15 phones need.
- **Manifest:** it gains `RECORD_AUDIO` and `FOREGROUND_SERVICE_MICROPHONE`.
- **Model:** it stays a download, not part of the APK.
- **APK size:** the native code is 27.6 MB. The v1 budget, under 20 MB, assumed no native code, so this needs a decision:
  - stored uncompressed, the default, the APK grows to about 33 MB;
  - compressed (legacy packaging), it grows to about 15 MB, and the libraries are unpacked at install.

## 9. Decisions

Decided on 2026-11-25, when the owner asked for option B and a release:

1. **The process:** a separate `:voice` process, as plan §9.2 requires for native runtimes.
2. **The APK:** native libraries compressed (legacy packaging), for `arm64-v8a` only.
3. **The compact build:** int8, the sherpa-onnx maintainer's export. The emulator's probe found int8 and 4-bit alike, and int8 is the official build.
4. **Choosing the model:** option B (§5.1).
5. **When:** in 0.1.8.

## 10. Before building

The bugs the reviews found in the voice test's code are fixed, with tests ([first triage §3](reviews/2026-11-25-voice-sketch-review.md#3-bugs-in-the-voice-tests-code), [second triage §2](reviews/2026-11-25-voice-model-and-settings-review.md#2-findings)):

- **Gain:**
  - it forgets close speech after 10 quiet seconds;
  - it takes its noise floor only from windows without speech;
  - it doesn't let a tap or a first word hold the gain down.
- **Recording:**
  - it ends on a read error, a crash in what it feeds, or Android silencing it, even when the recording starts silenced;
  - stopping while it starts no longer leaves it running;
  - it frees the detector only once recording has stopped.
- **The model download:**
  - it no longer gets stuck;
  - it checks for space first, counting what it really needs;
  - it stops a server that sends too much.
- **The noise floor:** it falls at a limited rate once settled, and never rises above the room's level.

Still to measure in the voice test:

- spoken-Stop latency, which needs the tail checks built there first;
- short "Stop!" detection;
- the call path with TalkBack;
- 30 minutes of battery;
- false turns with a device running;
- full precision against the compact build, for recognition, spoken-Stop latency and 30 minutes of heat, which decides option 4 in §9.

## 11. As built

What the build does that this sketch didn't spell out, or does differently:

- **Silero** comes with the speech model's download rather than in the APK (§3).
- **The chosen model** is a saved setting: `AppSettingsState.voiceModel` and `UserIntent.SetVoiceModel` in `core.api`, both additive. The model in use is the chosen one once it's complete, and otherwise the other model on the phone.
- **Downloads** run while the app is on screen, pause when it leaves and resume when it returns, after a restart too. A resumed writer waits for the old writer to close. The final file is checked for length and SHA-256 before its atomic rename. In Chat, the first download sits above the composer, so typing still works, and the microphone waits for it. A switch shows only in Settings › Voice.
- **Cancel** deletes the model's files but keeps Silero, which both models use. **Remove** deletes the whole voice folder.
- **Notices** sit above the composer with their next step and a Close button. Ending with End or a Stop leaves no notice; the Stop receipt says "voice ended" instead (V-VoiceStop).
- **Turns:** a turn waits while a reply streams, and for up to 3 s after it's sent until its reply starts, since the core takes one turn at a time.
- **Ready:** a model that hasn't loaded after 90 s ends listening as failed. So does the voice process dying, which the main process notices through the link.
- **Test builds:** Check the microphone opens the voice test page, which now runs voice input's own capture, gain, segmenter, model store and recognizer.
- **The boards:** the built screens were compared with the V-Voice boards before release. The sizes come from the files, so Compact reads 671 MB and full precision 2.6 GB, where the boards say 670 MB and 2.5 GB.
