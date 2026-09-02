# Score Widget Fixes — Session Summary

## 1. Duplicate teams in the live scores widget

**Initial question:** whether duplicate teams in the live-scores widget were caused by ESPN's API.

**Investigation:**
- Confirmed `TeamFilter.rank()` already dedupes score cards by `game.id` ([TeamFilter.kt](../scorewidget/src/main/java/com/dotgrid/scorewidget/TeamFilter.kt)), so duplicate *game cards* for the same matchup were not the issue.
- The real report was duplicate entries in the **favorites/team picker**, not the scoreboard.
- Root cause: `TeamDirectory.teams()` merges a hardcoded seed list (`TeamCatalog.kt`) with ESPN's live `/teams` endpoint, keyed by abbreviation. If the hardcoded abbreviation for a team didn't exactly match ESPN's current live abbreviation, both entries survived the merge under different keys — same team, two rows.

**Verification:** 5 parallel agents (one per league) fetched ESPN's live `/teams` endpoint and diffed it against `TeamCatalog.kt`'s hardcoded rows.

| League | Mismatches found |
|---|---|
| NFL | none |
| NBA | none |
| MLB | `CWS` → `CHW` (Chicago White Sox) |
| NHL | `LAK`→`LA`, `NJD`→`NJ`, `SJS`→`SJ`, `TBL`→`TB`, `UTA`→`UTAH` |
| NCAAF | `BAMA`→`ALA` (Alabama) — confirmed independently by two agents |

**Fixes applied:**
- Corrected all mismatched abbreviations in [TeamCatalog.kt](../scorewidget/src/main/java/com/dotgrid/scorewidget/TeamCatalog.kt).
- Added a name-based dedupe backstop in [TeamDirectory.kt](../scorewidget/src/main/java/com/dotgrid/scorewidget/TeamDirectory.kt) `teams()`: after the existing key-based merge, a second pass collapses any remaining entries sharing the same team name, preferring the live-fetched abbreviation. This means a *future* ESPN rename can no longer produce a visible duplicate row, even before the seed is manually updated.

## 2. Notifications never actually worked

**User report (from live device testing):** the widget "currently cannot request this, so it cannot send notifications for ongoing scores as it is built to."

**Root cause:** `AndroidManifest.xml` deliberately omitted the `POST_NOTIFICATIONS` `<uses-permission>` entry, based on a mistaken assumption that declaring it would trigger an install-time prompt. On Android 13+, `POST_NOTIFICATIONS` is always a runtime-requested permission regardless of manifest declaration — but *without* the declaration, `ConfigActivity`'s `requestPermissions()` call is silently auto-denied by the system with no dialog shown. The alert toggles, `GameAlerts`, and the request flow were all correctly built but could never obtain the permission.

**Fixes applied:**
- Added `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />` to [AndroidManifest.xml](../scorewidget/src/main/AndroidManifest.xml).
- Updated stale comments in `GameAlerts.kt`, `ScoreSettings.kt`, and `README.md` that described the permission as deliberately absent.

**On-device verification:** rebuilt a release-signed APK (required — the existing install was release-signed, so a debug build failed with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`), installed it, and toggled an alert on in the widget's settings screen. The system's "Allow Dot Matrix Player to send you notifications?" dialog appeared for the first time and was granted — confirmed the "Notifications are off for this app in system settings" warning cleared afterward.

## 3. Regression caught during on-device testing: orphaned favorites after abbreviation renames

While screenshotting the settings screen to test the notification fix, the favorites list showed **both** `NJD` (a broken stub, rendered from a raw abbreviation because the key no longer resolved) and `New Jersey Devils` as separate entries — a direct side effect of renaming `NJD` → `NJ` in `TeamCatalog.kt`.

**Root cause:** `ScoreSettings.favorites()` stores favorites as raw `league/ABBREV` strings with no awareness of catalog renames. Any user with a favorite saved under one of the just-renamed abbreviations (`LAK`, `SJS`, `TBL`, `UTA`, `CWS`, `BAMA`, `NJD`) would end up with an orphaned entry that:
- displays as a raw abbreviation stub instead of the team name, and
- never matches a live game again, since `Game.involves()` compares against ESPN's current abbreviation.

**Fix applied:** added an `ABBREV_RENAMES` migration map in [ScoreSettings.kt](../scorewidget/src/main/java/com/dotgrid/scorewidget/ScoreSettings.kt) `favorites()`. On read, any stored key matching an old abbreviation is rewritten to the new one, deduplicated, and persisted back — a one-time fixup per device.

**On-device verification:** rebuilt, reinstalled, reopened settings — the duplicate `NJD` / `New Jersey Devils` rows collapsed into a single correct `New Jersey Devils / NHL` entry.

## Files changed

- `scorewidget/src/main/AndroidManifest.xml`
- `scorewidget/src/main/java/com/dotgrid/scorewidget/TeamCatalog.kt`
- `scorewidget/src/main/java/com/dotgrid/scorewidget/TeamDirectory.kt`
- `scorewidget/src/main/java/com/dotgrid/scorewidget/ScoreSettings.kt`
- `scorewidget/src/main/java/com/dotgrid/scorewidget/GameAlerts.kt`
- `scorewidget/README.md`

## Verification method

All fixes were verified on a physical connected device via `adb`: release-signed builds (`./gradlew :app:assembleRelease`), installed with `adb install -r`, and driven with `adb shell input tap/swipe` + `adb exec-out screencap`, reading the resulting screenshots to confirm each fix visually rather than assuming from code alone.
