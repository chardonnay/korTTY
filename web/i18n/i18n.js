// korTTY landing i18n: EN inline (fallback), other languages fetched from i18n/<lang>.json.
(() => {
  const LANGS = ['en','de','es','fr','hr','it','nl','pt'];
  const KEY = 'kortty-lang';
  const orig = {};
  document.querySelectorAll('[data-i18n]').forEach(el => { orig[el.dataset.i18n] = el.innerHTML; });
  const cache = {};
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
    document.querySelectorAll('[data-i18n]').forEach(el => {
      const k = el.dataset.i18n;
      if (orig[k] !== undefined) el.innerHTML = (dict && dict[k]) || orig[k];
    });
    document.documentElement.lang = lang;
    const sel = document.getElementById('lang-sel');
    if (sel) sel.value = lang;
    if (save) { try { localStorage.setItem(KEY, lang); } catch (_) {} }
  }
  let saved = null; try { saved = localStorage.getItem(KEY); } catch (_) {}
  const detected = (navigator.languages || [navigator.language || 'en'])
    .map(l => String(l).slice(0, 2).toLowerCase()).find(l => LANGS.includes(l)) || 'en';
  setLang(saved || detected, false);
  const sel = document.getElementById('lang-sel');
  if (sel) sel.addEventListener('change', e => setLang(e.target.value, true));
})();
