# OpenRouter review and sketch review

- **Date:** 2026-11-27
- **Scope:**
  - OpenRouter support as released in 0.1.8, including the uncommitted error-mapping work of the bug-fix batch in progress;
  - the sketch: [docs/openrouter-design.md](../openrouter-design.md) and the six V-OpenRouter boards.
- **Reviewers:**
  - Gemini 4 Neon at `xhigh` reasoning in the Antigravity CLI, read-only in a disposable copy of the repo;
  - Claude: triage, checking each finding against the code and OpenRouter's public API, and the changes.
- **Status:**
  - the first version is built on the branch `openrouter-settings`, apart from the bug-fix batch in progress (§4);
  - the sketch, its boards and the design note are revised.

The subsequent Android integration and broader Neon review are recorded separately. [Integration review](2026-11-27-openrouter-autopilot-integration.md)

## 1. Summary

Neon agreed with all twelve findings of the design note, finding 7 only in part because the batch in progress already maps four HTTP statuses. It reported 26 more: 7 P1, 15 P2 and 4 P3. After checking each against the code and the API:

- **20 are confirmed** and **6 are confirmed in part**. None are false positives.
- **Its main advice was adopted:** a smaller first version, with model search, reasoning levels, editing a saved connection and the fastest host, and with sign-in through OpenRouter, Spending and the two highest reasoning levels left for later.
- **Checked on the API on 2026-11-27:**
  - the public `GET /api/v1/models` takes `zdr=true` and `supported_parameters=structured_outputs`, and `limit=1000` returns all 302 matching models with no further page;
  - `GET /api/v1/key` exists and answers 401 without a key.
- **One finding was fixed on its own branch:** Autopilot retried failed plans forever (finding A6). It affects every provider, so the fix is on `autopilot-failures`.

## 2. Findings

### 2.1 The current implementation

| # | Severity | Finding | Verdict | What changed |
|---|---|---|---|---|
| A1 | P1 | A key that can no longer be decrypted reads as a service outage | Confirmed: the OpenRouter token source lets `UnusableCredential` through as an `IOException` | It now reads as a missing key, which the batch in progress reports as rejected credentials |
| A2 | P1 | Streamed reasoning counts against the 4 MiB stream limit | Confirmed in part: at 8,192 tokens of reasoning the stream stays near 3.4 MB, close to the limit, and it costs mobile data for text nobody sees | `reasoning.exclude: true` whenever reasoning is on |
| A3 | P1 | The model's own context and reply limits are ignored, and the chat and request budgets differ | Confirmed in part: a request can ask for more than the model writes. The budgets only differ in how much history fits, since the client admits messages again within its own budget | Requests are capped at the catalog's `context_length` and `top_provider.max_completion_tokens` |
| A4 | P1 | A model without structured outputs is still called | Confirmed | Saving and editing refuse a model the catalog doesn't list with structured outputs. Saved connections keep their JSON fallback, since some may work |
| A5 | P2 | Errors inside a 200 stream, `finish_reason: "length"` and unreadable JSON all look like a broken stream | Confirmed in part | An `error` object, at the top or in a choice, or `finish_reason: "error"`, now reports an unavailable service. Length and unreadable replies are unchanged |
| A6 | P2 | Autopilot hides provider errors and retries every 30 s forever | Confirmed: `Planner` returns null for anything but a completed reply, and `Autopilot` holds and backs off from 2 to 30 s | Fixed separately on the branch `autopilot-failures`, since it affects every provider: a failure that needs a fix holds without retrying, its banner names the cause and offers Try again, and a changed connection wakes it |
| A7 | P2 | The key travels as a bare `String`, and a cancelled save can leave its key behind | Confirmed | The key stays a `Secret` until the header, and the save's clean-up can't be cancelled |
| A8 | P2 | An offline ChatGPT or OpenRouter connection says "same Wi-Fi?" | Confirmed | Hosted connections say Offline |
| A9 | P2 | The client treats any token as OpenRouter, holds its lock during a fetch and reads the clock directly | Confirmed, with no visible harm today | Deferred. The new catalog client takes its clock as a parameter |
| A10 | P3 | The model field autocorrects, and the batch's new strings drift between translations | Confirmed | The ID field turns autocorrect off. The batch's strings are passed on to it |

### 2.2 The proposed implementation

| # | Severity | Finding | Verdict | What changed |
|---|---|---|---|---|
| B11 | P1 | PKCE through the loopback receiver may fail on `state`, `localhost` and IPv6, and can leave keys behind | Confirmed as unverified risks | Sign-in through OpenRouter is deferred |
| B12 | P1 | `models/user` with `zdr` is unverified, and lists stop at 500 | Confirmed in part: the public list takes both filters | The app uses the public list with `limit=1000`. A model whose private host lacks structured outputs can still appear; its first message then fails as unavailable |
| B13 | P1 | The reasoning mapping misses models that list only `reasoning`, the `xhigh` name, the contract switch and `exclude` | Confirmed in part: the contract follows `isReasoningOn`, which stays in step with the choice | On and Off for models without levels, `xhigh`, `none` kept out of the levels, and `exclude` |
| B14 | P2 | Spending assumes monthly limits and confuses a key's limit with the account's credit | Confirmed | Spending is deferred. Management keys are refused at the key check |
| B15 | P2 | Extra high and Max can outgrow 8,192 tokens and 90 s | Confirmed as unmeasured | The app offers levels up to High. A saved higher level moves to the nearest offered one |
| B16 | P2 | The proposed failure table blames private hosts and quotas too readily | Confirmed | The table in the design note is narrowed. The chat's failure wording stays with the batch in progress |
| B17 | P2 | Phase 1 couldn't store a level, and Check model would spend credits unasked | Confirmed | Levels are stored from the start. Check model is deferred |
| B18 | P3 | The contract and storage plans are heavier than needed | Confirmed in part | No database migration: saved connections are JSON with defaults. One new field, `reasoningEffort`. No host-order setting, since every request asks for the fastest host. Fallbacks are allowed. The catalog stays in `ModelsState`, like the server check, rather than in a screen-scoped query: it's about 300 small rows |

### 2.3 The UI

| # | Severity | Finding | Verdict | What changed |
|---|---|---|---|---|
| C19 | P2 | Chips, inline actions and rows below 48 dp, and labels TalkBack can't use | Confirmed for the boards | The built chips and rows are 48 dp, each model row reads as one sentence, and the model field says "Model: … Change" |
| C20 | P2 | The setup steps change header, and the Chat header doesn't shorten long names | Confirmed in part | The OpenRouter pages use the same headers as the other provider pages. The Chat header is in a file the batch is editing, and the names aren't longer than the IDs shown before |
| C21 | P2 | The setup board asks too much | Confirmed | Adding asks for the key, the model and its reasoning. Capability shows only on a saved connection, and Speed isn't a choice. The model shows a price for 100 replies instead of its context and per-token prices |
| C22 | P2 | The model list misses states and offers too many sorts | Confirmed | Loading, unreachable with Try again, and no match. Search can be cleared. Two orders, the price one sorted on the phone, and one filter. Suggestions hide while searching or filtering, and carry tags too. Free waits |
| C23 | P2 | The saved-connection board needs period-aware spending, a confirmation and an entry from Models | Confirmed | No Spending yet. Remove asks first, and OpenRouter rows in Models open the page |
| C24 | P2 | The connect board misses its waiting, error and offline states | Confirmed in part | Without sign-in, the key sits on the OpenRouter page and its refusals show beside Save. Reusing a saved key for a second connection waits, since a saved connection can now change models |
| C25 | P3 | The reasoning sheet's footer is wrong when Autopilot plans with another model, and "tokens" is jargon | Confirmed | "Applies from your next message." Max isn't offered, so its line is gone |
| C26 | P3 | The failure board's actions crowd in translation, and its text is too technical | Confirmed | The board's text is fixed. The built chat failure belongs to the batch in progress |

## 3. Wording

Neon's wording table was adopted, with these differences:

- **The filter chip** stays "Reasoning optional", like the row tag, rather than "Without reasoning". It keeps the models that can reason but don't have to.
- **Privacy:** "providers that say they don't keep or train on your chats". These are plain words, and "say" because it's the providers' own claim.
- **The model's default level** is named in the hint, as "This model defaults to High", rather than tagged on a choice. At 200% text, a "Suggested" note inside the control broke mid-word, and the hint already says which choice is fastest.
- **"Contract"** is gone from the OpenRouter pages, along with the string that used it.

## 4. Built on the branch

`openrouter-settings`, from `3abba0d`, kept apart from the batch in progress. Merging needs that batch first: both change `CompatibleProvider.kt`, `UserIntent.kt`, `MobileSession.kt`, `ChatSheets.kt`, `MagicHandyRoot.kt` and the five `strings.xml` files.

- **Core:** reasoning levels, catalog models and their state, a key-and-catalog port, saving and editing with checks, and `LoadOpenRouterModels`.
- **Transport:**
  - `OpenRouterAccount` loads the public catalog without a key, cached for 15 minutes, and checks keys with `GET /api/v1/key`;
  - the chat client sends levels, sizes the room for reasoning by level, caps requests at the model's limits, asks for the fastest host, allows fallbacks, keeps reasoning text out of replies and reports errors sent mid-reply.
- **App:** the OpenRouter page for adding and editing, the model picker, the Reasoning page under Chat model, Models rows that open saved OpenRouter connections, Offline for hosted connections, and text in all five languages.
- **Tests:**
  - 5 new in `:core` for the reasoning rule and the price estimate;
  - 13 new in `:transport`, for catalog parsing, the key check, reasoning requests, limits, mid-stream errors, and saving, editing and loading against a fake catalog;
  - 13 new screenshots, and 2 updated for the new OpenRouter page.
