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

Everything below was worked out interactively against a photo reference (an
industrial scaffolding shot with a dark dot-grid pill overlaid on it) and
against live mockups, not derived from the code. Treat the pixel numbers as
close estimates read off that reference, scaled to a widget-appropriate size —
not an exact pixel transcription.

### Grid

| property | value |
|---|---|
| columns × rows | 3 × 14 (42 dots total) |
| dot shape | true circle, fixed diameter (not a stretched grid cell) |
| dot diameter | ~6dp |
| gap between dots (both axes) | ~2dp — tighter than the dot itself, matching the reference's densely packed look |
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
| shape | rounded rectangle, corner radius large enough to read as a pill/stadium end |
| padding around the grid | ~24dp on all sides, uniform — the grid sits as a visibly inset block, not edge-to-edge |
| overall size | proportioned so the 3×14 grid plus padding reads as a narrow vertical pillar (taller than wide), matching a 1×2 widget cell rather than the existing 2×2 square |

### Colors

| element | normal state | tripped state |
|---|---|---|
| card background | `#262626` (dark charcoal — matches `widget_bg`/`widget_surface`, not pure black) | `nt_red` — `#FFC8102E`, already defined in `colors.xml` as Nothing's brand red |
| filled dot | white (`meter_active` / full ink) | **white, unchanged** |
| unfilled dot | `#404040` — a subtle step up from the background, low contrast, matching the reference photo's barely-visible resting dots | `rgba(255,255,255,0.35)` — brighter than the normal-state gray so it stays legible against red instead of nearly disappearing |

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
`dial_root` `FrameLayout`; `pillar_root` sits beside it with `pillar_card_bg`
(a solid white rounded-rect drawable, `pillar_bg.xml`, tinted via
`setColorFilter` — the same technique `alert_dot`/`alert_border` already use)
and `pillar_grid` (the `PillarRenderer` bitmap). `WidgetRenderer` toggles
`View.GONE`/`VISIBLE` on the two roots per build, since RemoteViews can't
swap which layout XML is inflated per-instance without a second provider.

**Colors landed as new, theme-invariant resources**, not split across
`values`/`values-night` like the rest of the tile: `pillar_surface`
(`#FF262626`), `pillar_dot_inactive` (`#FF404040`), and
`pillar_dot_inactive_alert` (`#59FFFFFF`, used only once tripped, so the grid
stays legible against `nt_red`/whichever alert color is chosen instead of
nearly disappearing at the dial's usual 18%-alpha inactive value). The pillar
is modeled on one specific dark reference photo, not on the tile's own
day/night surface — see the color table above for the reasoning already
recorded there.

**Files touched:** `DataSettings.kt`, `UsageSnapshot.kt`, `WidgetRenderer.kt`,
`ConfigActivity.kt`, `widget_data.xml`, `activity_data_config.xml`,
`colors.xml`, `strings.xml`. **Files added:** `PillarRenderer.kt`,
`pillar_bg.xml`.
