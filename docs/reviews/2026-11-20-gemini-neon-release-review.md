# Gemini 4 Neon release review of the pre-fix snapshot

Antigravity CLI ran Gemini 4 Neon at XHigh effort against the November 20 Android source snapshot. The text below is its independent assessment before the corrections. The two parts of its final response are joined here, with file links pointing into this checkout. For verified outcomes, differences of judgment, and remaining acceptance checks, read the [engineering disposition](2026-11-20-sign-in-and-release-readiness.md).

# MagicHandy Mobile — Independent Release Review

## Release Verdict: **NO-GO (Block Release)**

Do **not** ship this snapshot as a public release (or treat it as release-ready). Beyond the known live `auth.openai.com` rejection of `ext_agent_host_id`, static review of the production graph, OAuth/callback/vault code, foreground service lifecycle, motion/transport layer, setup/persona flows, and packaging confirms **3 Critical** and **5 High** defects affecting safety Stop, OAuth first/returning sign-in, stale physical command handling, detached VacuGlide recovery, and cold-start behavior—plus **4 Medium** defects in setup, local refusal fallback, persona upgrade attribution, and release packaging.

In addition, **all verification evidence in this snapshot is synthetic** (JVM/Robolectric SDK 33 with mocked HTTP, BLE, Keystore, and Service stubs). Zero real Android 14–16 device, real hardware, or live provider acceptance evidence exists yet (as also reflected in [device-checklist.md](../../docs/backend/device-checklist.md)).

---

## Prioritized Confirmed Defects

### Critical (Release Blockers)

#### C1. ChatGPT OAuth `ext_agent_host_id` Rejection + Fragile Android `LoopbackBrowser` Callback Lifecycle
* **Area:** OAuth first & returning sign-in, callback
* **Source & symbols:**
  * [PlatformGraph.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/PlatformGraph.kt) (`platformCore` lazy `account`, `noBackupFilesDir/agent-host`)
  * [ChatGptAccount.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/providers/ChatGptAccount.kt) (`signIn`, `authorize`, `authorizationUrl`, `authorizationCode`)
  * [LoopbackBrowser.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/LoopbackBrowser.kt), [LoopbackBrowser.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/LoopbackBrowser.kt), [LoopbackBrowser.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/LoopbackBrowser.kt) (`redirectUri`, `openAndAwait`, `receive`)
  * [ChatGptScreen.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/models/ChatGptScreen.kt) + [MagicHandyRoot.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/MagicHandyRoot.kt)
* **Triggering conditions & confirmed defects:**
  1. **Known `ext_agent_host_id` symptom & dynamic-client assumption:** `PlatformGraph.kt:56-59` persists a random UUID to `noBackupFilesDir/agent-host` and `ChatGptAccount.authorizationUrl()` unconditionally sends `client_id=dynamic_agent_client`, `ext_agent_host_id=<hostId>`, and `agent_name_hint=MagicHandy` on both first and returning sign-in, while `authorizationCode()` (`ChatGptAccount.kt:161-163`) rejects callbacks without a returned `client_id != "dynamic_agent_client"`. Offline tests missed this completely because `IdentityFixture` ([ChatGptAccountTest.kt](../../transport/src/test/kotlin/com/magichandy/mobile/transport/providers/ChatGptAccountTest.kt)) uses `"fixture:/oauth-return"` and never checks the authorization URL parameters.
  2. **Background process freeze/kill during Android browser sign-in:** `LoopbackBrowser` launches `CustomTabsIntent` from `Application` context with `Intent.FLAG_ACTIVITY_NEW_TASK` (`LoopbackBrowser.kt:39-42`), putting the browser in another task and backgrounding `MainActivity` ([MainActivity.kt](../../app/src/main/kotlin/com/magichandy/mobile/MainActivity.kt)) while `CoreService` is **not** running (`needsHost == false` with no device). On Android 12–16 (`targetSdk = 36`), the Cached Apps Freezer or LMKD can freeze/kill the process during login/2FA, causing the loopback callback to `127.0.0.1:<port>/auth/callback` to fail with `ERR_CONNECTION_REFUSED`.
  3. **Timeout mismatch, error-callback stall, and no Custom Tab dismissal detection:** `LoopbackBrowser.redirectUri` kills the socket at **2 minutes** (`LoopbackBrowser.kt:65`) versus `ChatGptAccount.signIn()`'s **5-minute** timeout (`ChatGptAccount.kt:83`). Closing the Custom Tab (e.g., after `auth.openai.com` displays an error page) is never detected, leaving `SignInState.WaitingForBrowser` wedged until timeout. An OAuth error redirect `?error=...` without matching `state` is answered `400` and ignored (`LoopbackBrowser.kt:134-136`) instead of failing promptly; a valid redirect returns plain text `"Return to MagicHandy."` without flushing or bringing `MainActivity` back to the foreground. Also `expiry` (`LoopbackBrowser.kt:57`) is non-synchronized across threads and `ServerSocket(0, 1, ...)` uses backlog `1`.
  4. **Back navigation leaks active sign-in:** Tapping Back on `ChatGptScreen` (`MagicHandyRoot.kt:384`) pops the route without dispatching `UserIntent.CancelSignIn`, leaving the loopback `ServerSocket` and coroutine running in the background and preserving stale `SignInState.Failed` on the next visit.
* **Correction:**
  * Align `ChatGptAccount.authorizationUrl()` / `authorizationCode()` with the live `auth.openai.com` mobile OAuth contract (removing/fixing `ext_agent_host_id` and client registration/reuse behavior, validated on a real Android phone).
  * Launch Custom Tabs from the active `Activity` (without `FLAG_ACTIVITY_NEW_TASK`) so the tab lives in MagicHandy's task, detect Custom Tab dismissal / activity resume (e.g., cancel or fail promptly when `MainActivity` resumes without a callback), align socket/sign-in timeouts, flush and handle error redirects (`?error=...`) immediately, and dispatch `UserIntent.CancelSignIn` (plus clear stale `Failed` state) when leaving `ChatGptScreen`.
* **Offline regression:**
  * Add a unit test in `ChatGptAccountTest` asserting the exact allowlisted query parameters on `authorizationUrl` for both first and returning sign-in and testing `?error=invalid_request` callbacks (with and without `state`).
  * Add a Robolectric/JVM test for `LoopbackBrowser` + `ModelAccounts` + `ChatGptScreen` verifying: (a) socket timeout matches sign-in timeout, (b) leaving `ChatGptScreen` cancels the socket and coroutine immediately, and (c) resuming `MainActivity` after Custom Tab dismissal transitions out of `WaitingForBrowser`.

---

#### C2. `CoreService.onTaskRemoved()` Stops the Foreground Service Synchronously Before `emergencyStop(TaskRemoved)` Finishes; Cold `StopTileService` Races `RunSafety.initialize()`
* **Area:** Service lifetime & safety Stop
* **Source & symbols:**
  * [CoreService.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/CoreService.kt) (`CoreService.onTaskRemoved`, `CoreService.onDestroy`)
  * [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml) (`android:stopWithTask="false"`)
  * [AndroidHost.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/AndroidHost.kt) (`serviceDestroyed`)
  * [StopTileService.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/StopTileService.kt) (`onClick`) + [RunSafety.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/RunSafety.kt), [RunSafety.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/RunSafety.kt)
  * [CoreService.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/CoreService.kt) (`StopActionReceiver.onReceive`)
* **Triggering conditions & confirmed defects:**
  1. **Swipe-away tears down foreground protection mid-Stop:** Swiping MagicHandy from Recents invokes `CoreService.onTaskRemoved()`, which calls `owner.host.finished(false)`, fires async `owner.core.emergencyStop(StopSource.TaskRemoved)`, and **immediately** calls `stopSelf()` (`CoreService.kt:114-116`). `onDestroy()` immediately releases the WakeLock and calls `stopForeground(STOP_FOREGROUND_REMOVE)`—defeating `android:stopWithTask="false"` before BLE RPC 715 (2s), Coyote zeroing (1.5s), Handy Cloud `hsp/stop` (5s), Autoblow/VacuGlide `target-speed/stop` + valve closure + 750ms resend (5s), or `RunSafety.syncStopped()` journal persistence can finish.
  2. **Orphaned live connections when service stops:** Neither `onTaskRemoved()` nor `AndroidHost.serviceDestroyed()` disconnects live `owners` in [Devices.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/Devices.kt). If the process survives, devices remain `DeviceStatus.Connected` with `host.isRunning == false` and no persistent notification / lock-screen Stop.
  3. **Cold Quick Settings tile Stop race & unretained process:** On a cold process with an armed cloud journal, `StopTileService.onClick()` calls `core.emergencyStop(StopSource.QuickSettingsTile)` synchronously before `RunSafety.init`'s async `initialize()` reads `active-run.json`. `RunSafety.emergencyStop()` sees empty `owners` and `evidence == null` and publishes an empty `"No connected device to stop"` receipt, while `stopSaved()` runs with `source = null`—and `onClick()` returns immediately with no foreground service or `goAsync()` keeping the process un-frozen during the 5-second detached cloud Stop HTTP calls. (`StopActionReceiver.onReceive()` similarly does not call `goAsync()`.)
* **Correction:**
  * In `CoreService.onTaskRemoved()`, keep foreground priority active and wait (bounded to max Stop timeout + journal flush, e.g., ~6s) for all `pending` Stop attempts and journal reconciliation to settle, then disconnect attached `owners`, clear `host.finished(false)`, and call `stopSelf()`.
  * On `serviceDestroyed`, trigger explicit disconnection of live owners so UI/device state cannot stay `Connected` without a foreground host.
  * For `StopTileService.onClick()` (and `StopActionReceiver`), ensure `RunSafety.emergencyStop()` triggers/awaits journal inspection for detached cloud targets rather than dropping to an empty receipt, and retain process execution (e.g., `goAsync()` in `StopActionReceiver`; keep a bounded wake/execution window in `StopTileService` or await settled detached cloud Stop).
* **Offline regression:**
  * Extend [ServiceTest.kt](../../app/src/test/kotlin/com/magichandy/mobile/platform/ServiceTest.kt) with a real `MotionEngine` + delayed fake owner (500ms Stop + journal sync) verifying `CoreService` does **not** call `stopSelf()` until `pending` is empty and the journal is reconciled (or the hard bound expires), and live owners are detached.
  * Add a `RunSafetyTest` case invoking `emergencyStop(StopSource.QuickSettingsTile)` immediately after constructing `RunSafety` with an armed cloud `RunJournal` (before `initialize()` completes) and asserting the cloud recovery Stop is executed and reflected in the receipt.

---

#### C3. Fenced/Stale Physical Commands on 4 Transports Are Misclassified as `WriteResult.Uncertain`, Aborting Pause and Retiring Healthy Links
* **Area:** Stale physical commands
* **Source & symbols:**
  * [AutoblowCloud.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/autoblow/AutoblowCloud.kt), [AutoblowCloud.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/autoblow/AutoblowCloud.kt) (`put`, network interceptor) + [AutoblowSession.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/autoblow/AutoblowSession.kt), [AutoblowSession.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/autoblow/AutoblowSession.kt) (`output`, `tempo`)
  * [AndroidGattLink.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/bluetooth/AndroidGattLink.kt), [AndroidGattLink.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/bluetooth/AndroidGattLink.kt) (`writeCharacteristic`, `FencedGatt`) + [CoyoteSession.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/coyote/CoyoteSession.kt) (`output`)
  * [ButtplugSocket.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/intiface/ButtplugSocket.kt) (`begin`) + [IntifaceVibrator.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/intiface/IntifaceVibrator.kt) (`output`)
  * [IntifaceLinear.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/intiface/IntifaceLinear.kt), [IntifaceLinear.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/intiface/IntifaceLinear.kt), [IntifaceLinear.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/intiface/IntifaceLinear.kt) (`write`, `anchor`, `move`)
  * [MotionEngine.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/motion/MotionEngine.kt) (`admittedWrite`, `recover`)
* **Triggering conditions & confirmed defect:**
  * During active playback, user taps **Pause**, taps **Stop**, or lowers a live device ceiling (`topSpeed` / VacuGlide `tempoRange` at `AutoblowSession.kt:220-222`, Vibrator `maxLevel` at `IntifaceVibrator.kt:91`, or Coyote `canSend` at `CoyoteSession.kt:172`) right as an output command hits the last-chance `allowed()`/`admitted()` gate before wire dispatch.
  * Zero bytes are sent, and the transport throws a fence `IOException` (`"Stale Autoblow submission"`, `FencedGatt`, `"Bridge submission refused"`, or `"Linear anchor superseded"`).
  * Unlike Handy BLE ([HandyBluetoothSession.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/handy/HandyBluetoothSession.kt)) and Handy Cloud ([HandyCloud.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/handy/HandyCloud.kt)), which properly return `WriteResult.Stale`:
    * `AutoblowSession.output()` catches **all** `IOException`s, sets `uncertain.set(true)`, and returns `WriteResult.Uncertain`.
    * `IntifaceLinear.write()` catches **all** `IOException`s, calls `fail(r)`, and returns `WriteResult.Uncertain`.
    * `CoyoteSession.output()` and `IntifaceVibrator.output()` let the fence `IOException` escape to `MotionEngine.admittedWrite()` (`MotionEngine.kt:293-296`), which records `TransportWriteFailed` and returns `WriteResult.Uncertain`.
  * `MotionEngine.admittedWrite()` (`MotionEngine.kt:298-301`) then triggers `recover(owner)`—**aborting Pause**, executing a recovery Stop across all devices, and **retiring the healthy link** (`retire = owner`) so the device cannot resume without a full reconnect.
* **Correction:**
  * Introduce typed pre-send fence exceptions (or boolean `refusedAtWrite` flags checked before throwing) in `AutoblowCloud`, `BluetoothLink`/`AndroidGattLink`, and `ButtplugSocket`.
  * When the pre-send fence rejects a write before transmission, record `DiagnosticKind.WriteFenced` and return `WriteResult.Stale` (without setting `AutoblowSession.uncertain` or calling `IntifaceLinear.fail(r)`). Note: when a *live ceiling* rejects a write while `fence.isCurrent()` is still `true`, skip that stale slice cleanly (returning `WriteResult.Accepted` or re-sampling on the next tick) instead of terminating the stream (`TimedPlayback.kt:82` stops the owner loop on any result != `Accepted`).
* **Offline regression:**
  * Add per-transport JVM tests (`AutoblowSession`, `CoyoteSession`, `IntifaceVibrator`, `IntifaceLinear`) paired with `MotionEngine` where `fence.isCurrent()` flips `false` (simulating Pause) or a ceiling lowers *inside* the `allowed()`/`admitted()` callback before wire send, asserting: (a) 0 wire bytes sent, (b) `WriteResult.Stale` (or clean skip on ceiling drop) returned, (c) `engine.state.value.retired` stays empty, and (d) Pause remains resumable (`canResume == true`).

---

### High (Blockers / Major Safety or Functional Defects)

#### H1. Returning ChatGPT Sign-In Dead-Ends in UI, Broken Keystore/Vault Locks Out Re-Sign-In, and `ConnectionStatus.NeedsSignIn` Is Never Set
* **Area:** OAuth returning sign-in & token storage
* **Source & symbols:**
  * [ChatGptScreen.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/models/ChatGptScreen.kt) + [ModelAccounts.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/ModelAccounts.kt) (`startSignIn`)
  * [ChatGptAccount.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/providers/ChatGptAccount.kt), [ChatGptAccount.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/providers/ChatGptAccount.kt), [ChatGptAccount.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/providers/ChatGptAccount.kt) (`authorize`, `token`, `storeTokens`)
  * [AndroidVault.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/storage/AndroidVault.kt), [AndroidVault.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/storage/AndroidVault.kt), [AndroidVault.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/storage/AndroidVault.kt) (`read`, `replace`, `installKey`)
  * [ResponsesProvider.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/providers/ResponsesProvider.kt), [ResponsesProvider.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/providers/ResponsesProvider.kt) + [ModelsScreen.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/models/ModelsScreen.kt)
  * [ModelAccounts.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/ModelAccounts.kt) (`saveOpenRouter`) + [DeviceSetup.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/DeviceSetup.kt) (`cloud`)
* **Triggering conditions & confirmed defects:**
  1. **Returning sign-in never closes/advances UI:** `ModelAccounts.startSignIn()` always writes `ConnectionId("chatgpt")`. `ChatGptScreen.kt:42-43` snapshots `before = rememberSaveable { signedIn }` and only invokes `onSaved()` when `signedIn.any { it !in before }`. When ChatGPT is already saved (re-authenticating from Models or revisiting Step 2 from Setup Step 4), `"chatgpt"` is already in `before`; sign-in silently resets to `Idle` and never navigates away.
  2. **Keystore/vault corruption permanently blocks re-sign-in (contradicts [mobile-system-design.md](../../docs/mobile-system-design.md) §6):** `ChatGptAccount.authorize()` calls `vault.get(handle)` *before* launching the browser (`ChatGptAccount.kt:102`), and `storeTokens()` uses `vault.replace()` (`ChatGptAccount.kt:252`), which also calls `read(handle)` (`AndroidVault.kt:94`). If `magichandy.vault.1` becomes unusable or ciphertext is corrupt, `AndroidVault.read()` throws `IOException("Vault unavailable")` and `ChatGptAccount.signIn()` reports `SignInFailure.NetworkError` ("Couldn't reach ChatGPT")—blocking the user from even starting re-sign-in. Worse, `saveOpenRouter()` and `DeviceSetup.cloud()` call `d.vault.get()` across *all* existing rows for duplicate checks, so one unreadable vault entry blocks adding any new OpenRouter key or cloud device.
  3. **`ConnectionStatus.NeedsSignIn` is never emitted:** When OAuth refresh fails (`Rejected` on HTTP 400/401, missing token, or unusable vault), `ChatGptAccount.token()` returns `null` or throws `IOException`, `ResponsesProvider.send()` collapses both into `TextResult.Interrupted`, and no code path ever updates `ModelConnection.status` to `ConnectionStatus.NeedsSignIn`. `ModelsScreen` keeps showing green `Ready` while every turn fails generically.
* **Correction:**
  * Track sign-in completion by a monotonic completion event/transition (`WaitingForBrowser -> Succeeded`) in `SignInState` (or explicit callback/sequence counter) rather than checking whether `"chatgpt"` was absent before opening `ChatGptScreen`.
  * On explicit user-initiated `authorize()`, tolerate an unreadable existing vault record (overwrite via `vault.put()` rather than failing on `vault.get()`/`replace()`; reserve CAS `replace()` for background `token()` refresh), and in `AndroidVault`, regenerate the Keystore alias if `KeyPermanentlyInvalidatedException` / key corruption occurs on encrypt after wiping unreadable ciphertext. Guard duplicate-check `vault.get()` loops against corrupt unrelated entries.
  * Propagate auth-required failures (`token() == null` / vault unavailable / HTTP 401 from Responses) into `ModelStore` status `ConnectionStatus.NeedsSignIn` so Chat and Models prompt the user to sign in again.
* **Offline regression:**
  * `ChatGptAccountTest`: test `signIn()` succeeding when `vault.get(handle)` throws `IOException("Vault unavailable")` (unusable key), and test `token()` returning auth-expired/unreadable status propagating to `ConnectionStatus.NeedsSignIn` in `MobileSession`.
  * Compose UI test for `ChatGptScreen`: pre-populate `ConnectionId("chatgpt")`, run `StartChatGptSignIn` -> completion, assert `onSaved()` is called.

---

#### H2. VacuGlide Recovery Gaps: Manual Emergency Stop Skips Awaiting-Return VacuGlide, and `retryRecovery()` Clears `awaitingReturn` Before Stop Completes
* **Area:** Recovery & safety Stop
* **Source & symbols:**
  * [RunSafety.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/RunSafety.kt) (`emergencyStop`)
  * [RunSafety.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/RunSafety.kt) (`retryRecovery`)
  * [RunSafety.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/RunSafety.kt) (`canForget`)
* **Triggering conditions & confirmed defects:**
  1. **Emergency Stop omits awaiting-return VacuGlide:** After a VacuGlide drops during a run, it stays in `lostPersistent` (`awaitingReturn`) until a return-time Stop or local check completes. If its loss-time Stop happened to report `StopOutcome.Confirmed` in `state.evidence`, `RunSafety.emergencyStop()` (`RunSafety.kt:179-190`) filters detached cloud devices by `it.outcome != StopOutcome.Confirmed` and never inspects `lostPersistent`—so an explicit manual Stop skips that VacuGlide.
  2. **Premature clearing of `lostPersistent`:** `RunSafety.retryRecovery()` (`RunSafety.kt:406-418`) launches an async Stop (`stopSaved` or `engine.stop`) and **synchronously removes** the device from `lostPersistent` (`lines 409, 417`) before the Stop executes or confirms. If the return-time Stop fails, times out, or is superseded (especially in the `saved == null` branch at lines 410-418), the `awaitingReturn` requirement is already erased.
* **Correction:**
  * Include all detached `lostPersistent.value.values` (not in `state.owners` and not in `state.reconciled`) in `RunSafety.emergencyStop()`'s detached cloud targets.
  * Keep devices in `lostPersistent` until their return-time Stop attempt actually completes with `StopOutcome.Confirmed` (or explicit `checkedLocally(id)` reconciliation), clearing `lostPersistent` from the Stop confirmation observer rather than optimistically in `retryRecovery()`.
* **Offline regression:**
  * In [RunSafetyTest.kt](../../core/src/test/kotlin/com/magichandy/mobile/core/session/RunSafetyTest.kt), simulate a VacuGlide link loss whose initial Stop confirms while still in `lostPersistent`: (a) assert manual `emergencyStop` still sends a detached cloud Stop to it, and (b) simulate `retryRecovery()` where the return-time Stop returns `Unconfirmed(NoReply)` and assert `awaitingReturn` stays populated and `canForget(id)` remains `false`.

---

#### H3. Switching Motion Mode to `Off` While Autopilot Is Active Before First Motion Crashes the Planner and Wedges `AutopilotStatus.Active`
* **Area:** Service lifetime, Autopilot & safety Stop
* **Source & symbols:**
  * [MobileSession.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/MobileSession.kt), [MobileSession.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/MobileSession.kt) (`dispatch`, `setMotionMode`)
  * [Autopilot.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/Autopilot.kt), [Autopilot.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/Autopilot.kt) (`start`, `decision`)
  * [Planner.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/autopilot/Planner.kt) (`decide`) + [ChatContract.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/chat/ChatContract.kt), [ChatContract.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/chat/ChatContract.kt) (`compose`, `templateKey`)
* **Triggering conditions & confirmed defect:**
  * Device is connected (`owners` non-empty) and idle (`members` empty). User taps **Start Autopilot**; `Autopilot.start()` activates (`status = Active`, `owner = epoch`) and begins its initial `decision("plan")` call.
  * Before the first motion score applies, user sets Motion Mode to `Off`. Both `MobileSession.dispatch()` (`line 295`) and `setMotionMode()` (`line 427`) skip `engine.stop(null)` because `members.isEmpty()`.
  * `MobileSession`'s settings collector calls `autopilot.refresh()` (`MobileSession.kt:187`). `Autopilot.worker` does not check `motionMode == MotionMode.Off` and calls `decision("plan")` with `TurnContext(MotionMode.Off, ...)`, which looks up `"<locale>/<tier>/off/plan"` in `contracts.json`. `templates.getValue(key)` throws `NoSuchElementException`, which is uncaught by `Autopilot.decision()` (catches only `IOException`), killing `worker` without calling `reset()`. `Autopilot` stays wedged in `AutopilotStatus.Active` with `CoreService.needsHost == true`.
* **Correction:**
  * In `MobileSession.dispatch()` and `setMotionMode(MotionMode.Off)`, stop/turn off Autopilot unconditionally (`if (engine.state.value.members.isNotEmpty() || ui.value.autopilot.status != AutopilotStatus.Off) engine.stop(null)` and `autopilot.off()`).
  * In `Autopilot.worker` (`Autopilot.kt:133`), guard `ui.value.settings.motionMode == MotionMode.Off -> off()`, and guard `ChatContract.compose()` / `Autopilot.decision()` against missing template keys.
* **Offline regression:**
  * Core session test: connect a simulated device, dispatch `StartAutopilot` with a suspended fake planner, dispatch `SetMotionMode(MotionMode.Off)` before the first plan returns, assert `AutopilotStatus.Off`, no `CoreTaskFailed` diagnostic, and no physical writes when the planner releases.

---

#### H4. `AndroidGattLink.setActive(false)` Throws Uncaught `SecurityException` After an Acknowledged BLE Stop
* **Area:** Safety Stop / Bluetooth
* **Source & symbols:**
  * [AndroidGattLink.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/bluetooth/AndroidGattLink.kt) (`setActive`) vs [AndroidGattLink.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/bluetooth/AndroidGattLink.kt) (`retire`)
  * [HandyBluetoothSession.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/handy/HandyBluetoothSession.kt) (`BluetoothWire.stop`)
  * [CoyoteSession.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/coyote/CoyoteSession.kt) (`halt`)
  * [StopDelivery.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/motion/StopDelivery.kt)
* **Triggering conditions & confirmed defect:**
  * If `BLUETOOTH_CONNECT` is revoked or Binder rejects `requestConnectionPriority()`, `AndroidGattLink.setActive(false)` throws an uncaught `SecurityException` (unlike `retire()`, which catches it).
  * In `HandyBluetoothSession.kt:317`, `if (acknowledged) link.setActive(false)` executes *after* RPC 715 Stop was confirmed and *outside* the `try/catch (IOException)`. The exception escapes to `StopDelivery.kt:47-50`, turning a confirmed hardware Stop into `Unconfirmed(WriteFailed)`.
  * In `CoyoteSession.kt:185`, `link.setActive(false)` runs *before* awaiting the zero-strength B1 notification and is not caught by `catch (_: IOException)`, aborting confirmation wait after `writeStop` succeeded.
* **Correction:**
  * Wrap `requestConnectionPriority` in `AndroidGattLink.setActive()` in `try { ... } catch (_: SecurityException) {} catch (_: IllegalStateException) {}` (it is a best-effort power hint, never safety-critical).
* **Offline regression:**
  * Add a unit test in Handy BLE and Coyote session tests with a fake `BluetoothLink` whose `setActive(false)` throws `SecurityException`, asserting `stop()` still returns `StopEvidence.Acknowledged` when the hardware reply arrives.

---

#### H5. Cold-Start Async Room Load Flashes `BeforeYouStartScreen`, Drops Notification `openLive`, and Can Clobber Restored Editor Drafts
* **Area:** Actual production graph, setup, persona storage/restore
* **Source & symbols:**
  * [PlatformGraph.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/PlatformGraph.kt) + [RoomStores.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/storage/RoomStores.kt) (`prefs`, `ready`)
  * [InitialState.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/InitialState.kt)
  * [MagicHandyRoot.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/MagicHandyRoot.kt), [MagicHandyRoot.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/MagicHandyRoot.kt)
  * [CompanionStepScreen.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/setup/CompanionStepScreen.kt) + [PersonaEditorScreen.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/personas/PersonaEditorScreen.kt)
* **Triggering conditions & confirmed defects:**
  * `RoomStores` publishes default `SettingsDocument()` (`adultsOnly = false`, `isSetupDone = false`, `revision = 0`) while `ready` loads SQLite asynchronously on `Dispatchers.IO`.
  * On every cold start of a returning user, `AppContent` transiently renders `BeforeYouStartScreen`.
  * If `MainActivity` is cold-started/recreated from tapping the ongoing notification (`openLive = true`, [MainActivity.kt](../../app/src/main/kotlin/com/magichandy/mobile/MainActivity.kt)), `LaunchedEffect(openLive)` (`MagicHandyRoot.kt:111-116`) fires on frame 1 while `isGated == true`, skips `nav.openLive()`, and immediately clears `openLive` via `onLiveOpened()`.
  * If `CompanionStepScreen` or `PersonaEditorScreen` (for built-in MagicHandy) composes before `ready` finishes, `rememberSaveable { mutableStateOf(saved) }` freezes default `Personality()` into `draft`; once Room loads, `draft != saved` looks dirty with overwritten fields.
  * If SQLite open/migration throws (`RoomStores.kt:75-77`), the failure stays silent inside `ready` until the user attempts a write, trapping them on `BeforeYouStartScreen`.
* **Correction:**
  * Expose an explicit `isStorageReady` / `storageLoadFailed` flag (or await initial Room load behind a neutral splash/blank frame with the persistent Stop bar intact) before routing gates, consuming `openLive`, or initializing screen drafts. Do not call `onLiveOpened()` until storage is ready.
* **Offline regression:**
  * Robolectric test in `ReleaseGraphTest` / UI test: pre-seed `magichandy.db` with `adultsOnly = true, isSetupDone = true` and a custom `builtInPersonality`, open a fresh `platformCore(app)` + `AppContent(openLive = true)` with a delayed IO load, and assert `BeforeYouStartScreen` is never committed as the settled gate and `nav.current == Route.Live` once loaded.

---

### Medium (Functional, UX, Privacy Disclosure & Packaging Issues)

#### M1. Four-Step Setup: Changing AI From Step 4 Fails to Switch the Active Chat Model; Skipping Step 3 Leaks Unsaved Draft on Revisit
* **Area:** New four-step setup, skip/back
* **Source & symbols:**
  * [ReadyStepScreen.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/setup/ReadyStepScreen.kt), [ReadyStepScreen.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/setup/ReadyStepScreen.kt)
  * [MagicHandyRoot.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/MagicHandyRoot.kt), [MagicHandyRoot.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/MagicHandyRoot.kt) (`RouteStates`, `SetupStep`)
  * [RoomStores.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/storage/RoomStores.kt) (`models.save`)
  * [CompanionStepScreen.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/setup/CompanionStepScreen.kt), [CompanionStepScreen.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/setup/CompanionStepScreen.kt)
  * [AddDeviceScreen.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/devices/AddDeviceScreen.kt), [AddDeviceScreen.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/devices/AddDeviceScreen.kt)
* **Triggering conditions & confirmed defects:**
  1. **Cannot replace Chat model from Step 4:** User saves Model A in Step 2 -> reaches Step 4 -> taps the Chat model row (`nav.popTo(Route.Setup(2))`) -> adds Model B. `RoomStores.models.save()` uses `chat = it.chat ?: model.info.id`, leaving `chat` pinned to Model A; Step 4 still displays Model A with no in-setup way to switch or remove it.
  2. **Skipped Step 3 draft survives Back / Companion tap:** On Step 3 (`CompanionStepScreen`), user edits explicitness/style/description and taps **Skip for now** (`onNext`). Because `Route.Setup(3)` stays on `nav.stack`, `RouteStates` retains its `rememberSaveable` `draft`. Returning via Back or Step 4's Companion row restores the skipped draft, so tapping **Continue** silently saves it.
  3. *(Minor)* Opening "Add a device" from Step 4 renders hardcoded `"Devices › Add"` / `"Back to Devices"` chrome in `AddDeviceScreen`.
* **Correction:**
  * Bind the newly saved model to chat (via `SetChatConnection` or an explicit `bindAsChat` flag on setup saves) when saved from Setup (`Route.Setup`) or Chat's `Route.ChooseAi`.
  * Reset `draft = saved` inside `CompanionStepScreen`'s Skip handler before calling `onNext()`.
  * Pass setup origin/back-label into `AddDeviceScreen` when opened from Step 4.
* **Offline regression:**
  * UI navigation test covering: Step 2 save Model A -> Step 4 tap Model -> Step 2 save Model B -> assert Step 4 shows Model B; and Step 3 edit -> tap Skip -> Step 4 Back -> assert Step 3 shows saved defaults, not the skipped draft.

---

#### M2. Local Refusal Fallback: Stream Preview Contamination, `CompatibleProvider` Budget Mismatch, Memory Bleed-Back, and Disclosure Gap
* **Area:** Local fallback privacy & execution
* **Source & symbols:**
  * [ChatTurns.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/ChatTurns.kt), [ChatTurns.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/ChatTurns.kt) (`stream`, `commitReply`)
  * [ChatProposal.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/ChatProposal.kt) (`complete`, `ask`)
  * [ChatContract.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/chat/ChatContract.kt), [ChatContract.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/chat/ChatContract.kt) (`compose`, `render`, `messages`)
  * [CompatibleProvider.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/providers/CompatibleProvider.kt) (`send`)
  * [PersonaPrompt.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/chat/PersonaPrompt.kt) (`profile`)
  * [strings.xml](../../app/src/main/res/values/strings.xml) (`refusal_desc`)
* **Triggering conditions & confirmed defects:**
  1. **Stream preview mixes primary refusal text with fallback reply:** `ChatTurns.stream()` allocates a single `buffer` (`ChatTurns.kt:247`) and passes the same `preview` callback into `proposals.complete()`. If the hosted provider streams partial output deltas before emitting `TextResult.Refused` (e.g., [ResponsesProvider.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/providers/ResponsesProvider.kt) or [CompatibleProvider.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/providers/CompatibleProvider.kt)), `ChatProposal.kt:174-177` invokes the local fallback with the same `preview` without resetting `buffer`, so `ReplyState.Streaming` displays the hosted refusal prefix concatenated with the local fallback's reply until final commit.
  2. **Output-allowance mismatch can interrupt admitted local fallback turns:** For `Simple`/`Intermediate` LAN servers, `ChatContract.compose()` budgets `output = 256` (Short) or `512` (Detailed) (`ChatContract.kt:117-123`). `CompatibleProvider.send()` then recomputes `PromptBudget` with `output = maxOf(1536, contract?.outputAllowance ?: 0)` (`CompatibleProvider.kt:118-127`), shrinking `limitBytes` by 1,024–1,280 bytes. A prompt admitted by `ChatContract.compose()` near a 16K context ceiling (e.g., `Simple` local server with `Layered` schema + 500-grapheme persona description) can fail `budget.admit()` inside `CompatibleProvider` and return `TextResult.Interrupted`.
  3. **Memory bleed-back after hosted refusal:** When a turn starting with `"Remember ..."` is refused by the hosted provider and answered by the local fallback, `ChatTurns.commitReply()` (`ChatTurns.kt:318-327`) still extracts and persists `requestedMemory(turn.message)` into the active persona's memory, causing the refused message's extracted detail to be sent back to the **hosted** provider on subsequent turns.
  4. **Disclosure vs. actual LAN payload:** Although `ChatProposal.kt:145, 171-172` rightly strips prior chat history (`history.takeLast(1)`), saved `memories`, and `recentRequests`, `localContext` still sends the custom persona's **name and free-text description**, `MotionStyle`, and full live **device/motion state** (`current_score` / `current_tempo` / `previous_tempo`, `air_steps_this_session`, `saved_limits`, `engine_envelope`, `vibrator` capabilities)—more than `refusal_desc` ("send that message once, with the persona's wording instructions") tells the user, often over unencrypted LAN HTTP.
* **Correction:**
  * Provide a `resetPreview: () -> Unit` callback (or clear `buffer` and publish `ReplyState.Streaming("")`) whenever `ChatProposal` transitions from the primary provider to the local fallback.
  * Align `CompatibleProvider` (and `OllamaProvider`) output token reservation with `contract.outputAllowance` instead of forcing `maxOf(1536, ...)` for `Simple`/`Intermediate` local models.
  * Skip `requestedMemory()` persistence on turns resolved via local refusal fallback (or mark the completion as fallback-sourced so it does not seed hosted-visible memories).
  * Update `refusal_desc` across all 5 locales to state accurately that the single message, persona wording/description, and current motion/device context are sent to the chosen local server (or strip device context if motion control is not desired on fallback).
* **Offline regression:**
  * Core session test: primary hosted `StructuredProvider` emits partial `"{\"reply\":\"Sorry, "` before returning `TextResult.Refused`; local `CompatibleProvider` (16K `Simple`, `Layered`, 500-grapheme persona) answers `"{\"action\":\"none\",\"edits\":{},\"reply\":\"Fallback ok\"}"` on a `"Remember that ..."` message. Assert: (a) streamed preview after fallback contains no `"Sorry, "` prefix, (b) `CompatibleProvider` admits and completes the request rather than returning `Interrupted`, and (c) no memory row from the refused turn is stored for future hosted calls.

---

#### M3. Persona Attribution Bugs in Chat UI for Upgraded Legacy Transcripts and Mid-Turn Persona Switches
* **Area:** Persona isolation, storage & restore
* **Source & symbols:**
  * [ChatScreen.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/chat/ChatScreen.kt), [ChatScreen.kt](../../app/src/main/kotlin/com/magichandy/mobile/ui/chat/ChatScreen.kt) (`Transcript`)
  * [ChatTurns.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/ChatTurns.kt), [ChatTurns.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/ChatTurns.kt) (`send`, `commitReply`)
  * [PersonaStorageTest.kt](../../app/src/test/kotlin/com/magichandy/mobile/platform/PersonaStorageTest.kt) (confirms upgraded pre-v3 messages decode with `speaker == null`)
* **Triggering conditions & confirmed defects:**
  1. **Upgraded legacy messages retroactively change speaker:** Pre-v3 chats migrated to Room v3 have `ChatEntry.Message.speaker == null` (all originally authored by built-in `MagicHandy`). `ChatScreen.kt:225` falls back via `entry.speaker ?: chat.personaName`. Switching to a custom persona (e.g., `"Kit"`) causes every historical pre-upgrade message in the transcript to display `"Kit"` instead of `"MagicHandy"`.
  2. **Live streaming bubble mislabels speaker after mid-turn switch:** `ChatTurns.send()` captures `turn.persona` at turn start and commits `reply.copy(speaker = turn.persona.name)`. However, `ReplyState.Streaming` carries only `text`, so `ChatScreen.kt:204` labels the streaming bubble with `chat.personaName` (the *newly selected* persona). Switching personas while a reply streams shows the wrong persona name live, then snaps back to the original speaker on commit.
* **Correction:**
  * Fall back `entry.speaker` in `ChatScreen.kt:225` to the built-in persona name (`state.settings.personas.firstOrNull { it.isBuiltIn }?.name ?: stringResource(R.string.app_name)`), never the currently active `chat.personaName`.
  * Carry the captured speaker name on `ReplyState.Streaming` (additive field with default) or display the turn's captured speaker in `ChatScreen.kt:204`.
* **Offline regression:**
  * Compose UI / Robolectric test using a migrated v2->v3 chat entry (`speaker = null`): switch active persona to `"Kit"` and assert the legacy message header still shows `"MagicHandy"`.
  * Core + UI test: start streaming under `"MagicHandy"`, dispatch `UserIntent.SetPersona(kitId)` before completion, and assert both the streaming bubble and committed bubble display `"MagicHandy"`.

---

#### M4. Release Packaging, R8 Verification Gaps, Update-Feed/Diagnostics Drift, and Production-Graph Defensive Gaps
* **Area:** Release packaging & actual production graph
* **Source & symbols:**
  * [app/proguard-rules.pro](../../app/proguard-rules.pro) + [app/build.gradle.kts](../../app/build.gradle.kts), [app/build.gradle.kts](../../app/build.gradle.kts), [app/build.gradle.kts](../../app/build.gradle.kts) (`CheckReleaseContents`)
  * [PersonaPrompt.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/chat/PersonaPrompt.kt) (`Voice`, `Vocabulary`)
  * [PlatformDatabase.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/storage/PlatformDatabase.kt) (`exportSchema = false`)
  * [MobileReleases.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/http/MobileReleases.kt) + [docs/release.md](../../docs/release.md)
  * [DiagnosticsExport.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/DiagnosticsExport.kt) (`report`)
  * [network_security_config.xml](../../app/src/main/res/xml/network_security_config.xml) + [PlatformGraph.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/PlatformGraph.kt)
  * [PlatformDevices.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/PlatformDevices.kt), [PlatformDevices.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/PlatformDevices.kt), [PlatformDevices.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/PlatformDevices.kt) (`saved.first { ... }`)
* **Triggering conditions & confirmed defects:**
  1. **Narrow R8 verification & no schema export:** `CheckReleaseContents` checks only `PlatformDatabase`, `PlatformDatabase_Impl`, and `SettingsDocument$$serializer` (plus 3 resource files). It does not verify `PersonaPrompt.Vocabulary`/`Voice` (private `@Serializable` types decoded via `Json.decodeFromString<Map<String, Vocabulary>>`), `RunJournal`, `SavedDevice`, `SavedModel`, `ModelCatalog`, `ChatSummary`, `ChatEntry`, `PersonaRef`, or `Personality`, and no instrumented test exercises the minified APK. `PlatformDatabase` also sets `exportSchema = false`, leaving no checked-in Room schema history for future upgrades.
  2. **Release pipeline & update feed readiness gaps:** `./tools/release/build-release.ps1` and `./tools/release/test-release.ps1` referenced in `docs/release.md` are absent from this snapshot; without `keystore.properties`, `:app:assembleRelease` silently builds an unsigned APK; `compileSdk = 37` pins a preview/local-PC SDK; and `MobileReleases.check()` targets `/repos/MagicHandy/MagicHandy-Mobile/releases/latest`, which `docs/release.md:63` notes is currently private—guaranteeing `UpdateCheck.Failed` in production until the repo is public with a published stable release.
  3. **`deviceTest` diagnostics report drops version:** `DiagnosticsExport.kt:59-61` regex `[0-9]{1,8}(\.[0-9]{1,8}){1,3}` rejects `0.1.0-device-test`, emitting `"App version unknown"` on every phone-acceptance diagnostics export.
  4. **Global cleartext config without base-client HTTPS guard & fragile `saved.first` lookups:** `network_security_config.xml` enables cleartext globally; the shared base `OkHttpClient` in `PlatformGraph.kt:42-50` does not enforce HTTPS for non-LAN callers (relying purely on hardcoded `https://` URLs). Separately, `PlatformDevices.kt:160, 196, 245` call `saved.first { it.ref.id == device }` (unlike `reversed()` at line 126, which uses `firstOrNull`), risking `NoSuchElementException` if a device is removed concurrently with a live session callback.
* **Correction:**
  * Expand `CheckReleaseContents` (and/or add an androidTest smoke suite against the minified `deviceTest` APK) to verify all persisted and prompt `@Serializable` serializers—specifically `PersonaPrompt$Vocabulary$$serializer`, `RunJournal$$serializer`, `ModelCatalog$$serializer`, `SavedDevice$$serializer`, `ChatEntry$$serializer`, and `Personality$$serializer`—and enable `exportSchema = true` for `PlatformDatabase`.
  * Accept `-device-test` in `DiagnosticsExport.kt:59-61` (matching `MobileReleases.kt:28`).
  * Attach a cleartext-blocking interceptor on the base `http` client in `PlatformGraph.kt` and only allow HTTP on the private-origin-pinned `originClient` builder.
  * Replace `saved.first { ... }` with `saved.firstOrNull { ... }` + safe fallback (zero limits / previous snapshot) in `PlatformDevices.kt:160, 196, 245`.
  * Restore/verify the release signing scripts, pin a stable `compileSdk`, and either make `MagicHandy-Mobile` releases publicly readable before launch or gate/update the feed URL.
* **Offline regression:**
  * Unit test in `DiagnosticsExportTest` passing `versionName = "0.1.0-device-test"` and asserting `"App version 0.1.0-device-test"`.
  * Unit test on `PlatformGraph`'s base HTTP client asserting `http://example.com` is rejected while `originClient` permits `http://192.168.x.x`.
  * Unit test in `PlatformDevices` (or session callback test) verifying removing a saved row while a `Coyote`/`Autoblow`/`Vibrator` config lambda is read does not throw `NoSuchElementException`.
  * Expand `CheckReleaseContents` in `app/build.gradle.kts` to fail build if any persisted or `PersonaPrompt` serializer is stripped by R8.

---

## Favorable Evidence Confirmed in Source

These areas were checked directly in this snapshot and are cleanly implemented:
* **Backup & transfer isolation:** `android:allowBackup="false"`, `android:fullBackupContent="false"`, comprehensive `<exclude>` rules for all domains in [data_extraction_rules.xml](../../app/src/main/res/xml/data_extraction_rules.xml), and Room DB / vault / run journal / agent-host ID all placed under `noBackupFilesDir`.
* **Local network & SSRF enforcement:** [LocalAddress.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/providers/LocalAddress.kt) validates private/loopback/link-local/ULA addresses across DNS answers, numeric IP literals, and actual connected socket routes with `Proxy.NO_PROXY` and redirects disabled.
* **Live revocation of local refusal fallback:** [ChatProposal.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/ChatProposal.kt) (`whileAuthorized`) cancels an in-flight fallback call immediately if the user turns off fallback, removes the server, or loses the LAN route; [SessionForms.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/SessionForms.kt) clears `refusalFallback` when removing that model.
* **Persona memory isolation & deletion durability:** [RoomStores.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/storage/RoomStores.kt), [RoomStores.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/storage/RoomStores.kt) delete the persona row, its memories, and selected-persona fallback atomically in one SQLite transaction, and guard `append()` with `dao.hasPersona(persona.value)` so a late in-flight reply cannot resurrect memories for a deleted persona.
* **Crash-safe journal & cold-start storage wait for cloud Stop:** [AtomicBytes.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/storage/AtomicBytes.kt) fsyncs both the temp file and parent directory across `ATOMIC_MOVE`; [PlatformDevices.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/PlatformDevices.kt), [PlatformDevices.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/PlatformDevices.kt), [PlatformDevices.kt](../../app/src/main/kotlin/com/magichandy/mobile/platform/PlatformDevices.kt) properly `awaitReady()` before reading saved rows.
* **Handy BLE & Handy Cloud fence handling:** Both [HandyBluetoothSession.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/handy/HandyBluetoothSession.kt) and [HandyCloud.kt](../../transport/src/main/kotlin/com/magichandy/mobile/transport/handy/HandyCloud.kt) correctly classify pre-send fenced writes as `WriteResult.Stale` rather than `Uncertain`.
* **Android 14+ FGS permission & translation completeness:** [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml), [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml) declare `CHANGE_NETWORK_STATE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` (satisfying connectedDevice eligibility even without Bluetooth permissions), and all 5 locales (`en`, `es`, `pt-rBR`, `ja`, `b+zh+Hans`) contain complete `strings.xml` (including `form_too_long` and `form_limit_reached`) and `platform_strings.xml` entries.
* **Redacted diagnostics export:** [DiagnosticsExport.kt](../../core/src/main/kotlin/com/magichandy/mobile/core/session/DiagnosticsExport.kt) outputs only numeric/enum allowlisted fields, and [StorageTest.kt](../../app/src/test/kotlin/com/magichandy/mobile/platform/StorageTest.kt) confirms `FileProvider` scopes shares strictly to `cache/diagnostics/`.

---

## Evidence Matrix: Synthetic Checks vs. Required Real Acceptance Gates

| Area | What Synthetic / Offline Checks Cover in This Snapshot | Real Device / Provider Acceptance Still Unverified (Required Before Release) |
|---|---|---|
| **Production Graph & Cold Start** | [ReleaseGraphTest.kt](../../app/src/test/kotlin/com/magichandy/mobile/platform/ReleaseGraphTest.kt) boots `platformCore` on Robolectric SDK 33 and dispatches only `AcknowledgeAdultsOnly`. | Cold start, process-death restore, notification `openLive` launch, and minified APK execution on Android 14–16 (API 34–36). |
| **ChatGPT OAuth & Vault** | [ChatGptAccountTest.kt](../../transport/src/test/kotlin/com/magichandy/mobile/transport/providers/ChatGptAccountTest.kt) uses `MemoryHttp`, `"fixture:/oauth-return"`, and `InMemoryVault`; [BrowserTest.kt](../../app/src/test/kotlin/com/magichandy/mobile/platform/BrowserTest.kt) uses raw JVM `Socket`; [StorageTest.kt](../../app/src/test/kotlin/com/magichandy/mobile/platform/StorageTest.kt) uses a raw `SecretKeySpec` (not Android Keystore). | Live `auth.openai.com` first & returning sign-in from Chrome Custom Tabs on Android (already observed failing on `ext_agent_host_id`), loopback delivery under Android Cached Apps Freezer, live token rotation, and real Android Keystore key invalidation/recovery. |
| **Service Lifetime & Stop** | [ServiceTest.kt](../../app/src/test/kotlin/com/magichandy/mobile/platform/ServiceTest.kt) mocks `core.emergencyStop` into a list on Robolectric SDK 33; does not test real async Stop completion before `stopSelf()`. | Swipe-away from Recents (`onTaskRemoved`) while devices are physically moving; lock-screen notification Stop & public privacy; cold-process Quick Settings tile Stop for detached cloud devices; screen-off WakeLock/Doze stability. |
| **Physical Devices & Stale Commands** | JVM unit tests over simulated/fake transports and `RunSafetyTest` with `FakeOwner`. | Real BLE Handy (MTU, GATT 133, RPC 715 Stop latency, clock sync, mounting reversal), Handy Cloud (`platform_handy_application_id` acceptance), Coyote 3.0 (100ms cadence, B1 zero confirmation, permission revocation), Autoblow Ultra & VacuGlide (pause/ceiling fence, SSE button stream, loss/return Stop, check-in timeout), Intiface Central 3.2.2+ (bridge kill, axis re-ID, Pause/Stop during anchoring). |
| **Local Fallback & Setup / Personas** | [LocalContractTest.kt](../../core/src/test/kotlin/com/magichandy/mobile/core/chat/LocalContractTest.kt), [PersonaPromptTest.kt](../../core/src/test/kotlin/com/magichandy/mobile/core/chat/PersonaPromptTest.kt), [PersonaStorageTest.kt](../../app/src/test/kotlin/com/magichandy/mobile/platform/PersonaStorageTest.kt), [DebugPersonaGraphTest.kt](../../app/src/test/kotlin/com/magichandy/mobile/platform/DebugPersonaGraphTest.kt) verify offline prompt/DB contracts. | Real Ollama / llama.cpp LAN refusal fallback on Wi-Fi, 5-locale + IME + TalkBack testing of 4-step setup and persona editor, and real Android backup/transfer verification. |
| **Release Packaging** | `CheckReleaseContents` checks 3 DEX class names + 3 resource entries; no on-device minified run. | Clean install + v2->v3 upgrade of an owner-signed minified APK on physical Android 14–16 hardware, R8 runtime verification of all serializers (including `PersonaPrompt` and Room stores), and live public update-feed check. |

---

## Final Verdict & Release Gate Order

**Verdict: NO-GO.** Hold release until the following gates are closed in order:

1. **Blockers (Must fix + add offline regressions before phone test):**
   * **C1 & H1:** Fix the `auth.openai.com` OAuth request/callback contract, `LoopbackBrowser` Custom Tab task/lifecycle/cancel handling, returning sign-in completion in `ChatGptScreen`, unusable-Keystore re-sign-in lockout, and `ConnectionStatus.NeedsSignIn` propagation.
   * **C2, H2, H3, H4:** Make `CoreService.onTaskRemoved()` await bounded Stop + journal completion and detach live owners; fix cold `StopTileService` journal race; include `awaitingReturn` VacuGlides in manual `emergencyStop` and stop clearing `lostPersistent` optimistically; stop Autopilot cleanly on `SetMotionMode(Off)`; catch `SecurityException` in `AndroidGattLink.setActive()`.
   * **C3:** Classify pre-send fenced writes as `WriteResult.Stale` (and handle live ceiling drops without retiring links) on `AutoblowSession`, `CoyoteSession`, `IntifaceVibrator`, and `IntifaceLinear`.
   * **H5:** Gate root routing, notification `openLive`, and draft initialization on initial Room store readiness.
2. **Pre-release fixes (Medium):**
   * **M1–M4:** Fix Step 4 model replacement and Step 3 Skip draft reset; clear streaming preview buffer on fallback, align `CompatibleProvider` output budget, prevent fallback memory bleed-back, and tighten `refusal_desc`; fix legacy/streaming persona speaker attribution in `ChatScreen`; harden `PlatformDevices` `firstOrNull`, base-client HTTPS guard, `DiagnosticsExport` `-device-test` version regex, `CheckReleaseContents` serializer coverage, Room `exportSchema = true`, and release signing/public feed readiness.
3. **Real acceptance gate (Must execute on physical Android 14–16 device before shipping):**
   * Complete the owner-signed minified APK checks and physical device/provider matrix in [device-checklist.md](../../docs/backend/device-checklist.md), specifically confirming live `auth.openai.com` first + returning sign-in, swipe-away Stop on real hardware, cold Quick Settings tile cloud Stop, Pause/ceiling changes on real transports, and v2->v3 upgrade on a minified build.
