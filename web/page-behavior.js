// — latest-release sync: version labels + direct-download links follow the newest GitHub release —
(() => {
  const apply = (tag) => {
    if (!/^v?\d+\.\d+/.test(tag)) return;
    const ver = tag.replace(/^v/, '');
    document.querySelectorAll('[data-ver]').forEach(el => { el.textContent = 'v' + ver; });
    document.querySelectorAll('a[data-asset]').forEach(a => {
      a.href = 'https://github.com/chardonnay/korTTY/releases/download/' + tag + '/' +
        a.dataset.asset.split('{v}').join(ver);
    });
  };
  const KEY = 'kortty-latest-tag';
  try { const c = JSON.parse(localStorage.getItem(KEY) || 'null'); if (c && c.tag) apply(c.tag); } catch (_) {}
  fetch('https://api.github.com/repos/chardonnay/korTTY/releases/latest')
    .then(r => r.ok ? r.json() : null)
    .then(rel => {
      if (!rel || !rel.tag_name) return; // API down/rate-limited: hardcoded links stay
      apply(rel.tag_name);
      try { localStorage.setItem(KEY, JSON.stringify({ tag: rel.tag_name, t: Date.now() })); } catch (_) {}
    }).catch(() => {});
})();

// korTTY page behavior — progress bar, scrollspy, reveals, animated mockups, matrix rain.
// — matrix rain: sparse, dim, accent-tinted; skipped under reduced motion or hidden tab —
(() => {
  const cv = document.getElementById('rain');
  if (!cv) return; // deliberately not gated on prefers-reduced-motion — explicitly requested effect
  const cx = cv.getContext('2d', { alpha: true });
  const glyphs = '01{}[]<>$#%&*+=;:~^|/\\λπΣΔψ';
  const FS = 14, SPEED = 90; // px/s fall speed
  let cols = [], W = 0, H = 0;
  function size() {
    // cap the backing store: 1x DPR, bounded area — a rain layer needs no retina
    W = cv.width = Math.min(innerWidth, 2560); H = cv.height = Math.min(innerHeight, 1600);
    const n = Math.min(72, Math.max(1, Math.floor(W / (FS * 2.4)))); // sparse, but spread over the FULL width
    const gap = W / n; // even spacing across the whole window, whatever its size
    cols = Array.from({length: n}, (_, i) => ({
      x: (i + 0.5) * gap + (Math.random() - 0.5) * gap * 0.4, y: Math.random() * -H, v: (0.5 + Math.random()) * SPEED
    }));
    cx.font = FS + 'px "SFMono-Regular", Consolas, monospace';
  }
  size();
  let rsz; addEventListener('resize', () => { clearTimeout(rsz); rsz = setTimeout(size, 150); });
  let last = 0, running = false, slow = 0;
  function loop(t) {
    if (document.hidden) { running = false; return; }
    const t0 = performance.now();
    if (!last) last = t;
    const dt = Math.min((t - last) / 1000, 0.1); last = t;
    cx.clearRect(0, 0, W, H);
    for (const c of cols) {
      c.y += c.v * dt;
      if (c.y - FS * 14 > H) { c.y = Math.random() * -0.5 * H; c.v = (0.5 + Math.random()) * SPEED; }
      for (let k = 0; k < 14; k++) {
        const gy = c.y - k * FS;
        if (gy < -FS || gy > H + FS) continue;
        const a = (k === 0 ? 0.42 : 0.3 * (1 - k / 14));
        cx.fillStyle = 'rgba(145, 132, 217, ' + a.toFixed(3) + ')';
        const gi = Math.abs((c.x * 7 + Math.floor(gy / FS) * 13) | 0) % glyphs.length;
        cx.fillText(glyphs[gi], c.x, gy);
      }
    }
    // watchdog: if drawing itself is consistently slow, retire the effect
    if (performance.now() - t0 > 12) { if (++slow > 30) { cv.remove(); running = false; return; } } else if (slow) slow--;
    requestAnimationFrame(loop);
  }
  function start() { if (running) return; running = true; last = 0; requestAnimationFrame(loop); }
  document.addEventListener('visibilitychange', () => { if (!document.hidden) start(); });
  start();
})();

// Every mockup animation is visibility-gated: it runs only while its element
// is in the viewport and tears its timers down when it leaves.
(() => {
  document.documentElement.classList.add('js');
  const RM = matchMedia('(prefers-reduced-motion: reduce)').matches;
  // — progress bar (rAF-batched) —
  const bar = document.getElementById('progress');
  let ticking = false;
  const onScroll = () => { if (ticking) return; ticking = true;
    requestAnimationFrame(() => { const h = document.documentElement;
      bar.style.width = (h.scrollTop / Math.max(1, h.scrollHeight - h.clientHeight) * 100) + '%'; ticking = false; }); };
  addEventListener('scroll', onScroll, { passive: true }); onScroll();
  // — scrollspy —
  const links = [...document.querySelectorAll('.nav a[href^="#"]')];
  const byId = {}; links.forEach(a => { byId[a.getAttribute('href').slice(1)] = a; });
  const spy = new IntersectionObserver(es => es.forEach(e => { if (!e.isIntersecting) return;
    const a = byId[e.target.id]; if (!a) return;
    links.forEach(l => l.removeAttribute('aria-current')); a.setAttribute('aria-current', 'location');
  }), { rootMargin: '-30% 0px -60% 0px' });
  document.querySelectorAll('section[id]').forEach(s => spy.observe(s));
  // — reveal on scroll —
  const revealables = document.querySelectorAll('.sec, .stage, .stats');
  if (!RM) {
    revealables.forEach(s => s.classList.add('reveal'));
    const io = new IntersectionObserver(es => es.forEach(e => {
      if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); } }), { threshold: 0.08 });
    revealables.forEach(s => io.observe(s));
  }
  document.querySelectorAll('figure.reveal').forEach(f => { if (RM) f.classList.remove('reveal'); else f.classList.add('in'); });

  // — helpers: timer registry + visibility gate —
  const timers = () => { const set = new Set(); return {
    iv: (fn, ms) => { const t = setInterval(fn, ms); set.add(t); return t; },
    to: (fn, ms) => { const t = setTimeout(fn, ms); set.add(t); return t; },
    clear: () => { set.forEach(t => { clearInterval(t); clearTimeout(t); }); set.clear(); } }; };
  const gate = (el, start, stop) => { if (!el) return;
    if (!('IntersectionObserver' in window)) { start(); return; }
    let on = false;
    new IntersectionObserver(es => es.forEach(e => {
      if (e.isIntersecting && !on) { on = true; start(); }
      else if (!e.isIntersecting && on) { on = false; stop(); }
    }), { threshold: 0.05 }).observe(el); };
  window.__kgate = gate; window.__ktimers = timers;

  // — screenshot Ken-Burns/cursor: run only while in viewport (GPU relief) —
  if ('IntersectionObserver' in window) {
    const shotIO = new IntersectionObserver(es => es.forEach(e =>
      e.target.classList.toggle('play', e.isIntersecting)), { threshold: 0.05 });
    document.querySelectorAll('.shot-live').forEach(s => shotIO.observe(s));
  } else document.querySelectorAll('.shot-live').forEach(s => s.classList.add('play'));

  // — hero agent typewriter —
  const L = [
    { c: 'ln', h: '<span class="t-host">ops@prod-web-01</span><span class="t-run">:~$</span> <span class="t-cmd" data-type="agent /var is at 94% — find out why and fix it"></span>' },
    { c: 'ln t-agent', h: '🤖 AI Agent · local/qwen3-4b · cwd: /home/ops', d: 600 },
    { c: 'ln t-run', h: '→ df -h /var', d: 700 },
    { c: 'ln t-run', h: '→ du -sh /var/log/* | sort -h | tail -3', d: 800 },
    { c: 'ln t-think', h: '💭 journald has no size cap — vacuum now, then persist a 200M limit', d: 900 },
    { c: 'ln t-run', h: '→ sudo journalctl --vacuum-size=200M <span class="t-ok">[approved]</span>', d: 900 },
    { c: 'ln t-ok', h: '✓ /var: 94% → 41%. Root cause: unbounded journald retention.', d: 1000 },
    { c: 'ln', h: '<span class="t-host">ops@prod-web-01</span><span class="t-run">:~$</span> <span class="caret"></span>', d: 700 },
  ];
  const demo = document.getElementById('demo');
  const staticDemo = () => L.map(l => '<span class="' + l.c + '">' + l.h.replace(/data-type="([^"]*)"><\/span>/, '">$1</span>') + '</span>').join('');
  if (demo) {
    { const T = timers();
      const step = (i) => {
        if (i >= L.length) { T.to(() => { demo.innerHTML = ''; step(0); }, 4200); return; }
        const l = L[i]; const s = document.createElement('span'); s.className = l.c; s.innerHTML = l.h; demo.appendChild(s);
        const t = s.querySelector('[data-type]');
        if (t) { const txt = t.getAttribute('data-type'); let k = 0;
          const cur = document.createElement('span'); cur.className = 'caret'; t.after(cur);
          const iv = T.iv(() => { t.textContent = txt.slice(0, ++k);
            if (k >= txt.length) { clearInterval(iv); cur.remove(); T.to(() => step(i + 1), 500); } }, 34);
        } else T.to(() => step(i + 1), l.d || 600);
      };
      gate(demo, () => { demo.innerHTML = ''; step(0); }, () => { T.clear(); demo.innerHTML = staticDemo(); });
    }
  }

  // — swarm orbs —
  const orbBox = document.getElementById('orbs');
  if (orbBox) {
    orbBox.innerHTML = '';
    const N = 26, orbs = [];
    for (let i = 0; i < N; i++) { const o = document.createElement('span'); o.className = 'orb queued'; orbBox.appendChild(o); orbs.push({ el: o, st: 'queued' }); }
    const lg = { run: document.getElementById('lg-run'), slow: document.getElementById('lg-slow'), done: document.getElementById('lg-done') };
    const paint = () => { let r = 0, s = 0, d = 0;
      orbs.forEach(o => { o.el.className = 'orb ' + o.st + (o.st === 'run' ? ' run' : ''); if (o.st === 'run') r++; if (o.st === 'slow') { r++; s++; } if (o.st === 'done') d++; });
      lg.run.textContent = r; lg.slow.textContent = s; lg.done.textContent = d; };
    const seed = () => { orbs.forEach((o, i) => o.st = i % 5 === 4 ? 'slow' : (i % 3 === 0 ? 'done' : 'run')); paint(); };
    { const T = timers();
      gate(orbBox,
        () => { orbs.forEach(o => o.st = 'queued'); paint();
          T.iv(() => {
            const queued = orbs.filter(o => o.st === 'queued'), running = orbs.filter(o => o.st === 'run' || o.st === 'slow');
            if (queued.length && Math.random() < .8) queued[Math.floor(Math.random() * queued.length)].st = 'run';
            if (running.length && Math.random() < .45) running[Math.floor(Math.random() * running.length)].st = 'done';
            if (running.length && Math.random() < .12) { const o = running[Math.floor(Math.random() * running.length)]; if (o.st === 'run') o.st = 'slow'; }
            if (!queued.length && !running.length) orbs.forEach(o => o.st = 'queued');
            paint();
          }, 650); },
        () => { T.clear(); seed(); });
    }
  }

  // — broadcast panes —
  const bcCmds = document.querySelectorAll('.bc-cmd'), bcOuts = document.querySelectorAll('.bc-out');
  const bcast = document.querySelector('.bcast');
  if (bcCmds.length && bcast) {
    const CMD = 'sudo systemctl restart nginx && systemctl is-active nginx';
    const OUT = '<br /><span class="t-ok">active</span>';
    const setStatic = () => { bcCmds.forEach(c => c.textContent = CMD); bcOuts.forEach(o => o.innerHTML = OUT); };
    { const T = timers();
      const loop = () => { let k = 0;
        bcCmds.forEach(c => c.textContent = ''); bcOuts.forEach(o => o.innerHTML = '');
        const iv = T.iv(() => { k++; bcCmds.forEach(c => c.textContent = CMD.slice(0, k));
          if (k >= CMD.length) { clearInterval(iv);
            bcOuts.forEach((o, i) => T.to(() => o.innerHTML = OUT, 500 + i * 350));
            T.to(loop, 4600); } }, 42); };
      gate(bcast, loop, () => { T.clear(); setStatic(); });
    }
  }

  // — theme cycler —
  const themer = document.getElementById('themer-body');
  if (themer) {
    const themes = [
      ['Default', '#0d1117', '#d6dae2', '#56d4dd'],
      ['Matrix Terminal', '#020a04', '#25e05c', '#25e05c'],
      ['Holographic Interface', '#021018', '#7de8ff', '#22d3ee'],
      ['Klingon Tactical', '#190505', '#ff8a75', '#ff2e1f'],
      ['Elegant Dark', '#14121e', '#cfc8ee', '#9184d9'],
    ];
    const name = document.getElementById('theme-name'); const box = themer.closest('.themer'); let ti = 0;
    const apply = (t) => { box.style.setProperty('--tbg', t[1]); box.style.setProperty('--ttx', t[2]); box.style.setProperty('--tac', t[3]); name.textContent = t[0]; };
    apply(themes[0]);
    { const T = timers();
      gate(box, () => T.iv(() => { ti = (ti + 1) % themes.length; apply(themes[ti]); }, 2800), () => T.clear());
    }
  }

  // — model download card —
  const fill = document.getElementById('mc-fill');
  if (fill) {
    const bytes = document.getElementById('mc-bytes'), rate = document.getElementById('mc-rate'),
          dot = document.getElementById('mc-dot'), msg = document.getElementById('mc-msg'),
          card = fill.closest('.modelcard');
    { const T = timers();
      const run = () => { let p = 0;
        dot.style.background = '#2f81f7'; msg.textContent = 'Downloading — SHA-256 verified on completion';
        const iv = T.iv(() => { p += 0.6 + Math.random() * 1.2; if (p > 100) p = 100;
          fill.style.width = p + '%'; bytes.textContent = (p / 100 * 2.5).toFixed(1) + ' GiB of 2.5 GiB';
          const rem = Math.max(0, Math.round((100 - p) / 100 * 142));
          rate.textContent = 'Speed 18.0 MiB/s · Remaining ' + String(Math.floor(rem / 60)).padStart(2, '0') + ':' + String(rem % 60).padStart(2, '0');
          if (p >= 100) { clearInterval(iv);
            T.to(() => { dot.style.background = '#3fb950'; msg.textContent = 'llama-server running · 127.0.0.1:49213 · Metal · API key ✓'; }, 700);
            T.to(() => { dot.style.background = '#6b7484'; msg.textContent = 'Idle — model tensors unloaded'; }, 5200);
            T.to(run, 7600); } }, 90); };
      gate(card, run, () => { T.clear(); fill.style.width = '0%'; });
    }
  }

  // — jobscheduler countdown —
  const sm = document.getElementById('sched-msg');
  if (sm) {
    const sd = document.getElementById('sched-dot'), sched = sm.closest('.sched');
    { const T = timers();
      let s = 132;
      gate(sched,
        () => T.iv(() => { s--;
          if (s > 12) { sd.classList.remove('live');
            sm.textContent = 'Next: nightly-rsync in 00:' + String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0');
          } else if (s > 4) { sd.classList.add('live'); sm.textContent = 'Running: nightly-rsync — syncing web-01…03 …'; }
          else if (s > 3) { sm.textContent = 'nightly-rsync ✓ exit 0 · journaled'; sd.classList.remove('live'); }
          else if (s <= 0) s = 132;
        }, 1000),
        () => T.clear());
    }
  }

  // — coding-agents panel: states cycle, timers tick, quick keys light up while blocked —
  const capanel = document.getElementById('capanel');
  if (capanel) {
    const rows = [...capanel.querySelectorAll('.ca-row')];
    const statusEl = capanel.querySelector('.ca-status');
    const strip = document.getElementById('ca-strip');
    const EV = {
      blocked: ['Do you want to proceed? › 1. Yes', 'Allow Bash(npm test)? › 1. Yes, 2. No', 'Enter to confirm · Esc to cancel'],
      working: ['✳ Editing terraform/main.tf… (esc to interrupt)', '⎿ Running… (esc to interrupt)', '✻ Reading 24 files… (esc to interrupt)'],
      done: ['✓ 3 files changed, tests pass', '✓ Done — 2 commits staged'],
      idle: ['❯  · ? for shortcuts', '❯']
    };
    const LBL = { blocked: 'Waiting for you', working: 'Working', done: 'Done', idle: 'Idle' };
    // one scene per step: the three agents' states plus the panel's status line
    const SCENES = [
      { st: ['working', 'working', 'done'],    msg: '', hold: 5 },
      { st: ['blocked', 'working', 'idle'],    msg: 'Claude Code needs a decision — answer with y, n, Enter or Esc', hold: 7 },
      { st: ['working', 'working', 'idle'],    msg: 'Sent Enter to Claude Code', hold: 5 },
      { st: ['working', 'blocked', 'working'], msg: 'Codex needs a decision — answer with y, n, Enter or Esc', hold: 6 },
      { st: ['done', 'working', 'working'],    msg: 'Sent y to Codex', hold: 5 },
      { st: ['idle', 'done', 'blocked'],       msg: 'Gemini CLI needs a decision', hold: 6 }
    ];
    let si = 0, t = 0, secs = [134, 38, 62], ev = [0, 0, 0];
    const fmt = s => Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
    const paint = () => {
      const sc = SCENES[si];
      rows.forEach((r, i) => {
        const st = sc.st[i];
        r.className = 'ca-row ' + st + (i === 0 ? ' sel' : '');
        r.querySelector('.ca-chip').textContent = LBL[st] + (st === 'idle' ? '' : ' · ' + fmt(secs[i]));
        r.querySelector('.ca-ev').textContent = EV[st][ev[i] % EV[st].length];
        r.querySelectorAll('.ca-key[data-ans]').forEach(k => k.classList.toggle('on', st === 'blocked'));
      });
      statusEl.textContent = sc.msg;
      statusEl.classList.toggle('ok', /^Sent/.test(sc.msg));
      if (strip) {
        const c = { blocked: 0, working: 0, done: 0 };
        sc.st.forEach(s => { if (s in c) c[s]++; });
        const parts = [];
        if (c.blocked) parts.push('<span class="ca-sc wait"><span class="ca-sd"></span>✋ ' + c.blocked + '</span>');
        if (c.working) parts.push('<span class="ca-sc work">⚡ ' + c.working + '</span>');
        if (c.done) parts.push('<span class="ca-sc fin">✓ ' + c.done + '</span>');
        strip.innerHTML = parts.join('<span class="ca-sep">·</span>');
      }
    };
    { const T = timers();
      const tick = () => {
        t++; secs = secs.map(s => s + 1);
        if (t >= SCENES[si].hold) {
          const prev = SCENES[si].st; t = 0; si = (si + 1) % SCENES.length;
          SCENES[si].st.forEach((s, i) => { if (s !== prev[i]) { secs[i] = 0; ev[i]++; } });
        }
        paint();
      };
      gate(capanel, () => { si = 0; t = 0; paint(); T.iv(tick, 1000); }, () => T.clear());
    }
  }
})();

// — click-to-zoom lightbox for framed screenshots —
// The zoomed view is a live clone of the frame, so the tour (dot + callouts) keeps running in it;
// only the Ken-Burns pan is frozen so the picture itself stands still.
(() => {
  const lb = document.createElement('div');
  lb.id = 'lightbox';
  lb.innerHTML = '<figure><figcaption></figcaption></figure><button type="button" class="lb-close" aria-label="Close">\u00d7</button>';
  document.body.appendChild(lb);
  const fig = lb.querySelector('figure'), cap = lb.querySelector('figcaption');
  let open = false, lastFocus = null;
  const close = () => { lb.classList.remove('open', 'max');
    const c = fig.querySelector('.shot-live'); if (c) c.remove();
    document.documentElement.style.overflow = ''; open = false;
    if (lastFocus) lastFocus.focus({ preventScroll: true }); };
  document.querySelectorAll('.shot-live').forEach(sl => {
    sl.setAttribute('role', 'button'); sl.setAttribute('tabindex', '0');
    const src = sl.querySelector('img');
    sl.setAttribute('aria-label', 'Zoom: ' + (src ? src.alt : 'screenshot'));
    const show = () => {
      const clone = sl.cloneNode(true);
      clone.removeAttribute('role'); clone.removeAttribute('tabindex'); clone.removeAttribute('aria-label');
      clone.classList.add('play');
      fig.insertBefore(clone, cap);
      const t = sl.closest('.shot, figure'); const title = t && t.querySelector('.shot-title');
      cap.textContent = title ? title.textContent : '';
      lastFocus = sl; lb.classList.add('open'); document.documentElement.style.overflow = 'hidden'; open = true;
      lb.querySelector('.lb-close').focus({ preventScroll: true });
    };
    sl.addEventListener('click', show);
    sl.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); show(); } });
  });
  fig.addEventListener('click', e => {
    if (e.target.closest('.shot-live')) { e.stopPropagation(); lb.classList.toggle('max'); }
  });
  lb.addEventListener('click', close);
  addEventListener('keydown', e => { if (open && e.key === 'Escape') close(); });
})();

// — hero headline types itself, terminal-style, and re-types on language change —
(() => {
  const h1 = document.querySelector('.hero .display');
  if (!h1) return;
  let run = 0;

  const source = () => {
    const ghost = h1.querySelector('.h1-ghost'); // already typed once: read the ghost, not the live text
    return (ghost || h1).innerHTML;
  };

  const type = () => {
    const token = ++run;
    const src = source();
    const tmp = document.createElement('div');
    tmp.innerHTML = src;
    const lines = [...tmp.querySelectorAll('.line')];
    const segs = lines.length
      ? lines.map(l => ({ cls: l.className, text: l.textContent }))
      : [{ cls: 'line', text: tmp.textContent.trim() }];

    h1.innerHTML = '<span class="h1-stack"><span class="h1-ghost" aria-hidden="true">' + src +
      '</span><span class="h1-live"></span></span>';
    const live = h1.querySelector('.h1-live');
    // the ghost reserves room for the cursor too, so the finished line never rewraps
    const gl = h1.querySelectorAll('.h1-ghost .line');
    if (gl.length) {
      const gc = document.createElement('span');
      gc.className = 'h1-cursor';
      gc.style.animation = 'none';
      gl[gl.length - 1].appendChild(gc);
    }
    const cursor = document.createElement('span');
    cursor.className = 'h1-cursor';
    cursor.setAttribute('aria-hidden', 'true');

    let si = 0, ci = 0, span = null;
    const step = () => {
      if (token !== run) return; // superseded by a language change
      if (si >= segs.length) {
        // glue the cursor to the last word so it can't drop to its own line
        const last = live.lastElementChild;
        if (last) {
          const words = last.textContent.split(' ');
          const tail = words.pop();
          last.textContent = words.length ? words.join(' ') + ' ' : '';
          const nw = document.createElement('span');
          nw.style.whiteSpace = 'nowrap';
          nw.textContent = tail;
          nw.appendChild(cursor);
          last.appendChild(nw);
        } else live.appendChild(cursor);
        return;
      }
      if (!span) {
        span = document.createElement('span');
        span.className = segs[si].cls;
        live.appendChild(span);
      }
      const text = segs[si].text;
      span.textContent = text.slice(0, ++ci);
      span.appendChild(cursor);
      let wait = 34 + Math.random() * 26;
      if (ci >= text.length) { si++; ci = 0; span = null; wait = 330; } // beat between sentences
      else if (/[.,:!?]/.test(text[ci - 1])) wait = 240;
      setTimeout(step, wait);
    };
    setTimeout(step, 260);
  };

  type();
  document.addEventListener('kortty:lang', type);
})();

// — emulation picker: swap the explanation below the dropdown —
(() => {
  const gate = window.__kgate, timers = window.__ktimers;
  const sel = document.getElementById('emu-sel');
  if (!sel) return;
  const bodies = document.querySelectorAll('.emu-body');
  sel.addEventListener('change', () => {
    bodies.forEach(b => b.classList.toggle('on', b.dataset.emu === sel.value));
  });
  // — session isolation diagram: five scenes (flow, signing, sandbox deny, crash, reconnect) —
  const iso = document.getElementById('iso');
  if (iso) {
    const caps = [...iso.querySelectorAll('.iso-cap')];
    const rows = [...iso.querySelectorAll('.iso-row')];
    const msg = rows.map(r => r.querySelector('.iso-msg'));
    const sig = iso.querySelector('.iso-sig');
    const pid1 = document.getElementById('iso-pid1');
    const HOLD = [4500, 5200, 5000, 5500, 4200];
    let s = 0, pid = 51877, tids = [];
    const later = (fn, ms) => tids.push(setTimeout(fn, ms));
    const paint = () => {
      iso.dataset.scene = s;
      caps.forEach((c, i) => c.classList.toggle('on', i === s));
      msg[0].textContent = s === 1 ? 'auth: publickey → sign request' : 'channels: shell, sftp';
      msg[1].textContent = s === 3 ? 'Session process crashed (exit 137)' : s === 4 ? 'reconnected · new token' : 'channels: shell, -L 5432';
      msg[2].textContent = s === 2 ? 'cat ~/.ssh/id_ed25519: Operation not permitted' : 'HISTFILE, TMPDIR → own folder';
      if (s === 4) { pid += 9; pid1.textContent = 'pid ' + pid + ' · ssh'; }
      sig.className = 'iso-sig';
      if (s === 1) {
        later(() => { sig.textContent = 'sign challenge?'; sig.className = 'iso-sig out'; }, 500);
        later(() => { sig.textContent = '✓ signature'; sig.className = 'iso-sig back'; }, 2500);
        later(() => { sig.className = 'iso-sig'; }, 4600);
      }
    };
    const step = () => { paint(); later(() => { s = (s + 1) % HOLD.length; step(); }, HOLD[s]); };
    gate(iso, () => { s = 0; step(); }, () => { tids.forEach(clearTimeout); tids = []; });
  }
})();


// — app mockups: fit-to-frame zoom, tour dots on real targets, scripted states —
(() => {
  const BT = String.fromCharCode(96);
  const q = (s) => BT + s + BT;
  const roots = () => [...document.querySelectorAll('.shot-live .km')];
  const lbOpen = () => { const lb = document.getElementById('lightbox'); return lb && lb.classList.contains('open'); };
  const fixH = (km) => {
    if (km.__hfix) return;
    const z = km.style.zoom; km.style.zoom = 1; km.style.height = '';
    km.classList.add('measure'); const hh = km.offsetHeight; km.classList.remove('measure');
    km.style.height = (hh + (+km.dataset.hx || 14)) + 'px'; km.style.zoom = z; km.__hfix = 1;
  };
  const layout = (km) => {
    const sl = km.closest('.shot-live'); if (!sl) return;
    fixH(km);
    const dw = +km.dataset.dw || 900, inLb = !!sl.closest('#lightbox');
    km.style.width = inLb ? dw + 'px' : '';
    let z;
    if (inLb) {
      km.style.zoom = 1;
      z = Math.min(1.6, Math.min(innerWidth * 0.94, 1500) / dw, (innerHeight * 0.88) / km.offsetHeight);
    } else z = Math.min(1, (sl.parentElement.clientWidth || dw) / dw);
    km.style.zoom = z.toFixed(3);
    const b = sl.getBoundingClientRect(); if (!b.width) return;
    for (let i = 1; i <= 3; i++) {
      const el = km.querySelector('[data-t="' + i + '"]'); if (!el) continue;
      const r = el.getBoundingClientRect(); if (!r.width) continue;
      sl.style.setProperty('--s' + i + 'x', ((r.left - b.left + Math.min(r.width / 2, 26)) / b.width * 100).toFixed(2) + '%');
      const px = r.left - b.left + Math.min(r.width / 2, 26), py = r.top - b.top + r.height / 2;
      sl.style.setProperty('--s' + i + 'y', (py / b.height * 100).toFixed(2) + '%');
      const tip = sl.querySelector('.shot-tip.t' + i); if (!tip) continue;
      const off = inLb ? 22 : 14, w = tip.offsetWidth, hgt = tip.offsetHeight;
      const left = px + off + w > b.width - 6 && px - off - w > 6;
      const up = py + off + hgt > b.height - 6 && py - off - hgt > 6;
      tip.classList.remove('l', 'u', 'lu');
      if (left && up) tip.classList.add('lu'); else if (left) tip.classList.add('l'); else if (up) tip.classList.add('u');
    }
  };
  const layoutAll = () => roots().forEach(layout);
  let rz; addEventListener('resize', () => { clearTimeout(rz); rz = setTimeout(layoutAll, 120); });
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => { roots().forEach(k => { k.__hfix = 0; }); layoutAll(); });
  addEventListener('load', layoutAll);
  layoutAll();
  const lbw = () => { const lb = document.getElementById('lightbox'); if (!lb) return setTimeout(lbw, 300);
    new MutationObserver(() => { const k = lb.querySelector('.km'); if (k) requestAnimationFrame(() => layout(k)); })
      .observe(lb, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] }); };
  lbw();

  const vis = new WeakMap();
  const io = 'IntersectionObserver' in window ? new IntersectionObserver(es => es.forEach(e => vis.set(e.target, e.isIntersecting)), { threshold: 0.05 }) : null;
  roots().forEach(r => io ? io.observe(r) : vis.set(r, true));

  const $ = (r, k) => r.querySelector('[data-k="' + k + '"]');
  const tog = (el, c, on) => { if (el && el.classList.contains(c) !== !!on) el.classList.toggle(c, !!on); };
  const txt = (el, s) => { if (el && el.textContent !== s) el.textContent = s; };
  const htm = (el, s) => { if (el && el.__h !== s) { el.innerHTML = s; el.__h = s; } };
  const typ = (s, t, t0, t1) => t <= t0 ? '' : s.slice(0, Math.round(s.length * Math.min(1, (t - t0) / (t1 - t0))));
  const cl = (x) => Math.max(0, Math.min(1, x));
  const mmss = (s) => { s = Math.max(0, Math.round(s)); return String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0'); };
  const field = (el, s, ph, foc) => { htm(el, s ? s.replace(/&/g, '&amp;').replace(/</g, '&lt;') : '<span class="ph">' + ph + '</span>'); tog(el, 'foc', foc); };
  const SPIN = '<span class="km-spin"></span> ';
  const CODE_RE = new RegExp(BT + '([^' + BT + ']+)' + BT, 'g');
  const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;');
  const md = (l) => l.startsWith('#') ? '<span class="sk-h">' + l + '</span>' : esc(l).replace(CODE_RE, '<span class="sk-c">' + BT + '$1' + BT + '</span>');
  const ed = (lines) => lines.map((l, i) => '<div><span class="sk-ln">' + (i + 1) + '</span><span>' + md(l) + '</span></div>').join('');

  const FIG = {
    standard: [' _              _____ _______   __', '| | _____  _ __|_   _|_   _\\ \\ / /', "| |/ / _ \\| '__| | |   | |  \\ V / ", '|   < (_) | |    | |   | |   | |  ', '|_|\\_\\___/|_|    |_|   |_|   |_|  '],
    small: [' _           _____ _______   __', '| |_____ _ _|_   _|_   _\\ \\ / /', "| / / _ \\ '_| | |   | |  \\ V / ", '|_\\_\\___/_|   |_|   |_|   |_|  '],
    'ansi shadow': ['██╗  ██╗ ██████╗ ██████╗ ████████╗████████╗██╗   ██╗', '██║ ██╔╝██╔═══██╗██╔══██╗╚══██╔══╝╚══██╔══╝╚██╗ ██╔╝', '█████╔╝ ██║   ██║██████╔╝   ██║      ██║    ╚████╔╝ ', '██╔═██╗ ██║   ██║██╔══██╗   ██║      ██║     ╚██╔╝  ', '██║  ██╗╚██████╔╝██║  ██║   ██║      ██║      ██║   ', '╚═╝  ╚═╝ ╚═════╝ ╚═╝  ╚═╝   ╚═╝      ╚═╝      ╚═╝   ']
  };
  const RACK = ["   .------------------------------.", "   | [=====]  o o   ::::::   ▮▮▮  |", "   |------------------------------|", "   | [=====]  o o   ::::::   ▮▮▮  |", "   |------------------------------|", "   | [=====]  o o   ::::::   ▮▮▯  |", "   |------------------------------|", "   |  _________________________   |", "   | |_________________________| o|", "   '------------------------------'", "        ||                  ||"];
  const PERL = ['# Perl Best Practices', '', 'When generating or reviewing Perl (Perl 5) code, apply the rules below.', '', '## Robust Perl Code', '', '- Start every file with ' + q('use strict;') + ' and ' + q('use warnings;') + '.', '- Open files with three-argument ' + q('open') + ' and a lexical filehandle.', '- Include ' + q('$!') + ' in every error message.', '- Report misuse with ' + q('Carp::croak') + ' instead of ' + q('die') + '.', '- Validate ' + q('@ARGV') + ' up front; exit non-zero on failure.', '- Check the return values of ' + q('close') + ', ' + q('system') + ' and ' + q('print') + ' where failure matters.', '', '## Perl Comments', '', '- Comment to explain why, never to restate what the code already says.', '- Document every public script with POD: ' + q('NAME') + ', ' + q('SYNOPSIS') + ', ' + q('DESCRIPTION') + '.', '- Write complex regexes with the ' + q('/x') + ' modifier and inline comments.', '- Mark deferred work as ' + q('# TODO(owner): reason') + ' so it stays findable.', '', '## Secure Patterns', '', '- Never interpolate untrusted input into ' + q('system') + '; pass a list instead.', '- Run with ' + q('-T') + ' (taint mode) for scripts that handle external data.'];
  const HOUSE = ['# House rules: deploy', '', 'Applies to every Agent run on our servers.', '', '## Deploying', '', '- We deploy with systemd units, never Docker.', "- Reload, don't restart: " + q('systemctl reload <unit>') + '.', '- Changes under ' + q('/etc') + ' go through etckeeper first.', '- Never touch prod between 18:00 and 08:00.', '- Ask before any command that deletes data.', '', '## Before a change', '', '- Check ' + q('systemctl is-active <unit>') + ' and note the result in the journal.', '- Run the config test first: ' + q('nginx -t') + ', ' + q('apachectl configtest') + ', ' + q('sshd -t') + '.', '- Take a snapshot of the volume when a package upgrade touches the database.', '', '## After a change', '', '- Watch the unit for two minutes: ' + q('journalctl -fu <unit>') + '.', '- Roll back with the previous release symlink, not by hand.', '- Write one line into #ops: host, unit, what changed, why.', '', '## Never', '', '- No ' + q('rm -rf') + ' with a variable path.', '- No ' + q('chmod 777') + ', anywhere.'];
  const THEMES = {
    'GitHub Dark': ['#0d1117', '#c9d1d9', '#58a6ff', '#58a6ff', '#3fb950', '#f85149', '#d29922'],
    'Monokai': ['#272822', '#f8f8f2', '#f8f8f0', '#66d9ef', '#a6e22e', '#f92672', '#e6db74'],
    'Nord': ['#2e3440', '#d8dee9', '#88c0d0', '#81a1c1', '#a3be8c', '#bf616a', '#ebcb8b'],
    'Solarized Dark': ['#002b36', '#93a1a1', '#93a1a1', '#268bd2', '#859900', '#dc322f', '#b58900'],
    'IntelliJ Darcula': ['#2b2b2b', '#a9b7c6', '#bbbbbb', '#6897bb', '#6a8759', '#ff6b68', '#bbb529'],
    'One Dark': ['#282c34', '#abb2bf', '#528bff', '#61afef', '#98c379', '#e06c75', '#e5c07b'],
    'GitHub Light': ['#ffffff', '#24292f', '#0969da', '#0969da', '#1a7f37', '#cf222e', '#9a6700']
  };
  const TN = Object.keys(THEMES);
  const LINES = ['1', '6–12', '18–31', '33–47', '16–17', '52–60', '14–15', '61'];

  const MOCKS = {
    fca: [20, (r, t) => {
      htm($(r, 'st'), t < 1.6 ? SPIN + 'Analyzing with the local profile…' : t < 6.5 ? '<span class="ok">✓ 3 findings · 2 dependencies</span>' : '');
      [['sum', 1.6], ['dep', 2.2], ['h1', 2.6], ['f1', 2.8], ['h2', 3.4], ['f2', 3.6], ['f3', 4.4]].forEach(([k, s]) => tog($(r, k), 'off', t < s));
      const fl = $(r, 'flow');
      [...fl.children].forEach((c, i) => { if (!c.classList.contains('fl-tip')) tog(c, 'off', t < 3 + i * 0.2); });
      htm($(r, 'fst'), t < 3 ? '' : t < 5.6 ? SPIN + '<span class="mu">generating…</span>' : '<span class="ok">✓</span>');
      tog($(r, 'c1'), 'on', t >= 7); tog($(r, 'c2'), 'on', t >= 8.4);
      const ap = $(r, 'apply'); tog(ap, 'dis', t < 7); tog(ap, 'pri', t >= 7); tog(ap, 'hot', t >= 15.5 && t < 16.2);
      txt(ap, t < 8.4 ? (t < 7 ? 'Apply improvements' : 'Apply 1 improvement') : 'Apply 2 improvements');
      const hot = t >= 9.5 && t < 15.5 ? Math.floor((t - 9.5) / 0.75) % 8 : -1;
      fl.querySelectorAll('[data-n]').forEach(n => tog(n, 'hot', +n.dataset.n === hot));
      const tip = $(r, 'ftip');
      if (hot >= 0) { const n = fl.querySelector('[data-n="' + hot + '"]'); tip.style.top = (n.offsetTop + (hot === 4 ? 30 : 2)) + 'px'; txt(tip, 'lines ' + LINES[hot]); tip.style.opacity = 1; } else tip.style.opacity = 0;
      txt($(r, 'bar'), t >= 16.2 ? 'Diff ready — 2 changes, 11 hardening options folded in · review before applying' : 'Loaded text file: /home/daniel/Dokumente/messages_errors_table.pl');
    }],
    sftp: [15, (r, t) => {
      tog($(r, 'lsel'), 'sel', t >= 2.5 && t < 7.5);
      tog($(r, 'up'), 'hot', t >= 3 && t < 3.6);
      tog($(r, 'xfer'), 'off', !(t >= 3.4 && t < 8.6));
      const p = cl((t - 3.6) / 3.4);
      $(r, 'xbar').style.width = (p * 100).toFixed(1) + '%';
      txt($(r, 'xpct'), Math.round(p * 100) + '%');
      txt($(r, 'xlab'), p < 1 ? 'Uploading nginx-site.conf → /home/daniel' : '✓ Uploaded nginx-site.conf (3,4 KB) — owner 1000:1000, -rw-r--r--');
      const rn = $(r, 'rnew'); tog(rn, 'hide', t < 7); tog(rn, 'new', t >= 7);
      const s = t >= 9 && t < 13.5 ? typ('*.conf', t, 9, 10.2) : '';
      field($(r, 'rq'), s, 'Search files… (* as wildcard)', t >= 9 && t < 13.5);
      r.querySelectorAll('[data-conf="0"]').forEach(x => tog(x, 'hide', s === '*.conf'));
    }],
    lm: [17, (r, t) => {
      const p = cl(t / 11);
      $(r, 'dbar').style.width = (p * 100).toFixed(1) + '%';
      txt($(r, 'dl2'), (p * 2.5).toFixed(1) + ' GiB of 2.5 GiB');
      txt($(r, 'dl3'), p < 1 ? 'Elapsed ' + mmss(p * 142) + ' · Speed ' + (17.6 + Math.sin(t * 2.1) * 0.9).toFixed(1) + ' MiB/s · Remaining ' + mmss((1 - p) * 142) : 'Elapsed 02:22');
      htm($(r, 'dl1'), t < 11 ? 'Downloading: Qwen3-4B-Q4_K_M.gguf (1/1)' : t < 12.6 ? SPIN + 'Verifying SHA-256 against the pinned revision…' : '<span class="ok">✓ SHA-256 verified · revision 3f2a9c1 pinned · installed</span>');
      tog($(r, 'pz'), 'dis', t >= 11);
      const nm = $(r, 'newm'); tog(nm, 'hide', t < 12.6); tog(nm, 'new', t >= 12.6);
    }],
    am: [22, (r, t) => {
      const sp = [0, 2, 4, 6, 17, 19.5];
      for (let i = 1; i <= 5; i++) { const s = $(r, 's' + i); tog(s, 'cur', t >= sp[i - 1] && t < sp[i]); tog(s, 'done', t >= sp[i]); }
      let cur = 4; for (let i = 0; i < 5; i++) if (t >= sp[i] && t < sp[i + 1]) cur = i;
      txt($(r, 'sn'), 'Step ' + (cur + 1) + ' of 5');
      tog($(r, 'dbox'), 'off', !(t >= 6 && t < 17));
      const paused = t >= 10 && t < 12.5;
      const dt = t < 10 ? t - 6 : paused ? 4 : t - 8.5;
      const p = cl(dt / 8.5);
      $(r, 'db').style.width = (p * 100).toFixed(1) + '%';
      txt($(r, 'd1'), paused ? 'Paused — Qwen3-4B-Q4_K_M.gguf' : p < 1 ? 'Downloading Qwen3-4B-Q4_K_M.gguf' : '✓ Downloaded and verified');
      txt($(r, 'd2'), Math.round(p * 100) + '%');
      txt($(r, 'd3'), paused ? 'paused at ' + (p * 2.5).toFixed(2) + ' GiB · resumes from here' : (p * 2.5).toFixed(2) + ' GiB of 2.5 GiB · ' + (p < 1 ? (21.3 + Math.sin(t * 1.7)).toFixed(1) + ' MiB/s · ETA ' + mmss((1 - p) * 120) : 'done'));
      const pr = $(r, 'pr'); txt(pr, paused ? 'Resume' : 'Pause'); tog(pr, 'hot', paused || (t >= 9.6 && t < 10));
      txt($(r, 'nx'), t >= 17 ? 'Finish' : 'Next');
      const np = $(r, 'np'); tog(np, 'hide', t < 19.5); tog(np, 'new', t >= 19.5);
      txt($(r, 'bar'), t >= 19.5 ? 'Profile “Local · Qwen3-4B” created — loopback-only server, generated API key' : 'Ready');
    }],
    ks: [18, (r, t) => {
      txt($(r, 'q'), typ('how do we rotate nginx logs?', t, 0.5, 2.6)); tog($(r, 'q'), 'km-caret', t < 3);
      [3, 3.4, 3.8].forEach((s, i) => tog($(r, 'r' + i), 'off', t < s));
      tog($(r, 'upd'), 'hot', t >= 9 && t < 9.6);
      const ix = t >= 9.6 && t < 14;
      htm($(r, 'sst'), ix ? '<span class="wa">' + SPIN + 'INDEXING</span>' : '<span class="ok">READY</span>');
      const f = Math.round(12 + cl((t - 9.6) / 4.4) * 36);
      txt($(r, 'sfc'), ix ? f + ' / ' + Math.round(f * 27.4).toLocaleString('en') : t >= 14 ? '48 / 1,318' : '48 / 1,312');
      txt($(r, 'slt'), t >= 14 ? '2026-08-03 19:24' : '2026-07-15 20:30');
      tog($(r, 'srow'), 'new', t >= 14);
    }],
    sk: [18, (r, t) => {
      txt($(r, 'req'), typ('Write a Perl script that tails nginx logs and alerts on 5xx', t, 0.4, 3.4));
      const house = t >= 9 && t < 15, sw = house && t >= 9.8;
      const s = house ? typ('house', t, 9, 9.8) : '';
      field($(r, 'sq'), s, 'Search skills…', house);
      r.querySelectorAll('.sk-it').forEach(it => {
        const n = it.dataset.s;
        tog(it, 'snt', t >= 4 && t < 16.5 && (n === 'Perl (Perl 5)' || n.startsWith('House')));
        tog(it, 'sel', sw ? n.startsWith('House') : n === 'Perl (Perl 5)');
        tog(it, 'hide', s === 'house' && !n.toLowerCase().includes('house'));
      });
      htm($(r, 'ed'), ed(sw ? HOUSE : PERL));
      txt($(r, 'nm'), sw ? 'House rules: deploy' : 'Perl (Perl 5)');
      txt($(r, 'tg'), sw ? 'deploy, systemd, prod' : 'perl code, cpan, perldoc');
      txt($(r, 'bar'), t >= 4 && t < 16.5 ? 'Sent with this request: Perl (Perl 5), House rules: deploy — 2 of 40 skills' : 'Ready');
    }],
    jv: [16, (r, t) => {
      txt($(r, 'dur'), '27m ' + Math.floor(t) + 's');
      tog($(r, 'ne'), 'off', t < 3);
      txt($(r, 'nt'), typ('Rotated the nginx logs', t, 3, 4.4));
      txt($(r, 'np'), typ('Ran logrotate by hand and checked that nginx reopened its log files.', t, 4.4, 7));
      htm($(r, 'nc'), (t >= 7.5 ? '$ sudo logrotate -f /etc/logrotate.d/nginx' : '') + (t >= 9 ? '<br>$ ls -lh /var/log/nginx' : ''));
      txt($(r, 'en'), t < 3 ? '2' : '3');
      txt($(r, 'cm'), t < 7.5 ? '3' : t < 9 ? '4' : '5');
    }],
    jm: [14, (r, t) => {
      const s = t < 9 ? typ('nginx', t, 1, 2.4) : '';
      field($(r, 'f'), s, 'Filter by title, connection or host', t >= 1 && t < 9);
      tog($(r, 'sc'), 'on', t >= 2.6 && t < 9);
      r.querySelectorAll('[data-ng="0"]').forEach(x => tog(x, 'hide', s === 'nginx'));
      txt($(r, 'run'), '● 6m ' + String(Math.floor(t) + 12).padStart(2, '0') + 's');
      tog($(r, 'ex'), 'off', !(t >= 9.5 && t < 12));
      tog(r.querySelector('[data-t="2"]'), 'hot', t >= 9.5 && t < 12);
      tog($(r, 'd'), 'foc', t >= 12);
    }],
    th: [21, (r, t) => {
      const name = TN[Math.floor(t / 3) % TN.length], c = THEMES[name];
      r.querySelectorAll('[data-th]').forEach(x => tog(x, 'sel', x.dataset.th === name));
      const pv = $(r, 'pv');
      if (pv.__n !== name) { pv.__n = name; ['--bg', '--fg', '--cu', '--b', '--g', '--r', '--y'].forEach((v, i) => pv.style.setProperty(v, c[i])); txt($(r, 'pn'), name); }
    }],
    se: [18, (r, t) => {
      txt($(r, 'desc'), typ("Prints the 1-, 5- and 15-minute load averages from /proc/loadavg as a table; returns undef when the file can't be read.", t, 1, 4));
      tog($(r, 'desc'), 'foc', t >= 1 && t < 4.2);
      tog($(r, 'aib'), 'hot', t >= 0.6 && t < 4.2);
      tog($(r, 'aic'), 'hot', t >= 5.4 && t < 6.2);
      htm($(r, 'ghost'), t < 6 ? '    ' : t < 8.5 ? '<span class="se-ghost">    die "No load data in $file\\n" unless defined $line;</span>' : '    <span class="c-kw">die</span> <span class="c-st">"No load data in $file\\n"</span> <span class="c-kw">unless</span> <span class="c-fn">defined</span> $line;');
      tog($(r, 'chk'), 'hot', t >= 11 && t < 11.6);
      const cs = $(r, 'cs'); txt(cs, '✓ Syntax OK (perl -c)'); tog(cs, 'off', !(t >= 11.6 && t < 16.5)); tog(cs, 'ok', true);
      txt($(r, 'bar'), t >= 6 && t < 8.5 ? 'AI Code: inline completion — Tab to accept, Esc to dismiss' : t >= 4 && t < 6 ? 'AI suggestion written to the description' : 'Ready');
    }],
    aa: [20, (r, t) => {
      const ai = t >= 12;
      tog($(r, 'tb1'), 'on', !ai); tog($(r, 'tb2'), 'on', ai);
      $(r, 'p1').style.display = ai ? 'none' : ''; $(r, 'p2').style.display = ai ? '' : 'none';
      if (!ai) {
        const tx = typ('korTTY', t, 0.3, 1.5); txt($(r, 'tx'), tx);
        const si = Math.min(2, Math.floor(t / 4)), st = ['standard', 'small', 'ansi shadow'][si];
        txt($(r, 'sty'), st); txt($(r, 'sn'), (si + 1) + ' / 12');
        txt($(r, 'pv'), tx.length === 6 ? FIG[st].join('\n') : '');
        txt($(r, 'cp'), 'Copy to Clipboard'); tog($(r, 'cp'), 'hot', false);
      } else {
        txt($(r, 'sub'), typ('a server rack', t, 12.3, 13.5));
        const n = t < 14 ? 0 : Math.ceil(cl((t - 14) / 2.5) * RACK.length);
        txt($(r, 'pv'), t < 14 && t >= 13.6 ? '…drawing' : RACK.slice(0, n).join('\n'));
        tog($(r, 'cp'), 'hot', t >= 18 && t < 18.6); txt($(r, 'cp'), t >= 18.6 ? 'Copied ✓' : 'Copy to Clipboard');
      }
    }],
    tr: [20, (r, t) => {
      const AP = ['Google Translate', 'DeepL', 'LibreTranslate', 'Microsoft Translator', 'Yandex'];
      txt($(r, 'api'), t < 8 ? AP[Math.floor(t / 1.6) % 5] : 'DeepL'); tog($(r, 'api'), 'hot', t < 8);
      tog($(r, 'ok'), 'off', t < 9);
      tog($(r, 'gen'), 'hot', t >= 10 && t < 10.5);
      const p = cl((t - 10.5) / 5);
      $(r, 'gb').style.width = (t >= 10.5 ? p * 100 : 0).toFixed(1) + '%';
      txt($(r, 'gp'), t < 10.5 ? '' : p < 1 ? Math.round(p * 2086).toLocaleString('en') + ' / 2,086 strings' : '✓ 2,086 strings written');
      tog($(r, 'nl'), 'off', t < 15.6);
      const g = (t % 20) / 20;
      $(r, 'gbb').style.width = (23.7 + g * 2.6).toFixed(1) + '%';
      txt($(r, 'gpc'), (23.7 + g * 2.6).toFixed(1) + ' %');
    }]
  };

  // — tab tours: windows with several tabs step through every tab after their own scene —
  const R = (l, c) => '<div class="km-r"><span class="mu" style="width:150px;flex:none">' + l + '</span>' + c + '</div>';
  const S = (v, w) => '<span class="km-sel" style="min-width:' + (w || 170) + 'px">' + v + '</span>';
  const I = (v, w) => '<span class="km-in" style="max-width:' + (w || 320) + 'px">' + v + '</span>';
  const B = (v) => '<span class="km-btn">' + v + '</span>';
  const C = (on, l) => '<span class="km-cb' + (on ? ' on' : '') + '"><i></i>' + l + '</span>';
  const H = (v) => '<b class="km-h" style="margin-top:4px">' + v + '</b>';
  const N = (v) => '<div class="mu" style="font-size:11px;max-width:560px">' + v + '</div>';
  const SW = (c) => '<span class="km-sw" style="background:' + c + '"></span>';
  const P = (inner) => '<div class="km-p km-g" style="gap:7px">' + inner + '</div>';
  const T = (cols, rows) => '<div class="km-tw"><table class="km-t"><thead><tr>' + cols.map(c => '<th style="width:' + c[1] + '">' + c[0] + '</th>').join('') + '</tr></thead><tbody>' + rows.map(r => '<tr' + (r.sel ? ' class="sel"' : '') + '>' + r.map(x => '<td>' + x + '</td>').join('') + '</tr>').join('') + '</tbody></table></div>';
  const sel = (a) => { a.sel = 1; return a; };
  const ANSI = ['#000000', '#cd3131', '#0dbc79', '#e5e510', '#2472c8', '#bc3fbc', '#11a8cd', '#e5e5e5'], ANSIB = ['#666666', '#f14c4c', '#23d18b', '#f5f543', '#3b8eea', '#d670d6', '#29b8db', '#ffffff'];
  const TOURS = {
    ai: {
      tabs: ['Profiles', 'Local Models', 'Knowledge Stores', 'AI Skills', 'Local AI'],
      pane: {
        'Profiles': '<div style="display:grid;grid-template-columns:220px minmax(0,1fr)"><div class="km-p km-g" style="gap:6px;border-right:1px solid var(--kb)"><div class="km-r" style="gap:5px">' + B('✦ Setup wizard') + B('+ Add') + '</div>' + T([['Profile', '100%']], [sel(['Workstation LLM <span class="mu">(Default)</span>']), ['Claude Sonnet'], ['GPT-5 mini'], ['<span class="mu">🔒</span> ACME internal LLM']]) + N('Used 0 tokens (unlimited budget)') + '</div>' + P(R('Profile name', I('Workstation LLM')) + R('Connection', S('HTTP API')) + R('API URL', I('http://127.0.0.1:1234/v1/chat/completions', 380)) + R('Model', S('qwen2.5-vl-7b-instruct', 220)) + R('Reasoning', S('Disabled')) + R('Image input (vision)', S('Auto (detect)')) + R('Prompt optimization', S('Auto (model detection)', 200)) + R('Internet access', S('Disabled')) + R('Max tokens', I('0 · unlimited', 140)) + '<div class="km-r" style="gap:6px">' + B('Test AI Connection') + B('Save') + '</div>') + '</div>',
        'Local Models': P(H('Runtimes') + T([['Runtime', '25%'], ['Version', '25%'], ['Backend', '20%'], ['State', '30%']], [['llama.cpp', 'b6123', 'Metal', '<span class="ok">✓ Installed</span>'], ['MLX', '0.26.1', 'MLX', '<span class="ok">✓ Installed</span>']]) + H('Installed local models') + T([['Name', '24%'], ['GGUF file', '36%'], ['Purpose', '16%'], ['State', '24%']], [['Qwen3-8B', 'Qwen3-8B-Q4_K_M.gguf', 'Chat', '<span class="ok">● Running</span>'], ['nomic-embed-text', 'nomic-embed-text-v1.5.f16.gguf', 'Embedding', '<span class="mu">Stopped</span>']]) + '<div class="km-r" style="gap:6px">' + B('✦ Setup assistant') + B('Import GGUF') + B('▶ Start selected') + B('■ Stop selected') + '</div>' + H('Find local models on Hugging Face') + '<div class="km-r">' + I('qwen3', 600) + S('GGUF + MLX', 120) + B('⌕ Search') + '</div>'),
        'Knowledge Stores': P(H('Knowledge stores') + T([['Name', '30%'], ['Type', '16%'], ['Embedding model', '26%'], ['Sources', '28%']], [sel(['Operations handbook', 'Local HNSW', 'qwen3-embedding', '2']), ['Runbooks (prod)', 'Local HNSW', 'nomic-embed-text', '3']]) + '<div class="km-r" style="gap:6px">' + B('Create knowledge store') + B('Configure') + B('Test search') + '</div>' + H('Sources') + T([['Path', '40%'], ['Sync', '15%'], ['Status', '15%'], ['Files / chunks', '30%']], [['~/ops/handbook', 'Watch', '<span class="ok">READY</span>', '38 / 1,204'], ['~/ops/runbooks', 'Manual', '<span class="ok">READY</span>', '48 / 1,318']])),
        'AI Skills': '<div style="display:grid;grid-template-columns:200px minmax(0,1fr)"><div class="km-p km-g" style="gap:5px;border-right:1px solid var(--kb)">' + C(true, 'Enable AI Skills') + C(true, 'Send only matching skills') + T([['Skill', '100%']], [['Bourne-Shell (sh, POSIX)'], ['Docker'], ['House rules: deploy'], sel(['Perl (Perl 5)']), ['Python'], ['Terraform']]) + N('Total: 40 · Active: 40') + '</div>' + P(R('Skill name', I('Perl (Perl 5)')) + R('Target', S('Both')) + '<div class="sk-ed km-mono" style="min-height:140px"><div><span class="sk-ln">1</span><span class="sk-h"># Perl Best Practices</span></div><div><span class="sk-ln">2</span><span></span></div><div><span class="sk-ln">3</span><span>- Start every file with use strict; and use warnings;</span></div><div><span class="sk-ln">4</span><span>- Three-argument open with a lexical filehandle.</span></div></div>') + '</div>',
        'Local AI': P(H('Roles') + R('Text and translation', S('Use default AI profile', 220)) + R('Coding', S('Local · Qwen3-8B', 220)) + R('Session journal profile', S('Default profile', 220)) + H('Local runtime') + R('Embedding model', S('qwen3-embedding', 220)) + R('Preferred runtime backend', S('Auto (Metal, falls back to CPU)', 260)) + R('Runtime updates', S('Automatic (keep active backend)', 260)) + R('Hugging Face token', I('•••••••••••• stored in the vault', 260)) + N('Empty roles fall back to the default profile.')),
        'Saved Chats': P(H('Saved AI chats') + T([['Title', '42%'], ['Profile', '22%'], ['Messages', '12%'], ['Saved', '24%']], [sel(['nginx 502 on web01 after reload', 'Claude Sonnet', '14', '2026-08-03 19:40']), ['Explain this awk one-liner', 'Local · Qwen3-8B', '6', '2026-08-02 11:05'], ['Postgres vacuum strategy for db01', 'GPT-5 mini', '9', '2026-07-30 16:22'], ['Translate the on-call runbook to German', 'Local · Qwen3-8B', '4', '2026-07-28 08:47']]) + '<div class="km-r" style="gap:6px">' + B('Open') + B('Rename') + B('⟳ Refresh') + B('Delete') + '</div>'),
        'Swarm Chats': P(H('Saved AI Swarm conversations') + T([['Title', '38%'], ['Servers', '12%'], ['Origin', '22%'], ['Saved', '28%']], [sel(['Patch audit, read-only', '16', 'JobScheduler', '2026-08-03 03:00']), ['Disk usage across the fleet', '9', 'Manual', '2026-08-01 14:12'], ['Which hosts still run OpenSSL 1.1?', '24', 'Manual', '2026-07-29 10:31'], ['Weekly certificate expiry check', '12', 'JobScheduler', '2026-07-28 06:00']]) + '<div class="km-r" style="gap:6px">' + B('Open') + B('Rename') + B('Export') + B('Delete') + '</div>')
      },
      d: {
        en: { 'Profiles': 'endpoint, model, reasoning, image input, internet access and token budget per profile.', 'Local Models': 'install the runtimes, search Hugging Face, download and verify GGUF and MLX models.', 'Knowledge Stores': 'local RAG indexes from files and folders, with a test search before you trust them.', 'AI Skills': 'the instruction library, built-in and your own, sent only when a request matches.', 'Local AI': 'which profile handles text, coding and journal summaries, plus embedding model, runtime backend and Hugging Face token.', 'Saved Chats': 'reopen, rename or delete earlier AI conversations.', 'Swarm Chats': 'saved AI Swarm runs across many servers, including the scheduled ones.' },
        de: { 'Profiles': 'Endpoint, Modell, Reasoning, Bildeingabe, Internetzugriff und Token-Budget pro Profil.', 'Local Models': 'Runtimes installieren, Hugging Face durchsuchen, GGUF- und MLX-Modelle laden und prüfen.', 'Knowledge Stores': 'lokale RAG-Indizes aus Dateien und Ordnern, mit Testsuche, bevor man ihnen vertraut.', 'AI Skills': 'die Anweisungsbibliothek, eingebaut und eigene, gesendet nur bei passender Anfrage.', 'Local AI': 'welches Profil Text, Code und Journal-Zusammenfassungen übernimmt, dazu Embedding-Modell, Runtime-Backend und Hugging-Face-Token.', 'Saved Chats': 'frühere KI-Gespräche öffnen, umbenennen oder löschen.', 'Swarm Chats': 'gespeicherte AI-Swarm-Läufe über viele Server, auch die geplanten.' }
      }
    },
    set: {
      tabs: ['Font', 'Colors', 'Themes', 'Appearance', 'Terminal', 'Video', 'Backup', 'Logging', 'Export', 'Updates', 'Window', 'Keyboard', 'Resources', 'Security', 'Privacy', 'SFTP Manager', 'Editor', 'Snippet Editor', 'Language', 'Translation', 'AI'],
      pane: {
        'Font': P(R('Font Family', S('Monospaced')) + R('Font Size', I('14 pt', 90)) + R('Preview', '<span class="km-mono" style="font-size:15px;color:var(--kh)">AaBbCcDdEe 0123456789 {}[]()=&gt;</span>')),
        'Colors': P(R('Color Profile', S('GitHub Dark', 200)) + R('Text Color', SW('#c9d1d9') + ' <span class="km-mono">#c9d1d9</span>') + R('Background', SW('#0d1117') + ' <span class="km-mono">#0d1117</span>') + R('Cursor', SW('#79c0ff') + ' <span class="km-mono">#79c0ff</span>') + C(true, 'Cursor blinks') + R('Selection', SW('#3399ff') + ' <span class="km-mono">#3399ff</span>') + C(true, 'Enable terminal colors') + R('ANSI · Normal', '<span class="km-r" style="gap:5px">' + ANSI.map(SW).join('') + '</span>') + R('ANSI · Bright', '<span class="km-r" style="gap:5px">' + ANSIB.map(SW).join('') + '</span>')),
        'Themes': P(H('Terminal Themes') + '<div class="km-r" style="gap:5px"><span class="th-c" style="background:#c9d1d9;color:#0d1117">FG</span><span class="th-c" style="color:#c9d1d9">BG</span><span class="th-c" style="background:#58a6ff;color:#0d1117">CURSOR</span><span class="th-c" style="background:#f85149;color:#0d1117">AI ERR</span></div>' + T([['Theme', '100%']], [['Default (built-in)'], ['IntelliJ Darcula (built-in)'], ['Monokai (built-in)'], ['Nord (built-in)'], sel(['GitHub Dark (built-in)']), ['GitHub Light (built-in)']]) + '<div class="km-r" style="gap:6px">' + B('Add') + B('Edit') + B('Duplicate') + '</div>'),
        'Appearance': P(R('App Design', S('AtlantaFX Primer Dark', 220) + B('◀') + B('▶')) + C(true, 'Enable design animations') + R('UI font size', I('100 %', 80) + C(false, 'Match display resolution')) + N('Scales menus, dialogs and labels. Terminal, editor and AI chat keep their own font sizes.') + R('Chat color profile', S('Automatic (theme)', 200)) + '<div class="km-box" style="height:90px;display:grid;place-items:center" class="mu"><span class="mu">Preview: Matrix Terminal · Amber CRT · Synthwave ’84 · Gruvbox Retro …</span></div>'),
        'Terminal': P('<div style="display:grid;grid-template-columns:1fr 1fr;gap:6px 18px">' + R('Columns', I('80', 70)) + R('Rows', I('24', 70)) + R('Scrollback', I('10000', 90)) + R('Encoding', S('UTF-8', 110)) + '</div>' + C(true, 'Show a confirmation before closing a running terminal') + H('Keyword highlighting') + C(true, 'Highlight keywords in terminal output') + R('Default rule set', S('Errors &amp; warnings', 180) + B('Edit rules…')) + H('Links &amp; paste protection') + C(true, 'Detect web addresses and file paths') + R('Paste protection', S('Ask for multi-line and risky pastes', 260)) + H('Shell integration &amp; notifications') + C(true, 'Use the shell’s marks for prompts and command output (OSC 133)') + C(true, 'Notify when a long-running command finishes') + H('Coding agents') + C(true, 'Detect Claude Code, Codex and Gemini CLI in local shells')),
        'Video': P(C(false, 'Enable terminal recording after app restart') + C(true, 'Capture terminal colors in recordings') + N('Recordings follow the asciicast format and can be replayed inside korTTY.')),
        'Backup': P(H('Backup Settings') + R('Maximum number of backups', I('10', 70)) + N('0 = unlimited; older backups are deleted automatically.') + H('Encryption (required)') + C(true, 'ZIP with password') + R('Credential', S('Backup vault password', 240)) + C(false, 'GPG encryption') + R('GPG Key', S('Select GPG key…', 240)) + '<div class="wa" style="font-size:11.5px">⚠ Backups are ALWAYS encrypted.</div>'),
        'Logging': P(H('Log files') + R('Log directory', I('~/.kortty/logs', 320) + B('Browse…')) + R('Keep logs', I('7 days', 90)) + N('Log archives older than 24 hours are compressed automatically.') + H('Session Journal') + R('Storage folder', I('empty = ~/.kortty/journals', 320) + B('Browse…')) + C(true, 'Generate AI summaries by default') + C(true, 'Make web addresses in journals clickable') + R('Summary interval', I('5 min', 80)) + R('AI profile for summaries', S('Default profile'))),
        'Export': P(H('Exported documents') + N('Applies to every document korTTY exports: session journals and AI chats.') + C(false, 'Add a watermark to exported PDFs') + R('Watermark text', I('korTTY · Developed by Daniel Mengel', 320)) + C(true, 'Show a footer in exported documents') + R('Footer text', I('Created with korTTY · Developed by Daniel Mengel', 320))),
        'Updates': P(H('Update checks') + C(true, 'Check automatically for korTTY updates') + R('Check interval', '<span class="km-prog" style="width:220px;display:inline-block;vertical-align:middle"><i style="width:8%"></i></span><span>Every 1 day(s)</span>') + N('Automatic checks only show a dialog when a newer compatible version is available. Manual checks are in the About dialog.')),
        'Window': P(H('Window Settings') + C(true, 'Remember window geometry') + C(true, 'Remember dashboard state') + C(false, 'Open tool windows as tabs') + H('Tabs') + C(true, 'Frame the terminal in its connection’s tab color') + C(true, 'Name terminal tabs after the title the shell sets') + C(false, 'Ctrl+Tab switches tabs in the order they were last used') + H('Session Restore') + R('At startup', S('Offer to restore the previous session', 260)) + C(false, 'Also restore the output of each terminal pane (encrypted)')),
        'Keyboard': P(H('Keyboard Shortcuts') + '<div class="km-r">' + I('Filter by action, menu or shortcut', 300) + C(false, 'Only changed') + B('Reset All') + '</div>' + T([['Action', '34%'], ['Menu', '20%'], ['Shortcut', '28%'], ['Status', '18%']], [['New Tab', 'File', 'Cmd+T', ''], ['New Window', 'File', 'Shift+Cmd+N', ''], ['Reopen Closed Tab', 'File', 'Option+Shift+Cmd+T', ''], ['Copy', 'Edit', 'Cmd+C', '<span class="mu">Fixed</span>'], ['Command Palette', 'View', 'Shift+Cmd+P', ''], sel(['Show Dashboard', 'View', 'Option+Cmd+B', '<span class="wa">Changed</span>']), ['Global Settings', 'Configuration', 'Cmd+Comma', '']])),
        'Resources': P(H('Resources') + R('Resource profile', S('Balanced', 160)) + T([['Profile', '30%'], ['Use', '70%']], [sel(['Balanced', 'the default for everyday work']), ['High', 'more headroom for large sessions and local AI'], ['Maximum', 'everything the machine can spare']])),
        'Security': P(H('Master Password') + N('Encrypts every stored connection password. Changing it re-encrypts them all.') + B('Change Master Password…') + C(true, 'Require master password on startup') + C(false, 'Disable master password prompt on startup (auto-login)') + C(false, 'Enable temporary SSH key option') + H('Session isolation') + R('Default isolation', S('Own process + sandbox', 220)) + '<div class="ok" style="font-size:11.5px">✓ Sandbox on this computer: available (sandbox-exec)</div>'),
        'Privacy': P(H('Anonymous usage statistics') + N('Voluntary (opt-in). Contains no personal information and is processed exclusively on servers in the EU (GDPR-compliant).') + C(true, 'Share anonymous usage data to help improve korTTY') + N('Collected: event names (app start, feature used), app version, operating system, app language, an anonymous session ID.') + N('Never collected: hostnames, usernames, connection data, file paths, snippet or terminal content, keys, passwords, error messages.')),
        'SFTP Manager': P(H('SFTP Manager Settings') + C(false, 'Auto-close SFTP tabs after inactivity') + R('Timeout (minutes)', I('10', 70)) + H('Transfers') + R('Parallel transfers', I('3', 70)) + R('When the target exists', S('Ask (with “apply to all”)', 220)) + C(true, 'Resume interrupted transfers') + H('ZIP creation') + R('Default ZIP path', I('/tmp', 200)) + R('Default compression (0–9)', I('6', 70)) + H('JobScheduler Rsync') + R('Rsync binary path', I('rsync', 200) + B('Browse…'))),
        'Editor': P(H('Editor Settings') + N('The editor uses the terminal font family, size and colours from the Font and Colors tabs.') + R('Cursor Style', S('BLOCK', 140)) + R('Cursor Color', SW('#f85149') + ' Red') + N('BLOCK: wide cursor (2 px) · LINE: thin vertical line (1 px) · UNDERSCORE: medium width (1.5 px)')),
        'Snippet Editor': P(H('Snippet Editor Settings') + N('Override the terminal and editor defaults for the Snippet Manager and Edit dialog. Empty or 0 inherits.') + R('Font Size', I('0 (inherit)', 110)) + R('Foreground / Background', SW('#d4d4d4') + ' ' + SW('#1e1e1e')) + R('AI completion shortcut', I('Shift+Tab', 120) + B('Reset')) + C(true, 'Keep a pre-warmed editor ready') + R('Stored analyses per snippet', I('5', 70)) + R('Stored script size', S('1 MB (default)', 160))),
        'Language': P(R('Select Language', S('English', 200)) + N('The language setting takes effect after an application restart.') + T([['Built in', '100%']], [['English · Deutsch · Italiano · Español · Português · Français · Hrvatski · Nederlands']])),
        'Translation': P(R('Translation API', S('DeepL', 200)) + R('API Key', I('••••••••••••••••', 260)) + B('Test API Connection') + R('Target language', S('Kiswahili (sw)', 200)) + B('Generate Language File') + H('Guide translation') + R('AI profile', S('Local · Qwen3-8B', 200)) + '<div class="km-r" style="gap:6px">' + B('Estimate duration') + B('Start translation') + '</div>'),
        'AI': P(C(true, '<b style="font-weight:500">Enable AI features</b>') + C(true, 'Show confirmation dialog before sending AI requests') + C(true, 'Enable AI Agent execution') + C(false, 'Ask before the AI Agent changes the target system') + R('Agent command name', I('agent', 120)) + R('AI agent task target', S('Terminal window')) + R('Default profile', S('Claude Sonnet')) + R('Security-check profile', S('Use default profile', 200)) + R('AI chat code blocks in the terminal', S('Insert and Run')) + R('Maximum alternative solutions', I('3', 70)))
      },
      d: {
        en: { 'Font': 'terminal font family and size, with a live preview.', 'Colors': 'text, background, cursor, selection and the sixteen ANSI colours.', 'Themes': 'fifteen built-in colour themes with live preview, every one editable.', 'Appearance': 'app design, UI scaling and the chat colour profile.', 'Terminal': 'size, scrollback, encoding, keyword highlighting, links, paste protection, shell integration and agent detection.', 'Video': 'record terminal sessions, optionally with colours.', 'Backup': 'how many backups to keep. They are always encrypted, by password or GPG.', 'Logging': 'log folder and retention, plus the session-journal defaults.', 'Export': 'watermark and footer for exported PDFs, journals and chats.', 'Updates': 'automatic update checks and how often they run.', 'Window': 'window geometry, tab behaviour and session restore at startup.', 'Keyboard': 'every shortcut, searchable and rebindable.', 'Resources': 'how much memory and CPU korTTY’s runtime may claim.', 'Security': 'master password, startup lock and the default session isolation.', 'Privacy': 'anonymous usage statistics: opt-in, with exactly what is and isn’t collected.', 'SFTP Manager': 'auto-close, parallel transfers, conflict handling, ZIP defaults and the rsync binary.', 'Editor': 'cursor style and colour of the built-in editor.', 'Snippet Editor': 'its own font and colours, the AI completion shortcut and stored analyses.', 'Language': 'the interface language; eight are built in.', 'Translation': 'generate language files and translate the user guide.', 'AI': 'master switches for AI and the agent, confirmations, default and security-check profiles.' },
        de: { 'Font': 'Schriftart und -größe des Terminals, mit Live-Vorschau.', 'Colors': 'Text, Hintergrund, Cursor, Auswahl und die sechzehn ANSI-Farben.', 'Themes': 'fünfzehn eingebaute Farbthemen mit Live-Vorschau, jedes editierbar.', 'Appearance': 'App-Design, UI-Skalierung und das Farbprofil des Chats.', 'Terminal': 'Größe, Scrollback, Kodierung, Schlüsselwort-Hervorhebung, Links, Einfügeschutz, Shell-Integration und Agent-Erkennung.', 'Video': 'Terminalsitzungen aufzeichnen, auf Wunsch mit Farben.', 'Backup': 'wie viele Backups bleiben. Sie sind immer verschlüsselt, per Passwort oder GPG.', 'Logging': 'Log-Ordner und Aufbewahrung, dazu die Vorgaben fürs Sitzungsjournal.', 'Export': 'Wasserzeichen und Fußzeile für exportierte PDFs, Journale und Chats.', 'Updates': 'automatische Update-Prüfung und ihr Intervall.', 'Window': 'Fenstergeometrie, Tab-Verhalten und Sitzungswiederherstellung beim Start.', 'Keyboard': 'jedes Tastenkürzel, durchsuchbar und neu belegbar.', 'Resources': 'wie viel Speicher und CPU korTTYs Runtime beanspruchen darf.', 'Security': 'Master-Passwort, Startsperre und die Standard-Sitzungsisolation.', 'Privacy': 'anonyme Nutzungsstatistik: Opt-in, mit genauer Liste, was erfasst wird und was nicht.', 'SFTP Manager': 'Auto-Schließen, parallele Transfers, Konfliktverhalten, ZIP-Vorgaben und das rsync-Programm.', 'Editor': 'Cursorform und -farbe des eingebauten Editors.', 'Snippet Editor': 'eigene Schrift und Farben, das Kürzel für KI-Vervollständigung und gespeicherte Analysen.', 'Language': 'die Oberflächensprache; acht sind eingebaut.', 'Translation': 'Sprachdateien erzeugen und das Benutzerhandbuch übersetzen.', 'AI': 'Hauptschalter für KI und Agent, Bestätigungen, Standard- und Sicherheitsprüf-Profil.' }
      }
    }
  };
  const TSTEP = 4.5;
  const TOUR = new WeakMap();
  const setupTour = (km) => {
    const cfg = TOURS[km.dataset.tour], own = km.dataset.own; if (!cfg || km.querySelector('.km-panes')) return;
    const tabs = km.querySelector('.km-tabs');
    tabs.innerHTML = cfg.tabs.map(t => '<span class="km-tab' + (t === own ? ' on' : '') + '" data-tab="' + t + '">' + t + '</span>').join('');
    const panes = document.createElement('div'); panes.className = 'km-panes';
    const ownP = document.createElement('div'); ownP.className = 'km-pane-own';
    let n = tabs.nextSibling; const move = [];
    while (n) { if (n.nodeType === 1 && (n.classList.contains('km-bar') || n.classList.contains('km-foot'))) break; move.push(n); n = n.nextSibling; }
    move.forEach(x => ownP.appendChild(x));
    panes.appendChild(ownP);
    cfg.tabs.forEach(t => { if (t === own) return; const p = document.createElement('div'); p.className = 'km-pane-t'; p.dataset.pane = t; p.innerHTML = cfg.pane[t]; panes.appendChild(p); });
    tabs.after(panes);
    if (km.dataset.tour === 'set') { const nv = document.createElement('div'); nv.className = 'km-snav'; nv.innerHTML = '<span class="km-btn">◀</span><span data-k="snav"></span><span class="km-btn">▶</span>'; tabs.before(nv); }
    const cap = document.createElement('div'); cap.className = 'km-tcap'; cap.innerHTML = '<span class="n"></span><b></b><span class="d"></span>'; km.appendChild(cap);
  };
  const pick = (km, tab) => { km.__manual = { tab, until: performance.now() + 25000 }; km.__tab = null; };
  const bindTabs = (km) => {
    if (km.__b) return; km.__b = 1;
    km.addEventListener('click', (e) => {
      const cfg = TOURS[km.dataset.tour];
      const tb = e.target.closest('.km-tab');
      const nv = e.target.closest('.km-snav .km-btn');
      if (!tb && !nv) return;
      e.stopPropagation();
      if (km.dataset.mock === 'aa' && tb) { clock.aa = tb.dataset.k === 'tb2' ? 12 : 0.01; return; }
      if (!cfg) return;
      if (tb && tb.dataset.tab) return pick(km, tb.dataset.tab);
      if (nv) { const all = nv.parentElement.querySelectorAll('.km-btn'); const i = cfg.tabs.indexOf(km.__tab || km.dataset.own);
        pick(km, cfg.tabs[(i + (nv === all[0] ? -1 : 1) + cfg.tabs.length) % cfg.tabs.length]); }
    });
  };
  roots().forEach(k => { setupTour(k); bindTabs(k); });
  const lbb = () => { const lb = document.getElementById('lightbox'); if (!lb) return setTimeout(lbb, 300);
    new MutationObserver(() => { const k = lb.querySelector('.km'); if (k) bindTabs(k); }).observe(lb, { childList: true, subtree: true }); };
  lbb();
  const tourFrame = (km, t, dur) => {
    const cfg = TOURS[km.dataset.tour], own = km.dataset.own, others = cfg.tabs.filter(x => x !== own);
    let name = t < dur ? own : others[Math.min(others.length - 1, Math.floor((t - dur) / TSTEP))];
    const man = km.__manual;
    if (man && performance.now() < man.until) name = man.tab; else km.__manual = null;
    if (km.__tab === name) return name === own;
    km.__tab = name;
    km.querySelectorAll('.km-tab').forEach(x => tog(x, 'on', x.dataset.tab === name));
    const on = km.querySelector('.km-tab.on'), tb = km.querySelector('.km-tabs');
    if (on && tb) tb.scrollLeft = Math.max(0, on.offsetLeft - 60);
    tog(km.querySelector('.km-pane-own'), 'away', name !== own);
    km.querySelectorAll('.km-pane-t').forEach(p => tog(p, 'on', p.dataset.pane === name));
    const lang = (document.documentElement.lang || 'en').slice(0, 2), d = cfg.d[lang] || cfg.d.en;
    const i = cfg.tabs.indexOf(name);
    const cap = km.querySelector('.km-tcap');
    cap.querySelector('.n').textContent = (i + 1) + ' / ' + cfg.tabs.length;
    cap.querySelector('b').textContent = name;
    cap.querySelector('.d').textContent = d[name] || cfg.d.en[name];
    txt($(km, 'snav'), name + ' (' + (i + 1) + '/' + cfg.tabs.length + ')');
    tog(km.closest('.shot-live'), 'touring', name !== own);
    return name === own;
  };
  document.addEventListener('kortty:lang', () => roots().forEach(r => { r.__tab = null; }));

  const clock = {}; let last = performance.now(), lay = 0;
  setInterval(() => {
    const now = performance.now(), dt = Math.min(0.5, (now - last) / 1000); last = now;
    const lb = lbOpen(), all = roots();
    for (const k in MOCKS) {
      const rs = all.filter(r => r.dataset.mock === k); if (!rs.length) continue;
      const active = rs.some(r => (lb && r.closest('#lightbox')) || (!lb && vis.get(r)));
      if (!active) continue;
      clock[k] = (clock[k] || 0) + dt;
      const [dur, f] = MOCKS[k];
      rs.forEach(r => { try {
        if (r.dataset.tour) {
          const cfg = TOURS[r.dataset.tour], total = dur + (cfg.tabs.length - 1) * TSTEP, t = clock[k] % total;
          if (tourFrame(r, t, dur)) f(r, t % dur);
        } else f(r, clock[k] % dur);
      } catch (e) { console.warn('mock', k, e); } });
    }
    if ((lay += dt) > 1) { lay = 0; all.forEach(r => { if (vis.get(r) || r.closest('#lightbox')) layout(r); }); }
  }, 100);
})();
