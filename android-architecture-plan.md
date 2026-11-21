# MagicHandy Mobile: Android architecture plan

- **Date:** 2026-11-18
- **Code reviewed:** MagicHandy `main` (`bafc7ec4`, alpha.88) plus the head of PR #374 (`9a4c36cf`), which also contains PR #373.
- **Threads reviewed:**
  - Claude: "Design language templates for UI redesign" (2026-10-16 to 2026-11-12: Autopilot, remote/Edge, chat-model settings, vibrator, Coyote, OpenRouter, roleplay, Autoblow/VacuGlide) and "MagicHandy app branding" (2026-11-12 to 2026-11-14).
  - Codex: "Review architecture and motion performance" (2026-11-17 to 2026-11-18, PRs #373/#374, default-model prompt work, llama.cpp findings) and "Branding handoff" (2026-11-13 to 2026-11-14).
  - Both Discord threads were excluded.

## 1. Decision

Build a **native Kotlin app**. It has no Go, no local server and no WebView.

The desktop's Go code is the **starting point, not a spec to track**. The mobile app starts from one desktop snapshot (alpha.88 plus PRs #373/#374), then optimizes for mobile on its own schedule. Two mechanisms keep that safe and cheap:

- **One-time port verification:** fixtures exported from the snapshot prove the port is faithful where it matters most:
  - motion-engine frames and Stop behaviour;
  - device wire bytes;
  - chat requests and response validation.
- **Occasional parity by code comparison:** a parity map records which Go files each Kotlin file came from, and at which desktop commit. A parity pass diffs the desktop since that commit and ports only the chat changes worth having (§7).

Key decisions:

- **Languages:**
  - Kotlin for everything: UI, motion engine, chat, device drivers, Bluetooth and storage.
  - **No Rust.**
  - **C++ only later**, for on-device inference runtimes, running in its own process.
- **Scope (v1, decided 2026-11-18):**
  - chat and **Autopilot**, which is essential;
  - the motion engine and Stop;
  - **the same devices as the desktop**: The Handy over Bluetooth, Cloud or Intiface (several Handys too), Intiface vibrators, Coyote 3.0, Autoblow Ultra and VacuGlide;
  - models from **ChatGPT, OpenRouter, and servers on the local network** (Ollama and llama.cpp at least).

  Video, the pattern library, Labs, voice, accounts, remote access and desktop setup are dropped. On-device inference comes after v1. Further simplifications are in §15.
- **Order:** v1 ships whole; milestones M0–M4 (§11) only set the order of work.
- **Designs:** five directions and the robust flows are sketched on a design canvas (§14), and the system-level designs are in [docs/mobile-system-design.md](docs/mobile-system-design.md).
- **Priority:** mobile first. Battery, background survival, network resilience, latency and a one-handed UI come before matching desktop features (§3.3).

## 2. What the review found

| Finding | Evidence | Consequence |
|---|---|---|
| The behaviour worth porting is large and carefully tuned | The chat and device domain is about **63k lines of Go** plus about **65k lines of tests**. About **15k more** lines of orchestration sit inside `httpapi` handlers. | A port is a big job. Parity needs automated checks; reading the code is not enough. |
| It still changes quickly | Chat, motion, device and LLM code had **56 commits (+13.4k/−2.1k production lines) in the last 30 days** and 116 commits (+31.7k) in 60 days. Examples: #373 (queue coalescing, Stop acknowledgements, settings revisions) and #374 (default-model prompts, pace edits, repair feedback). | Port from one snapshot, then pull selected changes during occasional parity passes. Staying in sync isn't required. |
| Quality is measured on the Go stack | There are 85 live-eval test files, a 339-turn replay of installed chats and a 135-case motion atlas with byte-identical outputs. | Port prompts, validation and frames exactly at first, so the desktop's results are a valid baseline. After that, mobile tunes and re-measures on its own. |
| The device boundary is already clean | `ble.Link`/`ble.Connector` form a byte-link interface with allowlisted GATT profiles. Drivers (`handyble`, `estim`) are separate from radios, and every owner sits behind `transport.Transport`. | The port maps one to one. Android GATT replaces the `bluetooth-worker` process. |
| Orchestration is tangled into HTTP | Chat turns, motion dispatch, device connections and Autopilot live in `httpapi.Server` (30.8k lines). Only `chatapp` and `deviceapp` have been extracted so far. | Reusing Go without the server would require extracting application services first. |
| Go does run on Android | The whole core, including `cmd/magichandy`, cross-compiles for android/arm64 with `CGO_ENABLED=0`. However, Go's own resolver cannot do DNS on Android without cgo (`net/conf.go`, Go issue 10714). | Keeping Go means gomobile, cgo and the NDK, plus an ADR exception to the pure-Go rule. |
| Safety invariants to preserve | <ul><li>There is one engine and one motion path.</li><li>Stop always works.</li><li>Stop epochs fence late commands.</li><li>An uncertain write triggers a recovery Stop.</li><li>goleak gates protect the lifecycle.</li><li>Risk R41: a VacuGlide keeps its last speed if the host dies.</li></ul> | Mobile must preserve these invariants and handle Android killing the process, which happens routinely on phones. |

Note: the existing *phone remote* only controls a desktop. This app replaces the desktop rather than building on the phone remote.

## 3. Language options

### 3.1 Comparison

| Option | What you write | For | Against | Verdict |
|---|---|---|---|---|
| **A. Kotlin only** | Roughly **35–40k lines** of Kotlin for the MVP. About **50k** with all devices and Autopilot. Tests add about as much again. | <ul><li>One language, toolchain, runtime and debugger.</li><li>Idiomatic Android: Bluetooth, services and Compose.</li><li>No cgo.</li><li>The desktop is untouched.</li><li>On-device inference plugs in directly.</li></ul> | <ul><li>A second implementation of tuned behaviour.</li><li>Desktop improvements arrive only through occasional parity passes, which is acceptable here.</li><li>The engine needs hardware re-validation.</li></ul> | **Chosen** |
| B. Kotlin + Rust core | The same port in Rust, plus uniffi bindings | <ul><li>Memory-safe and GC-free.</li><li>Could later back iOS.</li></ul> | <ul><li>Same port cost.</li><li>Adds a second toolchain (cargo-ndk, uniffi) and an FFI boundary—the exact complexity this plan avoids.</li><li>Android Bluetooth stays in Kotlin anyway.</li><li>GC pauses don't matter at these timings (§3.2).</li></ul> | No |
| C. Kotlin + C++ core | The same port in C++ | Inference runtimes are already C++. | <ul><li>Memory-unsafe code in the safety-critical path.</li><li>Same port cost.</li><li>JNI everywhere.</li></ul> | No for the core; yes for inference only (§9.2) |
| D. Kotlin UI + embedded Go (gomobile, no server) | About 11k lines of Kotlin, plus extracting about 15k lines from `httpapi` and a small binding package | <ul><li>Zero behaviour drift.</li><li>Desktop fixes arrive automatically.</li><li>Live evals apply unchanged.</li></ul> | <ul><li>gomobile, NDK and cgo, with an ADR exception.</li><li>A JNI/JSON boundary and two runtimes.</li><li>A large desktop refactor must come first.</li></ul> | Not needed: mobile doesn't have to track the desktop |

### 3.2 Why Kotlin is fast enough

- **Handy buffering:** the Handy's HSP stream holds at least 1.5 s of points over Bluetooth.
- **Coyote cadence:** the Coyote needs one packet every 100 ms.
- **GC pauses:** ART's concurrent garbage collector pauses for only a few milliseconds, well inside both margins.
- **Go concepts map directly:**
  - goroutines and `context.Context` become coroutines with structured cancellation;
  - generation counters become `AtomicLong` epochs;
  - channels become `Channel` and `Flow`.
- **Output thread:** device output runs on a dedicated high-priority thread. M0 measures its jitter.

### 3.3 Mobile-first priorities

Because the mobile app doesn't have to track the desktop, it can be optimized for phones once the port is verified:

- **Battery and heat:**
  - output is event-driven, with no polling;
  - motion observations are produced only while a screen shows them;
  - a wake lock is held only during runs.
- **Latency over cellular:**
  - the same prompts and history windows as the desktop, so its evaluations carry over;
  - a warm HTTP/2 connection to the provider, and streamed replies;
  - motion starts as soon as a validated command arrives.

  Prompt length isn't the bottleneck. On the desktop, the compact and current prompts differ by about 450 tokens (2,600 against 3,065, installed-chat replay of 2026-11-18), and a shorter Layered prompt failed the desktop's review, passing 3 of 15 cases against 15 of 15 (2026-09-05). Any prompt change aimed at latency lands on the desktop first and reaches mobile through a parity pass.
- **Network changes:**
  - a switch between Wi-Fi and cellular mid-reply holds motion rather than guessing;
  - cloud device commands are fenced, and retried only where that is safe;
  - a connectivity watchdog covers cloud devices.
- **Background survival:** the foreground service keeps the core alive while a device is connected, a run is live or Autopilot is on. The UI can be recreated at any time and redraws from core state.
- **One-handed UI:**
  - two tabs and a Settings action in the bottom row;
  - a large, always-visible Stop with haptic confirmation;
  - speed and stroke controls on Live, one tap from chat or directly from the notification.
- **Small footprint:** no embedded web UI, no desktop-only features, and few dependencies (Compose, coroutines, OkHttp, kotlinx.serialization, Room).

## 4. Scope

**In scope:**

| Area | Included |
|---|---|
| Chat | <ul><li>Sessions with streaming replies.</li><li>Motion modes Layered, Simple and Off (Tempo and Off when a VacuGlide leads), chosen in the compact settings sheet.</li><li>Deterministic "stop" fast path.</li><li>Refusal retry and response repair.</li><li>Personas: the built-in presets. Archive import is proposed for after v1 (§12).</li><li>Long-term memory and conversation-history windows.</li><li>Now playing.</li><li>Autopilot: timing, check-ins and an optional finish target.</li></ul> |
| Live control | <ul><li>Stop, Pause, Resume and Edge.</li><li>Speed, stroke window and direction limits.</li></ul> |
| Devices | See §8. |
| Model connections | <ul><li>ChatGPT sign-in.</li><li>OpenRouter.</li><li>Servers on the local network: Ollama and llama.cpp, plus other OpenAI-compatible servers.</li><li>All capability contracts, so Gemma-class models on LAN servers get their tuned Simple and Intermediate contracts. The UI shows three levels; Advanced selects its no-reasoning contract automatically when reasoning is off for a connection.</li></ul> |
| Settings | <ul><li>Device and model limits.</li><li>Language: en, es, pt-BR, ja, zh-Hans.</li><li>Palettes: a minimal dark set for phones, Steel Azure, OLED black and High contrast (§12, decision 3).</li><li>A redacted diagnostics export.</li></ul> |

**Out of scope:**

- video, funscripts and media;
- the pattern library: its motion mode, the curated patterns, editing, programs, import and the Decisions API;
- the original Creative mode, which the desktop already retired;
- controller leases, read-only watchers and the notification center;
- Labs and voice (speech recognition and text-to-speech);
- accounts, HTTPS and Public access;
- the phone remote;
- desktop setup, managed llama.cpp and model downloads;
- the installer and updater;
- the persona editor, lore and portraits;
- Freestyle and preset modes;
- a nearby-server scan for LAN models (after v1; servers are added by address).

## 5. Architecture

### 5.1 Modules

The app uses three Gradle modules, adopted from the second Astra review (§17). Package boundaries and an import test enforce the rules that matter, so a routine change—such as adding a field to the Stop receipt—touches one module instead of several.

| Module | Owns | Depends on | Boundary its tests hold |
|---|---|---|---|
| `:core` (pure Kotlin) | <ul><li>Session orchestration that lived in the desktop's `httpapi`: chat turns, `MotionControl` (Stop, Pause, Resume, Edge), the device manager, model connections and revisioned settings.</li><li>Chat contracts, validation, repair, personas, memory and history windows.</li><li>The Autopilot planner and timing (ADR 0072).</li><li>The motion engine: targets → plans → sampler and sanitizer → frames, with retargeting, Stop epochs, fan-out, the vibration envelope and the tempo channel.</li><li>A clock, and ports for transports, providers and storage.</li></ul> | nothing | <ul><li>Chat and Autopilot emit semantic targets only.</li><li>An import test blocks their packages from importing the engine's output side, `:transport` or Android.</li><li>Only the engine dispatches device output, including manual tests.</li></ul> |
| `:transport` (JVM Kotlin) | <ul><li>Device codecs and sessions: the Handy Bluetooth byte link and Cloud HSP, Coyote packets, Buttplug over WebSocket, and the Autoblow cloud.</li><li>Provider clients for ChatGPT Responses, OpenRouter, OpenAI-compatible servers and Ollama, with SSE streaming, prompt budgets and redacted request types.</li></ul> | `:core` ports | <ul><li>Drivers map engine frames to wire output; providers return text for validation.</li><li>Driver and provider packages stay independent.</li><li>Exact bytes and cancellation are tested without Android.</li></ul> |
| `:app` (Android) | <ul><li>Compose screens and sheets, and navigation.</li><li>The foreground service, the notification and Quick Settings tile, and the network watcher.</li><li>Android GATT, Room, the Keystore vault and Custom Tab sign-in.</li><li>Debug simulators.</li><li>A hand-written container that wires one process-wide engine (no DI framework).</li></ul> | `:core`, `:transport` | <ul><li>The UI observes immutable `StateFlow` and sends intents.</li><li>No UI, receiver or storage code builds a motion payload.</li></ul> |

- **Seams inside `:app`:** `ble`, `auth`, `store` and `service` are packages. One becomes its own module only when it gains a second consumer or build times call for it.
- **Fixture tests** (§7) run as JVM tests in `:core` and `:transport`, so there is no separate conformance module.
- **Not built:** a DI framework, a placeholder inference module, Go, Rust, JNI, a WebView or a local server. On-device inference (§9.2) adds its own module and process when that work starts.

### 5.2 Rules (ported from AGENTS.md §1 and §4)

- **Pure Kotlin core:** `:core` and `:transport` use no `android.*`. They run in fast JVM tests and against the port-verification fixtures, and stay compatible with Kotlin Multiplatform if iOS ever matters.
- **Import boundaries:**
  - The chat and Autopilot packages in `:core` must not import the engine's output side, `:transport` or Android.
  - Semantic clients emit semantic targets only.
  - An architecture test (Konsist or ArchUnit) enforces this at package level, mirroring the desktop's depguard rules; the three module edges back it up.
- **One engine:** a single engine instance owns all output, and nothing bypasses it. The UI never builds device payloads.
- **Stop:** `MagicHandyCore.emergencyStop()` is independent of chat turns, settings writes and storage.
  - It advances the epoch first.
  - Every transport call carries its epoch and is rejected if stale.
  - An uncertain mutating write triggers a recovery Stop.
  - Every Stop surface calls it directly.
- **Lifecycle tests replace goleak:** use `kotlinx-coroutines-test` with a virtual clock. After Stop, the engine scope has no live children and a fake transport records zero further commands.

### 5.3 Concurrency mapping

| Go | Kotlin |
|---|---|
| goroutine + `context.Context` | coroutine + `Job` (structured concurrency) |
| mutex-guarded engine + generations | single-threaded engine dispatcher (`limitedParallelism(1)`) + atomic epochs |
| channels | `Channel` / `Flow` |
| `TestMain` goleak gate | coroutine-test scope assertions after Stop |
| monotonic clock | injected `Clock`: `SystemClock.elapsedRealtimeNanos()` on device, virtual in tests |

### 5.4 Persistence and secrets

Each store matches what recovery needs from it (from the second Astra review, §17).

| Data | Store | Rules |
|---|---|---|
| Chats and their messages, memory, named model connections, role bindings, revisioned settings | One Room (SQLite) database | <ul><li>One serialized repository applies the dirty-field patch from #373 against the last known revision, so a stale patch is merged by field or rejected.</li><li>A turn and its memory changes commit in one transaction.</li><li>Built-in personas and translations ship as packaged resources.</li><li>No second settings store, such as DataStore.</li><li>The schema is versioned, with upgrade tests.</li></ul> |
| The current run and any unresolved Stop | The run journal: one small versioned file, replaced atomically | <ul><li>Holds the run id, a stable reference to each device and its transport, the last intended command or state, whether Autopilot is on, and a timestamp.</li><li>Armed durably **before the first motion-capable write**, including manual starts and Test motor. If arming fails, the app refuses the start: no journal, no start.</li><li>Updated at state boundaries, never at frame rate. Stop never waits for it.</li><li>Cleared only once every device in it is reconciled, never by a clean activity exit.</li></ul> |
| Diagnostics, receipts and run history | A capped, redacted event ring in a rotating file; the current run's history stays in memory | <ul><li>Written off the output thread.</li><li>No chat text and no per-packet records.</li><li>Leaves the phone only when you share it.</li></ul> |
| Secrets: the Handy connection key, Autoblow tokens, the OpenRouter key and ChatGPT tokens | A dedicated file, encrypted with AES-GCM under an Android Keystore key | <ul><li>The database and journal hold only opaque handles.</li><li>If the key becomes unusable, the app asks you to sign in again.</li><li>Never in logs, recovery notices or exports. The `SafeCommand`/`SafeCommandResult` redaction rules are ported.</li></ul> |

**Backup:** everything lives in `noBackupFilesDir`, with `allowBackup=false` and data-extraction rules that exclude everything.

### 5.5 Maintainability and clarity

The port will be read far more often than it is written: in parity passes against the desktop, in reviews of the safety invariants, and by the agents that work on it. These rules keep it easy to follow and cheap to change. Tools enforce what they can, and review covers the rest. `AGENTS.md` at the repo root points here.

**Structure**

- One concept per file, named after its main type, such as `StopReceipt.kt`.
- Split a file at about 400 lines, and a function at about 40 lines or three levels of nesting.
- Organize packages by feature (`core.motion`, `core.chat`, `ui.live`), never `util`, `common` or `helpers`.
- Keep visibility as narrow as possible. `:core` and `:transport` use Kotlin's explicit API mode, so every public declaration is a deliberate choice.
- Dependencies point one way: `:app` depends on `:transport` and `:core`, and `:transport` on `:core`. Packages have no cycles.

**Names**

- Use the domain's words, and the desktop's names for ported code (§6), so a parity pass can find the Go source.
- Spell words out. The only abbreviations are established ones: BLE, GATT, HSP, RPC, SSE, MTU, LAN and UI.
- Booleans read as questions (`isConnected`), functions as actions (`armJournal()`) and events as facts (`StopConfirmed`).
- Put units in types: `Duration` for time, and value classes for percentages and ids (`Percent`, `DeviceId`, `Epoch`). Where a raw number crosses a boundary, its name carries the unit (`timeoutMillis`).

**Types, state and errors**

- Model state as immutable data and sealed hierarchies. A `when` over a sealed type lists every case, with no `else`.
- No `!!`, no nullable booleans, and no states held in strings. `lateinit` is only for Android framework injection in `:app`.
- Return expected outcomes as values (such as `StopOutcome.Unconfirmed`), not exceptions. Reserve exceptions for programming errors and I/O failures, and map each to an outcome where you catch it.
- Never swallow an exception. Rethrow `CancellationException`, and log anything else with its cause, redacted.
- Secrets travel in a type whose `toString()` is redacted, never as a bare `String`.
- Every failure the user sees names the cause and gives one next step, using a notice from the catalog.

**Concurrency**

- Structured concurrency only: every coroutine has an owning scope. No `GlobalScope`, and no `runBlocking` outside tests.
- Inject dispatchers and the `Clock`. `:core` never reads the wall clock or picks a dispatcher itself.
- Mutable shared state has one owner: the engine's single-threaded dispatcher or a `StateFlow`. No ad-hoc locks.

**Comments and documentation**

- KDoc every public declaration with its purpose, invariants and units. Don't restate the code.
- Comment on the reasons code can't show: a safety invariant, an intentional desktop behaviour or a hardware workaround. Cite the ADR, risk or issue.
- A ported file starts with its source, for example `// Ported from internal/motion/sampler.go at bafc7ec4.` `PARITY.md` records the same mapping.
- No commented-out code. Every `TODO` names its milestone: `TODO(M2): …`.
- When you change behaviour, update the document that describes it (this plan, the system design or an ADR) in the same change.

**Formatting**

- **Kotlin and Gradle scripts:** ktfmt in its Kotlin-language style, run through Spotless. `./gradlew spotlessApply` formats, and `spotlessCheck` fails the build on drift. Lines are at most 100 columns, with 4-space indents, trailing commas and no wildcard imports. `.editorconfig` gives editors the same settings.
- **Static checks:** Android Lint on `:app` with warnings treated as errors, and the import test from §5.2. detekt will join once a release supports this Kotlin version. Every suppression must state why.
- **Resources:** user-visible text lives only in `strings.xml`, grouped by screen, with Android Studio's default XML formatting.
- **Markdown** (this plan, the system design, ADRs and `PARITY.md`):
  - one `#` title per file, sentence-case headings, and numbered sections in long documents;
  - short paragraphs, lists for three or more parallel items, numbered lists for steps, and tables for comparisons;
  - code, commands and identifiers in backticks, and a language tag on every fenced block;
  - absolute dates, such as 2026-11-19, relative links inside the repo, and one line per paragraph, with no hard wrapping.

**Compose**

- Each route has a thin `XRoute` that collects state and forwards intents, and a stateless `XScreen(state, onIntent)` that can be previewed.
- Composables take state and lambdas, never the core or a view model. `Modifier` is the first optional parameter.
- Colours, type, spacing and shapes come only from the theme's tokens: no raw hex or dp values in screens.
- Every icon-only control has a content description, and every target is at least 48 dp.
- Every screen has previews of its main states (idle, playing and failure) in Steel Azure and OLED black, plus one at 200% font scale. A screenshot test compares each preview with its recorded image.

**Tests**

- Pair every behaviour change with a test, and start every bug fix with a failing test.
- Test names state the behaviour, in backticks: `` `Stop advances the epoch before any transport write` ``.
- Prefer fakes behind the real interfaces to mocks. Use virtual time, never real delays.
- Port-verification fixtures are read-only goldens. A deliberate change replaces the golden and says why.

**Dependencies and changes**

- Versions live only in `gradle/libs.versions.toml`, with no dynamic versions. A new library needs a stated reason; the default is the platform or the existing set (§3.3).
- Keep changes small and focused, and leave the build formatted, checked and tested.
- Never change safety-critical code without a test that proves its invariant: Stop, the epoch fence, admission, the run journal and every transport's Stop path.

## 6. Porting map

The Phase column below is superseded by the v1 milestones in §11: every row except "Not ported" is in v1.

| Desktop (Go) | Mobile (Kotlin) | How | Phase |
|---|---|---|---|
| `internal/motion` (14.8k lines; drop freestyle, lab, media rounding, review atlas and flow experiments) | `:core` `motion` | Faithful port that must reproduce the motion-atlas frames | 1 |
| `transport` types, fanout, leased, `cloud_client`/`cloud` (Handy Cloud API v3) | `:core` ports and fan-out; `:transport` `handy` | Faithful port, checked against wire goldens | 1 |
| `handyble`, `transport/handy_bluetooth_transport` and `native_bluetooth`, `ble` link/profiles | `:transport` `handy` + `:app` `ble` | Codec and session ported faithfully; the worker IPC is replaced by GATT | 1 |
| `chat` hosted contracts: Layered, Advanced, Advanced (no reasoning), chat-only; prompts, localization, schemas, validation, repair, history, motion and output context | `:core` `chat` | Faithful port, checked against request and validation goldens | 1 |
| `persona`, `memory`, `chatapp` | `:core` `chat`, `session` | Faithful port | 1 |
| `llm`: `openai_responses`, `compatible`, `ollama`, `openrouter_reasoning`, `cloud`, `prompt_budget` | `:transport` `providers` | Faithful port, checked against request goldens | 1 |
| `openaiauth` | `:app` `auth` + `:transport` `providers` | Same flow, with the Keystore replacing DPAPI or owner-only files | 1 |
| `httpapi`: `chat*`, `motion`, `stop_admission`, `handy_connections*`, `playback_devices`, `tempo`, `edge` | `:core` `session` | Port the behaviour, using the desktop's HTTP tests as the spec | 1–2 |
| `deviceapp` | `:core` `session` (`DeviceManager`) | Faithful port | 1 |
| `config`, `store` (subsets) | `:core` `settings` + `:app` `store` | Remodel while keeping the semantics | 1 |
| `patterns`, curated pattern content, library contracts, Decisions API | — | **Not ported** (owner decision, 2026-11-18) | — |
| `modes` Autopilot, `chat/autopilot*`, `httpapi` `cloud_planning`/`cloud_autopilot` | `:core` `autopilot` | Faithful port | 2 |
| `transport/intiface*`, `vibration` | `:transport` `buttplug`; the vibration envelope in `:core` `motion` | Faithful port; WebSocket via OkHttp | 2 |
| `estim`, `transport/coyote*` | `:transport` `coyote` + `:app` `ble` | Faithful port | 2 |
| `autoblow`, `transport/autoblow*` | `:transport` `autoblow` | Ultra in Phase 2, VacuGlide in Phase 3 | 2–3 |
| `chat` local contracts: Intermediate, Simple/Creative v2, local motion plan | `:core` `chat` | Faithful port; needed in v1 for Gemma-class models on LAN servers | M3 |
| **Not ported** | | <ul><li>The HTTP/SSE layer.</li><li>accounts, auth, netaccess and remote.</li><li>media, voice, labs, setup and update.</li><li>managed llama.cpp and model downloads.</li><li>console and installer.</li><li>browser Bluetooth and `bluetooth-worker`.</li></ul> | |

Port faithfully first, keeping Go's type and function names where practical. That makes the desktop's eval results a valid baseline and keeps later code comparisons easy. Optimize for mobile once the port is verified.

## 7. Port verification and parity

### 7.1 One-time verification fixtures

A small test-only exporter in this repository (`tools/port-fixtures`), built inside the desktop snapshot, writes these. It uses synthetic data only, never private transcripts.

1. **Motion.** The 135 motion-atlas cases as transport-neutral frames. Scripted traces on a virtual clock add:
   - coalescing of rapid edits;
   - Pause and Resume applying saved limits;
   - Stop epochs;
   - recovery Stop.
2. **Wire.** Frames mapped to exact bytes or requests for:
   - Handy Bluetooth RPC/HSP;
   - Handy Cloud REST;
   - Coyote packets;
   - Buttplug messages;
   - Autoblow calls.
3. **Chat requests.** The exact request bodies for hosted Advanced across motion modes, locales and persona presets: messages, JSON schema, and sampling and reasoning parameters. `chat.ComposePrompt` already produces these.
4. **Response handling.** Synthetic model output and context go in. The expected result is an accepted semantic command, or a rejection plus the repair prompt.
5. **Content.**
   - prompt sets and persona presets;
   - the notice catalog;
   - translations for the 5 locales, from which `strings.xml` is generated;
   - the brand mark and star.

These fixtures prove the initial port and then belong to the mobile repo. When mobile deliberately changes behaviour—such as a phone-specific notice—it updates its own fixtures and re-measures. Prompts stay aligned with the desktop (§3.3).

### 7.2 Parity by code comparison

`PARITY.md` maps each Kotlin file to the Go files it came from and the desktop commit it matches.

A parity pass works like this:

1. List `git log <commit>..main` for the mapped Go paths.
2. Review the chat changes (prompts, validation, repair, history) and port the ones worth having.
3. Rerun the affected fixtures and a short live check against Terra.
4. Record the new commit.

Motion and device changes are ported only when they fix a safety bug or matter on mobile.

## 8. Devices on Android

| Device | Path on the phone | Notes | Milestone |
|---|---|---|---|
| The Handy (fw4, API v3) | Handy Cloud API over HTTPS | Same as desktop. If the app dies, the Handy pauses when its HSP buffer runs out. | M1 |
| The Handy | Native Bluetooth via Android GATT (`handy4` profile) | <ul><li>Request MTU 517; payloads up to 512 bytes.</li><li>GATT allows one operation at a time, so writes go through a queue.</li><li>Stop and teardown use RPC 715.</li><li>Uncertain writes are never retried; they retire the link, as on desktop.</li></ul> | M1 |
| The Handy and other strokers via Intiface | Buttplug over WebSocket to Intiface Central | Kept, as on desktop (§12, decision 1): The Handy, or any other linear actuator, with its warning that Intiface can acknowledge Stop without delivering it. | M3 |
| Several Handys | Any mix of the above | Named connections and linked sessions, as on desktop (ADR 0054). No promise of physical sync. | M3 |
| Vibrators | Buttplug over WebSocket to Intiface Central on the phone or the LAN | <ul><li>The Intiface app must be running; verify it survives in the background.</li><li>Model-controlled level per ADR 0061.</li></ul> | M3 |
| DG-LAB Coyote 3.0 | Android GATT (`coyote3` profile), write without response | <ul><li>100 ms packet cadence.</li><li>Output stops when packets stop.</li><li>Explicit scan, with no fallback to another device.</li><li>Its safety notes are acknowledged before the first connection.</li></ul> | M3 |
| Autoblow Ultra | Autoblow cloud | One command per stroke leg. If the app dies, it finishes its last move. | M3 |
| VacuGlide | Autoblow cloud | R41: it keeps its last speed if the host dies, and Android kills apps routinely. It ships with the phone safeguards in §10, drawn on the canvas. | M4 |

**Bluetooth permissions:** `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT` and `POST_NOTIFICATIONS`. Emulators have no Bluetooth, so hardware tests run on your Pixel (wireless ADB is already set up).

## 9. Inference

### 9.1 v1 providers

- **ChatGPT sign-in:** GPT-6.1 Terra at Advanced with Low reasoning, the desktop default.
- **OpenRouter:** for example DeepSeek V4.1 Flash at Advanced (no reasoning), per ADR 0065.
- **Servers on the local network:**
  - Ollama and llama.cpp (`llama-server`) first; any other OpenAI-compatible server uses the same path.
  - You add a server by its address. The app detects the type, fetches the models and suggests a capability level from the model name, which you can change. There is no nearby-server scan in v1.
  - Gemma 4 E4B and 12B need the Simple and Intermediate contracts, so those are ported in v1 (M3).
- **Cleartext:** local servers usually speak plain HTTP. The app allows it only for private, link-local and loopback addresses, and labels such connections "Not encrypted".
- **Sign-in on Android:** ChatGPT sign-in uses the agent OAuth flow with PKCE and a loopback redirect. On Android it opens in a Custom Tab while the app listens briefly on a one-shot loopback port. Because that listener is the one exception to "no local server", it needs your approval (§12). M0 first checks for a provider-supported app redirect, then confirms a real return, a cancellation and a token refresh.

### 9.2 On-device later (Qualcomm, Tensor)

**Design it now:**

- `LlmProvider` already abstracts the provider. Add a `LocalModelRuntime` API in its own module when that work starts; v1 ships no placeholder for it.
- Place runtimes in separate modules **in a separate process**, behind a bound service. That way, an out-of-memory error or native crash cannot kill the process that owns motion and Stop. A runtime failure surfaces as "motion held".

| Runtime | Hardware | Fit |
|---|---|---|
| llama.cpp (C++/JNI) | <ul><li>CPU on every phone.</li><li>OpenCL backend for Adreno (Snapdragon).</li><li>Hexagon NPU backend (Snapdragon, experimental).</li><li>Vulkan for the GPU in Tensor G1–G4 (Mali); Tensor G5 moved to a PowerVR GPU.</li></ul> | <ul><li>Uses the same Gemma 4 GGUF files and JSON-schema grammar as the desktop, so the default-model suites apply directly.</li><li>Pin the desktop's build (b11533): Codex found b12503's Flash Attention path corrupts E4B lookups.</li></ul> |
| Qualcomm AI Engine Direct / Genie (QNN) | Snapdragon NPU | <ul><li>Fastest on Snapdragon.</li><li>Models are precompiled per chip (AI Hub).</li><li>No grammar-constrained decoding, so it relies on validation and repair. It must pass the same suite.</li></ul> |
| LiteRT-LM (Google AI Edge) | CPU, GPU, and NPU where supported | <ul><li>Kotlin API, Gemma-first.</li><li>Check structured-output support and NPU access on Tensor.</li></ul> |
| Gemini Nano (AICore, ML Kit GenAI) | Tensor TPU | **Excluded:** its safety filters and Google's generative-AI usage policy block this app's content. |

**Gate:** a device probe plus a per-chip allowlist, like desktop Easy Setup. A runtime ships for a capability level (E4B for Simple, 12B for Intermediate) only after it meets the desktop default-model suite's thresholds within a latency budget. Local models need a phone with ample RAM (12 GB or more).

## 10. Android platform details

- **Foreground service** (type `connectedDevice`). It runs while any of these is true:
  - a device is connected;
  - a run is playing or paused;
  - Autopilot is on.

  The ongoing notification has one action, Stop, and tapping the notification opens Live. A chat-only reply, with no device involved, stays bound to the visible screen; if the app goes to the background mid-reply, the turn is cancelled with a notice (§17).
- **Stop surfaces**, all calling `emergencyStop()` directly:
  - the footer Stop, the last row of the app frame on every screen;
  - the notification action, including on the public lock screen;
  - a Quick Settings tile.

  The volume-key chord was dropped (§15).
- **Swipe-away** (`onTaskRemoved`): stop everything, then end the service.
- **Process death, per device:**
  - The Handy pauses when its buffer runs out.
  - The Coyote goes silent without packets.
  - Intiface Central stops devices when its client disconnects (verify).
  - The Ultra finishes its last move.
  - The VacuGlide keeps running.

  Before VacuGlide ships in v1, it therefore needs:
  - runs and motor tests that start only while the foreground service is up and the run journal is armed;
  - a connectivity watchdog that stops the run, alerts, and sends Stop again when the network returns;
  - a check-in while it runs (every 15 minutes by default, §12); if unanswered within a minute, the whole run stops;
  - prominent guidance to switch it off at the device whenever its Stop is unconfirmed.

  Record this as a new mobile risk R43, next to R44 for Android GATT variance across manufacturers.
- **Doze:** the foreground service keeps network and CPU access. Hold a wake lock only during runs.
- **SDK targets:**
  - minSdk 31 (Android 12, the modern Bluetooth permission model);
  - targetSdk 36;
  - arm64-v8a, plus x86_64 for emulator debug builds only;
  - check 16 KB page alignment once native code arrives.
- **Distribution:**
  - Signed APK on GitHub Releases with `SHA256SUMS`, plus a manual release check you run from App settings (no background polling).
  - Google Play is unlikely because of its sexual-content and AI-generated-content policies.
  - **GPL-3.0-only**, because the port is a derivative of the desktop code.
  - Onboarding includes an adults-only and safety acknowledgement.
- **Brand and design:**
  - The brand mark and star (ADR 0073/0074) become the adaptive and monochrome launcher icons.
  - The Graphite tokens become the Compose theme: steel-azure accent, green only for go, red only for Stop.

## 11. Milestones and exit criteria

v1 ships when M0–M4 are all done; the milestones only set the order of work.

| Milestone | Work | Exit |
|---|---|---|
| **M0: Fixtures and spike** | <ul><li>Desktop: export the port-verification fixtures from the snapshot (hosted Advanced requests, atlas frames, Handy wire fixtures, content).</li><li>Mobile: the Gradle skeleton with its three modules.</li><li>Port enough of the sampler to pass about 20 atlas cases.</li><li>Real Handy over GATT: connect, MTU, initialize, RPC 715 Stop.</li><li>ChatGPT sign-in and a text-only turn.</li><li>Foreground service with notification Stop.</li><li>Run-journal arming, and a simulated device that keeps its last speed.</li></ul> | Measure <ul><li>output-loop jitter at 100 ms;</li><li>Bluetooth write and Stop latency;</li><li>cold start, memory and APK size;</li><li>effort per atlas case.</li></ul> Prove <ul><li>the sign-in return;</li><li>the behaviour with notifications denied;</li><li>recovery after a kill between the journal write and the first command;</li><li>that the service type is allowed on the Pixel.</li></ul> Use these numbers to firm up the M1 estimate. |
| **M1: Engine, The Handy, hosted chat** | <ul><li>Full engine port.</li><li>Handy Cloud and Bluetooth.</li><li>Hosted Advanced chat with sessions, presets and memory.</li><li>ChatGPT and OpenRouter connections.</li><li>Compose UI and every Stop surface, with the per-device Stop receipt.</li><li>Keystore secrets.</li><li>A "Simulated devices" debug toggle so agents can review without hardware.</li></ul> | <ul><li>All M1 fixtures pass.</li><li>Handy acceptance per the desktop checklist: start, retarget, Pause/Resume limits, Stop acknowledgement, swipe-away, airplane mode.</li><li>A stalled transport never delays Stop for another device.</li><li>Text-only and motion chat on Terra.</li></ul> |
| **M2: Autopilot** | <ul><li>Hosted planner with auto timing (ADR 0072), check-ins and the optional finish target.</li><li>Background runs owned by the foreground service.</li><li>Recent changes on Live.</li><li>Autopilot's next change in the private notification.</li></ul> | <ul><li>30 minutes of Autopilot with the screen off and Doze active, with no stalls.</li><li>Stop works from the lock screen and the Quick Settings tile.</li><li>Losing only the model holds planning while a Bluetooth segment plays; losing a device stops the run.</li></ul> |
| **M3: LAN models and the other devices** | <ul><li>Ollama and llama.cpp servers added by address, with the Simple and Intermediate contracts.</li><li>Intiface vibrators, and The Handy via Intiface only if you keep it (§12).</li><li>Several Handys, the Coyote (safety notes before its first connection) and the Autoblow Ultra.</li><li>Linked sessions and Edge.</li></ul> | <ul><li>The default-model suites for E4B and 12B meet desktop thresholds over the LAN.</li><li>Per-device acceptance as on desktop.</li></ul> |
| **M4: VacuGlide and hardening** | <ul><li>VacuGlide with the phone safeguards.</li><li>The Run interrupted page.</li><li>Chaos tests: killing the process, airplane mode, revoking permissions, switching Wi-Fi to cellular.</li><li>Five locales, the palettes, accessibility and release packaging.</li></ul> | <ul><li>VacuGlide acceptance with forced process death and a missed check-in.</li><li>No unexplained states in chaos runs.</li></ul> |
| **After v1** | On-device inference for Qualcomm and Tensor (§9.2) | Each runtime passes the default-model suite for its level. |

## 12. Decisions and open questions

Decided on 2026-11-18:

- Autopilot is essential and ships in v1.
- v1 models are ChatGPT, OpenRouter, and servers on the local network (Ollama and llama.cpp at least).
- The app lives in a separate repo in this folder.
- Device support matches the desktop, VacuGlide included.
- The pattern library is not part of the app (§15).
- Settings that can wait go in one compact menu, with the motion mode behind its own menu (§14).

**The eight questions from the canvas's "Needs your decision" card**, all now settled:

1. **Intiface: full support, decided 2026-11-20.** The Handy, and any other linear actuator, connect through Intiface Central as on the desktop, alongside vibrators. A persistent caveat says Stop through the bridge can't be confirmed, and an acknowledgement from the bridge alone reports Stop as unconfirmed.
2. **Personas: editable in v1, decided 2026-11-20.** This replaces "only the built-in presets".
   - The built-in MagicHandy keeps its name and can't be deleted, but its personality is fully customizable, with Reset to default.
   - People can create, duplicate, delete and use their own personas, up to 200. Each has its own memory, and deleting one deletes its memory while chats keep their messages.
   - A persona is only a name, how explicit replies may be (Utility, Warm, Intimate or Explicit), a reaction style (Neutral, Playful, Tender, Teasing, Dominant or Submissive), a description of at most 500 characters and a reply length (Short or Detailed). The defaults compose byte-identical requests to the desktop's.
   - None of it changes limits, what the model may control, Autopilot or Stop.
   - Out of v1: portraits, lore, anatomy wording, and importing or exporting persona archives (desktop risk R27).
3. **Palettes: a minimal dark set tailored to phones, decided 2026-11-20.** Steel Azure (the default), OLED black (true black, which OLED screens draw with their pixels off) and High contrast. Lowlight is retired and loads as OLED black. The eight desktop palettes stay on the desktop.
4. **Settings in the bottom row: confirmed on 2026-11-20, at least for now.** It's an action that opens the compact sheet over the current screen, with App settings as its last row.
5. **Edge:** Astra suggests deferring it, since it adds a fourth motion state to test on every device. The canvas keeps it on Chat and Live, because you asked for Edge on the phone remote.
6. **ChatGPT sign-in:** allow a few seconds of loopback callback during sign-in, the one exception to "no local server", if M0 finds no supported app redirect.
7. **Memory:** off by default, with review and clear in its sheet.
8. **VacuGlide check-in:** every 15 minutes by default, with Off on its device page. This answers the earlier open question.

Decisions 2 and 5 to 8 were settled on 2026-11-19 as proposed. From the 2026-11-19 review, decided on 2026-11-20 with the remaining work:

- **Forms wait for the core.** A form closes only once the core has saved what it submitted. A refusal keeps the input on screen with the reason.
- **The motor test stays at 1.5 seconds,** as the core runs it, and the copy says "for a moment".

**Carried forward unless you object:**

- **Design direction:** the five explorations converged on one v1 direction (§14), which replaces choosing among them. The explorations stay on the canvas's Exploration page.
- **Gemma over the LAN:** both the Simple and Intermediate contracts ship in v1, as Astra's second review also recommends. An Advanced-only LAN port would be cheaper, but Gemma works poorly with it.

## 13. Toolchain on this PC

Checked on 2026-11-19.

- **Installed:**
  - Android Studio 2026.2.2. Its bundled JBR (JDK 25) is the `JAVA_HOME` for every build;
  - the Android SDK with platform 37.0, build-tools 36.0.0 and 37.0.0, platform-tools and the emulator, with a Pixel 10 virtual device (API 37) and working WHPX acceleration;
  - `adb`, from both the SDK and the scrcpy bundle (the same version), and your Pixel with wireless debugging;
  - Go 1.26.4, for the fixture exporter.
- **Missing:** the NDK, needed only for on-device inference after v1.
- **Build versions:** Gradle 9.9.1, AGP 9.5.1 and Kotlin 2.4.21; `gradle/libs.versions.toml` holds the rest. `compileSdk` is 37 because that is the platform installed. The Compose BOM stays at 2026.09.00 until SDK Platform 37.2 is installed, because Compose 1.13 needs `compileSdk` 37.1 or later.

## 14. Design direction

**Canvas:** [MagicHandy Mobile designs](https://claude.ai/artifact/YH74sVUDiQCDpJ9p8dJdZU). It is private until it is shared. It opens on its "v1 direction" page, redrawn on 2026-11-19 after the second Astra review (§17):

| Row | Boards |
|---|---|
| Overview | The direction, the app frame, routes and sheets, palettes, what was cut or kept, the rules, and the eight decisions (§12) |
| Core screens | Chat while playing · Live controls · Devices · Compact settings sheet · Typing while playing |
| Settings and setup | Motion mode · Autopilot settings · App settings · Add a server on your network · Before you start |
| Devices and safeguards | Connect The Handy · The Handy's device page · Coyote before you connect · VacuGlide safeguards · Stop receipt |
| Failures, recovery and system | Connection lost · Run interrupted · Notification, lock screen and tile · Chat in the Lowlight palette |
| Architecture | The three modules, the Stop path, storage, lifecycle and the build order |
| Astra 6.2 · round two | Astra's six concept screens, tagged Adopted or Partly |

The first explorations moved to the "Exploration · Nov 18" page: the five directions, the flow and settings boards, and the first Astra consult.

### 14.1 Graphite Mobile

The v1 direction keeps what worked in the explorations:

- Graphite Deck's layout: every action in a bottom deck within thumb reach, with the upper screen for reading;
- Graphite Touch's command receipts, and its card patterns for settings and device pages;
- Lowlight's large numerals on Live, and its OLED palette.

Instrument's faders and Native's floating Stop were not carried forward; Native contributes only Android's Back behaviour and system sheets.

- **Tokens:** the desktop Graphite values:
  - ground #0E0F11, surface #16181B, raised #1D2024, line #262A30, strong line #353B43, text #E9E7E2, muted #9AA1A9;
  - accent #5B9DD9, and green #287D42 only for starting motion;
  - status colours ok #4FC06D, warn #D8B66A and fail #D6453F, always with a word;
  - Stop's fill #C43C36;
  - radii of 10, 6 and 4 dp, no glow and no violet.
- **Type:** Inter, as on the canvas, with JetBrains Mono for numeric readouts such as paces, timers, latencies and signal strengths. Both are bundled under the SIL Open Font License (`third_party/fonts`), so every phone renders the boards' type.
- **Palettes:** Steel Azure, OLED black (ground, wells and the bar behind the tabs and Stop at #000000, cards at #0B0C0D, with Steel Azure's text greys) and High contrast, with the semantic colours fixed (§12, decision 3). A unit test holds each to the desktop's contrast rules (ADR 0073).

### 14.2 The app frame

From top to bottom: content, the deck, the tabs and Stop.

- **Bottom row:** two destinations, Chat and Devices, and Settings, an action that opens the compact sheet over the current screen (§12, decision 4).
- **Stop** is the last row of the app frame on every screen: full width, at least 56 dp tall and the only red fill. Tabs sit above it, and subpages hide the tabs without moving Stop. While you type, the tabs and secondary controls give way so Stop stays directly above the keyboard. This rule came from the first Astra consult (§16).
- **Sheets** open inside the content area above the footer, so they never cover Stop.
- **Wording and colour:** green means start and nothing else. Commanded values are labelled as commanded. Failures name the cause and give one next step.

**Routes (nine):** Before you start · Chat · Live · Devices · Device setup and detail · App settings · Models · Provider setup · Run interrupted.

**Sheets:** chats · settings · motion mode · Autopilot · persona · chat model · memory · device type · Stop receipt.

### 14.3 Screens

- **Chat:** shows the transcript with command receipts, then the deck:
  - the Now playing strip (for example, "Playing · Pace 48% commanded" with Autopilot's next change); tapping it opens Live;
  - Autopilot, Pause and Edge;
  - the composer.
- **Live:** shows one large commanded figure, a row per device, steppers for Top speed, Stroke low and Stroke high, plus Pause motion and Edge. Below those sit the Autopilot card (the finish target when set, Pause Autopilot and Turn off) and Recent changes, which expands in place.
- **Devices:** groups devices into "In this run" and "Not in this run", with the connection and state on each row. A device connected during a run joins the next one. "Add a device" opens the device-type sheet.
- **Compact settings sheet:** six rows, then App settings:
  1. Motion mode, in its own menu: Layered, Simple and Off, with the desktop's one-line descriptions; Tempo and Off when a VacuGlide leads;
  2. Style: Gentle, Balanced or Intense, which shapes the motion, not the wording;
  3. Autopilot: how often it changes, check-ins (Off, Talk, or Talk and adjust), an optional finish target, and the planner model ("Same as chat" by default);
  4. Persona: choose who replies, or Manage personas, which opens Personas and each persona's editor (decision 2 in §12);
  5. Chat model;
  6. Memory.
- **App settings:** Models, palette, language, Stop on the lock screen, battery, the Quick Settings tile, sharing diagnostics, deleting chats, and the version with a release check.
- **Setup:**
  - a LAN server is added by address, with type detection, a suggested capability level, the context it needs, and a test;
  - The Handy connects over Bluetooth or Cloud, and its page holds the name, connection, model, Reverse direction, Check, Test and Remove;
  - the Coyote's safety notes come before its first connection;
  - VacuGlide's safeguards come before Test motor.
- **First-run setup, decided 2026-11-20:** four steps replace the one-page first run.
  1. Before you start: the adults-only acknowledgement and a Stop rehearsal with nothing connected.
  2. Choose your AI: ChatGPT, OpenRouter or a server on the network, or Skip for now.
  3. Your companion: how explicit MagicHandy may be, its reaction style and description, or Skip for now.
  4. You're set: what's chosen, Add a device, and Start chatting, or Finish setup without a model.

  Permissions are asked for when a feature first needs them. Chat offers Choose an AI while there's no model, and keeps the draft meanwhile.
- **Failures:** a banner and receipt when a connection is lost. Run interrupted appears on a cold start with an unresolved journal, and leads with the device that may still be running.
- **System:** the notification has one action, Stop, and tapping it opens Live. The public lock-screen version shows only "MagicHandy · Active" and Stop. The Quick Settings tile reads "Stop motion".

## 15. Simplifications

**Round one (2026-11-18)**, applied on the canvas and in this plan:

| Change | Why | Effect on the port |
|---|---|---|
| No pattern library | Owner decision | <ul><li>Drops `patterns` (1.9k lines), the library contracts, the curated content and the Decisions API.</li><li>Chat and Autopilot already run without library choices outside Pattern library mode.</li></ul> |
| No original Creative mode | The desktop retired it (ADR 0062); mobile gets no restore toggle | <ul><li>Drops its chat contract.</li><li>The shared "dynamic" motion definitions stay, because chat and Autopilot use them.</li></ul> |
| Three capability levels in the UI | Advanced (no reasoning) is chosen automatically when a connection's reasoning is off | The same contracts underneath, with one fewer choice |
| LAN server type detected | <ul><li>The type is found by probing `/api/tags` (Ollama), `/props` (llama.cpp) or `/v1/models` (other servers).</li><li>The capability level is suggested from the model name.</li></ul> | One add-server flow, with no type picker |
| Two model roles | <ul><li>Chat, and Autopilot ("Same as chat" by default).</li><li>No second opinion or context-sharing switch. The optional refusal fallback sends the current message once to a selected LAN server.</li><li>The primary provider gets one repair attempt for eligible malformed proposals. The consented LAN fallback gets a single request.</li></ul> | Less settings UI and orchestration |
| One check-in control | Off, Talk, or Talk and adjust replaces a frequency choice plus a switch; the model picks when to check in | One control instead of two |
| Reverse direction on The Handy's page | It is a mounting setting, not a chat setting | — |
| Any device failure stops every device | Safer and simpler on a phone than a "keep going" option | Drops the On device failure setting and the partial-run notices |
| Chats save themselves | <ul><li>No unsaved working tab.</li><li>No save-or-discard prompts.</li><li>A chat list and a New chat button.</li></ul> | A simpler session model |
| One controlling screen | No controller lease, read-only watchers or Take control, since a phone has one UI | Skips that orchestration |
| No notification center | Android notifications and in-app banners cover it | — |

**Round two (2026-11-19, §17)**, also applied:

| Change | Why | Effect on the port |
|---|---|---|
| Three Gradle modules instead of about twelve | Packages and an import test hold the boundaries that matter | Less build setup; a routine change touches one module (§5.1) |
| One database, plus a journal file, a secrets file and a diagnostics ring | Each store matches what recovery needs from it | No DataStore and no consistency work across stores (§5.4) |
| An optional finish target replaces the session buildup bar | In eight-minute desktop runs, a target stated only in chat reached the finish in every run; the reworded bar reached it in five of six | No always-visible meter. The planner still gets a structured target, and Live shows the time remaining |
| The notification has one action, Stop | Tapping it opens Live, where Pause and the limits are | Drops the other notification buttons and their receivers |
| No volume-key Stop | It worked only with the app in front, where Stop is already under the thumb, and it competes with the volume control | Drops key handling, its setting and its tests |
| No nearby-server scan | A /24 sweep misses other subnets and adds scan, timeout and permission states | Servers are added by address; type detection stays |
| Chat-only replies don't keep the service running | The service exists for devices, runs and Autopilot | Simpler service triggers. A chat-only reply is cancelled with a notice if the app goes to the background |
| Run history lives in Live | Recent changes expands in place | No separate history page |
| A one-page first run | Permissions are asked for in context | No setup tour |

**Estimated effect:**

- About 4k fewer lines of Go behaviour to port: patterns, library contracts, curated content, the Decisions API and the original Creative contract.
- The orchestration for controller leases, partial runs and the notification center is also no longer needed.
- Round two drops the server scanner, the volume-key handling, the extra notification actions and the buildup meter, plus about nine Gradle modules' worth of build setup.
- M0 will confirm these numbers.

**Proposed; these need your decision:** the eight items in §12.

## 16. Astra 6.2 consult (2026-11-18)

The second review (§17) supersedes parts of this record: the Settings proposal below is now decision 4 in §12, and the device roster says "Not in this run".

Codex CLI ran `gpt-6.2-astra` with image generation over the canvas sources, this plan and the system design.

- **Outputs:**
  - the full report is in [design/astra-consult/report.md](design/astra-consult/report.md);
  - its six concept screens are on the canvas's "Astra 6.2 consult" row.
- **Timing:** the brief predates the decision to drop the pattern library, so Astra's suggestions involving it were set aside.

**Adopted and applied to the boards:**

- **Stop placement.** Stop is the last row of the app frame on every screen. Tabs sit above it, and it stays a full-width row directly above the keyboard. The earlier sketch placed a small Stop beside the composer, where it changed size and position just as the transcript moved.
- **Honest outcomes:**
  - Stop results are reported per device as confirmed, pending, unconfirmed or no device, with uncertain devices first.
  - The restore screen no longer guesses why Android closed the app or claims that a device paused, since the app can't know either. It leads with any device that may still be running.
  - VacuGlide's safeguard is worded "Send Stop on connection loss", not as a guarantee.
- **Private lock screen.** The public notification shows only "MagicHandy · Active" and Stop. Details appear after unlocking, and pace reads "commanded".
- **Onboarding.**
  - An adults-only acknowledgement, plus a Stop rehearsal with nothing connected.
  - Permissions are requested when a feature first needs them, not all up front.
- **One-handed limit steps.** Live gives each stroke bound its own minus and plus buttons beside top speed.

**Adopted for the milestones:**

- **Run history from Live (M2).** Who changed what, the requested against the commanded value, the limit that applied, and the next planner check.
- **Per-device Stop receipt (M1).**
- **"Planning held" strip (M2).** When the network drops during Autopilot, the strip says whether the current segment is still playing.
- **TalkBack speech (M1).** Streamed replies are read as complete sentences, with Stop outcomes announced first.
- **Device roster (M3).** "In this run" and "Outside this run" sections with named devices, as in the Indigo Index concept, without its palette.

**Proposed for your decision:**

- **Settings in the bottom row.** Make "Settings" in the bottom row open the compact settings sheet, with an "App settings" link inside, instead of having a separate settings button. That removes one entry point.

**Not adopted:**

- **Astra's motion-mode availability table.** It blocks Layered at Simple and Intermediate, but the desktop's default Gemma models run Layered at exactly those levels.
- **Its motion-mode descriptions.** The desktop's wording is kept.
- **Neutral-only status dots.** Graphite keeps green and red status marks with a word. The consult brief had oversimplified the colour rule.
- **"Style" as the way replies are written.** In MagicHandy, style shapes motion.
- **Night Folio's lavender accent.** The brand rules exclude violet.

## 17. Astra 6.2 round two (2026-11-19)

Codex CLI ran `gpt-6.2-astra` at high reasoning effort, with image generation. Its brief included this plan, the system design, the canvas sources, the corrections from the first consult and the desktop's finish-target evaluation, and asked what to simplify or remove for mobile.

- **Outputs:**
  - the report in [design/astra-consult-2/report.md](design/astra-consult-2/report.md): a keep, simplify, defer or remove verdict for every feature and canvas board; the module and storage structure; a port order with evidence gates; the converged design with its nine routes; which failures deserve the screen; and the ten changes to make first;
  - six concept screens, on the canvas's "Astra 6.2 · round two" row.
- **Canvas:** redrawn on a new "v1 direction" page (§14). The earlier boards moved to "Exploration · Nov 18".

**Adopted:**

- **Architecture:**
  - three modules (§5.1);
  - storage by recovery need, with the journal armed before the first motion-capable write and the start refused if arming fails (§5.4);
  - the service only for devices, runs and Autopilot (§10).
- **Design:**
  - Settings as an action that opens a six-row sheet;
  - the labelled Now playing strip;
  - on Live, the large commanded figure, the limit steppers, Turn off Autopilot and Recent changes in place;
  - Devices grouped by run membership;
  - the Coyote's safety notes before its first connection;
  - the Run interrupted page, with one local action;
  - the optional finish target.
- **Removals:** the volume-key Stop, the extra notification actions and the nearby-server scan (§15).
- **Build order:** M0 also proves sign-in, journal arming with a kill test, and a simulated persistent-speed device. M2 tests losing only the model separately from losing a device (§11).

**Not adopted:**

- **Deferring Edge.** You asked for Edge on the phone remote, so the canvas keeps it on Chat and Live. It is decision 5 in §12.
- **A separate start before a chat reply may move a device.** Chat keeps the desktop behaviour: with a device connected and a motion mode other than Off, a reply can start motion. Off is the talk-only choice, and connecting never starts motion.
- **Lighter neutrals.** Astra's #101113, #1A1C1F and #F2F2EF are close to Graphite's, so the desktop tokens stay. Graphite's muted text already achieves about a 7:1 contrast ratio on the ground colour. M4 checks every state in each palette.
- **Roboto in the sketches.** The app bundles the canvas's Inter, with JetBrains Mono for readouts (§14.1).

**Corrected in the system design:** the option to keep going on healthy devices is gone, the service no longer runs for chat-only replies, the journal moved out of the database, the curated-pattern storage is gone, and LAN servers are added by address.

## 18. Astra 6.2 consult on setup and personas (2026-11-20)

Codex CLI ran `gpt-6.2-astra` at high reasoning effort, with image generation. Its brief held the six setup and persona boards, this plan, the system design and the two earlier consults, and asked what would make the flows clearer on a phone.

- **Outputs:** the report in [design/astra-consult-3/report.md](design/astra-consult-3/report.md), with a verdict and cost for each change, and five concept images in [design/astra-consult-3/images](design/astra-consult-3/images).
- **Canvas:** the setup and persona boards were redrawn with the adopted changes (V-SetupModel to V-PersonaStyle).

**Adopted:**

- **Setup:**
  - Skip works all the way to Chat: Skip for now on steps 2 and 3, and a You're set variant without a model that says "No model chosen" and offers Finish setup;
  - Choose your AI says what each provider needs and where it runs, with no claim that messages go only to that model;
  - a provider saved during setup moves on to Your companion, and one saved from Chat's Choose an AI returns to Chat with the draft kept;
  - one Stop: the footer, with no informational Stop row on You're set.
- **Personas:**
  - Personas rows only edit, and choosing who replies stays in the Settings sheet, with Manage personas below the choices;
  - the level and style pickers give every option its definition;
  - at Utility the description reads "Saved for Warm, Intimate and Explicit", since it isn't sent there;
  - the description counts characters as people see them, keeps an over-limit paste and says how much to remove;
  - editors track changes, and leaving with unsaved changes asks "Discard changes?", also before duplicating;
  - MagicHandy's Reset to default loads the defaults into the draft, to be saved;
  - a persona the person made is renamed in its title, offers Use persona while saved and Save and use while changed, and a blank name puts focus back on the title;
  - deleting names the persona, its memory, the messages that stay and, if it was in use, who replies next, with Cancel focused first;
  - replies keep the name of the persona that wrote them.
- **Access:** each step announces itself once, such as "Choose your AI, step 2 of 4", and rows say what a tap does.

**Not adopted:**

- **A required Stop press on Before you start.** The rehearsal stays optional, as approved for the one-page first run.
- **A style example in the editors, and a live audition.** A fixed line can't reflect the description or the model, and an audition would need a model, could cost money and touches memory and motion.
- **A persona shortcut in Chat's header.** It would compete with the chat's title; switching stays three taps away in Settings.
