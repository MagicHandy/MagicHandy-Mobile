# MagicHandy Mobile: guide for agents

MagicHandy Mobile is a native Android app written in Kotlin with Jetpack Compose. It ports the chat and device support of the desktop MagicHandy app. There is no Go, no local server and no WebView.

## Read first

1. [android-architecture-plan.md](android-architecture-plan.md), especially:
   - §5 for the modules, the architecture rules (§5.2) and storage (§5.4);
   - §5.5 for maintainability, clarity and formatting. These rules are binding.
2. [docs/mobile-system-design.md](docs/mobile-system-design.md) for lifecycle, Stop, devices, network and data.
3. The v1 design boards in [design/canvas](design/canvas) (`V-*.dc.html`) for every screen.

## Layout

| Module | Package root | Owns |
|---|---|---|
| `:core` | `com.magichandy.mobile.core` | Session orchestration, chat, Autopilot, the motion engine, and the UI contract in `core.api` |
| `:transport` | `com.magichandy.mobile.transport` | Device codecs and sessions, model provider clients |
| `:app` | `com.magichandy.mobile` | Compose UI (`ui`), Android platform code (`platform`), wiring |

`core.api` is the only boundary between the UI and the rest of the app. Make only additive changes to it, and keep its KDoc up to date.

## Commands

Use the JDK bundled with Android Studio: set `JAVA_HOME` to `%LOCALAPPDATA%\Programs\Android Studio\jbr`.

| Task | Command |
|---|---|
| Format | `./gradlew spotlessApply` |
| Check everything | `./gradlew check` |
| JVM tests | `./gradlew :core:test :transport:test` |
| Debug APK | `./gradlew :app:assembleDebug` |

Leave every change formatted, checked and tested.

For a public release, follow the [release guide and changelog rules](docs/release.md#changelog-rules). The tagged note in `docs/releases/` supplies the GitHub release body.

## Rules that are easy to miss

- **Stop is safety-critical.** Every Stop surface calls `MagicHandyCore.emergencyStop` directly. Never route Stop through a queue, storage or UI state.
- **One engine writes to devices.** Chat and Autopilot emit semantic targets only.
- **No real hardware or services from tests or tools.** Use the simulated devices and fakes. Never scan for or connect to real devices, and never call Handy Cloud, Autoblow's cloud or a model provider with real credentials.
- **Secrets travel as `Secret`.** This covers connection keys, device tokens, API keys and sign-in tokens. Never read, print, log or commit them, and never open the desktop app's settings or data folders.
- **Treat the desktop repository as read-only.** `../MagicHandy` is reference material only. Never edit its working tree.
