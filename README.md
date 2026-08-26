| `WidgetRenderer` | snapshot -> RemoteViews |
| `TextRenderer` | draws each label with the real face, since a widget cannot |
| `AppGlyph` | the source app icon, restated in monochrome |
| `LastSession` | remembers the last track, artwork included |
| `SessionResumer` | restarts an app whose session has gone |
| `TextRenderer` | draws labels with the real faces, since a widget cannot |
| `LastSession` | remembers the last track, artwork included |
| `SessionResumer` | restarts an app whose session has gone |
# Dot Matrix Player

A 5 × 3 Android home-screen widget for media control, drawn in a monochrome
dot-matrix idiom. Album art, track and artist, transport keys, and a scrub bar
you can tap to seek.

It controls **whatever is currently playing** — Spotify, YouTube Music, Poweramp,
a podcast app, a browser tab — by talking to the active `MediaSession`. It is not
a player itself and has no library of its own.

```
┌──────────────────────────────────────────────────┐
│  ┌────────┐   ● ▪▪▪ ▪▪▪▪▪▪▪  (NOW PLAYING)       │
│  │ album  │   Track Title                        │
│  │  art   │   Artist                             │
│  └────────┘                                      │
│  1:04  ●●●●●●●●●●▮∙∙∙∙∙∙∙∙∙∙∙∙∙∙∙∙∙∙∙∙∙   3:47   │
│              ◀▌      ( ▮▮ )      ▐▶              │
└──────────────────────────────────────────────────┘
```

## Installing

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Then open the app once to grant notification access, and add the widget from the
launcher's widget picker.

**Sideloading trips Android's Restricted Settings.** On Android 13+, an app with
no attributing installer (`installer=null` — a file-manager install, or a plain
`adb install`) cannot be granted notification-listener access through the normal
Settings toggle. It appears greyed out, and Settings says the option is
controlled by a restricted setting. That is the platform withholding a sensitive
permission from sideloaded apps, not a fault in this app. Two ways through.

On the phone: **Settings → Apps → Dot Matrix Player → ⋮ (top right) → Allow
restricted settings**, then grant notification access as normal.

Or over adb:

```bash
adb shell appops set com.dotgrid.mediawidget ACCESS_RESTRICTED_SETTINGS allow
```

That only un-greys the toggle — it grants nothing by itself. To also flip the
listener switch without touching the phone:

```bash
adb shell cmd notification allow_listener com.dotgrid.mediawidget/com.dotgrid.mediawidget.NotificationHookService
```

Built and verified against AGP 9.3.1 / Gradle 9.5 / Kotlin 2.2.10, compileSdk 36,
minSdk 26, and run on a Nothing device on Android 16. Release build is ~1.7 MB
unsigned, almost all of it the Ndot 77 JP Extended face.

## Typefaces

Three roles plus a coverage fallback, defined once in `Typography.kt` and
mirrored by two base styles in `values/styles.xml` for the setup screen, which
cannot read a Kotlin constant. Every caller names a **role**, never a face.

| role | resource | face | used for |
|---|---|---|---|
| Body | `geist.ttf` | Geist (variable, `wght` 400) | track title, artist, setup body copy |
| Accent | `ntype82.otf` | NType 82 | status label, elapsed / duration, setup controls |
| Wordmark | `ndot57_aligned.otf` | Ndot 57 Aligned | the setup headline — and nothing else |
| Coverage fallback | `ndot77_jp.ttf` | Ndot 77 JP Extended | any string Body cannot render |

**A widget cannot render a custom font from XML.** `AppWidgetHostView` inflates
through a context created with `CONTEXT_RESTRICTED`, and `TextView` only resolves
an `android:fontFamily` resource when `!context.isRestricted()`. In a widget that
test fails, so the attribute is skipped in silence — no exception, nothing in the
log, just the system face where the custom one should be. Setting a `Typeface`
from code does not help either: `TypefaceSpan` parcels a family *name*, not a
face.

So every label in the widget is drawn to a bitmap by `TextRenderer`, using a face
loaded in our own process where it resolves normally. `ImageView`, not `TextView`.
A Gradle check (`verifyWidgetHasNoTextViews`, wired into `preBuild`) fails the
build if a `TextView` reappears in a widget layout, because that regression is
invisible until someone looks at a phone.

The setup screen is an ordinary activity in an unrestricted context, so it does
use `android:fontFamily` — that is why `ntype82.otf` is declared in XML while the
widget's faces never are.

### The fallback is about coverage, not style

Title and artist are the only two strings the widget does not author: they arrive
from whichever app is playing, in whatever script it likes. So `TextRenderer`
checks glyph coverage **per string** and swaps to Ndot 77 JP Extended (~21,000
codepoints — full Latin, Cyrillic, Greek, kana and most common CJK) only for the
strings Body cannot render. That keeps the general-purpose face on everything it
can actually draw, and reaches for the wordmark cut only where the alternative is
a row of tofu boxes.

Moving title and artist from NType 82 to Geist made that fallback fire **less**
often. Measured off the two fonts' own `cmap` tables: NType 82 carries ~217
codepoints and is Latin-only; Geist carries ~728 and includes Cyrillic. A Russian
title used to fall through to the 14 MB cut and now does not.

### Tabular digits, without a tabular face

The elapsed clock repaints once a second, and Ndot 57 **Aligned** used to carry
it for exactly one reason: it is the tabular cut, all ten digits at 600 units per
em. Every other face here is proportional. Measured off their own `hmtx` tables:

| face | `0` | `1` | spread |
|---|---|---|---|
| Ndot 57 Aligned | 600 | 600 | tabular |
| NType 82 | 508 | 422 | 17% |
| Geist | 663 | 384 | 42% |

Set naively in NType 82, `1:01` is visibly narrower than `0:00`, so the label
reflows on the tick a `1` enters or leaves — and since the scrub rail is sized
from those same labels, the rail breathes with it.

Ndot is the wordmark face, though, so the fix cannot be typographic. It is
structural instead: `TextRenderer.renderTimeCode` advances every digit by the
width of the widest digit and centres the glyph in that cell, which makes any
face behave as a tabular one here. Non-digits keep their natural advance, so the
colon stays tight, and `timeCodeWidthPx` sizes the rail against the same
geometry so both halves hold still.

That is the same move the score tile makes with its scoreline, where two scores
are measured into one shared column so the separator cannot slide. Digit jitter
in this family is solved by measuring, not by picking a face.

These are Nothing's own typefaces. Fine for a personal build; if this goes further
than your own device, check what their licence permits — that call is yours, not
the code's.

## Why notification access

`MediaSessionManager.getActiveSessions()` — the only way for a third-party app to
read and control another app's playback — will only return sessions to a caller
that has an **enabled `NotificationListenerService`**. That is the sole reason the
app asks. `NotificationHookService` never reads a single notification; it exists
to satisfy that check and to host the refresh logic. There are no `<uses-permission>`
entries in the manifest at all, and nothing leaves the device.

## Design notes

**Nothing's visual language.** `#1B1B1B` surface, white type, one accent red
(`#D71921`) spent on exactly two things: the "playing" dot and the scrub playhead.
Everything geometric, nothing decorative.

**All widget text is pixels, not TextViews.** See [Typefaces](#typefaces) — a
restricted inflate context means a widget can never resolve a font resource, so
`TextRenderer` draws each label with the real face and ships a bitmap.

**Two layouts, one renderer, and only ever one on the wire.** `widget_media.xml`
is the three-cell-tall arrangement; `widget_media_compact.xml` handles two cells.
They carry identical view ids, so `WidgetRenderer` populates either without
knowing which is live.

Earlier this shipped both at once in a `RemoteViews(Map<SizeF, …>)` so the
launcher could swap on resize without a round trip. That stopped being viable
once every label became a bitmap: one variant measures ~460 KB of bitmap memory,
and two would sit against the 1 MB binder transaction limit. Now `build()` picks
a single variant from the widget's reported height and
`onAppWidgetOptionsChanged` repaints on resize — one round trip, no overflow.

**The two-cell layout turns ninety degrees.** At 5 × 2 the tile is 110 dp tall,
which leaves 94 dp of content — nowhere near enough for the stacked arrangement.
Keeping the three-cell layout there is what made the artwork tiny: as one row of
three it could only ever be 42 dp. The compact layout instead runs the artwork
down the full height on the left with everything else in a column beside it,
which puts it at 86 dp. The three-cell layout gained too: trimming the transport
row and scrub margins took the artwork from 80 dp to 94 dp.


**Scrubbing without touch events.** A RemoteViews tree gets no touch events of
its own, so there is no drag to read an x-coordinate from. `seek_strip.xml` lays
16 invisible tap targets edge to edge over the bar; each carries the fraction of
the track it sits over. Over a ~210 dp bar that is a ~13 dp target, and a
four-minute song scrubs in 15-second steps.

Those targets are `FrameLayout`s, which looks wrong until it does not:
**RemoteViews only inflates classes annotated `@RemoteView`, and `android.view.View`
is not one of them.** A bare `<View>` throws `Class not allowed to be inflated`
and the launcher replaces the entire tile with "Can't load widget" - no clue as to
which of the views was at fault unless you read logcat. An empty `FrameLayout` is
the lightest allowed stand-in. The same whitelist is enforced in-process, so
`SetupActivity` crashes on exactly the same layout, which makes it a usable smoke
test: if the setup screen renders its preview, the launcher will inflate it too.

**Album art comes from two different places, depending on the app.** Apps split
roughly evenly between embedding a bitmap (`METADATA_KEY_ALBUM_ART` and friends)
and publishing only a URI (`..._ART_URI`). Reading just the bitmap keys is the
obvious implementation and it silently shows the empty panel for half the apps
on the device — Spotify embeds a bitmap, SoundCloud sets every bitmap key to
null and gives a `content://` URI instead. `ArtworkTools.fromMetadata()` tries
both, bitmaps first.

Reading another app's `content://` URI works without any permission grant; this
was verified against Spotify's own provider, which served a 320 × 320 cover to
us directly. Remote `http(s)` art is deliberately **not** fetched — the app holds
no `INTERNET` permission and nothing it does leaves the device, which is worth
more than cover art for the few apps that only offer a URL.

Two smaller cross-app hazards handled in the same place: art is decoded
subsampled, so a 3000 px cover never lands in memory at full size just to be
shrunk; and a `HARDWARE`-config bitmap is copied to a software config first,
since it has no readable pixels and would otherwise throw the moment anything
tried to scale or shade it.

**The bar is a bitmap, at its real width.** `ProgressRenderer` draws the dot rail
at the widget's actual on-screen pixel width, computed from the widget options.
A dot rail stretched by `fitXY` turns into an ellipse rail.

**The last session is remembered, and can be restarted.** A media session vanishes
the moment its app leaves recents, taking the metadata and artwork with it, and
the tile would otherwise fall back to "nothing playing" - true, but useless. So
`MediaHub.snapshot()` writes each live frame to `LastSession`: title, artist,
position and the artwork, re-encoded to WEBP in `filesDir`. Writes are throttled
to a track change or three seconds of movement, so a 1 Hz repaint is not a 1 Hz
disk write.

With no live session but something cached, the tile shows that track with a
`TAP TO RESUME` label, the rail dimmed at the position it was left, and the skip
keys dark - there is no session to skip within. Play becomes `ACTION_RESUME`.

Restarting a dead app is the part with no clean answer. `SessionResumer` does what
Android s own media-resumption tile does: connects to the app s
`MediaBrowserService`, which starts it, then drives the session token it hands
back. Apps may refuse - several allowlist Android Auto and Wear in `onGetRoot` and
reject everyone else - so a refusal is expected rather than exceptional, and the
fallback is simply to open the app. An app may also publish more than one matching
service (Spotify publishes a media-*library* service alongside the browser one),
so the browser is preferred by name.

**The source app is named by a monochrome mark.** A full-colour launcher icon
dropped onto this card would be the only saturated thing on it, and would read as
a sticker rather than as part of the tile. `AppGlyph` takes the adaptive icon's
**monochrome layer** where one exists — the same layer the system uses for themed
icons, and already the silhouette we want — and tints it. Failing that it
desaturates the icon and clips it to a circle, which keeps the mark recognisable
without bringing colour in. Spotify ships a monochrome layer, so it takes the
first path.

Reading another app's icon at all requires it to be visible to us, which is the
second thing the manifest `<queries>` block buys — the first being the resume
path's hunt for a `MediaBrowserService`.

**The empty-artwork state is a dot panel, not a music note.** `ic_art_empty.xml`
is a 9 × 9 field of unlit dots with a ring and hub lit to read as a record. A
borrowed glyph would have been the one element in the widget speaking a different
language; this way an empty tile still looks like part of the same machine. It is
two `<path>` elements — one for the unlit field, one for the lit dots — rather
than 81 separate shapes.

## Refresh policy

Two things trigger a repaint:

- **The session**, via `MediaController.Callback` — track changes, play/pause,
  apps handing off to one another. Instant, event-driven, free.
- **A one-second ticker**, purely so the scrub bar advances. It runs only while
  audio is *actually playing*, the screen is *on*, and a widget is *actually
  placed*. Any one of those going false stops it.

`updatePeriodMillis` is `0` — the system never polls us.

## Layout budget

**Three cells tall** — the 5 × 3 grid minimum is 320 × 180 dp (`70n − 30`). With
12 dp padding that leaves 296 × 156 dp:

| row | height |
|---|---|
| artwork + metadata | 94 dp (weighted — absorbs extra height on roomier launchers) |
| scrub bar + time codes | 12 dp + 8 dp margin |
| transport | 36 dp + 6 dp margin |

**Two cells tall** — 5 × 2 is 320 × 110 dp, leaving 94 dp after 8 dp padding.
Far too little to stack, so the compact layout runs horizontally: 86 dp of
artwork down the left, then a column holding title, artist, scrub rail and
transport (87 dp of the 94). The time codes have no room and are hidden — but
they still exist as views, because a RemoteViews action aimed at a missing id
throws at `apply()` time, so the renderer can set every id unconditionally.

`targetCellWidth`/`targetCellHeight` are 5 × 3 so Android 12+ launchers place it
at that size; `minResizeHeight` of 110 dp is what lets it be pulled down to two.

## Files

| | |
|---|---|
| `MediaWidgetProvider` | receives taps, forwards to the session, repaints |
| `NotificationHookService` | the privileged hook; owns the refresh policy |
| `MediaHub` | picks and reads the active session |
| `PlaybackSnapshot` | one frame's worth of state, resolved at an instant |
| `WidgetRenderer` | snapshot → RemoteViews |
| `TextRenderer` | draws each label with the real face, since a widget cannot |
| `AppGlyph` | the source app icon, restated in monochrome |
| `LastSession` | remembers the last track, artwork included |
| `SessionResumer` | restarts an app whose session has gone |
| `ProgressRenderer` | the dot rail and playhead |
| `ArtworkTools` | centre-crop and downscale the album art |
| `SetupActivity` | grants access; shows a live copy of the widget |

## Known behaviour

- Apps vary wildly in what they report. Missing duration (live streams) renders an
  inert rail with `--:--`; transport keys dim when the session says it can't honour
  them.
- With several sessions active, a *playing* one always wins. When nothing is
  playing, the tile takes the highest-priority session that actually **describes a
  track** — system components hold empty sessions open indefinitely
  (`com.nothing.hearthstone` is one on Nothing OS), and letting one of those win
  means the widget claims to be showing something while every field is blank.
- A package id is never shown. `appLabel()` returns null rather than falling back
  to the package name: visibility filtering makes that lookup fail routinely, so
  the "fallback" is what you would actually end up reading, and it reads as a bug.
- With no session worth showing and nothing cached, the tile says *Nothing
  Playing* once — the status label stands down rather than repeating the title.
- Tapping the card opens the app that owns the session - or owned it, in the
  resume state.
- Resuming a dead app depends on that app accepting a MediaBrowser connection.
  Several big ones allowlist Android Auto and Wear and refuse everyone else; when
  that happens the widget opens the app instead of starting it.

## Preview

`docs/preview.html` is a standalone page that renders the widget live in a browser
from a JavaScript port of the layout arithmetic and `ProgressRenderer`, with toggles
for each playback state and dimension annotations. Open it directly; it needs no server.

The type there is an approximation — a drawn 5 × 7 matrix stands in for Ndot 57
Aligned, and the title and artist use the page's own faces. The Nothing fonts are
not embedded in it.
