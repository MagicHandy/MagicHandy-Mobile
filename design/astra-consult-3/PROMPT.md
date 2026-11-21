You are consulting a third time on MagicHandy Mobile, this time on two new flows the owner has approved for building: a first-run setup and persona management. Work only inside the current directory: do not modify any other files, do not run builds or installs, and do not use the network except for your image generation tool. Do not open `../../../MagicHandy` (the desktop app) or any settings or data folder; the facts you need are below.

## Goal

Suggest enhancements to the six sketch boards that make them clearly better on a phone: easier to understand, faster to complete, safer, or more accessible. Prefer small, concrete changes over new features. The owner will adopt only what is clearly beneficial, so say plainly when something is a matter of taste. Flag anything that would reverse an owner decision rather than silently dropping it.

## Read first

- The six boards, one 390 × 844 phone screen each, in `../canvas/`:
  - first-run setup: `V-SetupModel.dc.html` (step 2), `V-SetupCompanion.dc.html` (step 3) and `V-SetupReady.dc.html` (step 4);
  - personas: `V-Personas.dc.html`, `V-PersonaDefault.dc.html` (the built-in persona) and `V-PersonaCustom.dc.html` (one the person made).
- The boards they sit beside: `V-Onboarding.dc.html` (Before you start, which becomes step 1), `V-Settings.dc.html` (the compact settings sheet), `V-AddServer.dc.html`, `V-AppSettings.dc.html`, `V-Chat.dc.html` and `V-RefusalFallback.dc.html`.
- `../../android-architecture-plan.md` (especially §4, §12, §14 and §15), `../../docs/mobile-system-design.md` and `../../docs/frontend/status.md`.
- Your earlier consults: `../astra-consult/report.md` and `../astra-consult-2/report.md`.

## Owner decisions (keep these unless you flag a reason to revisit)

- **Setup is four steps, all but the first skippable:** Before you start (adults only and a Stop rehearsal), Choose your AI, Your companion, You're set. Permissions are still asked in context, never up front.
- **Choose your AI** offers ChatGPT sign-in (GPT-6.1 Terra), OpenRouter with the person's own key, and a server on the local network (Ollama, llama.cpp or another OpenAI-compatible server). Each opens the existing setup page for that provider; saving it moves setup on.
- **A persona carries only:** a name; how explicit replies may be; a reaction style; a personality description of at most 500 characters; and reply length. Nothing else.
  - How explicit: Utility ("No sexual content. Replies stay practical."), Warm ("Flirty and suggestive, never graphic."), Intimate ("A sensual partner voice with indirect language.") or Explicit ("Direct, graphic sexual language.").
  - Reaction style: Neutral, Playful (teases lightly and starts things), Tender (attentive, reassuring, unhurried), Teasing (withholds and draws things out), Dominant (leads, and says what happens next) or Submissive (follows, asks and defers).
  - Reply length: Short or Detailed.
- **The built-in persona, MagicHandy,** keeps its name but its personality is fully customizable, with Reset to default. People can also create, use, duplicate and delete their own personas. Each persona has its own memory; deleting a persona deletes its memory, while chats keep their messages.
- **The persona name is edited where it is shown, at the top of the editor,** not in a separate field.
- **Not in v1:** portraits, lore (background facts), and importing or exporting personas.
- **Settings stays in the bottom row** and opens the compact sheet.

## Facts to use

- **The desktop composes personas from code-owned parts.** Each explicitness level and reaction style is a fixed instruction block written in code, never user text. The personality description is quoted to the model as data. No style block may mention motion, speed, the device or JSON, so a Dominant persona can never imply authority over the device. Neutral composes nothing, so its prompt is byte-identical to having no style.
- **The description, and the person's anatomy wording on the desktop, apply only at Warm, Intimate and Explicit.** At Utility they are not sent.
- **A persona never changes** speed limits, the stroke range, what the model is allowed to control, Autopilot, or Stop.
- **Today the app always replies at Utility.** Setup is the first place a person can change that. The desktop also preselects Utility.
- **ChatGPT can refuse some messages.** The app can retry a refused message once on a local server the person chose (board `V-RefusalFallback`).
- **Graphite rules:**
  - accent #5B9DD9 is the only interactive hue;
  - #287D42 marks actions that start motion, #4FC06D running or OK, #D8B66A warnings, #D6453F failures;
  - #C43C36 is the Stop fill, and only Stop is solid red;
  - no violet or purple, no blue-green decorative tones, no glow;
  - status is a dot plus a word;
  - corner radii are 10, 6 and 4, with one card level;
  - Inter for text and JetBrains Mono for readouts;
  - every touch target is at least 48 dp, and layouts must reflow at 200% font scale.
- **Stop** is the last row of every screen: a full-width red bar at least 56 dp tall, directly above the keyboard while typing. The persona and setup screens keep it.
- **The app ships in English, Spanish, Brazilian Portuguese, Japanese and Simplified Chinese,** so labels must survive longer translations and languages written without spaces.

## What to deliver

1. **Assessment.** For each of the six boards, what works and what does not, in a few lines.
2. **Enhancement table.** For each idea: the board, the change, who benefits and how, the cost (S, M or L), any risk, and a verdict: Adopt (clearly beneficial), Consider (taste or trade-off) or Skip. Cover at least:
   - helping a person choose between the three model providers;
   - making the explicitness choice clear and comfortable, given it is the first time it can change;
   - whether the reaction style needs a description for every option;
   - letting a person hear or see what a persona sounds like before saving, without misleading them;
   - empty and edge states: no model yet, no personas of your own, the 500-character limit, deleting the persona in use, an unsaved edit when leaving;
   - how a person switches persona quickly from Chat;
   - TalkBack, 200% font scale and long translations.
3. **Copy fixes.** Any wording you would change, as before and after.
4. **Concept images.** Use your image generation tool for 5 separate portrait images (1024 × 1536) showing your recommended versions. Do not combine them into a sheet.
   - **Every image:** UI only, with no people, bodies, devices or suggestive imagery; legible text in this product's wording; dark Graphite styling; Stop as the last full-width red row.
   - **The five images:**
     1. Choose your AI.
     2. Your companion.
     3. Personas.
     4. The editor for a persona the person made, with its name edited at the top.
     5. Your choice: the strongest enhancement as a screen.
   - Copy them to `./images/` as `01-setup-model.png`, `02-setup-companion.png`, `03-personas.png`, `04-persona-editor.png` and `05-choice.png`.
5. **Report.** Write everything to `./report.md` in plain English. Include the exact prompt sent to the image tool for each image, and end with the changes you would make first, ranked, at most ten.
