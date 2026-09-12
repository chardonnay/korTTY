/* korTTY override of MkDocs' bundled search/worker.js.
   Upstream verbatim except where marked KORTTY: the guide is bundled into the app and
   loaded from a jar:/file: origin, where new Worker() and XMLHttpRequest are both
   blocked. <script> tags are not, so the offline path uses those instead. The
   http(s) worker path (GitHub Pages) is left exactly as upstream ships it. */

var IS_WORKER = 'function' === typeof importScripts;
// KORTTY: upstream hardcodes the absolute '/search/', which cannot resolve under a
// jar:/file: origin (nor with use_directory_urls:false). Derive it from base_url instead.
var base_path = IS_WORKER
  ? '.'
  : ((typeof base_url === 'string' ? base_url.replace(/\/$/, '') : '.') + '/search');
var allowSearch = false;
var index;
var documents = {};
var lang = ['en'];
var data;

function getScript(script, callback) {
  // KORTTY: <script> tag instead of $.getScript (XHR, blocked on jar:/file:).
  var s = document.createElement('script');
  s.src = base_path + '/' + script;
  s.onload = function () { callback(); };
  s.onerror = function () { console.error('search: could not load ' + s.src); };
  document.head.appendChild(s);
}

function getScriptsInOrder(scripts, callback) {
  if (scripts.length === 0) {
    callback();
    return;
  }
  getScript(scripts[0], function() {
    getScriptsInOrder(scripts.slice(1), callback);
  });
}

function loadScripts(urls, callback) {
  if( 'function' === typeof importScripts ) {
    importScripts.apply(null, urls);
    callback();
  } else {
    getScriptsInOrder(urls, callback);
  }
}

function onIndexReady () {
  var scriptsToLoad = ['lunr.js'];
  if (data.config && data.config.lang && data.config.lang.length) {
    lang = data.config.lang;
  }
  if (lang.length > 1 || lang[0] !== "en") {
    scriptsToLoad.push('lunr.stemmer.support.js');
    if (lang.length > 1) {
      scriptsToLoad.push('lunr.multi.js');
    }
    if (lang.includes("ja") || lang.includes("jp")) {
      scriptsToLoad.push('tinyseg.js');
    }
    for (var i=0; i < lang.length; i++) {
      if (lang[i] != 'en') {
        scriptsToLoad.push(['lunr', lang[i], 'js'].join('.'));
      }
    }
  }
  loadScripts(scriptsToLoad, onScriptsLoaded);
}

function onScriptsLoaded () {
  console.log('All search scripts loaded, building Lunr index...');
  if (data.config && data.config.separator && data.config.separator.length) {
    lunr.tokenizer.separator = new RegExp(data.config.separator);
  }

  if (data.index) {
    index = lunr.Index.load(data.index);
    data.docs.forEach(function (doc) {
      documents[doc.location] = doc;
    });
    console.log('Lunr pre-built index loaded, search ready');
  } else {
    index = lunr(function () {
      if (lang.length === 1 && lang[0] !== "en" && lunr[lang[0]]) {
        this.use(lunr[lang[0]]);
      } else if (lang.length > 1) {
        this.use(lunr.multiLanguage.apply(null, lang));  // spread operator not supported in all browsers: https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Operators/Spread_operator#Browser_compatibility
      }
      this.field('title');
      this.field('text');
      this.ref('location');

      for (var i=0; i < data.docs.length; i++) {
        var doc = data.docs[i];
        this.add(doc);
        documents[doc.location] = doc;
      }
    });
    console.log('Lunr index built, search ready');
  }
  allowSearch = true;
  postMessage({config: data.config});
  postMessage({allowSearch: allowSearch});
}

function init () {
  if (IS_WORKER) {
    // Real worker (http/https): keep upstream's XHR, which works there.
    var oReq = new XMLHttpRequest();
    oReq.addEventListener("load", function () {
      data = JSON.parse(this.responseText);
      onIndexReady();
    });
    oReq.open("GET", 'search_index.json');
    oReq.send();
    return;
  }
  // KORTTY: offline path. search_index.js is the same index wrapped as
  // `var __index = {...}` — the very convention GuideSearchIndexTranslator already
  // writes for runtime-translated languages — so a <script> tag replaces the XHR.
  // Loaded lazily on first search: the index is ~1.2 MB and must not ride along on
  // all 57 pages.
  var s = document.createElement('script');
  s.src = base_path + '/search_index.js';
  s.onload = function () {
    data = (typeof __index !== 'undefined') ? __index : null;
    if (!data) { console.error('search: search_index.js defined no __index'); return; }
    onIndexReady();
  };
  s.onerror = function () { console.error('search: could not load ' + s.src); };
  document.head.appendChild(s);
}

function search (query) {
  if (!allowSearch) {
    console.error('Assets for search still loading');
    return;
  }

  var resultDocuments = [];
  var results = index.search(query);
  for (var i=0; i < results.length; i++){
    var result = results[i];
    doc = documents[result.ref];
    doc.summary = doc.text.substring(0, 200);
    resultDocuments.push(doc);
  }
  return resultDocuments;
}

if( 'function' === typeof importScripts ) {
  onmessage = function (e) {
    if (e.data.init) {
      init();
    } else if (e.data.query) {
      postMessage({ results: search(e.data.query) });
    } else {
      console.error("Worker - Unrecognized message: " + e);
    }
  };
}
