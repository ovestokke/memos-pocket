# Design — Memos Pocket

A locked design system for the Android app. UI changes should preserve the app's
working behavior and use this file as the visual source of truth.

## Genre

Modern-minimal, tuned as a quiet personal writing tool rather than a generic
Material demo or a SaaS dashboard.

## Macrostructure family

- App pages: **Workbench** — adaptive primary navigation with one dominant work
  surface and calm supporting content.
- Feed: composer-first stream; the composer is the only strongly contained
  surface and memo rows use spacing and rules rather than repeated cards.
- Detail: single reading pane on compact/medium windows; list–detail on expanded
  windows when enough usable width remains.
- Settings: grouped list rows; diagnostics are supporting/advanced information,
  not the screen's primary visual layer.

## Theme

Dark values are sampled from the supplied Memos web-app reference.

- `ink` `#1D1F23` — app background
- `deepRail` `#16191C` — navigation background
- `raisedSlate` `#25282C` — composer and raised surfaces
- `rule` `#3F4348` — borders and separators
- `accent` `#3A6BA4` — primary action; keep below roughly 5% of a viewport
- `text` `#DBDEE2` — primary text
- `mutedText` `#A2A5A9` — metadata and supporting text

The light palette remains the warm Memos palette already present in the app:
`#FAF9F5` paper, white raised surfaces, `#242011` ink and `#305880` accent.

## Typography

- UI and memo content: Manrope, regular 400 / medium 500 / semibold 600.
- Diagnostics only: platform monospace.
- Titles are semibold, never oversized; memo content carries the reading rhythm.
- Metadata uses smaller text, muted colour and tabular numerals where useful.
- Headings are upright. No isolated italic display treatment.

## Shape and spacing

- 4 dp spacing scale.
- Compact controls: 8 dp corners.
- Composer and dialogs: 16–20 dp corners.
- Navigation selection: restrained rounded rectangle, not a pill used everywhere.
- Minimum interactive target: 48 dp.

## Navigation

- Compact (`<600dp`): bottom navigation for Memos, Archived and Settings.
- Medium (`600–839dp`): navigation rail.
- Expanded (`>=840dp`): persistent dark sidebar.
- Very wide (`>=1200dp`): feed and memo detail may appear side by side.
- Feed scope lives beneath the app-bar title as a quiet dropdown: **My memos**
  followed by member spaces. The chosen scope persists across Memos, Archived and
  app restarts; it is navigation context, not a Settings preference.

## Motion

Motion-cut. Use only Material's state transitions and navigation/open-close
feedback. No decorative entrance animations. Respect the system reduced-motion
setting automatically by avoiding custom motion.

## Interaction stance

- Use icons for compact, conventional app-bar actions.
- Keep labels for consequential or ambiguous actions.
- Silent success when the result is visible; explicit, directional errors.
- Whole memo rows and whole settings rows are interactive, not isolated words.
- Destructive operations keep confirmation because server deletion/disconnect is
  not locally reversible.

## What screens must share

- Palette and Manrope typography.
- Navigation treatment and app-bar action style.
- Composer surface, button shape and focus language.
- Metadata hierarchy and separator language.
- Edge-to-edge system chrome that follows the active theme.

## What screens may differ

- Feed is denser and stream-like.
- Detail prioritizes reading measure and whitespace.
- Editor gives the text field most of the available height.
- Settings uses grouped rows and may expose a compact diagnostic block.

## Per-screen allowance

App screens must not use decorative illustration, gradients, glass effects,
ambient blobs or repeated ornamental cards. Function and text carry the design.
