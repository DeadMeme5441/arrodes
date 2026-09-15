# TUI themes and theme packs

Run `/theme` to open the theme screen. Up/down previews immediately; Enter saves the
selection, and Escape restores the saved theme. The composer, its text/cursor, scroll
position, and current operation stay in place. `/theme` works before the first Send.

Built in:

- **Arrodes** (`default`): a Fool-inspired palette of silver text, antique gold accents, and layered charcoal/black surfaces.
- **Dracula** (`dracula`): purple accents with the [official Dracula palette](https://draculatheme.com/contribute).

`arrodes --theme dracula` chooses a theme for that launch. Saving a selection in `/theme`
updates the normal preference. Themes affect colors and text emphasis only; they do not
control width, spacing, layout, keyboard handling, or executable behavior. Turn rules,
execution frames, code boundaries, and table grids are shared components and remain
present in every theme.

## Install a pack

Place a directory in `HOME/themes/`, where HOME is the application home selected by
`--home`, `ARRODES_HOME`, or `~/.arrodes`:

```text
themes/
  rose/
    theme.edn
    preview.png     # optional sharing artwork; the TUI does not load it
```

Reopen `/theme` to discover new packs. A standalone `HOME/themes/rose.edn` is also
accepted. No installation script or network access is involved.

Example `theme.edn`:

```clojure
{:schema-version 1
 :id "rose"
 :name "Rose"
 :version "1.0.0"
 :author "Your name"
 :description "Muted surfaces with rose accents."
 :colors {:ui/accent "#cc5577"
          :user/heading "#cc5577"
          :assistant/heading "#99bbcc"}
 :styles {:user/heading {:bold true}
          :assistant/heading {:bold true}}}
```

Unspecified roles inherit the Arrodes defaults. To start from Dracula instead, copy
[its manifest](../resources/arrodes/themes/dracula/theme.edn) and change the ID/name.
The built-in manifests are embedded in compiled and standalone executables.

IDs use lowercase letters, digits, and hyphens. IDs must be unique, including built-ins.
Metadata is plain text. A manifest is one EDN map, limited to 64 KiB, with no custom
reader tags, executable forms, or trailing values. Invalid packs are excluded with a
visible diagnostic; the active theme remains available. Unknown fields and roles are
rejected so spelling mistakes do not silently disappear.

## Color roles

Every value is a six-digit `#RRGGBB` string.

| Group | Roles |
| --- | --- |
| Surfaces | `:surface/base`, `:surface/panel`, `:surface/selected` |
| Text | `:text/primary`, `:text/secondary`, `:text/dim` |
| Interaction | `:ui/accent`, `:border/default`, `:selection/background` |
| Status | `:status/success`, `:status/warning`, `:status/error` |
| Conversation | `:user/heading`, `:assistant/heading`, `:execution/heading` |
| Syntax | `:syntax/comment`, `:syntax/string`, `:syntax/keyword`, `:syntax/number`, `:syntax/function` |
| Markdown | `:markdown/heading`, `:markdown/link` |

Text treatments accept `:bold`, `:italic`, and `:underline` booleans on these roles:
`:user/heading`, `:assistant/heading`, `:execution/heading`, `:markdown/heading`,
`:markdown/strong`, `:markdown/emphasis`, and `:markdown/link`.

## Persistence

The saved theme ID is in `HOME/config/tui.edn`:

```clojure
{:theme "dracula"}
```

This is a local UI preference, separate from provider settings and session storage.
Opening the picker or previewing a pack writes nothing. Saving replaces the preference
file atomically and preserves other UI preference keys. A missing selected pack falls
back to Arrodes on startup. There is no change to the session database schema.
