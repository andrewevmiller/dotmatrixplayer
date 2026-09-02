# Brand language

How this widget family reads Nothing's own brand guidelines, and where it
deliberately departs from them. Source: `nothing_brand_guidelines.pdf`
(Nothing's Figma export — corporate identity, not the Nothing OS UI kit).

This project is an unofficial, third-party widget that borrows Nothing's
visual language. It is not a Nothing product, so the actual wordmark and
logotype are never reproduced — "NOTHING" only ever appears here as the
English word (e.g. the idle-state `NOTHING PLAYING` label), never as the
Dot55 logotype.

## Colour

The guideline palette, hex values as printed in the PDF:

| Role | Name | Hex |
|---|---|---|
| Foundation | Pure black | `#000000` |
| Foundation | Window grey | `#B1B3B3` |
| Foundation | N-Grey | `#DCD7D2` |
| Highlight | Pure white | `#FFFFFF` |
| Primary | N-Red | `#C8102E` |
| Primary | N-Blue | `#002F6C` |
| Primary | N-Yellow | `#FFC700` |

Applied:
- `nt_red` across every widget is now `#D71921` (N-Red), not an invented
  red — this is the one accent colour, reserved for "this is live". In the
  score widget that phrase is literal: red marks a game in progress, and the
  red zone.
- `nt_amber` in the data widget's over-limit picker is now `#FFC700`
  (N-Yellow) rather than a generic Material amber.
- `nt_grey_accent` is the accent grey, and it splits by theme like every other
  surface token: `#303032` in dark mode, `#E8E6E7` in light. Each sits one
  step *away* from its own card — lighter than the `#1B1B1D` tile, darker than
  the `#F3F0F1` one — so secondary surfaces read as distinct without
  introducing a second hue. It is a surface tone, not a signal colour: red
  stays the only accent that means anything. Distinct from `config_field`
  (`#C7C3BE`), which is a deeper, load-bearing "recessed input" step; the
  accent grey is the quiet one, for separation where a full field would
  overstate it.
- N-Blue has no use case in a media/data tile and isn't wired up anywhere.
- The "WHITE" accent choice (data widget's `COLOR_WHITE`, score widget's
  `ACCENT_WHITE`, health widget's `COLOR_WHITE`) resolves through
  `text_primary`, not a literal `nt_white`, as of the light-mode pass below.
  The choice was never really "the colour white" — the in-code comments call
  it "keeps the tile strictly monochrome" — so it now means "whatever the
  tile's own ink colour is," which tracks the theme like every other text on
  the tile. Resolving it to a literal white broke in light mode: a white
  alert dot/border/readout painted over a light `widget_surface` was
  invisible, the same failure mode a plain white status-indicator dot on the
  settings screen had (`status_dot_on` family, also moved to `text_primary`).

Deliberate departure (dark mode only): the guideline's foundation greys
(Window grey, N-Grey) are light-mode print/packaging tones. A dark-themed
home-screen widget lives on a dark launcher and needs a dark card, so its
surfaces stay near-black (`widget_surface #1B1B1D`) rather than adopting
those greys literally. The guideline's own rule — "only use black and white
for our logo, unless for exceptional circumstances" — is the principle
carried over: the tile is a monochrome system with exactly one signal
colour, same as the brand mark.

### Light mode

As of 2026-08-31, every tile follows the system theme rather than staying
permanently dark, which retires the rejection above for the light side: a
light-mode surface is exactly the print/packaging context Window grey and
N-Grey were specified for, so this is where they finally get used.

**Update, same day:** the card surface (tile and settings card) moved a
second time within this pass, from N-Grey to off-white, and text alpha
values were pushed further to keep contrast noticeably crisp on the
brighter surface rather than just re-clearing 4.5:1 by default. The table
below reflects that later state; N-Grey is no longer used as a card fill.

| Role | Name | Hex | Used for |
|---|---|---|---|
| Card surface | Off-white | `#F3F0F1` | `widget_surface` (the tile) and `config_card`/`setup_card` (the settings screen's card) |
| Page background | Off-white | `#F3F0F1` | `config_bg`/`setup_bg` |
| Accent grey | Off-white, one step down | `#E8E6E7` | `nt_grey_accent` — quiet separation against the card, where a full field would overstate it |
| Recessed field | N-Grey/Window grey blend | `#C7C3BE` | `config_field` (steppers, the team search box, the team picker rows) |
| Primary text | Pure black | `#000000` | `text_primary` |
| Secondary text | 65% black | `#A6000000` | `text_secondary` |
| Tertiary text | 80% black | `#CC000000` | `text_tertiary` |

Three decisions worth recording:

**The tile and the settings card share one tone, rather than each getting
its own the way the dark palette gives the tile `#1B1B1D` against a
near-black `config_bg`/`config_card` (`#000000`/`#0E0E0E`).** In dark mode the
tile has to sit visibly *lighter* than the settings chrome around it to read
as its own card against a near-black background. In light mode that lift
isn't needed — a light tile next to a light system launcher already reads as
a card without an extra nudge — so the tile and the settings screen's card
share one surface. That surface was N-Grey at first, then moved to off-
white (see below), at which point it became literally identical to the page
background rather than merely close to it.

**Card surface moved from N-Grey to off-white, on top of the decision
above.** This means `widget_surface`, `setup_card`/`config_card`, and
`setup_bg`/`config_bg` are now all `#FFF3F0F1` — three roles, one hex. For
the tile, this is an accepted tradeoff: an off-white tile against a light-to-white
system launcher can lose its edge definition, and the guideline's "only use
black and white for our logo, unless for exceptional circumstances" rule
plus this project's own no-tile-border stance rule out compensating with a
stroke on `widget_bg.xml`, so the tile stays flat white fill. For the
settings screen, `setup_card`/`config_card` keep the existing
`widget_stroke` hairline (already applied via `setup_card_bg.xml`'s
`<stroke>`, previously decorative reinforcement on a card that was already a
distinct tone) as the *only* remaining separation from `setup_bg`/`config_bg`
now that the fill itself no longer differs.

**Text alpha values were raised again, past what contrast alone required.**
Moving the card fill from N-Grey to white gives every existing alpha *more*
headroom, not less — a lighter surface makes translucent black read darker
by comparison at the same alpha, if anything. Rather than bank that headroom
unspent, `text_secondary` moved from 60% (`#99000000`) to 65% (`#A6000000`)
and `text_tertiary` from 65% (`#A6000000`) to 80% (`#CC000000`), so the
brighter surface actually reads as crisper type, not merely as "still
technically passing." `text_primary` stays pure black (100%) — already the
maximum.

**`text_tertiary`'s alpha is not a straight polarity-flip of the dark value,
even before the update above.** The dark palette uses `#5CFFFFFF` (36% white)
for tertiary text on `#1B1B1D`. Naively flipping that to `#5C000000` (36%
black) on N-Grey computed to about 2.4:1 contrast — well under the 4.5:1 text
bar, and a confirmed readability bug caught earlier in this pass. Dark-on-
light and light-on-dark don't scale symmetrically at the same alpha, because
the surfaces being drawn on aren't symmetric (near-black vs. a mid-light
grey, not near-black vs. near-white). The alpha was solved for directly
against the tightest surface in play each time — first N-Grey, now pure
white — rather than guessed or carried over by symmetry.

`config_field` is a custom blend rather than a literal guideline tone: the
full Window grey (`#B1B3B3`) is a hard requirement for the "recessed input"
step down from N-Grey stylistically, but computes to only ~4.4:1 for
`text_secondary` text drawn on it (the score widget's team-picker rows) —
just under the bar. `#C7C3BE` sits between N-Grey and Window grey and clears
4.5:1 for every text colour placed on it while still reading as a distinct,
darker step from the card.

`art_placeholder` (`#141414`, the "no artwork" swatch behind the media
tile's disc glyph) and the hairline strokes on settings-screen cards
(`widget_stroke`/`hairline`, low-alpha black in light mode) stay unsplit or
lightly-adjusted rather than redesigned: the former is a fixed backdrop for
a fixed glyph, not a themed surface, the same way `nt_black`/`nt_white` stay
brand invariants; the latter is decorative structure, not a text pairing, so
it isn't held to a contrast ratio.

**Known tension, flagged rather than silently resolved:** `nt_amber`
(`#FFC700`) is occasionally painted as full-opacity *text* (not just a dot or
border fill) when an accent colour is active — for example the data widget's
over-limit readout and the health widget's goal-met row value. Amber text
directly on the N-Grey tile computes to roughly **1.1:1** contrast — it is
essentially invisible, since N-Grey and N-Yellow sit at nearly the same
luminance. `nt_red` (`#D71921`) used the same way computes to roughly
**3.7:1** — under the 4.5:1 text bar for normal-size text, though it clears
3:1 for large/bold text. Per this pass's scope, brand-mandated accent hex
values were not altered to chase contrast; this is recorded here as a real,
user-reachable gap rather than fixed in place, since fixing it would mean
either changing N-Yellow's hex (out of scope) or changing which surface an
accent-coloured *text run* is allowed to sit on in light mode (a rendering
change, not a colour value, and out of this pass's scope). Worth a follow-up
pass specifically on accent-as-text in light mode.

## Typography

The guideline names two families: **NType (Type82)** in headline/regular/
mono cuts, and **NDot (Dot55/57)**, the industrial dot-matrix font used for
product names and the logotype itself. This project uses these alongside
**Geist** for optimal readability and coverage.

### Font roles

- **Geist** — primary body text font for all UI elements requiring legibility
  and extensive script coverage. Used for body copy, data readouts, labels, and
  any text sized below display hierarchies.
- **NType (Type82)** — accent and headline font for emphasis. Applied to UI
  controls (buttons, chips), status indicators, time codes, and section labels
  that benefit from its distinctive character. Carries variable tracking by size
  per the guideline's spacing chart.
- **NDot (Dot55/57)** — scoped exclusively to product names and the logotype.
  "Dot55 should not be used as a normal font." Rendered at zero tracking per the
  guideline's matrix-advance specification.
- **NDot77 JP Extended** — fallback for scripts beyond NType82's Latin coverage
  (~240 codepoints). Used only when NType82 cannot render a string; otherwise,
  the primary face carries the render.

### Implementation notes

This project ships genuine cuts of NType and NDot: `ntype82.otf`,
`ndot57_aligned.otf`, `ndot77_jp.ttf` (JP-extended NDot cut, media widget only),
plus `geist.ttf` (Geist variable, `wght` axis).

**All three modules are on the role split** as of 2026-08-25. Each has its own
`Typography.kt` naming Body / Accent / Wordmark, and a `values/styles.xml` with
matching `Body` and `Accent` base styles for its settings screen — XML cannot
read a Kotlin constant, so the decision is stated twice per module and the two
have to be kept in step. Every call site names a **role**, never a face, so a
future change to this document is a change to one file per module.

Each module also carries a `TypographyTest` asserting that neither primary role
ever resolves to an NDot cut. That failure is silent — the wrong face still
builds and still draws — so it is worth an assertion rather than a code review.

Rules applied identically in the media widget, the data widget, and the score
widget:

- **Geist is used for data and interface text.** Body text, UI labels, and any
  content prioritizing clarity uses Geist for its readability across all sizes
  and scripts.
- **NType82 is reserved for accents and titles.** Status labels, time codes,
  chips, steppers, buttons, and section headings use NType82's distinctive
  character to create hierarchy and visual interest. This includes the data
  widget's section labels.

  *The media widget's track title and artist are Geist, not NType82* — as
  implemented. This bullet previously named them here, which is a leftover from
  the version of this document that predated Geist, where the sentence meant
  "these are NType82 rather than **NDot**" and was listing what had moved off
  the wordmark face. Under the roles above they are body copy: the primary
  content of the tile, not a control, status indicator, time code or section
  label. They are also the strings that most need coverage, which is the other
  reason they belong on the wider face — see the fallback bullet below. If the
  intent really was NType82 for the hero text, it is a one-line change in
  `app/Typography.kt`.
- **NDot headlines mark product names only.** Each app's configuration screen
  headline — "Dot Matrix Player", "Dot Matrix Data" — uses NDot 57 Aligned
  because that *is* the product name, same as it would be for "phone (2a)". The
  media and data widgets' remaining labels are set in caps.
- **NDot never takes manual letter-spacing.** Zero tracking is enforced: it is a
  fixed matrix advance, not a proportional face. The data widget previously ran
  its entire interface in NDot with manual tracking (0.10–0.18 em); all non-logo
  text now uses Geist or NType82, which the guideline's spacing chart licenses
  to carry tracking.
- **NType82 and Geist allow variable tracking by size.** Both fonts' letter-spacing
  adapts with size per the guideline's spacing chart, so body and UI elements
  keep appropriate letter-spacing in both apps — these faces are allowed to breathe.
- **Coverage fallback (media widget only).** `TextRenderer` checks glyph coverage
  per string (`Typeface.hasGlyph`). If Geist or NType82 can't render a string,
  the renderer swaps to NDot77 JP Extended (~21,000 codepoints) for that string
  only. Otherwise, the primary face carries the render (the data and score
  widgets author every string they draw, so they have no coverage problem to
  solve — the score widget's team abbreviations come from a feed, but a feed of
  North American leagues, which is ASCII).

  Moving the media widget's title and artist to Geist made this fire **less**
  often, which is a point in its favour. Measured off the two fonts' own `cmap`
  tables: NType82 carries ~217 codepoints and is Latin-only; Geist carries ~728
  and includes Cyrillic. A Russian track title used to fall through to the 14 MB
  JP cut and now does not.

- **Check a face's coverage before adopting it, not after.** A missing glyph
  produces no build error — only a tofu box on a phone. Read the `cmap` table
  directly. Doing this turned up that **NType82 has no arrow glyphs at all**
  (U+2191 / U+2193 absent), which was a live bug in the score widget's settings
  screen where the reorder buttons were arrows set in the Accent face. All three
  modules now draw such controls as vector drawables, so a *control* never
  depends on the glyph coverage of whichever face a typographic role points at. 

### One further NDot use, in the score widget

`:scorewidget` sets a team's abbreviation in NDot 57 Aligned inside
`TeamGlyphs` — and then never displays it as type. The letters are drawn to an
offscreen bitmap purely so they can be **resampled onto a 13×13 dot grid** and
become that team's Glyph Matrix mark, which is what the tile actually shows.

This is not an exception to "Dot55 should not be used as a normal font"; it is
the rule's own logic. NDot is the industrial dot-matrix face, and the thing
being generated is a literal dot matrix. It renders at zero tracking like every
other NDot use here — doubly so, because a fractional tracking offset is exactly
the kind of sub-cell shift that makes the same abbreviation quantise two
different ways on consecutive draws.

Everything that module's tile draws as readable text is split across the two
non-wordmark roles above, in one place — `Typography.kt`, mirrored by two base
styles in its `values/styles.xml` because XML cannot read a Kotlin constant.
Every call site names a role rather than a face:

| role | covers |
|---|---|
| Body (Geist) | scores, team abbreviations, down-and-distance, broadcast, stat line, settings copy and labels |
| Accent (NType82) | the game clock, the league label, empty-state lines, chips, steppers, the status line |

The one that needed deciding: **a score is a data readout and the clock is a
time code**, so the two halves of the same line take different faces. The score
also resists the obvious pull toward NDot 57 Aligned — a changing figure invites
a tabular face — and instead solves digit jitter structurally, by measuring both
scores into a shared column width. That holds for one digit against two against
three, which a tabular face does not.

**Geist landed 2026-08-25** and the score widget ships it as `geist.ttf`.

It arrived as a variable font (`Geist-VariableFont_wght`), not static cuts,
which is worth knowing before the other two modules adopt it: a variable font
renders at its *default instance* unless an axis is set. Geist's `wght` axis
defaults to 400, matching its `OS/2` `usWeightClass`, so it loads as Regular and
needs no pinning. Do not set bold on it — a `Paint` told to embolden a variable
font synthesises a fake bold rather than moving the axis. The italic file is not
shipped. Cost is ~87 KB in a release APK.

`TypographyTest` asserts that neither Body nor Accent ever resolves to NDot, and
that the two remain different faces.

**Check coverage when adopting a face, not after.** Both faces' `cmap` tables
were read directly before shipping. Geist carries the en dash, em dash and `×`
this project needs. NType82 carries `×` but **has no arrow glyphs at all** —
U+2191 and U+2193 are absent — which was a live bug in the score widget's
settings screen, where the reorder buttons were arrows set in the Accent face.
They are vector drawables now. The general lesson: a control should not depend
on the glyph coverage of whichever face a typographic role points at, and a
missing glyph produces no build error, only a tofu box on a phone.

The module ships two cuts rather than three: with the settings headline being a
product name (and therefore NDot), `ntype82_headline.otf` had nothing left to
set, the same conclusion the data widget reached.

## Layout

The guideline's grid (2/4/6/8/10 columns, 2.3%-of-format margins) is built
for print and campaign formats, not a fixed-aspect home-screen tile — it
isn't applicable here and isn't used. What does carry over is the general
discipline: one clear focal object (artwork), a strict type scale, and no
decoration that isn't load-bearing.

The card hairline is where that last clause bites, and the answer is that no
tile carries one. An earlier draft rang the media and data tiles in a 1dp
`#1FFFFFFF` stroke on the reasoning that a mostly-empty near-black card needs
an edge against a dark wallpaper; the score widget dropped it because that tile
already carries two Glyph Matrices, a scoreline and a rail, and a stroke around
all of that stops reading as a frame and starts reading as one more line
competing with the content.

The stroke has since been removed from every tile in the family, including the
Health widget. The near-black fill against the 26dp corner radius does the
"this is a card" work on its own, and an outline is exactly the kind of
decoration the "nothing that isn't load-bearing" clause rules out. Treat a
`<stroke>` appearing in any tile's `widget_bg.xml` as a regression, not a fix.

## What's intentionally not adopted

- **The logotype and lockup rules** (uppercase-only wordmark, `(R)` usage,
  product-lockup spacing) — this app has no Nothing logotype to place.
- **"The Cut" treatment** — a print/digital effect specific to elevated
  marketing executions, not a system-widget affordance.
- **Photography categories** (Youth/Elevated/Basic) — no photography in a
  media tile; album art is sourced from the playing app, not commissioned.
