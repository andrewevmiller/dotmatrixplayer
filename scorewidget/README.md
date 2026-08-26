# Dot Matrix Score

Live scores for the teams you follow, on a home-screen tile that comes in three
sizes, with every club's mark redrawn as a Glyph Matrix.

```
   · · · · ·           · · · · ·
 ·  · ■ ■ ■ ·  ·     ·  · ■   ■ ·  ·
·  ■ ■ ■ ■ ■  ·  KC  24 – 17  DEN  ·  ■   ■ ■ ·  ·
 ·  · ■ ■ ■ ·  ·     ·  · ■ ■ ■ ·  ·
   · · · · ·      3RD AND 7   · · · · ·
 NFL          CBS                    ●   <- live
 · · · · · · · ● · · · · · · · · · · ·   <- win probability
```

It is a separate app from `:app` and `:datawidget` — its own `applicationId`,
its own APK, no code shared. It lives in this project because it is the same
design language and the same build, and because everything interesting about
drawing a Nothing typeface into a widget was already worked out here.

## Installing

```bash
./gradlew :scorewidget:assembleDebug
```

```bash
adb install scorewidget/build/outputs/apk/debug/scorewidget-debug.apk
```

Then open the app once to pick your teams, and add the widget from the
launcher's widget picker. The tile works before you pick anything — it just says
`PICK YOUR TEAMS` and opens the settings menu when tapped.

Built against AGP 9.3.1 / Gradle 9.5 / Kotlin 2.2.10, compileSdk 36, minSdk 31.
The release APK is ~169 KB unsigned.

## The three sizes

One provider, three layouts, chosen in `WidgetRenderer.pickLayout` from the size
the launcher reports in the widget's options bundle. Height decides first,
because it is the dimension that actually gates what can be drawn.

| | 2×1 strip | 4×1 banner | 4×2 card |
|---|:---:|:---:|:---:|
| marks and score | yes | yes | yes |
| clock / period | — | yes | yes |
| team abbreviations | — | yes | yes |
| possession marker | — | yes | yes |
| down & distance / count | — | yes | yes |
| runners on base (drawn) | — | — | yes |
| broadcast network | — | — | yes |
| field position rail | — | — | yes |
| win probability | — | — | yes |
| top performer | — | — | yes |
| carousel pager | — | yes | yes |

The strip drops the clock and the abbreviations rather than shrinking them.
110 × 40dp less padding is 28dp of content — one row — and the marks *are* the
abbreviations at that size, which is where resampling a team's letters onto the
grid stops being a stylistic choice and becomes the reason the layout works.

## Glyph Matrix marks

Nothing's Glyph Matrix is a circular array of individually dimmable LEDs behind
the back glass, and everything shown on it is reduced to that grid first. This
is the same reduction, drawn onto the home screen instead. A club crest scaled
down is not the same thing as a club crest *resampled* — resampling throws away
every edge finer than a cell and leaves only the silhouette, which is the point.

A full-colour crest dropped onto one of these tiles would be the only saturated
thing on the home screen and would read as a sticker — the same failure
`AppGlyph` exists to avoid next door in `:app`.

**The grid is 13 × 13, not the hardware's 25 × 25.** At the size a mark actually
occupies — 34dp on the card, 22dp on the strip — a 25-grid cell is under a
physical pixel on the strip, and the dots stop being dots. 13 is the largest odd
grid whose cells still read as separate dots at 22dp; odd matters because a mark
with a vertical axis needs a centre column to sit on.

Marks arrive two ways and the renderer never learns which it got:

1. **Hand-authored** — thirteen of them, as 13-row string art with four
   intensity levels (`.` `-` `+` `#`). Four levels rather than two because a
   silhouette reduced to on/off at this size loses everything that is not a
   mass: a horn, a beak, the open middle of a horseshoe.
2. **The abbreviation, set in NDot 57 Aligned and resampled onto the grid** —
   for everyone else. Five leagues is over 150 clubs, and hand-drawing them all
   would be 150 chances to draw one badly plus a maintenance bill every
   relocation.

The second route is not a consolation prize. NDot is itself a dot-matrix face,
so an abbreviation resampled onto a dot matrix is the same idea applied twice.
The source is **box-averaged, not point-sampled**: a dot typeface is mostly
holes, so a single sample per cell lands in a gap about as often as it lands on
ink and the abbreviation comes out moth-eaten.

The marks are **original dot compositions, not reproductions**. A club crest is
a trademark and tracing one onto a grid does not stop it being one. What is here
is the generic object the crest is *about* — a star, a crown, a wheel — drawn
from scratch at matrix resolution.

`TeamGlyphTest` checks all thirteen for miscounted rows, illegal characters,
cells outside the matrix circle, and a sane lit-cell share. `GlyphFrame.parse`
is deliberately forgiving at runtime — a wonky dot beats a widget that will not
draw — so it has to be strict somewhere, and that is the test. It caught three
real mistakes: the star's legs and the horns' tips reached into the corners
outside the rail, and the paw was drawn asymmetrically.

## Typography

Three roles, defined once in `Typography.kt` and mirrored in
`values/styles.xml` for the settings screen (which cannot read a Kotlin
constant). Every caller names a **role**, never a face.

| role | resource | face | what it covers here |
|---|---|---|---|
| Body | `geist.ttf` | Geist | scores, team abbreviations, down-and-distance, broadcast, stat line, settings copy |
| Accent | `ntype82.otf` | NType 82 | the clock, the league label, empty-state lines, chips, the status line |
| Wordmark | `ndot57_aligned.otf` | NDot 57 Aligned | the settings headline — and nothing else |

The split worth stating is that **a score is a data readout and the clock is a
time code**, so the two halves of the same line take different faces on purpose.
The score is the number you read; the clock is the thing telling you how much
game is left to change it.

### Geist is a variable font

`geist.ttf` is `Geist-VariableFont_wght` — one `wght` axis, 100–900, nine named
instances. A variable font renders at its **default instance** unless an axis is
set, so the only question that matters is what that default is. Read out of the
file's own `fvar` table: **400**, matching its `OS/2` `usWeightClass`. That is
Regular, the one weight this module wants, so nothing sets a variation and no
`wght` appears on any paint.

Don't reach for bold on it, tempting as a weight axis makes that. A `Paint` told
to embolden a variable font synthesises a fake bold rather than moving the axis,
which smears the letterforms at the 8–11sp most of this tile is set at.
Hierarchy comes from the type scale and the white/60%/36% ramp.

The italic cut is not shipped — nothing here is italic, and it is another 170 KB.
The variable font costs ~87 KB in the release APK (169 KB → 256 KB); it
compresses to about half its on-disk size.

`ntype82_headline.otf` is not shipped either: the one headline on the settings
screen is a product name and therefore NDot, so the headline cut had nothing
left to set — the same conclusion `:datawidget` reached.

`TypographyTest` guards the rule that actually matters and that this project has
already got wrong once: neither Body nor Accent may ever resolve to NDot, and
the two must stay different faces. Those failures are silent — the tile still
builds and draws, it just looks subtly off-brand to anyone who knows the
guideline.

### Coverage, checked rather than assumed

Every non-ASCII character the two faces have to carry was verified against the
fonts' own `cmap` tables before shipping, because a missing glyph is a tofu box
in the middle of a score and there is no build error for it:

- **Geist** — en dash (the score separator), em dash and `×` (settings copy),
  plus the ordinary punctuation in a stat line. All present.
- **NType 82** — `×` present, **but no arrow glyphs at all**: U+2191 and U+2193
  are simply not in the face.

That last one was a live bug. The reorder buttons on the favourites rows were
`↑` and `↓` set in the Accent face and would have rendered as missing-glyph
boxes. They are now vector drawables (`ic_move_up`, `ic_move_down`), along with
the remove and add controls, which also stops a *control* depending on the
coverage of whichever face a typographic role happens to point at.

**A widget cannot render a custom font from XML.** `AppWidgetHostView` inflates
through a context created with `CONTEXT_RESTRICTED`, and `TextView` only
resolves an `android:fontFamily` resource when `!context.isRestricted()`. In a
widget that test fails, so the attribute is skipped in silence — no exception,
nothing in the log, just the system face where the custom one should be. Setting
a `Typeface` from code does not help either: `TypefaceSpan` parcels a family
*name*, not a face.

So every label on the tile is drawn to a bitmap by `TextRenderer` and shipped as
an `ImageView`. A Gradle check (`verifyWidgetHasNoTextViews`, wired into
`preBuild`) fails the build if a `TextView` reappears in any of the three widget
layouts, because that regression is invisible until someone looks at a phone.

### Where NDot is, and is not

Nothing's guideline is explicit: NDot (Dot55/57) is the product-name and
logotype face, and *"Dot55 should not be used as a normal font."* So it appears
exactly twice in this module:

- the settings screen's headline, which is this app's *product name* and
  therefore the one place the industrial face is correct, set at **zero
  tracking** because the guideline says NDot's VA is `>0<` — it is a fixed
  matrix advance, not a proportional face. It is applied inline rather than
  through a style, so there is one place in the module it can be used;
- inside `TeamGlyphs`, which does not go through `Typography` at all. The
  letters there are rasterised and resampled into a dot matrix and never read
  as type, so routing them through a typographic role would imply they were one.

The tempting mistake is the score. A changing figure invites a tabular face, and
NDot 57 Aligned is exactly that — all ten digits at one width. But a score is a
data readout, so the rule sends it to Body. The jitter gets solved structurally
instead: `renderScoreline` measures both scores and centres each in a shared
column, which holds for one digit against two against three, where a tabular
face only fixes the width of each glyph. `TextRenderer` originally hardcoded
NDot here, copied from the data tile's readout; the role split caught it.

See `docs/BRAND_LANGUAGE.md`, which all three modules now follow.

### No NDot 77 JP Extended

`:app` ships the 14 MB JP-extended cut as a **coverage** fallback, because track
titles arrive from other apps in whatever script they like. This tile has no
coverage problem to solve: every string it draws is either authored here —
digits, a countdown, `PICK YOUR TEAMS` — or a team abbreviation from a feed of
North American leagues, which is ASCII. That is most of the difference between a
1.5 MB APK and a 169 KB one.

If the widget ever grew a league whose team names are not Latin, this is the
decision that would have to be revisited — and it would be a coverage decision,
not a style one.

## Colour

Hex values are Nothing's own, from the guideline, not invented:

- `nt_red` `#C8102E` (N-Red) — the one signal colour, reserved for *this is
  live* and the red zone.
- `nt_amber` `#FFC700` (N-Yellow) — the alternative accent.
- Surface stays near-black `#1B1B1B`, matching both siblings, because the
  guideline's foundation greys are light-mode print tones and a home-screen
  widget lives on a dark launcher.

**No border.** Both sibling tiles ring their card in a 1dp `#1FFFFFFF` hairline.
This one skips it: it carries far more on the card face (two matrices, a
scoreline, a rail) than either sibling, and a stroke around all of that reads as
one more line competing with the content rather than as a frame for it.

## Where the data comes from

ESPN publishes a scoreboard endpoint per league that espn.com itself reads. It
needs no key and no account, which is the only reason this widget can exist
without an onboarding screen — and it is **undocumented**, so the shape
`EspnClient` parses is observed rather than promised. Every field is read
through the optional accessors for that reason: a key ESPN renames should cost a
line on a tile, never a crash in a broadcast receiver.

One `HttpsURLConnection`, one `org.json` tree walk, no dependencies. A widget
pays for a shipped dependency twice — once in APK size, once in cold start on
every repaint — and a repaint here happens on an alarm with no UI in front of
it.

**What leaves the device:** a GET, with no body, no cookie, no identifier and no
account. The response is parsed, drawn and dropped. This is the only one of the
three tiles that touches the network at all; the other two answer their question
from the device.

## Keeping the score fresh without burning a battery

`updatePeriodMillis` has a floor of 30 minutes, and a score that can be half an
hour stale is a rumour. But the opposite mistake is worse here than on either
sibling, because this is the only one that costs data to refresh. So the
interval is a function of what is actually on the tile:

| state | interval |
|---|---|
| a game is live | 1 min |
| a game starts within the hour | 5 min |
| something today, not yet close | 30 min |
| nothing at all | 3 hours |

A single alarm, re-armed on every firing — not a repeating one, because the
interval changes as the games do and `setInexactRepeating` would hold whichever
interval was true when it was set.

Inexact at every tier. An exact alarm would need `SCHEDULE_EXACT_ALARM`, which
Android 13 makes the user grant by hand, to buy nothing: a score is not a
deadline. `RTC` rather than `RTC_WAKEUP`, too — if the screen is off, nobody is
reading the tile.

Every entry point is a broadcast and `onReceive` runs on the main thread, so all
of them hand the work to `Background`, which pairs a single worker thread with
`goAsync()`. Here that is not merely good manners: a network call on the main
thread throws.

## Which game gets the tile

A tile shows one game and you may follow eight teams across five leagues. On a
Sunday in autumn that is a dozen games at once; in July it is one.
`TeamFilter.rank` decides, in tiers:

1. a favourite, live
2. a favourite, starting within 24 hours
3. a favourite, just finished (6-hour recap window)
4. a favourite, later than tomorrow
5. a rival, live
6. a rival, otherwise

Position in your own favourites list breaks ties inside a tier — that list *is*
the priority queue, which is why it is stored newline-joined rather than as a
`StringSet` (which does not preserve order).

**Rivals only ever fill an empty tile.** They are dropped entirely whenever a
favourite has anything live, imminent or just finished. A rivalry tracker that
pushed a rival's game in alongside your own turns a tile about your team into a
tile about your division. Rivals cost no extra request — a rival is in the same
league by construction — which is why the feature is on by default.

Offseason filtering happens on the **favourites**, not on the games. Filtering
games would be a no-op that looked like a feature: a league that is not playing
has no games to filter. What the setting actually saves is the fetch.

Everything in `TeamFilter` is a pure function over its arguments — no `Context`,
no clock of its own. The rules are almost all *about* time, and the only way to
test a rule about time is to be able to lie about what time it is.

## The carousel is a tap, not a swipe

The brief asks for a horizontal swipe carousel. **A widget surface cannot
receive one** — not through RemoteViews and not through Glance either. The
launcher's own gesture detector owns horizontal movement, because that is how
the home screen pages; a widget gets clicks.

So the control is a pager: a dot per card, the current one lit, and a tap steps
forward. It is visibly a pager and it does what it looks like it does. An
invisible swipe target that sometimes flipped the home screen instead would not
be an improvement.

The lit dot is both brighter *and* bigger. At 3dp the difference between full
white and 36% white is a couple of pixels' worth of grey; size is the difference
that survives.

The carousel position is the one per-widget-id setting, and it is stored on disk
rather than held in memory — this process is started by a broadcast and killed
shortly after, so an in-memory index would reset every time you stopped tapping.

## Why not Jetpack Glance

The brief specifies Glance. Glance's `Text` still compiles down to a `TextView`
inflated through the same `CONTEXT_RESTRICTED` context, so it cannot carry a
Nothing typeface either — it would throw away the one thing this design language
cannot do without, and every label would have to become a bitmap anyway. Glance
also cannot detect a swipe, so it does not buy the carousel back. Against that
it adds a dependency to a process that exists to repaint a tile.

RemoteViews it is, the same as both siblings.

## Win probability

The feed carries its own number for some games, and where it does that is what
gets shown — ESPN's model has play-by-play and a decade of drives behind it.
This exists for the other games, which is most of them.

One idea applied five ways: the final margin is the current margin plus
everything still to come, and everything still to come is a random quantity
centred on zero.

```
P(home) = phi( (margin + homeEdge) / sigma(t) )
```

`sigma(t)` is how much the margin can still move and shrinks with the clock — a
ten-point lead in the first quarter and a ten-point lead with a minute left are
the same margin and completely different games. The per-league values are the
spread of final margins each sport actually produces: roughly two scores in the
NFL, four runs in a baseball game, a couple of goals in hockey. College football
is wider than the NFL because the talent gap is, so blowouts are ordinary.

The floor under `sigma` — 12% of the full-game spread, about one scoring play —
is what stops the last minute from being nonsense: a one-point lead divided by a
spread of zero is infinite confidence, which is wrong in every sport where
possession still exists.

It is deliberately a *scoreline* model. It does not know about possession, or
that a two-goal lead with an empty net is not a two-goal lead. What it gets
right is the shape, which is what a rail 60dp wide can actually communicate.

A scheduled game returns **null**, not 50%. A pre-game probability is a
statement about two rosters this model has never heard of, and printing 50% for
every fixture is worse than printing nothing because it looks like information.

## The settings menu

One screen serving three entrances — the launcher icon, a tap on the tile, and
the widget's own reconfigure item. Tapping anywhere on the card that is not the
pager opens it.

It renders a live 4×2 card at the top through the same `WidgetRenderer` path the
launcher uses, so a rendering bug shows up here rather than only on a home
screen, and every setting can be watched taking effect.

Settings are saved as they are touched rather than on the way out. A widget
configuration screen can be left by the back gesture, by the home key, or by the
system deciding it has waited long enough, and a Save button would lose to all
three. The activity answers `RESULT_OK` immediately for the same reason: a
cancelled configuration makes the host throw the widget away, which is a harsh
reading of a back gesture.

Everything is global except the carousel position. A phone has one set of
teams; a 2×1 strip beside a 4×2 card should be the same teams at two levels of
detail.

Adding a team re-fetches; reordering one does not — it re-ranks what is already
cached. This is the screen where somebody adds five teams in a row, and every
one of those would otherwise be a request.

## Notifications

`POST_NOTIFICATIONS` is deliberately **absent from the manifest**. All three
alert toggles are off by default, so declaring it at install time would be
asking for something the app may never use. `ConfigActivity` requests it at the
moment the first toggle is switched on — the only moment the request has a
reason the user can see — and `GameAlerts` re-checks the grant on every post,
because it can be revoked afterwards.

Alerts are found by comparing a fresh fetch against the last seen state; there
is no push channel. A game seen for the first time is recorded and nothing is
posted, or every game already in progress when the widget is placed would arrive
looking exactly like a game that just kicked off. The close-finish alert fires
once per game rather than on every repaint through the last six minutes.

## What is tested

60 tests, JUnit only, and it does not ship. What is covered is the arithmetic
that is easy to get quietly wrong and impossible to check by looking at a phone
on any given day:

- **`SeasonWindowTest`** — the offseason windows. Four of the five leagues wrap
  the new year, and the naive `month in first..last` reads as an *empty* range
  for every one of them. A widget that silently filtered out the NFL for twelve
  months would look exactly like a widget with no NFL games on today.
- **`TeamFilterTest`** — the tiers, the 24-hour and 6-hour windows, the rule
  that rivals only fill an empty tile, and the carousel's wrapping (Kotlin's `%`
  keeps the sign of the dividend, so stepping back from zero would throw).
- **`WinProbabilityTest`** — the shape of the curve, not its numbers: the same
  lead is worth more later, a tie is near even, and nothing is ever certain
  while the clock runs.
- **`RefreshIntervalTest`** — the polling tiers, including the two that cost
  someone a battery if they are wrong.
- **`TeamGlyphTest`** — the thirteen hand-drawn marks.

```bash
./gradlew :scorewidget:testDebugUnitTest
```

## Licence note

The typefaces are Nothing's own, and so is the design language. This is an
unofficial third-party widget; the Nothing wordmark and logotype are never
reproduced. Team marks are original dot compositions rather than club crests,
but club names and abbreviations are trademarks of their leagues, and the score
data is ESPN's. Fine for a personal build; if this goes further than your own
device, check what those licences permit — that call is yours, not the code's.
