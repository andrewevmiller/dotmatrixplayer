# Dot Matrix Data

A 2 × 2 Android home-screen widget showing the mobile data used since your plan
last rolled over, drawn in the same monochrome dot-matrix idiom as the media
tile next door. A dot dial for the shape of it, a figure for the number, your
own rollover date, and an over-limit indicator you choose the form of.

```
        · · · · · · ·
    ·                   ·
  ·                       ·          ●   <- over-limit indicator
 ·         12.4 GB         ·
 ·        LIMIT 20 GB      ·
  ·        12D LEFT       ·
    ·                   ·
        ·         ·
```

It is a separate app from `:app` — its own `applicationId`, its own APK, no code
shared. It lives in this project because it is the same design language and the
same build, and because everything interesting about drawing a Nothing typeface
into a widget was already worked out here.

## Installing

```bash
./gradlew :datawidget:assembleDebug
```

```bash
adb install datawidget/build/outputs/apk/debug/datawidget-debug.apk
```

Then open the app once to grant usage access, and add the widget from the
launcher's widget picker. The tile works before you grant anything — it just has
no number to show, and says so.

Built against AGP 9.3.1 / Gradle 9.5 / Kotlin 2.2.10, compileSdk 36, minSdk 29.
The release APK is ~191 KB unsigned.

## Why usage access

`NetworkStatsManager` is the only public route to a windowed data total. The
`TrafficStats` counters are cheaper but reset on reboot and cannot be given a
date range, so they cannot answer "since the 14th" — which is the only question
this widget asks.

That API is gated behind **usage access**: `PACKAGE_USAGE_STATS` is a
signature-level permission, so the manifest entry grants nothing by itself. It
only makes the app appear in **Settings → Apps → Special app access → Usage
access**, where the real grant is an appop the user flips. `MobileData`
therefore checks the op rather than the permission.

There is no `INTERNET` permission and no `READ_PHONE_STATE`. The query passes a
null `subscriberId`, which from Android Q means "every subscriber on this
device" — the right answer on a dual-SIM phone, and the only value a non-carrier
app can pass anyway, since `getSubscriberId()` went behind carrier privileges in
the same release. Nothing leaves the device.

That null subscriber id is also why this module sits at minSdk 29 while `:app`
sits at 26: before Q the same query wanted a real subscriber id and the
permission to read it, which would be a second code path asking for more.

## Settings

All five are global rather than per-tile, and the configuration activity edits
that one shared set. A phone has one data plan; two tiles disagreeing about when
the month rolls over would be a bug the user had to debug.

| setting | default | notes |
|---|---|---|
| rollover day | 1 | 1–31. Months without that date roll over on their last day. |
| allowance | 20 GB | 0 turns the limit off and the dial goes inert. |
| indicator | corner dot + dial | Four independent forms, any combination. |
| trips at | 100% | Below 100 warns early, above 100 allows an overdraft. |
| colour | red | Red, amber or white. |

The four indicator forms are a **corner dot**, the **dial** switching colour, the
**number** switching colour, and a **border** around the card. They are a bitmask,
not a single choice.

The corner dot sits hard into the tile's top-right corner with no margin of its
own. Set in even a little, it lands about 6dp off the arc, and when the dial has
gone red too it stops reading as an indicator and starts reading as a dot that
fell off the scale.

GB here is decimal — 1,000,000,000 bytes — because that is what a carrier sells
and bills, and what Android's own Settings has counted since Marshmallow.

## Typefaces

Three roles, defined once in `Typography.kt` and mirrored by two base styles in
`values/styles.xml` for the settings screen, which cannot read a Kotlin
constant. Every caller names a **role**, never a face.

| role | resource | face | used for |
|---|---|---|---|
| Body | `geist.ttf` | Geist (variable, `wght` 400) | the readout and its unit, the limit and days-left lines, settings copy and the figures being edited |
| Accent | `ntype82.otf` | NType 82 | the lines shown when there is no reading to give, plus every control on the settings screen |
| Wordmark | `ndot57_aligned.otf` | Ndot 57 Aligned | the settings headline — and nothing else |

**The tile switches between the first two by state.** With usage access granted
it is reporting the plan — `12.4 GB`, `LIMIT 20 GB`, `12D LEFT` — and those are
data readouts, so Body. Without it the tile has no reading to give and is
describing *itself*, and `TAP TO GRANT` is a status indicator, so Accent. The
colour on that line already switched for exactly this state, so the face
switching with it is a distinction the tile was drawing anyway. The score tile
next door does the same thing for its empty states.

Geist is a variable font — one `wght` axis, default instance 400, verified
against its own `fvar` table — so it loads as Regular with no pinning. Don't set
bold on it: a `Paint` told to embolden a variable font synthesises a fake bold
rather than moving the axis, and this tile sets type as small as 8sp.

**A widget cannot render a custom font from XML.** `AppWidgetHostView` inflates
through a context created with `CONTEXT_RESTRICTED`, and `TextView` only resolves
an `android:fontFamily` resource when `!context.isRestricted()`. In a widget that
test fails, so the attribute is skipped in silence — no exception, nothing in the
log, just the system face where the custom one should be. Setting a `Typeface`
from code does not help either: `TypefaceSpan` parcels a family *name*, not a
face.

So every label on the tile is drawn to a bitmap by `TextRenderer` and shipped as
an `ImageView`. A Gradle check (`verifyWidgetHasNoTextViews`, wired into
`preBuild`) fails the build if a `TextView` reappears in `widget_data.xml`,
because that regression is invisible until someone looks at a phone.

The settings screen is an ordinary activity in an unrestricted context, so it
does use `android:fontFamily` — that is the whole difference between
`activity_config.xml` and `widget_data.xml`.

### No Ndot 77 JP Extended

The media tile ships Ndot 77 JP Extended as a **coverage** fallback, because
track titles arrive from other apps in whatever script they like. This tile
authors every string it draws, out of a vocabulary of digits, `GB`, `MB` and a
handful of English words — so it has no coverage problem to solve, does not need
the 21,000-codepoint face, and does not pay the 14 MB for it. That is most of
the difference between a 1.7 MB APK and a 191 KB one.

### What moving off the tabular cut cost

Nothing, but the reasoning is worth keeping. Ndot 57 Aligned is the tabular cut
— all ten digits at 60 units, where Ndot 77's `1` is 40 — and the original
argument for setting the readout in it was that a figure which changes as data
is used would otherwise visibly breathe whenever a `1` entered or left it.

That argument does not survive the repaint cadence. This tile updates on a
15-minute alarm, not once a second like the media tile's elapsed time, so a
proportional figure has no ticking rhythm to breathe against — by the time the
number changes you are looking at a fresh tile, not watching a digit reflow. The
guideline's scoping of Ndot to product names wins on a tie, and this is not even
a tie.

Where a figure *does* change under the eye, the answer is structural rather than
typographic: the score tile next door measures both scores into a shared column
width, which holds for one digit against two against three where a tabular face
only fixes the width of each glyph.

## Fitting a dial and three lines into 110dp

A 2 × 2 tile is 110 × 110dp — two 70dp cells less the 30dp the grid takes back.
10dp of padding leaves a 90dp square, and the dial fills it, so the readout has
to live *inside a circle*. Its width budget is therefore not one number: it
narrows the further a line sits from the dial's middle.

Measured against the dial's clear inner radius at each line's own band:

| line | band from centre | clear width |
|---|---|---|
| readout | −22 … +2dp | 64.5dp |
| limit | +4 … +12dp | 74.3dp |
| cycle | +14 … +22dp | 64.5dp |

`WidgetRenderer` holds those as ratios of the content square and steps the type
down until it fits. Without them the type is measured against the tile and merely
*looks* like it fits — `LIMIT 1100 GB` for a big plan, `TAP TO GRANT` when there
is nothing else to say, and any figure of four glyphs all run into the arc.

The value and its unit are one bitmap rather than two views, because they are set
at different sizes on a shared baseline and RemoteViews has no baseline alignment
to offer across children. Bottom-aligning them instead would line up the two
*descents*, which at 22sp against 9sp leaves the unit visibly sunk.

## Two things the settings screen has to ask for

**`fitsSystemWindows="true"`.** From Android 15, an app targeting SDK 35 or
above gets an edge-to-edge window whether it asked for one or not, and this one
did not — without it the headline is drawn underneath the status bar and
collides with the clock. This is the pre-AndroidX way of taking the insets as
padding, which is the right one here: the module has no AndroidX to reach for,
and `WindowInsets.Type` is API 30 against a minSdk of 29.

**`windowSoftInputMode="stateHidden"`, plus `focusableInTouchMode` on the
content root.** The allowance field is the first focusable view in the tree, so
it wins focus on open and brings the keyboard up over the preview — which is the
one thing on this screen worth seeing first.

Both were found by installing on a phone and looking at it. Neither shows up in
a build, in lint, or in a layout preview.

## Keeping the number fresh

`updatePeriodMillis` has a floor of 30 minutes, and a data tile that can be half
an hour stale is one you stop trusting — the moment you look at it is the moment
you have just streamed something. So `RefreshScheduler` runs a 15-minute alarm
while at least one tile is placed, and `updatePeriodMillis` stays on underneath
it as the floor that survives a reboot without needing to be re-armed.

The alarm is deliberately **inexact**. An exact one would need
`SCHEDULE_EXACT_ALARM`, which Android 13 makes the user grant by hand, to buy
nothing: this number moves in megabytes, not in seconds. `RTC` rather than
`RTC_WAKEUP`, too — if the screen is off, nobody is reading the tile.

Every entry point here is a broadcast, and `onReceive` runs on the main thread,
so all of them hand the stats query to `Background`, which pairs a single worker
thread with `goAsync()`. Boot is exactly when that service is slowest to answer
and exactly when the app is most likely to be killed for taking its time.

## What is tested

`CycleMathTest` covers the rollover, which is the setting this widget is built
around and the one place where being a day out is both easy and invisible — the
tile would still show a plausible number, just for the wrong window. Short months
clamping (a 31st rollover in February), the clamp releasing again in March, leap
years, the year boundary, the midnight edge, and the days-left count.

JUnit is the only dependency and it does not ship. Everything at runtime is
framework API.

```bash
./gradlew :datawidget:testDebugUnitTest
```

## Licence note

The typefaces are Nothing's own. Fine for a personal build; if this goes further
than your own device, check what their licence permits — that call is yours, not
the code's.
