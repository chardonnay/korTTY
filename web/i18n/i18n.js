// korTTY landing i18n: EN inline (fallback), other languages fetched from i18n/<lang>.json.
(() => {
  const LANGS = ['en','de','es','fr','hr','it','nl','pt'];
  const KEY = 'kortty-lang';
  const orig = {};
  document.querySelectorAll('[data-i18n]').forEach(el => { orig[el.dataset.i18n] = el.innerHTML; });
  const cache = {};
  // The guide is built next to the site (Pages: /guide/) — but not in a bare
  // checkout or preview. Probe once; fall back to the published copy so the
  // link never lands on a 404.
  const PUBLISHED = 'https://chardonnay.github.io/korTTY/guide/';
  let basePromise = null;
  function guideBase() {
    if (!basePromise) {
      basePromise = fetch('guide/en/index.html', { method: 'HEAD' })
        .then(r => (r.ok ? 'guide/' : PUBLISHED))
        .catch(() => PUBLISHED);
    }
    return basePromise;
  }
  async function setLang(lang, save) {
    if (!LANGS.includes(lang)) lang = 'en';
    let dict = null;
    if (lang !== 'en') {
      if (!(lang in cache)) {
        try { const r = await fetch('i18n/' + lang + '.json'); cache[lang] = r.ok ? await r.json() : null; }
        catch (_) { cache[lang] = null; }
      }
      dict = cache[lang];
      if (!dict) lang = 'en';
    }
    // Fresh lookup per step: writing a parent's innerHTML replaces nested [data-i18n]
    // children, so a NodeList captured up front would hold detached nodes.
    for (let i = 0, n = document.querySelectorAll('[data-i18n]').length; i < n; i++) {
      const el = document.querySelectorAll('[data-i18n]')[i];
      if (!el) break;
      const k = el.dataset.i18n;
      if (orig[k] !== undefined) el.innerHTML = (dict && dict[k]) || orig[k];
    }
    document.documentElement.lang = lang;
    // The built guide ships EN + DE only — everything else lands on EN.
    const gl = lang === 'de' ? 'de' : 'en';
    guideBase().then(base => {
      document.querySelectorAll('[data-guide]').forEach(a => { a.href = base + gl + '/index.html'; });
    });
    const sel = document.getElementById('lang-sel');
    if (sel) sel.value = lang;
    if (save) { try { localStorage.setItem(KEY, lang); } catch (_) {} }
    document.dispatchEvent(new CustomEvent('kortty:lang', { detail: { lang } }));
  }
  let saved = null; try { saved = localStorage.getItem(KEY); } catch (_) {}
  const detected = (navigator.languages || [navigator.language || 'en'])
    .map(l => String(l).slice(0, 2).toLowerCase()).find(l => LANGS.includes(l)) || 'en';
  setLang(saved || detected, false);
  const sel = document.getElementById('lang-sel');
  if (sel) sel.addEventListener('change', e => setLang(e.target.value, true));
})();
