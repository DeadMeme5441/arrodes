# TUI design system

The provider browser and conversation share `tui-widgets/colors` and `layout`. Use
black or charcoal surfaces, grey text, muted yellow for focus and links, and semantic
status colors. Selection also has a cursor, label, or boundary. Thin frames mark an
interactive region or expanded execution; prose stays on the base surface.

## Components

- Provider rows show human name, connection state, and authentication method, with
  connected providers first.
- The model browser combines provider navigation, searchable models, and selected-model
  details. Below 76 columns, the sidebar becomes a Change provider action.
- Dialogs contain a title, short instructions, input or choices, and relevant keyboard
  hints. Secret editor contents never enter draft state or a rendered unmasked field.
- The transcript shows user turns, Markdown prose, and compact attributed function activity.
  Expanded activity shows highlighted Clojure source and output. Large output stays bounded
  and inspectable, and native values remain available through the inspector.
- The composer is a stable multiline editor with a focus frame and contextual send or
  steer hints.
- Status shows selected model and effort, project and Git branch when available, operation
  state, and last reported context usage when supplied by the provider.
