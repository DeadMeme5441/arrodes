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
- Navigation, settings, input, confirmation, and detail views are full terminal screens
  with a title, Back action, and keyboard hints. They have no floating dialog frame or
  dimmed backdrop. Slash suggestions remain inline. Inspection uses a full-width screen.
  Secret editor contents never enter draft state or a rendered unmasked field.
- The header shows only the session name. Providers, sessions, and commands remain
  available through slash commands and keyboard shortcuts.
- The transcript uses the full available width. User turns have a subtle shaded surface,
  left rule, and You label. Assistant prose has an Arrodes label and clear turn spacing.
  Reasoning and function activity are indented and muted. Code blocks use a distinct
  surface and language label so they are visibly separate from prose.
  Both the chevron and heading toggle reasoning/execution blocks. Expanded activity
  shows highlighted Clojure source and output. Large output stays bounded
  and inspectable, and native values remain available through the inspector.
- The welcome screen offers recent sessions and a compact starting prompt. The same
  multiline editor stays anchored at the terminal bottom, with two quiet metadata rows directly below
  it. Opening or closing suggestions preserves its position, draft, cursor, and selection.
- Slash commands expand upward above the composer, with name/alias/description search and
  name-prefix ranking. F3 and Ctrl+P open the same inline list. Escape dismisses it
  without discarding the draft. Provider/model controls can open a dedicated browser.
- Clicking the composer synchronizes native and application focus. Typing after mouse
  reading returns to the editor without losing the first character. F6 explicitly enters
  keyboard navigation, where transcript/inspector letter shortcuts remain available.
- Effort and apply controls remain in the model listing screen: beside the list on wide
  terminals, below it on narrow terminals. The selected effort is explicit; it is not
  a second model-selection page. Tab moves through providers, model search/list, effort,
  and apply actions; Shift+Tab reverses this order. Up/down selects models or actions;
  left/right moves the search cursor or changes effort within its range, then crosses
  to the adjacent column at an edge. Outer columns do not wrap. Modified arrows and
  search selections retain their normal editing behavior. Enter on a model focuses
  effort; Enter on an apply action saves. Escape from controls returns to model search.
  A provider selection shows only its models,
  with cached results, provider-specific discovery, and local error/connection status.
- Below the composer, model and effort are primary, the provider uses a short muted
  label, and last-reported context tokens/capacity/percentage sit on the right. Missing
  usage is shown as unknown, never assumed to be zero. Project and branch occupy a
  separate muted row. Idle status and permanent keyboard hints do not crowd the footer.
- Routine confirmations appear briefly in the footer and expire after 3.5 seconds.
  Errors and unknown outcomes remain visible; Details appears only for diagnostic data.
