# Nothing Health

A resizable Android home-screen widget showing today's steps, last night's
sleep and — if you want them — heart rate, blood oxygen and respiratory rate,
read from Health Connect and drawn in the same monochrome dot-matrix idiom as
the media and mobile-data tiles next door.

It is two tiles, not one stretched. Below four cells wide it is a dial with a
figure in it; at four and above it is a list of rows with a dot rail under
each.

```
   2 x 2                          4 x 2
                                  ● HEALTH                    08:42
      · · · · · · ·               ────────────────────────────────
  ·                   ·             ▲  STEPS               8,432
 ·       8,432 STEPS   ·             ····························
 ·       GOAL 10,000   ·            ☾  SLEEP              7H 12M
  ·      SLEEP 7H 12M ·              ····························
      ·         ·
```

## The four things this build added

**Resizing.** `minResizeWidth`/`minResizeHeight` down to the 110dp square,
`maxResize` out to 5 × 4, and `onAppWidgetOptionsChanged` wired to repaint —
which is what actually makes it work, because every label on this tile is a
bitmap drawn at a chosen size and every rail is drawn at its on-screen pixel
width. A tile that is not repainted after a drag keeps the bitmaps it was
given at its old size. The type grows with the tile but slower, and never
below its 2 × 2 size.

**The visual language.** The near-black `#1B1B1B` card at 26dp, the white /
60% / 36% text ramp, the dot arc and the dot rails, one signal colour used
only for state — Nothing's own N-Red (`#C8102E`), not an invented one. These
are the same values as `:datawidget` and `:app` in the Music Player project,
so the three tiles sit together on one home screen rather than merely
matching on colour. See [docs/BRAND_LANGUAGE.md](docs/BRAND_LANGUAGE.md) for
how the guideline itself was read, including a color and typography pass
that corrected this project's first draft.

**The settings screen.** Sleep reading, sleep window, unstaged sessions,
per-metric permissions, background reads, goals, which metric the dial shows,
the goal indicator, the accent, and where a tap goes. It renders live copies of
*both* layouts at the top through `WidgetRenderer.buildPreview`, so a setting
can be seen taking effect on the size you are not holding.

**The typefaces.** NType82 for everything the tile and the settings screen
say — labels, figures, buttons — because Nothing's own guideline scopes NDot
to product names and the logotype: "Dot55 should not be used as a normal
font." The one NDot 57 Aligned label is the settings headline, "Nothing
Health", which *is* this app's product name.

## What counts as sleep

There is no single sleep total, which is why this is a setting rather than a
constant. The same night from the same tracker reads an hour or more apart
depending on which question you ask:

| reading | what it adds | on a typical night |
|---|---|---|
| **ASLEEP** | light + deep + REM + plain sleeping | 7H 30M |
| **IN BED** | the whole session, end to end, less any out-of-bed stage | 8H 00M |
| **RESTFUL** | deep + REM | 3H 30M |
| **DEEP** | deep alone | 2H 00M |

ASLEEP is the default and is what most trackers print as your sleep duration.

**Sessions with no stages.** Plenty of apps write a bare `SleepSessionRecord`
with no breakdown at all. `COUNT` takes the whole session as asleep, which is
what your tracker's own app usually shows; `IGNORE` leaves it out so the figure
only ever comes from staged data. RESTFUL and DEEP ignore them whatever the
setting says — there is nothing in an unstaged session to call deep.

**Overlaps are merged, not summed.** Stages inside one session are written
overlapping by a second or two at their boundaries, and a watch and the phone
it is paired to will both write the same night in full. Summed raw, a
seven-hour night reads as fourteen. `SleepMath` collects intervals, clips them
to the window, merges them and then measures.

### The window

| window | span |
|---|---|
| **NIGHT** (default) | noon yesterday to noon today, never running past now |
| **24H** | a rolling day back from now |
| **TODAY** | local midnight to now — the same window the step count uses |

Noon rather than midnight, because a night crosses midnight: anchored at
midnight, sleep from 23:40 to 07:00 lands in two windows and is counted
properly in neither. Anchored at noon the whole night sits in one window, a
glance at 07:00 shows the night that has just ended, and a nap this afternoon
does not join it — which is what makes it *night* rather than *the last day*.

The cost is that a nap taken yesterday afternoon is inside the window. 24H and
TODAY are there for anyone that bothers.

Steps are always the calendar day so far, local midnight to now. That is how
every step counter on the phone defines a day and the only definition under
which this tile and your health app agree.

## Permissions

Health Connect grants one read at a time, so *enabled* and *granted* are two
different questions and the screen shows both: a chip is selected when the tile
wants that metric, and dimmed while it is still waiting for the grant. A row on
the tile says `NO ACCESS` per row rather than one banner across the whole
thing, because steps can be live while oxygen is still waiting.

Only the enabled metrics are ever asked for. Putting three reads the user did
not want in front of them on the grant sheet is how an app teaches someone to
deny the lot.

### Background reads are the one that matters

`READ_HEALTH_DATA_IN_BACKGROUND` is granted separately and is the one most
people skip. Without it Health Connect answers only while something of ours is
on screen — which a home-screen tile never is — so every repaint after the
settings screen closes comes back empty. The tile would show real figures while
you looked at the settings and dashes for the rest of the day, which reads as a
broken widget rather than as a missing permission.

`LastGood` covers that case: the last figure each metric returned, kept for 36
hours, shown with **the time it was read** rather than the time it was drawn,
so an old number looks old.

## Why every label is a bitmap

**A widget cannot render a custom font from XML.** `AppWidgetHostView` inflates
through a context created with `CONTEXT_RESTRICTED`, and `TextView` only
resolves an `android:fontFamily` resource when `!context.isRestricted()`. In a
widget that test fails, so the attribute is skipped in silence — no exception,
nothing in the log, just the system face where the custom one should be.
Setting a `Typeface` from code does not help either: `TypefaceSpan` parcels a
family *name*, not a face.

So every label on the tile is drawn to a bitmap by `TextRenderer` and shipped
as an `ImageView`. A Gradle check (`verifyWidgetHasNoTextViews`, wired into
`preBuild`) fails the build if a `TextView` reappears in either widget layout,
because that regression is invisible until someone looks at a phone.

The settings screen is an ordinary activity in an unrestricted context, so it
does use `android:fontFamily` — that is the whole difference between
`activity_config.xml` and the two widget layouts.

### NType82, not Ndot, for the figures

The first draft of this tile set every figure in Ndot 57 Aligned — the
tabular cut, chosen so a step count would not visibly breathe whenever a `1`
entered or left it. That reasoning was sound but the face was wrong: Nothing's
own guideline scopes Ndot to product names and the logotype ("Dot55 should
not be used as a normal font"), and a step count is not this app's name. See
[docs/BRAND_LANGUAGE.md](docs/BRAND_LANGUAGE.md) for the full correction —
every figure on the tile is NType82 now, and the one Ndot 57 Aligned label
left is the settings screen's own headline, which has no digits in it and so
never faced the breathing problem Aligned was chosen to solve.

Ndot 77 JP Extended was never bundled here at all. It exists in the sibling
media widget to cover track titles arriving from whatever app is playing, in
whatever script it used — this tile authors every string it draws itself, out
of digits, five metric names and a handful of English words, so there is no
foreign text to fall back for.

## Fitting three lines inside a circle

On the square tile the readout lives *inside* the dial, so its width budget is
not one number — it narrows the further a line sits from the dial's middle.
`WidgetRenderer` holds the chord across the dial's clear inner radius at each
line's own band as a ratio of the content square, and steps the type down until
it fits. Without them the type is measured against the tile and merely *looks*
like it fits: `GOAL 10,000` and `SLEEP 12H 45M` both run into the arc.

The value and its unit are one bitmap rather than two views, because they are
set at different sizes on a shared baseline and RemoteViews has no baseline
alignment to offer across children.

## How the wide tile decides how many rows to show

Rows are weighted in XML, so hiding the ones that do not fit is all it takes —
the survivors spread over the whole height instead of stacking at the top and
leaving a hole underneath. The renderer works out how many rows the height can
hold at 32dp each, caps that at the number of enabled metrics and at the five
slots the layout has, and hides the rest.

Type then scales on both axes and takes the smaller answer. Width alone would
let a short wide tile set 20sp figures into a 32dp row and clip their
descenders; height alone would leave a 5 × 2 tile setting 2 × 2 type across
320dp.

## Where a tap goes

Settings, Health Connect, or Fitbit — and the target is resolved at *paint*
time, so the widget holds a PendingIntent aimed straight at it. The obvious
alternative, broadcasting to our own provider and working it out on the tap,
has to call `startActivity` from a receiver, which is the launch background
activity launch restrictions exist to stop. A tile that cannot read anything
goes to the settings screen whatever the setting says: it is saying
`TAP TO GRANT`, and sending that tap to Fitbit would be a lie.

## Keeping it fresh

`updatePeriodMillis` has a floor of 30 minutes, and a step count that can be
half an hour stale is one you stop trusting. `RefreshScheduler` runs a
15-minute inexact alarm while at least one tile is placed, with
`updatePeriodMillis` underneath it as the floor that survives a reboot.

`RTC`, not `RTC_WAKEUP`. A health widget waking the phone every quarter of an
hour through the night would be measuring sleep by interrupting it.

## No Compose

The tile cannot use it — a widget is RemoteViews, and RemoteViews has a fixed
allowlist of framework views. The settings screen could, but it is a scroll of
chips and steppers the framework draws perfectly well, and the whole Compose
runtime for one screen costs the APK more than that screen is worth. What is
left is the Health Connect client, coroutines for the suspend API it exposes,
and `androidx.activity` for the one thing the platform genuinely does not
offer: Health Connect's permission contract.

The release APK is about 920 KB unsigned.

## Building

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:testDebugUnitTest
```

`SleepMathTest` covers the part of this app most likely to be quietly wrong.
Every case in it produces a *plausible* number when it breaks — an hour out, a
night double counted, a nap in the wrong day — so none of them can be checked
by looking at a phone in the morning. The four readings, unstaged sessions,
two writers on one night, overlapping stages, both window edges, and a timezone
west of UTC.

Built against AGP 9.3.1, compileSdk 36, minSdk 28.

## Licence note

The typefaces are Nothing's own. Fine for a personal build; if this goes
further than your own device, check what their licence permits — that call is
yours, not the code's.
