# Jadwal Bola

Football fixtures and scores for an Indonesian household, full screen on the TV: the Indonesia national team
(Timnas), Liga 1 (Indonesian Super League) and the UEFA Champions League, from yesterday through the next 7 days.
Each row has the day, the kickoff in WIB (Asia/Jakarta, 24 h), home and away, and the score once a match has
started. Live matches are marked LIVE with the match clock. A competition with nothing in the window says so and
names its next known match. The page text is Indonesian. For the [Clean Your TV](../../README.md) launcher it
declares a [module card](../../docs/modules.md) "Jadwal Bola" with a green glow; on any other Android TV home it
is an ordinary app.

- **Data** comes from ESPN's public, unofficial JSON API (no account or key), `site.api.espn.com`:
  - Liga 1 and the Champions League: `soccer/<slug>/scoreboard?dates=YYYYMM&limit=200` for each month the window
    touches (`idn.1`, `uefa.champions`). ESPN refuses a day range (`dates=YYYYMMDD-YYYYMMDD` answers 400) and
    returns at most 100 events without `limit`.
  - Timnas (ESPN team 4895): `soccer/all/teams/4895/schedule` (results) and `...?fixture=true` (coming
    matches). While a Timnas match may be on, that day's league scoreboard (`soccer/<league>/scoreboard?dates=YYYYMMDD`)
    is read for the live score.
- **Refresh:** when the page opens, then every 2 minutes while a match may be on (live, or past kickoff and not
  yet final), otherwise every 30 minutes or just after the next kickoff, only while the page is visible. A
  background job also refreshes every 30 minutes while the TV is on a network (see below).
- **Offline:** the last answer per source is kept in the app's private storage and shown.
- No images are downloaded; the art is two vector drawables.

### Home ticker (for a Home signed with the same key)

The launcher has no network access, so this app exposes its stored matches through a read-only content provider:

- URI: `content://io.github.agungksidik.football.matches/matches`
- Permission: `io.github.agungksidik.football.permission.READ_MATCHES` (`protectionLevel="signature"`, so only an
  app signed with the same key can read it)
- Rows: yesterday through the next 7 days (WIB), Timnas first, then Liga 1 and UCL together by kickoff. A
  competition with no match in that window adds its next known match instead (state "pre"), as the page does.
- Columns: `competition` ("Timnas", "Liga 1", "UCL"), `home`, `away`, `kickoff_ms` (epoch ms), `state` ("pre",
  "in", "post"; postponed and canceled are "post"), `home_score`, `away_score` (empty before kickoff and for
  postponed matches), `detail` (ESPN's short status, e.g. "45'", "HT", "FT", "TBD").
- The provider calls `notifyChange` on that URI after each refresh.

Install this app before the reading app: Android grants a signature permission only if it is defined when the
reader is installed.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | ESPN's public JSON API, as listed above. |
| `RECEIVE_BOOT_COMPLETED` | Keeps the 30-minute refresh job (JobScheduler, persisted) across reboots. |

What is sent: plain HTTPS GET requests to `site.api.espn.com` with the league slug, the team id 4895 and a month
or day, plus the User-Agent `JadwalBola/1.0 (Android TV)` (ESPN refuses the Java default). Nothing about the TV
or its owner. No account, no telemetry, no ads. Backups are off.

**Background work:** one periodic JobScheduler job, every 30 minutes (Android's minimum is 15) and only with a
network, which fetches the same data and stores it for the content provider. No foreground or long-running
service. It is scheduled when the page first opens and again at boot.

## Build and install

JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher:

```bash
cd addons/football && ./gradlew testReleaseUnitTest assembleRelease
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py --push app/build/outputs/apk/release/app-release.apk /data/local/tmp/addon.apk
TV_HOST=<tv ip> ../../tools/venv/bin/python -I ../../tools/tv.py 'pm install -r /data/local/tmp/addon.apk; rm -f /data/local/tmp/addon.apk'
```

The parsing, the window and the grouping are in `Fixtures.java`, tested with JUnit against real ESPN answers
trimmed to a few events (`app/src/test/resources/`).

The release build is signed with the debug key so it installs over ADB like the launcher.

## Known limits

- ESPN's API is unofficial and can change without notice.
- ESPN has no 2026-27 season for `idn.1` (its data stops at the 2025-26 season, May 2026), so the Liga 1 group
  stays empty until ESPN adds it.
- Timnas kickoff times are often "TBD" on ESPN until shortly before the match; they show as `--:--`.

## Tested on

Not yet tested on a TV.
