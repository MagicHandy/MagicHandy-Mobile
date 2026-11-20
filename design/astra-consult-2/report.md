# MagicHandy Mobile v1

MagicHandy Mobile should be a conversation you can run from a phone in one hand. Open a saved chat, connect the devices you want, then explicitly start motion or let Autopilot take over. The conversation stays readable while a single line tells you what the app is commanding. Tap it for live limits, tap Settings for choices that can wait, and reach Stop from either place. Ship a standalone native Kotlin app in its own repository, with remote inference and every required device family. Leave desktop administration, media authoring, model installation and fine-grained monitoring out of the phone experience. Put the engineering effort those cuts release into the moment the screen locks, the network disappears, or the app comes back and a device may still be moving.

## Decisions worth taking to the owner

**Approve the four outstanding proposals as a package, with one explicit exception to device parity.** Withdraw the Handy route through Intiface because a successful bridge response can conceal failed Stop delivery. Keep direct Bluetooth, Cloud, several named Handys and Intiface vibrators. People whose Handy is reachable only from their existing Intiface host lose that setup. Until the owner approves the exception, the route is still required for v1, with a persistent delivery caveat and an unconfirmed end-device outcome when only the bridge replied. The other three proposals would postpone persona archives, ship Steel Azure, Lowlight and High Contrast, and use the bottom Settings item to open the compact sheet. None is recorded here as an existing decision.

**Two further cuts change planned controls.** Defer Edge, the shortcut to a gentle hold, including its dedicated dispatch path. Someone who uses it loses a distinct action during play. Keep Pause and explicit Resume within reach. Remove the optional volume-key Stop and the extra notification controls. The volume key only works with the app in front, where Stop is already in thumb reach. The private notification opens Live on tap and has one direct action, Stop. This revisits the plan's foreground shortcut, its notification access to limits and the canvas's Pause and Edge actions. These cuts leave all four required Stop surfaces in scope.

**Keep session buildup as an optional finish target.** For the eight-minute Advanced desktop sessions, stating the target in chat alone reached 100% at eight minutes in every run. The bar alone reached the finish in five of six runs, and only after the prompt changed. This supports removing the always-visible progress bar. It gives no basis for dropping the structured target from a planner that cannot see the conversation, or predicting success on LAN models. Put the duration in Autopilot settings and pass it to the planner. Describe it as a target. A user can clear or replace it.

The authority for this review is the owner's current brief, followed by the applied decisions in plan §§15–16. In particular, any device failure stops the entire run. The older system design still offers continuing with healthy devices. The earlier consult also misstates the meaning of Style, the status colours, the mode descriptions and the availability of Layered. The product tables below use the corrected behaviours. [Architecture plan](../../android-architecture-plan.md) · [System design](../../docs/mobile-system-design.md) · [Previous review](../astra-consult/report.md) · [Canvas overview](../canvas/Main.dc.html) · [Astra adoption board](../canvas/A-Astra.dc.html)

## Scope ledger

Savings are qualitative v1 engineering effort relative to porting a desktop feature or implementing the fuller sketch. **S** covers little or no saving, **M** removes a workflow or material test surface, and **L** avoids a subsystem or integration. A Keep with S usually saves nothing. Applied exclusions describe savings already taken and must not be counted again. “Plan” names a planned commitment. “Applied” names an owner decision already incorporated. “Proposed” still needs a decision. Related items share a row when the verdict and trade-off apply to each.

### Conversation, motion and Autopilot

| Feature or setting | Verdict | Reason and v1 behaviour | What the user loses | Saving | Decision touched |
|---|---|---|---|---|---|
| Chat, streaming, composer and send | Keep | Stream readable text, validate before dispatch, save the committed exchange. On interruption, keep the user's message and offer deliberate resend. | No capability cut. | S | Required chat |
| Typed Stop and Stop during a reply | Keep | Recognize the desktop deterministic Stop fast path before inference, including offline. Fence and discard uncommitted output. | Interrupted text cannot later execute. | S | Plan §§4–5 |
| Refusal retry, validation, repair and provider errors | Simplify | Preserve snapshot refusal and validation semantics. One repair for a rejected reply, same configured provider. A failed repair holds motion. Reuse the provider error notice. | No repair dial, alternate local retry or second-opinion model. | M | Applied §15 |
| Chat sessions, history window and New chat | Keep | Autosave committed turns. Title opens a small list with New chat. Use the capability/context-sized recent history for every request. A running session stays bound to its chat. Browsing older messages sends no commands. End the run explicitly before handing control to a different conversation. | No unsaved tab or save/discard ceremony. | M | Applied §15 |
| Long-term memory | Simplify | Keep one opt-in Memory setting in the compact sheet, scoped to the conversation's persona. Explain what is sent and offer review/clear for saved entries. Off excludes retrieval and new writes. | No memory tuning dashboard. Clearing memory leaves the saved chat intact. | S | Plan §4 retained, default Off proposed |
| Persona presets | Keep | Use the shipped named presets and select in the compact sheet. A change takes effect on the next turn. | No loss for preset users. | S | Plan §4 |
| Portable persona archive import | Defer (after v1) | Avoid writing an untrusted archive parser, validation and migration before launch. | Existing desktop personas cannot be imported in v1. | M | Proposed §15, revises §4/M1 |
| Persona editor, lore and portraits | Remove | Presets cover v1 selection. Editing and media assets add another authoring workflow. | No custom character authoring or portraits. | M | Already out of scope |
| Motion mode picker | Keep | One chooser behind Settings. Show Layered, Simple, Off for a normal lead and Tempo, Off for a VacuGlide lead. Derive it from the lead and committed settings. | No direct mode chip on Chat. | S | Owner menu rule and §15 |
| Gentle, Balanced, Intense | Keep | Style biases motion character within the same limits. Apply to subsequent plans. | No cut to motion styles. | S | Plan §14 and owner correction |
| Now playing, source and command receipts | Simplify | In Chat show state, one commanded value and Autopilot state. Show a brief receipt after accepted change. Move per-device values, bounds and next check to Live. | Fewer simultaneous readings on Chat. | S | §16 adopted strip |
| Start, Pause motion and Resume | Keep | Pause suspends physical output and planner advancement while preserving the run and limits. Resume is an explicit motion start with the current limits applied. Stop ends the run. | No cut to temporary hold. | S | Plan §4 and acceptance fixtures |
| Edge | Defer (after v1) | Its gentle hold is a fourth motion state to recognize and test across devices. Prioritize a predictable Pause/Resume pair in v1. | No single-tap gentle moving hold. | M | Revisit §4, §14, M3 and §16 Chat deck |
| Top speed, stroke low/high, limits per source | Keep | Enforce centrally for manual, chat and planner output. In Live use independent +/- steps with value names. Reveal family-specific limits in device detail. Idle edits never start output. | No duplicate controls on the roster or vertical faders. | S | Plan §§4,16 |
| Reverse direction and mounting limits | Keep | Store per Handy, reachable in its detail page. Expose direction constraints to the engine. | No repeated switch in Chat. | S | Applied §15 |
| Autopilot, auto timing and planning controls | Keep | Explicit start, pause planning, resume planning and turn off. Pause planning holds new plans and says what the current segment is doing. Turning it off cancels future planning. Stop fences every source and ends output. | No cut to screen-off planning. | S | Essential v1, M2 |
| How often it changes | Simplify | Keep auto timing and the desktop 1–8 frequency bias as one labelled stepper in the Autopilot drill-down. Copy the meaning of the endpoints from the port. | No eight-button row or timing panel during play. | S | §14 compact settings |
| Conversational check-ins | Simplify | One choice, Off, Talk, or Talk and adjust. Planner chooses when. Talk emits chat text, Talk and adjust can emit validated motion within limits. | No separate frequency/switch pair or synthesized speech. | M | Applied §15, voice excluded |
| Session buildup | Simplify | Optional finish duration, plus time remaining in Live when set. Pass one committed target to both planner roles. Keep the user's chat instruction in context when available. | No permanent progress meter or implied guarantee of reaching the finish. | S | Keep §4/M2 feature, revisit §14 presentation |
| Run history | Simplify | Expand Recent changes in Live. Keep a bounded current-run list of meaningful events with time, actor, request, applied limit and command. Show next planner check separately. | No history destination, replay, sample log or past-run browser. | M | §16 M2 history retained in place |
| Pattern library, library mode, curated patterns, edits, programs, import and Decisions API | Remove | Entire library path already excluded. Keep shared dynamic motion definitions used by chat and Autopilot. | No browsing, authoring or selecting saved patterns. | L | Applied §15 |
| Original Creative mode, Freestyle, preset motion modes, Labs and flow experiments | Remove | The original Creative contract was retired. Keep Simple's supported contract and shared engine primitives. | No legacy experiments or manual preset modes. | M | §4 exclusions and applied §15 |

### Models and connections

| Feature or setting | Verdict | Reason and v1 behaviour | What the user loses | Saving | Decision touched |
|---|---|---|---|---|---|
| ChatGPT sign-in | Keep | System browser Custom Tab, PKCE, Keystore tokens. Default GPT-6.1 Terra, Advanced, Low reasoning. Verify redirect in M0. | No cut to hosted chat. | S | Required provider |
| OpenRouter | Keep | Key and model on one provider form. Persist reasoning with the connection. Test streaming, validation and cancellation. | No cut to model choice. | S | Required provider |
| Ollama, llama.cpp and other OpenAI-compatible LAN servers | Keep | Enter address, detect type, fetch models, show context requirement and perform a test before saving. Compatible endpoints share the adapter. | No managed runtime, download or setup of the host. | S | Required remote-first, applied detection |
| LAN discovery of nearby hosts | Defer (after v1) | Ship manual address and probe that host only. A /24 sweep misses other subnets and creates scan, timeout and permission UX. Leave pasted hostnames and addresses usable. | People must know their server address and port. | M | Revise system design §4 and F-AddServer. Keeps §15 type detection |
| Capability levels and per-model limits | Simplify | Suggest Simple, Intermediate or Advanced from the selected model. Allow override and validate configured context. Keep reasoning with the connection. Advanced selects the no-reasoning contract automatically when reasoning is off. | No fourth visible level, driver picker or speed-tier terminology. | S | Applied §15 |
| Intermediate and Simple contracts | Keep | Both execute Layered with paired-endpoint edits. Test Gemma 4 12B at Intermediate and E4B at Simple. Advanced hosted Layered uses Terra. | No cut. An Advanced-only port would undermine the two required LAN use cases. | S | M3, owner correction |
| Model roles and data disclosure | Simplify | Chat plus Autopilot, default Same as chat. Show destination, model, capability and what context is shared before changing a role. Allow an independent planner. | No context-sharing switch or extra inference roles. | M | Applied §15 |
| On-device inference on Qualcomm and Tensor | Defer (after v1) | Preserve the provider interface. Add a separate inference process, chip probe and per-level evaluation only when bringing up llama.cpp, QNN/Genie or LiteRT-LM. | No offline local inference in v1. | L | Owner schedule |
| Gemini Nano, desktop Easy Setup, managed llama.cpp, model installer/downloads | Remove | Plan excludes AICore for this use case. The v1 client connects to models elsewhere. | No built-in provisioning. | L | Plan §§4,9 |

The mode list describes what the AI may do. The capability list describes the connection's contract. Keep these exact picker descriptions.

| Mode | Description |
|---|---|
| Layered | The AI adjusts reach, location and pace separately and keeps the rest. |
| Simple | A flowing phrase that keeps developing over many strokes. |
| Off | The AI only talks and never moves the device. |

For a VacuGlide lead offer **Tempo** and **Off**. Apply Off to AI motion from both chat and Autopilot. When selecting it during AI-driven playback, take the shared Stop path first and report actual outcomes. Re-enabling an eligible mode permits a later explicit start. Switching among Simple, Intermediate and Advanced never hides Layered. A lead or role change invalidates in-flight plans compiled for the previous configuration.

### Devices and safeguards

| Feature or setting | Verdict | Reason and v1 behaviour | What the user loses | Saving | Decision touched |
|---|---|---|---|---|---|
| The Handy over native Bluetooth | Keep | Local Stop works offline. Explicit scan, stable results, distinguishing identifiers and nickname. Verify profile before showing Connected. Serialize GATT operations. Use RPC 715 on Stop and orderly teardown. | No automatic fallback to a different device. | S | Required device/path |
| The Handy over Cloud | Keep | HSP via connection key. Retire a stream on network change and require a new start. Buffer starvation may pause a Handy. Show confirmed only with supporting evidence. | No automatic replay or resume. | S | Required device/path |
| The Handy through Intiface | Remove | A bridge can acknowledge Stop without reaching the Handy. Direct Bluetooth and Cloud preserve the device. Remove only this family/route combination in discovery and dispatch. | Users who depend on this bridge must change connection method. | M | **Explicit proposed reversal** of §1/§8 desktop path parity |
| Several Handys and linked sessions | Keep | One named roster, explicit run membership, one lead and one engine fan-out with per-owner epochs and receipts. Identify duplicate routes to the same physical Handy before admission. | No promise of physical synchrony. | S | Explicit v1 requirement, ADR 0054 |
| Intiface vibrators and vibration envelope | Keep | Model-controlled level with per-device maximum. Connect to Central on the phone or LAN. A closed shared socket loses every attached owner and stops the run. | No loss of vibrator support from the Handy-route proposal. | S | Required device, ADR 0061 |
| DG-LAB Coyote 3.0 | Keep | Acknowledge the Coyote safety notes **before the first connection**. Then explicitly select and verify that device. Start from reported channel baselines, enforce limits, zero both channels on Stop and end the packet stream. | No blind reconnect or inferred starting strength. | S | Required device, owner safety prerequisite |
| Autoblow Ultra | Keep | Share the Autoblow token, event stream, health checks and serialized cloud client. Preserve one command per stroke leg and the hardware emergency button mapping. | No cut to the device. | S | Required device |
| VacuGlide, Tempo and motor test | Keep | Same cloud client, distinct persistent-speed behaviour. Show tempo range, explicit start/test and the physical fallback before moving. Keep bounded optional valve adjustment, default Off, up to three steps either way per session. | Testing requires the same safeguards as starting a run. | S | Required device, R41/R43, M4 |
| VacuGlide connection-loss Stop and foreground requirement | Keep | Arm journal and require a live service before the first command. Attempt Stop on loss and again after reconnect or cold start. Lead with local switch-off guidance on uncertainty. | No unattended assumption that a dead phone can stop it. | S | Plan §10 |
| VacuGlide check-in | Keep | Recommend the open default of **every 15 minutes**, with an explicit Off choice in device detail. Deliver a local prompt with a one-minute response window. On expiry issue Stop to the entire run. | An extra acknowledgement during longer runs. Dispatch and confirmation still need a working phone and link. | S | Resolves §12 default, retains optional safeguard |
| Run participation, connect, disconnect, reconnect, battery/signal/latency | Simplify | Roster groups by actual run membership. Put transport and state on each row. Link metrics on detail when available. Fix the selected participants for a run. Connecting/reconnecting ends at Connected and idle. | No inline roster limit controls or mid-run join. | S | §16 roster and connect invariant |
| Device failure policy | Remove | One failing participant trips Stop for all. Preserve each owner's evidence even after run membership clears. | No continue-on-healthy-devices setting or partial-run notices. | M | Applied §15 supersedes system design §3 |

### Android, settings and scope boundaries

| Feature or setting | Verdict | Reason and v1 behaviour | What the user loses | Saving | Decision touched |
|---|---|---|---|---|---|
| Footer Stop, per-device receipt and immediate haptic | Keep | One direct action in every app state. Pending, confirmed, unconfirmed and no device have different meanings. Show uncertainty first. | No optimistic “Stopped” on dispatch. | S | Applied §16 |
| Notification, public lock screen and Quick Settings tile | Keep | Private view can show names, commanded pace and time to check. Public view exposes only “MagicHandy · Active” and Stop. “Stop motion” tile has one command. | Lock screen hides run details. | S | Mandatory surfaces |
| Other notification actions and idle state | Simplify | Private content tap opens Live, where limits and Pause live. Keep quiet connected/ready notification while the service is needed. Remove separate Open, Open limits, Pause, Edge and adjustment buttons. | Unlock/open app to pause or edit limits. | M | Revisit §3.3, F-System and previous consult shortcut |
| Hold Volume down / volume chord | Remove | Foreground-only shortcut competes with the phone's volume control and needs discovery, settings and key-event tests. | No eyes-free physical Stop using that key. | S | Revisit optional §10 and F-System |
| Adult and safety acknowledgement, Stop rehearsal | Simplify | One first-run page with the 18+ acknowledgement and an actual no-device Stop. Ask for each Android permission at the feature that needs it. | No four-step setup tour. | S | §16 onboarding retained |
| Nearby devices, notification and battery access | Keep | Request Bluetooth where applicable, explain the missing lock-screen action if notifications are denied, and show the system settings link. Offer battery guidance on relevant device/restore pages. | No blanket permission wizard. | S | §16, safety access |
| Steel Azure, Lowlight OLED and High Contrast | Simplify | Three token sets on the same components. Test every state in each palette. Keep the exact semantic hues. | Other desktop palette choices and dynamic colour. | M | Proposed §15 changes §4/ADR 0073 mobile scope |
| Five UI languages | Keep | Ship English, Spanish, Brazilian Portuguese, Japanese and Simplified Chinese (en, es, pt-BR, ja, zh-Hans). Reuse catalogues and check new safety copy with speakers. Default to system language, allow override. | No locale cut. | S | Plan §4 and fixtures §7 |
| Diagnostics and export | Simplify | Bounded structured events, redact on ingestion, share only on request via Android's chooser. Exclude transcripts, memory, tokens, keys, addresses and raw requests. | No raw trace export or analytics dashboard. | S | §4 export retained |
| Chat/app settings entry points | Simplify | Bottom Settings opens one in-window sheet. App settings inside holds durable preferences. Live calls the same sheet. Device-specific durable settings link to their existing detail form. | One extra tap to app-wide preferences, one fewer duplicate menu. | S | Proposed §16 and owner compact-menu rule |
| Foreground service, network watcher, run journal and restore | Keep | Device ownership survives activity recreation. Interrupted runs recover explicitly. A journal is required even on a phone with battery exemptions. | Recovery never resumes a run on its own. | S | System design §§1–4 |
| Accessibility, reduced motion and readouts | Keep | 48 dp targets, 200% reflow, Stop action everywhere, sentence-level streaming speech, assertive Stop receipts. Suppress animation and avoid reading every tick. | No cut to these affordances. | S | Plan §16, system design §8 |
| Branding, SDK, signed release and update check | Simplify | Adaptive and monochrome mark/star, minSdk 31 and planned target 36. Signed GPL-3.0-only APK with SHA256SUMS. Keep a user-invoked release check/link in App settings. | No background update polling or in-app installation flow. | S | Plan §10 distribution |
| Simulated devices | Keep | Debug-only in-process endpoints behind the real transport boundary. Make simulation explicit and unavailable in release. | No pretend hardware in production. | S | M1 and system design §9 |
| Desktop video, media playback and funscripts | Remove | They would bring timeline, sync and file handling into a chat app. | No media-driven runs. | L | Already out of scope |
| Voice input, speech recognition and TTS playback | Remove | Chat and planner check-ins use text. TalkBack remains available as an accessibility service. | No app-managed voice conversation. | M | Already out of scope |
| Controller leases, watchers, Take control, notification center | Remove | A single app UI observes one service. Android handles notifications, banners handle interruptions. | No remote spectators, control handoff or inbox. | M | Applied §15 |
| Desktop accounts, public access, HTTPS hosting, phone remote, console, setup, installer and updater | Remove | The phone owns its session and uses outbound providers/transports. Provider sign-in and outbound HTTPS remain essential. | No desktop administration, serving a browser UI or remote-controlling another app. | L | Owner native form and §4 exclusions |

## How to build less and still prove the port

### Three production modules

Use **:core**, **:transport** and **:app** with a hand-written application container. Keep JVM fixture tests in the first two modules. The plan's many Gradle boundaries turn routine changes such as adding a receipt field into edits across several build targets inside a single app. Packages and a few enforced import rules buy the boundary that matters.

| Module | Owns | Boundary to test |
|---|---|---|
| **:core** (pure Kotlin) | Domain types, session orchestration formerly in HTTP handlers, chat composition/validation, planner, engine, clock and interfaces for transport, provider and storage. | Semantic clients emit targets only. A package/import test forbids chat and Autopilot from importing output, transport implementations or Android. Only the engine dispatches commands, including manual tests. |
| **:transport** (JVM Kotlin) | Codecs, sessions, BLE byte-link interface, Cloud REST, Intiface WebSocket, streaming provider clients, prompt budgets and redacted request types. Depends on core ports. | Drivers map engine frames to wire output. Providers return text/semantic input for validation. Keep driver/provider packages independent. Test exact bytes and cancellation without Android. |
| **:app** (Android) | Compose, navigation, service/receiver/tile, network callback, Android GATT, auth, persistence, Keystore and debug wiring. | Activity observes immutable StateFlow and sends intents. Wire one process-scoped engine. Disallow Android UI, receiver or persistence code constructing motion payloads. |

Keep named package seams for BLE, auth and store. Extract a Gradle module later if there is a second consumer or build evidence justifies it. Skip DI infrastructure, an empty inference module, Go/Rust/JNI, WebView and a local application server. Port the outbound SSE client. The desktop's inbound HTTP/SSE API and Bluetooth worker IPC have no Android caller. For sign-in, the plan's one-shot loopback OAuth callback deserves a specific M0 check against the owner's no-local-server rule. Prefer a provider-supported app redirect if available. If the supported registration requires a loopback listener, obtain an explicit owner exception for that transient callback only. Do not assume an alternate redirect is registered or promise sign-in until a real return, cancellation and token refresh work.

### Store by recovery requirement

| Data | Recommendation | Failure or migration rule |
|---|---|---|
| Sessions, ordered messages, optional memory, named connections, role bindings and revisioned settings | **One Room DB** under no-backup storage. One serialized repository applies dirty-field patches against the last known revision. Built-in presets and locale catalogues are packaged resources. | Commit a turn and its memory changes transactionally. Reject or merge stale patches by field and retain the last committed UI value. Version the schema and test upgrade. Skip a second DataStore writer for settings and the cross-store consistency it would entail. |
| Current run and unresolved Stop targets | **One versioned atomic file** under no-backup storage. Write the run ID, stable owner references, transports, last intended command/state, Autopilot flag and timestamp. Keep secret material in the vault. | Arm durably **before** the first motion-capable write, including Test motor and manual starts. If arming fails, refuse the start. Journal changes at state boundaries, never at frame rate. Emergency Stop advances immediately even if storage is stalled. Never clear an unresolved owner on a “clean” activity exit. Clear only after reconciliation, retain unresolved evidence with explicit local-check status. |
| Diagnostics, receipts and run history | Capped redacted events in a rotating no-backup file for post-crash diagnosis. Keep current-run history in memory and the recovery facts in the journal. | Bound writes outside the output thread. Export a consistent redacted snapshot on explicit share. No chat text or per-packet persistence. |
| Secrets | Separate AES-GCM secrets file sealed by Android Keystore. Store opaque handles in Room and journal. Disable backup and exclude all stores via data extraction rules. | An inaccessible key means re-authenticate and show uncertainty. Avoid disclosing credentials in logs, recovery notices or exports. |

### One engine when the activity disappears

* **Admission.** Connecting negotiates capabilities and ends idle. An explicit start snapshots membership, limits, lead, role/configuration revision and epoch. For the first motion-capable chat turn, name the devices on the start action. Sending ordinary text with no armed run is safe. A device change cannot join a running group. The service, chat, Autopilot, device buttons and Compose all address the same process owner. Recreating an activity only resubscribes it.
* **Stop.** Give immediate haptic feedback on supported surfaces. Advance the atomic epoch synchronously, close admission, cancel turn/planner/retarget jobs and mark the engine stopped. Dispatch Stop to **all** owners in parallel on a dedicated priority path with independent deadlines. Avoid waiting for main, storage, model I/O or a blocked device queue. Check epoch/generation both when enqueuing and immediately before a mutating transport write. In-flight bytes cannot be recalled. Retire an uncertain link and send recovery Stop. Late replies can update evidence for their original command only. They cannot reopen admission.
* **Receipts.** Confirmed requires evidence of device acknowledgement for that owner. Pending means a reply can still arrive before its deadline. Unconfirmed covers timeout, failed write, unknown delivery and bridge-only acknowledgement. Report no device only when both the current target set and the unresolved recovery target set are empty. Retain uncertainty across repeated presses. Show the worst unresolved owner first, pending next, confirmed last. Say “Stopped.” only when all targeted owners confirm. Attribute a failure to the connection and give one local action. A Coyote write without response and a radio disconnect alone cannot establish physical cessation.
* **Lifetime.** Start device work from a visible user action, with the foreground service and ongoing notification established before motion. Hold service ownership through connected-idle, playing, paused and active Autopilot. Hold a wake lock only when active output needs it. A foreground service improves survival and cannot guarantee network, CPU or a cleanup callback. Bind chat-only streaming to the visible activity/session when no device is involved. If the app backgrounds in that case, cancel the turn with a notice. This deliberately revises the system design's service trigger for all streams and avoids relying on a connected-device service type for unaffiliated inference. Verify permitted service types, permissions and startup deadlines on actual targets in M0.
* **Interruption.** Swipe-away attempts Stop before teardown. Force-stop or process death can skip all app code. On cold start inspect the journal before admitting output, attempt idempotent Stop to saved cloud owners immediately where credentials/network permit, and carry unresolved attempts to reconnection. Keep motion and Autopilot off. Resolve uncertainty through acknowledgements or a clearly attributed local check. Recovery must never send a movement command to discover whether the previous run ended. Battery settings lower risk at best. If a required safety check-in cannot reach the user, block a new check-in-enabled VacuGlide run until delivery is available or the owner explicitly changes that setting.
* **Network.** Distinguish provider reachability from participant loss. If only inference drops, abort the unfinished turn, hold planning, show whether a previously validated Bluetooth segment is still playing, and admit no speculative extension. Resume planning only within the same eligible live epoch. Loss of any participating transport trips whole-run Stop. Cloud stream changes retire the generation. Send VacuGlide recovery Stop on network return. A LAN server can be reachable on Wi-Fi without internet validation. Label local HTTP/WS “Not encrypted”. Permit cleartext only to loopback/private/link-local peers, validating the resolved address and redirect at each hop. Hosted endpoints use HTTPS, with reusable connections while visible and no warm-up pings in idle.
* **Radio and cloud.** Use one serialized GATT operation per connection. Check characteristics, notification setup, MTU and payload cap before Connected. Retry failed connects at most twice before mutation, never retry an uncertain movement write. A Stop can preempt queued operations and retire a stalled generation. Set run priority high and idle priority balanced. Preserve Handy HSP/RPC semantics, Coyote's 100 ms cadence, Central pings, Autoblow's 5 s health read, heartbeats and serialized generations. Preserve the desktop Stop sequence for Autoblow, including cancelling the in-flight call at 300 ms and resending Stop at 750 ms.

### Port order and evidence for release

M0–M4 still describe **one v1 release**. Move discovery of dangerous failures earlier than their polished screens. These are proposed acceptance tests. The supplied desktop evaluation is the only measured model result cited here. Android behaviour and the release budgets still need measurement.

| Gate | Build in this order | Evidence needed before moving on |
|---|---|---|
| M0 · Prove the boundaries | Pin alpha.88 (`bafc7ec4`) and the PR #374 head (`9a4c36cf`, includes #373), recording the exact effective tree used for each fixture. Port a sampler slice, real Handy GATT Stop, one Terra text turn, journal arming, a simulated persistent-speed owner and notification Stop. | About 20 atlas cases, real MTU/write/Stop timing, OAuth return, denied notifications, kill between journal write and first command. Measure service eligibility, output jitter, cold start, memory, APK size and effort per case on the Pixel. |
| M1 · One complete path | Finish the engine, stop admission, BLE/Cloud Handy and hosted chat through the real UI. Include saved sessions, presets, memory, OpenRouter, secrets, TalkBack and every retained Stop surface. | All 135 atlas cases, hosted and wire fixtures. Exercise start, retarget, cap changes during Pause, deliberate Resume, typed Stop, late SSE, swipe-away and airplane mode. Stress a stalled transport while another acknowledges promptly. |
| M2 · Essential Autopilot | Port semantic planner, timing, check-ins and finish target. Exercise the same service, engine, run history and receipt with the screen off. | Thirty minutes under Doze, no unexplained stalls. Stop from lock screen and tile. Test provider-only loss with a still-running BLE segment, followed by participant loss. Replay target-in-chat, structured target alone and both, with shared and separate planner contexts. |
| M3 · LAN and multi-device | Add manual address detection, both Gemma contracts, Intiface vibrators, several Handys, Coyote and Ultra. Keep Handy/Intiface in this gate if its removal is declined. | LAN E4B/12B meet snapshot default-model thresholds. Pair two named Handys, interrupt one and verify per-owner Stop evidence. Observe actual Central disconnect behaviour. Coyote cannot connect before acknowledgement. Ultra finishes its last leg under forced host loss. |
| M4 · Persistent-speed hardware and hardening | Validate the VacuGlide path with the real device, journal, watchdog, check-in and recovery UI. Finish five locales, three proposed palettes, accessibility and release packaging. | With local physical control available, kill the app during motion, take the network offline, miss a check-in and repeat recovery across restarts. Verify Stop retry and truthful uncertainty. A failed gate holds the whole release under the owner's all-device scope. |

**What to freeze.** Use a one-time desktop exporter and synthetic inputs. For every fixture record source SHA, case ID, role, locale, capability, mode, settings revision and clock inputs where relevant. Compare transport-neutral atlas frames, coalescing and retarget sequences, paused limit edits, epoch races and recovery Stop. Compare exact BLE bytes, HSP REST, Buttplug messages, Coyote packets and Autoblow requests. At the chat boundary compare request bodies, prompt order, history selection, response schema, sampling/reasoning, refusal and repair prompts, accepted target or rejection. Cover Off, lead-specific Tempo, hosted Layered and both paired-endpoint contracts across roles. Preserve notice strings, five translations, presets, brand mark and star. Pin normalized time, secrets and IDs in synthetic goldens. Keep real traces, keys and private chats out of the fixture set.

**What to simulate.** Use an injected monotonic clock, coroutine test scopes and in-process fakes. Script a response arriving after Stop or a role change, a stale settings patch, uncertain mutation, double Stop, repeated reconnect, lost acknowledgement, two transports for one owner and crash during a journal update. Assert zero new motion writes after the fence, even from retries. Allow safety Stop retries. Assert no planner/timer children after shutdown. In storage tests cover interrupted commits, journal schema upgrades, partial files, unavailable secrets and idempotent recovery. In UI tests cover Back from a sheet, opening the keyboard during a run, rotation, and direct Stop with a modal open.

**What a phone must prove.** Exercise real BLE, hardware button events, cloud and Intiface outcomes, background kill, force-stop, forced Doze, permission revocation, locked screen, Wi-Fi to cellular and a second Android vendor for GATT variance (R44). Watch the actual device as well as the API response. An observer's test finding does not upgrade the app's runtime evidence. Use the plan's Pixel 8-class targets of cold Chat under 1 s, UI plus core under 150 MB playing, and release APK under 20 MB. Track tail jitter and missed Coyote slots against its 100 ms cadence. Deliver visible readouts at up to 8 Hz, notification state at 1 Hz, no observation ticks without a consumer, and streamed text UI at up to 30 Hz. At disconnected idle require zero service, socket timers and wakeups. Disable cache-only observations under memory pressure. Track regression against measured M0 baselines.

**How to stay independent.** The mobile repo owns its fixtures after the verified port. Keep `PARITY.md` mapping Kotlin packages/files to source paths and last reviewed SHA. Periodically inspect those Go diffs, select chat changes, rerun the relevant goldens and a short Terra/LAN check, and record the new baseline and intentional divergences. The desktop 339-turn replay, 85 live-eval files and 135-case motion atlas are references for coverage. Build a small representative mobile replay and borrow evaluation thresholds. Skip continuous cross-repo synchronization.

## Converged design

### Graphite Deck

Use the Deck's thumb-reachable controls, Touch's command receipts and Lowlight's legible Live numerals. Use the platform Roboto family with tabular figures so the timer does not shift nearby controls. Pick one type and spacing system across all device families.

| Token | Production value and use |
|---|---|
| Steel Azure neutrals | Canvas #101113, surface #1A1C1F, divider #393C41, text #F2F2EF, secondary #B8BCC2. One card level. Prefer rows and spacing for grouping. |
| Lowlight / High Contrast | Lowlight canvas #000000, surface #111111, text #E9E4DB, secondary #BEB9B2. High Contrast canvas #000000, text #FFFFFF, secondary #E5E5E5, control boundaries #A9A9A9. Keep semantic hues fixed. |
| Actions | #5B9DD9 is the interactive accent for links, focus and selection. #287D42 marks explicit motion starts such as Resume and Test motor. Stop alone gets the solid red fill #C43C36 with white icon and label. |
| State | Dot plus visible word. #4FC06D for Playing, Connected or Ready. #D8B66A for Connecting, Held or uncertainty. #D6453F marks a failure such as Connection lost. Neutral ring for Needs setup, neutral dot for idle/disconnected. Use readable foreground text alongside the mark. |
| Type | 24 sp page title, 18 sp section or Stop label, 16 sp body/actions, 14 sp supporting text, 40 sp commanded figure on Live. Use system font scaling. |
| Geometry and access | 4 dp rhythm, 8 dp related spacing, 16 dp page inset, 24 dp between sections. Radii 10 dp sheet/card, 6 dp controls, 4 dp small details. 48 dp minimum ordinary targets. One shared 60 dp Stop row, minimum 56 dp, full width with flush outer edges. |
| Motion and contrast | Instant state text, subtle transitions respecting reduced motion. No glow, flashes, purple or decorative blue-green. Target 4.5:1 normal text and 3:1 large text and meaningful control boundaries. Use neutral outlines when hue alone has insufficient contrast. |

### The app frame and the complete route list

**Two destinations and one action** fit the bottom row. Chat and Devices maintain their own scroll position. Settings opens a compact sheet over the current destination. While idle, Chat's Live entry remains available so a user can start Autopilot or set limits before playing. During playback it becomes the Now playing strip. Include an explicit start with participant names and effective limits at admission. Chat without devices or with Off selected remains usable.

Draw every app sheet and scrim **inside** the content area above the shared footer. Tabs are directly above Stop at the roots. Drop tabs on subpages. When typing, drop the tabs and secondary controls, shrink the transcript and keep the entire Stop row directly between the composer and the docked keys. Compare footer coordinates at equal window insets. With the keyboard open its bottom tracks the visible app boundary. System-owned dialogs, sign-in and the notification shade have Android-controlled layouts. Return focus to the opener on Back or Close.

| V1 surface | Entry, content and exit |
|---|---|
| **1. Before you start** | First launch. Adult/safety acknowledgement and real no-device Stop rehearsal on one page. Continue to Chat. No required provider or device account to explore setup. |
| **2. Chat** | Launch into the last saved conversation. Title opens the chat list sheet with New chat. Composer, streamed replies, command receipts, Pause/Resume and one strip to Live. |
| **3. Live controls** | Open the strip, the idle Live entry or the private notification. Back/Done returns to the origin. Show per-device commanded values, source, limits and Autopilot controls. Expand Recent changes inline. Autopilot settings uses the shared sheet. |
| **4. Devices** | Root tab. “In this run” and “Not in this run” describe membership even when a connected device is idle. Add a device opens the family selector. Tap a name for detail. Select participants/lead before Start. |
| **5. Device setup/detail** | Reuse one route keyed by device family and connection. Render only the relevant fields, scan/verify results, transport, nickname and limits. Render Coyote's first-connection safety acknowledgement as a required step within this route. VacuGlide gets a dedicated safety section here, visible before explicit motor test/start. |
| **6. App settings** | Last link in compact Settings. Language, palette, permissions, diagnostics sharing, release information and Models. Links to device settings reopen the existing detail page. |
| **7. Models** | Enter from App settings or the Chat model picker. Two role rows, saved connections with destination, model, level and state, then Add provider. |
| **8. Provider setup/detail** | One form for create/edit/test. ChatGPT starts system sign-in, OpenRouter takes a key and model, LAN takes an address, detects type and reports context and test result. Show role destination and applicable privacy disclosure. Back returns to the picker or Models. |
| **9. Run interrupted** | Cold start with an armed/unresolved journal. Start recovery independently of navigation. Lead with VacuGlide or other uncertain owner, outcomes, timestamp and one local action. Offer Devices or Back to chat. Leaving preserves unresolved receipts. |

**Sheets and system surfaces.** Use one sheet host for the chat list, run participant/lead selection, device-family selector, receipt detail and session settings. The six compact settings rows are Motion mode, Style, Autopilot, Persona, Chat model and Memory. The sixth row keeps the canvas's Memory control visible without a second settings hierarchy. Autopilot opens timing, check-ins and optional finish target together. Memory opens its switch, review and clear controls. A committed selection returns to the parent sheet. Back retains the draft and the last committed setting. The app additionally supplies the private notification, its public version and the Stop motion tile. Use system permission dialogs, OAuth Custom Tab and Android sharing from their entry points.

**Components to implement once.** App frame with bottom insets, Stop action and TalkBack action. Two-tab row with a Settings button. Page heading with Back/Done. Chat transcript, composer, sentence announcer and command receipt. Now playing strip and labelled status dot. Live number and accessible limit stepper. Named device row and explicit start control. Settings row, radio list, disclosure and in-window sheet. Safety acknowledgement, provider/connection fields, persistent banner, per-owner Stop receipt and recent-change row. These components cover all nine routes and the keyboard layout. Native notification and tile adapters call the same Stop entry.

### Every canvas screen accounted for

Savings here concern screen/UI work only. They overlap the feature ledger.

| Source board(s) | Verdict | What survives and why | What the user loses | Saving | Decision touched |
|---|---|---|---|---|---|
| Main, A-Astra | Simplify | Keep the frame rules, honest receipts and participation grouping as specifications. Use one design. | No gallery of alternative designs in the app. | S | §14 directions, §16 adoption |
| GD-Chat, GD-Live | Simplify | Base both routes on the lower deck. Label the live strip and retain precise independent limit steps. | Repeated chip/menu, Edge and permanent buildup meter. | M | §14/§16, Edge revisit |
| GT-Chat, GT-Live | Simplify | Reuse transcript receipt, per-device command rows and limits in the Deck routes. | No separate Touch layout or duplicated upper control cluster. | S | Design choice §12 |
| LL-Chat, LL-Live | Simplify | Keep OLED neutrals and Live's large figure in shared layouts. | Separate hierarchy, oversized Chat pace figure. | M | Three-palette proposal |
| IN-Chat, IN-Live | Remove | Retain requested versus commanded values as plain Live history. Abbreviations and vertical faders take space and obscure values under a thumb. | No instrument panel, hardware-key layout or faders. | M | Unselected §14 exploration |
| NV-Chat, NV-Live | Remove | Keep Android Back, accessibility and system sheets where appropriate. The common app footer and theme already serve both routes. | Standalone Material variant, dynamic colour and proposed floating Stop. | M | Unselected §14 exploration |
| S-ChatSettings, S-DeckMenu | Simplify | Combine into one compact Settings sheet, including Memory and an App settings link. | No popover layered over a second settings surface. | M | Owner compact-menu rule, §16 entry proposal |
| S-MotionMode | Keep | One nested picker with the correct lead-specific list and the three supplied descriptions. | No duplicated chip. | S | Owner menu decision |
| F-Devices | Simplify | Move limits to Live/detail. Show nickname, route, worded state and actual participation. | No redundant playback badges or global top-speed control here. | S | Adopted roster §16 |
| F-AddHandy | Simplify | Shared device editor, stable scan rows, Bluetooth and Cloud paths. | Intiface path only if owner approves. | M | Explicit parity exception |
| F-VacuGlide | Keep | Dedicated safety content inside shared detail. Keep range, valve option and explicit motor test. | No extra top-level destination. | S | Mandatory family, §10 |
| F-Models, F-AddServer | Simplify | Keep role/provider list and one conditional editor with direct test and type detection. | Nearby host list until discovery returns. | M | Required providers, proposed discovery deferral |
| F-Onboarding | Simplify | One first-run route, contextual permission prompts. | No lengthy setup sequence. | S | §16 |
| F-Keyboard | Simplify | Same Chat and anchored footer, adapting to IME insets. | Tabs and secondary controls while typing. | S | Adopted frame §16 |
| F-ConnectionLost | Simplify | Banner and per-owner receipt on the current route. Preserve user message and explicit resend. | No full interruption page for a known live failure. | S | Whole-run Stop §15 |
| F-Restored | Simplify | Dedicated Run interrupted page with the unresolved owner and physical action first. | No guessed kill cause, predicted outcome or battery settings detour before the action. | S | Adopted restore §16 |
| F-System | Simplify | Public/private notification and tile with direct Stop. | Notification edits, extra actions and volume shortcut. | M | Revisit optional system shortcuts |
| A-Astra concept panels 1–6 | Simplify | 1 supplies the labelled strip, 2 the sheet shape, 3 is superseded by S-MotionMode, 4 becomes Recent changes, 5's readable transcript survives, 6 supplies roster grouping. | Old four-mode picker, separate history page, Night Folio lavender and Indigo Index colours. | M | §16 adoption boundaries |

### Which failures deserve the screen

| Situation | Surface and action |
|---|---|
| Interrupted run after launch | **Dedicated recovery page.** The current conversation cannot explain which physical device needs attention. Show time and per-owner result, uncertainty first. “It may still be running at its last speed. Switch it off at the device.” Keep unresolved owners accessible after dismissal. |
| Coyote first connection / VacuGlide first setup | **Dedicated step in device detail.** Acknowledge Coyote notes before Connect can proceed. Put VacuGlide's physical switch-off guidance, service readiness and safeguards before Test motor. Preserve access to Stop throughout. |
| Live device failure, uncertain write, lost shared bridge or denied Bluetooth | **Persistent banner and receipt.** Stop the run and Autopilot. Name the lost owner, show who confirmed, who is pending and who needs a local check. Offer reconnect only when appropriate. A restored connection ends idle. |
| Provider-only loss, Wi-Fi change, exhausted planner segment | **Now playing strip or banner.** “Planning held” plus the actual current state, for example “Current segment still playing” or “Motion held”. Aborted reply stays uncommitted. User initiates resend. |
| VacuGlide check-in due, missed or offline | **Actionable in-app banner and private notification prompt** when due. Provide “I'm here” only in the private check-in context, leaving the ongoing Stop action intact. On timeout or loss show Stop evidence and local switch-off guidance. Public version stays discreet. |
| Stop pressed with nothing connected, confirmed Stop, pending Stop | **Receipt above footer.** “No connected device to stop.” for a genuinely empty target set. Announce receipt updates assertively. An unconfirmed result persists with the named owner and local action. |
| Unreachable server, rejected credentials, insufficient context, stale settings revision | **Inline form error or banner.** Preserve entered fields and last committed values. Name one next step, e.g. join the server's Wi-Fi, sign in again or retry the save. |
| Denied notifications, restricted battery, permission denied during setup | **Persistent settings/device warning.** Explain the missing surface or capability and link to the matching system setting. Request permissions in context. |
| Empty chat list, no device, no provider, idle run, typing, font enlargement, interrupted reply | **Existing route state.** Offer New chat, Add device, Set up model or Send again where relevant. At 200% let values wrap and content scroll above Stop. Announce complete sentences, reduce meter motion and let Stop results interrupt speech. |

## Six concept images

Each link opens a separate 1024 × 1536 PNG. The sixth illustrates the strongest simplification in recovery. On return, the user needs to know which device to check locally. A single receipt list answers it without asking the person to interpret the chat, planner history or battery settings.

1. [Chat during an Autopilot run](images/01-chat.png)
2. [Live controls and recent changes](images/02-live.png)
3. [Devices grouped by run membership](images/03-devices.png)
4. [Compact session settings](images/04-settings.png)
5. [Public notification and Stop motion tile](images/05-lockscreen.png)
6. [Interrupted run and local switch-off action](images/06-choice.png)

The built-in image generation tool produced all six. I checked the pixel dimensions and inspected the text, footer placement and screen content. The generator varies the footer height, colours and stroke weight slightly between images. Use the token table and shared 60 dp footer for implementation. The system concept shows the requested red final row to identify the public notification action. Android and the lock-screen settings control its actual size, colour, position and availability. A real locked-device test must verify one-tap delivery without exposing private content.

### Exact image prompts

<details>
<summary>01-chat.png</summary>

```text
Create one 1024×1536 portrait PNG, a flat edge-to-edge Android UI concept. UI only. No handset frame, people, bodies, physical devices, device illustrations, photos, suggestive imagery, poster titles or annotations. Render the quoted product wording exactly in large readable Roboto type. Restrained near-black Graphite Deck: background #101113, surface #1A1C1F, text #F2F2EF, secondary #B8BCC2, hairline #393C41. Interactive accent #5B9DD9; #287D42 only for motion-start actions, #4FC06D for OK/running dots, #D8B66A warnings, #D6453F failure dots. Always pair a status dot with its state word. No purple, teal, gradients, glow, nested cards or invented copy. Modest 10/6/4 dp corners, flat grouped rows, generous touch areas. Reserve a full-bleed solid #C43C36 final row at the very bottom, about 144 pixels tall, white square stop icon and bold white “Stop” centered. Nothing below it. Only Stop is solid red.

Chat while playing. Header “Evening” and “Companion” with a small chat-list icon. Airy left-aligned transcript: “You” / “Keep this pace for a while.” and “Companion” / “I’ll keep it steady.” Below the conversation show a single thumbnail-free live strip, green dot “Playing”, “Pace 48% commanded” with right chevron, secondary “Autopilot on”. Outlined “Pause” action and a “Message” field with send arrow. One horizontal navigation row “Chat”, “Devices”, “Settings” above the final Stop, Chat selected. No Edge, tuning, mode chip, or extra meters.
```

</details>

<details>
<summary>02-live.png</summary>

```text
Create one 1024×1536 portrait PNG, a flat edge-to-edge Android UI concept. UI only. No handset frame, people, bodies, physical devices, device illustrations, photos, suggestive imagery, poster titles or annotations. Render the quoted product wording exactly in large readable Roboto type. Restrained near-black Graphite Deck: background #101113, surface #1A1C1F, text #F2F2EF, secondary #B8BCC2, hairline #393C41. Interactive accent #5B9DD9; #287D42 only for motion-start actions, #4FC06D for OK/running dots, #D8B66A warnings, #D6453F failure dots. Always pair a status dot with its state word. No purple, teal, gradients, glow, nested cards or invented copy. Modest 10/6/4 dp corners, flat grouped rows, generous touch areas. Reserve a full-bleed solid #C43C36 final row at the very bottom, about 144 pixels tall, white square stop icon and bold white “Stop” centered. Nothing below it. Only Stop is solid red.

One subpage titled “Live controls” with a back arrow. Large “48%” with adjacent label “Pace commanded” and green dot “Playing”. Three thumb-friendly separate minus/value/plus rows: “Top speed” 80%, “Stroke low” 15%, “Stroke high” 85%. A neutral outlined “Pause motion” control. Flat Autopilot section with green dot “Active”, “Next check in 0:40”, “Pause Autopilot” and “Turn off Autopilot”. One collapsed disclosure “Recent changes”. No tabs. End with full-width Stop.
```

</details>

<details>
<summary>03-devices.png</summary>

```text
Create one 1024×1536 portrait PNG, a flat edge-to-edge Android UI concept. UI only. No handset frame, people, bodies, physical devices, device illustrations, photos, suggestive imagery, poster titles or annotations. Render the quoted product wording exactly in large readable Roboto type. Restrained near-black Graphite Deck: background #101113, surface #1A1C1F, text #F2F2EF, secondary #B8BCC2, hairline #393C41. Interactive accent #5B9DD9; #287D42 only for motion-start actions, #4FC06D for OK/running dots, #D8B66A warnings, #D6453F failure dots. Always pair a status dot with its state word. No purple, teal, gradients, glow, nested cards or invented copy. Modest 10/6/4 dp corners, flat grouped rows, generous touch areas. Reserve a full-bleed solid #C43C36 final row at the very bottom, about 144 pixels tall, white square stop icon and bold white “Stop” centered. Nothing below it. Only Stop is solid red.

Devices roster. Title “Devices”. Two clearly separated list groups. “In this run” contains “The Handy” / “Bluetooth” / green dot “Playing” and “Vibrator” / “Intiface” / green dot “Playing”. “Not in this run” contains “Coyote 3.0” / “Bluetooth” / neutral ring “Needs setup” / blue action “Set up”. Three rows total, text only. Below is the quiet note “Connecting never starts motion.” and outlined action “Add a device”. Bottom horizontal navigation “Chat”, “Devices”, “Settings”, Devices selected. Full-width Stop last.
```

</details>

<details>
<summary>04-settings.png</summary>

```text
Create one 1024×1536 portrait PNG, a flat edge-to-edge Android UI concept. UI only. No handset frame, people, bodies, physical devices, device illustrations, photos, suggestive imagery, poster titles or annotations. Render the quoted product wording exactly in large readable Roboto type. Restrained near-black Graphite Deck: background #101113, surface #1A1C1F, text #F2F2EF, secondary #B8BCC2, hairline #393C41. Interactive accent #5B9DD9; #287D42 only for motion-start actions, #4FC06D for OK/running dots, #D8B66A warnings, #D6453F failure dots. Always pair a status dot with its state word. No purple, teal, gradients, glow, nested cards or invented copy. Modest 10/6/4 dp corners, flat grouped rows, generous touch areas. Reserve a full-bleed solid #C43C36 final row at the very bottom, about 144 pixels tall, white square stop icon and bold white “Stop” centered. Nothing below it. Only Stop is solid red.

The compact Settings sheet over a dimmed chat, with only the header “Evening” and a little transcript visible at the top. Single opaque surface starts mid-screen and ends flush above the undimmed full-width Stop. Sheet heading “Settings”, trailing close icon. Six simple label/value rows with hairline dividers and chevrons: “Motion mode” / “Layered”; “Style” / “Balanced”; “Autopilot” / “Auto timing”; “Persona” / “Companion”; “Chat model” / “Terra”; “Memory” / “Off”. A final text link “App settings”. No tabs or second menu, no extra explanation. Give every row readable type.
```

</details>

<details>
<summary>05-lockscreen.png</summary>

```text
Create one 1024×1536 portrait PNG, a flat edge-to-edge Android UI concept. UI only. No handset frame, people, bodies, physical devices, device illustrations, photos, suggestive imagery, poster titles or annotations. Render the quoted product wording exactly in large readable Roboto type. Restrained near-black Graphite Deck: background #101113, surface #1A1C1F, text #F2F2EF, secondary #B8BCC2, hairline #393C41. Interactive accent #5B9DD9; #287D42 only for motion-start actions, #4FC06D for OK/running dots, #D8B66A warnings, #D6453F failure dots. Always pair a status dot with its state word. No purple, teal, gradients, glow, nested cards or invented copy. Modest 10/6/4 dp corners, flat grouped rows, generous touch areas. Reserve a full-bleed solid #C43C36 final row at the very bottom, about 144 pixels tall, white square stop icon and bold white “Stop” centered. Nothing below it. Only Stop is solid red.

Neutral Android system styling overrides app chrome for this image. Show a single continuous, sparse locked Android shade in neutral grayscale, with clock “21:42”, a small lock glyph, and an unlit gray Quick Settings tile “Stop motion” near the top. Show a public notification headed only “MagicHandy · Active” in the lower area. Extend its action “Stop” as the full-width red last row specified above, representing the notification action, exactly once. No additional app footer, bottom tabs, wallpaper image, device names, message previews, pace, Autopilot detail, Bluetooth tile, provider names or second panel. Do not imply that the tile is a start or a toggle.
```

</details>

<details>
<summary>06-choice.png</summary>

```text
Create one 1024×1536 portrait PNG, a flat edge-to-edge Android UI concept. UI only. No handset frame, people, bodies, physical devices, device illustrations, photos, suggestive imagery, poster titles or annotations. Render the quoted product wording exactly in large readable Roboto type. Restrained near-black Graphite Deck: background #101113, surface #1A1C1F, text #F2F2EF, secondary #B8BCC2, hairline #393C41. Interactive accent #5B9DD9; #287D42 only for motion-start actions, #4FC06D for OK/running dots, #D8B66A warnings, #D6453F failure dots. Always pair a status dot with its state word. No purple, teal, gradients, glow, nested cards or invented copy. Modest 10/6/4 dp corners, flat grouped rows, generous touch areas. Reserve a full-bleed solid #C43C36 final row at the very bottom, about 144 pixels tall, white square stop icon and bold white “Stop” centered. Nothing below it. Only Stop is solid red.

A safety recovery subpage, single sober reading column. Heading “Run interrupted”. First row “VacuGlide” with amber dot “Stop unconfirmed”. Put “It may still be running at its last speed. Switch it off at the device.” prominently beneath. Second row “The Handy” with amber dot “Stop pending” and “Waiting for a reply.” A plain line “Autopilot is off.” Low in the screen use the text action “Back to chat”. No tabs, countdown, automatic restart, illustrations or dashboard. Only the permanent full-width Stop at the bottom is solid red.
```

</details>

## The ten changes I would make first

1. **Move Stop ahead of every queue.** Build the epoch fence, per-owner deadlines and honest receipts first. Make a late planner result and a stalled cloud write race a second Stop in the same fixture.
2. **Arm recovery before the first command.** Use the persistent-speed simulator to kill the app at each journal boundary. Require local switch-off guidance and deliberate restart before putting a real VacuGlide through the same test.
3. **Settle the Handy/Intiface exception.** Recommend withdrawing that route, retaining several Handys and Intiface vibrators. Record the decision against desktop parity before building discovery and acceptance tests for it.
4. **Pin the port and prove one complete path.** Stand up three Kotlin modules, export synthetic fixtures, and take a real Handy and one Terra turn from connection through Stop. Resolve the supported OAuth redirect in M0.
5. **Build the phone frame once.** Two tabs, one compact Settings action, one Live entry and one persistent Stop. Test with a sheet, keyboard, 200% type, TalkBack and the system Stop surfaces.
6. **Reduce live controls to the actions a person can distinguish quickly.** Preserve Pause motion, explicit Resume and separate planner controls. Defer Edge, remove the volume shortcut and put requested versus commanded values in expandable Recent changes.
7. **Run Autopilot with the screen locked.** Keep check-ins as one choice and buildup as an optional structured target. Exercise network-only interruption separately from device failure, including a planner without chat context.
8. **Protect LAN model quality while cutting setup.** Ship the address-and-test flow, three capability labels, automatic no-reasoning selection and both paired-endpoint contracts. Require E4B and 12B Layered evaluations.
9. **Unify device setup without hiding family-specific risk.** Verify named run membership, pre-connection Coyote acknowledgement, Ultra health checks, VacuGlide check-in delivery and recovery after lost connectivity on the hardware.
10. **Freeze the remaining presentation scope.** Use built-in personas, three palettes and all five languages. Check the same small set of routes, redacted export and measured phone budgets before signing the v1 APK.
