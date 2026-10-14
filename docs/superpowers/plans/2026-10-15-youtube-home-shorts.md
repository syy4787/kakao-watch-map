# 유튜브 앱처럼 (탭·칩·알고리즘 홈·Shorts) Implementation Plan

> **For agentic workers:** Executed inline (superpowers:executing-plans) by the agent that wrote the spec, except the web tasks (W1·W2),
> which go to one subagent working only on `web/` and `tools/tube-sim/`. This plan fixes task boundaries, file ownership, interfaces and the
> tests each task must make fail first; the code for each step lives in that step's commit. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make the watch YouTube app look and behave like the YouTube app — tabs (홈·Shorts·구독·나), home category chips with an
"algorithm" 전체 feed that includes non-subscribed videos (YouTube Mix recommendations harvested through the official player), a separate
Shorts player, and YouTube-like inner screens (search page, subscriptions with avatar strip, 나, channel tabs, 관심 없음).

**Architecture:** Data (`tube/`: models+parsers with category/portrait, Data API calls for UU*/search kinds, on-device stores for mix
harvests/dismissals/seen shorts, a cross-process inbox) → pure home composer (`tube/TubeHome.kt`) → pages/feeds (`ui/tube/`) → rows/layouts
(`ui/Rows.kt`, `ui/TubeRows.kt`) → host (`ui/TubeActivity.kt`) → player process (`ui/PlayerNav.kt`, `ui/TubePlayerActivity.kt`) → web pages
(`web/tube/index.html` reports mixes, new `web/tube/shorts.html`).

**Tech Stack:** Kotlin 2.4.20, compileSdk 37 / target 36 / min 30, WearableRecyclerView, GeckoView, YouTube Data API v3 + IFrame API,
Robolectric 4.17 (+ NATIVE render tests), Playwright 1.56 simulator with the fake IFrame player.

**Spec:** `docs/superpowers/specs/2026-10-15-youtube-home-shorts-design.md`

## Global Constraints

- Official IFrame player only; player viewport ≥ 200×200 CSS px; nothing in front of it; scripted/automatic playback only when > half
  visible (we use 90%); one auto-playing player per page; thumbnails that start playback ≥ 120×70 px; no background playback (III.I.9).
- API data only in memory (15 min–3 h TTL); on-device: history 30 d (existing), mix harvest IDs 7 d (≤ 12 harvests × ≤ 49 IDs),
  dismissed videos/channels 30 d, seen Shorts 30 d — all cleared by '기록 지우기'. No scores/metrics from API data (III.E.4.h).
- Quota: home 전체 refresh ≈ 40 units; search bucket (100/day) only for 실시간 and the Shorts top-up, each cached 3 h, only when needed; 뉴스 = chart 25.
- Re-consent: `TubeState.TERMS_VERSION` 1 → 2 with updated terms text + `web/privacy.html`.
- Links page → app: `kakaowatch://tube/recs?seed=<id>&ids=<id,…≤49>` and `kakaowatch://tube/seen?v=<id>` (in-app only, silent),
  `kakaowatch://tube/channel?v=<id>` (opens the app's channel page; browser pages use the `intent://…#Intent;scheme=kakaowatch;package=com.mywatch.kakaomap;end` form).
- Shorts page URL: `<base>shorts.html#ids=<≤50 ids>&i=<start>&n=<epoch ms>&app=1`; viewport width 280.
- Korean copy; no YouTube/Shorts logos; map screens untouched; never print keys; no destructive git.

---

## File map

| File | Responsibility |
|---|---|
| `tube/Tube.kt` | `TubeVideo.categoryId/portrait/isShort`, `Uploads`, `TubeLinks.uploads/shortsUrl/phoneShortsUrl/recs/seen/channelLink`, `PlayedHistory` keeps `cat`·`por` |
| `tube/TubeParsers.kt` | `snippet.categoryId`, `player.embedWidth/embedHeight` |
| `tube/YouTubeData.kt` | `player` part + `maxHeight`, `uploads(id, kind, token)`, `uploadsFeed(ids, kind, n)`, `shortsOf(ids, n)`, `searchVideos(params, token)`, `channelOfVideo(id)` |
| `tube/TubeRecs.kt` (new) | `Harvest`, `MixHarvests`, `Marks` (pure) |
| `tube/TubeInbox.kt` (new) | cross-process message files (`post`, `drain`) |
| `tube/TubeService.kt` | `TubeState`: harvests, dismissals, seen shorts, `ingest(links)`, `clear()`, `TERMS_VERSION = 2` |
| `tube/TubeHome.kt` (new) | `HomeChip`, `HomeSources`, `HomeContext`, `HomeFeed`, `TubeHome` composer |
| `ui/tube/TubePages.kt` | `Home(chip)`, `Shorts`, `Find`, `Subscriptions(chip)`, `SubsChannels`, `Channel(id,title,tab)`, `SubsChip`, `ChannelTab`, `TubeTab`, `TubeNav.swapTop`; `Chart` removed |
| `ui/tube/TubeLoader.kt` | feeds `Recs`, `Watched`, `ShortsPool`, `ShortsSearch`, `Live`, `News`, `ChannelUploads`, `SubsShorts`, `VideoChannel`; `forget(prefix, keep)` |
| `ui/tube/LiveBackend.kt` | fetch for the new feeds |
| `ui/Rows.kt` + layouts | `Tabs`, `ChipBar`, `ShortsShelf`, `ShortsPair`, `ShortCard`(tap payload), `AvatarStrip`, `VideoCard.badge`; `item_tube_tabs.xml`, `item_tube_chipbar.xml`, `item_tube_chipbar_chip.xml`, `item_tube_shelf.xml`, `item_tube_short.xml`, `item_tube_pair.xml`, `item_tube_avatars.xml`, `item_tube_avatar.xml`, `ic_shorts.xml` |
| `ui/TubeRows.kt` | builders: home(chip), shorts, find, subscriptions(chip), subsChannels, library(나), channel(tab), menu(+관심 없음), search(+Shorts 선반) |
| `ui/TubeActivity.kt` | tabs/chips/back, base+derived feeds, inbox drain, queue rules, Shorts open, channel link, dismissals |
| `ui/PlayerNav.kt` | `shorts.html` page, `Decision.Note`, `/channel` → App |
| `ui/TubePlayerActivity.kt` | Note → inbox; Shorts rotary = one page per detent |
| `web/tube/index.html` (W1) | mix report + channel link |
| `web/tube/shorts.html` (W2, new) | Shorts player |
| `tools/tube-sim/sim.mjs` (W1), `tools/tube-sim/shorts_sim.mjs` (W2, new), `.github/workflows/pages.yml` (W2) | simulators |
| `web/privacy.html`, `res/values/strings.xml`, `README.md` | copy |

---

### Task 0: Live API probe (de-risk UU*/player dims/categories)

**Files:** `app/src/test/java/com/mywatch/kakaomap/YouTubeDataLiveTest.kt` (append a second test using raw `Http.get` + JSON, no new app code).

- [ ] Add `theApiHasWhatTheNewHomeNeeds`: for channel `UCweOkPb1wVVH0Q0Tlj4a5Pw` ask `playlistItems` for `UUSH…`, `UULF…`, `UULV…`
      (count, first id); `videos.list part=player,contentDetails,snippet maxHeight=360` for the first UUSH and UULF ids (embedWidth/Height,
      duration, categoryId); `chart=mostPopular regionCode=KR videoCategoryId=20` count. Write rows to `build/youtube-live.md`; assert only that
      calls returned 200 (the report is the result).
- [ ] Push branch `watch-polish`, dispatch `youtube-api-check.yml` on it (`gh api -X POST …/dispatches -f ref=watch-polish`), read the run summary.
- [x] Record the findings in this plan (Task 0 results) and adjust fallbacks if any assumption fails.

**Task 0 results (run 38652362972, 2026-10-14 23:11 UTC):** all assumptions hold.
- `UUSH/UULF/UULV` answer 200 for 1theK (218 Shorts · 19 781 long · 2 live) and YTN (1 753 · 17 035 · 1 213); `UU` = everything.
- `part=player&maxHeight=360`: Short 203×360 (PT13S, PT33S), long 640×360, **live stream 0×0 and `P0D`** → treat ≤ 0 as unknown.
- `categoryId` present (10 music, 25 news). Charts KR: 10 → 9/10 music, 20 → 10/10 gaming, **25 → 7/10 news** (rest 22),
  1/17 mixed; no category → music+gaming+entertainment. ⇒ **뉴스 = `chart=mostPopular videoCategoryId=25` filtered to category 25
  (1 unit) — no search.** 실시간 stays a search (`eventType=live` returned 10).
- Searches `eventType=live`, `videoCategoryId=25`, `videoDuration=short q=#shorts` all 200 with 10 results.

### Task 1: Models, parsers, links

**Files:** `tube/Tube.kt`, `tube/TubeParsers.kt`; tests `YouTubeParsersTest.kt`, `TubeTest.kt`.

**Produces:** `TubeVideo(…, categoryId: String? = null, portrait: Boolean? = null)`, `TubeVideo.isShort`, `TubeVideo.SHORT_MAX_SEC = 180`;
`enum class Uploads(prefix) { ALL("UU"), LONG("UULF"), SHORTS("UUSH"), LIVE("UULV") }`; `TubeLinks.uploads(channelId, kind): String?`;
`TubeLinks.shortsUrl(base, ids, start, nonce, inApp): String`; `TubeLinks.phoneShortsUrl(id)`; `TubeLinks.recs(link): Harvest?`
(seed + ≤ 49 valid distinct ids, not the seed); `TubeLinks.seen(link): String?`; `TubeLinks.channelLink(link): String?` (video id);
`PlayedHistory` encodes `cat`, `por`.

- [ ] Failing tests: categoryId/portrait parsed (`player.embedWidth 203/embedHeight 360` → portrait, 640/360 → landscape, missing → null,
      non-digit category → null); `isShort` cases (portrait 59 s → true, 181 s → false, live → false, unknown portrait → false);
      `uploads` prefixes and bad ids; `shortsUrl` (ids filtered/deduped/≤ 50, `i` clamped, `app=1`, `n=`); link readers reject other hosts/paths,
      bad ids, > 49 ids trimmed, seed removed from ids, `intent://` not accepted (only the plain form reaches the app); history round-trip keeps cat/por.
- [ ] Implement; run `rt.sh 'com.mywatch.kakaomap.YouTubeParsersTest' 'com.mywatch.kakaomap.TubeTest'`.
- [ ] Commit `유튜브: 동영상 분류·세로(Shorts) 판별, 업로드 종류(UU·UULF·UUSH·UULV), Shorts·재생 화면 링크`.

### Task 2: Data API calls

**Files:** `tube/YouTubeData.kt`; test `YouTubeDataTest.kt`.

**Produces:** `VIDEO_PARTS += player`, `VIDEO_FIELDS += snippet/categoryId, player(embedWidth,embedHeight)`, `maxHeight=360` on every
`videos.list`; `fun uploads(channelId, kind, pageToken): TubePage` (LONG: UULF → on 404/empty first page UU filtered `!isShort`;
SHORTS: UUSH → on 404 UU filtered `isShort`; LIVE: UULV → on 404 empty); `suspend fun uploadsFeed(channelIds, kind, perChannel): List<TubeVideo>`;
`suspend fun shortsOf(channelIds, perChannel): List<TubeVideo>` (only `isShort`); `fun searchVideos(extra: Map<String,String>, pageToken): TubePage`
(type=video, regionCode, relevanceLanguage, search bucket, details → order kept, upcoming dropped); `fun channelOfVideo(id): Pair<String,String>?`.

- [ ] Failing tests with the fake transport: parts/fields/maxHeight in popular·details·liked URLs; uploads fallbacks (404 → UU filtered,
      LIVE 404 → empty); uploadsFeed skips failing channels, throws if all fail; shortsOf keeps only isShort in channel round-robin order;
      searchVideos sends `eventType=live`/`videoCategoryId=25`/`videoDuration=short` exactly as given and counts as search (quota message);
      channelOfVideo returns channel id + title, null for unknown.
- [ ] Implement; run `rt.sh 'com.mywatch.kakaomap.YouTubeDataTest'`; commit `유튜브 데이터: 업로드 종류·Shorts·조건 검색·동영상의 채널`.

### Task 3: On-device stores + inbox + re-consent

**Files:** new `tube/TubeRecs.kt`, `tube/TubeInbox.kt`; `tube/TubeService.kt`; tests new `TubeRecsTest.kt`, `TubeInboxTest.kt`, `TubeStateTest.kt`.

**Produces:** `data class Harvest(seed, ids, at)`; `object MixHarvests { KEEP_MS=7d; MAX=12; add(list,h); prune(list,now); order(list,max=100):
List<String>; encode/decode }`; `data class Mark(id, at)`; `object Marks { KEEP_MS=30d; MAX=300; add; prune; encode/decode }`;
`class TubeInbox(dir, now) { post(link); drain(): List<String>; MAX=50; companion of(context) }`;
`TubeState.harvests()/addHarvest/recsStamp/dismissedVideos()/dismissVideo/dismissedChannels()/dismissChannel/seenShorts()/markSeen/ingest(links)`,
`clear()` clears all of them, `TERMS_VERSION = 2`.

- [ ] Failing tests: harvest add (same seed replaces, newest first, cap 12, ids ≤ 49, prune 7 d); order (round-robin newest first, dedupe,
      excludes all seeds, max); marks (30 d, cap, newest wins); inbox (atomic: no partial reads, survives a new instance = other process,
      cap 50 drops oldest, drain empties, garbage files ignored); state ingest of recs/seen/garbage; clear wipes all; agree() needs version 2
      (a v1 agreement is not enough).
- [ ] Implement; run the three tests; commit `유튜브: 믹스 추천·관심 없음·본 Shorts 보관(7·30일), 재생 화면 → 앱 받은 편지, 다시 동의(2)`.

### Task 4: Home composer (pure)

**Files:** new `tube/TubeHome.kt`; test new `TubeHomeTest.kt`.

**Produces:** `enum class HomeChip { ALL, MIXES, MUSIC, GAMING, LIVE, NEWS, RECENT, WATCHED, NEW }`;
`data class HomeSources(recs, subs, watched, trending, music, gaming, live, news, shorts)` (lists, default empty);
`data class HomeContext(history: List<Played>, dismissedVideos: Set<String>, dismissedChannels: Set<String>, subscribed: Set<String>, seenShorts: Set<String>, now: Long)`;
`data class HomeFeed(videos: List<TubeVideo>, shorts: List<TubeVideo>, shelfAt: Int)`;
`object TubeHome { fun feed(chip, s, ctx): HomeFeed; fun mixSeeds(ctx, music): List<TubeVideo>; fun watchedChannels(history, subscribed, max=4): List<String>;
fun shortsChannels(subscribedOrder: List<String>, history, max=12): List<String>; fun shorts(candidates, ctx): List<TubeVideo>; const SHELF_AT=3; SHELF_MAX=10 }`.

- [ ] Failing tests: ALL pattern `R S R T W R S T` with exhausted sources skipped; dedupe; excludes watched/dismissed video/channel/upcoming/shorts;
      channel spread (≤ 2 per 10); shelf at 3 only with ≥ 2 shorts; cold start = trending; MUSIC/GAMING interleave pool category + chart;
      LIVE/NEWS; RECENT (7 d, newest first); WATCHED = history; NEW excludes history and subscribed channels; MIXES seeds (music first,
      top-up from chart, ≤ 8); shorts pool (portrait only, channel alternation, seen moved back unless < 6).
- [ ] Implement; run; commit `유튜브 홈: 칩마다 목록 만들기 (믹스 추천·구독·본 채널·인기 번갈아, Shorts 선반)`.

### Task 5: Pages, nav, feeds, backend

**Files:** `ui/tube/TubePages.kt`, `ui/tube/TubeLoader.kt`, `ui/tube/LiveBackend.kt`; tests `TubeNavTest.kt` (+ loader cases).

**Produces:** pages/enums listed in the file map; `TubeNav.swapTop(p)`; `Page.tab: TubeTab?`; feeds listed in the file map
(`ChannelVideos` replaced by `ChannelUploads(id, Uploads.LONG)`); `TubeLoader.forget(prefix, keep)`; LiveBackend mappings.

- [ ] Failing tests: save/restore of every new page (chip/tab kept), old saved `chart` ignored, swapTop at depth 1 and 2, dropMine drops
      Subscriptions(chip)/SubsChannels; forget drops stale `recs/…` slots and cancels their jobs.
- [ ] Implement; run `TubeNavTest`; commit `유튜브: 새 화면(홈 칩·Shorts·검색·구독 칩·채널 탭)과 받을 거리`.

### Task 6: Row types, layouts, binding

**Files:** `ui/Rows.kt`, new layouts/drawables, `res/values/strings.xml`; test `TubeRowsTest.kt` (binding cases via Robolectric).

- [ ] Failing tests: binding Tabs (selected tab flagged in contentDescription "선택됨", tap → `Row.Action("tab:SHORTS")`), ChipBar (selected chip,
      tap → `Row.Action("chip:<bar>:<id>")`, scroll offset kept across rebinds), ShortsShelf/ShortsPair tap → `Row.ShortCard(video, list, index)`,
      thumbnails ≥ 120×70 px, AvatarStrip tap → `Row.ChannelItem`, VideoCard badge.
- [ ] Implement; commit `유튜브 화면 부품: 탭·칩 줄·Shorts 선반·격자·채널 사진 줄`.

### Task 7: Screen builders

**Files:** `ui/TubeRows.kt`; test `TubeRowsTest.kt`.

- [ ] Failing tests for each builder's rows (titles, order, chip selected, empty/error/loading states, sign-in states, queue = Mix for feed cards,
      Ids for liked, Playlist for playlists, mix cards badge + Queue.Mix, menu has 관심 없음/채널 추천 안 함, search has Shorts shelf after 3 videos).
- [ ] Implement; commit `유튜브 화면: 홈 칩·Shorts·검색·구독·나·채널 탭·메뉴`.

### Task 8: Host activity

**Files:** `ui/TubeActivity.kt`; tests `TubeAppTest.kt`, `TubeJourneyTest.kt`, `TubeShortcutTest.kt`.

- [ ] Failing tests: tabs (홈 resets, others swap; back from a tab → home; back from chip → 전체 → close); chips load only their feeds
      (실시간/뉴스 searched only when opened); inbox recs → 전체 shows them (and a new Recs key replaces the old slot); 관심 없음 removes the card
      and survives reopening; Shorts tab opens the shorts page once when ready (not when left/asleep — deferred like other plays), returns to grid;
      Shorts route browser/phone; channel link (`kakaowatch://tube/channel?v=`) resolves and opens the channel; Find page opens input, recent searches,
      submit replaces Find; journey invariant updated (feed taps → Mix, playlists → list order); re-consent shows terms for v1 users.
- [ ] Implement; run all Tube* tests; commit `유튜브 앱: 탭·칩·알고리즘 홈·Shorts 열기·채널 링크·관심 없음`.

### Task 9: Player process

**Files:** `ui/PlayerNav.kt`, `ui/TubePlayerActivity.kt`; tests `PlayerNavTest.kt`, `TubePlayerTest.kt`.

- [ ] Failing tests: shorts.html is our page; recs/seen → `Decision.Note`; `/channel` → App; others still blocked; Note posts to the inbox and
      does not finish the activity; rotary on shorts URL scrolls one view height per detent (debounced), index URL unchanged.
- [ ] Implement; commit `재생 화면 프로세스: Shorts 화면, 믹스·본 Shorts 알림 받기, 베젤 한 칸 = Shorts 한 장`.

### Task W1 (subagent): index.html mix report + channel link + simulator scenarios

**Files:** `web/tube/index.html`, `tools/tube-sim/sim.mjs` (and `fake_yt.js` only if needed).

- [ ] Simulator first: scenarios `믹스알림` (app=1: one recs nav per seed with ≤ 49 ids, no seed inside; continueMix sends the new seed),
      `믹스알림-인터넷앱` (no app=1 → none), `채널링크` (tap channel → `kakaowatch://tube/channel?v=<current>` in app / intent form in browser),
      invariants unchanged. Then implement; `node sim.mjs --retry=1` all green (+ `--random=30`).

### Task W2 (subagent): Shorts player page + simulator + pages workflow

**Files:** new `web/tube/shorts.html`, new `tools/tube-sim/shorts_sim.mjs`, `tools/tube-sim/README.md`, `.github/workflows/pages.yml`.

- [ ] Scenarios first (see spec 'Shorts 재생 화면'), then the page. Invariants per step: one player max, player 200×200, nothing over it,
      start only when ≥ 90% visible and page visible, thumbnails ≥ 120×70, no horizontal overflow, visible elements inside the round screen,
      wake lock only while playing, no page errors. `node shorts_sim.mjs --retry=1` green; pages.yml runs both simulators.

### Task 10: Copy, privacy, render

**Files:** `res/values/strings.xml`, `web/privacy.html`, `README.md`, `render/ScreensRenderTest.kt`.

- [ ] Terms text + privacy (what/where/how long/clear), README YouTube row; render tests for home(전체 with shelf, 음악), Shorts grid, 구독(사진 줄),
      나, 채널(Shorts 탭), 메뉴, 검색 화면 — look at every PNG on the 480 round mask and fix clipping.
- [ ] Commit `문서·처리방침: 추천에 쓰는 정보, 다시 동의 · 화면 그림 시험`.

### Task 11: Verify

- [ ] Full unit suite, lint, `-Prender`, both simulators (+ random), `YouTube API check` run, independent reviews (code + policy), fix findings.

### Task 12: Release

- [ ] Fast-forward `main`, push, watch release + pages workflows, confirm `update.json`/APK/patch and the live `shorts.html`, report.
