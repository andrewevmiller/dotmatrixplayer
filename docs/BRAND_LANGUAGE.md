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
- `nt_red` across every widget is now `#C8102E` (N-Red), not an invented
  red — this is the one accent colour, reserved for "this is live". In the
  score widget that phrase is literal: red marks a game in progress, and the
  red zone.
- `nt_amber` in the data widget's over-limit picker is now `#FFC700`
  (N-Yellow) rather than a generic Material amber.
- N-Blue has no use case in a media/data tile and isn't wired up anywhere.

Deliberate departure: the guideline's foundation greys (Window grey,
N-Grey) are light-mode print/packaging tones. A home-screen widget lives on
a dark launcher and needs a dark card, so surfaces stay near-black
(`widget_surface #1B1B1B`) rather than adopting those greys literally. The
guideline's own rule — "only use black and white for our logo, unless for
exceptional circumstances" — is the principle carried over: the tile is a
monochrome system with exactly one signal colour, same as the brand mark.

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
