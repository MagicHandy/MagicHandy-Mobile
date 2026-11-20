# Wiring the backend

## Debug builds

In `:app/build.gradle.kts`, add the two JVM test-fixture dependencies to the debug configuration:

```kotlin
dependencies {
    debugImplementation(testFixtures(project(":core")))
    debugImplementation(testFixtures(project(":transport")))
}
```

Put the container that imports `simulatedDependencies` in `app/src/debug`. Regular project dependencies supply the production ports and factory, while the two fixture JARs supply the in-memory stores, simulated devices and canned model.

Create one core in the application composition root and pass that same instance to the activity, service and Stop receivers. This setup runs before an Android GATT adapter, database, vault or redirect receiver exists.

```kotlin
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.createMagicHandyCore
import com.magichandy.mobile.transport.simulated.simulatedDependencies
import kotlinx.coroutines.CoroutineScope

internal class DebugContainer(processScope: CoroutineScope) {
    val core: MagicHandyCore = createMagicHandyCore(simulatedDependencies(processScope))
}
```

The process scope must own a `Job`. The debug factory supplies the clock, dispatchers, in-memory stores (settings, chats, memory, models, roles, journal, vault and diagnostics), five disconnected simulated devices and a ready canned text model. The UI must collect and dispatch the user's adults-only acknowledgement before chat or manual motion. Connecting a selected device leaves it idle; an explicit Start or Test motor exercises the engine.

Every Stop surface calls `core.emergencyStop(source)` directly, and the UI collects `core.state` for the receipt. Show a heading stating that every device stopped only when `stopReceipt.isAllConfirmed` is true—both a write-only Coyote and an Intiface bridge can leave unresolved outcomes. After the person checks a device locally, dispatch `UserIntent.ReconcileDevice(id)` from the device or recovery screen and observe the next `AppState` for the remaining outcomes. Pressing Stop again reaches attached owners and retries any unresolved detached cloud targets.

Use the overload accepting `CoreDispatchers`, `Clock` and `SimulatedDevices` when testing with virtual time. To exercise process death, keep the simulated devices and in-memory journal across two test scopes: a VacuGlide keeps its last speed until it receives Stop, while a fresh process starts with motion and Autopilot off. The normal debug overload closes its executors when the process scope completes.

## Android integration

Keep the simulated composition root in the app's debug source set only. The release composition root constructs `CoreDependencies` with real Android ports. The real Handy and Responses adapters are available for that integration, and their tests run against in-memory peers.

| App dependency | Backend contract |
|---|---|
| Service and connectivity | `RunHost` confirms the foreground service is already running. `NetworkMonitor` reports both network validation and route changes. `Clock` supplies elapsed and wall time. |
| Execution lanes | Serialize session and output work independently. Reserve the priority Stop-deadline thread and a separate concurrent Stop I/O dispatcher. Launch device notification and acknowledgement readers in the `ownerScope` passed to `DeviceDirectory.connect`; the core cancels that scope after disconnect. Drive readiness from `DeviceStatus.Connected`, because later intents can run while status is `Connecting`. |
| Room | Implement `ChatStore`, `ModelStore` and `SettingsStore`, applying dirty-field patches atomically. Invoke `ChatStore.append`'s `admitted` callback at the final commit boundary of the message-and-memory transaction, and abort the transaction if it returns false. Publish committed entries through `ChatStore.state`. |
| Recovery and secrets | Implement `RunJournalStore` with atomic durable replacement and per-run reconciliation. `SecretsVault.replace` performs a compare-and-set, including during token rotation. Journal and model rows carry stable references. |
| Devices | Implement `DeviceDirectory`. The Handy Bluetooth adapter takes a selected scan result, `BluetoothConnector` and `BluetoothLink`; Cloud takes secrets and an OkHttp client. `stopSavedCloud` must resolve its target independently of any live session. |
| Sign-in and text chat | `BrowserLauncher` reserves an exact redirect URI and awaits a single callback. Pass the same `ChatGptAccount` instance to `CoreDependencies.signIn` and `ResponsesProvider`, using the same vault handle in `signInCredential`. |
| Diagnostics | Supply a bounded, off-thread `DiagnosticsSink`. The core installs `DiagnosticReporter` in its owned coroutine context and forwards categories with the event time. Production adapter constructors need no extra argument. |

The JVM verification suite reads the top-level `fixtures/` directory. The UI imports only `com.magichandy.mobile.core.api`; the composition root also imports the factory and debug wiring shown above.

See the [milestone status](status.md) for controls available in this build and the [contract change](contract-changes.md) for action notices.
