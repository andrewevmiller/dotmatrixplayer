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
- `nt_red` is `#C8102E` (N-Red) - the one accent colour, reserved for "this
  needs your attention" (no access, background read denied) and for the goal
  indicator when it is set to red.
- `nt_amber` is `#FFC700` (N-Yellow), one of the three accent choices, a step
  below the red state.
- N-Blue has no use case on a health tile and isn't wired up anywhere -
  same as it has none on the media or data tile.

This project shipped with an invented red (`#D71921`) and an invented amber
(`#FFB300`) at first, ported from the sibling widgets before *they* had
corrected to the guideline's own values. Both are now the real ones.

Deliberate departure: the guideline's foundation greys (Window grey, N-Grey)
are light-mode print/packaging tones. A home-screen widget lives on a dark
launcher and needs a dark card, so surfaces stay near-black
(`widget_surface #1B1B1B`) rather than adopting those greys literally. The
guideline's own rule - "only use black and white for our logo, unless for
exceptional circumstances" - is the principle carried over: the tile is a
monochrome system with exactly one signal colour, same as the brand mark.

The card's 1dp white stroke was removed - see the hairline paragraph under
Layout below, which is now the family-wide rule rather than a local decision.

The launcher icon's background is pure black, matching the three sibling
modules. It shipped as the Android Studio template's `#3DDC84` green, which is
an invented colour with no role in this palette.

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
