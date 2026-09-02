# The 2×1 pillar layout

A design doc for a second visual style on the data usage widget: a tall dot-matrix
"pillar" alongside the existing radial dial, with a hard red trip state.

---

## The brief

> Create an option to make the data usage widget a 2×1 pillar matching a
> reference dot-grid style. Make the background turn Nothing red when usage
> reaches the trip %, while retaining the white of the filled-in dots.

## What exists today, and what's new

The widget currently ships **one** visual style: a 40-dot radial arc drawn by
`MeterRenderer.kt`, sized to fill a resizable square tile (`data_widget_info.xml`
declares `targetCellWidth/Height = 2`, min 110dp, max 250dp, `resizeMode="horizontal|vertical"`).
There's no size-based layout switching — `WidgetRenderer.build()` reads the
live `OPTION_APPWIDGET_MIN_WIDTH/HEIGHT` and scales one continuous layout.

This doc adds a **second style**: a rectangular dot-matrix grid in a tall
pill-shaped card, sized for a 1×2 (narrow, tall) placement rather than the
existing 2×2 square. It is a sibling to the radial dial, not a replacement —
selected the same way accent color already is, from `ConfigActivity`.

```
        ┌────────────┐
        │            │
        │   ● ● ●    │
        │   ● ● ●    │
        │   ● ● ●    │
        │   ● ● ○    │   ● = filled  (white)
        │   ○ ○ ○    │   ○ = unfilled (dim gray)
        │   ○ ○ ○    │
        │   ○ ○ ○    │
        │   ○ ○ ○    │
        │   ○ ○ ○    │
        │   ○ ○ ○    │
        │   ○ ○ ○    │
        │   ○ ○ ○    │
        │            │
        └────────────┘
        3 columns × 14 rows, fills bottom → top
```

---

## Visual spec
Everything below started as estimates worked out interactively against a
photo reference (an industrial scaffolding shot with a dark dot-grid pill
overlaid on it). It has since been re-derived from the real thing: the user
runs the reference widget on their own home screen, and every ratio and hex
here is now measured off a device screenshot of it rather than eyeballed.
not an exact pixel transcription.

### Grid

| property | value |
|---|---|
| columns × rows | 3 × 14 (42 dots total) |
| dot shape | true circle, fixed diameter (not a stretched grid cell) |
| dot diameter | 100% of the dot pitch (`PillarRenderer.DOT_DIAMETER_RATIO`) — adjacent dots are exactly tangent |
| gap between dots (both axes) | none along the axes — the reference's measured dot diameter and pitch agree to within a fifth of a pixel, across every edge threshold from 32 to 45 luminance. What reads as separation between dots is the diagonal gap left between touching circles, not a designed-in gap. Earlier rounds guessed 60%/40%, then 93%/7%; both drew a visibly sparser grid than the thing being copied. |
| fill order | bottom-to-top, column-major (fills like a level gauge, not left-to-right like reading order) |
| fill count | `ceil(fraction * 42)`, same rounding rule `MeterRenderer` already uses for the radial dial |

The dot pitch (diameter + gap) is identical horizontally and vertically —
a square grid, not stretched to fit the card. Getting this right required
fixing each dot's size explicitly rather than letting a CSS/Canvas grid
stretch cells to fill available space, which produces ellipses instead of
circles whenever the card's aspect ratio doesn't match the column:row ratio.

### Card

| property | value |
|---|---|
| shape | true stadium (semicircular ends) — drawn by `PillarRenderer` itself with a corner radius of half the card's short side, not by a background drawable. A drawable had to bleed past the widget's own bounds to escape the layout padding, and the host clipped its rounded ends back off into near-square corners; drawing the card into the same bitmap as the dots puts the shape entirely inside what the host will show. |
| proportions | long side = 2.2554 × short side (415 / 184), letterboxed with transparency inside whatever box the launcher hands the widget, so a cell that is not exactly 1:2.26 gives a correctly proportioned pill rather than a stretched one |
| grid inset | proportional, not a fixed dp: the 14-dot axis spans 56.1% of the card's long side (`LONG_OVER_PITCH`, the single ratio the pitch comes from) and the 3-dot axis 27.2% of its short one, which follows from the aspect rather than being set separately. Leaves the reference's generous margin on all four sides at any tile size. |
| overall size | proportioned so the 3×14 grid plus its margins reads as a narrow vertical pillar (taller than wide), matching a 1×2 widget cell rather than the existing 2×2 square |

Every number in both tables is measured off the reference widget on a device
screenshot at density 3.0 — its card is 415 × 184 px (x 538..952, y 226..409) and its
grid 233 × 50 px (x 628..860, y 293..342), giving a 16.64 px pitch and a
16.7 px dot — and stored as a ratio, so the pillar is that same
object rotated 90 degrees rather than a lookalike at a different scale.

### Colors

| element | normal state | tripped state |
|---|---|---|
| card background | `#1B1B1B`, sampled off the reference (it reads `#1B1B1D` on screen). Lands exactly on the family's own dark `widget_surface` — see `docs/BRAND_LANGUAGE.md` — so in dark mode the pillar now matches the rest of the family and only diverges in light. | `nt_red` — `#FFC8102E`, already defined in `colors.xml` as Nothing's brand red |
| filled dot | white (`meter_active` / full ink) | **white, unchanged** |
| unfilled dot | `#303030`, sampled off the reference's resting dots (`#303032` on screen) — a subtle step up from the background, low contrast | `rgba(255,255,255,0.35)` — brighter than the normal-state gray so it stays legible against red instead of nearly disappearing |

The one hard requirement from the brief — filled dots stay white through the
trip — is the reason unfilled dots get a *different* treatment in each state
rather than a single fixed gray. On red, an 18%-alpha gray (the normal-state
value) would have almost no contrast against `nt_red`; bumping it to a
white-alpha wash keeps the unfilled dots visible as "the rest of the gauge"
without competing with the filled white dots for attention.

---

## How this maps onto the existing code

Implemented. This section now describes what shipped, not a plan.

**New renderer, not a modified one.** `MeterRenderer.kt`'s radial-arc drawing
is untouched. `PillarRenderer.kt` draws the 3×14 grid to its own cached
`Bitmap` (`LruCache<String, Bitmap>(4)`, keyed on size/filled-count/colors —
same pattern, no shared code), fills **bottom-to-top** (row 0 is the canvas
top; the dot `(ROWS - 1 - row) * COLS + col` positions up from the bottom is
the one that lights), and draws no text — confirmed against the dial's
clockwise fill and the "no text in this display" requirement, settling both
open questions from the original draft of this doc.

**Reuses `DataSettings` / `UsageSnapshot` as-is, with one new setting.** The
trip concept (`alertPercent`, `UsageSnapshot.overLimit`) is untouched and
reused directly — `WidgetRenderer.paintPillar` reads `snapshot.overLimit` to
decide whether the card is tripped. What's new is `DataSettings.layoutStyle`
(`LAYOUT_GAUGE` / `LAYOUT_PILLAR`, persisted as `layout_style`), because the
pillar's background-flip isn't one of the dial's four independent alert
*styles* — it's a wholesale swap of which renderer runs, so it lives beside
`alertStyles` rather than inside its bitmask. The pillar's filled dots are
**always** white, trip or not; there is no equivalent of the dial's
`STYLE_RING` toggle for it; per the brief, the background does the alerting
instead. (This dispatch is on `layoutStyle`, not a bitmask flag —
`STYLE_BACKGROUND`, floated in the original draft of this section, turned out
to be unnecessary once the layout choice itself became the switch.)

**Explicit config-screen choice, not resize-detection.** A new "SHAPE"
section in `activity_data_config.xml` (`layout_gauge` / `layout_pillar`
chips, same visual pattern as the existing style/color chips) sets
`layoutStyle` directly — the primary path this doc originally recommended.
`WidgetRenderer.build()` branches on `snapshot.layoutStyle` before doing any
size math. No second `AppWidgetProviderInfo` was added and
`data_widget_info.xml`'s bounds are unchanged (110–250dp, resizable both
axes) — a pillar is achieved today by dragging the tile taller than it is
wide within the existing bounds (e.g. 110×250), not by placing a visually
distinct widget. A dedicated narrow-and-tall provider entry remains a
possible follow-up if 110dp-minimum width ever feels too wide for the
intended pillar proportions.
**`widget_data.xml` gained a `pillar_root` sibling to the existing dial
content, not a second layout file.** The four dial views (`meter`, the
readout `LinearLayout`, `alert_dot`, `alert_border`) were wrapped in a new
`dial_root` `FrameLayout`; `pillar_root` sits beside it holding a single
`pillar_grid` `ImageView` — card and dots arrive as one `PillarRenderer`
bitmap. An earlier round split them, with a tinted `pillar_bg.xml` drawable
behind the grid bled out past the layout's padding by negative margins; the
host clipped that overhang and took the pill's rounded ends with it, so the
drawable is gone and the card is drawn in the bitmap. `paintPillar` also
clears `widget_root`'s background and padding, both of which the gauge
branch gets back for free by re-inflating the layout.

`WidgetRenderer` toggles `View.GONE`/`VISIBLE` on the two roots per build,
since RemoteViews can't swap which layout XML is inflated per-instance
without a second provider.

**Colors landed as new, theme-invariant resources**, not split across
`values`/`values-night` like the rest of the tile: `pillar_surface`
(`#FF1B1B1B`), `pillar_dot_inactive` (`#FF303030`), and
`pillar_dot_inactive_alert` (`#59FFFFFF`, used only once tripped, so the grid
stays legible against `nt_red`/whichever alert color is chosen instead of
nearly disappearing at the dial's usual 18%-alpha inactive value). The pillar
is modeled on one specific dark reference widget, not on the tile's own
day/night surface — see the color table above for the reasoning already
recorded there.

**Files touched:** `DataSettings.kt`, `UsageSnapshot.kt`, `WidgetRenderer.kt`,
`ConfigActivity.kt`, `widget_data.xml`, `activity_data_config.xml`,
`colors.xml`, `strings.xml`. **Files added:** `PillarRenderer.kt`.
