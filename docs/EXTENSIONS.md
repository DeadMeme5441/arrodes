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

Commands receive their argument value. Tools receive one argument map and return a native value or a structured result with `:value`, `:content`, `:details`, and optional `:error?`. Provider and evaluator invocation share validation, hooks, locking, cancellation, progress, and normalization.

Hook descriptors have an ID, owner attribution, integer order, and function. Ordering is deterministic. Context and request hooks must preserve valid provider messages and stable prefixes when no semantic change is needed.

Deliberate replacement/restoration of built-in capabilities remains an open integration item. Do not assume replacement is supported merely because registration is supported; consult [STATUS.md](STATUS.md).

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

Registration captures the implementation and installs a shared-pipeline wrapper. Definitions are otherwise private to the evaluator. A live namespace is not restored after process restart; durable serializable result values are a separate facility.

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
