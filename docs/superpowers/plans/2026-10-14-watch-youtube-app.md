# 워치용 유튜브 앱 Implementation Plan

> **For agentic workers:** Executed inline (superpowers:executing-plans) by the agent that wrote the spec — the code for each
> step lives in that step's commit; this plan fixes task boundaries, file ownership, interfaces and the tests each task must
> make fail first. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the watch app's YouTube part into a real YouTube-style app — browse (home, search, subscriptions, library,
channel, playlist), Google sign-in (device flow, read-only), and in-app playback of the official player inside a bundled
GeckoView engine, with Samsung Internet as the automatic fallback.

**Architecture:** Data layer (`tube/`: YouTube Data API client with injected transport, OAuth device flow with injected
transport + encrypted token store) → page model (`ui/tube/`: back stack of `Page`s, a loader with memory cache, pure row
builders) → host activity (`ui/TubeActivity`) → player launcher (`tube/TubePlay`) → in-app player activity in its own
process (`ui/TubePlayerActivity`, GeckoView) that loads the existing round player page (`web/tube`, extended for queues).

**Tech Stack:** Kotlin 2.4.20 (AGP 9.1.1 built-in), compileSdk 37 / targetSdk 36 / minSdk 30, androidx.wear WearableRecyclerView,
GeckoView 158 (org.mozilla.geckoview:geckoview, ARM only), YouTube Data API v3, Google OAuth 2.0 device flow,
Robolectric 4.17 tests, Playwright simulator for the web page.

**Spec:** `docs/superpowers/specs/2026-10-14-watch-youtube-app-design.md`

## Global Constraints

- Official YouTube IFrame player only; viewport ≥ 200×200; nothing drawn over it; autoplay only when visible; no background player (III.I.9).
- No downloading/caching of audiovisual content; do not block or modify ads; tracking protection off in GeckoView.
- Thumbnails that start playback ≥ 120×70 px (physical): full-width 16:9 cards, compact rows use ≥ 64×36 dp (136×77 px).
- OAuth scope: `https://www.googleapis.com/auth/youtube.readonly` only. Tokens encrypted with Android Keystore AES-GCM; sign-out revokes and deletes.
- API responses only in memory (≤ 30 min). Local history ≤ 30 days (existing PlayedHistory).
- Never ask for a password on the watch; never print keys/secrets; CI masks OAuth values.
- Korean UI copy, "폰" for the phone, "유튜브" wordmark text only (no YouTube logo shapes/colors in our icon).
- Map (카카오맵) screens unchanged (`Look.MAP` path untouched).
- Player process `:player`; a crash there must never take down the main app; fallback to Samsung Internet player page, then phone.

---

## File map

| File | Responsibility |
|---|---|
| `tube/Tube.kt` | models: `TubeVideo`(+channelId, views, publishedAt, thumb), `TubeChannel`, `TubePlaylist`, `TubePage`(+channels, playlists), links (+`playerUrl` with `Queue`), text helpers |
| `tube/TubeParsers.kt` (split out of Tube.kt) | JSON → models for search/videos/channels/playlists/playlistItems/subscriptions |
| `tube/YouTubeData.kt` | Data API calls with injected `get(url, headers)`; pagination; subscription feed; errors (quota/auth) |
| `tube/TubeAuth.kt` | device flow, polling, refresh, revoke; `TokenStore` + `KeystoreTokenStore` |
| `tube/TubeService.kt` | `TubeState`, `PhoneOpener`, `TubeApi` facade (search via `TubeSearcher`, plus home/channel/playlist/library through `YouTubeData`) |
| `tube/TubePlay.kt` | `Queue`, `PlayRequest`, launcher decision (in-app / Samsung Internet / phone), crash marker |
| `tube/WatchPlayer.kt` | unchanged role: Samsung Internet page reachability + intent (fallback) |
| `ui/tube/TubeNav.kt` | `Page` sealed class + back stack |
| `ui/tube/TubeLoader.kt` | loads page data (coroutines), memory cache with TTL, pagination state |
| `ui/TubeRows.kt` | pure row builders for every page (home, search, subscriptions, library, channel, playlist, menu, login, account, handoff) |
| `ui/Rows.kt` | new Row types: `VideoCard`, `ChannelItem`, `PlaylistItem`, `Skeleton`, `Code`; `VideoItem` becomes compact 16:9 row |
| `ui/TubeActivity.kt` | host: list, back stack, input, intents (tile, kakaowatch links), login polling, ambient |
| `ui/TubePlayerActivity.kt` | GeckoView host in `:player`; `PlayerNav` decisions; lifecycle → setActive |
| `web/tube/index.html` | queue modes `ids=`/`i=`/`list=`, `app=1`; YouTube-like now playing |
| `web/privacy.html` | privacy policy (Data API + OAuth) |
| layouts | `item_tube_card.xml`, `item_tube_channel.xml`, `item_tube_playlist.xml`, `item_tube_skeleton.xml`, `item_tube_code.xml`, `item_video.xml` (compact) |
| removed | `tube/MusicApp.kt`, `PlayTarget`/`PlayPlaces`, TARGETS/PREMIUM screens, `TubeMusicTest` |

---

### Task 1: Toolchain for GeckoView (done in spike — commit)

**Files:** `build.gradle.kts` (KGP 2.4.20 classpath), `settings.gradle.kts` (maven.mozilla.org exclusive for `org.mozilla.geckoview`),
`app/build.gradle.kts` (compileSdk 37, GeckoView dependency, exclude `lib/x86*/**`), `ui/HubActivity.kt` (API 37 nullability).

- [x] Build debug APKs (armeabi-v7a 111 MB, arm64 117 MB, universal 199 MB), all 291 unit tests pass.
- [ ] Commit `빌드: GeckoView(앱 안 유튜브 재생 엔진) + Kotlin 2.4.20 + compileSdk 37`.

### Task 2: Models + parsers for the YouTube app

**Files:** Modify `tube/Tube.kt`; Create `tube/TubeParsers.kt` (move `TubeParsers` there); Test `TubeParsersTest.kt` (new).

**Interfaces (produces):**
- `data class TubeVideo(id, title, channel, durationSec: Int? = null, live: Boolean = false, channelId: String? = null, views: Long? = null, publishedAt: Long? = null)`
- `data class TubeChannel(id: String, title: String, thumb: String?, subscribers: Long? = null, videoCount: Long? = null, uploads: String? = null)`
- `data class TubePlaylist(id: String, title: String, channel: String, thumb: String?, count: Int? = null)`
- `data class TubePage(videos: List<TubeVideo>, next: String?, channels: List<TubeChannel> = emptyList(), playlists: List<TubePlaylist> = emptyList())`
- `TubeParsers.youtubeSearchAll(json): TubePage` (video+channel+playlist), `videoDetails(json): Map<String, TubeVideo>` (snippet, contentDetails, statistics),
  `channels(json): List<TubeChannel>`, `playlists(json): Pair<List<TubePlaylist>, String?>`, `playlistItems(json): Pair<List<String>, String?>`
  (video ids in order, skipping deleted/private), `subscriptions(json): Pair<List<TubeChannel>, String?>`, `iso8601Ms(s): Long?`
- `TubeText.views(n): String` ("조회수 1.2만회"), `TubeText.ago(ms, now): String` ("3일 전"), `TubeText.subscribers(n)` ("구독자 120만명")
- `TubeLinks.uploadsOf(channelId)`: "UC…" → "UU…"

- [ ] Step 1: tests with real-shaped JSON (KR titles, missing statistics, private/deleted playlist items, live, hidden subscriber counts) — fail.
- [ ] Step 2: implement; Step 3: green + existing TubeTest/TubeSearcherTest green; Step 4: commit.

### Task 3: YouTubeData client

**Files:** Create `tube/YouTubeData.kt`; Test `YouTubeDataTest.kt`.

**Interfaces:**
- `class YouTubeData(key: String, token: () -> String?, get: (url: String, headers: Map<String,String>) -> Http.Response)`
- `popular(categoryId: String?, pageToken: String?): TubePage` — `videos?chart=mostPopular&regionCode=KR`
- `search(query, pageToken): TubePage` — `search?type=video,channel,playlist` + details
- `details(ids: List<String>): Map<String, TubeVideo>` (≤50 per call)
- `channel(id): TubeChannel?`, `myChannel(): TubeChannel?`
- `videosOf(playlistId: String, pageToken: String?): TubePage` (playlistItems + details, keeps playlist order)
- `channelPlaylists(channelId, pageToken): TubePage` (playlists in `.playlists`)
- `myPlaylists(pageToken)`, `liked(pageToken)`, `subscriptions(pageToken): TubePage` (channels in `.channels`)
- `subscriptionFeed(maxChannels = 15, perChannel = 3, max = 30): List<TubeVideo>` (newest first)
- Errors: `YouTubeQuotaException` (quotaExceeded/dailyLimitExceeded), `SignInNeededException` (401 or mine-call without token),
  `NetworkException`, `ApiException(message)`; Authorization: `Bearer` when `token()` non-null, else `key=`.

- [ ] Step 1: tests with a scripted fake `get` (asserts path/params/headers, pagination, feed merge/sort/dedupe, 401 → SignInNeeded, quota) — fail.
- [ ] Step 2–4: implement, green, commit.

### Task 4: Sign-in (device flow) + token store

**Files:** Create `tube/TubeAuth.kt`; Modify `net/Http.kt` (`post(url, form, headers)`); `app/build.gradle.kts` (BuildConfig
`YOUTUBE_OAUTH_CLIENT_ID`/`SECRET`); Test `TubeAuthTest.kt`.

**Interfaces:**
- `data class DeviceCode(deviceCode, userCode, verificationUrl, expiresAtMs: Long, intervalSec: Int)`
- `sealed interface Poll { Pending; SlowDown; Denied; Expired; Granted }`
- `interface TokenStore { fun load(): Tokens?; fun save(t: Tokens); fun clear() }`, `data class Tokens(access, refresh, expiresAtMs)`
- `class TubeAuth(clientId, clientSecret, post, store, now)`: `configured`, `signedIn`, `start(): DeviceCode`, `poll(code): Poll`,
  `accessToken(): String?` (refresh when < 60 s left; `invalid_grant` → clear + null), `signOut()` (revoke best-effort + clear)
- `object TubeAccount { fun auth(context): TubeAuth }` (singleton with `KeystoreTokenStore`, falls back to private prefs if Keystore unavailable)

- [ ] Step 1: tests: device code parse; pending→slow_down(interval+5)→granted; denied; expired (expires_in passed / `expired_token`);
  refresh ok; refresh invalid_grant → signed out; sign-out posts revoke and clears even when offline; secrets never in exceptions — fail.
- [ ] Step 2–4: implement, green, commit.

### Task 5: Player page queues (web)

**Files:** Modify `web/tube/index.html`; `tools/tube-sim/sim.mjs` (+scenarios), `tools/tube-sim/fake_yt.js` (loadPlaylist with ids/list).

- Hash params: `ids=a,b,…` (≤50 valid ids) + `i=k` → `loadPlaylist(ids, k)`; `list=PL…`(playlist id `[A-Za-z0-9_-]{2,64}`) + `i=k` →
  `loadPlaylist({list, listType:'playlist', index:k})`; `mix=1` unchanged; `app=1` → '다른 곡 찾기' becomes '목록으로'.
- Up next shows the following 5 of whatever queue is active; continue-after-end only for mix.
- [ ] Step 1: sim scenarios `목록재생_ids`, `재생목록_list`, `잘못된목록` fail on current page.
- [ ] Step 2: implement; Step 3: sim 226 + allvp + random + real smoke; Step 4: commit.

### Task 6: Player launcher + crash guard

**Files:** Create `tube/TubePlay.kt`; Modify `tube/Tube.kt` (`TubeLinks.playerUrl(base, video, queue, nonce, inApp)`); Test `TubePlayTest.kt`.

**Interfaces:**
- `sealed interface Queue { Mix; Single; data class Ids(ids, index); data class Playlist(id, index) }`
- `data class PlayRequest(video: TubeVideo, queue: Queue)`
- `enum class PlayRoute { IN_APP, BROWSER, PHONE }`
- `object TubePlay { fun route(engineOk: Boolean, crashedRecently: Boolean, browserAvailable: Boolean): PlayRoute;
   fun crashedRecently(context, now): Boolean; fun markStarting(context); fun markStarted(context); fun inAppIntent(context, req, now): Intent? }`

- [ ] Step 1: tests (URL for each queue, ids capped/validated, route table, stale marker → crash recorded for 6 h, fresh marker ignored) — fail.
- [ ] Step 2–4: implement, green, commit.

### Task 7: In-app player activity (GeckoView, `:player`)

**Files:** Create `ui/TubePlayerActivity.kt` (+ `ui/PlayerNav.kt` pure decisions); Modify `AndroidManifest.xml`; Test `PlayerNavTest.kt`.

**Interfaces:** `PlayerNav.decide(uri, pageBase): Decision` where `Decision = Allow | Block | App(Uri) | Phone(String)`;
`TubePlayerActivity.intent(context, url)`.

- Runtime singleton per process; session settings `suspendMediaWhenInactive(true)`, `useTrackingProtection(false)`;
  permission: autoplay → ALLOW, else DENY; navigation via `PlayerNav`; `onPageStop(false)` → fallback; `onCrash/onKill` → fallback;
  MediaSession onPlay → keep screen on, onPause/onStop/onDeactivated → clear; onStart/onStop/ambient → setActive; rotary → scroll;
  `markStarting` before runtime, `markStarted` on first contentful paint.
- [ ] Step 1: PlayerNav tests — fail. Step 2: implement activity + manifest. Step 3: assembleDebug, lint. Step 4: commit.

### Task 8: Pages, back stack, loader

**Files:** Create `ui/tube/TubeNav.kt`, `ui/tube/TubeLoader.kt`; Test `TubeNavTest.kt`, `TubeLoaderTest.kt`.

**Interfaces:**
- `sealed class Page { Home; Search(query); Subscriptions; Library; History; Liked; MyPlaylists; Channel(id, title);
   Playlist(id, title); Menu(video, from: Page); Login; Account; Handoff(h) }`
- `class TubeNav { val top: Page; fun push(p); fun back(): Boolean; fun reset(p = Home) }` (no duplicate consecutive pages, Menu never under Menu)
- `class TubeLoader(scope, api: TubeApi-like interface, now)`: `state(page): Load` (`Loading | Ready(data, nextToken) | Failed(msg)`),
  `load(page, force)`, `more(page)`, memory TTL 15 min (30 for popular), `clearMine()` on sign-out.

- [ ] Step 1–4 with tests (stack rules; cache hit/miss/TTL; more appends; failure keeps old data; clearMine drops authorized pages).

### Task 9: Rows + layouts (YouTube look)

**Files:** Modify `ui/Rows.kt`; Create layouts `item_tube_card.xml`, `item_tube_channel.xml`, `item_tube_playlist.xml`,
`item_tube_skeleton.xml`, `item_tube_code.xml`; modify `item_video.xml` (compact 64×36 dp thumb), drawables (duration badge, round avatar);
Modify `ui/TubeRows.kt` (builders for every page); Test render shots + `TubeRowsTest.kt`.

- Card: 16:9 thumbnail full width (12 dp corners) + duration/LIVE badge bottom-right + title 15 sp bold 2 lines + meta 13 sp.
- [ ] Step 1: `TubeRowsTest` (home with/without login, sections order, '더 보기', skeletons while loading, errors as notes,
  library rows, channel header, playlist '모두 재생', login code rows) — fail. Step 2: implement. Step 3: renders reviewed by eye. Step 4: commit.

### Task 10: Host activity rewrite

**Files:** Modify `ui/TubeActivity.kt`, `tile/TubeTileService.kt` (mix → launcher), remove `tube/MusicApp.kt` + PlayTarget/PlayPlaces;
Tests: rewrite `TubeWatchTest` → `TubePlayFlowTest`, new `TubeAppTest`, update `TubeShortcutTest`, `TubeUpdateTest`, rewrite `TubeJourneyTest`.

- Tap card → `TubePlay` route (in-app intent / browser / phone) with the right `Queue`; long-press → Menu page; channel/playlist/library
  navigation; login page polls only while visible; sign-out; handoff links as before; tile shortcuts as before; back = one page.
- [ ] Step 1: tests — fail. Step 2: implement. Step 3: full suite + journey 200 seeds. Step 4: commit.

### Task 11: Release wiring, privacy page, README

**Files:** `.github/workflows/release.yml` (OAuth secrets env + masking; no printing), `web/privacy.html`, `README.md`.

- [ ] Steps: release build locally (unsigned) for size; lint; commit.

### Task 12: Sign-in setup with the user

- [ ] Google Cloud (same project as the API key): Google Auth Platform → Branding/Audience (External, In production) → Data access
  (youtube.readonly) → Clients → Create → "TVs and Limited Input devices". User pastes client ID/secret into GitHub Secrets
  (`YOUTUBE_OAUTH_CLIENT_ID`, `YOUTUBE_OAUTH_CLIENT_SECRET`). Never type them in chat logs.

### Task 13: Verification + ship

- [ ] Full unit tests (local + CI-like), lint, renders, sim (allvp + random), real smoke (queues), independent review agent,
  merge `youtube-app` → `main`, release, check APK/update.json, Pages live, report with screenshots.
