# Provider contract

`provider.clj` adapts the SDK and supported custom transports. `provider_repl.clj`
exposes one provider-visible action, `repl`; registered coding/MCP/extension functions
remain ordinary Clojure functions inside the evaluator.

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

## Verification

Use isolated provider fixtures for authentication callbacks, errors, discovery, selection,
streaming and cancellation. Retain provider-specific regression tests when wire semantics
differ. When changing authentication or provider behavior, try the affected account flow when
authorized. Do not call a fixture-backed test a successful live-provider verification.
