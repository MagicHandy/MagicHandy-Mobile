# Android integration proposals

## UI action notices

Adopted on 2026-11-19: the UI shows the notice above the footer and any Stop receipt.

Display `AppState.backendNotice` with localized copy for its cause. Because the property defaults to null, existing UI code compiles before adopting it. In the M0 debug build, gate unfinished controls (device setup, LAN models, Advanced motion chat, Edge and Autopilot); the backend returns `FeatureUnavailable` when an unfinished control is invoked.

## Foreground lifecycle

Wire the application, footer, notification, TalkBack action and Quick Settings tile to one process-wide core. The Android pass also needs a lifecycle input for when a chat-only turn moves into the background. The current UI contract has no visibility event yet, and Android acceptance still requires it.

## Device reconciliation

Adopted on 2026-11-19. Add a local-check action for unresolved devices on the device detail or recovery screen. Once the person confirms that the device has stopped, dispatch `UserIntent.ReconcileDevice(device)` and update exhaustive matches on `UserIntent`. The backend removes the checked row while keeping other outcomes. The simulated Intiface vibrator returns `UnconfirmedCause.BridgeOnly`. See the [UI contract changes](contract-changes.md).

## Physical and account acceptance

The M0 backend verifies GATT, HSP and OIDC protocol paths with synthetic data. The Android pass owns callback registration, service readiness, notification denial, battery and jitter measurements and the Pixel acceptance runs.
