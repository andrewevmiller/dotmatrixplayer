# Building Dot Matrix Data

A session log: a 2 × 2 Android home-screen widget for monthly mobile data,
in Nothing OS's design language, built from scratch and installed on a phone.

---

## The brief

> Build an Android widget using the Nothing OS design language that displays
> monthly mobile data usage in their font. Make the rollover date for usage
> customizable. Give it a customizable indicator for when data is over a usage
> limit. Make it fit onto a 2×2 widget. Use the fonts located in
> `Nothing App Dev/Fonts`. Give it a meter as well as text showing the data usage.

## What was built

A new Gradle module, **`:datawidget`**, inside the existing
`DotMatrixMediaWidget` project — its own `applicationId`, its own APK, no code
shared with the media widget next door. It lives in that project because it is
the same design language and the same build, and because everything interesting
about drawing a Nothing typeface into a widget had already been worked out there.

```
        · · · · · · ·
    ·                   ·
  ·                       ·          ●   <- over-limit indicator
 ·          3.6 GB         ·
 ·        LIMIT 30 GB      ·
  ·        27D LEFT       ·
    ·                   ·
        ·         ·
```

**47 files**, ~1,400 lines of Kotlin and XML. Release APK: **124 KB**.

### Customisable, as asked

| setting | range | default |
|---|---|---|
| rollover day | 1–31, clamped in short months | 1st |
| allowance | 0–2000 GB; 0 = no limit | 20 GB |
| over-limit indicator | corner dot / dial / number / border — any combination | dot + dial |
| trips at | 25–200% of the allowance | 100% |
| colour | red, amber, white | red |

The indicator is a **bitmask, not a single choice** — the four forms are
independent, and someone who wants the dial and the border to both go red
shouldn't have to pick.

---

## Decisions worth recording

**Settings are global, not per-tile.** The widget declares a configuration
activity, which normally implies per-instance state. A phone has one data plan;
two tiles disagreeing about when the month rolls over would be a bug the user
had to debug, not a feature.

**minSdk 29, where the sibling module sits at 26.** `NetworkStatsManager` with a
null `subscriberId` means "every subscriber on this device" only from Android Q.
Before that the same query wanted a real subscriber id *and* `READ_PHONE_STATE`
to read it — which Q then made unobtainable anyway by putting `getSubscriberId()`
behind carrier privileges. The Q+ path asks for strictly less.

**Only two typefaces ship, and Ndot 77 is not one of them.** The media tile
carries Ndot 77 JP Extended because track titles arrive from other apps in
whatever script they like. This tile authors every string it draws — digits,
`GB`, `MB`, a handful of English words — so it doesn't need 21,000 codepoints and
doesn't pay 14 MB for them. That is most of the difference between a 1.5 MB APK
and a 124 KB one.

**Ndot 57 Aligned specifically**, because it is the tabular cut: all ten digits
at 60 units, where Ndot 77's `1` is 40 against 60 for everything else. A figure
that changes as data is used would otherwise visibly breathe whenever a `1`
entered or left it.

**GB is decimal** — 1,000,000,000 bytes — because that is what a carrier sells
and bills, and what Android's own Settings has counted since Marshmallow.
Matching the bill matters more here than matching RAM.

**The dial opens at the bottom.** Not decoration: the readout stacks three lines
*inside* the arc, and a closed circle would either crowd them or force the type
down a size.

**Red is reserved.** The sibling widget spends its one accent on the scrub
playhead. Here nothing is red until the limit is crossed — the head dot on the
dial stays white — so the colour means exactly one thing.

**The refresh is an inexact alarm.** `updatePeriodMillis` has a 30-minute floor,
and a data tile that can be half an hour stale is one you stop trusting: the
moment you look at it is the moment you just streamed something. So a 15-minute
inexact alarm does the work, with `updatePeriodMillis` underneath it as the floor
that survives a reboot without needing re-arming. Exact alarms would need
`SCHEDULE_EXACT_ALARM` — a permission the user grants by hand — to buy nothing:
this number moves in megabytes, not seconds.

---

## The constraint that shapes everything

**A widget cannot render a custom font from XML.** `AppWidgetHostView` inflates
through a context created with `CONTEXT_RESTRICTED`, and `TextView` only resolves
an `android:fontFamily` resource when `!context.isRestricted()`. In a widget that
test fails, so the attribute is skipped in silence — no exception, nothing in the
log, just the system face where the custom one should be. Setting a `Typeface`
from code doesn't help either: `TypefaceSpan` parcels a family *name*, not a face.

So every label on the tile is drawn to a bitmap and shipped as an `ImageView`. A
Gradle check (`verifyWidgetHasNoTextViews`, wired into `preBuild`) fails the build
if a `TextView` reappears in the widget layout, because that regression is
invisible until someone looks at a phone.

The settings screen is an ordinary activity in an unrestricted context, so it
*does* use `android:fontFamily` — that is the whole difference between
`activity_config.xml` and `widget_data.xml`.

---

## Verification, in three passes

### 1. Rendering the real font at real size

A 2 × 2 tile is 110 × 110dp. Rather than estimate whether the type fits, the tile
was rebuilt as a standalone HTML page with the actual `.otf` embedded, rendered
at 1× and 3× across eight states, and measured.

It caught three things a build never would:

- **The readout crowded the dial.** The width budget had been set against the
  tile, but the readout sits inside a *circle* — the budget narrows the further a
  line is from the middle.
- **`TAP TO GRANT` overflowed the arc** at 64.6dp against 64.5dp of clearance.
- **The corner dot merged with the arc.** Set in even slightly, it landed ~6dp
  off the dial and, once the dial went red too, read as a dot that had fallen off
  the scale.

Measured clearances, which became ratios in the renderer:

| line | band from dial centre | clear width |
|---|---|---|
| readout | −22 … +2dp | 64.5dp |
| limit | +4 … +12dp | 74.3dp |
| cycle | +14 … +22dp | 64.5dp |

The type now steps down until it fits, so `1024 GB` and `LIMIT 1100 GB` stay
inside the arc. The corner dot moved hard into the corner: 17dp clear.

Also fixed here: the no-access state had been showing `--` where the figure goes,
which floated and read as a tile that had failed to draw. An instrument saying
"not reading right now" is the wrong sentence when there is nothing to read at
all — the line is now dropped entirely and the two small ones centre in the dial.

### 2. Unit tests for the rollover

The rollover is the setting the widget is built around, and the one place where
being a day out is both easy and invisible: the tile would still show a plausible
number, just for the wrong window.

**11 tests**, all passing — short-month clamping (a 31st rollover in February),
the clamp releasing again in March, leap years, the year boundary, the midnight
edge, and the days-left count. JUnit is the only dependency and it doesn't ship.

### 3. Installing it on the phone

Installed to a Nothing Phone (A024, Android 16) over wireless adb. It came up
reading real data — 3.6 GB against a 30 GB allowance, rollover on the 20th.

Three more bugs, none of which show up in a build, in lint, or in a layout
preview:

- **The headline was drawn under the status bar.** Android 15 forces edge-to-edge
  for anything targeting SDK 35+, and this app never asked for it. Fixed with
  `fitsSystemWindows="true"` — the pre-AndroidX lever, which is the right one
  here given no AndroidX and `WindowInsets.Type` being API 30 against minSdk 29.
- **The keyboard opened over the preview on launch.** The allowance field is the
  first focusable view in the tree, so it won focus immediately. Fixed with
  `windowSoftInputMode="stateHidden"` plus `focusableInTouchMode` on the root.
- **The minus key read as an ellipsis.** It had been set as a true minus
  (U+2212) on the reasoning that a hyphen would look short beside the plus.
  Backwards for this face: rendering the candidates showed the plus is a 3 × 3
  dot glyph whose crossbar is *exactly* the ASCII hyphen, while U+2212 is five
  dots wide.

### One thing that went wrong

Scrolling the settings screen was attempted with a blind coordinate swipe. It
landed outside the app and opened a system share sheet on the home screen, with
contacts visible. It was dismissed with a back press, nothing in it was tapped,
and nothing was shared. Subsequent checks relaunched the activity instead of
driving the screen.

---

## Also worth knowing

Every entry point into this app is a broadcast, and `onReceive` runs on the main
thread. All of them hand the stats query to a shared worker paired with
`goAsync()` — boot is both when that service is slowest to answer and when the
app is likeliest to be killed for taking its time.

Because the install was a plain `adb install`, the installer is `null`. If usage
access is ever revoked, re-granting it may hit Android's Restricted Settings:
**Settings → Apps → Dot Matrix Data → ⋮ → Allow restricted settings**.

## Final state

| check | result |
|---|---|
| debug build | passes |
| release build (R8) | passes, 124 KB unsigned |
| unit tests | 11/11 |
| lint | clean but for 5 benign notes |
| on device | installed, running, reading live data |

Full module documentation is in `datawidget/README.md`.

*The typefaces are Nothing's own. Fine for a personal build; anything further
than your own device is a licence question worth checking.*

---

## Chapter 2: reading the actual brand guideline

A later session asked for the design language to be checked against Nothing's
own brand PDF (`nothing_brand_guidelines.pdf`) rather than against inherited
assumptions, with the findings written up in `docs/BRAND_LANGUAGE.md`. That
doc already existed and the media widget had already been brought in line with
it; the data widget had not.

### The border, first

A small, separate request landed mid-session: remove the border around the
widget. That was `widget_bg.xml`'s 1dp `widget_stroke` outline — the card's
own edge, not the customisable over-limit border (`widget_border.xml`, a
distinct feature the user can still turn on). Removed cleanly; the two were
easy to tell apart because one is state-conditional and colour-driven, the
other draws unconditionally.

### What the guideline actually says, versus what the data widget did

Two rules, both explicit in the PDF:

- **NDot is scoped to product names and the logotype** — "should not be used
  as a normal font."
- **NDot never takes manual letter-spacing** — "VA is set to `>0<`" — because
  it's a fixed matrix advance, not a proportional face.

The media widget already followed both: NType82 for everything but the
sibling app's own headline ("Dot Matrix Player"), which sits in NDot at zero
tracking. The data widget had the roles **inverted**:

- The config screen's headline — "Dot Matrix Data", the one place that *is*
  a product name — was set in `ntype82_headline`, the wrong family entirely.
- Everything else on the tile and the config screen — the dial readout, the
  limit/cycle lines, section labels, chips, steppers, the status label, both
  buttons — was set in NDot 57 Aligned, with manual tracking (0.10–0.18 em)
  layered on top of a face the guideline says should never carry it.

Both rules broken, in opposite directions, everywhere at once.

### The fix

- Headline: `ntype82_headline` → `ndot57_aligned`, tracking dropped to zero.
  The now-unused `ntype82_headline.otf` cut was deleted from the module.
- Everything else — `TextRenderer.renderReadout`, the dial's limit/cycle
  lines in `WidgetRenderer`, and every `ndot57_aligned` reference in
  `activity_config.xml` and `styles.xml` (section labels, chips, steppers,
  status label, both buttons) — moved to NType82. Existing tracking values
  were kept, because the guideline's own spacing chart licenses NType to
  carry it; NDot never could.

One question this raised and answered: does the dial's big figure still need
a tabular face, now that it's not NDot 57 Aligned? The original reason for
picking Aligned over Ndot 77 for *any* ticking digit was to stop it visibly
resizing as it changed. But the data widget repaints on a 15-minute alarm, not
once a second like the media widget's elapsed-time clock — there's no ticking
cadence for a proportional digit to breathe against, so the move cost nothing.
`docs/BRAND_LANGUAGE.md` was updated to record this reasoning, not just the
outcome.

### Verified on the phone, not just in a build

Debug build passed, all 11 unit tests still passed, the `verifyWidgetHasNoTextViews`
check still passed. Then reinstalled to the same Nothing Phone and screenshotted
the config screen to confirm by eye — "DOT MATRIX DATA" now renders as the
dot-matrix wordmark, and every other line (readout, limit, cycle, labels,
buttons) renders in the plain face, all-caps, still tracked. Two throwaway
snags on the way to that screenshot, both connection/screen-state issues, not
app bugs: a wireless-adb session had dropped and needed reconnecting, and the
first screenshot came back solid black because the phone's screen was asleep.

### Roboto, added as a scoped exception

A later, smaller request: permit Roboto for body text. Rather than a blanket
allowance, `docs/BRAND_LANGUAGE.md` now carries it the same way as the doc's
other departures — narrowly scoped. NType82 stays the default and should be
reached for first; Roboto is only sanctioned for a body-copy run where NType82
is unavailable or impractical (e.g. a system-rendered surface outside the
app's control). It explicitly does not extend to headlines, labels, buttons,
or any wordmark/product-name use. No code changes accompanied this — nothing
in either widget uses Roboto today; the doc change only opens the door for
a future body-text case.

### Final state, chapter 2

| check | result |
|---|---|
| debug build | passes |
| unit tests | 11/11 |
| `verifyWidgetHasNoTextViews` | passes |
| on device | reinstalled, headline and body text confirmed by screenshot |
| `docs/BRAND_LANGUAGE.md` | updated: NDot/NType roles corrected, Roboto exception added |
