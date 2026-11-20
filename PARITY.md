# Parity with the desktop app

This file maps each ported Kotlin file to the Go source it came from, and the desktop commit it matches (plan §7.2).

- **Snapshot:** alpha.88 `bafc7ec4` plus the PR #374 head `9a4c36cf`, which includes #373.
- **Last parity pass:** 2026-11-19. The backend checkpoints below use the exported source tree.

| Kotlin file | Go source | Desktop commit | Notes |
|---|---|---|---|
| `tools/port-fixtures/{main,hosted}.go` (verification tool) | `cmd/motion-atlas`, `internal/motion/review_atlas.go`, `internal/handyble`, `internal/transport/testdata/cloud_requests.golden.json`, `internal/chat`, `internal/llm/openai_responses.go`, `internal/httpapi/personas.go`, `internal/notices`, `web/src/i18n/locales`, `assets/brand` | `9a4c36cf` | M0.1, 49 manifest records, reproducible. See [export instructions](tools/port-fixtures/README.md) and [manifest](fixtures/manifest.json). |
| `core.motion.MotionFrame` | `internal/motion/{plan,sampling}.go` | `9a4c36cf` | Transport-neutral stream time and whole percent position. |
| `core.motion.BoundedCurve` | `internal/motion/{content,reversal_profile}.go` | `9a4c36cf` | Periodic Hermite interpolation, reversal guides and exact peak acceleration. |
| `core.motion.MotionPlan` | `internal/motion/{plan,content}.go` | `9a4c36cf` | Full-span bounded-ramp loop period, speed calibration and reversal timing. |
| `core.motion.BufferedSampler` | `internal/motion/sampling.go` | `9a4c36cf` | Buffered knot-preserving fitter, quantization and natural append seams. M0.2 covers the bounded-ramp subset. |
| `core.motion.MotionEngine` | `internal/motion/engine.go`, `internal/httpapi/stop_admission.go` | `9a4c36cf` | Mobile atomic epoch, per-owner deadlines, scoped idle disconnect and separate receipt publication for routine stops. An uncertain write retires its owner and triggers recovery. |
| `core.ports.DeviceTransport` | `internal/transport/types.go`, `internal/motion/engine.go` | `9a4c36cf` | Typed transport, write lease and independently acknowledged Stop. The clock port is mobile-specific. |
| `core.session.RunSafety`, `core.ports.RunJournalStore`, `core.testing.InMemoryRunJournal` | Mobile system design §§1.1–2 | Mobile-specific | Atomic compare-and-arm and per-run clearing after acknowledgement or a local check. Cold recovery and a subsequent Stop can reach unresolved detached cloud devices. |
| `transport.ble.BluetoothLink` | `internal/ble/{link,profiles}.go` | `9a4c36cf` | Filtered selection, MTU, profile discovery, read, notifications and serialized writes. |
| `transport.handy.{HandyCommand,HandyCodec}` | `internal/handyble/{codec,wire}.go` | `9a4c36cf` | Ten command vectors, bounded protobuf reader and correlated response evidence. |
| `transport.handy.HandyBluetoothSession` | `internal/handyble/{session,session_rpc}.go` | `9a4c36cf` | RPC 715 at connect, emergency Stop and orderly disconnect. Stop waits for a healthy GATT write, retires a stalled generation and distinguishes an unsent command from a missing correlated reply. |
| `transport.handy.{HandyTransport,HandyCloud}` | `internal/transport/{handy_bluetooth_transport,cloud,cloud_client}.go` | `9a4c36cf` | Finite HSP batches distinguish a stale first write from uncertain partial submission. Cloud owners share a dedicated priority dispatcher for REST Stop and state readback. |
| `transport.providers.ResponsesProvider`, `core.ports.ModelProvider` | `internal/llm/{openai_responses,prompt_budget}.go` | `9a4c36cf` | Text-only plan usage, bounded SSE preview and completed-event authority. |
| `transport.providers.{ChatGptAccount,VerifiedIdentity}` | `internal/openaiauth/{oauth,refresh,store,jwt}.go` | `9a4c36cf` | S256 browser handoff, verified registration, expiry and cancellation-safe serialized token rotation with vault compare-and-set. |
| `core.ports.SecretsVault`, `core.testing.InMemoryVault`, `transport.http.HttpExchange` | Mobile infrastructure | Mobile-specific | Redacted vault handles and cancellable, bounded HTTP exchanges. |
| `core.chat.TypedStop`, `core.session.ChatTurns` | `internal/httpapi/chat.go`, `internal/chatapp/workspace.go` | `9a4c36cf` | Five-locale fast path, bounded provisional text preview, transactional terminal admission against the Stop epoch and Send again for interrupted replies. |
| `core.motion.ManualPlayback` | `internal/motion/{engine,content}.go` | `9a4c36cf` | Verified bounded-ramp sampler supplies manual output and finite motor tests. |
| `core.session.MobileSession` | `internal/httpapi/{chat,motion,stop_admission}.go` | `9a4c36cf` | Process-level typed intent routing, session and Stop ownership. |
| `core.session.Devices` | `internal/deviceapp/lifecycle.go`, `internal/httpapi/playback_devices.go` | `9a4c36cf` | Device selection, asynchronous connection with per-owner scope, Stop-before-close on loss, receipt and recovery projections. `core.session.InitialState` constructs the mobile UI snapshot. |
| `core.ports.SettingsStore` | `internal/config/{settings,settings_patch}.go` | `9a4c36cf` | Field-level revision checks with atomic patches. |
| `core.ports.{ChatStore,ModelStore,DeviceDirectory,Platform}`, `core.testing.{InMemoryStores,InMemoryDiagnostics}`, `core.{CoreDependencies,MagicHandy}` | Mobile storage, platform ports and composition root | Mobile-specific | Process-scoped factory and isolated debug storage. |
| `transport.simulated.{DebugBackend,SimulatedDevices,SimulatedModelProvider}` | Mobile system design §9 | Mobile-specific | Debug-only wiring, five simulated owners, host death, persistent speed and cancellable canned replies. |
| `transport.simulated.SimulatedHandyLink` | `internal/handyble/simulated.go` | `9a4c36cf` | The debug Handy uses the production Bluetooth session with an in-memory firmware peer. |
