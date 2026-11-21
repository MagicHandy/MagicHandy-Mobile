# Phone acceptance checklist

On November 21, 2026, the owner tested MagicHandy Test 0.1.6 with ChatGPT on a physical Pixel 10 Pro XL and accepted its performance for the first public release. The record covers that phone and provider. The checklist below tracks further combinations of Android versions, firmware, devices and interruption scenarios. JVM simulations provide protocol and lifecycle regression coverage.

| Area | Acceptance evidence |
|---|---|
| Service and system controls | Start from a visible activity on the target Android versions. Check notification denial, connected-device service eligibility, background restrictions, screen-off playback and wake-lock release. Swipe away the task and check every participating device. Verify the footer, lock-screen action, TalkBack and Quick Settings Stop. |
| Notification and visibility | Inspect the public lock screen for only "MagicHandy · Active" and Stop. Verify that the private notification includes the next change during Autopilot and opens Live. Compare Android's battery and notification settings with the app. Interrupt a chat-only reply by backgrounding. |
| Bluetooth | Check explicit permission timing, denial and revocation, profile filtering, granted MTU, status-133 behaviour before the first write and retirement after an uncertain write. Measure output jitter, per-owner Stop latency and reconnection across supported firmware. |
| Handy | Exercise each cloud and native model with both mount orientations, narrowed stroke windows, clock drift, a phone wall-clock jump, long playback, Pause/Resume and Edge. Check initial slider position and endpoints. On BLE firmware without geometry telemetry, verify RPC 715 and range restoration. If telemetry is available, verify the finite lead-in. Check linked alignment and loss of one participant. |
| Other devices | Confirm Intiface vibration selection, ceiling and behaviour when the bridge is killed. Check Coyote B1 reporting, personal ceilings, both channels at zero and the 100 ms cadence. Check the Ultra's last movement and physical pause button. |
| VacuGlide | With a local means to turn the device off, verify loss-time Stop, independent Stop on return, process death, both valves closed, permitted valve steps and personal motor range. Exercise the default check-in, Off, and the unanswered one-minute deadline. |
| Network and models | Check airplane mode, Wi-Fi to cellular, a LAN without internet, a model-only outage and return of the same device while Wi-Fi remains connected. Complete the owner-authorized Custom Tab sign-in and token rotation. Verify the provider accepts the loopback callback on the phone. |
| Storage and performance | Check Android Keystore persistence and behaviour after a key becomes unusable. Inspect backup and restore on the supported Android versions. Kill the process between journal arming and the first command. Verify Stop-only recovery and the local-check flow. Measure cold startup, sustained memory, trim response and screen-off scheduling jitter. |

## Verify Handy Cloud on a second phone

Install the current MagicHandy Test update. With the Handy on Wi-Fi and safe to put in idle, open **Devices**. Tap **Connect** on an existing Handy Cloud entry. On a fresh install, add a Handy Cloud device and enter its connection key in the app. The row should show **Connected** and the device should stay idle. In device details, **Check connection** should keep the row connected without starting playback.

Try **Connect** while the Handy is booting. If the cloud is silent, the attempt should time out in about three seconds and offer **Reconnect**. If boot finishes while **Connecting…** is visible, tap **Retry** in the row or device details to start a fresh attempt immediately. Record the displayed message, HTTP status or Handy error number if it still fails. Keep the connection key private.

## Compare the feel of a turnaround

Use the same Handy model, connection route, stroke window and requested speed on both apps. Compare manual Start at 25%, then ask each chat for steady, full sweeps at 25%. Compare a repeating two-short/one-full phrase separately. Observe whether a hesitation occurs at every turn, only on the transition from short to full, or when a new score arrives. Use one controlling app at a time and Stop between runs. Record the route and the case alongside the observation. [Matched command traces](../reviews/2026-11-21-handy-motion-quality.md)

## Verify the ChatGPT update on the phone

Install the current update over the previous MagicHandy Test APK. If ChatGPT is already connected, send a short text-only message, reopen the app, and confirm the reply is still in the chat. For a new account, tap **Continue with ChatGPT** in Choose your AI or Models. After approval, close the browser tab when prompted and confirm the connection is saved. Repeat sign-in with the same account and workspace. Cancel a separate attempt before approving and confirm the existing connection still works. If sign-in or chat fails, record the app version, Android version, and the message shown inside MagicHandy, including any HTTP status. Browser addresses contain private sign-in values.

## First checks on the device-test build

1. Open **MagicHandy Test** and complete first-run setup. A fresh install starts idle with an empty device list. Add one device explicitly and use **Check connection**. It should stay idle.
2. After an ordinary Stop, dismiss the receipt. Start another run and press Stop again. Check that the new receipt appears. On an Intiface connection a bridge acknowledgement still requires a local check.
3. For a VacuGlide, set the personal motor range and a lower Live top speed. Check that the output respects the lower ceiling when a request changes and during playback. Open a due check-in from the private notification and answer it on Live. Confirm the public lock screen stays private.
4. For a Coyote, check that Live waits for reported strength before enabling the controls. For a Handy or a generic linear device on Intiface, change the mounting during playback and check that the new setting first takes effect on the next Start or Resume.

## Intiface linear, sharing and release acceptance

- On Central 3.2.2 and each supported newer version, establish what Stop physically does on The Handy and on another linear device. Record the firmware, reported command support, final movement and local means of stopping. The screen must keep a bridge-only acknowledgement unconfirmed.
- Select a specific axis of a multi-axis device. Reorder the device list, remove and reconnect the device, and attach two physically similar devices. Verify re-identification, explicit re-selection on ambiguity and exclusive ownership. Connect a vibrator on the same server. Disconnect each independently and kill the bridge during linked playback.
- Measure the startup anchor, device timing gap, endpoint resolution, narrowed and reversed windows, late segments and Stop latency with screen off. Include a Stop during anchoring and during discovery. Check the local reconciliation interaction after a bridge-only receipt. Exercise an older vibrator row saved with only its former index and explicitly reselect the device.
- Exercise Add tile, removal and re-addition from System UI, including the initially unknown state. Verify the branded tile and notification icons, lock-screen Stop and Live navigation.
- From the Settings share sheet inspect the diagnostics attachment and the one-time read grant. Confirm the selected recipient can open only that file and that names, addresses, chat and memory are absent.
- The UI owner checks installation and upgrade of an owner-signed minified APK on an emulator after merge. Open existing Room data, return via notification, decode stored settings and verify Lowlight presents as OLED black. Compare the APK signing fingerprint and checksum with the owner’s release artifacts.

## First-run setup and personas

- On a clean install, complete the first step, skip the model and optional device, leave MagicHandy at Utility and finish. Reopen the app. Confirm permissions appear at the associated feature. Repeat after upgrading an already acknowledged install.
- Type a name and a 500-character description with emoji, combining marks and a Japanese IME. Check the counter, rejection focus, keyboard and Stop footer. Exercise all five locales, TalkBack and large type.
- Switch personas during a debug reply, then during a check-in. Read each saved speaker in the transcript. Clear one persona's memory, duplicate another, and delete the active one. Check that the fallback speaker is MagicHandy and older messages keep their names.
- Share a diagnostics report to a local viewer. Inspect its persona count and three settings. Search for the distinctive test persona names and memories. Check the established cloud-backup and device-transfer exclusions on supported Android versions.
