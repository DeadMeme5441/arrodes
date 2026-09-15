# TUI design system

The provider browser and conversation share `tui-widgets/colors` and `layout`.
Use black/charcoal surfaces, grey text, muted yellow for focus and links, and semantic
status colors. Selection always also has a cursor, label, or boundary. Thin frames
mark an interactive region or expanded execution; prose stays on the base surface.

## Components

- Provider rows: human name, connection state, authentication method; connected first.
- Model browser: provider navigation, searchable models, selected-model details.
  The sidebar gives way to a Change provider action below 76 columns.
- Dialogs: title, short instructions, input or choices, relevant keyboard hints.
  Secret editor contents never enter draft state or a rendered unmasked field.
- Transcript: user marker, Markdown prose, compact attributed function activity.
  Expand activity to see locally highlighted Clojure source and output; native values
  remain available through the inspector. Large output remains bounded and inspectable.
- Composer: stable multiline editor and focus frame, contextual send/steer hints.
- Status: selected model/effort, project and Git branch when available, operation state,
  and last reported context usage when the provider supplies it. Click to select models.

## Validation and captured frames

From the checkout, run `bun scripts/test-tui.ts` for real JSONL controller tests and
native OpenTUI rendering checks. They include isolated provider fixtures; no real
account credentials or paid completions are needed.

Set `ARRODES_CAPTURE_UI="$PWD/target/ui-preview"` to retain native cell captures
(JSON spans with foreground/background colors, plus plain text) for providers,
models, chat, and narrow layouts. All shown model metadata in these fixtures is
illustrative. `ARRODES_TUI_VISUAL_ONLY=1` runs only renderer checks while iterating;
run the full suite before delivery.
