# UI contract changes

## Personas and first-run setup, 2026-11-20

The UI owner introduced the following additive declarations. The backend uses those declarations directly. The `Personality` data class also has a generated serializer for the two Room representations.

| Declaration | Behavior for callers |
|---|---|
| `Explicitness`, `ReactionStyle`, `ReplyLength`, `Personality` | Four levels, six styles and two reply lengths. The four editable settings default to Utility, Neutral, an empty description and Short. The persona name is the fifth field. |
| `PersonaLimits` and `characterCount(text)` | Both form validation and counters use the same grapheme iterator. The limits are 60 for the trimmed name, 500 for the description and 200 custom personas. |
| `PersonaRef.personality`, `PersonaRef.isBuiltIn` | Defaults preserve earlier constructors. The empty ID identifies MagicHandy. The runtime lists it first, followed by custom names in case-insensitive order. Every summary is empty. |
| `UserIntent.SavePersona(persona, name, personality, use = false)` | Null creates a new row. Saving and using it is one transaction. InvalidName covers blank, overlong and reserved names or a proposed rename of MagicHandy. Duplicate covers another custom name ignoring case. TooLong covers a description over 500. LimitReached covers creation at capacity. A missing edit target returns NotFound. |
| `UserIntent.DuplicatePersona(persona, name)` | Copies the saved four personality fields under the supplied name. The new row starts inactive with empty memory. The same name and capacity checks apply. A missing source returns NotFound. |
| `UserIntent.DeletePersona(persona)` | Acceptance means the row and its memory were removed. An active deletion selects MagicHandy in the same commit. A missing row returns NotFound and the built-in returns Unsupported. Existing messages keep their speaker. |
| `FormReason.TooLong` and `FormReason.LimitReached` | Distinguish an overlong field from a full collection. Reasons carry no input text. The three forms share the existing Busy and StorageUnavailable outcomes. |
| `FirstRunState.isSetupDone`, `UserIntent.FinishSetup` | The property defaults to true for existing fakes. The runtime supplies durable setup state. The acknowledged person can finish without adding a model or device. |
| `ChatEntry.Message.speaker` | The stored name at the beginning of a persona-authored turn, including check-ins and a successful refusal fallback. A person's messages and legacy messages without attribution use null. |

`SetPersona` changes the speaker for the next turn. `ClearMemory` applies to the selected ID. The global opt-in defaults to Off. [UI integration](ui-requests.md)

## Last non-UI items, 2026-11-20

| Addition | Meaning and compatibility |
|---|---|
| `intifaceLinearActuators(address)` and `IntifaceLinearChoice` | Read-only v3 lookup. Returns the device index, feature index, device name, descriptor and opaque selection. The default method returns an empty list. |
| `AddIntifaceLinear(address, deviceIndex = null, actuatorIndex = null, selection = null)` | Adds one linear feature. Null indices select a single eligible result. With several axes, submit both indices and the selection from the visible row. `IntifaceChoice.selection` and `AddIntiface.selection` also default to null. A fresh list must still uniquely identify the selected device. A recycled numeric slot needs the opaque selection. |
| `DeviceFamily.LinearActuator` and `DeviceSettings.LinearActuator(isReversed = false)` | Distinguish generic linear hardware from The Handy. Generic devices have no Handy model setting. The Handy keeps `DeviceFamily.Handy` and its Intiface connection kind. Both expose stroke output and the possibility that the last move finishes after link loss. **Exhaustive consumers need the new branches.** |
| `FormIntent`, `FormResult`, `FormReason` and `MagicHandyCore.submit(form)` | These changes let a form await its own durable result. Existing form intents remain `UserIntent`s for dispatch. The default method returns `Rejected(Unsupported)`. `Accepted` signals that the requested change was committed. `Rejected` carries only a localizable enum. Stop is outside the form type. |
| `MagicHandyCore.diagnosticsReport()` and `DiagnosticsReport(fileName, text)` | An explicit read-only call supplies a safe basename and a UTF-8 report at most 256 KiB. Existing implementations return a small unavailable report by default. |
| `AppSettingsState.isStopTileAdded: Boolean? = null` and `updateStopTileAdded(added)` | Null means Android has supplied no observation. Added, removed and listening callbacks persist the last observed value. The default core method is a no-op for existing fakes. |
| `Palette.OledBlack` | Added by the UI owner. Persisted Lowlight and calls from older clients select OLED black. Steel Azure remains the default. The Lowlight constant stays readable for older data. |

### Form completion

| Intent | Acceptance | Actionable rejection |
|---|---|---|
| `AddCloudHandy` | Saved a named connection and its credential handle. | Key outside 5–64 ASCII letters and digits, invalid name, missing adult acknowledgement, or duplicate connection key. |
| `AddAutoblowDevice` | Saved the selected Ultra or VacuGlide and its token handle. | Unsupported family, token outside 1–256 printable non-space ASCII characters, invalid name, missing acknowledgement, or token already saved. |
| `AddIntiface`, `AddIntifaceLinear` | Refetched the device list and saved the unique identity of the chosen feature. | Invalid address, missing acknowledgement, unreachable bridge, ambiguous or changed choice, duplicate device. |
| `SaveServer` | The named server completed an inert readiness turn for the requested model and the binding was saved. | Invalid address, model or name, private-address policy, unreachable server, provider refusal, duplicate model at that address or a superseding check. |
| `SaveOpenRouter`, `EditOpenRouter` | Saved the model configuration and credential handle. Cloud device forms check local syntax. With `OpenRouterAccess` wired, as in the app, OpenRouter forms also check the key with OpenRouter and the model against its catalog before saving, and settle the reasoning the model can't honour (docs/openrouter-design.md §10). | Invalid key, including one OpenRouter rejects; invalid model, including one it can't serve; unreachable; duplicate model with the same credential; and, for an edit, not found. |
| `Rename` | Committed the device name. | Empty, overlong or invalid name, duplicate name, or missing device. |
| `Remove` | Released the owner if connected, erased its credential and removed the saved row. | Missing device, connection still opening, active participation or evidence still requiring reconciliation. |
| `RemoveConnection` | Removed the model, its role bindings and credential, and cleared an associated refusal fallback. | Missing connection or an active run using that role. |

All forms can report storage failure or a busy or closed submission lane. A failed row deletion restores a removed credential only if its handle is still empty. No reason includes a credential, address, provider message or form input.

## Earlier M0 to M4 contract

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
