# Wiring the backend

`MagicHandyApp` owns one lazy process core and one foreground-service host. The activity, notification receiver, tile, Autoblow buttons and service use that instance. Activity recreation only reattaches the UI. The debug and release variants each provide `createCore(app)`. The separately installable `deviceTest` variant uses the release factory. [Application](../../app/src/main/kotlin/com/magichandy/mobile/MagicHandyApp.kt)

## Release

`createCore` calls `platformCore(app)`. It supplies a session dispatcher, a dedicated elevated-priority output thread, a separate Stop deadline executor and separate Stop workers. A Stop fences output synchronously. Each transport receives its own bounded Stop attempt. Storage, chat, provider discovery, diagnostics and packet submission cannot hold the Stop admission path. [Platform graph](../../app/src/main/kotlin/com/magichandy/mobile/platform/PlatformGraph.kt) · [Stop delivery](../../core/src/main/kotlin/com/magichandy/mobile/core/motion/StopDelivery.kt)

| Dependency | Production owner and lifetime |
|---|---|
| Run host | A visible activity requests `CoreService` on connection, run start or Autopilot. Admission waits for foreground promotion. The service observes connection and run state, owns the private/public notification and scopes the playback wake lock. Swipe-away and unexpected service destruction both revoke output and call Emergency Stop. The engine checks foreground ownership again at device submission. |
| Platform events | `ActivityConnection` requests Bluetooth at explicit discovery and notifications on first connection. Android supplies radio, network identity, validation, LAN route, notification authorization and battery status. Elapsed time drives deadlines, wall time dates stored records. |
| Durable state | Room in `noBackupFilesDir` owns device records, named models and role bindings, revisioned settings, chat headers, entries and persona memory. A reply and its memory changes commit together after the admission check. Version 2 indexes messages by chat and sequence. |
| Recovery and credentials | A separately versioned journal retains unresolved Stops, including explicit Stops at idle and repeat Stops owed on device return. It uses synchronized atomic file replacement. The vault binds AES-GCM ciphertext to an opaque handle using authenticated data and serializes compare-and-set. Only the vault receives the user's keys and tokens. Diagnostics accept a timestamp and an enum, and rotate in a separate directory. |
| Devices | `PlatformDevices` opens the explicitly selected owner and binds its lifetime to a connection scope. The same engine schedules Handy HSP, Intiface, Ultra, Coyote and VacuGlide. A previously active VacuGlide can be reached by its saved token for Stop-only recovery. The cloud lookup watcher runs only while recovery is outstanding and the phone has internet access. |
| Models | The configured connection selects Responses, OpenRouter, Ollama or the appropriate compatible provider. The release graph discards cached clients for removed or replaced connection records. Named LAN discovery contacts the supplied origin. |
| Browser | The authorization port opens a Custom Tab and leases one callback socket on `127.0.0.1` for up to five minutes. The OIDC adapter verifies state, PKCE and identity before replacing the vault credential. Other entry points create no listener. |

`ReleaseGraphTest` constructs the production graph under Robolectric, observes a new install at Idle, and writes the first-run acknowledgement through Room. The physical acceptance work is recorded separately. [Phone checklist](device-checklist.md)

## Debug

The debug factory passes the process scope and real service host to the simulated graph. Its five seeded devices, discoverable linear actuator, model replies, LAN readiness check, release check, vault and device/model configuration use local fixtures. Android debug stores chats, memory, personas and preferences in its separate no-backup `simulated.db`, so the setup flow survives a debug process restart. Plain JVM simulators use in-memory stores. New model rows also use the canned provider. The Handy simulator exercises the production RPC session. The fixture artifacts are debug and test dependencies. The existing UI owner can add a selector if a runtime simulation toggle is wanted. [Debug factory](../../app/src/debug/kotlin/com/magichandy/mobile/CoreFactory.kt) · [UI requests](ui-requests.md)

## Shared wiring

`MagicHandyApp.kt` owns the core, Android host and memory-pressure callback. `MainActivity.kt` forwards visibility and permission interactions and handles the notification's Live entry. The debug `CoreFactory.kt` supplies the same host to the simulated graph. The release factory, service, receivers, Bluetooth and storage adapters live in the backend-owned source sets.

## Last non-UI wiring

`PlatformDevices` owns one `IntifaceHub` for the process. Canonical WebSocket addresses own connections. Discovery, linear features and vibrators take independent leases. Selecting an output requires fresh type, feature and device identity. The final lease closes the socket. Shared emergency Stop also reaches a discovery or handshake in progress. A discovery-only broadcast has no selected device to attribute to a receipt. A device row can report `NeedsSetup` when its identity cannot be established.

The engine fits full-precision points for each distinct linear sampling policy. Each linear owner captures mounting at enqueue, maps the live stroke window at send, and uses monotonic absolute deadlines. The owner sends one anchor and bounds pending movement replies and unsent segments. Excessive lateness or exhausted coverage records the redacted IntifaceStarved event before recovery Stop. The bridge write barrier also covers vibrator submissions. Physical confirmation remains distinct from a correlated server reply.

A bounded form queue handles only configuration changes. A pending LAN readiness request leaves motion controls and direct Stop available. The session exposes a structured, allowlisted diagnostics report, while the Android ring reader validates timestamps and enum categories. The latest 16 Stop epochs are kept in process. The journal and the rotating event files supply the durable part of a report after restart. The FileProvider grants access only to the diagnostics cache subtree. Tile presence has a dedicated dirty settings field. Debug uses the production Coyote and VacuGlide sessions over in-memory peers, validates structured chat replies and reports the installed app version and Android API to diagnostics.

Release builds use optional root signing properties. Gradle signs with the configured owner key and checks the packaged Room and serialization classes, prompt resource and numerical licence. The script uses the variant metadata to stage the APK, verifies its signature and writes SHA256SUMS. The regular build works without signing configuration. [Release guide](../release.md)

## Personas and first-run setup

Room database version 3 adds the `personas` table to the existing no-backup database. Its rows store the stable ID, name and four settings. The revisioned document stores the built-in personality, the selected ID and setup completion. Loading a settings document adds the sorted roster to its in-memory projection. A validated form uses one transaction for the changed row, dirty fields, and any deleted memory. Turn commit checks for a surviving memory owner inside the same transaction as the message. The empty ID continues to own the original built-in memory.

Chat captures its persona before any suspending turn setup. Autopilot captures a check-in's persona before calling its provider. Each stores the captured speaker with the reply. Switching a persona updates the projection independently of ongoing planning. The provider composer expands the quoted profile after selecting the mode and schema, and before fitting history. The local refusal branch uses the captured speaker and one current message. Debug and device-test/release use the same validation and selection functions.

The generated `chat/personas.json` contains code-owned wording and placeholder formats. The release contents check verifies it alongside `chat/contracts.json`. The raw fixture exporter runs the real Go composer with an in-process recorder. Regenerate with `tools/port-fixtures/export.ps1` and `tools/port-prompts/export.ps1`. Both accept a checkout-relative `-Output` for independent repeat runs. [Parity](../../PARITY.md)

The Android debug factory uses the separate Room file for the same persistence and upgrade behavior as the release app. Its simulator binds a new speaking view of the canned model to the captured name and level. Production provider requests contain only the composed messages and contract. `DebugPersonaGraphTest` reads the durable debug settings and labelled transcript back through a second Room handle.
