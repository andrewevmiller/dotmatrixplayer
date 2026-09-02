# Brand language

How this widget reads Nothing's own brand guidelines, and where it
deliberately departs from them. Source: `nothing_brand_guidelines.pdf`
(Nothing's Figma export - corporate identity, not the Nothing OS UI kit),
plus the interpretation already worked out for the sibling widgets in
`Nothing App Dev/Music Player/docs/BRAND_LANGUAGE.md`, which this file
follows rather than re-deriving.

This project is an unofficial, third-party widget that borrows Nothing's
visual language. It is not a Nothing product, so the actual wordmark and
logotype are never reproduced - "Nothing Health" appears here as the English
words, set in the dot-matrix face the same way a product name would be, never
as the Dot55 logotype itself.

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
- `nt_red` is `#D71921` (N-Red) - the one accent colour, reserved for "this
  needs your attention" (no access, background read denied) and for the goal
  indicator when it is set to red.
- `nt_amber` is `#FFC700` (N-Yellow), one of the three accent choices, a step
  below the red state.
- `nt_grey_accent` is the accent grey, and it splits by theme like every other
  surface token: `#303032` in dark mode, `#E8E6E7` in light. Each sits one step
  *away* from its own card - lighter than the `#1B1B1D` tile, darker than the
  `#F3F0F1` one - so secondary surfaces read as distinct without introducing a
  second hue. It is a surface tone, not a signal colour - red stays the only
  accent that means anything. Distinct from `config_field` (`#C7C3BE`), which
  is a deeper, load-bearing "recessed input" step; the accent grey is the quiet
  one, for separation where a full field would overstate it.
- N-Blue has no use case on a health tile and isn't wired up anywhere -
  same as it has none on the media or data tile.

This project shipped with `#D71921` and an amber of `#FFB300` at first, ported
from the sibling widgets. Amber corrected to the guideline's own `#FFC700` and
has stayed there. Red moved to the guideline's `#C8102E` in the same pass, then
back to `#D71921`, which is the current value across the family - so the accent
red is a deliberate departure from the printed palette rather than a leftover.

Deliberate departure (dark mode only): the guideline's foundation greys
(Window grey, N-Grey) are light-mode print/packaging tones. A dark-themed
home-screen widget lives on a dark launcher and needs a dark card, so its
surfaces stay near-black
(`widget_surface #1B1B1D`) rather than adopting those greys literally. The
guideline's own rule - "only use black and white for our logo, unless for
exceptional circumstances" - is the principle carried over: the tile is a
monochrome system with exactly one signal colour, same as the brand mark.

The card's 1dp white stroke was removed - see the hairline paragraph under
Layout below, which is now the family-wide rule rather than a local decision.

The launcher icon's background is pure black, matching the three sibling
modules. It shipped as the Android Studio template's `#3DDC84` green, which is
an invented colour with no role in this palette.

### Light mode

As of 2026-08-31, the tile follows the system theme rather than staying
permanently dark, which retires the rejection above for the light side: a
light-mode surface is exactly the print/packaging context Window grey and
N-Grey were specified for, so this is where they finally get used. The card
surface then moved a second time within that pass, from N-Grey to off-white,
and text alphas were pushed further to keep contrast crisp on the brighter
surface rather than just re-clearing 4.5:1 by default. The table below reflects
that later state; N-Grey is no longer used as a card fill.

| Role | Name | Hex | Used for |
|---|---|---|---|
| Card surface | Off-white | `#F3F0F1` | `widget_surface` (the tile) and `config_card` (the settings screen's card) |
| Page background | Off-white | `#F3F0F1` | `config_bg` |
| Accent grey | Off-white, one step down | `#E8E6E7` | `nt_grey_accent` - quiet separation against the card, where a full field would overstate it |
| Recessed field | N-Grey/Window grey blend | `#C7C3BE` | `config_field` (the goal steppers and metric rows) |
| Primary text | Pure black | `#000000` | `text_primary` |
| Secondary text | 65% black | `#A6000000` | `text_secondary` |
| Tertiary text | 80% black | `#CC000000` | `text_tertiary` |

**The tile and the settings card share one tone, rather than each getting its
own the way the dark palette gives the tile `#1B1B1D` against a near-black
`config_bg`/`config_card` (`#000000`/`#0E0E0E`).** In dark mode the tile has to
sit visibly *lighter* than the settings chrome around it to read as its own
card against a near-black background. In light mode that lift isn't needed - a
light tile next to a light system launcher already reads as a card without an
extra nudge - so `widget_surface`, `config_card` and `config_bg` are all one
hex. For the tile this is an accepted tradeoff: an off-white tile against a
light-to-white system launcher can lose its edge definition, and the
guideline's "only use black and white for our logo, unless for exceptional
circumstances" rule plus this project's own no-tile-border stance rule out
compensating with a stroke on `widget_bg.xml`, so the tile stays flat fill. For
the settings screen, `health_config_card_bg.xml` draws `widget_stroke`
(`#1F000000`) as a 1dp outline around the card fill, and that hairline is now
the *only* separation from `config_bg` - it was already there for the N-Grey
version and does the job on off-white too.

**Text alphas were raised past what contrast alone required.** Moving the card
fill off N-Grey gives every existing alpha more headroom, not less - a lighter
surface makes translucent black read darker by comparison at the same alpha.
Rather than bank that headroom unspent, `text_secondary` moved from 60%
(`#99000000`) to 65% (`#A6000000`) and `text_tertiary` from 65% (`#A6000000`)
to 80% (`#CC000000`). `text_primary` stays pure black - already the maximum.

**`text_tertiary`'s alpha is not a straight polarity-flip of the dark value.**
The dark palette uses `#5CFFFFFF` (36% white) for tertiary text on `#1B1B1D`.
Naively flipping that to `#5C000000` (36% black) computed to about 2.4:1 on
N-Grey - well under the 4.5:1 bar. Dark-on-light and light-on-dark don't scale
symmetrically at the same alpha, because the surfaces aren't symmetric
(near-black vs. a mid-light grey, not near-black vs. near-white). The alpha was
solved for directly against the tightest surface in play each time rather than
carried over by symmetry.

`config_field` is a custom blend rather than a literal guideline tone: the full
Window grey (`#B1B3B3`) is the stylistically right "recessed input" step down,
but computes to only ~4.4:1 for `text_secondary` drawn on it - just under the
bar. `#C7C3BE` sits between N-Grey and Window grey and clears 4.5:1 for every
text colour placed on it (`text_primary` ~10:1, `text_secondary` ~5:1,
`text_tertiary` ~7.3:1) while still reading as a distinct, darker step from the
card.

Two decisions specific to this module, both consequences of the brighter
surface rather than separate choices:

- `chip_off` was raised from 8% (`#14000000`) to 16% (`#29000000`). On N-Grey,
  8% black was already a subtle wash but still visible; against an off-white
  card it computes to under 1.2:1, essentially invisible. It is not a text
  colour, so it is held only to being perceptibly distinct from its background.
  `chip_on` stays white on purpose: `chip_text.xml` pairs the selected state
  with `nt_black` text, and `nt_black`/`nt_white` are brand invariants left
  unsplit, so a black fill would make that text unreadable.
- The config screen's CTA pill inverts in light mode - `button_surface`
  `#000000` with `button_text` white. The fixed white-pill/black-text pairing
  that used to be hardcoded had no visible boundary at all against the light
  `config_bg`. Dark mode keeps the original white pill.

The hairline strokes on settings-screen cards (`widget_stroke`, low-alpha black
in light mode) are decorative structure, not a text pairing, so they aren't
held to a contrast ratio.

## Typography

The guideline names two families: **NType (Type82)** in headline/regular/
mono cuts, and **NDot (Dot55/57)**, the industrial dot-matrix font used for
product names and the logotype itself. This project ships genuine cuts of
both - `ntype82.otf` and `ndot57_aligned.otf`.

**Outstanding divergence from the family: this module has not adopted Geist.**
The three Music Player modules moved body copy, data readouts and labels to
`geist.ttf` on 2026-08-25, keeping NType82 as the accent/headline role, and
each carries a `Typography.kt` naming Body / Accent / Wordmark plus a
`TypographyTest` asserting neither primary role resolves to an NDot cut. This
module still names faces directly at every call site and has no Geist. Closing
that gap is a separate change, not a materials pass.

Rules that matter here, and this project's first pass got backwards:

- **NDot is scoped to product names and the logotype** - "Dot55 should not
  be used as a normal font." Every string this widget draws that isn't its
  own name - the tile's row labels and figures, the settings screen's section
  headings, chip text, button labels, the connection status line - is set in
  NType82, not NDot. The **one** NDot label is the settings screen's own
  headline, "Nothing Health": that *is* this app's product name, so NDot is
  the correct face for it, the same way the media widget's setup screen sets
  "Dot Matrix Player" in it.
- **NDot never takes manual letter-spacing.** The guideline is explicit -
  "VA is set to `>0<`" - because the font is a fixed matrix advance, not a
  proportional face. The headline carries no `letterSpacing`.
- **NDot is mainly uppercase.** The headline is `textAllCaps`, matching the
  sibling's pattern of keeping the string itself mixed-case and applying caps
  at the view.
- **There is no exception.** An earlier pass kept the settings screen's stepper
  `+`/`-` keys in NDot 57 Aligned, on the argument that they are symbols rather
  than words and so the face was a shape choice. That argument does not hold:
  a stepper is a control, and the family puts controls - chips, steppers,
  buttons - on the accent face without exception. The data widget's own
  `Stepper` style inherits its `Accent`. Both keys are NType82 now, and the
  headline is the only NDot on the page.
- **NType82 falls back to nothing for coverage**, unlike the media widget.
  Every string this tile draws is one it authored itself out of digits, five
  metric names and a handful of English words - there is no arriving text in
  an unknown script, so there is no glyph-coverage fallback to Ndot 77 JP
  Extended to wire up, and that 14 MB cut was never bundled here.
- **NType regular takes variable tracking by size** (the guideline's spacing
  chart), so every NType82 label on this tile and screen keeps the tracking
  it had before this pass - that face is allowed to breathe. Only the face
  assignment was wrong, not the numbers.

**`ntype82_headline.otf` has been removed.** It was carried over from the Fonts
folder on the assumption that a distinct headline cut belonged on the settings
screen's headline - but the headline turned out to be this app's product name,
which the guideline reserves for NDot instead, leaving the cut with nothing to
set. An earlier note here claimed the sibling widgets bundle it too and that it
stayed for parity; they do not - all three ship exactly `ndot57_aligned.otf`,
`ntype82.otf` and `geist.ttf`. This module now ships two cuts, the same
conclusion the data and score widgets reached.

## Layout

The guideline's grid (2/4/6/8/10 columns, 2.3%-of-format margins) is built
for print and campaign formats, not a fixed-aspect home-screen tile - it
isn't applicable here and isn't used. What does carry over is the general
discipline: one clear focal object (the dial, or the row list), a strict type
scale, and no decoration that isn't load-bearing.

The card hairline is where that last clause bites, and the answer is that no
tile carries one. An earlier draft rang the media and data tiles in a 1dp
`#1FFFFFFF` stroke on the reasoning that a mostly-empty near-black card needs
an edge against a dark wallpaper; the score widget dropped it because that tile
already carries two Glyph Matrices, a scoreline and a rail, and a stroke around
all of that stops reading as a frame and starts reading as one more line
competing with the content.

The stroke has since been removed from every tile in the family, including this
one. The near-black fill against the 26dp corner radius does the "this is a
card" work on its own, and an outline is exactly the kind of decoration the
"nothing that isn't load-bearing" clause rules out. Treat a `<stroke>`
appearing in any tile's `widget_bg.xml` as a regression, not a fix. The
`widget_stroke` colour survives for the settings screen's cards, fields and
unselected chips, which are a different surface and are not tiles.

## What's intentionally not adopted

- **The logotype and lockup rules** (uppercase-only wordmark, `(R)` usage,
  product-lockup spacing) - this app has no Nothing logotype to place.
- **"The Cut" treatment** - a print/digital effect specific to elevated
  marketing executions, not a system-widget affordance.
- **Photography categories** (Youth/Elevated/Basic) - no photography on a
  health tile; every glyph is a drawn vector, not a photograph.
