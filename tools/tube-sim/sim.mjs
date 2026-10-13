// 워치 유튜브 재생 화면 시뮬레이터 — 가짜 유튜브 플레이어(fake_yt.js)로 사용자가 겪을 모든 경우를 돌리고
// 매 단계 뒤 유튜브 규칙·단단함 불변식을 확인한다.
//   node sim.mjs [--only=이름] [--allvp=1] [--random=개수] [--steps=개수] [--seed=시작] [--retry=1] [--page=경로] [--shots=폴더]
import fs from 'node:fs';
import path from 'node:path';

// playwright: 설치된 것(npm i playwright) 또는 이 개발 환경의 것
let pw;
try { pw = await import('playwright'); } catch (e) { pw = await import('/opt/npm-tools/node_modules/playwright/index.mjs'); }
const { chromium } = pw;
const args = Object.fromEntries(process.argv.slice(2).map(a => { const [k, v] = a.replace(/^--/, '').split('='); return [k, v ?? '1']; }));
const DIR = path.dirname(new URL(import.meta.url).pathname);
const PAGE_PATH = args.page || path.resolve(DIR, '../../web/tube/index.html');
const PAGE = fs.readFileSync(PAGE_PATH, 'utf8');
const FAKE = fs.readFileSync(path.join(DIR, 'fake_yt.js'), 'utf8');
const BASE = 'https://syy4787.github.io/kakao-watch-map/tube/';
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64');
const SHOTS = args.shots || path.join(DIR, 'shots');
fs.mkdirSync(SHOTS, { recursive: true });

// 주소 속 n=NOW → 지금(앱에서 방금 고름), n=OLD → 두 시간 전(되살아난 옛 탭·오래된 주소)
function stamp(h) { return String(h).replace(/NOW/g, String(Date.now())).replace(/OLD/g, String(Date.now() - 2 * 3600e3)); }

// ---------- 무작위 (재현 가능) ----------
function rng(seed) { let s = seed >>> 0 || 1; return () => { s ^= s << 13; s >>>= 0; s ^= s >> 17; s ^= s << 5; s >>>= 0; return s / 4294967296; }; }

// ---------- 페이지 띄우기 ----------
async function openPage(browser, sc) {
  const vw = sc.vw || 226, vh = sc.vh || vw;
  const ctx = await browser.newContext({ viewport: { width: vw, height: vh }, deviceScaleFactor: sc.dpr || 2.125, isMobile: true, hasTouch: true, locale: 'ko-KR' });
  const page = await ctx.newPage();
  const errors = [];
  const nav = { loads: 0 };
  page.on('load', () => { nav.loads++; });
  page.on('pageerror', e => errors.push(String(e.message || e)));
  page.on('console', m => { if (m.type() === 'error' && !/Failed to load resource|net::ERR/.test(m.text())) errors.push('console: ' + m.text()); });
  await page.addInitScript(({ cfg }) => {
    window.__ytcfg = cfg;
    if (cfg.minuteMs) window.__kwMinuteMs = cfg.minuteMs;
    if (cfg.freshMs) window.__kwFreshMs = cfg.freshMs;
    if (cfg.pageReadyMs) window.__kwReadyMs = cfg.pageReadyMs;
    window.__navs = [];
    window.__kwNavigate = u => window.__navs.push(u);
    window.__playClicks = 0;
    // ▶/⏸ 가 실제로 눌린 횟수와 눌린 순간의 표시 (페이지의 버튼 처리보다 먼저 기록)
    document.addEventListener('click', e => { const b = e.target && e.target.closest && e.target.closest('#play'); if (b) { window.__playClicks++; window.__playClickLabel = b.getAttribute('aria-label'); } }, true);
    // 화면 켜 두기(가짜): 켠 개수·최대를 센다, 화면이 숨겨지면 브라우저처럼 저절로 풀림
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
    // 숨김 흉내: document.visibilityState 를 바꿀 수 있게
    let vis = 'visible';
    Object.defineProperty(Document.prototype, 'visibilityState', { configurable: true, get: () => vis });
    Object.defineProperty(Document.prototype, 'hidden', { configurable: true, get: () => vis !== 'visible' });
    window.__setVisible = v => {
      vis = v ? 'visible' : 'hidden';
      if (!v) wl.sentinels.forEach(s => s.release());
      document.dispatchEvent(new Event('visibilitychange'));
    };
  }, { cfg: sc.cfg || {} });
  // 화면이 꺼져 브라우저가 멈췄다 깨어나는 것을 흉내 내려면 페이지 시계를 가짜로 (시간은 그대로 흐름)
  if (sc.cfg?.clock) await page.clock.install();
  await page.route(BASE + '**', r => r.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: PAGE }));
  await page.route('https://www.youtube.com/iframe_api*', r => sc.cfg?.apiFail ? r.abort() : r.fulfill({ status: 200, contentType: 'text/javascript', body: FAKE }));
  await page.route('https://www.youtube.com/oembed*', r => {
    if (sc.cfg?.oembedFail) return r.abort();
    const u = new URL(r.request().url());
    const id = (new URL(u.searchParams.get('url')).searchParams.get('v')) || '?';
    return r.fulfill({ status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify({ title: '곡 ' + id + (sc.cfg?.longTitles ? ' — 아주 긴 제목이 붙은 노래 (Official Music Video) [4K Remaster] 가사 포함 버전' : ''), author_name: '채널 ' + id.slice(0, 3) }) });
  });
  await page.route('https://i.ytimg.com/**', r => sc.cfg?.thumbFail ? r.abort() : r.fulfill({ status: 200, contentType: 'image/png', body: PNG }));
  await page.goto(BASE + stamp(sc.hash ?? '#v=dQw4w9WgXcQ&mix=1&t=' + encodeURIComponent('첫 곡 제목') + '&c=' + encodeURIComponent('첫 채널') + '&n=NOW'));
  if (sc.textScale) await page.addStyleTag({ content: textScaleCss(sc.textScale) });
  return { ctx, page, errors, nav };
}

// 글자 크게 (안드로이드 '글자 크기'처럼 글자만 키움)
function textScaleCss(k) {
  const sizes = [['#title', 15], ['#channel', 12], ['#status', 12], ['#time', 11], ['.next h2', 12], ['.next .t', 12], ['.tail a', 13], ['.tail p', 11], ['.tail button', 13], ['.pill', 13]];
  return sizes.map(([s, px]) => `${s}{font-size:${(px * k).toFixed(1)}px !important}`).join('\n');
}

// ---------- 페이지 상태 읽기 ----------
async function snap(page) {
  return page.evaluate(() => {
    const fy = window.__fakeYT || { players: [], violations: [], calls: [], starts: [], soft: [], errors: 0 };
    const p = fy.players.filter(x => !x.destroyed).slice(-1)[0] || null;
    const frames = [...document.querySelectorAll('#stage iframe')];
    const f = frames[0];
    const b = f ? f.getBoundingClientRect() : null;
    let overlay = null;
    if (b) {
      for (const [fx, fy2] of [[.5, .5], [.12, .12], [.88, .12], [.12, .88], [.88, .88], [.5, .06], [.5, .94], [.06, .5], [.94, .5]]) {
        const x = b.left + b.width * fx, y = b.top + b.height * fy2;
        if (x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) continue;
        const el = document.elementFromPoint(x, y);
        if (el && el !== f) { overlay = `${el.tagName}#${el.id}.${el.className} @${fx},${fy2}`; break; }
      }
    }
    const btn = id => { const e = document.getElementById(id); return e ? { disabled: !!e.disabled, label: e.getAttribute('aria-label') || '', pressed: e.getAttribute('aria-pressed'), hidden: !!e.hidden || !e.offsetParent } : null; };
    const thumbs = [...document.querySelectorAll('#list img')].map(i => { const r = i.getBoundingClientRect(); let t = 0; for (let e = i; e; e = e.parentElement) { const tr = getComputedStyle(e).transform; if (tr && tr !== 'none') t++; } return { w: r.width, h: r.height, t, vis: r.bottom > 0 && r.top < innerHeight }; });
    return {
      vis: document.visibilityState,
      scrollY, vw: innerWidth, vh: innerHeight, sw: document.scrollingElement.scrollWidth,
      frames: frames.length, box: b && { w: b.width, h: b.height, x: b.left, y: b.top },
      overlay,
      player: p && { state: p.state, idx: p.getPlaylistIndex(), len: (p.getPlaylist() || []).length, id: p.curId(), vol: p.vol, muted: p.muted, ready: !!p.readyFired },
      violations: fy.violations.slice(), soft: fy.soft.length,
      wake: { ...window.__wl, sentinels: undefined },
      title: document.getElementById('title')?.textContent || '',
      channel: document.getElementById('channel')?.textContent || '',
      status: document.getElementById('status')?.textContent || '',
      time: document.getElementById('time')?.textContent ?? null,
      play: btn('play'), next: btn('next'), prev: btn('prev'), retry: btn('retry'), repeat: btn('repeat'),
      auto: document.getElementById('play')?.getAttribute('data-auto') === '1',
      stage: document.getElementById('stageMsg')?.hidden ? '' : (document.getElementById('stageMsg')?.textContent || '').trim(),
      upnext: !document.getElementById('upnext')?.hidden, nUp: document.querySelectorAll('#list li').length,
      thumbs, navs: window.__navs.slice(), pcount: fy.players.length, errs: fy.errors || 0,
      log: (fy.log || []).slice(-12),
    };
  });
}

// 둥근 화면: 보이는(흐리지 않은) 요소가 원 밖으로 4px 넘게 나가는지
async function fitCheck(page) {
  return page.evaluate(async () => {
    // 맞추기는 화면 그리기(rAF)에서 한다 — 그 화면의 rAF 가 다 끝난 뒤(사용자가 보는 그 화면)에 잰다
    await new Promise(r => requestAnimationFrame(() => setTimeout(r, 0)));
    const r = Math.min(innerWidth, innerHeight) / 2, cx = innerWidth / 2, cy = innerHeight / 2;
    const bad = [];
    for (const el of document.querySelectorAll('.fit, .fade')) {
      if (!el.offsetParent) continue;
      const op = parseFloat(getComputedStyle(el).opacity);
      if (op <= 0.7) continue;
      const b = el.getBoundingClientRect();
      if (b.bottom < 0 || b.top > innerHeight || !b.width) continue;
      let L = b.left, R = b.right;
      if (el.tagName === 'P' || el.tagName === 'SPAN') { const rg = document.createRange(); rg.selectNodeContents(el); const rr = rg.getBoundingClientRect(); if (rr.width) { L = rr.left; R = rr.right; } }
      if (el.classList.contains('ctl') || el.classList.contains('row') || el.classList.contains('more')) { const bs = [...el.children].filter(c => c.offsetParent).map(c => c.getBoundingClientRect()); if (bs.length) { L = Math.min(...bs.map(q => q.left)); R = Math.max(...bs.map(q => q.right)); } }
      for (const yy of [Math.max(b.top, 0), Math.min(b.bottom, innerHeight)]) {
        const dy = Math.abs(yy - cy); const half = dy >= r ? 0 : Math.sqrt(r * r - dy * dy);
        const over = Math.max(cx - half - L, R - (cx + half));
        if (over > 4) bad.push(`${el.id || el.className}(${el.textContent.trim().slice(0, 12)}) +${over.toFixed(1)}px y=${Math.round(yy)} op=${op}`);
      }
    }
    return bad;
  });
}

// ---------- 불변식 ----------
class Checker {
  constructor(name) { this.name = name; this.fails = []; this.hiddenSince = null; this.idleSince = Date.now(); this.loadSince = null; this.autoSince = null; this.lastState = null; this.stateSince = Date.now(); this.errs = 0; this.pausedAt = null; this.promise = null; this.promiseSince = 0; }
  fail(step, msg, s) { this.fails.push({ step, msg, log: s?.log }); }
  // 사용자가 새로 틀려고 함 — '오래 못 받는 동안 화면 켜 둠'을 새로 잰다
  userAct() { if (this.loadSince) this.loadSince = Date.now(); }
  check(step, s, errors) {
    if (errors.length) this.fail(step, '페이지 오류: ' + errors.join(' | '), s), errors.length = 0;
    if (s.frames > 1) this.fail(step, `플레이어가 ${s.frames}개`, s);
    if (s.box && (Math.abs(s.box.w - 200) > 0.6 || Math.abs(s.box.h - 200) > 0.6)) this.fail(step, `플레이어 크기 ${s.box.w}×${s.box.h}`, s);
    if (s.overlay) this.fail(step, '플레이어 위에 겹침: ' + s.overlay, s);
    if (s.sw > s.vw + 1) this.fail(step, `가로 넘침 ${s.sw} > ${s.vw}`, s);
    for (const v of s.violations) this.fail(step, '유튜브 규칙 위반: ' + JSON.stringify(v), s);
    const now = Date.now();
    const st = s.player ? s.player.state : null;
    if (st !== this.lastState) { this.lastState = st; this.stateSince = now; }
    if (s.vis === 'hidden') { this.hiddenSince ??= now; } else this.hiddenSince = null;
    if (this.hiddenSince && now - this.hiddenSince > 700 && st === 1) this.fail(step, '숨겨졌는데 재생 중', s);
    // 화면 켜 두기: 멈춤·끝·대기·숨김이면 바로(1.2초) 꺼져야, 불러오기(-1·3)가 17초 넘게 이어져도 꺼져야.
    // 단 페이지가 저절로 다음 곡으로 넘어가는 중(data-auto)이면 켜 둬도 되지만 17초까지만
    if (s.auto && s.vis === 'visible') this.autoSince ??= now; else this.autoSince = null;
    if (this.autoSince && now - this.autoSince > 17000 && s.wake.active > 0) this.fail(step, '넘어가는 중이 너무 길게 화면 켜 둠', s);
    const idle = (st == null || [0, 2, 5].includes(st) || s.vis === 'hidden') && !(s.auto && s.vis === 'visible');
    if (idle) this.idleSince ??= now; else this.idleSince = null;
    if ((st === -1 || st === 3) && s.wake.active > 0) this.loadSince ??= now; else this.loadSince = null;
    if (s.errs !== this.errs) { this.errs = s.errs; if (this.loadSince) this.loadSince = now; }   // 막힌 곡 → 다음 곡을 새로 시도
    // 사용자가 ⏸ 를 눌렀으면, 다른 걸 누르기 전엔 다시 재생되면 안 된다 (불러오던 곡이 멈춤을 놓쳐도)
    if (this.pausedAt && now - this.pausedAt > 1200 && st === 1) { this.fail(step, '⏸ 를 눌렀는데 다시 재생됨', s); this.pausedAt = null; }
    if (this.pausedAt && now - this.pausedAt > 1500 && s.wake.active > 0) { this.fail(step, '⏸ 뒤에도 화면 켜 두기', s); this.pausedAt = null; }
    // '넘어가요'·'이어서 틀어요' 같은 약속이 보이는 채로 17초 넘게 그대로면 지켜지지 않은 것
    const promise = s.vis === 'visible' && /넘어가요|이어서 틀어요|이어서 찾아요/.test(s.status) ? s.status : null;
    if (promise !== this.promise) { this.promise = promise; this.promiseSince = now; }
    else if (promise && now - this.promiseSince > 17000) { this.fail(step, '지켜지지 않는 안내: ' + promise, s); this.promise = null; }
    if (s.wake.active > 1) this.fail(step, `화면 켜 두기 ${s.wake.active}개`, s);
    if (this.idleSince && now - this.idleSince > 1200 && s.wake.active > 0) this.fail(step, `재생 안 하는데(상태 ${st}) 화면 켜 두기가 남음`, s);
    if (this.loadSince && now - this.loadSince > 17000) this.fail(step, '오래 못 받는데 화면 켜 두기가 남음', s);
    const settled = now - this.stateSince > 400;
    if (settled && s.player && s.play && !s.play.hidden) {
      let want = null;
      if (st === 1) want = '일시정지';
      else if ([0, 2, 5].includes(st)) want = '재생';
      else if (this.loadSince && now - this.loadSince > 17000) want = '재생';
      if (want && s.play.label !== want) this.fail(step, `▶ 표시 '${s.play.label}' ≠ 상태 ${st}`, s);
    }
    for (const t of [s.status, s.title, s.channel, s.time ?? '']) if (/undefined|null|NaN|\[object/.test(t)) this.fail(step, '이상한 글자: ' + t, s);
    if (s.time && !/^(\d+:\d\d(:\d\d)? \/ \d+:\d\d(:\d\d)?|LIVE|\d+:\d\d(:\d\d)?)$/.test(s.time)) this.fail(step, '시간 표시 이상: ' + s.time, s);
    if (settled && s.player && s.next && !s.next.hidden && s.player.ready) {
      const canNext = s.player.len > 1 && s.player.idx >= 0 && s.player.idx < s.player.len - 1;
      if (canNext && s.next.disabled && [1, 2].includes(st)) this.fail(step, '다음 곡이 있는데 ⏭ 꺼짐', s);
      if (!canNext && !s.next.disabled && s.player.len <= 1) this.fail(step, '목록이 없는데 ⏭ 켜짐', s);
    }
    for (const t of s.thumbs) if (t.vis && (t.w < 119.5 || t.h < 69.5 || t.t)) this.fail(step, `미리보기 ${t.w}×${t.h} 변형 ${t.t}`, s);
  }
}

// ---------- 동작 ----------
const A = {
  async wait(page, ms) { await page.waitForTimeout(ms); },
  async tapPlayerButton(page) {   // 플레이어 가운데 ▶ (유튜브 쪽)
    const f = await page.$('#stage iframe'); if (!f) return 'no-player';
    const fr = await f.contentFrame(); if (!fr) return 'no-frame';
    const b = await fr.$('#b'); if (!b) return 'no-button';
    const box = await f.boundingBox(); if (!box || box.y + box.height < 0 || box.y > (await page.evaluate(() => innerHeight))) return 'offscreen';
    await b.tap().catch(() => {}); return 'ok';
  },
  async tap(page, sel) {
    const el = await page.$(sel); if (!el) return 'missing';
    const dis = await el.evaluate(e => e.disabled || !e.offsetParent); if (dis) return 'disabled';
    await el.scrollIntoViewIfNeeded().catch(() => {});
    await el.tap({ timeout: 1500 }).catch(() => {}); return 'ok';
  },
  async tapUpNext(page, k) {
    const items = await page.$$('#list li button'); if (!items.length) return 'none';
    const it = items[k % items.length];
    await it.scrollIntoViewIfNeeded().catch(() => {});
    await it.tap({ timeout: 1500 }).catch(() => {}); return 'ok';
  },
  // 베젤을 계속 돌려 아래에 머묾 (그동안 화면이 플레이어로 올라가지 못함)
  async holdScroll(page, ms) { const end = Date.now() + ms; while (Date.now() < end) { await page.evaluate(() => scrollTo(0, document.scrollingElement.scrollHeight)); await page.waitForTimeout(50); } },
  async scroll(page, frac) { await page.evaluate(f => scrollTo(0, (document.scrollingElement.scrollHeight - innerHeight) * f), frac); },
  async scrollToNow(page) { await page.evaluate(() => document.getElementById('now')?.scrollIntoView({ block: 'center' })); },
  async hide(page) { await page.evaluate(() => window.__setVisible(false)); },
  async show(page) { await page.evaluate(() => window.__setVisible(true)); },
  async offline(page, ctx) { await page.evaluate(() => { window.__ytcfg.offline = true; }); await ctx.setOffline(true); },
  async online(page, ctx) { await page.evaluate(() => { window.__ytcfg.offline = false; }); await ctx.setOffline(false); },
  async hash(page, h) { await page.evaluate(h2 => { location.hash = h2; }, stamp(h)); },
  async reload(page) { await page.reload(); },
  // 화면이 꺼진 동안: 플레이어(iframe)는 얼고, 페이지 시계는 한꺼번에 흘러 밀린 타이머가 깨어날 때 한 번씩 돈다
  async freeze(page) { await page.evaluate(() => window.__fakeYT && window.__fakeYT.freeze()); },
  async thaw(page) { await page.evaluate(() => window.__fakeYT && window.__fakeYT.thaw()); },
  async ff(page, ms) { await page.clock.fastForward(ms); },
  // 손목을 내렸다 올림 (얼었다 깨어남): showFirst 면 화면이 먼저 켜지고 플레이어가 나중에 깨어남
  async lid(page, [ms, showFirst]) {
    await A.hide(page); await A.freeze(page); await A.ff(page, ms);
    if (showFirst) { await A.show(page); await A.thaw(page); } else { await A.thaw(page); await page.waitForTimeout(50); await A.show(page); }
  },
  async back(page) { await page.evaluate(() => history.back()); },
};

// ---------- 정해 둔 시나리오 ----------
const ID2 = 'kJQP7kiw5Fk', ID3 = '9bZkp7q19f0';
const scripted = [
  { name: '자동재생OK_믹스_기본', cfg: { autoplay: true, mixLen: 6, duration: 3, speed: 2 }, steps: [
    ['wait', 2500], ['scrollToNow'], ['wait', 600], ['tap', '#next'], ['wait', 1500], ['tap', '#prev'], ['wait', 1500],
    ['tapUpNext', 1], ['wait', 1500], ['tap', '#play'], ['wait', 700], ['tap', '#play'], ['wait', 1500],
    ['tap', '#volDown'], ['tap', '#volDown'], ['wait', 300], ['tap', '#volUp'], ['wait', 400], ['scroll', 1], ['wait', 600], ['scroll', 0.5], ['wait', 4000] ] },
  { name: '자동재생막힘_플레이어▶', cfg: { autoplay: false, mixLen: 5, duration: 3, speed: 2 }, steps: [
    ['wait', 1500], ['expect', s => s.player?.state !== 1, '막히면 재생 안 됨'], ['tapPlayerButton'], ['wait', 1500], ['expect', s => s.player?.state === 1, '플레이어 ▶ 로 재생'] ] },
  { name: '자동재생막힘_우리▶_아래에서', cfg: { autoplay: false, mixLen: 5, duration: 3 }, steps: [
    ['wait', 1500], ['scrollToNow'], ['wait', 500], ['tap', '#play'], ['wait', 2000], ['expect', s => s.player?.state === 1 && s.scrollY < 30, '위로 올라가서 재생'] ] },
  { name: '첫곡막힘150_믹스는이어감', cfg: { autoplay: true, mixLen: 5, duration: 3, speed: 2, errors: { dQw4w9WgXcQ: 150 } }, steps: [
    ['wait', 4500], ['expect', s => s.player?.idx >= 1 && s.player?.state === 1, '막힌 첫 곡을 건너뛰고 다음 곡 재생'] ] },
  { name: '전부막힘_멈춤', cfg: { autoplay: true, mixLen: 20, duration: 3, errorAll: 150 }, steps: [
    ['wait', 30000], ['expect', s => s.player && s.player.idx < 19 && /많/.test(s.status), '연속으로 막히면 멈추고 알림'] ] },
  { name: '마지막곡막힘_새믹스', cfg: { autoplay: true, mixLen: 3, duration: 2, speed: 2, errors: { dQw4wx00002: 150 } }, steps: [
    ['wait', 9000], ['expect', s => s.player && s.player.id !== 'dQw4wx00002', '마지막 곡이 막혀도 새 믹스로 이어감'] ] },
  { name: '믹스설정오류_이곡만', cfg: { autoplay: true, mixError: 2, duration: 4 }, steps: [
    ['wait', 2500], ['expect', s => s.player && s.player.len <= 1 && s.player.state === 1, '믹스를 못 불러오면 이 곡만 재생'] ] },
  { name: '믹스없음', cfg: { autoplay: true, noMix: ['dQw4w9WgXcQ'], duration: 3, speed: 2 }, steps: [
    ['wait', 1500], ['expect', s => s.next?.disabled && !s.upnext, '목록 없으면 ⏭ 꺼짐·다음 곡 없음'], ['wait', 3000] ] },
  { name: '믹스끝_새믹스이어감', cfg: { autoplay: true, mixLen: 3, duration: 2, speed: 2 }, steps: [
    ['wait', 9000], ['expect', s => s.player && s.player.id !== 'dQw4wx00002' && s.player.state === 1, '믹스를 다 들으면 비슷한 곡으로 이어서'] ] },
  { name: '한곡반복_단일', hash: '#v=dQw4w9WgXcQ&t=x', cfg: { autoplay: true, duration: 4, speed: 2 }, steps: [
    ['wait', 800], ['scrollToNow'], ['tap', '#repeat'], ['wait', 4000], ['expect', s => s.player?.state === 1 || s.player?.state === 3, '한 곡 반복이면 끝나도 다시'] ] },
  { name: '한곡반복_믹스중간', cfg: { autoplay: true, mixLen: 5, duration: 4, speed: 2 }, steps: [
    ['wait', 800], ['scrollToNow'], ['tap', '#repeat'], ['wait', 5000], ['expect', s => s.player?.idx === 0, '반복이면 다음 곡으로 안 넘어감'],
    ['tap', '#next'], ['wait', 1500], ['expect', s => s.player?.idx === 1, '직접 ⏭ 는 넘어감'], ['tap', '#repeat'], ['wait', 3000] ] },
  { name: '인터넷끊김', cfg: { autoplay: true, mixLen: 4, duration: 3 }, steps: [
    ['wait', 1500], ['offline'], ['tap', '#next'], ['wait', 16500], ['expect', s => /인터넷/.test(s.status), '끊기면 알림'], ['online'], ['wait', 1500], ['expect', s => !/끊겼/.test(s.status), '다시 연결되면 알림 바뀜'] ] },
  { name: '숨김켜짐반복', cfg: { autoplay: true, mixLen: 5, duration: 3, speed: 2 }, steps: [
    ['wait', 1500], ['hide'], ['wait', 1200], ['expect', s => s.player?.state !== 1, '숨기면 멈춤'], ['show'], ['wait', 1500], ['expect', s => s.player?.state !== 1 && /멈췄/.test(s.status), '돌아오면 안내만'],
    ['tap', '#play'], ['wait', 1500], ['hide'], ['wait', 300], ['show'], ['wait', 200], ['hide'], ['wait', 1500], ['show'], ['wait', 800] ] },
  { name: '숨긴채넘어감', cfg: { autoplay: true, mixLen: 5, duration: 2, speed: 1 }, steps: [
    ['wait', 1200], ['hide'], ['wait', 4000], ['expect', s => s.player?.state !== 1, '숨겨진 채로 다음 곡이 시작되지 않음'], ['show'], ['wait', 1000] ] },
  { name: '아래로내린채다른곡', cfg: { autoplay: true, mixLen: 5, duration: 5 }, steps: [
    ['wait', 1500], ['scroll', 1], ['wait', 300], ['hash', '#v=' + ID2 + '&mix=1&n=NOW'], ['wait', 2000], ['expect', s => s.player?.id === ID2 && s.scrollY < 30, '새 곡은 맨 위에서'] ] },
  { name: '같은곡다시', cfg: { autoplay: true, mixLen: 5, duration: 5 }, steps: [
    ['wait', 1500], ['tap', '#next'], ['wait', 1200], ['hash', '#v=dQw4w9WgXcQ&mix=1&n=NOW'], ['wait', 1500], ['expect', s => s.player?.idx === 0 && s.player?.id === 'dQw4w9WgXcQ', '같은 곡을 다시 고르면 처음부터'] ] },
  { name: '숨김중다른곡', cfg: { autoplay: true, mixLen: 5, duration: 5 }, steps: [
    ['wait', 1500], ['hide'], ['hash', '#v=' + ID3 + '&mix=1&n=NOW'], ['wait', 1500], ['expect', s => s.player?.state !== 1, '숨김 중엔 새 곡도 재생 안 함'],
    ['show'], ['wait', 1500], ['expect', s => s.player?.state === 1 && s.player?.id === ID3, '화면이 켜지면 방금 고른 곡 시작'] ] },
  { name: '숨김중고른곡_준비전켜짐', cfg: { autoplay: true, mixLen: 5, duration: 5, readyMs: 900 }, steps: [
    ['wait', 1500], ['hide'], ['hash', '#v=' + ID2 + '&n=NOW'], ['wait', 200], ['show'], ['wait', 2200], ['expect', s => s.player?.state === 1 && s.player?.id === ID2, '준비되면 시작'] ] },
  { name: '뒤로가기_예전곡은안틂', cfg: { autoplay: true, mixLen: 5, duration: 20 }, steps: [
    ['wait', 1200], ['hash', '#v=' + ID2 + '&mix=1&n=NOW'], ['wait', 1500], ['expect', s => s.player?.id === ID2 && s.player?.state === 1, '새 곡 재생'],
    ['back'], ['wait', 3200], ['expect', s => s.player?.id === 'dQw4w9WgXcQ' && s.player?.state !== 1 && /▶/.test(s.status), '뒤로 가면 예전 곡이 보이지만 저절로 안 틂'],
    ['tapPlayerButton'], ['wait', 1500], ['expect', s => s.player?.state === 1, '누르면 재생'] ] },
  { name: '오래된탭_안틂', hash: '#v=dQw4w9WgXcQ&mix=1&n=OLD', cfg: { autoplay: true, mixLen: 4, duration: 20 }, steps: [
    ['wait', 3200], ['expect', s => s.player && s.player.state !== 1 && /▶/.test(s.status), '오래된 주소는 저절로 안 틂 + 안내'],
    ['scrollToNow'], ['tap', '#play'], ['wait', 1800], ['expect', s => s.player?.state === 1 && s.scrollY < 30, '▶ 로 재생'] ] },
  { name: '막힌곡넘기는중_숨김', cfg: { autoplay: true, mixLen: 5, duration: 3, speed: 2, errors: { dQw4wx00001: 150 }, durations: { dQw4wx00002: 40 } }, steps: [
    ['wait', 3300], ['expect', s => /넘어가요/.test(s.status), '막힌 곡 알림'], ['hide'], ['wait', 3500], ['expect', s => s.player?.state !== 1, '숨김 중엔 안 넘어감'],
    ['show'], ['wait', 300], ['expect', s => /▶ 로 이어/.test(s.status) && !/넘어가요/.test(s.status), '돌아오면 ▶ 안내 (넘어간다는 말 없음)'],
    ['scrollToNow'], ['tap', '#play'], ['wait', 2200], ['expect', s => s.player?.idx === 2 && s.player?.state === 1, '▶ 는 막힌 곡 말고 다음 곡'] ] },
  { name: '믹스끝에서다음_새믹스', cfg: { autoplay: true, mixLen: 3, duration: 30 }, steps: [
    ['wait', 1500], ['scrollToNow'], ['tap', '#next'], ['wait', 1300], ['tap', '#next'], ['wait', 1300], ['expect', s => s.player?.idx === 2 && s.next && !s.next.disabled, '끝 곡에서도 ⏭ 켜짐'],
    ['tap', '#next'], ['wait', 2500], ['expect', s => s.pcount === 1 && s.player?.state === 1 && !['dQw4w9WgXcQ', 'dQw4wx00001', 'dQw4wx00002'].includes(s.player?.id), '끝에서 ⏭ → 비슷한 곡'] ] },
  { name: '넘어가는중멈춤_놓쳐도', cfg: { autoplay: true, mixLen: 5, duration: 3, pauseLate: true, bufferMs: 2000 }, steps: [
    ['wait', 5600], ['expect', s => s.play?.label === '일시정지' && [3, -1].includes(s.player?.state), '다음 곡 불러오는 중'], ['scrollToNow'], ['tap', '#play'], ['wait', 3500],
    ['expect', s => s.player?.state !== 1 && s.play?.label === '재생', '멈춤을 놓친 곡이 시작돼도 다시 멈춤'] ] },
  { name: '넘어가는중숨김_놓쳐도', cfg: { autoplay: true, mixLen: 5, duration: 3, pauseLate: true, bufferMs: 2000 }, steps: [
    ['wait', 5600], ['hide'], ['wait', 300], ['show'], ['wait', 3000], ['expect', s => s.player?.state !== 1 && /멈췄/.test(s.status), '잠깐 꺼졌다 켜져도 저절로 안 틂'] ] },
  { name: '반복대기중멈춤_다시안틂', cfg: { autoplay: true, mixLen: 4, duration: 3, bufferMs: 3000 }, steps: [
    ['wait', 3600], ['expect', s => s.player?.state === 1, '첫 곡 재생 중'], ['tap', '#repeat'], ['holdScroll', 3000], ['expect', s => s.play?.label === '일시정지', '곡이 끝나 반복하려고 플레이어가 보이길 기다리는 중'],
    ['tap', '#play'], ['scroll', 0], ['wait', 3500], ['expect', s => s.player?.state !== 1 && s.play?.label === '재생', '⏸ 뒤엔 기다리던 반복도 안 함'],
    ['tap', '#repeat'], ['scroll', 0], ['wait', 300] ] },
  { name: '멈춤놓침_다음곡막힘', cfg: { autoplay: true, mixLen: 5, duration: 3, pauseLate: true, bufferMs: 2000, errors: { dQw4wx00001: 150 } }, steps: [
    ['wait', 5600], ['expect', s => s.play?.label === '일시정지' && s.player?.idx === 1, '다음 곡(막힌 곡) 불러오는 중'], ['scrollToNow'], ['tap', '#play'], ['wait', 6000],
    ['expect', s => s.player?.state !== 1 && s.player?.idx === 1 && s.wake.active === 0 && !/넘어가요/.test(s.status), '멈춘 뒤 막힌 곡이 와도 저절로 안 넘어감'],
    ['tap', '#play'], ['wait', 2600], ['expect', s => s.player?.idx === 2 && s.player?.state === 1, '▶ 로 그다음 곡'] ] },
  { name: '잠자기_넘어가는중_놓침', cfg: { autoplay: true, mixLen: 5, duration: 3, pauseLate: true, bufferMs: 2500, minuteMs: 330, errors: { dQw4wx00001: 150 } }, steps: [
    ['wait', 600], ['scroll', 0.4], ['tap', '#sleep'], ['scroll', 0], ['wait', 12000],
    ['expect', s => s.player?.state !== 1 && /잠자기/.test(s.status) && s.wake.active === 0, '잠자기 타이머는 넘어가는 중이어도 멈춤'] ] },
  { name: '막힌곡뒤▶_다음곡그대로', cfg: { autoplay: true, mixLen: 6, duration: 30, errors: { dQw4wx00001: 150 }, bufferMs: 1600 }, steps: [
    ['wait', 2500], ['scrollToNow'], ['tap', '#next'], ['wait', 2500], ['expect', s => /넘어가요/.test(s.status), '막힌 곡'], ['wait', 2000],
    ['expect', s => s.player?.idx === 2 && s.player?.state !== 1, '다음 곡 불러오는 중'], ['tap', '#play'], ['wait', 2500],
    ['expect', s => s.player?.idx === 2 && s.player?.state === 1, '▶ 가 불러오던 곡을 건너뛰지 않음'] ] },
  { name: '꺼진사이고른곡_오래지나면안틂', cfg: { autoplay: true, mixLen: 4, duration: 20, freshMs: 2500 }, steps: [
    ['wait', 1200], ['hide'], ['hash', '#v=' + ID2 + '&n=NOW'], ['wait', 3500], ['show'], ['wait', 1500],
    ['expect', s => s.player?.id === ID2 && s.player?.state !== 1 && /▶/.test(s.status), '고른 지 오래되면 켜져도 저절로 안 틂'] ] },
  { name: '고르자마자폰으로_돌아와도안틂', cfg: { autoplay: true, mixLen: 4, duration: 20, pauseLate: true, bufferMs: 3000 }, steps: [
    ['wait', 300], ['scroll', 1], ['tap', '#toPhone'], ['hide'], ['wait', 4500], ['show'], ['wait', 2000],
    ['expect', s => s.navs.length === 1 && s.player?.state !== 1 && s.wake.active === 0, '폰으로 넘긴 곡은 워치에서 다시 안 틂'] ] },
  { name: '플레이어준비안됨_다시시도', cfg: { neverReady: true, pageReadyMs: 2500 }, steps: [
    ['wait', 3200], ['expect', s => s.retry && !s.retry.hidden && /불러오지/.test(s.status) && /다시 시도/.test(s.status), '준비 안 되면 다시 시도 안내'] ] },
  { name: '플레이어준비안됨_꺼진동안은안셈', cfg: { neverReady: true, pageReadyMs: 2500, clock: true }, steps: [
    ['wait', 800], ['hide'], ['ff', 60000], ['show'], ['wait', 1200], ['expect', s => s.retry?.hidden && !/불러오지/.test(s.status), '꺼져 있던 시간은 세지 않음'],
    ['wait', 2000], ['expect', s => s.retry && !s.retry.hidden, '켜진 채 기다리면 다시 시도 안내'] ] },
  { name: '스크립트받다꺼짐_다시연결돼도안새로고침', cfg: { autoplay: true, clock: true, apiMs: 3000, mixLen: 4, duration: 60 }, steps: [
    ['hide'], ['freeze'], ['ff', 20000], ['show'], ['thaw'], ['wait', 2500],
    ['expect', s => s.player?.state === 1 && s.retry?.hidden && !/연결하지/.test(s.status + s.stage), '늦게 온 스크립트로 바로 재생 (꺼진 동안 못 받음으로 안 셈)'],
    ['offline'], ['wait', 600], ['online'], ['wait', 1500], ['expect', s => s.loads === 1 && s.player?.state === 1, '인터넷이 다시 연결돼도 새로 고치지 않음'] ] },
  { name: '고르고바로꺼짐_얼었다켜짐_재생', cfg: { autoplay: true, clock: true, readyMs: 1500, mixLen: 4, duration: 30 }, steps: [
    ['wait', 1500], ['hash', '#v=' + ID2 + '&mix=1&n=NOW'], ['hide'], ['freeze'], ['ff', 25000], ['show'], ['thaw'], ['wait', 3000],
    ['expect', s => s.frames === 1 && s.player?.id === ID2 && s.player?.state === 1 && !/불러오지/.test(s.status), '고른 곡이 켜지자 시작 (준비 기다림이 꺼진 시간을 세지 않음)'] ] },
  { name: '고르고바로꺼짐_며칠뒤켜짐_안틂', cfg: { autoplay: true, clock: true, readyMs: 1500, mixLen: 4, duration: 30 }, steps: [
    ['wait', 1500], ['hash', '#v=' + ID2 + '&mix=1&n=NOW'], ['hide'], ['freeze'], ['ff', 6 * 60000], ['show'], ['thaw'], ['wait', 3500],
    ['expect', s => s.player?.id === ID2 && s.player?.state !== 1 && /▶/.test(s.status) && !/불러오지/.test(s.status), '오래 지나 켜지면 저절로 안 틂 + ▶ 안내'] ] },
  { name: '다음곡불러오다꺼짐_얼었다켜짐_안틂', cfg: { autoplay: true, clock: true, mixLen: 5, duration: 30, pauseLate: true, bufferMs: 2000 }, steps: [
    ['wait', 2600], ['expect', s => s.player?.state === 1, '첫 곡 재생 중'], ['scrollToNow'], ['tap', '#next'], ['wait', 400],
    ['expect', s => [3, -1].includes(s.player?.state) && s.player?.idx === 1, '다음 곡 불러오는 중'], ['hide'], ['wait', 300], ['freeze'], ['ff', 30000], ['show'], ['thaw'], ['wait', 4000],
    ['expect', s => s.player?.state !== 1 && /멈췄/.test(s.status), '깨어난 뒤 늦게 시작돼도 다시 멈춤'] ] },
  { name: '이어듣기중꺼짐_목록안옴', cfg: { autoplay: true, mixLen: 2, duration: 3, speed: 2, cueStall: true }, steps: [
    ['wait', 3900], ['expect', s => /이어서 틀어요/.test(s.status), '믹스 끝 → 비슷한 곡 받는 중'], ['hide'], ['wait', 1000], ['show'], ['wait', 300],
    ['expect', s => /▶ 로 이어/.test(s.status), '돌아오면 ▶ 안내'], ['scrollToNow'], ['tap', '#play'], ['wait', 300], ['expect', s => /이어서 틀어요/.test(s.status), '▶ 로 다시 받기'],
    ['wait', 15800], ['expect', s => /못 찾았어요/.test(s.status) && s.wake.active === 0, '끝내 안 오면 알림 (갇히지 않음)'] ] },
  { name: '새로고침_바로안틂', cfg: { autoplay: true, mixLen: 4, duration: 30 }, steps: [
    ['wait', 1500], ['expect', s => s.player?.state === 1, '처음엔 재생'], ['reload'], ['wait', 3000], ['expect', s => s.player && s.player.state !== 1 && /▶/.test(s.status), '새로 고침하면 ▶ 를 기다림'] ] },
  { name: '막힌곡많음_소리눌러도안내', cfg: { autoplay: true, mixLen: 12, duration: 3, errorAll: 150 }, steps: [
    ['wait', 19000], ['expect', s => /많/.test(s.status), '많이 막힘 안내'], ['scroll', 0.4], ['tap', '#volUp'], ['wait', 2300], ['expect', s => /많/.test(s.status), '소리 알림 뒤 안내 되돌림'] ] },
  { name: '연속막힘_화면켜둠', cfg: { autoplay: true, mixLen: 12, duration: 3, speed: 2, errors: Object.fromEntries([1, 2, 3, 4, 5, 6, 7, 8].map(i => ['dQw4wx0000' + i, 150])) }, steps: [
    ['wait', 7000], ['expect', s => s.wake.active === 1, '막힌 곡을 넘기는 동안 화면 켜 둠'], ['wait', 14000],
    ['expect', s => /많/.test(s.status) && s.wake.active === 0, '여러 곡이 막히면 멈추고 화면 켜 두기 끝'] ] },
  { name: '새로고침', cfg: { autoplay: false, mixLen: 4, duration: 3 }, steps: [
    ['wait', 1200], ['tapPlayerButton'], ['wait', 1200], ['reload'], ['wait', 1500], ['expect', s => s.player && s.title.length > 0, '새로 고침해도 그 곡'] ] },
  { name: '유튜브스크립트실패', cfg: { apiFail: true }, steps: [
    ['wait', 1500], ['expect', s => s.retry && !s.retry.hidden && /연결/.test(s.title + s.status), '못 받으면 안내 + 다시 시도'] ] },
  { name: '유튜브스크립트멈춤', cfg: { apiNeverReady: true }, steps: [
    ['wait', 16500], ['expect', s => s.retry && !s.retry.hidden, '너무 오래 걸리면 다시 시도'] ] },
  { name: '플레이어준비안됨', cfg: { neverReady: true }, steps: [
    ['wait', 600], ['scrollToNow'], ['tap', '#play'], ['tap', '#next'], ['tap', '#volUp'], ['wait', 600], ['expect', s => s.player && s.player.state === -1, '준비 전 누름은 무시'] ] },
  { name: '곡이름그림실패', cfg: { autoplay: true, mixLen: 6, duration: 4, oembedFail: true, thumbFail: true }, steps: [
    ['wait', 2500], ['scroll', 0.8], ['wait', 600], ['expect', s => s.nUp > 0, '실패해도 다음 곡 목록은 있음'] ] },
  { name: '화면켜두기거부', cfg: { autoplay: true, mixLen: 4, duration: 3, wakeLock: 'reject' }, steps: [['wait', 4000]] },
  { name: '화면켜두기없음', cfg: { autoplay: true, mixLen: 4, duration: 3, wakeLock: 'none' }, steps: [['wait', 4000]] },
  { name: '생방송', hash: '#v=jfKfPfyJRdk&mix=1', cfg: { autoplay: true, durations: { jfKfPfyJRdk: 0 }, mixLen: 3 }, steps: [
    ['wait', 2500], ['expect', s => s.player?.state === 1 && /LIVE/.test(s.time || ''), '생방송은 LIVE'] ] },
  { name: '빠르게연타', cfg: { autoplay: true, mixLen: 8, duration: 5 }, steps: [
    ['wait', 1500], ['scrollToNow'], ['tap', '#next'], ['tap', '#next'], ['tap', '#next'], ['tap', '#prev'], ['tap', '#next'], ['tap', '#play'], ['tap', '#play'], ['wait', 2500] ] },
  { name: '폰에서이어듣기', cfg: { autoplay: true, mixLen: 5, duration: 20 }, steps: [
    ['wait', 3500], ['scroll', 1], ['wait', 300], ['tap', '#toPhone'], ['wait', 600],
    ['expect', s => s.navs.length === 1 && /^intent:\/\/tube\/phone\?v=dQw4w9WgXcQ&t=\d+&mix=1&title=.+#Intent;scheme=kakaowatch;package=com\.mywatch\.kakaomap;end$/.test(s.navs[0]) && s.player.state !== 1, '이어 듣기 링크 + 멈춤'] ] },
  { name: '소리끝까지', cfg: { autoplay: true, mixLen: 3, duration: 30 }, steps: [
    ['wait', 1200], ['scroll', 0.4], ...Array(12).fill(['tap', '#volUp']), ['wait', 300], ['expect', s => s.player?.vol === 100, '소리 100 까지'],
    ...Array(12).fill(['tap', '#volDown']), ['wait', 300], ['expect', s => s.player?.vol === 0 && /0/.test(s.status), '소리 0 까지 + 알림'] ] },
  { name: '새믹스같은플레이어', cfg: { autoplay: true, mixLen: 3, duration: 2, speed: 2 }, steps: [
    ['wait', 7000], ['expect', s => s.pcount === 1 && s.player?.state === 1 && !['dQw4w9WgXcQ', 'dQw4wx00001', 'dQw4wx00002'].includes(s.player?.id), '같은 플레이어로 새 믹스(방금 곡 아닌 곡)'] ] },
  { name: '숨김중믹스끝', cfg: { autoplay: true, mixLen: 2, duration: 3, speed: 1 }, steps: [
    ['wait', 2500], ['hide'], ['wait', 5000], ['expect', s => s.player?.state !== 1, '숨김 중엔 이어서 틀지 않음'], ['show'], ['wait', 1500], ['expect', s => s.player?.state !== 1, '돌아와도 저절로 안 틂'] ] },
  { name: '반복기억', cfg: { autoplay: true, mixLen: 3, duration: 20 }, steps: [
    ['wait', 1000], ['scroll', 0.4], ['tap', '#repeat'], ['wait', 300], ['reload'], ['wait', 1500], ['expect', s => s.repeat?.pressed === 'true', '새로 고침해도 반복 켜짐'], ['scroll', 0.4], ['tap', '#repeat'], ['wait', 300] ] },
  { name: '잠자기타이머', cfg: { autoplay: true, mixLen: 3, duration: 60, minuteMs: 100 }, steps: [
    ['wait', 1200], ['scroll', 0.4], ['tap', '#sleep'], ['wait', 300], ['expect', s => /15분 뒤/.test(s.status), '15분 타이머'],
    ['scroll', 0], ['wait', 2200], ['expect', s => s.player?.state !== 1 && /잠자기/.test(s.status), '시간이 되면 멈춤'] ] },
  { name: '잠자기타이머끄기', cfg: { autoplay: true, mixLen: 3, duration: 60, minuteMs: 100 }, steps: [
    ['wait', 1200], ['scroll', 0.4], ['tap', '#sleep'], ['tap', '#sleep'], ['tap', '#sleep'], ['tap', '#sleep'], ['wait', 300], ['expect', s => /끔/.test(s.status), '네 번 누르면 꺼짐'],
    ['scroll', 0], ['wait', 7000], ['expect', s => s.player?.state === 1, '끄면 멈추지 않음'] ] },
  { name: '곡없이열기', hash: '', cfg: { autoplay: true }, steps: [
    ['wait', 1200], ['expect', s => !s.player && /곡이 없어요/.test(s.title) && s.play?.disabled, '안내만'] ] },
  { name: '잘못된주소', hash: '#v=<script>&mix=1', cfg: { autoplay: true }, steps: [
    ['wait', 1200], ['expect', s => !s.player, '이상한 번호는 무시'] ] },
];
const viewports = [
  { tag: '226', vw: 226 }, { tag: '240', vw: 240 }, { tag: '216', vw: 216, dpr: 2 }, { tag: '200', vw: 200, dpr: 2.4 },
  { tag: '226x196', vw: 226, vh: 196 }, { tag: '360', vw: 360, dpr: 1.333 }, { tag: '226_text1.3', vw: 226, textScale: 1.3 },
];

async function runScenario(browser, sc, label) {
  const { ctx, page, errors, nav } = await openPage(browser, sc);
  const snapN = async () => { const s = await snap(page); s.loads = nav.loads; return s; };
  const ck = new Checker(label);
  let i = 0;
  try {
    await page.waitForTimeout(300);
    for (const [act, a1, a2] of sc.steps) {
      i++;
      if (act === 'expect') {
        const s = await snapN();
        if (!a1(s)) ck.fail(i, '기대 실패: ' + a2 + ` (state=${s.player?.state} idx=${s.player?.idx}/${s.player?.len} id=${s.player?.id} status='${s.status}' title='${s.title}' y=${s.scrollY})`, s);
        continue;
      }
      if (act === 'wait') {
        // 기다리는 동안도 촘촘히 불변식 확인
        const end = Date.now() + a1;
        while (Date.now() < end) { await page.waitForTimeout(Math.min(250, end - Date.now())); ck.check(i, await snap(page), errors); }
        const bad = await fitCheck(page);
        if (bad.length) ck.fail(i, '원 밖으로: ' + bad.slice(0, 3).join(' ; '));
        continue;
      }
      // ⏸ 를 실제로 눌렀는지는 누른 뒤에 안다 (스크롤 중이면 탭이 빗나갈 수 있음)
      let before = null;
      if (act === 'tap' && a1 === '#play') before = await page.evaluate(() => ({ n: window.__playClicks }));
      else if ((act === 'tap' && ['#next', '#prev'].includes(a1)) || ['tapUpNext', 'tapPlayerButton', 'hash', 'reload', 'back'].includes(act)) { ck.pausedAt = null; ck.userAct(); }
      if (act === 'back' && !(ck.depth > 0)) continue;
      if (act === 'hash') ck.depth = (ck.depth || 0) + 1;
      if (act === 'back') ck.depth--;
      if (act === 'offline' || act === 'online') await A[act](page, ctx);
      else if (act === 'tap') await A.tap(page, a1);
      else await A[act](page, a1);
      if (before) {
        const after = await page.evaluate(() => ({ n: window.__playClicks, lab: window.__playClickLabel })).catch(() => before);
        if (after.n > before.n) { ck.pausedAt = after.lab === '일시정지' ? Date.now() : null; ck.userAct(); }
      }
      await page.waitForTimeout(120);
      ck.check(i, await snap(page), errors);
    }
    await page.waitForTimeout(500);
    ck.check(i + 1, await snap(page), errors);
  } catch (e) {
    ck.fail(i, '시뮬레이터 예외: ' + e.message);
  }
  if (ck.fails.length) await page.screenshot({ path: path.join(SHOTS, label.replace(/[^\w가-힣.-]+/g, '_') + '.png') }).catch(() => {});
  await ctx.close();
  return ck.fails;
}

// ---------- 무작위 사용자 (원숭이) ----------
function randomScenario(seed, steps) {
  const r = rng(seed);
  const pick = a => a[Math.floor(r() * a.length)];
  const cfg = {
    autoplay: r() < 0.6, mixLen: 2 + Math.floor(r() * 6), duration: 1 + Math.floor(r() * 4), speed: 1 + Math.floor(r() * 2),
    errors: r() < 0.4 ? { ['dQw4wx0000' + (1 + Math.floor(r() * 3))]: pick([100, 101, 150, 5]) } : undefined,
    mixError: r() < 0.06 ? 2 : undefined, noMix: r() < 0.1 ? ['dQw4w9WgXcQ'] : undefined,
    wakeLock: pick(['ok', 'ok', 'ok', 'reject', 'none']), oembedFail: r() < 0.1, thumbFail: r() < 0.1, minuteMs: 200,
    bufferMs: 150 + Math.floor(r() * 600), longTitles: r() < 0.3, pauseLate: r() < 0.3,
    syncEvents: r() < 0.2, lateIndex: r() < 0.25, cueStall: r() < 0.05, clock: r() < 0.3,
  };
  const vp = pick(viewports);
  const hashes = ['#v=' + ID2 + '&mix=1', '#v=' + ID3, '#v=dQw4w9WgXcQ&mix=1', '#v=' + ID2 + '&t=' + encodeURIComponent('다른 곡')];
  const list = [];
  for (let k = 0; k < steps; k++) {
    const x = r();
    if (x < 0.14) list.push(['wait', 200 + Math.floor(r() * 2500)]);
    else if (x < 0.26) list.push(['tap', pick(['#play', '#next', '#prev', '#volUp', '#volDown', '#repeat', '#sleep'])]);
    else if (x < 0.34) list.push(['tapPlayerButton']);
    else if (x < 0.42) list.push(['tapUpNext', Math.floor(r() * 5)]);
    else if (x < 0.55) list.push(['scroll', r()]);
    else if (x < 0.60) list.push(['scrollToNow']);
    else if (x < 0.67) list.push(['hide']);
    else if (x < 0.75) list.push(['show']);
    else if (x < 0.78) { const y = r(); list.push(['hash', pick(hashes) + (y < 0.75 ? '&n=NOW' : y < 0.9 ? '&n=OLD' : '')]); }
    else if (x < 0.80) list.push(['back']);
    else if (x < 0.83) list.push(['offline']);
    else if (x < 0.88) list.push(cfg.clock && r() < 0.5 ? ['lid', [pick([3000, 25000, 90000, 7 * 60000]), r() < 0.5]] : ['online']);
    else if (x < 0.90) list.push(['reload']);
    else list.push(['wait', 100 + Math.floor(r() * 600)]);
  }
  list.push(['show'], ['online'], ['wait', 1500]);
  return { name: `원숭이#${seed}`, cfg, steps: list, ...vp };
}

const browser = await chromium.launch();
const t0 = Date.now();
let total = 0, failed = 0;
const report = [];
const only = args.only ? new RegExp(args.only) : null;
if (!args.random || args.scripted) {
  for (const sc of scripted) {
    if (only && !only.test(sc.name)) continue;
    const vps = args.allvp ? viewports : [viewports[0]];
    for (const vp of vps) {
      const label = `${sc.name}@${vp.tag}`;
      let fails = await runScenario(browser, { ...vp, ...sc }, label);
      // --retry=1: 공유 서버(CI)에서 시간이 밀려 실패했을 수 있으니 실패한 시나리오만 한 번 더
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
  if (fails.length) { failed++; report.push({ label, cfg: sc.cfg, fails }); console.log('FAIL', label, JSON.stringify(sc.cfg)); fails.slice(0, 3).forEach(f => console.log('   step', f.step, f.msg, f.log ? '\n      ' + f.log.slice(-6).join('\n      ') : '')); }
  else if (k % 10 === 9) console.log(`..${k + 1}/${nRand} 원숭이 통과`);
}
await browser.close();
fs.writeFileSync(args.report || path.join(SHOTS, 'report.json'), JSON.stringify(report, null, 1));
console.log(`\n${total - failed}/${total} 통과 (${((Date.now() - t0) / 1000).toFixed(0)}초)`);
process.exit(failed ? 1 : 0);
