# MagicHandy Mobile: system design

- **Date:** 2026-11-18, revised 2026-11-19 after the second Astra 6.2 review (plan §17).
- **Companions:**
  - the [architecture plan](../android-architecture-plan.md);
  - the [design canvas](https://claude.ai/artifact/YH74sVUDiQCDpJ9p8dJdZU), whose "v1 direction" page shows these behaviours.
- **Rules:** code, formatting and documentation follow plan §5.5 (maintainability and clarity).
- **Status.** The release and device-test builds use Android storage, Bluetooth, network adapters and a foreground service. The debug build runs simulated peers. Firmware and provider acceptance are tracked in the [phone checklist](backend/device-checklist.md).

These designs answer one question: what keeps the phone app safe and predictable when Android, the radio or the network misbehaves?

## 1. Processes and lifecycle

| Component | Role |
|---|---|
| `MagicHandyApp` (Application) | Owns the core container: motion engine, device manager, chat, Autopilot and storage. |
| `CoreService` (foreground, type `connectedDevice`) | Keeps the process alive while a device is connected, a run is live or Autopilot is on, and owns the ongoing notification. |
| `MainActivity` (Compose) | UI only. It can be destroyed and recreated at any time, and redraws from core state. |
| `StopTileService` | The Quick Settings "Stop motion" tile. |
| `StopActionReceiver` | The notification's one action, Stop. |
| `:inference` process (after v1) | On-device model runtimes, isolated so they can't take down the core. |

**When the foreground service runs.** It runs while any of these is true:

- a device is connected;
- a run is playing or paused;
- Autopilot is on.

When none apply, the service stops and the core holds no timers, sockets or wake locks. A foreground service improves the process's chances of survival, but it can't guarantee network access, CPU time or a cleanup callback. M0 checks the permitted service type, permissions and start deadlines on the Pixel.

**Chat-only replies** (with no device involved) don't start the service and stay tied to the visible screen. If the app moves to the background mid-reply, the app cancels the turn with "Reply interrupted" and never resends it.

**Starting the service.** Android 12 and later forbid starting a foreground service from the background, so the app starts it from the visible activity as soon as the user connects a device, starts motion or turns Autopilot on.

**If notifications are denied** (Android 13+), the service still runs, but lock-screen Stop is unavailable. The app asks for the permission when the first device connects. While it stays denied, Devices and App settings show a warning with a link to the system setting.

**Swipe-away** (`onTaskRemoved`): trigger Emergency Stop for every device, then stop the service. Force-stop and process death can skip all app code, which is why the run journal exists.

**Service loss.** Destruction of a promoted service fences output and sends Emergency Stop. Admission and each subsequent motion write require a live host.

**Memory pressure** (`onTrimMemory`): drop caches such as older chat pages, but never drop run state.

### 1.1 Run journal

The run journal is a small versioned file, separate from the database and replaced atomically. It holds:

- the run id;
- a stable reference to each device in the run, and its transport;
- the last intended command or state;
- whether Autopilot is on;
- a timestamp.

Secrets stay in the vault (§6).

**No journal, no start.** The app arms the journal durably before the first motion-capable write of a run, whether triggered by chat, Autopilot, a manual start or Test motor. If arming fails, the app refuses the start with a notice. Updates happen at state boundaries, never at frame rate, and Stop never waits for storage.

An explicit Stop on an idle connection also retains outstanding targets asynchronously. Device removal preserves recovery credentials until the return-time Stop or an explicit local check.

**Clearing.** The journal clears only after every device in it has confirmed Stop or been reconciled. A clean activity exit never clears a device whose Stop is unresolved.

**Cold start.** If the app starts and finds an armed or unresolved journal, before admitting any output it:

1. sends Stop at once to every saved cloud device where credentials and network permit—because the VacuGlide may still be running—and carries unresolved attempts to the next reconnection;
2. never restarts motion or Autopilot, and never sends a movement command to test whether the previous run ended;
3. opens the Run interrupted page with an honest per-device account:
   - Leads with any device that may still be running: "It may still be running at its last speed. Switch it off at the device."
   - Never guesses why the app was closed.
   - Never reports a physical outcome that no device acknowledged.
   - Keeps unresolved devices listed after you leave the page.

## 2. Stop

**Surfaces:**

- the footer Stop, which sits as the last row of the app frame on every screen and stays full-width directly above the keyboard while you type;
- the notification's one action, including on the public lock screen;
- the Quick Settings tile;
- device buttons that the desktop already maps to Emergency Stop (Autoblow);
- the TalkBack action "Stop all motion" (§8).

The volume-key shortcut was dropped because it worked only with the app in the foreground, where the footer Stop is already under the thumb.

**Path.** Every Stop surface calls `MagicHandyCore.emergencyStop(source)` directly. It runs on a dedicated single-thread executor at elevated priority and never waits behind a UI view model, a busy coroutine dispatcher, model I/O, a blocked device queue or storage. The engine checks the epoch when a command is queued and again immediately before each mutating write.

**Order**, ported from the desktop's motion and transport contract:

```text
press ──► haptic now (before any acknowledgement)
      ──► advance Stop epoch (stale commands are now rejected)
      ──► close admission; cancel chat turn, Autopilot planning, retargets
      ──► mark engine stopped (even if a transport fails)
      ──► Stop every owner in parallel, each with its own deadline
            Handy BLE      RPC 715 StopCurrentMode
            Handy Cloud    HSP stop
            Intiface       StopAllDevices / zero levels
            Coyote         zero both channels, stop packets
            Autoblow       stop, cancel in-flight call at 300 ms, resend at 750 ms
      ──► aggregate: confirmed · pending · unconfirmed · no device
      ──► UI, notification and TalkBack announce the outcome
```

Bytes already in flight can't be recalled. An uncertain link is retired and sent a recovery Stop. A late reply can update the evidence for its own command, but it can never reopen admission.

**Per-device receipt.** After the haptic, a short receipt above the footer reports each device's outcome, with unconfirmed devices first, then pending, then confirmed:

- **Confirmed:** the device itself acknowledged Stop.
- **Pending:** a reply can still arrive before the deadline.
- **Unconfirmed:** covers a timeout, a failed write, unknown delivery or an acknowledgement from a bridge alone. A Coyote write without response, or a radio disconnect on its own, doesn't prove the device stopped.
- **No device:** appears only when nothing is connected and no earlier Stop is unresolved.

Uncertainty survives repeated presses. An unconfirmed result stays on screen, naming the device and giving one local action.

**Lock screen.** The public lock-screen notification shows only "MagicHandy · Active" and Stop. Device names, pace and Autopilot timing appear only in the private view after unlocking, and tapping the notification opens Live.

**Wording** reuses the desktop strings:

- "Stopped." (only when every targeted device confirms)
- "Stop is unconfirmed. Check the device locally."
- "No connected device to stop."
- "Stop request failed; check the connection."

**Network.** Bluetooth devices stop with no network at all. Cloud devices need one, which is why the VacuGlide carries its warning and safeguards.

## 3. Device connections

### 3.1 States shared by every device

```text
            add                connect            first frame
NotSetUp ─────► Disconnected ─────────► Connecting ──────► Connected ─────────► Playing ⇄ Paused
                   ▲    ▲                  │                 │  ▲                 │
                   │    └── cancel/fail ───┘                 │  └── run ends ─────┘
                   │                                         │
                   └──────── user Reconnect/Disconnect ◄─── Lost ◄── link drop / uncertain write / health check
```

- **Lost** means one of:
  - the link dropped;
  - an uncertain mutating write retired the link;
  - a health check failed, as with Autoblow's 5-second read.
- **Connecting and reconnecting** always end at Connected and idle. They never rejoin a running session or restart motion. A device connected during a run joins the next one.
- **On device failure,** every device in the run stops; there is no option to keep going on the healthy ones (plan §15). The app keeps each device's Stop evidence after the run ends.
- **Status words and colours** match the desktop:
  - Playing and Connected are green;
  - Connecting… is amber;
  - Not connected is faint;
  - Needs setup is a ring;
  - Connection lost is red.

### 3.2 Android Bluetooth (The Handy, Coyote)

A connected device shows the battery percentage only after a supported characteristic reports one. Cloud latency uses a measured request round trip.

**Operation queue.** `GattOperationQueue` runs one operation at a time, as Android requires, each with its own timeout:

- connect;
- MTU request;
- service discovery;
- enabling notifications;
- write;
- read.

A Stop can jump ahead of queued operations and retire a stalled generation.

**Connect sequence:**

1. Scan with service-UUID and name filters.
2. `connectGatt(autoConnect=false, TRANSPORT_LE)`.
3. Request MTU 517; the payload is the MTU minus 3, capped by the profile.
4. Discover services and verify every required characteristic.
5. Enable notifications.
6. The device is ready only after that.

**Retries.** The app retries a failed connect (status 133 and similar) at most twice, and only before the first write. It never retries an uncertain write; instead, it retires the link (desktop rule).

**Power.** Connection priority is high during a run and balanced when idle.

**Handy.** On an orderly disconnect, the app sends RPC 715 first to honour the profile's Stop-on-disconnect rule.

**Coyote.** The user must acknowledge its safety notes before the first connection, and Find nearby devices stays unavailable until then. It uses an explicit scan with no fallback to another device, and strength rises only from a reported baseline.

**Permissions.** The app requests `BLUETOOTH_SCAN` (with `neverForLocation`) and `BLUETOOTH_CONNECT` when you first add a Bluetooth device. It never asks for location.

### 3.3 Cloud and Intiface

- **Handy Cloud:**
  - HSP over REST with the connection key;
  - a network change makes the stream uncertain, so a new start is needed;
  - buffer starvation pauses the device by itself.
- **Autoblow (Ultra, VacuGlide):**
  - device token;
  - a health read every 5 s;
  - an event stream with heartbeats for the physical Stop buttons. A dropped stream retires the device and stops its run even if health reads still succeed;
  - commands fenced by generation and sent one at a time, as on desktop.
- **VacuGlide safeguards:**
  - runs and Test motor start only while the service is up and the journal is armed;
  - Stop is sent on connection loss and sent again when the connection returns;
  - a check-in runs every 15 minutes by default. Chat, Live and the private notification show its one-minute deadline. The whole run stops if it expires;
  - its page asks for unrestricted battery use.
- **Intiface Central:**
  - WebSocket to `ws://127.0.0.1:12345` on the phone, or to a server on the LAN;
  - pings keep the connection alive;
  - Android may close Intiface Central in the background, so the app treats a closed socket as Lost for every device using it and stops the run;
  - The Handy, and other linear actuators, connect through Intiface too (plan §12, decision 1). Setup shows a persistent caveat, and an acknowledgement from the bridge alone reports Stop as unconfirmed.

## 4. Network resilience

**Watching the network.** `ConnectivityManager.NetworkCallback` tracks the default network and whether it is validated.

**When the network is lost,** the app treats losing the model differently from losing a device:

- **A device in the run** (Handy Cloud, Autoblow, or an Intiface server on the LAN): mark it Lost and stop the whole run. The Handy also pauses by itself when its buffer runs out.
- **VacuGlide:** send Stop immediately (it may fail), alert, and send Stop again when the network returns and on the next cold start.
- **Chat turn:** abort the turn and show "Reply interrupted". Keep the message you sent with Send again; never resend automatically.
- A transient planner failure holds the current segment and retries after 2 seconds with backoff up to 30 seconds within the same run.

**Autopilot:** hold planning. The strip shows "Planning held" alongside what is actually happening, such as "Current segment still playing" on Bluetooth devices. Planning resumes only within the same run.

**Wi-Fi to cellular.** Open streams reset and are treated as interruptions. LAN servers show "Not reachable · same Wi-Fi?". A LAN server can still remain reachable on a Wi-Fi network that has no internet access.

**Cleartext policy:**

- The network security config permits cleartext.
- An OkHttp interceptor permits cleartext only to private (RFC 1918), link-local and loopback addresses, checking both the resolved address and every redirect.
- Every other host must use HTTPS.
- Cleartext connections are labelled "Not encrypted".

**LAN servers are added by address.** HTTP, HTTPS and Intiface connections all require a private, loopback or link-local peer. Internet validation changes on the same working LAN preserve local sessions.  There is no nearby-server scan in v1: a /24 sweep misses other subnets and adds scan, timeout and permission states. Instead, you enter a host name or address and a port, and the app probes only that host:

- `GET /api/tags` identifies Ollama, `GET /props` llama.cpp, and `GET /v1/models` any other OpenAI-compatible server;
- it fetches the models, suggests a capability level from the model name, shows the context that level needs and runs a test before saving.

**Hosted providers.** The app reuses HTTP/2 connections and keeps them warm only while the chat screen is visible, with no background pings.

## 5. Chat and Autopilot on a phone

**Chat turn:**

1. Compose the request, with the history window chosen by capability and context.
2. Stream the reply over SSE, updating the UI at most 30 times per second.
3. Validate the reply, then repair it once if rejected, as on desktop. A failed repair holds motion.
4. Dispatch any motion to the engine. The first motion-capable command of a run arms the journal first (§1.1).
5. Commit the reply and any memory changes to storage in one transaction.

A hosted refusal can send the current message once to the explicitly selected LAN provider. Prior dialogue and stored memory stay out of this request. Clearing the selection cancels the provider call.

**Interruption.** Stop cancels the turn and discards any uncommitted reply with a plain notice, as on desktop. Discarded text can never execute later.

A transient planner failure holds the current segment and retries after 2 seconds with backoff up to 30 seconds within the same run.

**Autopilot:**

- Lives in `CoreService`, so it keeps running with the screen off.
- Holds a wake lock only during an active run; the foreground service keeps network access under Doze.
- Check-ins use one choice: Off, Talk, or Talk and adjust. The planner picks when to check in.
- An optional finish target, when set, goes to the planner as a structured target, and Live shows the time remaining. You can clear or change it.
- While the app is in the background, the private notification shows the next change ("changes in 0:40").
- After Stop or process death, Autopilot stays off until the user starts it again.

## 6. Data and privacy

**Storage** lives in `noBackupFilesDir`:

- **One Room (SQLite) database:** chats and messages, memory, named model connections and role bindings, and the revisioned settings document. Built-in personas and translations ship as resources.
- **The run journal:** its own atomic file (§1.1).
- **Diagnostics:** a capped, redacted event ring in a rotating file, written off the output thread. No chat text and no per-packet records.

**Secrets** (Handy key, Autoblow tokens, OpenRouter key, ChatGPT tokens):

- encrypted with AES-GCM under an Android Keystore key, in a dedicated file;
- the database and journal hold only opaque handles;
- if the key becomes unusable, the app asks you to sign in again;
- never logged or exported.

**Backup.** `allowBackup="false"`, and the data-extraction rules exclude everything.

**Sharing and telemetry.**

- Diagnostics leave the phone only through the share sheet, already redacted.
- No analytics.
- Crash logs stay on the device unless the user shares them.

## 7. Budgets (to be measured in M0)

| Budget | Target |
|---|---|
| Cold start to a usable chat screen | under 1 s on a Pixel 8-class phone |
| Idle, nothing connected | no foreground service, sockets, timers or wakeups |
| Memory while playing (UI and core) | under 150 MB |
| APK size (v1, no native code) | under 20 MB |
| Motion readouts | 8 per second while a screen shows them; once a second to the notification; none otherwise |
| Streamed reply text | at most 30 UI updates a second |
| Coyote packet jitter | well inside the 100 ms cadence |

## 8. Accessibility

- **TalkBack Stop action:** "Stop all motion" is a custom TalkBack action available from every screen.
- **Announcements:**
  - state changes are announced politely;
  - streamed replies are read as complete sentences;
  - Stop results are announced assertively and interrupt other speech.
- **Font scale:** at 200% the layouts reflow, and Stop never scrolls out of view.
- **Reduced motion and colour:**
  - reduced motion turns off animated meters;
  - status is always a dot plus a word, never colour alone.

## 9. Testing on the phone

- **Simulated devices in debug builds:**
  - in-process simulators for the Handy Bluetooth session, Coyote, Autoblow cloud (including a VacuGlide that keeps its last speed) and Intiface, behind the real transport boundary;
  - agents can review the app without hardware;
  - debug builds only, and no local servers are needed.
- **Journal kill tests:** kill the app at each journal boundary, including between arming and the first command. Recovery must send Stop and never restart motion.
- **Chaos scripts over wireless ADB on the Pixel:**

  | Scenario | Command |
  |---|---|
  | Background kill | `adb shell am kill com.magichandy.mobile` |
  | Force-stop | `adb shell am force-stop com.magichandy.mobile` |
  | Forced Doze | `adb shell dumpsys deviceidle force-idle` |
  | Network loss | `adb shell svc wifi disable` and `adb shell cmd connectivity airplane-mode enable` |
  | Revoked Bluetooth permission | `adb shell pm revoke com.magichandy.mobile android.permission.BLUETOOTH_CONNECT` |

  Each run checks the Run interrupted page, verifies the Stop outcomes and confirms that motion never restarts on its own.
- **UI tests:** cover Back from a sheet, opening the keyboard during a run, screen rotation and Stop while a sheet is open.
- **Instrumented tests:** cover the Bluetooth queue, the service lifecycle and every Stop surface.
