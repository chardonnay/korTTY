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
    const cursor = document.createElement('span');
    cursor.className = 'h1-cursor';
    cursor.setAttribute('aria-hidden', 'true');

    let si = 0, ci = 0, span = null;
    const step = () => {
      if (token !== run) return; // superseded by a language change
      if (si >= segs.length) { live.appendChild(cursor); return; }
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
  const sel = document.getElementById('emu-sel');
  if (!sel) return;
  const bodies = document.querySelectorAll('.emu-body');
  sel.addEventListener('change', () => {
    bodies.forEach(b => b.classList.toggle('on', b.dataset.emu === sel.value));
  });
})();
