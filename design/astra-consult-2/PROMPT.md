You are consulting again on MagicHandy Mobile, this time on the overall product, architecture and design direction. Work only inside the current directory: do not modify any other files, do not run builds or installs, and do not use the network except for your image generation tool.

## Goal

Refine the direction for a v1 that is simpler and better suited to a phone. Be willing to recommend removals. Justify each one, and name any owner decision it would reverse rather than silently dropping something.

## Read first

- `../../android-architecture-plan.md`: architecture, scope, milestones, simplifications (§15) and your previous consult (§16).
- `../../docs/mobile-system-design.md`: lifecycle, Stop, devices, network, Autopilot, budgets.
- `../astra-consult/report.md`: your previous review. Build on it, and correct it where the facts below differ.
- `../canvas/*.dc.html`: the current sketches, one 390 × 844 phone screen each, plus `Main.dc.html` and the `A-Astra` board.

## Owner decisions (keep these unless you flag a reason to revisit)

- **App form:**
  - native Android, Kotlin only;
  - no Go, no local server, no WebView;
  - a separate repo.
- **Relationship to the desktop app:**
  - the app is not kept in sync with it;
  - chat behaviour is brought to parity now and then by comparing code.
- **Priorities:**
  - mobile first;
  - remote inference first: ChatGPT sign-in (GPT-6.1 Terra at Advanced), OpenRouter, and Ollama or llama.cpp servers on the local network;
  - on-device inference later (Qualcomm, Tensor).
- **v1 must include:**
  - chat;
  - Autopilot, which is essential;
  - the same devices as the desktop: The Handy over Bluetooth, Cloud or Intiface (several Handys too), Intiface vibrators, DG-LAB Coyote 3.0 (e-stim), Autoblow Ultra and VacuGlide.
- **Already decided:**
  - no pattern library;
  - motion mode lives behind a menu;
  - settings changed once a session or less live in one compact settings menu.
- **Already applied:** the simplifications in plan §15.
- **Still proposed:**
  - drop The Handy via Intiface;
  - ship only the built-in persona presets in v1;
  - ship three palettes;
  - make the bottom-row Settings item open the compact sheet.

## Facts to use (these correct your previous report)

- **Motion modes.** The mobile modes are Layered, Simple and Off. When a VacuGlide leads, the choices are Tempo and Off instead.
  - **Layered works at every capability level.** On the desktop, the default models run Layered: Gemma 4 E4B at Simple and Gemma 4 12B at Intermediate, both with paired-endpoint edits, and GPT-6.1 Terra at Advanced with hosted Layered.
  - **Descriptions** (keep them):
    - Layered: "The AI adjusts reach, location and pace separately and keeps the rest."
    - Simple: "A flowing phrase that keeps developing over many strokes."
    - Off: "The AI only talks and never moves the device."
- **Capability levels.** The UI shows Simple, Intermediate and Advanced. Advanced picks its no-reasoning contract by itself when a connection's reasoning is off.
- **Style.** Gentle, Balanced or Intense biases the character of the motion. It does not change how replies are written.
- **Session buildup.** This is Autopilot's finish target. A desktop eval ran 8-minute sessions on Advanced models:
  - Saying the target in chat alone reached 100% at 8 minutes in every run.
  - The buildup bar alone reached the finish in only 5 of 6 runs, and only after its prompt was reworded.
  - It still helps planners that are not shown the chat.
- **Graphite colour rules:**
  - Accent #5B9DD9 is the only interactive hue.
  - #287D42 marks actions that start motion.
  - #4FC06D marks running or OK status.
  - #D8B66A marks warnings.
  - #D6453F marks failures.
  - #C43C36 is the Stop fill, and only Stop is solid red.
  - No violet or purple, no blue-green decorative tones, no glow.
  - Status is a dot plus a word.
  - Corner radii are 10, 6 and 4.
  - One card level.
- **Stop:**
  - It is the last row of the app frame: tabs sit above it, sub-pages drop the tabs without moving it, and with the keyboard open it stays directly above the keys.
  - It is at least 56 dp tall.
  - It also appears on the notification, the lock screen and a Quick Settings tile.
  - Outcomes are confirmed, pending, unconfirmed or no device. Never claim an outcome that no device acknowledged.
- **Known risks:**
  - A VacuGlide keeps its last speed if the phone app dies or goes offline, and Android kills apps routinely.
  - Intiface can acknowledge Stop without delivering it to The Handy.
  - Coyote needs its safety notes acknowledged before the first connection.

## What to deliver

1. **Direction in one paragraph.** What MagicHandy Mobile v1 is, and what it deliberately is not.
2. **Simplification and removal table.**
   - Cover every feature, setting and screen in the plan and on the canvas.
   - For each, give a verdict: Keep, Simplify, Defer (after v1) or Remove.
   - For each, give the reason, what the user loses, the engineering saving (S, M or L) and any owner decision it touches.
   - Include at least: The Handy via Intiface, several Handys, Edge, Pause, session buildup, check-ins, style, personas, memory, chat sessions, LAN discovery, capability levels, the Intermediate and Simple contracts, run history, the volume-key Stop, notification actions, palettes, the five UI languages, diagnostics export, the VacuGlide check-in, Coyote, Autoblow Ultra and VacuGlide.
3. **Architecture refinements.**
   - Module granularity (fewer modules?), persistence (Room versus DataStore or files), the background model, the porting order, port-verification fixtures and testing.
   - Say what to simplify and why. Keep the safety invariants: one engine, Stop always works and fences late commands, connecting never starts motion.
4. **Converged design.**
   - One design language: name it and give its tokens.
   - The information architecture: tabs, sheets and entry points.
   - The complete v1 screen inventory, kept minimal, and the component inventory.
   - The edge states that need their own screen and those that need only a banner.
5. **Concept images.**
   - Use your image generation tool for 6 separate portrait images (1024×1536) in the converged direction. Do not combine them into a sheet.
   - **Every image:**
     - UI only, with no people, bodies, devices or suggestive imagery;
     - legible text using this product's wording;
     - Stop as the last full-width red row.
   - **The six images:**
     1. Chat while playing with Autopilot on.
     2. Live controls with Autopilot.
     3. The Devices roster, "In this run" and "Not in this run", with The Handy, a vibrator and a Coyote.
     4. The compact settings sheet.
     5. The lock-screen public notification and the Quick Settings tile. Use neutral system styling.
     6. Your choice: the strongest simplification shown as a screen, or the Lowlight OLED palette version of Chat.
   - Copy them to `./images/` as `01-chat.png`, `02-live.png`, `03-devices.png`, `04-settings.png`, `05-lockscreen.png` and `06-choice.png`.
6. **Report.**
   - Write everything to `./report.md` in plain English.
   - Include the exact prompt sent to the image tool for each image.
   - End with the 10 changes you would make first, ranked.
