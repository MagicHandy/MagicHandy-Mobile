# Handy motion quality on Android

**November 21, 2026 · Android 0.1.5 compared with the signed 0.1.6 test build · desktop alpha.89 (`1b80d72e`)**

After this command-trace review, the owner tested 0.1.6 with ChatGPT on a physical Pixel 10 Pro XL and accepted its performance for the initial release. [Phone acceptance record](../backend/device-checklist.md)

The review workstation stores the atlas, raw captures and internal test APK under `build/`. To compare the current source with the desktop renderer on another machine, follow the capture instructions. For installation, use the production APK on GitHub Releases. [Capture instructions](../../tools/motion-review/README.md) · [Public Android release](https://github.com/MagicHandy/MagicHandy-Mobile/releases/tag/v0.1.6)

Android had two point-density errors with opposite effects. Manual Start used a bounded ramp with too few samples to reproduce the desktop's rounded reversal. Generated motion on a single Original Handy over Cloud used a more detailed fit than the one the owner had preferred on that hardware. The regular desktop and Android Bluetooth fits already agreed on the generated full-sweep case. Intiface had a separate error that could produce an actual stationary command across a reversal.

## What the desktop review established

I revisited the actual desktop chats *Investigate LLM and Decisions API (2)* and *Improve motion schema controls*, along with the pinned visual review and hardware acceptance records. The process compares the compiled loop, quantized points, segment velocities, phase portrait, acceleration and seams. It retains failed fits, inspects the overview sheets and follows individual outliers into the raw commands. A second capture through the public application controls covers startup, changes to a running score and Stop. [Desktop visual-review procedure](https://github.com/MagicHandy/MagicHandy/blob/1b80d72ee4b90a5ab80a9bb3fe373b1640388f80/docs/motion-visual-review.md)

In the October comparison, the owner preferred the sparser variant D on an Original Handy with firmware 4.3.0 over Cloud, including the slower compound phrase. The still-sparser E variant lost pattern definition. Some short-to-full transitions retained slight hesitation even with D. The accepted policy keeps every authored turn and limits optional position error to the smaller of three percentage points or 12% of the shortest moving leg, with a 0.3-point floor. Whole-percent encoding adds at most another half point to that budget. [Desktop acceptance and rejected variants](https://github.com/MagicHandy/MagicHandy/blob/1b80d72ee4b90a5ab80a9bb3fe373b1640388f80/docs/cloud-sampling-review-2026-10-24.md) · [Desktop sampler policy](https://github.com/MagicHandy/MagicHandy/blob/1b80d72ee4b90a5ab80a9bb3fe373b1640388f80/internal/motion/sampling_policy.go)

## Points actually sent to The Handy

These counts are decoded HTTP JSON and GATT payloads through normal Start and structured chat on an Original Handy profile, a requested speed of 25%, and a 0–100% stroke window. The measurement includes the point at time zero and ends just before 12 seconds of stream time. All four updated traces match the independently built Go desktop reference at every transmitted timestamp and position in that interval. The historical tests replay the pre-R8 Kotlin build outputs from 0.1.5. The preserved final R8 DEX matched the DEX inside its signed APK. [End-to-end capture](../../transport/src/test/kotlin/com/magichandy/mobile/transport/handy/HandyMotionCaptureTest.kt)

| Entry point and route | Android 0.1.5 | Android 0.1.6 | Desktop alpha.89 |
|---|---:|---:|---:|
| Manual Start, Cloud | 41 | 56 | 56 |
| Chat full sweeps, Cloud | 149 | 56 | 56 |
| Manual Start, native Bluetooth | 41 | 149 | 149 |
| Chat full sweeps, native Bluetooth | 149 | 149 | 149 |

At 10%, the previous manual Cloud path could encode a whole half-stroke as one straight line from endpoint to endpoint. The 12-second sampler capture contained eight points. The new manual carrier uses the same Full sweeps recipe as desktop, with 36 points in that window on Original Handy Cloud. The velocity plot shows the carriage command slowing into the reversal and accelerating out of it. At 25%, the formerly over-detailed chat Cloud path sends 62% fewer points. Across all 33 single-Original-Cloud generated cases, the counts fell from 7,688 to 3,240. Native Bluetooth, Handy 2, linked-device batches and transition windows keep the precise fit. [Manual carrier](../../core/src/main/kotlin/com/magichandy/mobile/core/motion/ManualTimeline.kt) · [Buffered sampler](../../core/src/main/kotlin/com/magichandy/mobile/core/motion/BufferedSampler.kt)

The packet size limits and the point density are separate. In the updated 25% case, the first Cloud append contained 12 points covering 2,486 ms. Bluetooth primed 28 points covering 2,132 ms in two packets of 20 and 8. Both ultimately sent their complete fitted path. The capture checks absolute zero-based tail indices, a single initial flush, ascending times and the absence of any movement writes after Stop.

## Other causes of hesitation

| Path | What was wrong | What changed |
|---|---|---|
| **Handy through Intiface** | The fitter could miss a turn beside a batch boundary, or send successive destinations that mapped to the same physical actuator step. In the full 25% case this created a **110 ms stationary command** across the base turn. | The fitter preserves true turns and authored holds, fits at the negotiated step resolution and respects the minimum interval across appends. The pacer schedules against absolute deadlines. The four representable bridge captures matched every desktop time and endpoint. An unrepresentable linked case now stops before either device is positioned. [Linear fitter](../../core/src/main/kotlin/com/magichandy/mobile/core/motion/LinearSampler.kt) |
| **Changing scores while moving** | Android joined different stroke bands using a curve that assumed zero velocity and acceleration at both ends. The incoming and destination curves can have substantial acceleration at the very moment they reverse. | A bounded, monotone joining stroke carries endpoint velocity and acceleration across each seam. It searches nearby phrase slots for a feasible entry, preserves already submitted points and rejects a join with an interior speed dip. [Score timeline](../../core/src/main/kotlin/com/magichandy/mobile/core/motion/ScoreTimeline.kt) · [Joining stroke](../../core/src/main/kotlin/com/magichandy/mobile/core/motion/TurnBridge.kt) |
| **Cloud and Bluetooth buffer timing** | Android timed playback from the Play acknowledgement, could calculate a linked offset before Cloud clock synchronization, and used a fixed refill margin even when several Bluetooth writes were needed. A late wake could try to append after accepted coverage had expired. | Playback uses the measured Play round-trip midpoint. Linked Cloud computes the offset after clock refresh. The refill margin accounts for the entire append. Expired coverage or an explicit active-playback fault takes the Stop path. Initial prefill still accepts legitimate idle state. In the zero-latency public traces both versions had at least 1,500 ms of coverage before refill. The delayed-peer tests establish a different failure mechanism from a repeatable hesitation at every turn. [Playback scheduling](../../core/src/main/kotlin/com/magichandy/mobile/core/motion/ManualPlayback.kt) · [Timing regressions](../../transport/src/test/kotlin/com/magichandy/mobile/transport/handy/HandyPlaybackTimingTest.kt) |

The Intiface comparison used a 100-step feature, a 110 ms minimum interval and the FW4+ preference. Full strokes went from 90 points to 53, with two missing reversals restored. Short strokes at 100% went from 93 to 100 points and recovered 17 missed turns. In the separate 330 ms minimum-interval case, the old fit spent 4.692 seconds on repeated steps while attempting incompatible motion. Both updated Android and desktop refuse that case.

## Numerical and visual findings

The Android atlas contains **210 matched HSP cases** across three Handy profiles and two routes. It covers manual speed 10 and 25, plus eleven generated shapes at 25, 60 and 100. Each case includes a six-panel figure and raw data. I inspected all **18 overview sheets**, then the turnaround, compound, bridge, runtime and outlier details. The atlas also includes five bridge comparisons and eight historical/current public-path recordings. [Capture and reproduction instructions](../../tools/motion-review/README.md)

- **All 210 point counts and all authored turn times matched.** Every timestamp and position matched desktop for **208 of 210** cases, up from 163. All 210 updated HSP captures had zero stationary time from consecutively encoded equal positions within the sampled 12 seconds. The deliberately soft shapes still spend longer near the ends of their travel.
- Both residual cases are the Handy 2 Pro default field at 100%, once on each route. Both implementations emit 312 points. A handful of optional samples differs between 11.548 and 11.822 seconds. The maximum difference between the two interpolated command paths is **0.474 percentage points of stroke**. The maximum deviation of updated Android from its own compiled path in this case is 0.782 points, within the 0.8-point precise-fit budget.
- At 13.25 seconds the runtime tests changed the active motion. At 20.65 seconds they issued Stop. The recordings retain the queued future endpoints, shown shaded. Each device peer received one main Play and no later motion write after Stop. Simulated latency and firmware-state tests separately exercise clock refresh, a long multi-packet append, a stalled scheduler and stream-specific starvation.

## Independent review

Gemini 4 Neon reviewed five source packets in Antigravity using `gemini-4-neon-xhigh` and `--effort xhigh`. The reports cover the buffered fitter, wire encoding, Intiface, curve geometry, and clocks/refill. Every session acknowledged its complete input. I agreed with the missing route-specific fit, the manual-path mismatch, the Intiface reversal defect, the transition discontinuity and the clock/refill findings. The release archive includes the five original reports and session metadata.

I found no evidence that packet splitting discarded HSP points or that a larger fixed point count would solve the reported problem. The review raised two other hypotheses about explicit gesture rests and switching a live score back to manual. The supported mobile gesture grammar does not generate a rest leg, and clearing the active score ends the stream before a fresh timeline starts. Neither sequence explained the captured output. The firmware's actual interpolation and carriage motion on this Android build still require an owner-operated comparison.

## Test package

The release check passed **953 tests** (305 core, 240 transport and 408 app), including **80 screenshot comparisons**, with zero skips, failures or errors. Debug and device-test lint reported zero issues. Debug, release and device-test assembled, and both minified content checks passed. Android SDK tools verified the APK signature, its match with 0.1.5, its non-debuggable manifest and 16 KiB alignment. MagicHandy Test 0.1.6 has version code 7, supports Android 12 and newer, and has SHA-256 `23d715a261355458826ca1a204922413893c6c6bd383c902fe554e3269c63606`.
