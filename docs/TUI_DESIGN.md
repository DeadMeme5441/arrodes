# TUI design system

The provider browser and conversation share semantic theme roles and fixed layout
constants. The default uses layered black/charcoal surfaces, silver text, and antique-gold accents inspired by the Fool;
Dracula supplies an alternate palette. Theme packs control colors and text treatments,
while layout and interaction remain fixed. See [Themes](THEMES.md). Selection also has a cursor, label, or boundary. Thin frames mark an
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
  available through slash commands and keyboard shortcuts. A blank row separates
  the title from the main region; another separates that region from the composer.
- The transcript uses the full available width. User turns have a gold diamond and You label; replies have a silver star and Arrodes
  label. Thin role-colored horizontal rules sit above every user and assistant turn across the
  available width. The assistant boundary precedes its first reasoning/tool/prose row;
  subsequent activity and prose share that turn until the next user message.
  Prose remains open on the base surface. Execution artifacts use thin outlines with
  a header/output separator, while reasoning and read summaries stay compact.
  Code has top/bottom rules and Clojure/EDN syntax colors; tables have visible cell
  boundaries. Only active keyboard selection adds a highlighted surface.
  Both the chevron and heading toggle reasoning/execution blocks. Expanded activity
  shows highlighted Clojure source and output. Large output stays bounded
  and inspectable, and native values remain available through the inspector.
  The transcript fills the available vertical space independently of streamed
  content height. Native sticky scrolling owns following new content; post-paint
  height and follow corrections must not create a second visible layout step.
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
