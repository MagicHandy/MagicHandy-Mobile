# Android integration decisions

The 19 November review adopted localized backend notices and per-device local reconciliation. M1 supplies process visibility, permission and memory-pressure hooks. M2 adds the notification's next-change time and the Quick Settings Stop surface. The current implementation and UI-facing follow-ups are documented in [wiring](wiring.md) and [UI requests](ui-requests.md).

The owner retained built-in presets, memory Off by default, manual Edge and a 15-minute VacuGlide check-in with Off available. The loopback browser callback implements the selected sign-in fallback. Real callback registration and physical device acceptance require the phone. [Device checklist](device-checklist.md)

The 20 November follow-up confirms full Intiface linear support, the bottom settings entry, the 1.5-second motor test and per-form completion. The UI status column records the landed Coyote, vibrator, model, memory, permission and Live navigation integrations.
