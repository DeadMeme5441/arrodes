# Clojure extensions and resources

Global resources live under the selected application home. Project resources live under `.arrodes-mono` in the project. Executable project resources require an applicable trust decision or explicit run trust.

Resource groups include `extensions`, `skills`, `prompts`, `themes`, and `packages`. Settings and keybindings are EDN. Skills use the Agent Skills frontmatter/content convention; prompt templates are expanded on demand.

## Extension entry point

An extension file evaluates to an initialization function. The function receives a session-attributed API map:

```clojure
(fn [api]
  ((:register-tool! api)
   {:name "add_values"
    :description "Add a vector of integers"
    :parameters {:type "object"
                 :properties {:values {:type "array" :items {:type "integer"}}}
                 :required ["values"]
                 :additionalProperties false}
    :execution :parallel
    :fn (fn [{:keys [values]}] (reduce + values))})

  ((:register-command! api)
   {:name "hello"
    :fn (fn [_arguments] {:message "Hello"})})
  nil)
```

An initializer can return a cleanup function or a map with `:close`. It can register additional cleanup through `:on-close!`. Failures must withdraw attributed contributions rather than leave a partially active extension.

## API groups

- Session context: `:session-id`, `:cwd`, `:home`, `:settings`, `:session`, `:get-session`.
- Registration: `:register-tool!`, `:register-hook!`, `:register-command!`, `:register-provider!`.
- Presentation: `:register-renderer!`, `:register-ui!`, `:ui!`.
- Interaction: `:append-entry!`, `:send-message!`, `:emit!`.
- Lifecycle: `:on-close!`.

Commands receive their argument value through an optional host. Registered functions receive one argument map and return a native value or a structured result with `:value`, `:content`, `:details`, and optional `:error?`. Clojure wrappers share validation, hooks, effect locking, cancellation, progress, and normalization. They are not separate provider tools.

Hook descriptors have an ID, owner attribution, integer order, and function. Ordering is deterministic. Context and request hooks must preserve valid provider messages and stable prefixes when no semantic change is needed.

Tool replacement is deliberate: set `:replace? true` on a tool descriptor to override an existing capability. The runtime injects extension ownership and records the exact registration, so activation failure, deactivation, close, and failed reload restore the nearest prior implementation without removing unrelated owners. Omit `:replace?` for ordinary registration; accidental duplicate names still fail.

Presentation registrations work through native hosts and the RPC/OpenTUI bridge. A renderer descriptor has `:name` (an event type such as `"evaluation/completed"` or capability name) and a pure `:fn` accepting an event. The function stays on the JVM; its bounded text is attached to the event for display, and renderer errors do not change the underlying operation result. Replay may invoke a renderer again, so rendering must not perform external effects.

`:register-ui!` accepts a named `:widget` or `:set-widget` descriptor with text `:content` and a placement such as `:status`, `:header`, or `:footer`. Widgets belong to the session and are withdrawn on teardown. `:ui!` also supports portable `:render` and `:editor` requests. Failed teardown retains pending cleanup work; retry close/reload rather than treating an incomplete report as success.

Explicit `{:path "..." :enabled? false}` resource entries override lower-precedence/default discovery before the catalog is filtered. Shared settings transactions are serialized by canonical file identity, including reload rollback. Local package sources are stored canonically, so updating from another working directory cannot silently select another package.

## REPL-defined tools

Inside a live session:

```clojure
(defn add_values [{:keys [values]}] (reduce + values))
(register-tool! #'add_values
               {:name "add_values"
                :parameters {:type "object"
                             :properties {:values {:type "array" :items {:type "integer"}}}
                             :required ["values"]}})
(add_values {:values [20 22]})
```

Registration captures the implementation and installs an instrumented wrapper. It is optional: unregistered `defn` functions are immediately callable and composable in the REPL. A live namespace is not restored after process restart; supported durable result values are a separate facility.

## Skills, prompts, and MCP clients

`skill` supports `{:action "catalog"}` and `{:action "read" :name "name" :path "optional/support/file"}`. `prompt` supports catalog and render. Read/render results are native maps containing `:content` and resource metadata; support paths retain the existing containment checks.

Configure external MCP servers in effective settings:

```clojure
{:mcp/servers
 {:local {:transport :stdio
          :command "/absolute/path/to/server"
          :args []
          :timeout-ms 30000}
  :remote {:transport :streamable-http
           :url "https://mcp.example.test/mcp"
           :headers {"Authorization" "Bearer ${MCP_TOKEN}"}
           :timeout-ms 30000}}}
```

Arrodes is the client. Connections are lazy and session-owned; reload/close disconnect them. Environment/header configuration uses `${VARIABLE}` references rather than embedded credentials. Project settings still require trust.

```clojure
(mcp {:action "catalog"})
(mcp {:action "describe" :server "local" :name "add"})
(def response
  (mcp {:action "call" :server "local" :name "add"
        :arguments {:a 20 :b 22}}))
(:structuredContent response)
```

The gateway returns native MCP result data, including structured content, and propagates remote tool errors. It also supports `status`, `resources`, `read-resource`, `prompts`, `get-prompt`, and `reconnect`. Remote tools stay behind this Clojure function; they do not become provider-visible tool definitions.

## Packages

A package has an `arrodes.edn` manifest, for example:

```clojure
{:name "example-tools"
 :version "1.0.0"
 :extensions ["extension.clj"]
 :skills ["skills"]
 :prompts ["prompts"]
 :themes ["themes"]}
```

The package API supports local directories, Git sources, and Maven resources. Local install/update/remove has behavioral coverage; Git/Maven and supporting-dependency coverage remain on the verification list.

Installations are staged. Unindexed destinations are not owned and must not be replaced. Local sources and their installation destinations cannot contain one another. Pinned references do not silently advance. Installation does not automatically execute lifecycle scripts.

Extensions are trusted local Clojure code. Project trust and attribution are not OS isolation, and cleanup cannot reverse arbitrary external effects.
