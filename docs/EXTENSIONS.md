# Extensions, resources, MCP, and packages

Arrodes resources add instructions, reusable prompts, presentation, and callable Clojure functions without placing configuration inside a repository.

Global resources live under application home (`--home`, then `ARRODES_HOME`, then `~/.arrodes`). Project resources live under the external directory returned by `project.info`. Project settings and executable resources load only for the trusted canonical project root.

## Resource locations

Arrodes discovers:

| Resource | Global default | Project default |
| --- | --- | --- |
| Skills | `HOME/skills/` | `PROJECT_STATE/skills/` |
| Extensions | `HOME/extensions/` | `PROJECT_STATE/extensions/` |
| Prompts | `HOME/prompts/` | `PROJECT_STATE/prompts/` |
| Themes | `HOME/themes/` | `PROJECT_STATE/themes/` |
| Packages | `HOME/packages/` | `PROJECT_STATE/packages/` |

Additional paths come from the `:skills`, `:extensions`, `:prompts`, and `:themes` vectors in effective settings. Global relative paths resolve from `HOME`; project relative paths resolve from `PROJECT_STATE`. A string path and `{:path "..."}` are equivalent. An entry with `:enabled? false` excludes that resource name from lower-precedence discovery.

Name collisions resolve by precedence: global packages and defaults, global configured paths, trusted project packages and defaults, trusted project configured paths, then explicit runtime settings. Use `resource.list` to inspect the effective catalog.

## Skills and prompts

Skills use an Agent Skills Markdown file with frontmatter and optional support files below the skill directory. Inside a session:

```clojure
(skill {:action "catalog"})
(skill {:action "read" :name "review"})
(skill {:action "read" :name "review" :path "checklist.txt"})
```

Support paths must stay inside the skill root, including after symbolic-link resolution.

Prompt files are Markdown templates. Use `prompt` to catalog or render them. The RPC equivalents are `skill.read`, `prompt.render`, and `prompt.run`.

## Extension entry point

Each discovered `.clj` extension evaluates to an initializer function. The function receives a session-attributed API map:

```clojure
(fn [api]
  ((:register-tool! api)
   {:name "add_values"
    :description "Add a vector of integers"
    :parameters {:type "object"
                 :properties {:values {:type "array"
                                       :items {:type "integer"}}}
                 :required ["values"]
                 :additionalProperties false}
    :execution :parallel
    :fn (fn [{:keys [values]}]
          (reduce + values))})

  ((:register-command! api)
   {:name "hello"
    :fn (fn [_arguments] {:message "Hello"})})
  nil)
```

The initializer may return a cleanup function or a map containing `:close`. It may register more cleanup with `:on-close!`. Activation is attributed: a failed initializer or reload withdraws that extension's contributions instead of leaving a partially active extension.

### API groups

- Session context: `:session-id`, `:cwd`, `:home`, `:settings`, `:session`, `:get-session`
- Registration: `:register-tool!`, `:register-hook!`, `:register-command!`, `:register-provider!`
- Presentation: `:register-renderer!`, `:register-ui!`, `:ui!`
- Interaction: `:append-entry!`, `:send-message!`, `:emit!`
- Lifecycle: `:on-close!`

Registered functions receive one argument map. They may return a native value or a structured result with `:value`, `:content`, `:details`, and optional `:error?`. Wrappers apply argument validation, hooks, permissions, effect locking, cancellation, progress, and retention. They remain Clojure functions rather than separate provider-visible tools.

Tool replacement must be explicit: set `:replace? true` to override an existing registered function. Ordinary duplicate names fail. When an override is withdrawn, Arrodes restores the nearest previous implementation owned by another activation.

Hooks have an ID, owner attribution, integer order, and function. Keep request/context hooks deterministic and preserve valid provider message boundaries.

## REPL-defined functions

Ordinary definitions are immediately callable in the live session:

```clojure
(defn add-values [{:keys [values]}]
  (reduce + values))

(add-values {:values [20 22]})
```

Registration adds discovery metadata and invocation tracing when needed:

```clojure
(register-tool!
 #'add-values
 {:name "add_values"
  :parameters {:type "object"
               :properties {:values {:type "array"
                                     :items {:type "integer"}}
               :required ["values"]}})
```

Definitions and arbitrary objects are live-only. Join any futures before returning; unsupervised background work is trusted local code and is not a durable session operation.

## Presentation and UI requests

A renderer descriptor has a `:name` matching an event type or capability name and a pure `:fn` accepting the event. The function stays in the core; its bounded text is attached to the event as advisory presentation. Renderer errors do not replace the canonical operation status. Replay may render a recorded event again, so a renderer must not perform effects.

`:register-ui!` accepts a named `:widget` or `:set-widget` descriptor with text `:content` and placement such as `:status`, `:header`, or `:footer`. Widgets belong to the session and disappear on teardown. `:ui!` also supports portable `:render` and `:editor` requests.

## MCP clients

Configure external servers in effective settings:

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

Use `${VARIABLE}` references rather than embedding credentials. Project MCP settings require trust.

Connections are lazy and session-owned. Arrodes acts as the client:

```clojure
(mcp {:action "catalog"})
(mcp {:action "describe" :server "local" :name "add"})
(def response
  (mcp {:action "call"
        :server "local"
        :name "add"
        :arguments {:a 20 :b 22}}))
(:structuredContent response)
```

Other actions are `status`, `resources`, `read-resource`, `prompts`, `get-prompt`, and `reconnect`. Remote tools remain behind the `mcp` function; they do not become a provider-visible tool list. Reload and close disconnect owned clients.

## Packages

A package is a directory or archive with an `arrodes.edn` manifest:

```clojure
{:name "example-tools"
 :version "1.0.0"
 :extensions ["extension.clj"]
 :skills ["skills"]
 :prompts ["prompts"]
 :themes ["themes"]}
```

Manifest paths must remain within the package. Package trees may not contain symbolic links.

The RPC package methods accept global or project scope:

```json
{"type":"request","id":"install","method":"package.install","params":{"source":"/path/to/example-tools","scope":"global"}}
```

Supported sources are:

- a local directory, resolved from the project working directory;
- a Git URL, optionally pinned with `#REF` or a `ref` parameter;
- `mvn:group/artifact:version`, optionally with a repository URL.

Use `package.list`, `package.update`, and `package.remove` to manage installations. Updates retain pinned Git refs and Maven versions. Installation is staged and atomic; it will not overwrite an unindexed destination, follow package symbolic links, or execute lifecycle scripts. Git URLs containing credentials are rejected—use SSH or an external credential helper.

Extensions and package code are trusted local code. Trust and owner attribution control loading and cleanup; they do not limit operating-system permissions or reverse arbitrary external effects.
