// 워치 Shorts 재생 화면 시뮬레이터 — web/tube/shorts.html 을 가짜 유튜브 플레이어(fake_yt.js)로 띄워 사용자가 겪을 경우를 돌리고
// 매 단계 뒤 유튜브 규칙·단단함 불변식을 확인한다 (sim.mjs 의 Shorts 판).
//   node shorts_sim.mjs [--only=이름] [--allvp=1] [--vp=280] [--retry=1] [--shots=폴더] [--page=경로] [--random=개수 --steps=개수 --seed=시작]
import fs from 'node:fs';
import path from 'node:path';

let pw;
try { pw = await import('playwright'); } catch (e) { pw = await import('/opt/npm-tools/node_modules/playwright/index.mjs'); }
const { chromium } = pw;
const args = Object.fromEntries(process.argv.slice(2).map(a => { const [k, v] = a.replace(/^--/, '').split('='); return [k, v ?? '1']; }));
const DIR = path.dirname(new URL(import.meta.url).pathname);
const PAGE_PATH = args.page || path.resolve(DIR, '../../web/tube/shorts.html');
const PAGE = fs.readFileSync(PAGE_PATH, 'utf8');
const FAKE = fs.readFileSync(path.join(DIR, 'fake_yt.js'), 'utf8');
const BASE = 'https://syy4787.github.io/kakao-watch-map/tube/';
const URL0 = BASE + 'shorts.html';
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64');
const SHOTS = args.shots || path.join(DIR, 'shots');
fs.mkdirSync(SHOTS, { recursive: true });

// Shorts 번호 (11자) 와 목록
const S = k => 'sh' + String(k).padStart(9, '0');
const L = n => Array.from({ length: n }, (_, k) => S(k + 1)).join(',');
const INTENT = p => 'intent://' + p + '#Intent;scheme=kakaowatch;package=com.mywatch.kakaomap;end';
// 주소 속 n=NOW → 지금(앱에서 방금 고름), n=OLD → 두 시간 전
function stamp(h) { return String(h).replace(/NOW/g, String(Date.now())).replace(/OLD/g, String(Date.now() - 2 * 3600e3)); }
function rng(seed) { let s = seed >>> 0 || 1; return () => { s ^= s << 13; s >>>= 0; s ^= s >> 17; s ^= s << 5; s >>>= 0; return s / 4294967296; }; }

// ---------- 페이지 띄우기 ----------
async function openPage(browser, sc) {
  const vw = sc.vw || 280, vh = sc.vh || vw;
  // 기본: 소리 자동 재생 됨 · 30초짜리 Shorts (시나리오가 바꿈)
  sc = { ...sc, cfg: { autoplay: true, duration: 30, ...(sc.cfg || {}) } };
  const ctx = await browser.newContext({ viewport: { width: vw, height: vh }, deviceScaleFactor: sc.dpr || 480 / vw, isMobile: true, hasTouch: true, locale: 'ko-KR' });
  const page = await ctx.newPage();
  const errors = [];
  const nav = { loads: 0 };
  page.on('load', () => { nav.loads++; });
  page.on('pageerror', e => errors.push(String(e.message || e)));
  page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource|net::ERR/.test(m.text())) errors.push('console: ' + m.text()); });
  await page.addInitScript(({ cfg }) => {
    window.__ytcfg = cfg;
    if (cfg.freshMs) window.__kwFreshMs = cfg.freshMs;
    if (cfg.loopIdleMs) window.__kwLoopIdleMs = cfg.loopIdleMs;
    if (cfg.apiWaitMs) window.__kwApiWaitMs = cfg.apiWaitMs;
    window.__navs = [];
    window.__navHash = [];   // 링크마다 그때 페이지가 연 주소 (앱 안인지)
    window.__lastHash = location.hash;
    window.__life = Math.random();
    window.addEventListener('hashchange', () => { window.__lastHash = location.hash; });
    window.__kwNavigate = u => { window.__navs.push(u); window.__navHash.push(window.__lastHash); };
    const wl = window.__wl = { active: 0, max: 0, requests: 0, sentinels: [] };
    const fake = {
      request(type) {
        wl.requests++;
        return new Promise((res, rej) => {
          if (cfg.wakeLock === 'reject') { rej(new DOMException('no', 'NotAllowedError')); return; }
          setTimeout(() => {
            if (document.visibilityState !== 'visible') { rej(new DOMException('hidden', 'NotAllowedError')); return; }
            wl.active++; wl.max = Math.max(wl.max, wl.active);
            const ls = [];
            const s = { type, released: false, addEventListener(t, f) { if (t === 'release') ls.push(f); }, release() { if (!s.released) { s.released = true; wl.active--; ls.forEach(f => { try { f(); } catch (e) {} }); } return Promise.resolve(); } };
            wl.sentinels.push(s);
            res(s);
          }, cfg.wakeDelay ?? 30);
        });
      },
    };
    try { Object.defineProperty(Navigator.prototype, 'wakeLock', { configurable: true, get: () => (cfg.wakeLock === 'none' ? undefined : fake) }); } catch (e) {}
    let vis = 'visible';
    Object.defineProperty(Document.prototype, 'visibilityState', { configurable: true, get: () => vis });
    Object.defineProperty(Document.prototype, 'hidden', { configurable: true, get: () => vis !== 'visible' });
    window.__setVisible = v => {
      vis = v ? 'visible' : 'hidden';
      if (!v) wl.sentinels.forEach(s => s.release());
      document.dispatchEvent(new Event('visibilitychange'));
    };
  }, { cfg: sc.cfg || {} });
  if (sc.cfg?.clock) await page.clock.install();
  await page.route(BASE + '**', r => r.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: PAGE }));
  await page.route('https://www.youtube.com/iframe_api*', r => sc.cfg?.apiFail ? r.abort() : r.fulfill({ status: 200, contentType: 'text/javascript', body: FAKE }));
  await page.route('https://www.youtube.com/oembed*', r => {
    if (sc.cfg?.oembedFail) return r.abort();
    const u = new URL(r.request().url());
    const id = (new URL(u.searchParams.get('url')).searchParams.get('v')) || '?';
    const title = 'Shorts ' + id + (sc.cfg?.longTitles ? ' 아주 긴 제목이 붙은 Shorts #shorts #유튜브 #워치 (Official) 가사 포함 버전 끝까지' : '');
    const author = '채널 ' + id.slice(0, 3) + (sc.cfg?.longAuthors ? ' 이름이 아주 긴 채널 Official' : '');
    return r.fulfill({ status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({ title, author_name: author }) });
  });
  await page.route('https://i.ytimg.com/**', r => sc.cfg?.thumbFail ? r.abort() : r.fulfill({ status: 200, contentType: 'image/png', body: PNG }));
  await page.goto(URL0 + stamp(sc.hash ?? '#ids=' + L(4) + '&n=NOW'));
  return { ctx, page, errors, nav };
}

// ---------- 페이지 상태 읽기 ----------
async function snap(page) {
  return page.evaluate(() => {
    const fy = window.__fakeYT || { players: [], violations: [], calls: [], starts: [], soft: [], errors: 0, log: [] };
    const live = fy.players.filter(x => !x.destroyed);
    const p = live.slice(-1)[0] || null;
    const frames = [...document.querySelectorAll('iframe')];
    const f = frames[0] || null;
    const b = f ? f.getBoundingClientRect() : null;
    let overlay = null;
    if (b) {
      for (const [fx, fy2] of [[.5, .5], [.1, .1], [.9, .1], [.1, .9], [.9, .9], [.5, .04], [.5, .96], [.04, .5], [.96, .5]]) {
        const x = b.left + b.width * fx, y = b.top + b.height * fy2;
        if (x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) continue;
        const el = document.elementFromPoint(x, y);
        if (el && el !== f) { overlay = `${el.tagName}.${el.className} @${fx},${fy2}`; break; }
      }
    }
    const slides = [...document.querySelectorAll('.slide')];
    const H = slides[0] ? slides[0].getBoundingClientRect().height : 0;
    const active = document.body.dataset.active != null ? +document.body.dataset.active : -1;
    const act = slides.find(s => +s.dataset.k === active) || null;
    const shown = e => !!(e && !e.hidden && e.offsetParent && !e.closest('[hidden]'));
    const txt = e => shown(e) ? e.textContent.replace(/\s+/g, ' ').trim() : '';
    const ch = act && act.querySelector('.ch');
    const thumbs = [...document.querySelectorAll('.ph img')].map(i => {
      const r = i.getBoundingClientRect();
      let t = 0;
      for (let e = i; e && e !== document.body; e = e.parentElement) { const tr = getComputedStyle(e).transform; if (tr && tr !== 'none') t++; }
      return { w: r.width, h: r.height, t, vis: r.bottom > 0 && r.top < innerHeight, src: !!i.getAttribute('src'), k: +(i.closest('.slide')?.dataset.k ?? -1) };
    });
    const retry = document.getElementById('retry');
    const toList = document.getElementById('toList');
    return {
      vis: document.visibilityState, scrollY, vw: innerWidth, vh: innerHeight, sw: document.scrollingElement.scrollWidth,
      H, nSlides: slides.length, end: slides.findIndex(s => s.classList.contains('end')),
      msg: txt(document.querySelector('.slide.msg')), endText: txt(document.querySelector('.slide.end')),
      endLink: toList ? { text: toList.textContent.trim(), href: toList.getAttribute('href') || '' } : null,
      retryShown: shown(retry),
      active, frames: frames.length, frameSlide: f ? +(f.closest('.slide')?.dataset.k ?? -99) : null,
      box: b && { w: b.width, h: b.height, x: b.left, y: b.top },
      overlay, live: live.length, pcount: fy.players.length,
      player: p && { state: p.state, id: p.curId(), ready: !!p.readyFired, n: p.n },
      violations: fy.violations.slice(), wake: { ...window.__wl, sentinels: undefined },
      status: act ? [txt(act.querySelector('.st')), txt(act.querySelector('.sub'))].filter(Boolean).join(' · ') : '',
      title: act ? txt(act.querySelector('.ttl')) : '',
      chan: ch ? { text: txt(ch.querySelector('.nm')), hidden: !shown(ch), label: ch.getAttribute('aria-label') || '' } : null,
      thumbs, navs: window.__navs.map((u, k) => ({ u, hash: window.__navHash[k] })), life: window.__life,
      starts: fy.starts.map(x => x.id), errs: fy.errors || 0,
      log: (fy.log || []).slice(-14),
    };
  });
}
const seenIds = s => s.navs.filter(n => /^kakaowatch:\/\/tube\/seen\?v=/.test(n.u)).map(n => n.u.split('v=')[1]);
const userNavs = s => s.navs.filter(n => !/tube\/(seen|recs)\?/.test(n.u)).map(n => n.u);

// 둥근 화면: 보이는 글자·단추(.fit)가 원 밖으로 3px 넘게 나가는지 — 줄마다(글은 줄 상자, 단추는 둥근 모서리만큼 안쪽 꼭짓점)
async function fitCheck(page) {
  return page.evaluate(async () => {
    await new Promise(r => requestAnimationFrame(() => setTimeout(r, 0)));
    // 장이 화면에 맞춰졌을 때만 잰다 — 넘어가는 동안은 두 장이 걸쳐 보이는 게 당연 (멈추면 늘 맞춰지는지는 불변식이 따로 본다)
    const first = document.querySelector('.slide');
    const H = first ? first.getBoundingClientRect().height : 0;
    if (H && Math.abs(scrollY - Math.round(scrollY / H) * H) > 1) return [];
    const R = Math.min(innerWidth, innerHeight) / 2, cx = innerWidth / 2, cy = innerHeight / 2;
    const bad = [];
    for (const el of document.querySelectorAll('.fit')) {
      if (!el.offsetParent || el.closest('[hidden]')) continue;
      const cs = getComputedStyle(el);
      if (cs.visibility === 'hidden' || parseFloat(cs.opacity) < 0.05) continue;
      const b = el.getBoundingClientRect();
      if (!b.width || !b.height || b.bottom <= 0 || b.top >= innerHeight) continue;
      const boxy = el.tagName === 'BUTTON' || el.tagName === 'A' || cs.overflow !== 'visible' || cs.overflowX !== 'visible';
      let rects = [b], inset = 0;
      if (boxy) {
        const k = b.width / (el.offsetWidth || b.width);
        inset = Math.min(parseFloat(cs.borderTopLeftRadius) || 0, el.offsetWidth / 2, el.offsetHeight / 2) * k * (1 - Math.SQRT1_2);
      } else {
        const rg = document.createRange(); rg.selectNodeContents(el);
        const rr = [...rg.getClientRects()].filter(q => q.width > 0.5 && q.height > 0.5);
        if (rr.length) rects = rr;
      }
      for (const r of rects) {
        for (const [x, y] of [[r.left + inset, r.top + inset], [r.right - inset, r.top + inset], [r.left + inset, r.bottom - inset], [r.right - inset, r.bottom - inset]]) {
          const over = Math.hypot(x - cx, y - cy) - R;
          if (over > 3) { bad.push(`${el.className}(${el.textContent.trim().slice(0, 14)}) +${over.toFixed(1)}px @${Math.round(x)},${Math.round(y)}`); break; }
        }
      }
    }
    return bad;
  });
}

// ---------- 불변식 ----------
const NAV_FORMS = [
  [/^kakaowatch:\/\/tube\/seen\?v=[A-Za-z0-9_-]{11}$/, 'seen'],
  [/^kakaowatch:\/\/tube\/channel\?v=[A-Za-z0-9_-]{11}$/, 'app'],
  [/^intent:\/\/tube\/channel\?v=[A-Za-z0-9_-]{11}#Intent;scheme=kakaowatch;package=com\.mywatch\.kakaomap;end$/, 'web'],
  [/^kakaowatch:\/\/tube$/, 'app'],
  [/^intent:\/\/tube#Intent;scheme=kakaowatch;package=com\.mywatch\.kakaomap;end$/, 'web'],
];
class Checker {
  constructor(name, sc) {
    this.name = name; this.fails = []; this.expectPlayer = !(sc.cfg?.apiFail || sc.cfg?.apiNeverReady);
    this.hiddenSince = null; this.idleSince = null; this.loadSince = null; this.lastState = undefined; this.stateSince = Date.now();
    this.promise = null; this.promiseSince = 0; this.lastY = null; this.ySince = Date.now(); this.shownSince = Date.now();
    this.life = null; this.navSeen = 0; this.seen = new Set(); this.holdFinger = false; this.pausedAt = null;
  }
  fail(step, msg, s) { this.fails.push({ step, msg, log: s?.log }); }
  check(step, s, errors) {
    const now = Date.now();
    if (errors.length) this.fail(step, '페이지 오류: ' + errors.join(' | '), s), errors.length = 0;
    if (s.live > 1) this.fail(step, `플레이어가 ${s.live}개`, s);
    if (s.frames > 1) this.fail(step, `iframe 이 ${s.frames}개`, s);
    if (s.box && (Math.abs(s.box.w - 200) > 0.6 || Math.abs(s.box.h - 200) > 0.6)) this.fail(step, `플레이어 크기 ${s.box.w}×${s.box.h}`, s);
    if (s.overlay) this.fail(step, '플레이어 위에 겹침: ' + s.overlay, s);
    if (s.sw > s.vw + 1) this.fail(step, `가로 넘침 ${s.sw} > ${s.vw}`, s);
    if (s.life !== this.vLife) { this.vLife = s.life; this.vSeen = 0; }
    for (let k = this.vSeen; k < s.violations.length; k++) this.fail(step, '유튜브 규칙 위반: ' + JSON.stringify(s.violations[k]), s);
    this.vSeen = s.violations.length;
    // 플레이어는 지금 장에만 (끝 장·안내 장엔 없음)
    if (s.frames && s.frameSlide !== s.active) this.fail(step, `플레이어가 지금 장(${s.active}) 아닌 ${s.frameSlide} 장에`, s);
    if (s.end >= 0 && s.active === s.end && (s.frames || s.live)) this.fail(step, '끝 장인데 플레이어가 있음', s);
    if (s.end >= 0 && (s.active < 0 || s.active > s.end)) this.fail(step, `지금 장 번호 이상 ${s.active}/${s.end}`, s);
    const st = s.player ? s.player.state : null;
    if (st !== this.lastState) { this.lastState = st; this.stateSince = now; }
    if (s.vis === 'hidden') { this.hiddenSince ??= now; this.shownSince = null; } else { this.hiddenSince = null; this.shownSince ??= now; }
    if (this.hiddenSince && now - this.hiddenSince > 700 && st === 1) this.fail(step, '숨겨졌는데 재생 중', s);
    // 화면 켜 두기: 재생 중에만 (멈춤·끝·대기·숨김이면 1.2초 안에 꺼져야, 불러오기가 17초 넘게 이어져도 꺼져야)
    if (s.wake.active > 1) this.fail(step, `화면 켜 두기 ${s.wake.active}개`, s);
    const idle = st == null || [0, 2, 5].includes(st) || s.vis === 'hidden';
    if (idle) this.idleSince ??= now; else this.idleSince = null;
    if ((st === -1 || st === 3) && s.wake.active > 0) this.loadSince ??= now; else this.loadSince = null;
    if (this.idleSince && now - this.idleSince > 1200 && s.wake.active > 0) this.fail(step, `재생 안 하는데(상태 ${st}) 화면 켜 두기가 남음`, s);
    if (this.loadSince && now - this.loadSince > 17000) this.fail(step, '오래 못 받는데 화면 켜 두기가 남음', s);
    // '넘어가요' 약속은 지켜져야 (2.5초 + 넘기기)
    const promise = s.vis === 'visible' && /넘어가요/.test(s.status) ? s.active + s.status : null;
    if (promise !== this.promise) { this.promise = promise; this.promiseSince = now; }
    else if (promise && now - this.promiseSince > 4500) { this.fail(step, '지켜지지 않는 안내: ' + s.status, s); this.promise = null; }
    // 채널로 떠난 뒤엔 사용자가 다른 걸 하기 전까지 다시 재생되면 안 된다
    if (this.pausedAt && now - this.pausedAt > 1200 && st === 1) { this.fail(step, '채널로 가며 멈췄는데 다시 재생됨', s); this.pausedAt = null; }
    for (const t of [s.status, s.title, s.chan?.text || '', s.msg, s.endText]) if (/undefined|null|NaN|\[object/.test(t)) this.fail(step, '이상한 글자: ' + t, s);
    // 재생을 시작하는 미리보기는 120×70 이상, 변형 없이
    for (const t of s.thumbs) if (t.w < 119.5 || t.h < 69.5 || (t.vis && t.t)) { this.fail(step, `미리보기 ${t.w}×${t.h} 변형 ${t.t} (장 ${t.k})`, s); break; }
    // 멈춘 자리는 늘 장에 맞는다 (손가락을 대고 있는 동안·숨김·막 켜진 때 빼고)
    if (s.scrollY !== this.lastY) { this.lastY = s.scrollY; this.ySince = now; }
    const still = now - this.ySince > 900 && !this.holdFinger && s.vis === 'visible' && this.shownSince && now - this.shownSince > 900;
    if (still && s.H > 0 && s.end >= 0 && Math.abs(s.scrollY - s.active * s.H) > 1.5) this.fail(step, `멈춘 자리 ${s.scrollY} 가 지금 장(${s.active}×${s.H})에 안 맞음`, s);
    // 장에 멈춰 1.5초가 지나면 지금 장(Shorts)에 플레이어가 하나
    if (still && now - this.ySince > 1500 && this.expectPlayer && s.end > 0 && s.active < s.end && !(s.live === 1 && s.frameSlide === s.active)) this.fail(step, `지금 장(${s.active})에 플레이어가 없음 (${s.live}개, ${s.frameSlide} 장)`, s);
    // 링크: 모양·앱 안/인터넷 앱 꼴, 본 Shorts 알림은 앱 안에서만·재생된 것만·한 번씩
    if (s.life !== this.life) { this.life = s.life; this.navSeen = 0; this.seen = new Set(); }
    for (let k = this.navSeen; k < s.navs.length; k++) {
      const { u, hash } = s.navs[k];
      const app = new URLSearchParams(String(hash || '').replace(/^#/, '')).get('app') === '1';
      const kind = (NAV_FORMS.find(([re]) => re.test(u)) || [])[1];
      if (!kind) this.fail(step, '이상한 링크: ' + u, s);
      else if (kind === 'seen') {
        const id = u.split('v=')[1];
        if (!app) this.fail(step, '인터넷 앱인데 본 Shorts 알림: ' + u, s);
        else if (this.seen.has(id)) this.fail(step, '같은 Shorts 를 두 번 알림: ' + id, s);
        else if (!s.starts.includes(id)) this.fail(step, '재생되지 않은 Shorts 를 알림: ' + id, s);
        this.seen.add(id);
      } else if ((kind === 'app') !== app) this.fail(step, (app ? '앱 안인데 ' : '인터넷 앱인데 ') + '링크 꼴이 다름: ' + u, s);
    }
    this.navSeen = s.navs.length;
  }
}

// ---------- 동작 ----------
const A = {
  async wait(page, ms) { await page.waitForTimeout(ms); },
  // 베젤 한 칸 = 화면 높이만큼 한 번에 (앱이 그렇게 굴린다)
  async bezel(page, n) { for (let k = 0; k < (n || 1); k++) await page.evaluate(() => window.scrollBy(0, innerHeight)); },
  async bezelUp(page) { await page.evaluate(() => window.scrollBy(0, -innerHeight)); },
  async bezelBurst(page, n) { for (let k = 0; k < n; k++) { await page.evaluate(() => window.scrollBy(0, innerHeight)); await page.waitForTimeout(40); } },
  async by(page, px) { await page.evaluate(d => window.scrollBy(0, d), px); },
  async byH(page, f) { await page.evaluate(x => window.scrollBy(0, x * innerHeight), f); },
  // 손가락으로 튕김 (페이지가 손가락을 봄): touchstart → 굴림 → touchend
  async fling(page, f) {
    await page.evaluate(x => {
      const t = () => { try { return new TouchEvent('touchstart', { bubbles: true, cancelable: true, touches: [] }); } catch (e) { return new Event('touchstart', { bubbles: true }); } };
      document.body.dispatchEvent(t());
      window.scrollBy(0, x * innerHeight);
      let e; try { e = new TouchEvent('touchend', { bubbles: true, cancelable: true, touches: [] }); } catch (x2) { e = new Event('touchend', { bubbles: true }); }
      document.body.dispatchEvent(e);
    }, f);
  },
  // 손가락을 댄 채 굴림 (떼지 않음) — release 로 뗀다
  async holdTouch(page, f) {
    await page.evaluate(x => {
      let e; try { e = new TouchEvent('touchstart', { bubbles: true, cancelable: true, touches: [] }); } catch (x2) { e = new Event('touchstart', { bubbles: true }); }
      document.body.dispatchEvent(e);
      window.scrollBy(0, x * innerHeight);
    }, f);
  },
  async release(page) {
    await page.evaluate(() => { let e; try { e = new TouchEvent('touchend', { bubbles: true, cancelable: true, touches: [] }); } catch (x2) { e = new Event('touchend', { bubbles: true }); } document.body.dispatchEvent(e); });
  },
  // 진짜 손가락 끌기 (브라우저가 굴리고 튕김까지): where = 'band'(띠 — 페이지가 손가락을 봄) | 'player'(플레이어 위 — 못 봄),
  // dy = 손가락 이동(CSS px, 음수 = 위로 밀기 = 다음 장), ms = 움직임 사이 (2ms 면 세게 튕김: 2장 넘게 굴러감)
  async drag(page, [where, dy, steps, ms]) {
    const m = await page.evaluate(() => ({ iw: innerWidth }));
    const k = (page.viewportSize().width) / m.iw;
    const up = dy < 0;
    const x = (where === 'band' ? 60 : 140) * k;
    const y0 = (where === 'band' ? (up ? 262 : 18) : (up ? 230 : 50)) * k;
    const cdp = await page.context().newCDPSession(page);
    const n = steps || 4;
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y: y0 }] });
    for (let i = 1; i <= n; i++) { await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: y0 + dy * k * i / n }] }); await page.waitForTimeout(ms ?? 16); }
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await cdp.detach().catch(() => {});
  },
  async key(page, name) { await page.keyboard.press(name); },
  async tapPlayerButton(page) {
    const f = await page.$('iframe'); if (!f) return 'no-player';
    const fr = await f.contentFrame(); if (!fr) return 'no-frame';
    const b = await fr.$('#b'); if (!b) return 'no-button';
    await b.tap().catch(() => {}); return 'ok';
  },
  async tap(page, sel) {
    const el = await page.$(sel); if (!el) return 'missing';
    const dis = await el.evaluate(e => e.disabled || !e.offsetParent); if (dis) return 'disabled';
    await el.tap({ timeout: 1500 }).catch(() => {}); return 'ok';
  },
  // 지금 장 안의 것을 누름
  async tapIn(page, sel) {
    const k = await page.evaluate(() => document.body.dataset.active);
    return A.tap(page, `.slide[data-k="${k}"] ${sel}`);
  },
  // 지금 장에서 off 만큼 떨어진 장의 미리보기를 누름 (보일 때)
  // (Playwright 의 el.tap 은 먼저 굴려 보이게 하는데, 그 굴림이 장을 넘기므로 보이는 부분 가운데를 손가락으로 직접 누른다)
  async tapPh(page, off) {
    const p = await page.evaluate(o => {
      const el = document.querySelector(`.slide[data-k="${+document.body.dataset.active + o}"] .ph`);
      if (!el) return null;
      const r = el.getBoundingClientRect(), top = Math.max(r.top, 0), bottom = Math.min(r.bottom, innerHeight);
      return bottom - top < 10 ? null : { x: (r.left + r.right) / 2, y: (top + bottom) / 2, iw: innerWidth };
    }, off);
    if (!p) return 'hidden';
    const k = page.viewportSize().width / p.iw;
    await page.touchscreen.tap(p.x * k, p.y * k);
    return 'ok';
  },
  async hide(page) { await page.evaluate(() => window.__setVisible(false)); },
  async show(page) { await page.evaluate(() => window.__setVisible(true)); },
  async offline(page, ctx) { await page.evaluate(() => { window.__ytcfg.offline = true; }); await ctx.setOffline(true); },
  async online(page, ctx) { await page.evaluate(() => { window.__ytcfg.offline = false; }); await ctx.setOffline(false); },
  async hash(page, h) { await page.evaluate(h2 => { location.hash = h2; }, stamp(h)); },
  async reload(page) { await page.reload(); },
  async back(page) { await page.evaluate(() => history.back()); },
  async resize(page, [w, h]) { await page.setViewportSize({ width: w, height: h }); },
  async freeze(page) { await page.evaluate(() => window.__fakeYT && window.__fakeYT.freeze()); },
  async thaw(page) { await page.evaluate(() => window.__fakeYT && window.__fakeYT.thaw()); },
  async ff(page, ms) {
    const now = await page.evaluate(() => Date.now());
    await page.clock.pauseAt(now + 1000);
    await page.waitForTimeout(30);
    await page.clock.fastForward(ms);
    await page.clock.resume();
  },
  // 손목을 내렸다 올림 (얼었다 깨어남)
  async lid(page, [ms, showFirst]) {
    await A.hide(page); await A.freeze(page); await A.ff(page, ms);
    if (showFirst) { await A.show(page); await A.thaw(page); } else { await A.thaw(page); await page.waitForTimeout(50); await A.show(page); }
  },
};
// 사용자가 무언가를 함 (채널로 떠난 뒤 '저절로 다시 재생' 검사를 끝낸다)
const USER_ACTS = ['bezel', 'bezelUp', 'bezelBurst', 'by', 'byH', 'fling', 'holdTouch', 'drag', 'key', 'tapPlayerButton', 'tap', 'tapPh', 'hash', 'reload', 'back'];

// ---------- 정해 둔 시나리오 ----------
// vp: true 는 기본 실행에서도 화면 크기 4가지로 (나머지는 --allvp=1 일 때)
const scripted = [
  { name: '열기_바로재생', vp: true, hash: `#ids=${L(4)}&n=NOW&app=1`, steps: [
    ['wait', 1500], ['expect', s => s.active === 0 && s.player?.id === S(1) && s.player.state === 1 && s.frameSlide === 0, '방금 고른 Shorts 는 바로 재생'],
    ['expect', s => s.title === 'Shorts ' + S(1) && s.chan && !s.chan.hidden && s.chan.text === '채널 sh0' && s.chan.label === '채널 sh0 채널로 가기', '위 띠 제목 · 아래 띠 채널 알약'],
    ['expect', s => seenIds(s).join() === S(1), '앱 안: 처음 재생된 Shorts 를 앱에 알림'],
    ['expect', s => s.wake.active === 1, '재생 중엔 화면 켜 둠'] ] },
  { name: '열기_시작자리', hash: `#ids=${L(5)}&i=2&n=NOW`, steps: [
    ['wait', 1500], ['expect', s => s.active === 2 && s.player?.id === S(3) && s.player.state === 1 && Math.abs(s.scrollY - 2 * s.H) <= 1, 'i= 자리부터'] ] },
  { name: '오래된주소_안틂', hash: `#ids=${L(3)}&n=OLD`, steps: [
    ['wait', 3300], ['expect', s => s.player && s.player.state !== 1 && /▶/.test(s.status), '오래된 주소는 저절로 안 틂 + 안내'],
    ['tapPlayerButton'], ['wait', 1200], ['expect', s => s.player?.state === 1 && s.status === '', '플레이어 ▶ 로 재생'] ] },
  { name: '자동재생막힘_안내_탭', vp: true, hash: `#ids=${L(3)}&n=NOW`, cfg: { autoplay: false }, steps: [
    ['wait', 1500], ['expect', s => s.player && s.player.state !== 1 && s.status === '가운데 ▶ 를 눌러 시작하세요', '막히면 안내'],
    ['tapPlayerButton'], ['wait', 1200], ['expect', s => s.player?.state === 1 && s.status === '', '플레이어 ▶ 로 재생'] ] },
  { name: '베젤_한칸씩', vp: true, hash: `#ids=${L(6)}&n=NOW&app=1`, steps: [
    ['wait', 1300], ['expect', s => s.active === 0 && s.player?.state === 1, '첫 Shorts 재생'],
    ['bezel'], ['wait', 1300], ['expect', s => s.active === 1 && s.player?.id === S(2) && s.player.state === 1 && s.live === 1, '베젤 한 칸 = 다음 장'],
    ['bezel'], ['wait', 1300], ['expect', s => s.active === 2 && s.player?.id === S(3) && s.player.state === 1, '또 한 칸'],
    ['bezel'], ['wait', 1300], ['expect', s => s.active === 3 && s.player?.id === S(4) && s.player.state === 1 && s.pcount === 4, '세 칸 → 세 장 (플레이어는 늘 하나)'],
    ['expect', s => seenIds(s).join() === [S(1), S(2), S(3), S(4)].join(), '본 Shorts 마다 한 번 알림'],
    ['bezelUp'], ['wait', 1300], ['expect', s => s.active === 2 && s.player?.id === S(3) && s.player.state === 1 && seenIds(s).length === 4, '반대로 한 칸 = 앞 장 (다시 알리지 않음)'] ] },
  { name: '베젤_빠르게세칸', hash: `#ids=${L(6)}&n=NOW`, steps: [
    ['wait', 1300], ['bezelBurst', 3], ['wait', 1500], ['expect', s => s.active === 3 && s.player?.id === S(4) && s.player.state === 1, '빠르게 세 칸도 세 장'] ] },
  { name: '살짝_제자리', hash: `#ids=${L(4)}&n=NOW`, steps: [
    ['wait', 1300], ['by', 5], ['wait', 900], ['expect', s => s.active === 0 && s.pcount === 1 && s.player?.state === 1 && Math.abs(s.scrollY) <= 1, '5px 은 제자리 (플레이어 그대로)'],
    ['by', 7], ['wait', 900], ['expect', s => s.active === 0 && s.pcount === 1 && Math.abs(s.scrollY) <= 1, '8px 까지는 제자리'] ] },
  { name: '손안대고_여러장', hash: `#ids=${L(8)}&n=NOW`, steps: [
    ['wait', 1300], ['byH', 2.5], ['wait', 1500], ['expect', s => s.active === 2 && s.player?.id === S(3) && s.player.state === 1, '2.5장 → 2장 (딱 반이면 덜 가는 쪽)'],
    ['byH', 1.6], ['wait', 1500], ['expect', s => s.active === 4 && s.player?.state === 1, '1.6장 → 2장'],
    ['byH', 0.3], ['wait', 1500], ['expect', s => s.active === 5, '0.3장 → 한 장'],
    ['byH', -0.02], ['wait', 1200], ['expect', s => s.active === 5 && Math.abs(s.scrollY - 5 * s.H) <= 1, '8px 이하는 제자리'] ] },
  { name: '손가락_세게튕겨도_한장', vp: true, hash: `#ids=${L(6)}&n=NOW`, steps: [
    ['wait', 1300], ['fling', 2.5], ['wait', 1500], ['expect', s => s.active === 1 && s.player?.id === S(2) && s.player.state === 1, '손가락으로 세게 넘겨도 한 장'],
    ['fling', 0.02], ['wait', 1200], ['expect', s => s.active === 1 && s.pcount === 2, '살짝 움직인 손가락은 제자리'],
    ['fling', -0.7], ['wait', 1500], ['expect', s => s.active === 0 && s.player?.id === S(1) && s.player.state === 1, '위로 넘기면 앞 장'] ] },
  { name: '진짜손가락_띠위', hash: `#ids=${L(6)}&n=NOW`, steps: [
    ['wait', 1300], ['drag', ['band', -260, 5, 2]], ['wait', 2600], ['expect', s => s.active === 1 && s.player?.state === 1, '띠에서 세게 튕겨도 (2장 넘게 굴러가도) 한 장'],
    ['drag', ['band', 200, 5, 2]], ['wait', 2600], ['expect', s => s.active === 0 && s.player?.state === 1, '반대로 튕기면 앞 장'] ] },
  { name: '진짜손가락_플레이어위', vp: true, hash: `#ids=${L(6)}&n=NOW`, steps: [
    ['wait', 1300], ['drag', ['player', -260, 5, 2]], ['wait', 2600], ['expect', s => s.active === 1 && s.player?.state === 1, '플레이어 위에서 세게 튕겨도 (페이지가 손가락을 못 봐도) 한 장'],
    ['drag', ['player', -60, 4, 16]], ['wait', 2200], ['expect', s => s.active === 2 && s.player?.state === 1, '살살 밀어도 한 장'],
    ['drag', ['player', 260, 5, 2]], ['wait', 2600], ['expect', s => s.active === 1 && s.player?.state === 1, '반대로 튕기면 앞 장'] ] },
  // 화면이 꺼진 동안 넘어감(드묾): 그동안은 안 틀고, 켜지면 (방금 넘긴 것이니) 재생
  { name: '숨긴채넘김_켜지면재생', hash: `#ids=${L(4)}&n=NOW`, steps: [
    ['wait', 1300], ['hide'], ['wait', 600], ['bezel'], ['wait', 1500], ['expect', s => s.active === 1 && s.live === 1 && s.player?.ready && s.player.state !== 1, '꺼진 동안 넘긴 장은 준비돼도 안 틂'],
    ['show'], ['wait', 1500], ['expect', s => s.active === 1 && s.player?.state === 1, '켜지면 재생'] ] },
  // 플레이어가 준비됐을 때 손가락으로 반쯤 걸쳐 들고 있으면(상자가 덜 보임) 시작하지 않는다 — 놓아서 장이 맞춰지면 그 장에서 시작
  { name: '넘기는중엔_시작안함', hash: `#ids=${L(4)}&n=NOW`, cfg: { readyMs: 1000 }, steps: [
    ['wait', 1800], ['expect', s => s.active === 0 && s.player?.state === 1, '첫 Shorts 재생'], ['bezel'], ['wait', 300],
    ['holdTouch', 0.6], ['wait', 1600], ['expect', s => s.active === 1 && s.live === 1 && s.frameSlide === 1 && s.player?.ready && s.player.state !== 1, '덜 보이는 동안엔 준비돼도 안 틂'],
    ['release'], ['wait', 2600], ['expect', s => s.active === 2 && s.player?.id === S(3) && s.player.state === 1, '놓으면 한 장 넘어가 그 장에서 재생'] ] },
  { name: '키', hash: `#ids=${L(5)}&n=NOW`, steps: [
    ['wait', 1300], ['key', 'ArrowDown'], ['wait', 1200], ['expect', s => s.active === 1 && s.player?.state === 1, '↓ = 다음 장'],
    ['key', 'PageDown'], ['wait', 1200], ['expect', s => s.active === 2, 'PageDown'],
    ['key', 'ArrowUp'], ['wait', 1200], ['expect', s => s.active === 1, '↑'],
    ['key', 'PageUp'], ['wait', 1200], ['expect', s => s.active === 0 && s.player?.state === 1, 'PageUp'],
    ['key', 'PageUp'], ['wait', 900], ['expect', s => s.active === 0 && Math.abs(s.scrollY) <= 1, '맨 앞에서 더 위로는 그대로'] ] },
  { name: '숨김_멈춤_돌아와도안틂', vp: true, hash: `#ids=${L(4)}&n=NOW`, steps: [
    ['wait', 1300], ['hide'], ['wait', 1200], ['expect', s => s.player?.state !== 1 && s.wake.active === 0, '숨기면 멈춤·화면 켜 두기 끝'],
    ['show'], ['wait', 1500], ['expect', s => s.player?.state !== 1 && /멈췄어요 · ▶ 로 이어서/.test(s.status), '돌아와도 저절로 안 틂 + 안내'],
    ['tapPlayerButton'], ['wait', 1200], ['expect', s => s.player?.state === 1 && s.status === '', '▶ 로 이어서'] ] },
  // 넘긴 Shorts 를 불러오는 중 화면이 잠깐 꺼짐: 방금 넘긴 것이면 켜질 때 시작 (고른 것과 같은 규칙)
  { name: '불러오는중숨김_켜지면시작', hash: `#ids=${L(4)}&n=NOW`, cfg: { pauseLate: true, bufferMs: 2000 }, steps: [
    ['wait', 3000], ['expect', s => s.player?.state === 1, '첫 Shorts 재생'], ['bezel'], ['wait', 600], ['expect', s => s.active === 1 && [3, -1].includes(s.player?.state), '다음 장 불러오는 중'],
    ['hide'], ['wait', 300], ['show'], ['wait', 3000], ['expect', s => s.active === 1 && s.player?.state === 1, '방금 넘긴 것은 켜지면 재생'] ] },
  // …그런데 오래 꺼져 있었으면 저절로 안 틂: 멈춤을 놓친 플레이어가 켜진 뒤 늦게 시작해도 다시 멈춤
  { name: '불러오는중숨김_놓친멈춤_다시멈춤', hash: `#ids=${L(4)}&n=NOW`, cfg: { pauseLate: true, bufferMs: 4000, freshMs: 2500 }, steps: [
    ['wait', 5000], ['expect', s => s.player?.state === 1, '첫 Shorts 재생'], ['bezel'], ['wait', 600], ['expect', s => s.active === 1 && [3, -1].includes(s.player?.state), '다음 장 불러오는 중'],
    ['hide'], ['wait', 2500], ['show'], ['wait', 2200], ['expect', s => s.player?.state !== 1 && /▶/.test(s.status), '늦게 시작돼도 다시 멈춤 + ▶ 안내'],
    ['tapPlayerButton'], ['wait', 1500], ['expect', s => s.player?.state === 1, '플레이어 ▶ 는 바로 됨'] ] },
  { name: '못트는Shorts_알리고다음장', vp: true, hash: `#ids=${L(4)}&n=NOW`, cfg: { errors: { [S(2)]: 150 } }, steps: [
    ['wait', 1300], ['bezel'], ['wait', 900], ['expect', s => s.active === 1 && /다음으로 넘어가요/.test(s.status) && /막혔어요/.test(s.status), '못 트는 Shorts 알림'],
    ['wait', 3800], ['expect', s => s.active === 2 && s.player?.id === S(3) && s.player.state === 1, '2.5초 뒤 다음 장 재생'] ] },
  { name: '못트는Shorts_숨김이면안넘어감', hash: `#ids=${L(4)}&n=NOW`, cfg: { errors: { [S(2)]: 100 } }, steps: [
    ['wait', 1300], ['bezel'], ['wait', 900], ['expect', s => /다음으로 넘어가요/.test(s.status), '못 트는 Shorts 알림'],
    ['hide'], ['wait', 3500], ['expect', s => s.active === 1, '숨김 중엔 안 넘어감'], ['show'], ['wait', 1200],
    ['expect', s => s.active === 1 && !/넘어가요/.test(s.status) && /비공개/.test(s.status), '돌아와도 그 자리 (넘어간다는 말 없이)'],
    ['bezel'], ['wait', 1300], ['expect', s => s.active === 2 && s.player?.state === 1, '넘기면 다음 장'] ] },
  { name: '마지막Shorts못틂_끝장으로', hash: `#ids=${L(2)}&n=NOW`, cfg: { errors: { [S(2)]: 101 } }, steps: [
    ['wait', 1300], ['bezel'], ['wait', 4200], ['expect', s => s.active === 2 && s.end === 2 && s.frames === 0 && s.live === 0, '마지막이 막히면 끝 장 (그 너머로는 안 감)'] ] },
  { name: '반복', vp: true, hash: `#ids=${L(3)}&n=NOW&app=1`, cfg: { durations: { [S(1)]: 1 } }, steps: [
    ['wait', 4500], ['expect', s => s.active === 0 && s.starts.filter(x => x === S(1)).length >= 3 && [1, 3, -1].includes(s.player?.state), '끝나면 처음부터 다시 (Shorts 처럼)'],
    ['expect', s => seenIds(s).join() === S(1), '본 Shorts 알림은 한 번만'] ] },
  { name: '오래안만지면_반복멈춤', hash: `#ids=${L(3)}&n=NOW`, cfg: { durations: { [S(1)]: 1, [S(2)]: 1 }, loopIdleMs: 3000 }, steps: [
    ['wait', 6500], ['expect', s => s.active === 0 && s.player?.state === 0 && /멈췄어요 · ▶ 로 다시/.test(s.status) && s.wake.active === 0, '오래 만지지 않으면 반복을 멈춤 (배터리)'],
    ['bezel'], ['wait', 1300], ['expect', s => s.active === 1 && s.player?.state === 1, '넘기면 다시 재생'],
    ['wait', 1600], ['expect', s => s.starts.filter(x => x === S(2)).length >= 2, '만진 뒤엔 다시 반복'] ] },
  { name: '끝장_처음부터', vp: true, hash: `#ids=${L(2)}&n=NOW&app=1`, steps: [
    ['wait', 1300], ['bezel'], ['wait', 1300], ['bezel'], ['wait', 1300],
    ['expect', s => s.active === 2 && s.end === 2 && s.frames === 0 && s.live === 0 && s.wake.active === 0 && /Shorts를 다 봤어요/.test(s.endText), '끝 장: 플레이어 없음'],
    ['bezel'], ['wait', 1000], ['expect', s => s.active === 2 && Math.abs(s.scrollY - 2 * s.H) <= 1, '끝 장 너머로는 안 감'],
    ['tap', '#again'], ['wait', 1800], ['expect', s => s.active === 0 && s.player?.id === S(1) && s.player.state === 1, '처음부터 → 첫 장 재생'] ] },
  { name: '끝장_목록으로_앱', hash: `#ids=${L(1)}&n=NOW&app=1`, steps: [
    ['wait', 1300], ['bezel'], ['wait', 1200], ['expect', s => s.endLink?.text === '목록으로' && s.endLink.href === 'kakaowatch://tube', '앱 안: 목록으로'],
    ['tap', '#toList'], ['wait', 400], ['expect', s => userNavs(s).join() === 'kakaowatch://tube', '목록으로 → 앱'] ] },
  { name: '끝장_다른동영상_인터넷앱', hash: `#ids=${L(1)}&n=NOW`, steps: [
    ['wait', 1300], ['bezel'], ['wait', 1200], ['expect', s => s.endLink?.text === '다른 동영상 찾기' && s.endLink.href === INTENT('tube'), '인터넷 앱: 다른 동영상 찾기'],
    ['tap', '#toList'], ['wait', 400], ['expect', s => userNavs(s).join() === INTENT('tube'), 'intent:// 로 앱을 엶'] ] },
  { name: '채널알약_앱', vp: true, hash: `#ids=${L(3)}&n=NOW&app=1`, steps: [
    ['wait', 1500], ['tapIn', '.ch'], ['wait', 600], ['expect', s => userNavs(s).join() === 'kakaowatch://tube/channel?v=' + S(1) && s.player?.state !== 1, '채널 알약 → 앱의 채널 화면 (멈추고)'] ] },
  { name: '채널알약_인터넷앱', hash: `#ids=${L(3)}&i=1&n=NOW`, steps: [
    ['wait', 1500], ['tapIn', '.ch'], ['wait', 600], ['expect', s => userNavs(s).join() === INTENT('tube/channel?v=' + S(2)) && s.player?.state !== 1, 'intent:// 로 채널 화면'] ] },
  { name: '본Shorts_인터넷앱엔없음', hash: `#ids=${L(3)}&n=NOW`, cfg: { durations: { [S(1)]: 1 } }, steps: [
    ['wait', 3000], ['bezel'], ['wait', 1300], ['expect', s => s.player?.state === 1 && seenIds(s).length === 0 && s.navs.length === 0, '인터넷 앱에선 알리지 않음'] ] },
  { name: 'Shorts없음', vp: true, hash: '#n=NOW', steps: [
    ['wait', 1200], ['expect', s => /볼 Shorts가 없어요/.test(s.msg) && s.frames === 0 && s.live === 0, '안내만'],
    ['tap', '#find'], ['wait', 300], ['expect', s => userNavs(s).join() === INTENT('tube'), '동영상 찾기 → 앱'] ] },
  { name: 'Shorts없음_앱', hash: '#ids=bad,%3Cx%3E&app=1', steps: [
    ['wait', 1200], ['expect', s => /볼 Shorts가 없어요/.test(s.msg), '이상한 번호뿐이면 안내'], ['tap', '#find'], ['wait', 300], ['expect', s => userNavs(s).join() === 'kakaowatch://tube', '앱 안: kakaowatch://tube'] ] },
  { name: '잘못된목록', hash: `#ids=bad,${S(1)},${S(1)},%3Cx%3E,${S(2)},${Array.from({ length: 60 }, (_, k) => S(k + 10)).join(',')}&i=zz&n=NOW`, steps: [
    ['wait', 1500], ['expect', s => s.end === 50 && s.nSlides === 51 && s.active === 0 && s.player?.id === S(1), '이상한·같은 번호는 빼고 50개까지 + 끝 장'],
    ['expect', s => s.thumbs.filter(t => t.src).length <= 3, '미리보기 그림은 지금 장 앞뒤 둘까지만 (메모리)'] ] },
  { name: '시작자리_범위밖', hash: `#ids=${L(3)}&i=99&n=NOW`, steps: [
    ['wait', 1500], ['expect', s => s.active === 2 && s.player?.id === S(3), 'i 가 넘치면 마지막'],
    ['hash', `#ids=${L(3)}&i=-4&n=NOW`], ['wait', 1500], ['expect', s => s.active === 0 && s.player?.id === S(1) && s.player.state === 1, 'i 가 음수면 처음'] ] },
  { name: '유튜브스크립트실패', vp: true, hash: `#ids=${L(3)}&n=NOW`, cfg: { apiFail: true }, steps: [
    ['wait', 1500], ['expect', s => /유튜브에 연결하지 못했어요/.test(s.msg) && s.retryShown, '못 받으면 안내 + 다시 시도'] ] },
  { name: '유튜브스크립트멈춤', hash: `#ids=${L(3)}&n=NOW`, cfg: { apiNeverReady: true, apiWaitMs: 2500 }, steps: [
    ['wait', 3500], ['expect', s => /유튜브에 연결하지 못했어요/.test(s.msg) && s.retryShown, '너무 오래 걸리면 안내'] ] },
  { name: '주소바뀜_새목록', hash: `#ids=${L(3)}&n=NOW&app=1`, steps: [
    ['wait', 1300], ['bezel'], ['wait', 1200], ['hash', `#ids=${S(7)},${S(8)}&n=NOW&app=1`], ['wait', 1500],
    ['expect', s => s.end === 2 && s.active === 0 && s.player?.id === S(7) && s.player.state === 1 && s.live === 1, '새 목록 → 첫 장부터 재생'],
    ['hash', `#ids=${S(5)},${S(6)}&n=OLD&app=1`], ['wait', 3200], ['expect', s => s.player?.id === S(5) && s.player.state !== 1 && /▶/.test(s.status), '예전 주소는 저절로 안 틂'],
    ['back'], ['wait', 3200], ['expect', s => s.player?.id === S(7) && s.player.state !== 1, '뒤로 가도 저절로 안 틂'] ] },
  { name: '미리보기를눌러서', hash: `#ids=${L(4)}&n=NOW`, steps: [
    ['wait', 1300], ['holdTouch', 0.45], ['wait', 500], ['expect', s => s.active === 0 && s.thumbs.some(t => t.k === 1 && t.vis && t.src), '손가락을 댄 채: 다음 장 미리보기가 보임'],
    ['tapPh', 1], ['wait', 1800], ['expect', s => s.active === 1 && s.player?.id === S(2) && s.player.state === 1, '미리보기를 누르면 그 장'] ] },
  { name: '긴제목_둥근화면', vp: true, hash: `#ids=${L(4)}&n=NOW&app=1`, cfg: { longTitles: true, longAuthors: true }, steps: [
    ['wait', 1500], ['expect', s => s.title.length > 10 && s.chan && !s.chan.hidden, '긴 제목·채널'], ['bezel'], ['wait', 1300],
    ['hide'], ['show'], ['wait', 900], ['expect', s => /멈췄어요/.test(s.status), '안내도 둥근 화면 안에'] ] },
  { name: '제목못받음', hash: `#ids=${L(3)}&n=NOW`, cfg: { oembedFail: true, thumbFail: true }, steps: [
    ['wait', 1500], ['expect', s => s.player?.state === 1 && s.chan?.hidden && s.title === '', '제목·채널 없이도 재생 (알약 숨김)'], ['bezel'], ['wait', 1300] ] },
  { name: '화면켜두기거부', hash: `#ids=${L(3)}&n=NOW`, cfg: { wakeLock: 'reject', durations: { [S(1)]: 1 } }, steps: [['wait', 3000], ['bezel'], ['wait', 1500]] },
  { name: '화면켜두기없음', hash: `#ids=${L(3)}&n=NOW`, cfg: { wakeLock: 'none' }, steps: [['wait', 2000], ['bezel'], ['wait', 1500]] },
  { name: '손목내림_얼었다깨어남', hash: `#ids=${L(3)}&n=NOW`, cfg: { clock: true }, steps: [
    ['wait', 1300], ['expect', s => s.player?.state === 1, '재생 중'], ['lid', [30000, true]], ['wait', 2500], ['expect', s => s.player?.state !== 1 && /멈췄어요/.test(s.status), '깨어나도 저절로 안 틂'] ] },
  { name: '고르고바로꺼짐_켜지면재생', hash: `#ids=${L(3)}&n=NOW`, cfg: { readyMs: 900 }, steps: [
    ['hide'], ['wait', 1500], ['expect', s => s.player?.state !== 1, '꺼진 동안엔 안 틂'], ['show'], ['wait', 1500], ['expect', s => s.player?.state === 1, '방금 고른 것이면 켜지면 재생'] ] },
  { name: '고르고꺼짐_오래뒤켜짐_안틂', hash: `#ids=${L(3)}&n=NOW`, cfg: { readyMs: 900, freshMs: 2000 }, steps: [
    ['hide'], ['wait', 2500], ['show'], ['wait', 1500], ['expect', s => s.player?.state !== 1 && /▶/.test(s.status), '고른 지 오래면 켜져도 안 틂 + 안내'] ] },
  { name: '빠르게이것저것', hash: `#ids=${L(6)}&n=NOW&app=1`, steps: [
    ['wait', 1000], ['bezel'], ['fling', 0.6], ['key', 'ArrowDown'], ['bezelBurst', 2], ['byH', -0.4], ['wait', 2500],
    ['expect', s => s.live === 1 && s.frameSlide === s.active && Math.abs(s.scrollY - s.active * s.H) <= 1 && s.player?.state === 1, '연타해도 한 장에 맞춰 하나만 재생'] ] },
  { name: '크기바뀜', hash: `#ids=${L(4)}&i=1&n=NOW`, steps: [
    ['wait', 1300], ['resize', [280, 240]], ['wait', 1200], ['expect', s => s.active === 1 && Math.abs(s.H - s.vh) <= 1 && Math.abs(s.scrollY - s.H) <= 1, '높이가 바뀌면 장 크기·자리도 맞춤'],
    ['resize', [280, 280]], ['wait', 1200], ['expect', s => s.active === 1 && Math.abs(s.scrollY - s.H) <= 1 && s.player?.state === 1, '되돌려도 그대로 재생'] ] },
];
const viewports = [{ tag: '280', vw: 280 }, { tag: '240', vw: 240 }, { tag: '300', vw: 300 }, { tag: '226', vw: 226 }];

async function runScenario(browser, sc, label) {
  const { ctx, page, errors, nav } = await openPage(browser, sc);
  const ck = new Checker(label, sc);
  let i = 0;
  try {
    await page.waitForTimeout(300);
    for (const [act, a1, a2] of sc.steps) {
      i++;
      if (act === 'expect') {
        const s = await snap(page); s.loads = nav.loads;
        if (!a1(s)) ck.fail(i, '기대 실패: ' + a2 + ` (장 ${s.active}/${s.end} y=${s.scrollY} H=${s.H} state=${s.player?.state} id=${s.player?.id} live=${s.live} status='${s.status}' title='${s.title}' navs=${s.navs.map(n => n.u).join(' ')})`, s);
        continue;
      }
      if (act === 'wait') {
        const end = Date.now() + a1;
        while (Date.now() < end) { await page.waitForTimeout(Math.min(250, end - Date.now())); ck.check(i, await snap(page), errors); }
        const bad = await fitCheck(page);
        if (bad.length) ck.fail(i, '원 밖으로: ' + bad.slice(0, 3).join(' ; '));
        continue;
      }
      // 뒤로 가기는 이 화면 안에서 주소(#…)를 바꾼 만큼만 (더 가면 다른 페이지로 나가 버린다)
      if (act === 'back' && !(ck.depth > 0)) continue;
      if (act === 'hash') ck.depth = (ck.depth || 0) + 1;
      if (act === 'back') ck.depth--;
      if (USER_ACTS.includes(act)) ck.pausedAt = null;
      if (act === 'holdTouch') ck.holdFinger = true;
      if (act === 'release' || act === 'tapPh') { ck.holdFinger = false; ck.ySince = Date.now(); }   // 손가락을 뗀 때부터 다시 잰다
      let navsBefore = null;
      if (act === 'tapIn' && a1 === '.ch') navsBefore = await page.evaluate(() => window.__navs.length);
      if (act === 'offline' || act === 'online') await A[act](page, ctx);
      else await A[act](page, a1);
      if (navsBefore != null && (await page.evaluate(() => window.__navs.length).catch(() => navsBefore)) > navsBefore) ck.pausedAt = Date.now();
      await page.waitForTimeout(120);
      ck.check(i, await snap(page), errors);
    }
    await page.waitForTimeout(500);
    ck.check(i + 1, await snap(page), errors);
  } catch (e) {
    ck.fail(i, '시뮬레이터 예외: ' + e.message);
  }
  if (ck.fails.length) await page.screenshot({ path: path.join(SHOTS, 'shorts_' + label.replace(/[^\w가-힣.-]+/g, '_') + '.png') }).catch(() => {});
  await ctx.close();
  return ck.fails;
}

// ---------- 무작위 사용자 ----------
function randomScenario(seed, steps) {
  const r = rng(seed);
  const pick = a => a[Math.floor(r() * a.length)];
  const n = 2 + Math.floor(r() * 6);
  const cfg = {
    autoplay: r() < 0.7, duration: 1 + Math.floor(r() * 5), speed: 1 + Math.floor(r() * 2),
    errors: r() < 0.4 ? { [S(1 + Math.floor(r() * n))]: pick([2, 5, 100, 101, 150]) } : undefined,
    wakeLock: pick(['ok', 'ok', 'ok', 'reject', 'none']), oembedFail: r() < 0.1, thumbFail: r() < 0.1,
    bufferMs: 150 + Math.floor(r() * 600), longTitles: r() < 0.3, longAuthors: r() < 0.2, pauseLate: r() < 0.3,
    syncEvents: r() < 0.2, clock: r() < 0.25, loopIdleMs: r() < 0.3 ? 2500 : undefined,
  };
  const vp = pick(viewports);
  const app = r() < 0.5 ? '&app=1' : '';
  const hashes = [`#ids=${L(n)}${app}`, `#ids=${L(3)}&i=1${app}`, `#ids=${S(7)},${S(8)}${app}`, `#ids=${L(5)}&i=4${app}`];
  const list = [];
  for (let k = 0; k < steps; k++) {
    const x = r();
    if (x < 0.14) list.push(['wait', 200 + Math.floor(r() * 2500)]);
    else if (x < 0.26) list.push(['bezel']);
    else if (x < 0.30) list.push(['bezelUp']);
    else if (x < 0.34) list.push(['bezelBurst', 2 + Math.floor(r() * 2)]);
    else if (x < 0.40) list.push(['byH', Math.round((r() * 4 - 1.5) * 100) / 100]);
    else if (x < 0.46) list.push(['fling', Math.round((r() * 3 - 1) * 100) / 100]);
    else if (x < 0.50) list.push(['drag', [pick(['band', 'player']), Math.round(r() * 460 - 260), 2 + Math.floor(r() * 4), pick([2, 8, 16])]]);
    else if (x < 0.54) list.push(['key', pick(['ArrowDown', 'ArrowUp', 'PageDown', 'PageUp'])]);
    else if (x < 0.60) list.push(['tapPlayerButton']);
    else if (x < 0.63) list.push(['tapIn', '.ch']);
    else if (x < 0.66) list.push(['tap', '#again']);
    else if (x < 0.73) list.push(['hide']);
    else if (x < 0.80) list.push(['show']);
    else if (x < 0.84) { const y = r(); list.push(['hash', pick(hashes) + (y < 0.7 ? '&n=NOW' : y < 0.9 ? '&n=OLD' : '')]); }
    else if (x < 0.86) list.push(['back']);
    else if (x < 0.88) list.push(cfg.clock && r() < 0.6 ? ['lid', [pick([3000, 25000, 7 * 60000]), r() < 0.5]] : ['reload']);
    else list.push(['wait', 100 + Math.floor(r() * 600)]);
  }
  list.push(['show'], ['wait', 1800]);
  return { name: `원숭이#${seed}`, hash: `#ids=${L(n)}&n=NOW${app}`, cfg, steps: list, ...vp };
}

const browser = await chromium.launch();
const t0 = Date.now();
let total = 0, failed = 0;
const report = [];
const only = args.only ? new RegExp(args.only) : null;
if (!args.random || args.scripted) {
  for (const sc of scripted) {
    if (only && !only.test(sc.name)) continue;
    let vps = args.allvp || sc.vp ? viewports : [viewports[0]];
    if (args.vp) vps = viewports.filter(v => v.tag === String(args.vp));
    for (const vp of vps) {
      const label = `${sc.name}@${vp.tag}`;
      let fails = await runScenario(browser, { ...vp, ...sc }, label);
      for (let k = 0; fails.length && k < +(args.retry || 0); k++) {
        console.log('again', label, '—', fails[0].msg.slice(0, 120));
        fails = await runScenario(browser, { ...vp, ...sc }, label);
      }
      total++;
      if (fails.length) { failed++; report.push({ label, fails }); console.log('FAIL', label); fails.slice(0, 4).forEach(f => console.log('   step', f.step, f.msg, f.log ? '\n      ' + f.log.slice(-6).join('\n      ') : '')); }
      else console.log('ok  ', label);
    }
  }
}
const nRand = +(args.random || 0);
const seed0 = +(args.seed || 1);
for (let k = 0; k < nRand; k++) {
  const sc = randomScenario(seed0 + k, +(args.steps || 30));
  const label = `${sc.name}@${sc.tag}`;
  const fails = await runScenario(browser, sc, label);
  total++;
  if (fails.length) { failed++; report.push({ label, cfg: sc.cfg, steps: sc.steps, fails }); console.log('FAIL', label, JSON.stringify(sc.cfg)); fails.slice(0, 3).forEach(f => console.log('   step', f.step, f.msg, f.log ? '\n      ' + f.log.slice(-6).join('\n      ') : '')); }
  else if (k % 10 === 9) console.log(`..${k + 1}/${nRand} 원숭이 통과`);
}
await browser.close();
fs.writeFileSync(args.report || path.join(SHOTS, 'shorts_report.json'), JSON.stringify(report, null, 1));
console.log(`\n${total - failed}/${total} 통과 (${((Date.now() - t0) / 1000).toFixed(0)}초)`);
process.exit(failed ? 1 : 0);
