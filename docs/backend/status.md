# Backend status

**Handoff on 2026-11-19.** The backend is at M0. All nine pre-M1 review items and both Stop decisions are complete, and the debug composition root is packaged in the test fixtures. A forced offline run of `spotlessApply :core:check :transport:check spotlessCheck` passed all **103 JVM tests** (44 in `:core` and 59 in `:transport`) with zero failures, errors or skips.

## Verification on 2026-11-19

The baseline is the exported desktop snapshot at `9a4c36cf41fa4ec6abeb4c72ae6b4790cab0430a`. The Gradle verification gate runs `spotlessApply :core:check :transport:check spotlessCheck`.

| Checkpoint | Verified at the checkpoint |
|---|---|
| M0.1 fixture export | 49 manifest records and two JVM tests. A second Go export to a scratch directory produced the same manifest and file hashes. |
| M0.2 buffered sampler | 23 passing JVM tests, covering 21 independently calculated periods and exact frame sequences (807 timestamps and positions) from authored coordinates and requested speeds. |
| M0.3 Stop | 29 passing JVM tests. Six safety tests cover parallel receipts, an independent deadline, double Stop, cancellation, stale writes and replies, uncertain delivery and no-device evidence. |
| M0.4 run journal | 36 passing JVM tests. Seven journal tests cover service and storage refusal, stalled arming, kill boundaries, stale reconciliation and Stop-only recovery. A confirmed Stop cannot clear a journal record while an older motion write may still land. |
| M0.5 Handy | 57 passing JVM tests (36 core, 21 transport). All 10 RPC and five REST vectors match. Fake GATT tests verify safe connect and teardown, wrong acknowledgement IDs and pre-write retries. In-process HTTP tests distinguish server acceptance from device evidence. |
| M0.6 first hosted turn | 64 passing JVM tests (36 core, 28 transport). Synthetic Responses streams test completion, refusal, truncation and HTTP cancellation. The fake identity provider checks S256, state, nonce, an RS256 identity signature, vault persistence, rotating refresh tokens, discovery origin and browser expiry. |
| M0.7 debug integration | 73 passing JVM tests (38 core, 35 transport). The public factory runs manual output, motor tests, text chat, typed Stop and recovery. Five UI-contract integration tests exercise the five simulated device families and the canned model. Two transactional store tests and two bytecode architecture tests also pass. |

The fixtures cover 21 atlas cases (807 frames), 10 Bluetooth RPC vectors, five Cloud REST requests, three hosted Terra request bodies, five translations, five prompt sets in one file, the default persona, the notice catalog and both brand assets. The shipped desktop has one built-in MagicHandy persona derived from default settings, and the fixture exporter never opens a persona store.

The final handoff adds tests for the scope-only debug factory, held ordinary dispatchers, finite and stalled motor tests, HSP playback through the real Bluetooth session, connection-role deletion, the diagnostic ring and credential removal during token refresh. The Cloud tests check the actual HTTP method, path, headers and body against an in-process peer. All three Terra request fixtures serve as desktop export baselines for the future Advanced composer.

Two independent final exports produced the same manifest SHA-256 (`d08f1f6c9353fc96d1fb6ad59d80cc1d6dd4167b9bba964328c5559783b6f5b3`), and each manifest entry records its file digest. The exporter normalizes text line endings before hashing so these digests survive a Windows Git checkout.

## Remaining milestones

| Milestone | Implemented and verified | Remaining work and deviations |
|---|---|---|
| M0 | All seven backend checkpoints and the pre-M1 review fixes (49 fixture records and 103 JVM tests). | The Android pass still needs to cover real sign-in return, Pixel radio and Stop latency, jitter, cold launch, APK and memory budgets, notification denial and service lifecycle. |
| M1 | M0 supplies the 21-case bounded-ramp sampler, both Handy protocol paths, text-only turns, revisioned in-memory storage and basic orchestration. | 114 atlas cases remain, along with continuous score compilation, retargeting, Pause/Resume semantics, Edge, Advanced motion chat, schema validation, refusal retry and repair, memory extraction, full history windows, OpenRouter and device/model setup. No M1 exit claim yet. |
| M2 | Settings can store change frequency, check-in choice, planner role and finish target. No planner acceptance runs yet. | Autopilot planning, ADR 0072 timing, check-ins, finish-target handling, held planning and run history. |
| M3 | The M0 integration suite exercises device selection and simulator fan-out. No M3 production drivers or LAN acceptance runs yet. | LAN server detection and providers, Simple and Intermediate contracts, Intiface, multiple Handy connections, the Coyote cadence and the Ultra driver. The Handy via Intiface isn't built, following the default for plan §12 decision 1, which is still open. |
| M4 | M0 includes persistent-speed simulation, a cloud-loss retry and cold-start Stop through the journal. No production VacuGlide acceptance runs yet. | Real tempo and valve commands, the timed check-in and its Off setting, longer failure scenarios and physical acceptance. The simulator carries the fifteen-minute settings default. |

The Android app pass owns the foreground service, GATT, Room, Keystore, browser callback and on-device measurements. All protocol verification so far uses synthetic data.

The Bluetooth spike requires correlated responses to mutating RPCs, including initialization, which can exclude firmware that writes successfully without replying. Cloud confirmation requires an explicit stopped playback state. Startup position reconciliation, optional clock synchronization, retargeting, reversed mounting and sustained physical playback remain for Handy acceptance.

## Behaviour exposed to the UI

The debug path supports onboarding, new and saved chats, streaming replies, Send again, five-locale typed Stop, explicit connection to the supplied devices, next-run selection, manual motion and a short motor test. It also supports Stop receipts, interrupted-run recovery and stored preferences. The first hosted text path has no motion authority. When a requested action is unavailable, the UI can display `AppState.backendNotice`.

Currently, Pause ends output through Stop, and Resume starts a fresh manual run. Editing a limit during motion also stops the current run before storing the limit; the reviewed Phase 1 handoff and smooth retargeting wait for M1. Text-only chat history keeps up to 16 messages. Memory storage and transactions are in place, while extraction is deferred to the Advanced chat port.

The Bluetooth simulator runs the production RPC session against an in-memory byte peer, while the other four simulators operate at the device transport interface. Coyote write-only completion and vibrator bridge acknowledgement both retain unconfirmed receipts. See the [wiring guide](wiring.md), [additive API change](contract-changes.md) and [platform proposals](proposals.md).

## Review fixes (2026-11-19)

1. **Bluetooth Stop.** A submitted GATT write holds its slot through its bounded native callback, and RPC 715 waits for that slot within the Stop deadline. `HandyBluetoothTest` covers a 50 ms overlap, an uncooperative native callback while ordinary I/O is parked, an unsent command (`WriteFailed`) and a missing correlated reply (`NoReply`). Offline formatting and module checks passed.
2. **Transport exceptions.** Non-cancellation exceptions during motion retire the owner and trigger recovery; Stop failures produce per-device evidence. Core jobs run under a supervised child scope with redacted diagnostics. `MotionEngineTest` covers revoked permissions on write and Stop, sibling confirmation and a later successful press. `DebugBackendTest` confirms that an uncaught child failure leaves the core responsive. Both module checks passed.
3. **Lost connections and Stop again.** Detaching a lost device waits for its pending Stop (up to its deadline) and verifies the generation before releasing the owner. A direct Stop press also retries detached cloud targets from unresolved evidence held in memory. `DeviceRecoveryTest` covers acknowledgement before close, deadline expiry before close and a second Stop press after VacuGlide link loss. Both module checks passed.
4. **Routine stops.** Only explicit Stop calls increment the receipt's press count. Routine stops keep internal evidence and surface unresolved results, while an idle disconnect targets only the departing owner and preserves the current chat epoch. `DeviceRecoveryTest` covers confirmed and bridge-only pauses followed by the next explicit press; `DeviceConnectionTest` covers an idle disconnect during streaming and a disconnect during a two-device run. Both module checks passed.

   **Owner decisions.** The Intiface simulator already returned `Unconfirmed(BridgeOnly)`, which the factory tests verify. `ReconcileDevice` now removes locally checked rows, records the time and reconciles the matching journal while older writes drain. Two new integration tests cover partial and final reconciliation, cold-start projections, no-ops, zero transport writes and a later Stop. `RunSafetyTest` confirms that automatic journal retries skip devices already checked locally. The offline gate passed.

5. **Stale batches.** Handy wires now distinguish pre-send fence rejection from an uncertain submission; a partly sent batch still requires recovery. Factory and fake GATT tests press Stop at the first command of a later batch, verify exactly one Stop and then start successfully on the same owner. Both module checks passed.
6. **ChatGPT refresh.** Once the refresh request can reach the token endpoint, token rotation, identity validation and the vault compare-and-set finish together under `NonCancellable`. `ChatGptAccountTest` cancels the caller after the response arrives and reopens the account with the new token, while confirming that concurrent credential removal still wins. Both module checks passed.
7. **Committed replies.** The chat transaction's final admission check claims the turn in the engine's Stop epoch, so a Stop after that point cannot label the saved reply as interrupted. `ChatCommitTest` holds the store after commit, triggers Stop, checks the receipt and retries, confirming that exactly one assistant message remains. Both module checks passed.
8. **Connection ownership.** Each opening or connected device owns a supervised child job, and service readiness and connection I/O run outside the intent queue. Disconnect cancels an unfinished open, or waits for the attached owner's Stop and release before ending its scope. `DeviceConnectionTest` covers a later chat intent while connect is held, duplicate connect requests, owner-only cancellation and disconnecting mid-connect. Both module checks passed.
9. **Maintainability and packaging.** Both module checks passed with strict explicit API enabled for test-fixture compilations, and production JARs exclude `core.testing` and `transport.simulated`. `ArchitectureTest` checks all five driver packages in both directions, and `HandyCloudTest` verifies that owners share a dedicated Stop dispatcher. The six reviewed functions now delegate their component steps, KDoc for intents and dependencies is complete, and caught I/O failures report redacted categories (`HandyBluetoothTest` checks the diagnostic emitted by a failed native Stop). Final JAR inspection found all six debug-store classes and all 15 simulator classes in the fixture JARs and zero in the production JARs.

The Android handoff covers the debug Gradle dependencies and the local-check control. Motor-test duration and form-completion behaviour remain open owner decisions. See [wiring](wiring.md) and [contract changes](contract-changes.md).
