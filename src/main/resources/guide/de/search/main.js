/* korTTY override of MkDocs' bundled search/main.js.
   Upstream verbatim except where marked KORTTY: the guide is bundled into the app and
   loaded from a jar:/file: origin, where new Worker() and XMLHttpRequest are both
   blocked. <script> tags are not, so the offline path uses those instead. The
   http(s) worker path (GitHub Pages) is left exactly as upstream ships it. */

function getSearchTermFromLocation() {
  var sPageURL = window.location.search.substring(1);
  var sURLVariables = sPageURL.split('&');
  for (var i = 0; i < sURLVariables.length; i++) {
    var sParameterName = sURLVariables[i].split('=');
    if (sParameterName[0] == 'q') {
      return decodeURIComponent(sParameterName[1].replace(/\+/g, '%20'));
    }
  }
}

function joinUrl (base, path) {
  if (path.substring(0, 1) === "/") {
    // path starts with `/`. Thus it is absolute.
    return path;
  }
  if (base.substring(base.length-1) === "/") {
    // base ends with `/`
    return base + path;
  }
  return base + "/" + path;
}

function escapeHtml (value) {
  return value.replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function formatResult (location, title, summary) {
  return '<article><h3><a href="' + joinUrl(base_url, location) + '">'+ escapeHtml(title) + '</a></h3><p>' + escapeHtml(summary) +'</p></article>';
}

function displayResults (results) {
  var search_results = document.getElementById("mkdocs-search-results");
  while (search_results.firstChild) {
    search_results.removeChild(search_results.firstChild);
  }
  if (results.length > 0){
    for (var i=0; i < results.length; i++){
      var result = results[i];
      var html = formatResult(result.location, result.title, result.summary);
      search_results.insertAdjacentHTML('beforeend', html);
    }
  } else {
    var noResultsText = search_results.getAttribute('data-no-results-text');
    if (!noResultsText) {
      noResultsText = "No results found";
    }
    search_results.insertAdjacentHTML('beforeend', '<p>' + noResultsText + '</p>');
  }
}

function doSearch () {
  var query = document.getElementById('mkdocs-search-query').value;
  if (query.length > min_search_length) {
    // KORTTY: branch on the worker we actually managed to construct, not on the
    // presence of the Worker API. On a jar:/file: origin the API exists but the
    // constructor throws, so upstream's `!window.Worker` test takes the worker
    // branch and calls postMessage on null.
    if (!searchWorker) {
      displayResults(search(query));
    } else {
      searchWorker.postMessage({query: query});
    }
  } else {
    // Clear results for short queries
    displayResults([]);
  }
}

function initSearch () {
  var search_input = document.getElementById('mkdocs-search-query');
  if (search_input) {
    search_input.addEventListener("keyup", doSearch);
  }
  var term = getSearchTermFromLocation();
  if (term) {
    search_input.value = term;
    doSearch();
  }
}

function onWorkerMessage (e) {
  if (e.data.allowSearch) {
    initSearch();
  } else if (e.data.results) {
    var results = e.data.results;
    displayResults(results);
  } else if (e.data.config) {
    min_search_length = e.data.config.min_search_length-1;
  }
}

// KORTTY: upstream bootstraps the index as soon as this script runs. The index is
// ~1.2 MB and building the lunr structure costs real time, so on all 57 pages of an
// embedded guide that is pure waste — the reader opens the search box on almost none
// of them. The box is armed immediately but the index is only fetched the first time
// someone focuses or types in it (or lands on a ?q= deep link).
var searchWorker = null;
var searchBootstrapped = false;

function bootstrapSearch () {
  if (searchBootstrapped) {
    return;
  }
  searchBootstrapped = true;
  // KORTTY: a jar:/file: page HAS window.Worker, but constructing one throws (opaque
  // origin), so feature-detection alone never reaches the main-thread fallback.
  // Probe by actually constructing it.
  if (window.Worker) {
    try {
      searchWorker = new Worker(joinUrl(base_url, "search/worker.js"));
    } catch (e) {
      console.log('Web Worker blocked for this origin, searching in the main thread');
      searchWorker = null;
    }
  }
  if (searchWorker) {
    searchWorker.onmessage = onWorkerMessage;
    searchWorker.postMessage({init: true});
    return;
  }
  // KORTTY: a <script> tag rather than $.getScript, whose same-origin path is XHR
  // and is therefore blocked on jar:/file:.
  var script = document.createElement('script');
  script.src = joinUrl(base_url, "search/worker.js");
  script.onload = function () {
    init();
    window.postMessage = function (msg) {
      onWorkerMessage({data: msg});
    };
  };
  script.onerror = function () { console.error('Could not load worker.js'); };
  document.head.appendChild(script);
}

// KORTTY: arm the box before the index exists so the first keystroke is what pays
// for it. initSearch() re-binds keyup once the index is ready and replays the value.
function armSearchBox () {
  var input = document.getElementById('mkdocs-search-query');
  if (!input) {
    return;
  }
  input.addEventListener('focus', bootstrapSearch);
  input.addEventListener('keyup', bootstrapSearch);
  if (getSearchTermFromLocation()) {
    bootstrapSearch();
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', armSearchBox);
} else {
  armSearchBox();
}
