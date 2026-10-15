// 가짜 유튜브 IFrame API — 실제 플레이어에서 잰 동작을 그대로 흉내 내고, 유튜브 규칙 위반을 기록한다.
//  · 자동 재생(autoplay=1)은 cfg.autoplay 일 때만 소리 재생, 아니면 -1 에 머묾 (실제: 막히면 -1 만)
//  · API 로 재생(playVideo 등)은 사용자가 페이지를 한 번이라도 눌렀으면 됨 (실제: 우리 ▶ 뒤 playVideo 로 재생됨)
//  · 재생목록 중간 곡이 끝나면 '끝남(0)' 없이 3(다음 번호) → -1 → 3 → 1 (실제 관찰), 마지막 곡만 0
//  · 마지막 곡에서 nextVideo → 그 곡을 처음부터 (실제 관찰)
//  · 오류 곡: -1 → 3 → onError(code) → -1 (실제 관찰), 저절로 다음 곡으로 안 넘어감
//  · 믹스(list=RD…): cfg.noMix 에 있으면 목록 없음(null), cfg.mixError 면 준비되자마자 onError
//  · 우리가 준 목록(playlist=a,b,c): videoId 는 무시하고 그 목록만(cfg.goneIds 는 빠짐) · 재생목록(list=PL…, listType=playlist):
//    cfg.playlists[목록] 또는 만든 목록, videoId 가 있으면 그 자리부터 (모두 실제 플레이어에서 2026-10 관찰)
//  · 불러오는 중(-1·3)의 pauseVideo: -1 을 알리고 불러오기를 그만둠 (실제: 바로 멈추면 -1 에 머묾).
//    cfg.pauseLate 면 -1 을 알리고도 계속 불러와 재생까지 감 (실제 관찰: 멈춤 뒤에도 3 → 계속 불러옴)
//  · 알림(상태·오류)은 실제처럼 비동기(postMessage)로, 페이지가 읽는 값(getPlayerState·번호·목록·주소)도 알림이 갈 때 바뀜.
//    cfg.syncEvents 면 예전처럼 바로
//  · 소리 자동 재생이 막히면 onAutoplayBlocked (실제 관찰), cfg.lateIndex: 다음 곡으로 넘어갈 때 첫 3 은 앞 번호로 알림,
//    cfg.cueStall: cuePlaylist 뒤 대기(5)가 안 옴 (인터넷 끊김 등)
//  · 스크립트로 시작(playVideo 등)이 막히면 -1 + onAutoplayBlocked (실제 관찰: autoplay=0 + 준비 때 playVideo → 막힘 알림)
//  · __fakeYT.freeze()/thaw(): 화면이 꺼져 브라우저가 멈춘 동안 플레이어(iframe)도 멈춤 — 그 사이 할 일은 깨어난 뒤에
(function () {
  'use strict';
  var cfg = window.__ytcfg || {};
  var S = { UNSTARTED: -1, ENDED: 0, PLAYING: 1, PAUSED: 2, BUFFERING: 3, CUED: 5 };
  var reg = window.__fakeYT = window.__fakeYT || { players: [], violations: [], soft: [], starts: [], calls: [], log: [], tapped: false };
  // 멈춤(화면 꺼짐으로 얼어 있음): 그동안 오는 플레이어 일은 모아 두었다가 풀릴 때 차례대로
  var frozenQueue = [];
  reg.frozen = false;
  reg.freeze = function () { reg.frozen = true; };
  reg.thaw = function () { reg.frozen = false; var q = frozenQueue; frozenQueue = []; q.forEach(function (f) { setTimeout(f, 0); }); };
  var speed = cfg.speed || 1;          // 재생 시간 배속 (곡 길이를 짧게)
  var bufferMs = cfg.bufferMs || 350;  // 다음 곡을 불러오는 시간
  var T0 = Date.now();

  // 사람이 페이지를 실제로 눌렀는지 (playwright 의 tap/click 은 isTrusted)
  ['pointerdown', 'touchstart', 'click', 'keydown'].forEach(function (t) {
    window.addEventListener(t, function (e) { if (e.isTrusted) reg.tapped = true; }, true);
  });

  function L(s) { reg.log.push(((Date.now() - T0) / 1000).toFixed(2) + ' ' + s); if (reg.log.length > 4000) reg.log.shift(); }

  // 플레이어 상자가 화면에 실제로 보이는 비율 — 상자 안 7×7 점마다 그 자리 맨 위가 플레이어인지 (화면 밖·겹침은 안 보임).
  // 페이지(playerVisible)와 다른 방법으로 재야 페이지 계산이 틀렸을 때 잡힌다. 숨김이면 0
  function visFrac(el) {
    if (document.visibilityState !== 'visible' || !el || !el.isConnected) return 0;
    var b = el.getBoundingClientRect();
    if (!b.width || !b.height) return 0;
    var hit = 0, n = 0;
    for (var i = 0; i < 7; i++) {
      for (var j = 0; j < 7; j++) {
        n++;
        var x = b.left + b.width * (i + 0.5) / 7, y = b.top + b.height * (j + 0.5) / 7;
        if (x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) continue;
        if (document.elementFromPoint(x, y) === el) hit++;
      }
    }
    return hit / n;
  }

  function pad(n, k) { n = String(n); while (n.length < k) n = '0' + n; return n; }
  // 씨앗 곡마다 다른 믹스 (첫 믹스만 dQw4wx0000N — 시나리오가 이 번호로 오류를 건다)
  var CH = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  function tag(seed) { var h = 7; for (var i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) >>> 0; var t = ''; for (var k = 0; k < 4; k++) { t += CH[h % CH.length]; h = Math.floor(h / CH.length) + 13 * k; } return t; }
  function mixFor(seed) {
    if (cfg.noMix && cfg.noMix.indexOf(seed) >= 0) return null;
    var len = cfg.mixLen || 8;
    var base = seed === 'dQw4w9WgXcQ' ? 'dQw4w' : 'm' + tag(seed);
    var a = [seed];
    for (var i = 1; i < len; i++) a.push(base + 'x' + pad(i, 5));
    return a;
  }
  // 재생목록마다 정해진 동영상들 (시나리오가 cfg.playlists 로 정하지 않았으면)
  function playlistFor(id) {
    var a = [];
    for (var i = 0; i < (cfg.listLen || 10); i++) a.push('p' + tag(id) + 'x' + pad(i, 5));
    return a;
  }
  function durationOf(id) { return (cfg.durations && cfg.durations[id] != null) ? cfg.durations[id] : (cfg.duration || 6); }
  function errorOf(id) { return cfg.errors ? cfg.errors[id] || (cfg.errorAll ? cfg.errorAll : 0) : (cfg.errorAll || 0); }

  function Player(id, opts) {
    var host = typeof id === 'string' ? document.getElementById(id) : id;
    var elId = host.id;
    var f = document.createElement('iframe');
    f.id = elId;
    f.width = String(opts.width || 640);
    f.height = String(opts.height || 360);
    f.setAttribute('allow', 'autoplay; encrypted-media');
    f.setAttribute('title', 'YouTube video player');
    f.srcdoc = '<body style="margin:0;background:#202020;color:#fff;font:12px sans-serif;display:grid;place-items:center;height:100vh">' +
      '<button id=b style="font-size:22px;padding:8px 18px;background:#f00;color:#fff;border:0;border-radius:12px">▶</button></body>';
    host.replaceWith(f);
    this.el = f;
    this.elId = elId;
    // 유튜브 규칙: 플레이어 칸은 200×200 이상 (만들 때·setSize 때 모두)
    this.size = [+f.width, +f.height];
    if (!(this.size[0] >= 200 && this.size[1] >= 200)) reg.violations.push({ kind: 'player-too-small', w: this.size[0], h: this.size[1] });
    this.opts = opts;
    this.vars = opts.playerVars || {};
    this.events = opts.events || {};
    this.state = S.UNSTARTED;
    this.time = 0;
    this.vol = 100;
    this.muted = !!this.vars.mute;
    this.destroyed = false;
    this.single = opts.videoId || null;
    this.list = null;
    this.idx = 0;
    this.loaded = false;
    this.timers = [];
    this.seg = 0;   // 재생 구간 번호 (멈췄다 다시 틀면 새 구간)
    this.tok = 0;   // 불러오기 차례 (새로 불러오면 앞의 예약은 버림)
    this.n = reg.players.length;
    this.rep = null;
    var listId = this.vars.list ? String(this.vars.list) : '';
    if (this.vars.playlist) {
      // 우리가 준 목록(playlist 변수): 실제(2026-10 관찰)는 videoId 를 무시하고 이 목록만, 없는·못 트는 번호는 뺀다
      var gone = cfg.goneIds || [];
      this.list = String(this.vars.playlist).split(',').filter(function (x) { return x && gone.indexOf(x) < 0; });
      if (!this.list.length) this.list = null;
    } else if (listId && !/^RD/.test(listId) && this.vars.listType === 'playlist') {
      // 재생목록(list=PL…·UU…): videoId 가 목록에 있으면 그 자리부터 (실제 관찰: UU… + 셋째 곡 → idx 2)
      this.list = cfg.playlists && cfg.playlists[listId] ? cfg.playlists[listId].slice() : playlistFor(listId);
      var at = this.single ? this.list.indexOf(this.single) : -1;
      if (at >= 0) this.idx = at;
      else if (this.single) this.list.unshift(this.single);
    } else if (listId) {
      if (cfg.mixError) this.mixErr = cfg.mixError;
      else this.list = mixFor(listId.replace(/^RD/, ''));
      if (this.list && this.single && this.list[0] !== this.single) this.list.unshift(this.single);
    }
    this.rep = this._snap();
    reg.players.push(this);
    var self = this;
    f.addEventListener('load', function () {
      try {
        f.contentDocument.getElementById('b').addEventListener('click', function (e) {
          if (!e.isTrusted) return;
          reg.tapped = true;
          self._userPlay();
        });
      } catch (e) {}
    });
    this._later(cfg.readyMs || 120, function () {
      if (cfg.neverReady) return;
      L('p' + self.n + ' ready');
      self.readyFired = true;
      self.rep = self._snap();
      self._fire('onReady', { target: self });
      if (self.mixErr) { self._later(50, function () { self._error(self.mixErr); }); return; }
      if (self.vars.autoplay) self._start('autoplay');
    });
  }
  var P = Player.prototype;

  P._later = function (ms, fn) {
    var self = this;
    var t = setTimeout(function run() {
      if (self.destroyed) return;
      if (reg.frozen) { frozenQueue.push(run); return; }
      fn();
    }, ms);
    this.timers.push(t);
    return t;
  };
  P._fire = function (name, ev) {
    var h = this.events[name];
    if (typeof h === 'function') {
      try { h(ev); } catch (e) { reg.violations.push({ kind: 'handler-threw', name: name, msg: String(e && e.message || e) }); throw e; }
    }
  };
  // 지금 상태를 페이지 쪽 '보고된 값'으로 찍어 둔다
  P._snap = function () { return { state: this.state, idx: this.list ? this.idx : -1, list: this.list ? this.list.slice() : null, id: this.curId() }; };
  // 실제 API 는 iframe 이 postMessage 로 알린다 → 다음 차례에, 보낸 순서대로. 받는 순간 페이지가 읽는 값도 바뀐다
  P._emit = function (name, ev) {
    var self = this, snap = this._snap();
    var go = function run() {
      if (self.destroyed) return;
      if (reg.frozen) { frozenQueue.push(run); return; }
      self.rep = snap;
      self._fire(name, ev);
    };
    if (cfg.syncEvents) go(); else setTimeout(go, 0);
  };
  P._setState = function (s) {
    if (this.destroyed) return;
    var prev = this.state;
    this.state = s;
    L('p' + this.n + ' s' + s + ' idx=' + (this.list ? this.idx : -1) + ' id=' + this.curId());
    if (s === S.PLAYING && prev !== S.PLAYING) { this.seg++; this._checkStart('play-state'); }
    this._emit('onStateChange', { target: this, data: s });
  };
  P.curId = function () { return this.list ? this.list[this.idx] : this.single; };

  // 규칙 검사: 재생이 시작되는 순간 플레이어가 절반 넘게 보여야 한다.
  // 시작 순간 안 보였더라도 페이지가 0.3초 안에 멈추면(보이게 한 뒤 다시 틀려고) 위반이 아니다.
  P._checkStart = function (why) {
    var f = visFrac(this.el);
    var rec = { why: why, frac: +f.toFixed(2), hidden: document.visibilityState !== 'visible', id: this.curId(), t: Date.now() - T0 };
    reg.starts.push(rec);
    if (f >= 0.5) { if (f < 0.9) reg.soft.push(rec); return; }
    var self = this, mySeg = this.seg;
    setTimeout(function () {
      if (self.destroyed) return;
      // 같은 재생 구간이 0.3초 넘게 이어지면 위반 (페이지가 바로 멈췄다가 보이게 한 뒤 다시 튼 건 괜찮다)
      if (self.seg === mySeg && self.state === S.PLAYING) reg.violations.push({ kind: 'started-while-not-visible', detail: rec });
    }, 300);
  };

  P._allowed = function (kind) {
    if (cfg.autoplay) return true;
    if (kind === 'autoplay') return false;
    return !!reg.tapped;
  };
  P._start = function (kind) {
    if (!this._allowed(kind)) {
      L('p' + this.n + ' blocked ' + kind);
      this._setState(S.UNSTARTED);
      this._emit('onAutoplayBlocked', { target: this });   // 실제: 자동 재생이 막히면 바로 옴
      return;
    }
    this._load();
  };
  P._userPlay = function () {
    if (this.state === S.PAUSED) { this._resume(); return; }
    if (this.state === S.ENDED) this.time = 0;
    this._load();
  };
  P._resume = function () {
    var self = this, tok = ++this.tok;
    this._later(60, function () { if (self.tok !== tok) return; self._setState(S.PLAYING); self._tick(); });
  };
  P._load = function () {
    var self = this, tok = ++this.tok;
    this._stopTick();
    this.time = 0;
    this.loaded = false;
    var id = this.curId();
    this._setState(S.UNSTARTED);
    this._later(40, function () {
      if (self.tok !== tok) return;
      self._setState(S.BUFFERING);
      self._later(bufferMs, function () {
        if (self.tok !== tok) return;
        var code = errorOf(id);
        if (code) { self._error(code); return; }
        if (cfg.offline) { L('p' + self.n + ' offline-stall'); return; }   // 인터넷이 끊기면 계속 3
        self.loaded = true;
        self._setState(S.PLAYING);
        self._tick();
      });
    });
  };
  P._error = function (code) {
    reg.errors = (reg.errors || 0) + 1;
    L('p' + this.n + ' error ' + code + ' id=' + this.curId());
    this._emit('onError', { target: this, data: code });
    this._setState(S.UNSTARTED);
  };
  P._tick = function () {
    var self = this;
    this._stopTick();
    this.tickTimer = setInterval(function () {
      if (self.destroyed || self.state !== S.PLAYING || reg.frozen) return;
      self.time += 0.1 * speed;
      var d = durationOf(self.curId());
      if (d > 0 && self.time >= d) {
        self.time = d;
        self._stopTick();
        self._ended();
      }
    }, 100);
  };
  P._stopTick = function () { if (this.tickTimer) { clearInterval(this.tickTimer); this.tickTimer = 0; } };
  P._ended = function () {
    var self = this, tok = ++this.tok;
    if (this.list && this.idx < this.list.length - 1) {
      // 실제: 끝남 없이 바로 다음 번호로 3 → -1 → 3 → 1 (lateIndex: 첫 3 은 앞 번호로)
      this.time = 0;
      this.loaded = false;
      if (cfg.lateIndex) { this._setState(S.BUFFERING); this.idx++; } else { this.idx++; this._setState(S.BUFFERING); }
      this._later(40, function () {
        if (self.tok !== tok) return;
        self._setState(S.UNSTARTED);
        self._later(40, function () {
          if (self.tok !== tok) return;
          self._setState(S.BUFFERING);
          self._later(bufferMs, function () {
            if (self.tok !== tok) return;
            var code = errorOf(self.curId());
            if (code) { self._error(code); return; }
            if (cfg.offline) return;
            self.loaded = true;
            self._setState(S.PLAYING);
            self._tick();
          });
        });
      });
      return;
    }
    this._setState(S.ENDED);
  };

  function call(p, name, args) {
    var f = visFrac(p.el);
    reg.calls.push({ name: name, frac: +f.toFixed(2), hidden: document.visibilityState !== 'visible', t: Date.now() - T0 });
    L('p' + p.n + ' call ' + name + '(' + Array.prototype.join.call(args, ',') + ') vis=' + f.toFixed(2));
    if (p.destroyed) reg.violations.push({ kind: 'call-after-destroy', name: name });
  }
  // 재생을 시작하는 호출: 플레이어가 절반 넘게 보일 때만 (유튜브 규칙 — 스크립트로 시작하는 재생)
  function startCall(p, name, args) {
    call(p, name, args);
    var f = visFrac(p.el);
    if (f < 0.5) reg.violations.push({ kind: 'scripted-start-while-not-visible', name: name, frac: +f.toFixed(2), hidden: document.visibilityState !== 'visible' });
  }

  P._blocked = function (name) {
    L('p' + this.n + ' ' + name + ' blocked');
    if (this.state !== S.UNSTARTED) this._setState(S.UNSTARTED);
    this._emit('onAutoplayBlocked', { target: this });
  };
  P.playVideo = function () {
    startCall(this, 'playVideo', arguments);
    if (!this._allowed('api')) { this._blocked('playVideo'); return; }
    if (this.state === S.PLAYING || this.state === S.BUFFERING) return;
    if (this.state === S.PAUSED && this.loaded) { this._resume(); return; }
    if (this.state === S.ENDED) this.time = 0;
    this._load();
  };
  P.pauseVideo = function () {
    call(this, 'pauseVideo', arguments);
    if (this.state === S.UNSTARTED || this.state === S.BUFFERING) {
      if (cfg.pauseLate && this.state === S.BUFFERING) { L('p' + this.n + ' pause missed while loading'); this._setState(S.UNSTARTED); return; }   // 계속 불러옴
      this.tok++;   // 불러오기를 그만두고 -1 에 머묾 ('멈춤' 알림 없음)
      this._stopTick();
      if (this.state !== S.UNSTARTED) this._setState(S.UNSTARTED);
      return;
    }
    if (this.state === S.PLAYING) { this.tok++; this._stopTick(); this._setState(S.PAUSED); }
  };
  P.stopVideo = function () { call(this, 'stopVideo', arguments); this._stopTick(); this._setState(S.CUED); };
  P.nextVideo = function () {
    startCall(this, 'nextVideo', arguments);
    if (!this.list) return;
    if (this.idx < this.list.length - 1) this.idx++;   // 마지막이면 그 곡을 처음부터 (실제)
    if (this.state === S.PLAYING) this._setState(S.PAUSED);
    if (this._allowed('api')) this._load(); else this._blocked('nextVideo');
  };
  P.previousVideo = function () {
    startCall(this, 'previousVideo', arguments);
    if (this.list && this.idx > 0) this.idx--;
    if (this.state === S.PLAYING) this._setState(S.PAUSED);
    if (this._allowed('api')) this._load(); else this._blocked('previousVideo');
  };
  P.playVideoAt = function (i) {
    startCall(this, 'playVideoAt', arguments);
    if (!this.list || i < 0 || i >= this.list.length) return;
    if (this.state === S.PLAYING) this._setState(S.PAUSED);
    this.idx = i;
    if (this._allowed('api')) this._load(); else this._blocked('playVideoAt');
  };
  P.seekTo = function (s) {
    call(this, 'seekTo', arguments);
    var d = durationOf(this.curId());
    this.time = Math.max(0, Math.min(d, +s || 0));
    if (this.state === S.ENDED) { /* 실제처럼 끝난 뒤엔 그대로 */ }
  };
  // 이미 있는 플레이어에 목록 넣기 — 실제로는 고른 곡이 맨 앞이 아니다 (여기선 둘째에 둔다)
  P._setList = function (a, index) {
    this.tok++;
    if (a && typeof a === 'object' && !Array.isArray(a)) { index = a.index; a = a.list; }
    this.mixErr = 0;
    if (Array.isArray(a)) this.list = a.slice();
    else if (typeof a === 'string') {
      if (cfg.mixError) { this.list = null; this.mixErr = cfg.mixError; }
      else {
        var m = mixFor(a.replace(/^RD/, ''));
        if (m && m.length > 2) { var seed = m.shift(); m.splice(1, 0, seed); }
        this.list = m;
      }
    }
    this.idx = this.list ? Math.max(0, Math.min(this.list.length - 1, index || 0)) : 0;
    this._stopTick();
    this.loaded = false;
    this.time = 0;
  };
  P.loadPlaylist = function (a, index) {
    startCall(this, 'loadPlaylist', arguments);
    this._setList(a, index);
    if (this.mixErr) { var self = this; this._later(50, function () { self._error(self.mixErr); }); return; }
    if (this._allowed('api')) this._load(); else this._setState(S.UNSTARTED);
  };
  P.cuePlaylist = function (a, index) {
    call(this, 'cuePlaylist', arguments);
    if (this.state === S.PLAYING) this._setState(S.PAUSED);
    this._setList(a, index);
    var self = this;
    if (this.mixErr) { this._later(50, function () { self._error(self.mixErr); }); return; }
    if (cfg.cueStall || cfg.offline) { L('p' + this.n + ' cue stalled'); return; }
    this._later(80, function () { self._setState(S.UNSTARTED); self._setState(S.CUED); });
  };
  P.loadVideoById = function (id) {
    startCall(this, 'loadVideoById', arguments);
    this.list = null; this.single = typeof id === 'object' ? id.videoId : id; this.idx = 0;
    if (this._allowed('api')) this._load(); else this._setState(S.UNSTARTED);
  };
  P.cueVideoById = function (id) {
    call(this, 'cueVideoById', arguments);
    this.tok++;
    this._stopTick();
    this.list = null; this.single = typeof id === 'object' ? id.videoId : id; this.idx = 0; this.loaded = false; this.time = 0;
    var self = this;
    this._later(80, function () { self._setState(S.CUED); });
  };
  P.getCurrentTime = function () { return this.time; };
  P.getDuration = function () { return this.loaded || this.state === S.PLAYING || this.state === S.PAUSED || this.state === S.ENDED ? durationOf(this.curId()) : 0; };
  // 페이지가 읽는 값: 마지막으로 받은 알림 때의 값 (실제 API 처럼 iframe 의 진짜 상태보다 조금 늦음)
  P.getPlayerState = function () { return this.rep.state; };
  P.getPlaylist = function () { return this.rep.list ? this.rep.list.slice() : null; };
  P.getPlaylistIndex = function () { return this.rep.idx; };
  P.getVideoUrl = function () { var id = this.rep.id; return id ? 'https://www.youtube.com/watch?v=' + id : 'https://www.youtube.com/watch'; };
  P.setVolume = function (v) { call(this, 'setVolume', arguments); this.vol = Math.max(0, Math.min(100, Math.round(+v))); };
  P.getVolume = function () { return this.vol; };
  P.mute = function () { call(this, 'mute', arguments); this.muted = true; };
  P.unMute = function () { call(this, 'unMute', arguments); this.muted = false; };
  P.isMuted = function () { return this.muted; };
  P.setLoop = function (b) { call(this, 'setLoop', arguments); this.loop = !!b; };
  P.setShuffle = function (b) { call(this, 'setShuffle', arguments); };
  P.getIframe = function () { return this.el; };
  // 문서에 있는 setSize: 플레이어 칸(iframe) 크기를 바꾼다 — 200×200 보다 작으면 규칙 위반
  P.setSize = function (w, h) {
    call(this, 'setSize', arguments);
    w = +w; h = +h;
    if (!(w >= 200 && h >= 200)) reg.violations.push({ kind: 'player-too-small', w: w, h: h });
    this.el.width = String(w);
    this.el.height = String(h);
    this.size = [w, h];
    reg.sizes = (reg.sizes || []).concat([[w, h]]);
    return this;
  };
  P.destroy = function () {
    call(this, 'destroy', arguments);
    this.destroyed = true;
    this._stopTick();
    this.timers.forEach(clearTimeout);
    var d = document.createElement('div');
    d.id = this.elId;
    if (this.el.isConnected) this.el.replaceWith(d);
  };

  // 페이지가 쓰는 함수가 문서에 있는 것만인지 — 문서에 없는 이름을 부르면 바로 TypeError 가 나도록 일부러 만들지 않는다
  window.YT = { Player: Player, PlayerState: S, loaded: 1 };
  setTimeout(function run() {
    if (cfg.apiNeverReady) return;
    if (reg.frozen) { frozenQueue.push(run); return; }   // 얼어 있으면 깨어난 뒤에
    if (typeof window.onYouTubeIframeAPIReady === 'function') window.onYouTubeIframeAPIReady();
  }, cfg.apiMs || 60);
}());
