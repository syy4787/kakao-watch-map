# Watch Polish — Audit Fixes, Delta Updates, Watch Features — Implementation Plan

> **For agentic workers:** executed inline by the author with superpowers:executing-plans + TDD (each task: failing test → fix → green → commit). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix every verified defect found by the 4-way audit of the map and YouTube sides (2026-10-14), make updates small (delta patches instead of ~105 MB APKs), and add a few watch-first features — then ship one release.

**Architecture:** Work stays in the existing Kotlin modules (one app, processes: main / `:player` / GeckoView engine). New pieces: `update/DeltaPatch.kt` (apply a copy/literal patch to the installed APK), `.github/scripts/make-deltas.py` (generate + self-verify patches in CI). Everything else is targeted changes with tests next to the existing ones in `app/src/test/java/com/mywatch/kakaomap`.

**Tech Stack:** Kotlin, Android (minSdk 30, target 36, compile 37), Wear OS, Kakao Maps SDK v2, GeckoView 158, WorkManager, Robolectric 4.17 (NATIVE graphics for layout-size tests), Playwright simulator for `web/tube`, GitHub Actions, Python 3 in CI.

**Spec:** user request 2026-10-14 21:30 ("카카오맵과 유튜브앱의 모든 오류를 고쳐주고 기능을 추가 하거나 향상 시켜주고 갤럭시워치에 모든걸 최적화") + the four audit reports summarised under each task (each finding re-verified against the code before fixing).

## Global Constraints

- Device: Galaxy Watch 9 44mm, round 480×480 @340dpi (226dp), 32-bit armeabi-v7a userspace, 2 GB RAM, 445 mAh; user wears it on the **right wrist**.
- YouTube: official APIs only (Data API v3, OAuth device flow, IFrame Player API). No InnerTube/yt-dlp/NewPipe-style extraction, no audio extraction, no background playback, player ≥200×200 with nothing overlaid, scripted playback only while visible, API data cached only as the policy allows (lists in memory; history on device 30 days).
- Never print or commit secrets (Kakao key in `/home/claude/KakaoWatchMap/local.properties`, OAuth client secret, tokens); keep the user's home location out of the repo and CI logs.
- No destructive git (no force-push, no branch deletion). Work on branch `watch-polish`; release = fast-forward `main` + push.
- Updates must never strand the watch: any delta failure falls back to the full APK; SHA-256 of the result must equal update.json before install.
- Gradle locally: `export ANDROID_HOME=/opt/android-sdk` before `./gradlew --no-daemon …`.
- Korean UI copy; short lines that fit 226dp at font scale 1.3.

---

## Part U — Updates (do first: makes every later release cheap)

### Task U1: Wi-Fi patience is not reset by every new release
**Finding (D2, verified):** `UpdateManager.waitingSince` keys the 2-day Wi-Fi patience by `versionCode`; with releases every few hours a watch without saved Wi-Fi never auto-updates.
**Files:** `update/UpdateManager.kt`, test `update/UpdateOutsideTest.kt`.
- [ ] Test: big update v200 pending since T; at T+1 day v201 appears; at T+2 days+1 min `autoNeedsWifi(v201)` is **false** (patience counts from the first pending big update). After `check()` finds no update, the clock is cleared (next big update starts a new 2-day wait). Old stored format `"200:T"` still read as T.
- [ ] Implement: store only the first-pending timestamp; clear it in `check()` when up to date.

### Task U2: Downloads stop when their owner stops; manual update never installs over the player
**Findings (D3, D4, verified):** blocking `fetch` ignores `isStopped` (keeps the Wi-Fi lease and the lock after a worker is replaced/stopped); `UpdateActivity` installs right after download even if the user left and is watching a video.
**Files:** `update/UpdateManager.kt`, `update/UpdateWorker.kt`, `update/UpdateActivity.kt`, tests in `update/`.
- [ ] Test: `download(…, cancelled = { calls > N })` throws `UpdateManager.Cancelled` within one chunk, leaves the partial file for resume, and the Wi-Fi lease is closed (FakeWifi records close).
- [ ] Test: retry sleeps are skipped when cancelled.
- [ ] Implement `cancelled: () -> Boolean` checked per 64 KB chunk and before sleeps; worker passes `{ isStopped }` and returns `retry()` on `Cancelled`.
- [ ] `UpdateActivity`: after download, install only if the screen is still started **and** `!PlayerPresence.of(c).busy(0)`; otherwise `UpdateInstallWorker.enqueueSoon()` and show "다 받았어요 — 화면을 닫으면 설치해요".

### Task U3: Delta patch format — apply on the watch, generate + self-verify in CI
**Measured:** v123→v124 armeabi-v7a: 100.8 MB of entry data identical, 3.67 MB changed (classes.dex), 0.1 MB headers → patch ≈ 3.8 MB instead of 104.5 MB.
**Format (`.kwdelta`):** 8-byte magic `KWDELTA1`, then one zlib stream of records: `'C'` u64 offset u32 length (copy from installed APK) · `'L'` u32 length + bytes (literal) · `'E'` u64 total length + 32-byte SHA-256 of the output (end). Big-endian.
**Files:** create `update/DeltaPatch.kt`, `.github/scripts/make-deltas.py`; tests `update/DeltaPatchTest.kt`, `.github/scripts/test_make_deltas.py`.
- [ ] Kotlin tests: synthetic patch (copy+literal) reproduces exact bytes; wrong magic / copy past end / truncated stream / wrong total / wrong end SHA → `DeltaPatch.BadPatch`, output deleted; cancel mid-apply → `Cancelled`; constant memory (streams).
- [ ] Python generator: match zip entries by (name, CRC, compressed size, method, size) **and** identical bytes → copy their data range; everything else literal (merged); verify by applying with the Python applier and comparing SHA-256; skip a patch bigger than 60 % of the target.
- [ ] Cross-check locally: generate v123→v124 with the script, apply with Kotlin (`DeltaRealApkTest`, runs only when `-Ddelta.old/new/patch` paths exist) → SHA-256 equals v124's.

### Task U4: Use patches when the installed APK matches
**Files:** `update/UpdateManager.kt` (UpdateInfo.patches, installed-APK SHA cache, patch-first download with full fallback, size for `isBig`), `update/UpdateActivity.kt` (shows patch size), tests in `update/`.
- [ ] Test: update.json with `apks.armeabi-v7a.patches=[{from, url, sha256, size}]` parses; `toJson()` round-trips patches.
- [ ] Test (FakeReleaseServer): installed APK SHA = `from` → only the patch is fetched, result SHA = target, file installed; patch SHA mismatch or apply failure → falls back to the full APK and records "작은 업데이트 실패" once; no matching `from` → full APK.
- [ ] `isBig`/`autoNeedsWifi` use the size that will actually be downloaded.

### Task U5: CI publishes deltas, and only from the newest main
**Finding (D9, verified):** re-running an old run or dispatching on another branch publishes that commit as latest.
**Files:** `.github/workflows/release.yml`.
- [ ] Guard step: if `GITHUB_REF != refs/heads/main` or `HEAD != git ls-remote origin refs/heads/main` → notice + skip (no release).
- [ ] After building: download the previous 3 releases' ABI APKs (REST via `gh release download`), run `make-deltas.py` per ABI, attach `.kwdelta` files, merge `patches` into `update.json`; the step never fails the release (no deltas → full only).

## Part P — Player process

### Task P1: `intent://x#Intent;end` crashes the player (D6, reproduced)
- [ ] `PlayerNavTest`: `top("intent://x#Intent;end", …)` → `Block` (no exception); same for `newWindow`.
- [ ] Guard the substring range in `PlayerNav.fromIntent`.

### Task P2: Audio focus and earbuds disconnect (D1)
**Files:** `ui/TubePlayerActivity.kt`, new `tube/PlayerAudio.kt` (pure decisions), test `PlayerAudioTest.kt`.
- [ ] On media `onPlay`: request `AUDIOFOCUS_GAIN` (USAGE_MEDIA/CONTENT_TYPE_MOVIE); on `LOSS`/`LOSS_TRANSIENT` pause the session; abandon on pause/stop.
- [ ] While playing, `ACTION_AUDIO_BECOMING_NOISY` pauses (earbuds gone → no surprise loudspeaker).
- [ ] Robolectric test with ShadowAudioManager: focus requested on play, loss pauses, noisy broadcast pauses, receiver unregistered on stop.

### Task P3: `:player` process lifecycle + give memory back
**Findings (D5 + idea):** ProcessLifecycleOwner never starts in `:player` (GeckoView network manager / pause hooks dead); after closing the player, GeckoView keeps 150–300 MB alive next to navigation.
- [ ] Init ProcessLifecycleOwner in the PLAYER branch of `MapApp` (guarded, logged on failure) — test that it reaches STARTED when a player activity starts in a PLAYER-role app.
- [ ] When no player screen has been started for 3 min (or on `TRIM_MEMORY_BACKGROUND+` with none started), end the `:player` process (`PlayerPresence` keeps its marks) — test the decision helper.

### Task P4: Page — long stall and hidden player
**Files:** `web/tube/index.html`, `tools/tube-sim/sim.mjs`.
- [ ] After 60 s of buffering with no progress, pause (screen may sleep) and show "연결이 느려 멈췄어요 — ▶ 다시"; sim scenario.
- [ ] Pause when the player is less than half visible (IntersectionObserver), no auto-resume; sim scenario.

### Task P5: Error log safe across processes (D10)
- [ ] `ErrorLog` writes go to a temp file + atomic rename; test concurrent writers don't truncate.

## Part M2 — Map screen & guidance

### Task N1: Guidance that stalls stops draining the battery (B1)
**Files:** `nav/NavigationService.kt`, `nav/LostClock.kt` (or new `nav/StallClock.kt`), `nav/LocationPolicy.kt`, tests `NavEngineTest`/new `StallClockTest`.
- [ ] Track last usable fix and last route progress. 5 min with neither → BALANCED + periodic GPS check; 30 min → end with the existing "gave up" notification; last fix within 60 m of the destination → arrived.
- [ ] Location turned off mid-guidance → banner/notification says so; ends after 30 min.
- [ ] Guidance that never got onto the route ends after 45 min.

### Task N2: Reroute backoff that actually backs off (B4)
- [ ] Reset the backoff only after ≥100 m progress or 2 min matched on the new route; at the per-trip cap show "다시 찾기를 멈췄어요 · 톡하면 다시" and allow a manual reroute (tap). Tests in `NavEngineTest`/service decision helper.

### Task N3: Approximate-only location is visible, not silent (B2)
- [ ] When guidance starts without precise location: banner state "정확한 위치가 필요해요 · 톡하면 설정" (opens app settings), no fake progress. Robolectric test on the decision helper.

### Task N4: Heading from the GPS course while moving (B3) + declination early
- [ ] `HeadingMath.chooseHeading(sensorDeg, location)`: speed ≥ 1.5 m/s with bearing accuracy ≤ 25° (or BIKE mode with speed ≥ 2.5 m/s) → GPS bearing; else sensor. Tests.
- [ ] Declination from the last known location at sensor start.
- [ ] Compass mode: apply rotation only when it changed ≥ 6° and at most 5×/s.

### Task N5: Dragging the map in compass mode keeps the rotation (B6)
- [ ] Leaving COMPASS by touch keeps the camera rotation; the button cycle still resets to north.

### Task N6: Guidance and destination survive process death (B5, A7)
- [ ] Persist the active route (+ destination, mode, started-at) on change; restore on MainActivity/Hub start if < 6 h old and guidance was running → restart NavigationService and say "안내를 다시 이어요". Directions/RouteDetail restore the destination instead of finishing.

## Part M1 — Search & routes

### Task R1: Timetable routes come back (A1)
- [x] When a timetable kind ran out within the 5-min refetch gap, mark `refetchDue` and refetch once the gap passes (also when no timetable route remains). Test via DirectionsActivity logic helper. → `model/TimetableRefresh.kt`, tests in `IntercityRoutesTest`.

### Task R2: Search is near me (A2)
- [x] No fresh fix → get a coarse fix (≤3 s, `Locator.rough`) before searching. **Changed after live probing (2026-10-14, 대전시청):** with x,y the default accuracy sort is already local ('스타벅스' → 0.2 km stores, '부산역' → 부산역 203 km); `sort=distance`/`radius` broke '대전역' (렌터카 픽업존) and '부산역' (0건), so the query stays accuracy + x,y. Nationwide fallback is labelled. → `net/PlaceSearch.kt`, `PlaceSearchTest`, `SearchFlowTest`.

### Task R3: Intercity failures are not silent (A3, A4)
- [x] Access-leg network errors: retry once, else the station is dropped **with a problem note** (no invented 'walk 10 km' leg); "no route" stays dropped silently. When city transit is empty and timetables failed, the error says so (다시 시도); city failures with trains present are noted; incomplete results get '다시 찾기'. → `net/TransitAnswers.kt`, `IntercityPlannerTest`, `TransitAnswersTest`.

### Task R4: Night runs after midnight (A5)
- [x] Before 04:00 KST also query the previous service day and keep runs with departure ≥ now. Test in `IntercityPlannerTest`.

### Task R5: Transit card meta not cut (A6)
- [x] Meta allows 2 lines, items glued with no-break spaces; `TransitCardFitTest` (font 1.0/1.3).

### Task R6: Back steps inside Search (A8)
- [x] OnBackPressedCallback steps back: menu → results → home. Plus swipe: Wear OS swipe-to-dismiss finishes the activity without calling back handlers, so `activity_list` is wrapped in `SwipeDismissFrameLayout` that is swipeable only while there is an in-app step (Search + YouTube). `SearchFlowTest`, `TubeAppTest`.

### Task R7: Bus card catchability (feature)
- [x] If the first bus arrives before you can walk to the stop (1 min grace) → "놓칠 수 있어요 · 다음 n분"; tests in `LiveBusTest`.

### Task R8: Nearby chips on search home (feature)
- [x] '내 주변' row → 편의점·약국·화장실·카페·지하철역 (rows, not chips: 5 chips don't fit 176 dp at font 1.3) → Kakao category search (화장실: keyword + category filter, renovation shops excluded) within 5 km, distance sort. `PlaceSearchTest`, `SearchFlowTest`.

## Part T — YouTube

### Task T1: Lists don't shrink or burn quota on back/resume (C1 + cache idea)
- [x] Extended (더 보기) pages and search pages are not auto-refreshed on back/resume (3 h, memory only); `more()` during a refresh is queued; nav stack saved in `onSaveInstanceState` (spots not restored: data is refetched after process death). Tests in `TubeAppTest`/`TubeNavTest`.

### Task T2: Revoked access → 로그인 works first time (C2)
- [x] Apply the sign-out transition before any `nav.push` (`syncSignIn`) — not in the loader callback (that would yank the page from under the user). Test: revoke then tap 구독 → 로그인 → Login page with a code request.

### Task T3: Lists don't jump under the finger (C3)
- [x] Row keys + DiffUtil in RowAdapter (no item animator); skeleton count = final count on Home; search page-2 channels/playlists appended after existing rows (`TubePage.parts`); Search screen restores per-page scroll. `ListStaysPutTest`, `TubeRowsTest`, `SearchFlowTest`.

### Task T4: Logout offline still revokes (C4)
- [x] `forget()` writes the refresh token to an encrypted pending-revoke list before revoking; `TubeRevokeWorker` (network constraint, exponential backoff) retries; dropped on 2xx/4xx (except 429). `TubeAuthTest`, `TubeTokenStoreTest`, `TubeRevokeWorkerTest`.

### Task T5: Login code stays visible (C6)
- [x] Ambient summary on the Login page shows code (large) + URL; "폰에서 주소 열기" shows an inline note (sending → opened / no phone / failed) and no overlay. `TubeRowsTest`, `TubeAppTest`.

### Task T6: Polling robustness (C7)
- [x] 5xx/429 → SlowDown (keep polling); re-check `signedIn` before showing Expired/Denied/Failed; slow_down interval kept in `LoginState.Waiting`; `poll()` save bumps generation and revokes a replaced refresh token (in-flight renewals return the new token). Tests in `TubeAuthTest`, `TubeAppTest`.

### Task T7: Own playlists play fully; private-only first pages (C8, C9)
- [x] Own playlists carry `privacy` (part=status, same 1 unit): public/unlisted → `list=` (YouTube plays to the end); private → 모두 재생 collects up to the player page's 50 ids (2 more pages) — **not 200**: the page and IFrame queue are capped at 50. Phone handoff passes `list=`. `videosOf` keeps paging (≤3) until a playable video. Tests.

### Task T8: Token store survives transient keystore errors (C10)
- [x] Wipe only on bad tag/padding, unrecoverable/invalid key, or corrupt data; transient keystore errors keep the blob. Hub pre-reads the token on IO. Tests.

### Task T9: Thumbnails give memory back (C5)
- [x] Cache 12 MB max; main-process `onTrimMemory` evicts on UI_HIDDEN+ and halves on RUNNING_LOW/CRITICAL. Test.

### Task T10: Bezel-friendly paging + richer Kakao fallback (features)
- [x] Auto-load the next page when the last 4 rows come into view while scrolling down, cheap feeds only (never search), once per token. Kakao fallback results enriched with one `videos.list` (embeddable, duration, live; gone videos dropped). Tests.

## Part Z — Ship

### Task Z1: Verify and release
- [x] Full unit tests (524, 2 skipped, 0 failed), lint (0 errors — only old style/dependency warnings), render pass (`-Prender`, new: real search screen with 내 주변 → kinds → toilets, '놓칠 수 있어요' cards at font 1.3, login phone steps) reviewed by eye, tube simulator 66/66 (`--retry=1`) + 30/30 random users.
- [x] Independent read-only reviews of the full diff (3 parallel reviewers: update/CI, map, YouTube/player); verified findings fixed test-first:
  - update/CI (236f5d6): partial patch kept on outage, compare-based release gating, Wi-Fi patience, cancelled manual update → worker.
  - map: interrupted guidance offered after process-death restore and before the init-error return, forgotten on arrival (be5ea84); waiting at a stop/platform is not a stall, platform uses cell/Wi-Fi (fce54c7); clock cards re-bound (3636b42); stall baseline per route version (2719f66); nearby uses ≤10/15-min location (b1117e9).
  - YouTube: pending revokes sent before a new sign-in, replaced token not revoked, per-entry encrypted storage (cea6ecc); thumbnails halve on UI_HIDDEN (c7341a1); no player start after leaving, play-all waits for in-flight more (6297b1f).
- [x] Second review of the fixes (1 reviewer) → 7 findings, all fixed: minute refresh reaches walk/bike/city lists (48d9630); screen-off defers play until looked at again, Home drops it and the tile shortcut (e9bd373); 2-h wait cap survives GPS blips and skips timetabled trains (cf1f303); revoke storage returns readable entries, keeps Android-transient keystore errors, one sender at a time (09196e5); thumbnails cleared when hidden during guidance (d8d7ca3).
- [x] README/privacy updates (be2073d).
- [ ] Fast-forward `main`, push; verify release assets (APKs, `.kwdelta`, update.json patches + sizes), apply the published patch to the previous APK locally and compare SHA-256; Pages + simulator gate green.
