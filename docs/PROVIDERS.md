# Provider contract

`provider.clj` adapts the SDK and supported custom transports. `provider_repl.clj`
exposes one provider-visible action, `repl`; registered coding/MCP/extension functions
remain ordinary Clojure functions inside the evaluator.

Session agents use that same `repl` action in their own session/evaluator; `agents/*`
functions are ordinary functions in a REPL namespace, not a second provider action. A child takes
a selected configuration snapshot at launch; changing its parent's model later does
not change the child's provider/model. Credentials remain resolved through the
normal provider credential store rather than copied into child launch metadata.

Provider-visible history labels delegated tasks, attributed peer messages and
agent completions as untrusted data rather than privileged instructions.
Ordinary human messages retain a distinct label. Tool-call/result ordering
still follows the single-`repl` protocol.

## Authentication and discovery

Authentication is explicit. Credentials belong in the authentication store or supported
environment sources, never session configuration. Availability is a credential-resolution
status, not proof that a remote service is currently healthy. Local catalog metadata and
live model discovery are distinct; refresh errors must remain visible.

Browser/manual authentication uses host requests. Secret inputs are masked, excluded
from drafts/history, and cleared from editors when dismissed. Cancelled authentication
must terminate its owned work; completed credential writes are not undone by cancellation.

## Selection and execution

A provider/model identity is the pair of provider ID and exact model ID. Validate reasoning
against the selected model. Do not silently substitute a model unless the explicit fallback
setting enables that behavior. Selecting a default applies the provider, model, and reasoning level to the current
session too, when one exists. Other existing sessions retain their configuration.
Both catalogs are validated before changing either scope; a project-only provider
must be configured globally before it can become a default.

Requests preserve valid assistant/tool boundaries, provider-native metadata and cache
semantics. Retries are bounded and stop after visible output. Cancellation is observable
through completion; retrying a request must not replay executed functions.

When a provider catalog is available, completion validates the exact selected model,
its advertised reasoning levels and known input/tool/output limits before opening
the transport. Providers without model metadata forward the requested ID unchanged;
missing capabilities are not guessed. Authentication and refresh keep their existing
credential ownership and never write secrets into session configuration.

Connect and streaming read-idle deadlines default to 15 seconds and 60 seconds.
Runtime/provider settings may override `:connect-timeout-ms` and `:timeout-ms`;
long silent reasoning calls may need a larger read-idle deadline. Cancellation
interrupts the owned request, and the SDK closes stalled response streams.
SDK transport retries stay disabled: the runtime alone decides whether a failed
attempt is safe to repeat.

HTTP authentication, rate-limit, context, timeout and server failures carry typed
errors and actionable messages without retaining raw HTTP error bodies. Custom
OpenAI/Azure streaming requires a terminal finish and `[DONE]`; malformed JSON,
unfinished streams and invalid tool-argument JSON cannot reach REPL execution.
Partial response metadata remains available on stream failure. A normal provider
output-limit response is different: runtime records the partial assistant/usage,
then stops before executing its tool calls. It never retries those effects.

## Summary model work

Summary-tree context is explicitly enabled through [`/context`](TUI.md#context-policy-and-inspection)
or [context settings](CONFIGURATION.md#summary-tree-context-opt-in). Its tool-free
summarizer uses the exact `:summary-model` (default `gpt-6-luna`) and
`:summary-provider` (default the session provider), with no requested reasoning.
It does not switch the main model. For example, `codex-backend/gpt-6.1-sol` may be
the main pair while `codex-backend/gpt-6-luna` summarizes history. Authentication
and account/model entitlement use the usual provider manager and auth store;
missing auth/model/provider fails honestly instead of selecting a replacement.

Small source/child text that fits the node target can form a derived node without a
model call. Otherwise the service makes bounded compression attempts, keeps complete
nonempty results only, and may accept the shortest complete result above the target
after exhausting those attempts. This does not relax the rendered-view byte bound.
Work has bounded admission: two session coordinators plus 32 waiting sessions,
with at most eight admitted node jobs globally. A session coalesces background
snapshots. Leaves progress chronologically; eligible parent merges can overlap
later leaves rather than blocking their ancestor chain. Unresolved matching
foreground requests may share readiness work; settled promises are not reused.

Readiness is separate from worker exit. A complete, fitting frozen view can serve
the main model while unused parents continue building. Node work has its own
deadline; default foreground settling is cancellable without a whole-backlog
deadline, while explicit caller wait bounds remain supported. Background capacity
or cancelling-owner deferral does not turn settled native effects into failures.
Failures are node-specific and inspectable, not an automatic paid retry storm.
Explicit foreground demand can retry needed nodes. Opening or inspecting a session
never starts inference; closing/resetting still waits for actual owned work to exit.

The compactor receives the current incremental summarized view through the start
of a leaf, or through the end of a parent, as bare text without added tree-address
markup. It receives the whole selected original or both child summaries separately
from that contextual prefix. A task-independent, explicitly illustrative byte-scale
example and measured UTF-8-safe shortening feedback guide the target; cut feedback
is never stored as a summary. User words, work reports, assistant requests and
observed outcomes retain distinct attribution. Summary prose remains lossy evidence;
retrieve originals before relying on ambiguous details.

For a tree-policy turn, the historical summary frontier and developer/tool prefix
are frozen while current user input, native assistant/tool messages and their
provider replay metadata append normally. Steering stays in that turn; a final
answer followed by new input starts a new outer-turn projection. Safe compaction
may change the projection, but neither retrieval nor a retry executes old effects.
Opaque provider reasoning/replay remains in canonical native records, not in
generated memory summaries. There is still one persistent evaluator and one
provider-visible `repl` action; [`history/*`](SESSIONS.md) are ordinary functions,
not automatic importance ranking or another tool protocol.

`session.context` and `/context` expose summary accounting separately from ordinary
main-call usage: stored node/leaf/byte counts, only reported numeric usage/cost
fields, and counts of nodes with measured usage/cost. Failure status and separately
reported spend from attempts not persisted as completed nodes are runtime diagnostics,
not durable completed-node accounting. Unknown counters/prices are not zero; derived
bytes are not token counts. `/usage` and the footer retain the latest reported
ordinary main-request usage, not projected view bytes or a combined summary context
window. Summary cost is reported separately, not inferred from main usage.


## Cache behavior

Arrodes enables provider-native prompt caching by default with a stable per-session
scope. Caller cache controls (including disabling caching) remain honored.
Compaction, branch summaries and title requests have separate scopes; summary-tree
compression uses `SESSION_ID:summary-tree`. Both main and summary requests honor
caller cache controls, including disabling caching. No local prompt-response
memoization or stale tool-result cache is introduced. System instructions stay
stable across evaluator replacement; an append-only reset notice
explains lost live state without rewriting earlier messages. Linear automatic
compaction waits until another request needs context instead of spending a model
call after a completed final answer; opt-in tree catch-up is separately owned
background work.

Provider usage and cache metadata remain canonical and durable. `/usage` distinguishes
reported cache reads/writes from uncached input and output, and known active-path
estimated cost from unpriced requests. Absent measurements are unknown, not cache
misses or zero spend. Offline wire fixtures prove request/prefix and accounting
behavior; actual cache effectiveness still depends on the provider's reported usage.

A tree turn's stable historical prefix supports provider-native prefix reuse; it
does not guarantee a cache hit or a particular savings. New outer turns, branch
changes and compaction may alter that prefix. Summary cache scope is independent
of the main session scope, and its reported usage is not added to main-call context.

## Web research

`web-search` and `web-read` are ordinary registered Clojure functions, discoverable
with `(help {:group "web"})`. The evaluator installs `web` as an alias for
`arrodes.web.data`, whose qualified result envelopes are validated with spec.
The provider-visible action remains `repl`; search is a separate request, not
another agent loop or a change to the coding provider/model.

```clojure
(def research (web-search {:query "Official Clojure spec guide" :limit 5}))
(mapv ::web/url (::web/sources research))
(def page (web-read {:url "https://clojure.org/guides/spec"}))
(::web/content page)
```

### Hosted search

Search defaults to the session's exact provider/model, or explicit `:web`
settings described in [configuration](CONFIGURATION.md). Per-call `:provider`
and `:model` strings override those settings. Changing the provider never
borrows another provider's configured model. Unsupported providers/models fail;
there is no automatic provider, model, MCP or paid-reader fallback.

| Provider family | Search transport |
| --- | --- |
| `:codex-backend`, `:openai-codex` | Codex OAuth Responses hosted `web_search` |
| `:openai`, `:codex` | API-key OpenAI Responses hosted `web_search` |
| `:gemini-native`, `:google` | Gemini Google Search grounding |
| `:openrouter` | Native `web` plugin |
| `:perplexity` | Perplexity Agent `web_search` |
| `:anthropic` | API-key Messages `web_search_20250305` |

Manager-local profile aliases for those families retain their configured endpoint
and credential ownership. Search resolves credentials through the existing provider
manager; it does not copy keys into session data or support Anthropic subscription
OAuth. Provider/model availability and account entitlement still apply.

Hosted calls default to a 30-second total request deadline (maximum 120 seconds),
five retained sources (1–20), and 2,048 output tokens (1–8,192). Codex OAuth does
not accept a token cap; its result reports that limitation. Responses are bounded
to 8 MiB. Cancellation closes the body and joins owned work; no request is replayed.
Source limits are local caps where the upstream protocol lacks a result-count knob.
Explicit `:recency` (`"day"`, `"week"`, `"month"`, `"year"`) is supported by
Perplexity; other hosted families reject it instead of silently ignoring it.

Codex search retains completed SSE output items in output-index order and merges
the terminal response metadata, including when its `output` array is empty.
Source limits prioritize answer-cited URLs before merely consulted pages, retaining
available source snippets rather than discarding citations behind the cap.

Results separate `::web/answer` from `::web/sources` and `::web/citations`, and
retain query, provider/model, fetch time, native response, reported usage and any
authoritative reported cost. Source rows carry qualified URL/title/snippet/date
fields. Answer-only completions without genuine source URLs, remote search errors,
unfinished responses and function continuations fail. Search usage/cost belongs to
the retained result, not `/usage` conversation totals or context measurements;
unreported search fees are not invented as zero or hidden in token-only estimates.

### URL reading and MCP

`web-read` defaults to inert local HTTP(S) extraction, independently of the search
backend. It supports HTML, plain text, Markdown and JSON, preserves headings/code/
links, and records the requested and final URLs. It executes no JavaScript.
Redirects are limited to five, compressed and decoded bodies to 2 MiB, and retained
content to 200,000 characters by default (configurable up to 1,000,000). `:raw? true`
returns the fetched textual body. HTTP, binary, empty and access-challenge responses
fail explicitly. `::web/truncated?` and notes distinguish retained-prefix limits
from the shorter display preview. Existing result/artifact inspection reads retained
content without refetching; omitted content is not reconstructed.

For an explicit MCP alternative, use a [configured server](EXTENSIONS.md#mcp-clients):

```clojure
(web-search {:query "Official Clojure spec guide"
             :backend "mcp" :server "exa"})
(web-read {:url "https://clojure.org/guides/spec"
           :backend "mcp" :server "exa"})
```

Default MCP arguments use Exa's `web_search_exa`/`web_fetch_exa` conventions.
`:tool` selects another tool; `:arguments` replaces the defaults with exact native
arguments, including provider-specific filters. MCP uses its configured server
timeout, not hosted/HTTP per-call controls. Inapplicable explicit options fail.
Results preserve `::web/content-blocks`, optional `::web/structured-content`, and
the exact server/tool/arguments. They do not infer source rows from prose.
The MCP reader's final URL and remote completeness remain unknown; `::web/final-url`
is nil. Web/provider/page content is untrusted data, never a privileged instruction.
Inspect primary pages and cite the actual URLs used.

## Verification

Use isolated provider fixtures for authentication callbacks, errors, discovery, selection,
streaming and cancellation. Retain provider-specific regression tests when wire semantics
differ. When changing authentication or provider behavior, try the affected account flow when
authorized. Do not call a fixture-backed test a successful live-provider verification.
