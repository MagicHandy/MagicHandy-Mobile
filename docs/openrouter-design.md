# OpenRouter: review and proposed changes

Proposed on November 27, 2026 and reviewed that day by Gemini 4 Neon. The first implementation is integrated in the Android test candidate. Section 8 records the design decisions and section 10 describes the implemented scope. [Design triage](reviews/2026-11-27-openrouter-review.md) · [Integration review](reviews/2026-11-27-openrouter-autopilot-integration.md)

- **What it covers:**
  - a review of OpenRouter support as released in 0.1.8;
  - a sketch of sign-in, model search, reasoning levels, host order and editing a saved connection.
- **Evidence:**
  - the code at `3abba0d` (v0.1.8);
  - the desktop app's OpenRouter support (ADR 0065 and its reasoning and DeepSeek reviews of 2026-10-29);
  - OpenRouter's documentation and public model catalog, both read on 2026-11-27. The catalog was fetched without a key.
- **Boards:** the row "OpenRouter · proposed" on the canvas's v1 page holds V-OpenRouterConnect, V-OpenRouterModels, V-OpenRouterSetup, V-OpenRouterSettings, V-OpenRouterReasoning and V-OpenRouterProblem.
- **Work in progress:** the current bug-fix batch is fixing finding 7's HTTP statuses. This sketch builds on the reasons that fix adds to `ProviderReplyFailure`: `CredentialsRejected`, `BillingRequired` and `RateLimited`.

## 1. What 0.1.8 does

- **Setup:** one form takes an API key, a model ID typed by hand, a capability level and a reasoning switch, which is off by default. Saving stores the key in the vault and checks nothing remotely.
- **Each request:**
  - finds the model's row in OpenRouter's catalog, which is cached for 15 minutes per connection;
  - requires zero data retention (`zdr`), denies data collection, turns off host fallbacks and requires every parameter it sends;
  - sends `reasoning: {enabled: true|false}` when the model supports reasoning;
  - asks for a strict JSON schema when the model supports structured outputs.
- **Afterwards:** a saved connection can only be removed. Its name is always "OpenRouter".

## 2. Review findings

Severity follows the 2026-11-27 implementation review: P1 breaks the feature for many people, P2 costs time, money or clarity, and P3 is polish. Links point at `3abba0d`.

| # | Severity | Finding |
|---|---|---|
| 1 | P1 | **Models that must reason fail every message with the default settings.** Reasoning starts off ([OpenRouterScreen.kt:48](https://github.com/MagicHandy/MagicHandy-Mobile/blob/3abba0d1798eaa4f9b96282d3bd6e95622b16456/app/src/main/kotlin/com/magichandy/mobile/ui/models/OpenRouterScreen.kt#L48)), and saving accepts any model ([ModelAccounts.kt:89](https://github.com/MagicHandy/MagicHandy-Mobile/blob/3abba0d1798eaa4f9b96282d3bd6e95622b16456/core/src/main/kotlin/com/magichandy/mobile/core/session/ModelAccounts.kt#L89)). The client then gives up before sending when the catalog marks reasoning as required ([CompatibleProvider.kt:120](https://github.com/MagicHandy/MagicHandy-Mobile/blob/3abba0d1798eaa4f9b96282d3bd6e95622b16456/transport/src/main/kotlin/com/magichandy/mobile/transport/providers/CompatibleProvider.kt#L120)). 150 of the catalog's 477 models require reasoning, among them Claude Sonnet 5.5, GPT-6.1 Sol, the Gemini 4 models and GLM 5.3. Turning reasoning on for a model that can't reason fails the same way. Either way, every message ends in the generic error bubble, and nothing points at the switch. The fix in progress leaves this branch unchanged. |
| 2 | P2 | **The model ID is typed, and only the first message checks it.** A typo, a retired model or one without a private host saves without complaint ([OpenRouterScreen.kt:46](https://github.com/MagicHandy/MagicHandy-Mobile/blob/3abba0d1798eaa4f9b96282d3bd6e95622b16456/app/src/main/kotlin/com/magichandy/mobile/ui/models/OpenRouterScreen.kt#L46)). The catalog lookup that would catch it runs only inside a chat request ([CompatibleProvider.kt:58](https://github.com/MagicHandy/MagicHandy-Mobile/blob/3abba0d1798eaa4f9b96282d3bd6e95622b16456/transport/src/main/kotlin/com/magichandy/mobile/transport/providers/CompatibleProvider.kt#L58)). |
| 3 | P2 | **Saving doesn't check the key.** A successful save means only that the key was stored ([contract changes](backend/contract-changes.md)). `GET /api/v1/key` checks a key and uses no credits. |
| 4 | P2 | **Reasoning is only on or off, and on means the model's default effort.** The request sends `enabled: true` ([CompatibleProvider.kt:169](https://github.com/MagicHandy/MagicHandy-Mobile/blob/3abba0d1798eaa4f9b96282d3bd6e95622b16456/transport/src/main/kotlin/com/magichandy/mobile/transport/providers/CompatibleProvider.kt#L169)). Those defaults are often slow: 74 models default to high and 19 to max, including DeepSeek V4.1 Flash 1017 at high. Motion waits for the whole reply, so the effort level is the main control over reply time. The desktop offers each model's listed levels. |
| 5 | P2 | **There's no host order.** No `provider.sort` is sent ([CompatibleProvider.kt:161](https://github.com/MagicHandy/MagicHandy-Mobile/blob/3abba0d1798eaa4f9b96282d3bd6e95622b16456/transport/src/main/kotlin/com/magichandy/mobile/transport/providers/CompatibleProvider.kt#L161)). On desktop, OpenRouter's balanced routing sent every traced DeepSeek request to a 4-bit host, with an 8.6 s median. Sorting by throughput or latency chose a 0.9 s host with the same pass rate (ADR 0065). New desktop connections now start at Fastest output. |
| 6 | P2 | **A model without a private host fails without saying why.** Requiring zero data retention is right ([CompatibleProvider.kt:164](https://github.com/MagicHandy/MagicHandy-Mobile/blob/3abba0d1798eaa4f9b96282d3bd6e95622b16456/transport/src/main/kotlin/com/magichandy/mobile/transport/providers/CompatibleProvider.kt#L164)). However, 111 of the 413 models with structured outputs have no zero-retention host, for example Qwen4 Max and Claude Fable 5.2. Requests to them fail at routing. |
| 7 | P2 | **OpenRouter's failures all look the same.** HTTP 401, 402, 429 and 503 all ended as `Interrupted` ([CompatibleProvider.kt:198](https://github.com/MagicHandy/MagicHandy-Mobile/blob/3abba0d1798eaa4f9b96282d3bd6e95622b16456/transport/src/main/kotlin/com/magichandy/mobile/transport/providers/CompatibleProvider.kt#L198)). The batch in progress fixes the HTTP statuses. Three cases remain after it: <ul><li>errors sent inside the stream, as an `error` object with `finish_reason: "error"` ([:257](https://github.com/MagicHandy/MagicHandy-Mobile/blob/3abba0d1798eaa4f9b96282d3bd6e95622b16456/transport/src/main/kotlin/com/magichandy/mobile/transport/providers/CompatibleProvider.kt#L257), [:278](https://github.com/MagicHandy/MagicHandy-Mobile/blob/3abba0d1798eaa4f9b96282d3bd6e95622b16456/transport/src/main/kotlin/com/magichandy/mobile/transport/providers/CompatibleProvider.kt#L278));</li><li>moderation refusals, which arrive as 403 and should take the refusal path, so a local server can retry them;</li><li>finding 1.</li></ul> |
| 8 | P3 | **A saved connection can't be changed.** Models offers only Remove ([ModelsScreen.kt:164](https://github.com/MagicHandy/MagicHandy-Mobile/blob/3abba0d1798eaa4f9b96282d3bd6e95622b16456/app/src/main/kotlin/com/magichandy/mobile/ui/models/ModelsScreen.kt#L164)). To change the model, the reasoning or the level, you remove the connection and paste the key again. |
| 9 | P3 | **Every connection is called "OpenRouter".** The name is fixed ([ModelAccounts.kt:113](https://github.com/MagicHandy/MagicHandy-Mobile/blob/3abba0d1798eaa4f9b96282d3bd6e95622b16456/core/src/main/kotlin/com/magichandy/mobile/core/session/ModelAccounts.kt#L113)). The short name is the ID after the slash, such as `deepseek-v4.1-flash-1017` ([:116](https://github.com/MagicHandy/MagicHandy-Mobile/blob/3abba0d1798eaa4f9b96282d3bd6e95622b16456/core/src/main/kotlin/com/magichandy/mobile/core/session/ModelAccounts.kt#L116)). The Chat role row shows only the name ([ModelsScreen.kt:67](https://github.com/MagicHandy/MagicHandy-Mobile/blob/3abba0d1798eaa4f9b96282d3bd6e95622b16456/app/src/main/kotlin/com/magichandy/mobile/ui/models/ModelsScreen.kt#L67)), so two OpenRouter connections look identical there. |
| 10 | P3 | **The room for reasoning is fixed.** With reasoning on, the reply allowance grows by 8,192 tokens or a quarter of the context, whichever is smaller ([CompatibleProvider.kt:123](https://github.com/MagicHandy/MagicHandy-Mobile/blob/3abba0d1798eaa4f9b96282d3bd6e95622b16456/transport/src/main/kotlin/com/magichandy/mobile/transport/providers/CompatibleProvider.kt#L123)). The desktop sizes it by effort: 2,048 for minimal, 6,144 for low and 8,192 for medium and above. This matters once levels exist. |
| 11 | P3 | **Every connection downloads the whole catalog.** That's about 0.9 MB for 477 models, every 15 minutes, to read one row. Search needs the catalog too, so one shared cache should serve both. |
| 12 | P3 | **Spending is invisible.** With an ordinary key, OpenRouter reports the key's limit and usage through `GET /api/v1/key`. It also reports each reply's cost. The app shows none of this, so a 402 comes without an explanation. |

**Worth keeping:**

- no attribution headers: `HTTP-Referer` and `X-OpenRouter-Title` would make the app's usage public in OpenRouter's rankings;
- private routing: `zdr`, denied data collection, and `require_parameters`;
- `transforms: []`, so prompts are never compressed;
- no redirects or automatic retries, a 90 s limit per call, and the key kept in the vault;
- a strict JSON schema whenever the model supports structured outputs.

## 3. Compared with the desktop

| | Desktop | Mobile 0.1.8 | Proposed |
|---|---|---|---|
| Key | Pasted; Connect provider then lists models | Pasted, unchecked | Sign in with OpenRouter, or paste a key; either way it's checked first |
| Model | Catalog list, with search when it has more than 20, or an ID typed in | ID typed in | A search page with suggestions, filters and prices; an ID can still be typed in |
| Reasoning | Provider default, Off, Enabled, and each listed level | On or off | Off, when allowed, and each listed level, with the model's default marked |
| Room for reasoning | By effort | Fixed | By effort |
| Host order | Fastest output, fastest first token, lowest price or balanced | Balanced | Fastest, Cheapest or Balanced |
| Fallbacks, allowed hosts, data collection, zero retention | Settings | Fixed: off, any, deny, on | Fixed. Fallbacks are an open question (§8) |
| Context and history | 64K to 256K, and a history size | 64K | Unchanged. 128K is an open question (§8) |
| Check model | Yes | No (LAN servers have Test) | Yes |
| Editing after saving | Yes | Remove only | Everything, without pasting the key again |
| Spending | No | No | Limit, what's left and this month's use |

## 4. Proposed design

### 4.1 Connect (V-OpenRouterConnect)

- **Sign in with OpenRouter** comes first. It uses OpenRouter's OAuth PKCE flow (S256):
  1. A Custom Tab opens `openrouter.ai/auth`. Its `callback_url` is the loopback receiver that ChatGPT sign-in already uses ([LoopbackBrowser.kt](../app/src/main/kotlin/com/magichandy/mobile/platform/LoopbackBrowser.kt)), and its `key_label` is "MagicHandy".
  2. The user approves a key there and can give it a spending limit. The app can't set the limit itself.
  3. The app exchanges the code at `POST /api/v1/auth/keys` and stores the key in the vault. Codes expire after 10 minutes and work once.
- **Or paste a key.** Check calls `GET /api/v1/key`, which uses no credits. A 401 reads "OpenRouter didn't accept this key."
- **Both lead to the model page.** From Models › Add a provider, it's the same page without the setup progress.
- **Two lines at the foot:** the key is stored encrypted and sent only to OpenRouter, and only private hosts are used.

### 4.2 Choose a model (V-OpenRouterModels)

- **Source:** `GET /api/v1/models/user?supported_parameters=structured_outputs&zdr=true&sort=most-popular`, sent with the key so the account's own privacy settings and guardrails apply. It's cached for 15 minutes and searched on the phone by name and ID.
- **Only models that pass both filters are listed:** they can reply in MagicHandy's format, on a private host. That's 302 models today.
- **Suggested for motion:** a short list of IDs shipped with the app, shown when the catalog has them:
  - DeepSeek V4.1 Flash 1017: desktop tests through OpenRouter found it quick and cheap, best with reasoning off;
  - GPT-6.1 Terra: the most reliable motion in desktop tests, but slower. Those tests went through ChatGPT sign-in, not OpenRouter.
- **Sort:** Popular, Lowest price, Newest or Fastest. OpenRouter sorts.
- **Filters:** Reasoning can be off, and Free.
- **Rows:**
  - the name without its author prefix, so "DeepSeek: DeepSeek V4.1 Flash 1017" becomes "DeepSeek V4.1 Flash 1017";
  - the ID;
  - tags: Reasoning optional, Always reasons or Free;
  - a price for about 100 replies of 4K tokens in and 500 out, before reasoning, worked out from the catalog's per-token prices. The desktop measured a median DeepSeek prompt of 3.7K tokens. A "+" marks models that always reason.
- **Enter a model ID** covers models that aren't listed. The ID is checked against the full catalog, and a refusal says what's missing: the MagicHandy format, or a private host.

### 4.3 Reasoning (V-OpenRouterSetup, V-OpenRouterReasoning)

- **The choices come from the catalog's `reasoning` object:**
  - no `reasoning` object: no Reasoning row;
  - `supported_efforts` listed: Off, unless reasoning is `mandatory`, then the listed levels from lowest to highest. `null` means every level;
  - a `reasoning` object without `supported_efforts`: Off and On.
- **New connections start at Off** when the model allows it, and otherwise at its lowest level. The hint or a "Model default" tag names the model's own default.
- **Controls:** four choices or fewer use a segmented control. More use a row that opens a list.
- **What's sent:**
  - Off: `effort: "none"` when "none" is listed or every level is accepted, and `enabled: false` otherwise;
  - a level: `effort` with that level;
  - On: `enabled: true`.
- **Advanced** keeps its rule: it uses the no-reasoning contract when the choice is Off.
- **Room for reasoning** follows the effort, as on desktop (§2, finding 10).
- **From Chat:** Settings › Chat model shows a Reasoning row under an OpenRouter connection, which opens V-OpenRouterReasoning. A change applies from the next message, for both Chat and Autopilot.
- **Existing connections:** off becomes Off. On becomes the model's default level, now shown by name.

### 4.4 Speed (V-OpenRouterSetup)

- **Fastest:** `provider.sort: "throughput"`. New connections start here.
- **Cheapest:** `"price"`.
- **Balanced:** no sort. This is what 0.1.8 does.
- **Not offered:** fastest first token (`"latency"`). Motion waits for the whole reply, so the first token's timing matters less.

### 4.5 A saved connection (V-OpenRouterSettings)

- **Getting there:** each row in Models opens this page, and Remove moves into it.
- **Header:** the title is the model's name, followed by a status line.
- **Replies:** Model, Reasoning, Capability and Speed each open their choices. Check model sends a short text-only request and shows the round trip. Nothing moves, as with Add a server's Test.
- **Spending:** read from `GET /api/v1/key` when the page opens:
  - Left this month: `limit_remaining` of `limit`;
  - Used this month: `usage_monthly`;
  - a key without a limit shows "No limit on this key";
  - a free model shows "Free requests today", from `free_model_daily_requests`;
  - a link opens the key's page on openrouter.ai.
- **Key:** Replace key keeps every other setting. Remove this connection deletes it and its key.
- **Names:** the connection is named after the model. The short name in the Chat header is the same name, shortened with an ellipsis.

### 4.6 Failures (V-OpenRouterProblem)

Each failure names its cause and offers one next step. Connection rows in Models also show the last failure, such as "Key rejected".

| What happened | Bubble says | Next step |
|---|---|---|
| 401 | OpenRouter didn't accept the key | Replace key |
| 402 | No credits left, or the key's spending limit is used up. `error.metadata.limit_source` tells which | Open OpenRouter |
| 429 on a free model | The day's free requests are used up | Choose a model |
| Other 429 | OpenRouter is limiting requests. Wait for `Retry-After` | Send again |
| 404 or 503 at routing | No private host can serve this model now | Choose a model |
| Error inside the stream | The host stopped partway through the reply | Send again |
| 403 moderation (`refusal`, `content_policy_violation`) | Handled as a refusal, so the refusal retry still works | Existing refusal path |

### 4.7 Privacy, unchanged

- Requests keep `zdr: true` and `data_collection: "deny"`, and send no attribution headers.
- The key stays encrypted on the phone and goes only to OpenRouter. The spending check uses the same key.
- OpenRouter doesn't list apps that sign in through localhost in its app directory or rankings.

## 5. Requests

- **One catalog per key:** `models/user`, filtered and sorted as in §4.2 and cached for 15 minutes. It serves search, the checks at save time and each request's capabilities, which replaces finding 11's per-connection download.
- **Key operations:** `GET /api/v1/key` for the check and for spending, and `POST /api/v1/auth/keys` for sign-in.
- **Changes to `CompatibleProvider`:**
  - reasoning as in §4.3, with room by effort;
  - `provider.sort` as in §4.4;
  - errors inside the stream mapped through `error.metadata.error_type`;
  - a moderation 403 returned as `Refused`.
- **Cost per reply:** OpenRouter returns `usage.cost` with every reply automatically. This sketch doesn't display it. It could add to the month's figure between checks of the key.

## 6. Contract changes

All are additive to `core.api`, as `AGENTS.md` requires:

```kotlin
/** A reasoning level for OpenRouter's `reasoning.effort`. */
public enum class ReasoningEffort { None, Minimal, Low, Medium, High, ExtraHigh, Max }

/** How OpenRouter picks a host for each reply (`provider.sort`). */
public enum class HostOrder { Fastest, Cheapest, Balanced }

/** What a catalog model accepts. */
public sealed interface ReasoningSupport {
    public data object None : ReasoningSupport
    public data class Optional(val levels: List<ReasoningEffort>, val default: ReasoningEffort?) : ReasoningSupport
    public data class Required(val levels: List<ReasoningEffort>, val default: ReasoningEffort?) : ReasoningSupport
}
```

- **`ModelConnection`** gains `reasoningEffort: ReasoningEffort? = null` and `hostOrder: HostOrder = HostOrder.Balanced`. `isReasoningOn` stays for connections saved before levels existed.
- **`ModelsState`** gains:
  - `modelSearch`: Idle, Loading, Results or Failed with a reason. Each result has its ID, name, context, prices, `ReasoningSupport` and whether it's free;
  - `keyCheck`, for the key being connected;
  - `spending: Map<ConnectionId, KeySpending>`.
- **New intents:**
  - `StartOpenRouterSignIn` and `CheckOpenRouterKey(apiKey: Secret)`;
  - `SearchOpenRouterModels(query, sort, filters)`;
  - `SaveOpenRouterConnection`, which uses the checked key;
  - `UpdateOpenRouter(connection, …)`, `ReplaceOpenRouterKey(connection, apiKey)` and `CheckModel(connection)`.
- **Units:** prices travel as a value class of micro-dollars per million tokens, as plan §5.5 requires.
- **Storage:** saved connections gain the effort, the host order and the model's name, context and reasoning support, so pages can show them offline. That needs a Room migration. It should wait until the batch in progress, which also touches storage, has landed.

## 7. Phases

1. **Fixes, small enough for the next bug-fix release:**
   1. At save, look the model up in the catalog. Refuse a model that's unknown or has no structured outputs or no private host, and turn on its lowest level when it must reason.
   2. Check the key with `GET /api/v1/key` before saving.
   3. Map errors inside the stream, and treat moderation 403s as refusals.
   4. Send `provider.sort: "throughput"`.
2. **Model search, reasoning levels and editing:** V-OpenRouterModels, V-OpenRouterSetup, V-OpenRouterReasoning, and V-OpenRouterSettings without Spending.
3. **Sign-in and spending:** V-OpenRouterConnect, Spending on V-OpenRouterSettings, and the next steps in V-OpenRouterProblem.

**Before building:**

- confirm that OpenRouter accepts a `127.0.0.1` callback, since its documentation names `localhost`;
- confirm that `models/user` accepts the `zdr` and `supported_parameters` filters;
- measure reply time and reasoning tokens at high, extra high and max for DeepSeek V4.1 Flash 1017 and GLM 5.3 Flash, to check that 8,192 tokens is enough room for reasoning.

The owner does these with their own account. Tools and tests use fakes only.

## 8. Decisions

The first version follows the recommendations for 1, 2, 3, 5 and 6. For 4, free models are listed and priced as Free, without a tag or the day's remaining requests.


1. **Private hosts:** keep them fixed (recommended), or add a setting? Fixed hides 111 models that have no zero-retention host.
2. **Host fallbacks:** allow them? Fallback hosts still pass the same privacy filters, and fallbacks make replies more reliable. The desktop leaves them off by default. Recommended: on.
3. **Fastest for existing connections:** phase 1 has no setting, so either every connection gets Fastest, or only new ones do. Recommended: every connection, as the desktop evidence supports it. It can cost a little more.
4. **Free models:** list them? Without $10 of purchased credits they allow 50 requests a day, and Autopilot uses requests too. Recommended: list them, tagged "Free", with the day's remaining requests shown.
5. **Extra high and max:** offer them? They're the slowest levels and may need more room than 8,192 tokens. Recommended: offer them once the measurement above is done.
6. **Context:** add 128K for models that allow it, as the desktop does? Recommended: not yet.

## 9. Not proposed

- **Attribution headers:** they would publish the app's usage.
- **Fallback models** (`models: [...]`): a different model can change how motion behaves.
- **Web search** (`openrouter:web_search`) and image input.
- **Cost in the chat transcript.**
- **Account credits:** `GET /api/v1/credits` needs a management key, which ordinary keys aren't.

## 10. As built

Claude developed the first version in `openrouter-settings`. It is now integrated with the current Android candidate and the Autopilot changes. [Design triage](reviews/2026-11-27-openrouter-review.md) · [Integration review](reviews/2026-11-27-openrouter-autopilot-integration.md)

- **Adding a connection:** the OpenRouter page asks for the key, the model and its reasoning, then Save. It starts at Advanced. Saving checks the key with `GET /api/v1/key`, which also refuses management keys, and checks the model against the catalog. A refusal shows beside Save in OpenRouter's own words.
- **Choosing a model:** the picker loads the public catalog without a key: `GET /api/v1/models?supported_parameters=structured_outputs&zdr=true&sort=most-popular&limit=1000`, cached for 15 minutes. It has:
  - search, which can be cleared;
  - Popular or Lowest price, the second sorted on the phone, and a Reasoning optional filter;
  - the two suggestions, hidden while searching or filtering;
  - a price for 100 replies and a reasoning tag on every row;
  - loading, unreachable and no-match states;
  - Enter a model ID by hand. Saving checks it against the eligible catalog.
- **Reasoning:**
  - the levels a model lists, up to High, with Off first unless the model requires reasoning;
  - a segmented control for up to four choices, and a list for more;
  - a new choice starts at Off, or at the lowest level;
  - the hint names the model's own default;
  - a level the model doesn't offer moves to the nearest one, and an existing connection that delegated effort to the provider keeps its Model default choice.
- **A saved connection:** OpenRouter rows in Models open its page. There the key can be replaced or left empty to keep it, and the model, reasoning and capability can change. Remove asks first.
- **From Chat:** Settings › Chat model shows Reasoning under an OpenRouter chat model, and changes apply from the next message.
- **Requests:**
  - `provider.sort: "throughput"` for every request, with host fallbacks allowed;
  - reasoning as in §4.3, with `exclude: true` while it's on, and room sized by level;
  - capped at the model's context and reply limits;
  - errors sent mid-reply report an unavailable service.
- **Also fixed:**
  - an unreadable saved key asks for the key again;
  - a cancelled save leaves no key behind;
  - hosted connections say Offline instead of asking about Wi-Fi;
  - the key stays a `Secret` until it's sent.
- **Waits:**
  - sign-in through OpenRouter (§4.1);
  - Spending and Check model (§4.5);
  - the levels above High;
  - a Free filter, and reusing a saved key for a second connection;
  - the failure table of §4.6, apart from errors sent mid-reply;
  - Autopilot's endless retries after a failed plan affect every provider, so the integrated Autopilot fix covers every provider.
