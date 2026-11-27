# Android motion atlas

This review compares the received Handy commands and the shared score renderer with desktop alpha.89 (`1b80d72e`). It uses synthetic scores, an in-memory HTTP peer, simulated GATT and a loopback Intiface bridge. The tools run outside the Android APK.

From the Android project root, use the Android Studio JDK, Python 3.12 or newer with NumPy and Matplotlib, and a Go toolchain capable of building the desktop revision. The capture script archives tracked files from the adjacent desktop repository into the mobile workspace. Its Go test overlay enables the review fixtures in that copy.

```powershell
python tools/motion-review/capture.py --baseline-archive build/motion-review/0.1.6/captures.zip
python tools/motion-review/render.py .work/motion-android build/motion-review/current
```

The baseline archive supplies the historical 0.1.5 captures for the **before** series. With those files already in the work directory, omit `--baseline-archive`. The capture command also runs independently to compare today's Android with the pinned desktop source. The capture writes its Android version, commit and working-tree status to `capture.json`. Older captures without metadata retain their same-route comparison and are labeled unversioned. Open `index.html` from the render output directory. It supports direct local file access.

- **Steady motion.** The desktop reference selects its shipping Cloud or Bluetooth fitting policy. The Android capture uses the same score, calibration and route with the production timeline and sampler. For a single Original Handy over Bluetooth, Android starts with the sparse Cloud fit and adds whole-percent points near slow reversals. The report shows both desktop routes so the point cost and turnaround shape can be checked independently. Each case has the compiled period, authored turn times, a whole-loop position/velocity/acceleration trace and encoded endpoints spanning 12 seconds. Point counts cover `0 <= time_ms < 12000`. Android checks interpolation error at one-millisecond intervals.
- **Actual playback.** The transport tests exercise manual Start and a structured chat start, make an in-place change at 13.25 seconds and press Stop at 20.65 seconds. The JSON retains append times, points, tail indices, flush and Play. Cloud records the production HTTP body. Bluetooth decodes the production GATT write. The historical public-path traces replay the pre-R8 Kotlin inputs from the 0.1.5 shipping build. The recovered R8 DEX was separately checked against the signed APK. The evidence archive carries that provenance.
- **Intiface.** Five cases compare destination times and physical actuator steps. One case deliberately exceeds a linked actuator's timing capability. An explicit refusal is retained in the output.
- **Review.** Inspect the overview sheets, first-turn detail, whole-loop derivatives, phase portrait, the retarget and queued horizon at Stop. Inspect known outliers at their actual time. Look for lost turns, repeated actuator positions, broken phrase counts, seams and changes in pace. A study of physical turnaround feel additionally needs the owner's observations on the same device, route, speed and stroke range.


For a longer steady interval in the simulated wire replay, set `MAGICHANDY_WIRE_STEADY_MS` (minimum and default 13250) and `MAGICHANDY_WIRE_CAPTURE` (output directory), then run `./gradlew :transport:test --tests "*HandyMotionCaptureTest" --rerun-tasks`. The same peer checks every HSP tail index, timestamp, packet size and Stop after the extended run.
