# UI contract changes

All additions below keep existing UI implementations and constructors source-compatible, apart from the noted consideration for exhaustive enum or sealed-type consumers. The current UI compiles.

| API | Default and reason |
|---|---|
| `MagicHandyCore.setRefusalFallback(ConnectionId?)`, `ModelsState.refusalFallback` | Default method and null selection. A person must select the local connection before a hosted refusal can send their turn there. |
| `SessionSettings.memories` | Empty list. Supplies saved persona memories for review and clearing. The existing count and switch remain available. |
| `MagicHandyCore.updateSystemAccess`, `notificationPermissionRequested`, `appBackgrounded`, `trimMemory` | Default methods let existing UI fakes compile. They carry OS access, the first notification prompt, activity visibility and memory pressure into the process core. |
| `AppSettingsState.notificationsAllowed`, `isBatteryUnrestricted`, `hasSystemAccessSnapshot`, `hasAskedForNotifications` | All four are non-null Booleans defaulting to false. The snapshot flag distinguishes first launch from an OS denial. The asked flag survives process recreation. |
| `CoyoteChannel`, `setCoyoteLimits`, `stepCoyote` | A new independent enum and two default core methods expose only explicit user-owned channel controls. A strength increase requires reported telemetry. |
| `MagicHandyCore.intifaceVibrators(address)`, `IntifaceChoice`, `UserIntent.AddIntiface.deviceIndex` | The default method returns an empty list. Choices contain the reported index and display name. The optional index defaults to null and removes ambiguity on a multi-device bridge. |
| `MagicHandyCore.stepTempoRange` | Default method for physical VacuGlide speed buttons. They change the top of that device's tempo range. |
| `StopSource.ModelReply`, `StopSource.CheckInTimeout` | New enum values distinguish a validated model Stop and an expired VacuGlide check-in. **Exhaustive consumers must include both.** Existing Stop surfaces keep their source values and call the core directly. |
| Stored IDs, summaries, settings, limits, messages and connections | Added Kotlin serialization descriptors for Room and the journal. The inclusive tempo range has an internal endpoint-pair serializer. |
| `PauseMotion`, `ResumeMotion`, `SetReversed` | Updated KDoc describes run retention, the confirmed halt required for Resume and the mounting boundary. Existing Paused and Edging phases now occur during manual playback. |

## Previously adopted at M0

`AppState.backendNotice` defaults to null and carries storage, settings-conflict, reconciliation, active-run and availability notices. `UserIntent.ReconcileDevice(device)` records the person's local check for a particular unresolved device. It can remove the last receipt row. A routine halt can surface uncertainty with zero explicit presses. The existing UI implements both changes. The baseline's new sealed intent required updating exhaustive intent consumers.

The alpha.89 history-window and sampler changes introduced no UI types. Named model context limits, recovery observers, provider factories and transport probes are internal implementation or port concerns. [UI integration requests](ui-requests.md)
