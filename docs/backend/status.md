# Backend status

## First public release

The owner completed physical testing with ChatGPT on a Pixel 10 Pro XL and accepted the performance of 0.1.6 for release on November 21, 2026. The device checklist tracks broader testing across firmware, providers and phones. [Phone acceptance record](device-checklist.md)

## Android 0.1.6 motion-quality build

Manual and chat Start now reproduce the desktop's full-sweep positions and timestamps over Handy Cloud and Bluetooth in all four end-to-end captures. The 210-case HSP atlas matched desktop point counts and authored turn times in every case. The Intiface comparison reproduced and corrected an unintended 110 ms stationary step at a reversal. The signed test build passed 953 tests. [Review and package verification](../reviews/2026-11-21-handy-motion-quality.md)

## Android 0.1.5 Handy Cloud phone-test build

The owner confirmed a connection to their real Handy from the emulator using the previously saved entry in 0.1.4. Their boot-time retry check led to a three-second total connection deadline and a **Retry** action while connecting in 0.1.5. The handshake verifies device-wide idle. Further changes cover device command replay, Stop evidence, retained Bluetooth and bridge callbacks, structured LAN-model readiness, and ChatGPT token rotation. [Diagnosis, independent review and verification](../reviews/2026-11-21-handy-cloud-and-integration-review.md)

## Android 0.1.3 phone-test build

ChatGPT sign-in and the first real reply were verified on the Android 17 emulator. The signed update recognizes a complete event stream without a usable media type and preserves the reply after restart. The 907-test gate passed. [Diagnosis, Gemini findings and verification](../reviews/2026-11-20-sign-in-and-release-readiness.md)

## Personas and first-run setup, 2026-11-20

All five backend steps are implemented. The final offline rerun passed **828 JVM tests** (298 core, 170 transport and 360 app), with zero failures, errors or skips. This includes 60 screenshot comparisons and both UI boundary tests. Both APKs assembled. R8 contents verification passed, and the two packaged prompt banks and the Go numerical licence matched their source bytes in both archives.

The full gate exits on **two Android Lint MissingTranslation errors**. The synced UI has English values for `form_too_long` and `form_limit_reached` and needs the four other supported locales. The gate used `--continue` so the test suites and release package also ran. A separate offline invocation of Spotless verification, core and transport checks, the app JVM tests, both assemblies and the R8 contents check exited successfully. The [UI handoff](ui-requests.md) lists the translations, screen integration and copy. The [device checklist](device-checklist.md) covers the remaining on-device acceptance.

**3.1 · Persona lifecycle.** Room v3 adds the custom roster and keeps selection and built-in personality in revisioned settings. Save and use, duplicate and delete commit together with their affected settings and memory. A turn captures its speaker before suspending. A late reply can finish after a deletion with historical attribution. The write transaction checks that its memory owner still exists. The full gate ran 726 JVM tests (225 core, 144 transport, 357 app), all passed, including 60 screenshot comparisons. Both APKs assembled. Lint reports two missing UI translations in the synced resources, tracked in the [UI handoff](ui-requests.md).

**3.2 · Prompt composition.** All 820 JVM tests passed (298 core, 165 transport, 357 app). The gate assembled both APKs and reported the same two UI-owned lint errors. Two fixture exports matched on 267 payloads and the manifest, including the 197 original hashes. Two vocabulary exports matched on both resources. Seventy raw references cover five languages across hosted chat, Simple, Intermediate and check-ins. Twenty additional public-core hosted cases compare every Responses field. Default requests retain their bytes. The opt-in voice blocks use the [listed wording differences](port-deviations.md) to satisfy the control-vocabulary constraint. A held check-in and a one-call refusal fallback both preserve their speaker.

**3.3 · First run.** A new installation starts incomplete. An older preferences document with an existing adult acknowledgement loads as complete. Acknowledging today keeps setup incomplete across Room reopen, and FinishSetup works with no model or device. Deleting all chats and memory preserves the flag. The gate ran 822 passing JVM tests (298 core, 166 transport, 358 app), assembled both APKs and reported the two UI translation errors.

**3.4 · Diagnostics and privacy.** The report adds the total persona count and the active level, style and reply length. A regression test looks for synthetic custom and duplicate names, IDs, description and saved memory in both text and filename. Robolectric checks the merged backup flag, database location and all excluded storage domains for both backup and transfer. The gate ran 824 passing JVM tests (298 core, 167 transport, 359 app). Both APKs assembled. The same two UI lint errors remain.

**3.5 · Debug model.** The simulator labels canned text from the captured name and level, including at Utility. A virtual-time test switches speakers during a simulated Start, observes the original label on that reply and the new label on the next, and verifies the same motion and direct Stop. All 825 JVM tests passed (298 core, 168 transport, 359 app). Both APKs assembled. The two missing UI translations still block Lint.

**Final integration.** A blocked persona write leaves direct Stop available and cannot restart the run on completion. Repeatedly saving identical settings preserves their revision. The Android debug composition root now backs chats, personas, memory and preferences with its own no-backup Room database while its devices and model still run in process. The JVM fixture graph retains independent in-memory stores.

## Android port review follow-up

The November 20 device-test build uses the release Android factory, production device adapters and durable stores. Its own application ID permits a side-by-side install. The [original review](../reviews/2026-11-20-android-port-behavior-review.md) records the failures at commit `4524e9f`. The [phone checklist](device-checklist.md#first-checks-on-the-device-test-build) covers physical acceptance.

| Area | Implemented behavior and regression coverage |
|---|---|
| Stop and recovery | Unexpected foreground-service loss revokes queued output and directly requests Stop. An unresolved Stop at idle survives process replacement. A VacuGlide awaiting return keeps its saved row and credentials until there is evidence or a local check. A later Stop gets its own receipt, a reconnected device clears its loss banner, and a stopped run adds one timestamped event to the open chat. |
| Output and configuration | A VacuGlide motor write must satisfy both the current personal range and the Live ceiling. The real Intiface scalar driver enforces the editable vibrator limit, initially 40% for a newly saved device. Existing personal values persist. Handy model selection changes calibration. Mounting is sampled at Start or Resume. The lead comes from the included roster. |
| Feedback and connection loss | Coyote Live waits for measured strength. Three consecutive late packet writes stop the run. The Autoblow button channel must be available to attach and its loss stops active output. Handy Cloud connection checks read device state. Supported devices show measured battery or connection latency. Re-selection of a uniquely identified bridge feature preserves the saved row and settings. |
| Chat and Autopilot | A hosted refusal can send the current message once to the chosen private LAN server. Prior conversation and saved memories are excluded, and revoking consent cancels the request. Transient planner failures retry after 2 to 30 seconds. Unaffected LAN traffic survives an internet-validation change. A second draft stays in the composer while a reply streams, and typed Stop remains available. |
| Phone UI and simulation | A due VacuGlide check-in counts down on Chat and Live. The private notification opens Live to answer it. Recovery shows the last safety record and per-device evidence. Settings identifies the single built-in companion. Debug uses validated structured chat replies and the production VacuGlide session over an in-memory peer. The formatter now checks explicit imports across production, fixtures and tests. |

The final offline build reran 181 Gradle tasks. All 722 tests passed with zero skips, failures or errors, including 225 core tests, 141 transport tests and 356 app tests. All 60 screenshot comparisons passed. Android Lint reported zero issues for both the debug and device-test variants.

Android SDK apksigner verified the v2 signature of the 4,744,167-byte APK. Package inspection confirmed the non-debuggable `com.magichandy.mobile.devicetest` manifest, the production adapters in DEX, the bundled prompt catalog and 16 KiB alignment. The APK SHA-256 is `a13e15df9efb5db63e77a4f7c17ef52ec861f8d59962fcdc2936f98cd889b719`. The release graph and service lifecycle ran under Robolectric. An install on an emulator or phone, firmware behavior, timing under Doze and real-provider acceptance remain open. [Device-test packaging](../release.md) · [Phone acceptance](device-checklist.md)

## Last non-UI items, 2026-11-20

All seven items are implemented. The final offline rerun executed all 127 Gradle tasks and passed 367 JVM tests with zero failures, errors or skips. The Android Lint XML reports zero issues. Both UI boundary tests passed. On the final source, the disposable signer verified the staged APK and SHA256SUMS, then restored the unsigned output. ZIP checks passed for both APKs and their embedded prompt catalog and numerical licence matched the source bytes. The debug APK is 37,396,184 bytes and the unsigned release APK is 4,516,111 bytes.

**3.1** Intiface linear features now use a shared per-address connection, stable selection, bounded asynchronous acknowledgements, full-precision engine samples and a generation-fenced anchor. A correlated bridge Stop acknowledgement remains unconfirmed. Gate passed with 349 tests (224 core, 109 transport, 16 app).

**3.2** Configuration forms have one bounded submission lane and a per-call typed result. Store commits, LAN readiness, duplicate and stale choices, active runs and unresolved devices return distinct outcomes. A suspended form cannot delay direct Stop. Gate passed with 352 tests (224 core, 112 transport, 16 app).

**3.3** The read-only diagnostics report includes bounded event and Stop evidence, run/journal state, device kinds and build versions. The share provider exposes only cache/diagnostics. JVM tests inject synthetic private fields and malformed disk entries. Gate passed with 354 tests (224 core, 113 transport, 17 app).

**3.4** System UI tile callbacks now persist Added or Removed; a new installation exposes null until Android reports presence. The tile click still calls emergencyStop directly. Robolectric verifies callback order and Room reopen. Gate passed with 356 tests (224 core, 113 transport, 19 app).

**3.5** The debug Coyote now uses the production CoyoteControls and B0/B1 session against an in-memory byte peer. Delayed reports, one pending step, independent ceilings and both-channel zero on Stop are verified from Live’s core entry points. Gate passed with 357 tests (224 core, 114 transport, 19 app).

**3.6** The signed pipeline built and verified a disposable APK with Android SDK apksigner, generated MagicHandy-0.1.0.apk and independently verified SHA256SUMS. The key, temporary properties and staged artifacts were deleted. The following gate restored the unsigned APK. R8 contents and the restored licence are checked at build time. Private or inaccessible release metadata now reports Failed. Gate passed with 357 tests (224 core, 114 transport, 19 app).

**3.7** Old Lowlight JSON loads as OledBlack and the next settings patch writes its current name. The previous palette-revision test deliberately retains a Lowlight input and now expects OledBlack. The Room test preserves the existing revision and other preferences. Gate passed with 358 tests (224 core, 114 transport, 20 app).

**Final integration audit.** Direct Emergency Stop reaches an open discovery socket and supersedes a pending per-device Stop. Repeated numeric indices are checked against fresh device identity, and a failed database deletion leaves the credential and form retryable. The same gate passed with **367 JVM tests** (225 core, 122 transport, 20 app), including the existing UI boundary tests.

**Owner decisions and remaining work.** The Handy reached through Intiface keeps its Handy family and bridge connection kind. A generic linear actuator has its own family without Handy-only settings. Legacy bridge rows that have only an index require explicit re-selection. The desktop motor test remains 1.5 seconds. Android reports tile presence as unknown until the first callback. Cloud key and OpenRouter save results denote local persistence; remote reachability is established separately.

The UI owner has the remaining screen changes for linear discovery and the bridge caveat, per-form error presentation, diagnostics sharing and the Android add-tile request. The four exhaustive UI edits in this pass are in `AddDeviceScreen.kt`, `DeviceDetailScreen.kt`, `DevicesScreen.kt` and `Words.kt`. Debug `CoreFactory.kt` is the only shared wiring edit. Existing Coyote controls, vibrator discovery, refusal fallback, memory, system access, notification navigation, branded icons and the Go numerics licence are present. Physical timing, real Stop behaviour and signed install/upgrade remain on the device checklist. [UI requests](ui-requests.md) · [Device checklist](device-checklist.md) · [Release guide](../release.md)

## Earlier M0 to M4 checkpoint

The counts and APK sizes in this historical checkpoint precede the final non-UI pass.

The non-UI M1–M4 implementation is complete against desktop alpha.89, `1b80d72e`. The offline rerun passed with **342 JVM tests**, comprising 223 core, 103 transport and 16 app tests, with zero failures, errors or skips. Full-suite counts below are cumulative at each milestone.

| Milestone | Implemented and verified | Tests at milestone | Remaining acceptance or deviation |
|---|---|---:|---|
| Parity refresh | Re-export from alpha.89, history admission fitted to the selected context, expanded managed reference windows and the 135-case matrix. | 115 | Managed inference stays desktop-only. The final export now includes 197 cases. |
| M1 backend | Continuous gesture and Layered scores, retargeting, Pause/Resume, live limits, Edge, both Handy paths, startup, clock sync and sustained playback. Hosted Advanced chat, refusal retry, one repair, built-in prompts, memory, OpenRouter, device and model setup. | 293 | The pinned native Handy protocol exposes no physical geometry RPC. The port uses acknowledged Stop and range restoration there. The measured path and lead-in are tested with synthetic telemetry. |
| M1 Android | Release factory, foreground-service admission, notification and Live entry, GATT, contextual permissions, Room, journal, Keystore vault, browser, clock, network and diagnostics. Both APKs assemble. | 300 | Android and firmware behaviour on a phone, including actual account sign-in. Debug defaults to the simulators. A runtime settings toggle is an optional UI request. |
| M2 | Service-owned Autopilot, ADR 0072 timing, finish target, all three check-in settings, held planning on model loss, Recent changes, private next-change notification and direct tile Stop. | 305 | Screen-off scheduling, Doze and tile behaviour on target Android versions. |
| M3 | Three named LAN server types, Simple and Intermediate contracts, Intiface vibration, linked Handys, Coyote 3.0 and Autoblow Ultra. | 313 | Physical linked timing, bridge loss, Coyote strength reporting and Ultra movement. Bridge-only Stop evidence remains unconfirmed. |
| M4 | VacuGlide tempo, six seeded routines, valve limits, 15-minute default check-in with Off, Stop on loss and return, and simulated process death, radio revocation, airplane mode and network handover. | 326 | Physical suction, local switch-off, valve closure and reconnection. A return may happen while the phone stays on the same network. |

## Verification

The final command was `./gradlew --console=plain spotlessApply check :app:assembleDebug :app:assembleRelease --offline --rerun-tasks`. All 126 actionable tasks executed. The debug Android Lint XML contains zero issues. JUnit Platform ran Jupiter and Vintage/Robolectric with zero failures, errors or skips. App JVM tests use the debug test variant, including a test that constructs the production release graph. The build configuration disables a separate release JVM test variant. Provider and device traffic in tests stayed in memory or on loopback.

The debug APK is 37,064,636 bytes and the unsigned release APK is 4,414,238 bytes. Both APKs passed ZIP integrity checks and contain the exact regenerated prompt catalog.

The later audit checkpoints reached 331 tests for the complete Terra bodies, explicit refusal fallback and automatic Edge return, 334 for Room migration and network projection, 338 for the release graph, browser, device acknowledgements and Intiface selection, then 341 for durable cloud-only return, provider authorization and all three LAN server types. The 342-test final gate includes priority native Stop. It also exercises a last-moment Coyote strength change and the 200-fact memory capacity. Fixtures and prompt resources were independently regenerated and compared byte for byte. [Parity map](../../PARITY.md)

## Decisions and handoff

- **Stop and recovery.** Stop fences output before any I/O. A returned VacuGlide requires a second Stop even if the loss-time Stop was acknowledged. The journal retains the device until that repeat settles or the user records a local check. Unknown delivery stays visible. Physical switches and the 1.5-second motor test keep their existing semantics.
- **Consent and privacy.** Memory starts Off. With memory enabled, explicit remember requests in the five supported languages can save a short fact in the reply transaction, up to 200 distinct facts per persona. The refusal fallback requires a chosen local connection. Clearing it invalidates an in-flight fallback result. ChatGPT sign-in uses a short-lived, single-use loopback callback behind the browser port.
- **Release and debug.** Release uses platform adapters. Debug uses local simulators for devices, model setup and release checks. Only `MagicHandyApp.kt`, `MainActivity.kt` and the debug `CoreFactory.kt` changed among shared wiring files. Compose source, UI strings, previews and the root build files stayed with their owners.
- **Subsequent decisions.** The 20 November owner decision confirmed the bottom settings row, a 1.5-second motor test, per-form acceptance and full Intiface support. Current UI status is recorded in [UI requests](ui-requests.md).

## M0 verification on 2026-11-19

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

The final handoff adds tests for the scope-only debug factory, held ordinary dispatchers, finite and stalled motor tests, HSP playback through the real Bluetooth session, connection-role deletion, the diagnostic ring and credential removal during token refresh. The Cloud tests check the actual HTTP method, path, headers and body against an in-process peer. Those three baseline captures now verify the complete hosted path in HostedParityTest.

Two independent final exports produced the same manifest SHA-256 (`d08f1f6c9353fc96d1fb6ad59d80cc1d6dd4167b9bba964328c5559783b6f5b3`), and each manifest entry records its file digest. The exporter normalizes text line endings before hashing so these digests survive a Windows Git checkout.

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
