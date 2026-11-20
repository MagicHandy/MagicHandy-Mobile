You are consulting as a senior mobile product designer on MagicHandy Mobile. Read the files listed below first. Then produce the deliverables in "What to deliver". Work only inside the current directory: do not modify any other files, do not run builds or installs, and do not use the network except for your image generation tool.

## The product

MagicHandy Mobile is a planned native Android app (Kotlin and Jetpack Compose), a pared-down mobile port of the open-source MagicHandy desktop app. It is for adults only. It lets a person chat with an AI model that also drives intimate devices through one shared motion engine.

Scope of v1:

- **Chat.** Sessions and streaming replies. The model can change motion through LLM motion modes.
- **Autopilot.** The model plans motion changes between messages on its own timing.
- **Live controls.**
  - Stop is always available.
  - Pause.
  - Edge, a gentle hold.
  - Top speed and stroke range limits.
- **Devices.** The Handy over Bluetooth, Cloud or Intiface (including several Handys), Intiface vibrators, DG-LAB Coyote 3.0 (e-stim), Autoblow Ultra and VacuGlide.
- **Models.** ChatGPT sign-in, OpenRouter, and Ollama or llama.cpp servers on the local network. Each model connection has one capability level: Simple, Intermediate, Advanced (no reasoning) or Advanced.

## Rules that must not be broken

- **Stop:**
  - always visible in the same place on every screen, at least 56 dp tall;
  - red is used only for Stop;
  - also on the notification, the lock screen and a Quick Settings tile.
- **Colour meaning:**
  - green only for actions that start motion;
  - one interactive accent hue;
  - no glow effects.
- **Status:** always a dot plus a word, never colour alone.
- **Honesty:**
  - commanded values are labelled as commanded, not measured;
  - failures are stated plainly, with one next step;
  - connecting a device never starts motion;
  - reconnecting never restarts a run.

## New direction from the owner (2026-11-18)

- Some things should be hidden in a dedicated, compact settings menu.
- The motion control menu should sit behind a menu rather than on the main chat surface. This is the LLM motion mode: Simple (formerly Creative v2), Layered, Pattern library and Off.

## Read these files

- `../../android-architecture-plan.md`: architecture and scope.
- `../../docs/mobile-system-design.md`: lifecycle, Stop, devices, network and Autopilot behaviour.
- `../canvas/*.dc.html`: the current design sketches as HTML.
  - Each file is one 390 × 844 phone screen, except `Main.dc.html`, the overview.
  - You may open them in a local browser if that helps. They are plain HTML apart from a missing `support.js`.
  - Prefixes:
    - GT = Graphite Touch;
    - GD = Graphite Deck;
    - LL = Lowlight;
    - IN = Instrument;
    - NV = Native;
    - F- = flows drawn in Graphite Touch.
  - The current recommendation is a blend: Graphite Deck's layout and Graphite tokens, with Lowlight as an extra OLED palette.

## What to deliver

1. **Critique.** A frank critique of the five directions and the flow screens: mobile usability, one-handed reach, safety clarity, density, consistency and accessibility. Name the screen files you mean.
2. **Compact settings menu.**
   - What stays on the main surfaces, and what moves into the menu.
   - The entry point and the presentation (sheet, popover or something better).
   - The structure, and how the motion mode menu works, including mode descriptions and how options depend on the capability level.
3. **Ideas and enhancements.** 8 to 12, adapted or from scratch, specific to mobile.
   - Possible areas: gestures, haptics, widgets, Wear OS, lock screen, glance or ambient modes, onboarding, accessibility, error recovery, Autopilot transparency, multi-device views.
   - For each idea: what it is, why it helps, any conflict with the rules above, and effort (S, M or L).
4. **New visual directions.** One or two not already covered, each with:
   - a palette as hex values;
   - type choices;
   - shape language;
   - a one-paragraph description.
5. **Concept images.**
   - Use your image generation tool to create 6 separate portrait images (1024×1536). Do not combine them into a sheet.
   - **Every image:**
     - an Android phone app screen in a dark theme;
     - UI only, with no people, bodies, devices or suggestive imagery;
     - realistic legible UI text using wording from this product ("Playing · Pace 48%", "Autopilot", "Layered", "Stop");
     - a full-width red Stop bar at the bottom of the screen.
   - **Images 1–3** use the Graphite look:
     - near-black graphite surfaces #0E0F11 and #16181B;
     - warm off-white text #E9E7E2;
     - a single steel-azure accent #5B9DD9;
     - flat controls, small radii, no glow.
   - **The six images:**
     1. The chat screen with no motion-mode chip on it. It has a clear entry point to the compact settings menu, a one-line Now playing strip, and the Stop bar.
     2. The compact settings menu open over the chat. It shows Motion mode, Style, Autopilot tuning, Persona and Model, compactly.
     3. The motion mode menu open, listing Simple, Layered, Pattern library and Off, each with a one-line description and the current one checked.
     4. Your strongest new mobile idea, drawn as a screen.
     5. Your first new visual direction, as the chat screen while playing.
     6. Your second new visual direction, or a second strong idea, as a screen.
   - After generating, copy every image file into `./images/` here, named `01-chat.png`, `02-settings-menu.png`, `03-motion-mode-menu.png`, `04-idea.png`, `05-direction-a.png` and `06-direction-b-or-idea.png`.
6. **Report.**
   - Write everything above to `./report.md` in clear, plain English.
   - Include a table that maps each image file to the idea it shows, and the exact prompt you sent to the image tool for each.
   - Finish with a short ranked list of the changes you would make first.
