# MagicHandy on Windows and Android during live sessions

*Links into `build/` open verification artifacts in a local checkout. Public Android downloads are on the [releases page](https://github.com/MagicHandy/MagicHandy-Mobile/releases).*

November 27, 2026 · Windows v0.1.0-alpha.90 (`4286ae4`) · Android 0.1.9 test candidate (working tree based on `3abba0d`)

**Android kept the timing of steady strokes throughout the long run. Its Original Handy Bluetooth output described the approach to a reversal with fewer points and a sharper change in velocity. ChatGPT carried out the same pace and pattern requests on both platforms. Both lost the earliest pace instructions when the conversation exceeded the configured history window.** [Interactive comparison and paired replies](../../build/reviews/live-windows-android/index.html) · [Wire captures and verification data](../../build/reviews/live-windows-android/comparison-evidence.zip)

## Measured motion

| Original Handy measure | Windows | Android |
|---|---:|---:|
| Bluetooth points in the first 12 s at 25% | 149 | 56 |
| Bluetooth points in the first 715 s | 8,821 | 3,308 |
| Reversals in those 715 s | 787 | 787 |
| Median change in commanded velocity at a reversal | 34.5 % stroke/s | 90.9 % stroke/s |
| Median time for a full-range half-stroke at 25% | 908 ms | 908 ms |
| Median half-stroke during the default gentle hold | 536 ms | 841 ms |
| Cloud points in the first 475 s at 25% | 2,198 | 2,198 |
| Minimum headroom before refill, healthy Bluetooth run | 1.30 s | 1.45 s |
| Minimum headroom before refill, healthy Cloud run | 1.30 s | 1.47 s |

**Bluetooth.** Every steady reversal occurred at the same stream timestamp. Neither app encoded a flat at a turn. Interpolating the accepted commands at 1 ms gave a maximum positional difference of 3.176 percentage points of full stroke and an RMS difference of 1.206. The sparse Android fit accounts for the lower point count. The velocity figure shows its practical consequence at the turn. [Measured Bluetooth curves](../../build/reviews/live-windows-android/bluetooth-motion.png) · [Android buffered sampler](../../core/src/main/kotlin/com/magichandy/mobile/core/motion/BufferedSampler.kt)

**Gentle hold.** Both used the middle 25–75% of travel. Windows clamped its requested hold pace to the shipped 20% minimum. Android selected 10%, or 40% of the incoming 25% pace. Lowering only the Windows minimum to 1% and repeating the live run gave 10% and the same 841 ms half-stroke as Android. After leaving the hold, both returned to 908 ms half-strokes. The Android full-stroke tips had a constant **201 ms** offset in the measured interval. After the deliberate ten-second pause and resume the constant offset was **237 ms** in the measured interval. [Android manual hold](../../core/src/main/kotlin/com/magichandy/mobile/core/motion/ManualTarget.kt) · [Windows holding target](https://github.com/MagicHandy/MagicHandy/blob/4286ae435497a81130ebacf21509e644e07f02a4/internal/motion/holding.go#L45)

**Cloud and Stop.** In the steady Cloud interval, every one of the 2,198 time-position pairs matched. Both sent 56 in the first 12 s. The Windows and Android apps divided those points into different append requests. The healthy 30-minute Bluetooth and 16-minute Cloud runs had zero out-of-order point timestamps, incorrect tail indices or late refills. Both apps sent zero new movement writes during the eight seconds observed after each final Stop. [Cloud commands](../../build/reviews/live-windows-android/cloud-motion.png)

## What ChatGPT said and what the Handy received

Each app used its own normal signed-in ChatGPT integration with **GPT-6.1 Terra, low reasoning**, the Utility persona and Layered motion. The 22-turn paced script ran for 28 minutes and 12 seconds before the final answer. It covered exact and relative pace, a named baseline, a change of pattern, unchanged-motion replies, a typed Stop and an idle follow-up. A further 18 live text-only turns and two recall probes extended the same conversation.

| Matched result before the typed Stop | Windows | Android |
|---|---:|---:|
| Requested pace or idle state reached | 20/20 | 20/20 |
| Completed ChatGPT calls for those 20 turns | 20 | 20 |
| Median send-to-reply time | 2.72 s | 2.86 s |
| 90th percentile | 4.36 s | 3.81 s |
| Median of first five / last five | 2.38 s / 2.60 s | 2.85 s / 2.80 s |
| Observed full-sweep intervals containing exactly two requested base rebounds | 26/26 | 26/26 |

On the pattern change, Windows said “Keeping your 30% baseline, with two shorter base-end rebounds between full sweeps.” Android said “Keeping your 30 percent baseline, with two shorter base-side rebounds between full sweeps.” The wire traces show the same repeating full tip at 100% and the two shorter tips at 35%. Both later removed the rebounds, applied 26% when asked for four points below the 30% baseline, and recalled “juniper” during the paced conversation. [Actual ChatGPT-generated motion](../../build/reviews/live-windows-android/chat-rebounds.png)

**Android gives less information about a pattern change.** Its receipt for adding the rebounds and its receipt for removing them both recorded only `Pace 30 → 30`. The change is visible on the wire. On 6 pace edits, the saved reply was available while the Android run readout still held the preceding command. In the 4 edits covered by the additional sampling, the new readout was observed within 0.68–1.16 s of reply completion. Windows already exposed the new target in its backend snapshot. These snapshots have different semantics. Android updates the commanded readout at output dispatch. Physical movement on both platforms also depends on points already buffered. [Receipt construction](../../core/src/main/kotlin/com/magichandy/mobile/core/session/ChatProposal.kt) · [Android receipt text](../../app/src/main/kotlin/com/magichandy/mobile/ui/format/Words.kt) · [Dispatch publication](../../core/src/main/kotlin/com/magichandy/mobile/core/motion/ManualPlayback.kt)

**Long-session recall.** The actual first two explicit targets were **25% and 35%**, followed by **+5 points**. After the rest block, Windows answered “The first two paces were 30% and 38%. The requested increase to the second pace is unavailable.” Android answered “The first two requested paces were 30% and 38%. The requested increase is unavailable.” Both still recovered *juniper* and *30%*, which had been repeated later. No device resumed during this text-only block. The selected 65,536-token configurations retain at most 64 prior dialogue messages and 24 KB of history. The full conversations remained stored, with 82 records in the Windows conversation and 83 displayed entries in the Android conversation at the final probe. [Android history window](../../core/src/main/kotlin/com/magichandy/mobile/core/chat/ConversationWindow.kt) · [Windows history window](https://github.com/MagicHandy/MagicHandy/blob/4286ae435497a81130ebacf21509e644e07f02a4/internal/config/conversation_window.go#L19)

**Stopping through chat.** Both typed Stops used zero model calls and left motion idle. The Windows test client reused its old Stop sequence on the next request and received HTTP 409. Repeating that pair with the state refresh used by the shipping UI returned “Yes, motion is stopped and will stay off.” and left the device idle. Android's first idle follow-up returned “Yes, motion is stopped and remains off.” [Windows Chat panel](https://github.com/MagicHandy/MagicHandy/blob/4286ae435497a81130ebacf21509e644e07f02a4/web/src/components/ChatPanel.tsx#L273)

**Autopilot.** A separate fresh start exercised the normal ChatGPT planner. Windows published 3 distinct plans over 119 s with observed paces 40, 46, 42%. Android made 3 requests over 64 s with observed paces 35, 42%. The Android probe ended after its third request and the Windows probe at the two-minute limit. These samples establish that scheduled model output reached each app's running session. The differing observation periods limit a comparison of planning frequency.

## Cloud recovery

A peer with a frozen zero-position readback accepted the positioning commands but continued to report zero. Both clients stopped the unsuccessful resume. Windows retained the paused run. Android ended the run and marked the Handy Lost. The healthy rerun calculated slider position from the timestamped points it had received.

For delivery jitter, the healthy peer delayed only the eighth active refill. It reported a starving buffer if the preceding tail expired before accepting the points. Headroom below is measured at acceptance. The resulting state was observed 21 s into each independently started run.

| Injected delay | Windows headroom and outcome | Android headroom and outcome |
|---|---|---|
| 1.0 s | +302 ms, continued | +493 ms, continued |
| 1.4 s | -98 ms, stopped | +93 ms, continued |
| 1.8 s | -497 ms, stopped | -307 ms, stopped |

## Scope and reproduction

The Windows executable was built from the current desktop checkout and launched with isolated settings and simulated motion. The Android test builds ran native Kotlin, Android foreground services, production encoders, Room and the production ChatGPT provider on Android 17 x86-64 emulators. Inert peers captured actual GATT requests or local HTTP request bodies. The Windows public HTTP app and Android's public intent interface were the action entry points. The local receiver captured clock, state and test replies. Both sides used a 0–100% stroke window and an 80% top pace. ChatGPT used the network. All device traffic stayed inside the test environment.

Reply times include the normal provider round trip and each harness's observation method. Android polled for the saved reply. The measured waveforms show piecewise-linear commands. Physical motor feel and wide-area Handy Cloud timing remain hardware measurements. One paired account run is descriptive of this script and configuration. [Numerical results](../../build/reviews/live-windows-android/measurements.json) · [Portable comparison](../../build/reviews/live-windows-android/index.html)

## Android changes after the comparison

The updated Android candidate adds a point one percentage point from a slow reversal when it meaningfully reduces the change in commanded velocity and stays within the existing position and velocity limits. This applies to a single Original Handy on native Bluetooth. A replay through the Android app’s public controls, production encoder and simulated GATT peer produced the following results against the original live captures.

| Original Handy Bluetooth at 25% | Previous Android | Updated Android | Windows |
|---|---:|---:|---:|
| Encoded points in 12 seconds | 56 | 83 | 149 |
| Encoded points in 715 seconds | 3,308 | 4,883 | 8,821 |
| Direction changes in 715 seconds | 787 | 787 | 787 |
| Median commanded velocity change at reversal (% of full stroke per second) | 90.9 | 34.5 | 34.5 |
| Median half-stroke duration | 908 ms | 908 ms | 908 ms |
| RMS position difference from Windows (percentage points of full stroke) | 1.206 | 0.912 | 0 |

All 787 updated reversals occurred at the Windows timestamps. The encoded trajectory spent zero milliseconds stationary. Manual Start and the scripted chat start produced identical steady commands. On Cloud, all 2,198 points in the matched 475 seconds were identical across all three captures. Across the 210-case motion atlas, 18 of the 35 Original Handy Bluetooth paths changed. Every authored turn was present. The 35 cases used 3,709 points in total, up from 3,332, compared with 7,919 on Windows. The other 175 cases matched the previous Android build point for point. The Android gentle hold still uses the 10% pace measured above. [Reversal plot](../../build/reviews/android-reconciliation/bluetooth-reversals.png) · [Case-by-case measurements](../../build/reviews/android-reconciliation/case-deltas.csv) · [Received wire points](../../build/reviews/android-reconciliation/wire-evidence.zip)

- **Chat receipts and pace.** A reply that adds two base rebounds now records the ordered stroke counts and endpoints. Removing them records the return to full sweeps. A pace-only edit records the accepted numerical change. Chat and Live show the next accepted pace alongside the last dispatched pace until the new batch reaches the transport. Bluetooth and Cloud tests observed 25% commanded and 32% pending immediately after a reply, followed by 32% commanded with the pending indicator cleared. [Receipt coverage](../../core/src/test/kotlin/com/magichandy/mobile/core/session/StrokeReceiptTest.kt) · [Application and wire test](../../transport/src/test/kotlin/com/magichandy/mobile/transport/handy/HandyMotionCaptureTest.kt)
- **Long chats.** When a conversation exceeds its recent window, Android includes up to 2 KiB of verbatim, numbered quotations from its first 16 stored entries. The quotations share the existing message and byte budget with the recent history. The final user message stays last. A replay of the original five opening exchanges and recall question with a connected simulated Handy confirmed that 25%, 35% and the subsequent five-point increase reached the structured provider request. Room tests retrieved the correct opening with 520 stored entries. This verifies the input available to ChatGPT. The post-change test used a deterministic provider, and details from an omitted middle section can still be unavailable. [Recorded-dialogue replay](../../transport/src/test/kotlin/com/magichandy/mobile/transport/simulated/ChatHistoryTest.kt) · [Prompt admission](../../core/src/main/kotlin/com/magichandy/mobile/core/chat/PromptBudget.kt)
- **Cloud resume.** When a responsive slider stops short of its resume position, Android requires an acknowledged halt and a second stationary, in-range reading. It keeps the original run paused and enables manual retry after the roster confirms Stop. Missing evidence, a moving slider, a failed halt or loss of the connection still goes through Stop and recovery. The simulation verified that a stalled member held the whole linked roster, correcting the slider and pressing Resume continued the same journaled run, and an explicit Stop during positioning left it idle. [Session tests](../../transport/src/test/kotlin/com/magichandy/mobile/transport/simulated/ResumePositionTest.kt) · [Readback tests](../../core/src/test/kotlin/com/magichandy/mobile/core/motion/StartupReconciliationTest.kt)

The desktop project received the Cloud refill headroom measurement and the failed opening-pace recall, with the suggested changes and reproduction details. The Android emulator and inert peers measure app behavior and received commands. The revised Bluetooth feel on a physical Original Handy remains to be assessed.


The signed **MagicHandy Test 0.1.9** APK uses the same signing certificate and application ID as the previous test build. Its SHA-256 is `70136b70859a64e861e11991b50bf4d89024472744ed842d8a4bb373ca73accf`. The build passed all 1,093 automated tests, screenshot verification, lint, release and device-test content checks, APK signature verification and 16 KiB ZIP alignment. [Reconciled test APK](../../build/device-test/MagicHandy-Test-0.1.9-reconciled.apk)
