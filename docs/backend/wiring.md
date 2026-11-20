# Wiring the backend

`MagicHandyApp` owns one lazy process core and one foreground-service host. The activity, notification receiver, tile, Autoblow buttons and service use that instance. Activity recreation only reattaches the UI. The debug and release variants each provide `createCore(app)`. [Application](../../app/src/main/kotlin/com/magichandy/mobile/MagicHandyApp.kt)

## Release

`createCore` calls `platformCore(app)`. It supplies a session dispatcher, an output dispatcher, a dedicated elevated-priority Stop deadline executor and separate Stop workers. A Stop fences output synchronously. Each transport receives its own bounded Stop attempt. Storage, chat, provider discovery, diagnostics and packet submission cannot hold the Stop admission path. [Platform graph](../../app/src/main/kotlin/com/magichandy/mobile/platform/PlatformGraph.kt) · [Stop delivery](../../core/src/main/kotlin/com/magichandy/mobile/core/motion/StopDelivery.kt)

| Dependency | Production owner and lifetime |
|---|---|
| Run host | A visible activity requests `CoreService` on connection, run start or Autopilot. Admission waits for foreground promotion. The service observes connection and run state, owns the private/public notification and scopes the playback wake lock. Swipe-away calls Emergency Stop before stopping the service. |
| Platform events | `ActivityConnection` requests Bluetooth at explicit discovery and notifications on first connection. Android supplies radio, network identity, validation, LAN route, notification authorization and battery status. Elapsed time drives deadlines, wall time dates stored records. |
| Durable state | Room in `noBackupFilesDir` owns device records, named models and role bindings, revisioned settings, chat headers, entries and persona memory. A reply and its memory changes commit together after the admission check. Version 2 indexes messages by chat and sequence. |
| Recovery and credentials | A separately versioned journal uses synchronized atomic file replacement. The vault binds AES-GCM ciphertext to an opaque handle using authenticated data and serializes compare-and-set. Only the vault receives the user's keys and tokens. Diagnostics accept a timestamp and an enum, and rotate in a separate directory. |
| Devices | `PlatformDevices` opens the explicitly selected owner and binds its lifetime to a connection scope. The same engine schedules Handy HSP, Intiface, Ultra, Coyote and VacuGlide. A previously active VacuGlide can be reached by its saved token for Stop-only recovery. The cloud lookup watcher runs only while recovery is outstanding and the phone has internet access. |
| Models | The configured connection selects Responses, OpenRouter, Ollama or the appropriate compatible provider. The release graph discards cached clients for removed or replaced connection records. Named LAN discovery contacts the supplied origin. |
| Browser | The authorization port opens a Custom Tab and leases one callback socket on `127.0.0.1` for up to two minutes. The OIDC adapter verifies state, PKCE and identity before replacing the vault credential. Other entry points create no listener. |

`ReleaseGraphTest` constructs the production graph under Robolectric, observes a new install at Idle, and writes the first-run acknowledgement through Room. The physical acceptance work is recorded separately. [Phone checklist](device-checklist.md)

## Debug

The debug factory passes the process scope and real service host to the simulated graph. Its five seeded devices, discoverable linear actuator, model replies, LAN readiness check, release check, vault and stores use local fixtures. New model rows also use the canned provider. The Handy simulator exercises the production RPC session. The fixture artifacts are debug and test dependencies. The existing UI owner can add a selector if a runtime simulation toggle is wanted. [Debug factory](../../app/src/debug/kotlin/com/magichandy/mobile/CoreFactory.kt) · [UI requests](ui-requests.md)

## Shared wiring

`MagicHandyApp.kt` owns the core, Android host and memory-pressure callback. `MainActivity.kt` forwards visibility and permission interactions and handles the notification's Live entry. The debug `CoreFactory.kt` supplies the same host to the simulated graph. The release factory, service, receivers, Bluetooth and storage adapters live in the backend-owned source sets.

## Last non-UI wiring

`PlatformDevices` owns one `IntifaceHub` for the process. Canonical WebSocket addresses own connections. Discovery, linear features and vibrators take independent leases. Selecting an output requires fresh type, feature and device identity. The final lease closes the socket. Shared emergency Stop also reaches a discovery or handshake in progress. A discovery-only broadcast has no selected device to attribute to a receipt. A device row can report `NeedsSetup` when its identity cannot be established.

The engine fits full-precision points for each distinct linear sampling policy. Each linear owner captures mounting at enqueue, maps the live stroke window at send, and uses monotonic absolute deadlines. The owner sends one anchor and bounds pending movement replies and unsent segments. Excessive lateness or exhausted coverage records the redacted IntifaceStarved event before recovery Stop. The bridge write barrier also covers vibrator submissions. Physical confirmation remains distinct from a correlated server reply.

A bounded form queue handles only configuration changes. A pending LAN readiness request leaves motion controls and direct Stop available. The session exposes a structured, allowlisted diagnostics report, while the Android ring reader validates timestamps and enum categories. The latest 16 Stop epochs are kept in process. The journal and the rotating event files supply the durable part of a report after restart. The FileProvider grants access only to the diagnostics cache subtree. Tile presence has a dedicated dirty settings field. Debug uses the production Coyote controller with a simulated byte peer and reports the installed app version and Android API to diagnostics.

Release builds use optional root signing properties. Gradle signs with the configured owner key and checks the packaged Room and serialization classes, prompt resource and numerical licence. The script uses the variant metadata to stage the APK, verifies its signature and writes SHA256SUMS. The regular build works without signing configuration. [Release guide](../release.md)
