# Handy Bluetooth connection and motion, 2026-11-24

The first sections cover the connection failure. [Motion over Bluetooth](#motion-over-bluetooth) covers the follow-up after the owner's phone test.

## Symptom

On the owner's phone, public release 0.1.6 found an Original Handy under **Bluetooth on this phone** with a strong signal, but every **Connect** ended in **Couldn't verify**. Handy Cloud connected normally. Nothing moved.

## Cause

Android declares a Bluetooth Handy ready only after a correlated reply to firmware RPC 715, StopCurrentMode. The session accepted a reply only when its result field had the request's method number.

The manufacturer schema has no result for RPC 715. In [handy_rpc.proto at `2398008c`](https://gitlab.com/sweettechas/platform/handy-public-rpc/-/blob/2398008c10d5f43903ec150e654968501f0ebc83/public/proto/handy_rpc.proto), the `Response` oneof has no entry 715 and notes that some requests "just return a blank response". The firmware therefore answers RPC 715 with only the request ID. Android treated that reply as unconfirmed, closed the link and reported the result as failed. The same rule would also have reported every Bluetooth emergency Stop and disconnect Stop as unconfirmed.

Every other RPC the app sends over Bluetooth has a result in the schema: clock set and get (709, 712), stroke window (841) and HSP setup, add, play, stop and state (860–867).

The tests passed because both simulated firmware peers, `SimulatedHandyLink` and `HandyPeerLink`, echoed an empty 715 result that real firmware never sends. Desktop alpha.89 never checks that result: its native worker sends RPC 715 at connection and accepts the GATT write acknowledgement, and its browser path sends no Stop at connection.

## Desktop and Android connection handling

| Step | Desktop alpha.89, native worker | Android 0.1.6 | Android now |
|---|---|---|---|
| Choosing a device | The scan ends before the connection starts | The 12-second scan keeps running during the connection | Scanning ends when a result is chosen |
| Time allowed | 12 seconds for the whole connection | 5 s link, 2 s MTU, 2 s service discovery, 2 s notifications, three attempts | Service discovery gets 5 s |
| ATT payload | Windows negotiates and reports it (509 bytes on the tested Handy) | Requests MTU 517 and needs at least 48 bytes | Unchanged |
| Readiness | RPC 715 written with response; clock sync optional | Correlated RPC 715 reply carrying a 715 result | Correlated RPC 715 reply, blank result accepted |
| Motion and Stop replies | Not awaited; a late error retires the link | Correlated reply within 700 ms | Unchanged, with blank replies accepted |
| After a failure | Connect again | The row said **Couldn't verify** with no action | The row offers **Retry** |

Android still waits for a correlated reply to every Bluetooth command, where desktop accepts the GATT write acknowledgement. The schema says every request gets a response, and that reply is what lets the Stop receipt say a device confirmed Stop. RPC 715 needs firmware 4.0.13 or later on both apps.

Android also retires the link on an undecodable notification, where the desktop native worker skips it. With a 512-byte MTU, firmware notifications aren't truncated, so this hasn't been changed.

## Changes

- `RpcReply.acknowledges` accepts a correlated reply without an error when its result belongs to the command or is absent. A result for another method, or more than one result, never acknowledges a command.
- Both simulated peers answer RPC 715 with a blank response, as firmware does. With the old rule restored, 9 of the 15 tests in `HandyBluetoothTest`, `HandyClockProtocolTest` and `SetupTest` failed, including the setup flow with "expected Verified but was Failed". All pass with the fix.
- Choosing a scan result ends discovery but keeps the list. Scanning competes with connecting for the radio.
- Service discovery is bounded at 5 seconds, up from 2 in the session and 3 in the GATT queue. Android has no GATT cache for an unpaired device, so it reads the whole table on every connection. Verification now waits 60 seconds, which covers three full attempts, the handshake and clock sync.
- A result that couldn't be verified offers **Retry** on the same list.
- At the owner's request, **Handy Cloud** is labelled **Recommended**.

## Verification

Golden bytes for the firmware's blank reply (`08 03 22 02 08 01`) acknowledge Stop. A wrong-mode error and another method's result don't. The add-device flow verifies a Bluetooth Handy through the production session and a firmware-shaped peer, ends discovery first, and retries a failed result. The full gate passed 959 JVM tests (305 core, 244 transport and 410 app, including 82 screenshot comparisons), Lint and all three assemblies. No Bluetooth hardware was connected. Checking a real Handy over Bluetooth on the phone remains for the owner. [Device checklist](../backend/device-checklist.md#verify-handy-bluetooth-on-the-phone)

## Motion over Bluetooth

With the connection fix, the owner's phone connected to the Original Handy over Bluetooth and played motion, but its quality was not as expected. This section compares the Bluetooth path with the Android Cloud path the owner accepted and with desktop alpha.89.

### Cause: the fit the owner had rejected

In the October hardware comparison, an Original Handy with firmware 4.3.0 kept stuttering with the precise fits (variants A and B). The owner accepted the sparse variant D, which keeps every authored turn and allows at most three points of optional position error. Desktop shipped D for Cloud only, the route it was tested on. [Desktop acceptance](https://github.com/MagicHandy/MagicHandy/blob/1b80d72ee4b90a5ab80a9bb3fe373b1640388f80/docs/cloud-sampling-review-2026-10-24.md)

The Handy's own HSP player plays the points, whichever route delivers them. Android kept desktop's split, so Bluetooth sent the precise fit to the same Original Handy: **149 points** in the first 12 seconds of full sweeps at 25%, against **56** over Cloud. Desktop's Bluetooth motion was never tested on hardware, and no Codex conversation records the owner's feedback on it.

A single Original Handy now uses the sparse fit over Bluetooth as well. Linked sessions, the Handy 2 and transitions keep the precise fit, as on Cloud. Bluetooth manual Start and full sweeps now match the Cloud hashes exactly, and the end-to-end capture counts 56 points through the production GATT session. Packets carry up to 20 points, so a one-second refill usually fits in a single write.

### Other differences checked

| Area | Finding |
|---|---|
| Same as desktop | Stroke window before Setup, stream IDs, zero-based tail indices, Play with `pause_on_starving` and the synchronized server time, the clock offset convention (server UNIX time minus machine time since boot), a 1.5-second minimum buffer lead for Bluetooth, playback timed from the Play round-trip midpoint, and changes taking effect at the end of the accepted buffer. |
| Different, kept | Android waits for the firmware's reply to every Bluetooth command and asks for high connection priority while playing. The reply is what lets the Stop receipt say the Handy confirmed Stop, and high priority shortens Stop. Desktop accepts the GATT write acknowledgement and leaves connection parameters to the operating system. The first Add of a stream sets `flush` on an already empty buffer; desktop's Cloud path does the same. |
| Start of each run | Over Cloud, Android reads the slider and eases from its position to the first point. Bluetooth has no position read on either app, so the Handy travels to the first point at its own speed when Play starts. Firmware RPC 842 reports `position_absolute` in mm and `speed_absolute` in mm/s, and RPC 840 reports the stroke window. The schema doesn't document the units of the stroke values, so reading them needs a check on the Handy before it ships. |
| Setup and the clock | The schema says HSP Setup resets options including "clock 0", without saying which clock. If it means the RPC 709 offset, Play's latency adjustment would be lost after every Setup. Motion starts either way, and this hasn't been changed. |

### Verification

`HandySamplingPolicyTest` and `HandyMotionCaptureTest` pin both routes. Whether the sparse fit removes the stutter over Bluetooth needs the owner's comparison on the same Handy, speed and stroke window.
