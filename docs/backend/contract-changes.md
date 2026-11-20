# UI contract changes

On 2026-11-19, the backend added `AppState.backendNotice: BackendNotice? = null` as the last constructor property, so existing constructor calls remain source-compatible. The `BackendNotice` enum covers:

- an unavailable feature;
- a storage failure;
- a conflicting settings patch;
- pending device reconciliation;
- a run already in progress.

The next queued action clears the notice.

The review also added `UserIntent.ReconcileDevice(device: DeviceId)` so the person can record a local check for a device with unresolved Stop evidence. **UI change required** for exhaustive `when` expressions over `UserIntent`:

- Dispatch the intent from the checked device's detail or recovery action.
- Reconciling a device removes its row from both the receipt and the interrupted run.
- A receipt with zero rows left closes, and a receipt containing only confirmed rows can be dismissed.
- Existing Stop result enums and `MagicHandyCore.emergencyStop` keep their signatures.
- `StopReceipt.pressCount` counts explicit presses; a routine stop can still surface an unresolved outcome with zero presses.

Updated KDoc on existing intents and the receipt describes these behaviours. The app composition root creates the implementation using [the backend factory](../../core/src/main/kotlin/com/magichandy/mobile/core/MagicHandy.kt).
