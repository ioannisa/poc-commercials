# In-app AI assistant

A chat inside the application that answers questions about schedules, breaks,
spots, contracts and customers in natural language. **Phase 1 is read-only**:
the model can look things up but cannot modify anything (mutations with
in-chat confirmation are a follow-up).

The chat is a **companion**: on Desktop it opens as a separate, resizable,
**always-on-top OS window** (the main window keeps its full width - a small
display never squeezes the schedule, and the chat can live on a second
monitor); on the Web it docks as a drag-resizable side panel whose width is
capped so the main content never drops below its working minimum. Either way
the schedule stays visible and live beside it, and the conversation survives
closing/reopening the companion and navigating. It is toggled by the **sparkles
button** in the main-screen toolbar (next to the Preferences gear) or opened
from Preferences → Assistant - both visible ONLY when the server has at least
one provider key. **Enter sends** (Shift+Enter inserts a newline). Assistant
answers render as **GitHub-flavoured Markdown** (tables included) and every
bubble is **selectable/copyable**. The chat is **pinned to the application's
active station**: every client request already carries `?station=<selected>`,
so the model scopes every answer to it, never asks "which station?", and
politely refuses questions about other stations (switch station in the app
instead). The prompt also carries the server's current date AND time, so
"next break" resolves against now.

## How it works

```
app (Compose chat) ──► POST /api/ai/chat (our server, user's bearer)
                          │ agentic loop against the configured LLM provider
                          │ tools = the MCP registry's READ tools, in-process,
                          │         executed AS the logged-in user (grants apply)
                          ▼
                       final answer + the tool-call trail
```

- The provider **API keys live only in server.yaml** — clients never see them;
  the server proxies every chat. The chat screen offers a provider dropdown
  (and a model dropdown per provider) built from the catalog the server sends
  at login; the server validates every request's provider/model against
  server.yaml, so a client can never coax it into calling an unlisted model.
- The model calls the same tools the MCP connectors use (minus the PDF report
  tools and every mutating tool), through the same per-user `McpCaller`
  scoping — the assistant sees exactly what the user's grants allow.
- The reply carries the tool names it used; the client shows them under the
  answer. Requests are per-IP rate limited (10/min) and capped at 40 turns.
- This is separate from the MCP connectors (docs/MCP.md): connectors let users
  bring their OWN AI subscription (claude.ai, ChatGPT, Gemini) at zero cost to
  the operator; the in-app chat is operator-paid per token and works for users
  with no AI subscription at all.

## Configuration (server.yaml)

One optional block per provider — every keyed provider becomes a dropdown
option in the client:

```yaml
ai:
  provider: anthropic        # optional: the DEFAULT (preselected) provider;
                             # otherwise the first configured of
                             # anthropic / openai / gemini
  anthropic:
    apiKey: "sk-ant-..."
    models: [claude-sonnet-5, claude-haiku-4-5, claude-opus-4-8]   # optional
  openai:
    apiKey: "sk-..."
    models: [gpt-5.6-sol, gpt-5.6-terra, gpt-5.6-luna]   # REQUIRED when keyed
  gemini:
    apiKey: "AIza..."
    models: [gemini-flash-latest, gemini-pro-latest]  # REQUIRED when keyed
  maxTokens: 8192            # optional per-response output cap (all providers)
```

- Each `models` list is that provider's model dropdown; the FIRST entry is its
  default. `models` may be omitted only for `anthropic` (falls back to the
  trio above) — for openai/gemini a keyed block without models refuses to boot
  (their catalogs move too fast for a baked-in default).
- **Two keys → two dropdown options; one key → the dropdown is a fixed label
  (no expansion); zero keys → the feature is invisible** — the login response
  carries an empty catalog and the client hides the Preferences entry. The
  catalog also rides the session keep-alive, so a running client tracks a
  server.yaml change without re-login.
- `provider:` naming a provider without a key is a boot error, as is an
  unknown provider/model in a chat request (400) — the operator's catalog is a
  hard whitelist, which is what keeps the token bill under the operator's
  control.

| Provider | API key from | Model examples |
|---|---|---|
| `anthropic` | platform.claude.com (Console → API keys) | `claude-sonnet-5` (default), `claude-haiku-4-5` (cheapest), `claude-opus-4-8` (strongest) |
| `openai` | platform.openai.com | `gpt-5.5`, `gpt-5.6-sol` / `gpt-5.6-terra` / `gpt-5.6-luna` — see below for the current catalog |
| `gemini` | aistudio.google.com | `gemini-pro-latest`, `gemini-flash-latest` — see below for the current catalog |

### Getting an API key

All three consoles follow the same shape: create an account, load a little
prepaid credit, create a key, copy it **immediately** — it is shown in full
only once; afterwards the console shows only a masked stub. Note that the API
is billed separately from any chat subscription: a Claude Pro / ChatGPT Plus /
Gemini subscription does NOT cover API calls.

**Anthropic** (key starts with `sk-ant-`)

1. Go to [platform.claude.com](https://platform.claude.com) (the Claude
   Console — NOT claude.ai, which is the chat product) and sign up / log in.
2. Buy prepaid credits first — without credits every API call is refused.
   Open **Settings** (gear icon in the left sidebar's bottom section /
   account menu) → **Billing**, or go directly to
   [platform.claude.com/settings/billing](https://platform.claude.com/settings/billing),
   and buy credits (minimum ~$5).
3. Create the key: **Settings → API Keys**, or directly
   [platform.claude.com/settings/keys](https://platform.claude.com/settings/keys)
   → **Create Key** → give it a name (e.g. `commercials-server`) and pick an
   expiration → the full key is shown ONCE in a dialog with a Copy button.
4. Optional: try it in the browser first at
   [platform.claude.com/workbench](https://platform.claude.com/workbench).

**OpenAI** (key starts with `sk-`; project-scoped keys `sk-proj-` — both work)

1. Go to [platform.openai.com](https://platform.openai.com) (the developer
   platform — NOT chatgpt.com) and sign up / log in.
2. Billing first: open **Settings** (gear icon, top-right) → **Billing**, or
   directly
   [platform.openai.com/settings/organization/billing/overview](https://platform.openai.com/settings/organization/billing/overview)
   → **Add payment details**, then **Add to credit balance** (prepaid, min $5).
3. Create the key: same Settings area → **API keys**, or directly
   [platform.openai.com/api-keys](https://platform.openai.com/api-keys) →
   **+ Create new secret key** → name it, leave the default project and
   "All" permissions → **Create** → the full key appears ONCE with a Copy
   button.

**Gemini / Google** (key starts with `AIza`)

1. Go to [aistudio.google.com](https://aistudio.google.com) (Google AI
   Studio) and sign in with any Google account — no card needed to start.
2. Open the **API Keys** page: **Dashboard** in the left panel → **API
   Keys**, or directly
   [aistudio.google.com/apikey](https://aistudio.google.com/apikey).
3. Click **Create API key** — AI Studio creates (or lets you pick) the
   backing Google Cloud project by itself; the key appears in a dialog with a
   Copy button.
4. The key works immediately on a rate-limited **free tier** — enough to try
   the assistant. For real use, enable billing on that Cloud project (the
   API Keys page links to it per key).

Sanity-check a fresh key with the matching list-models call below — it is the
cheapest possible request and doubles as a key test. Then put the key in the
**server's** server.yaml only: it never belongs in a client build, and keep
the repository copy of server.yaml commented out (the file is tracked — a
pushed key is a leaked key). If a key does leak, revoke it on the same
console page and mint a new one; editing server.yaml + restart is the whole
rotation.

Cost control: prepaid credit IS the hard ceiling on Anthropic/OpenAI, and all
three consoles offer budget alerts. Start small — a tool-using conversation
costs a few cents, so $5 of credit covers weeks of testing.

### Finding the exact model ids

What goes into `models` is the provider's **API model id** (the wire id), not
the marketing name — "GPT-5.6 Sol" is the product, `gpt-5.6-sol` is the id.
The server passes your entry to the provider verbatim: a mistyped id boots
fine and only fails at the first chat that picks it, as a 502 carrying the
provider's own "model not found" error. So copy ids, never guess them.

The authoritative source is each provider's **list-models endpoint**, queried
with the same key you put in server.yaml:

```bash
# Anthropic → ids in .data[].id  (e.g. claude-sonnet-5)
curl -s https://api.anthropic.com/v1/models \
  -H "x-api-key: $KEY" -H "anthropic-version: 2023-06-01" | jq -r '.data[].id'

# OpenAI → ids in .data[].id  (e.g. gpt-5.5, gpt-5.6-sol)
curl -s https://api.openai.com/v1/models \
  -H "Authorization: Bearer $KEY" | jq -r '.data[].id'

# Gemini → names in .models[].name, PREFIXED with "models/" - STRIP the prefix
# for server.yaml (models/gemini-pro-latest → gemini-pro-latest). Only entries
# whose supportedGenerationMethods include generateContent are chat models.
curl -s "https://generativelanguage.googleapis.com/v1beta/models" \
  -H "x-goog-api-key: $KEY" \
  | jq -r '.models[] | select(.supportedGenerationMethods | index("generateContent")) | .name' \
  | sed 's|^models/||'
```

The human-readable catalogs (name → id → pricing) live in the provider docs:
Anthropic [platform.claude.com/docs — models overview](https://platform.claude.com/docs),
OpenAI [developers.openai.com/api/docs/models](https://developers.openai.com/api/docs/models),
Gemini [ai.google.dev/gemini-api/docs/models](https://ai.google.dev/gemini-api/docs/models).

Worked example — offering "GPT-5.5 plus the GPT-5.6 family" is:

```yaml
openai:
  apiKey: "sk-..."
  models: [gpt-5.5, gpt-5.6-sol, gpt-5.6-terra, gpt-5.6-luna]
```

(Bare `gpt-5.6` also exists as an alias that routes to Sol; prefer the
explicit ids so the dropdown says what it does. OpenAI/Gemini ids move fast —
re-run the list call when a provider announces new models.)

Cost lives with the operator (per-token API billing; a typical tool-using
conversation costs a few cents). The Anthropic path uses the official Java
SDK; OpenAI/Gemini go over their public HTTP APIs.

## Verification status

- Full E2E without real keys, all three catalog sizes: **0 providers** → no
  `aiChat` in the login response + route unmounted (404); **1 provider** →
  single catalog entry (anthropic with the default model trio); **2
  providers** → both entries, the yaml `provider:` first. Per-request routing
  proven with fake keys: `provider: anthropic` reached Anthropic (their own
  401 with a request id came back as our 502), the no-provider default reached
  Google. Unknown provider and unlisted model each return 400 with the allowed
  list; a keyed openai/gemini block without `models`, or `provider:` naming a
  keyless provider, refuses to boot with a pointed message.
- **Live model loop: VERIFIED on Gemini** (free-tier key, `gemini-flash-latest`):
  a schedule question ran a 2-round tool loop (`list_breaks` → `spots_in_break`)
  and returned the real 17/07/2026 16:00 Crete TV break, Greek answer with a
  table; a deletion request was politely refused (read-only) while a 6-round
  loop proactively located the spots for manual removal. Provider/model
  selection, default routing and 502 error transport (Anthropic billing
  message, Google rate-limit message) all observed live. Note: Gemini's free
  tier allows ~5 requests/min on flash and one tool-looping chat costs several
  — expect 429-as-502 under quick successive chats until billing is enabled.
- **Live model loop: VERIFIED on OpenAI** (`gpt-5.6-luna` explicit pick and
  `gpt-5.5` via the provider's default-model path) — same real break data,
  Greek tables, 1-2 tool rounds, ~8s. Found live and fixed: GPT-5.6 models
  reject function tools alongside their default-on reasoning at
  `/v1/chat/completions`, so the adapter was migrated to the **Responses API**
  (`/v1/responses`) — stateful rounds via `previous_response_id`, only
  `function_call_output` items are resent, reasoning survives between rounds.
- The Anthropic adapter still has not run live (key valid, account awaiting
  credits). Once credits exist, also re-check that the bare `claude-haiku-4-5`
  alias is accepted — the models list shows only the dated snapshot id.
