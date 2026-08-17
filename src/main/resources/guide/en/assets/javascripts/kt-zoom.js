/* ===========================================================================
   korTTY Guide — click-to-enlarge for screenshots and diagrams.

   Written by hand instead of pulling in mkdocs-glightbox: the guide has to run
   from a jar:/file: origin inside korTTY's WebView with ZERO runtime fetches
   (scripts/build-docs-site.py asserts it), and one small self-contained file is
   easier to keep offline-safe than a plugin's asset pipeline. It also has to
   survive JavaFX WebKit, so: no modules, no optional chaining, no <dialog>.

   Scope is the same set the stylesheet frames — images under assets/screenshots
   and assets/diagrams. The hero logo, badges and icons stay untouched.
   =========================================================================== */
(function () {
  "use strict";

  var ZOOMABLE =
    ".md-typeset img[src*='assets/screenshots/'], .md-typeset img[src*='assets/diagrams/']";

  /* Multiples of the fitted size. 1 = "as large as the window allows", which is
     already a big step up from the in-page thumbnail, so the ladder starts there
     and climbs to 4x for reading a single settings row in a full-window shot. */
  var LEVELS = [1, 1.5, 2, 3, 4];

  var TEXT = {
    en: {
      open: "Click to enlarge",
      in: "Zoom in",
      out: "Zoom out",
      reset: "Fit to window",
      close: "Close (Esc)"
    },
    de: {
      open: "Zum Vergrößern klicken",
      in: "Vergrößern",
      out: "Verkleinern",
      reset: "An Fenster anpassen",
      close: "Schließen (Esc)"
    }
  };

  function strings() {
    var lang = (document.documentElement.getAttribute("lang") || "en").slice(0, 2);
    return TEXT[lang] || TEXT.en;
  }

  var t = strings();
  var overlay = null;
  var stage = null;
  var image = null;
  var caption = null;
  var levelButton = null;
  var levelIndex = 0;
  var fittedWidth = 0;
  var lastFocused = null;

  function button(action, label, extraClass) {
    var el = document.createElement("button");
    el.type = "button";
    el.className = "kt-zoom__btn" + (extraClass ? " " + extraClass : "");
    el.setAttribute("data-kt-zoom", action);
    el.setAttribute("title", label);
    el.setAttribute("aria-label", label);
    return el;
  }

  /* Built once on the first click, not on every page load: most visits never
     open it, and the markup is identical for every image. */
  function buildOverlay() {
    overlay = document.createElement("div");
    overlay.className = "kt-zoom";
    overlay.setAttribute("role", "dialog");
    overlay.setAttribute("aria-modal", "true");
    overlay.hidden = true;

    var bar = document.createElement("div");
    bar.className = "kt-zoom__bar";
    var out = button("out", t.out);
    out.textContent = "−";
    levelButton = button("reset", t.reset, "kt-zoom__level");
    var into = button("in", t.in);
    into.textContent = "+";
    var close = button("close", t.close, "kt-zoom__close");
    close.textContent = "×";
    bar.appendChild(out);
    bar.appendChild(levelButton);
    bar.appendChild(into);
    bar.appendChild(close);

    stage = document.createElement("div");
    stage.className = "kt-zoom__stage";
    image = document.createElement("img");
    image.className = "kt-zoom__img";
    image.alt = "";
    stage.appendChild(image);

    caption = document.createElement("p");
    caption.className = "kt-zoom__caption";

    overlay.appendChild(bar);
    overlay.appendChild(stage);
    overlay.appendChild(caption);
    document.body.appendChild(overlay);

    bar.addEventListener("click", function (event) {
      var target = event.target;
      while (target && target !== bar && !target.getAttribute("data-kt-zoom")) {
        target = target.parentNode;
      }
      var action = target && target.getAttribute ? target.getAttribute("data-kt-zoom") : null;
      if (action === "in") {
        setLevel(levelIndex + 1);
      } else if (action === "out") {
        setLevel(levelIndex - 1);
      } else if (action === "reset") {
        setLevel(0);
      } else if (action === "close") {
        close_();
      }
    });

    /* A click on the backdrop closes; a click on the picture zooms one step and
       wraps back to fit, so the whole thing is usable with one mouse button. */
    stage.addEventListener("click", function (event) {
      if (dragged) {
        return;
      }
      if (event.target === image) {
        setLevel(levelIndex + 1 < LEVELS.length ? levelIndex + 1 : 0);
      } else {
        close_();
      }
    });

    installPanning();
    stage.addEventListener("wheel", onWheel, { passive: false });
  }

  /* Dragging beats scrollbars for a picture: grab it and move. The threshold
     keeps a slightly shaky click from being swallowed as a drag. */
  var dragged = false;

  function installPanning() {
    var panning = false;
    var startX = 0;
    var startY = 0;
    var startLeft = 0;
    var startTop = 0;

    stage.addEventListener("mousedown", function (event) {
      if (event.button !== 0 || event.target !== image) {
        return;
      }
      panning = true;
      dragged = false;
      startX = event.clientX;
      startY = event.clientY;
      startLeft = stage.scrollLeft;
      startTop = stage.scrollTop;
      event.preventDefault();
    });
    document.addEventListener("mousemove", function (event) {
      if (!panning) {
        return;
      }
      var dx = event.clientX - startX;
      var dy = event.clientY - startY;
      if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
        dragged = true;
      }
      stage.scrollLeft = startLeft - dx;
      stage.scrollTop = startTop - dy;
    });
    document.addEventListener("mouseup", function () {
      panning = false;
      // Cleared after the click event that follows the drag has been handled.
      window.setTimeout(function () {
        dragged = false;
      }, 0);
    });
  }

  /* Ctrl/Cmd + wheel zooms, a plain wheel keeps scrolling the enlarged picture —
     the same split every image viewer uses, and it leaves korTTY's own
     Ctrl+wheel-free scrolling inside the WebView intact. */
  function onWheel(event) {
    if (!event.ctrlKey && !event.metaKey) {
      return;
    }
    event.preventDefault();
    setLevel(levelIndex + (event.deltaY < 0 ? 1 : -1));
  }

  function setLevel(index) {
    if (index < 0) {
      index = 0;
    }
    if (index > LEVELS.length - 1) {
      index = LEVELS.length - 1;
    }
    levelIndex = index;
    var level = LEVELS[levelIndex];
    if (levelIndex === 0) {
      // Back to "fit": hand sizing to the stylesheet's max-width/max-height.
      image.style.width = "";
      image.style.maxWidth = "";
      image.style.maxHeight = "";
      image.classList.remove("kt-zoom__img--zoomed");
    } else {
      image.style.maxWidth = "none";
      image.style.maxHeight = "none";
      image.style.width = Math.round(fittedWidth * level) + "px";
      image.classList.add("kt-zoom__img--zoomed");
    }
    levelButton.textContent = Math.round(level * 100) + " %";
    centerScroll();
  }

  /** Keeps the middle of the picture in view when it grows past the stage. */
  function centerScroll() {
    stage.scrollLeft = (stage.scrollWidth - stage.clientWidth) / 2;
    stage.scrollTop = (stage.scrollHeight - stage.clientHeight) / 2;
  }

  function open(source) {
    if (!overlay) {
      buildOverlay();
    }
    lastFocused = document.activeElement;
    image.src = source.currentSrc || source.src;
    image.alt = source.alt || "";
    caption.textContent = source.alt || "";
    overlay.hidden = false;
    document.body.classList.add("kt-zoom-open");
    // The fitted width can only be measured once the picture is laid out.
    var measure = function () {
      fittedWidth = image.getBoundingClientRect().width;
      setLevel(0);
    };
    if (image.complete && image.naturalWidth) {
      measure();
    } else {
      image.addEventListener("load", measure, { once: true });
    }
    overlay.querySelector(".kt-zoom__close").focus();
  }

  function close_() {
    if (!overlay || overlay.hidden) {
      return;
    }
    overlay.hidden = true;
    document.body.classList.remove("kt-zoom-open");
    // Drop the picture so a large screenshot is not kept decoded in the WebView.
    image.removeAttribute("src");
    if (lastFocused && lastFocused.focus) {
      lastFocused.focus();
    }
  }

  function isZoomable(node) {
    return node && node.matches && node.matches(ZOOMABLE);
  }

  /* Delegated, so images added after load (none today, but the search result
     preview and any future partial render) are covered without re-binding. */
  document.addEventListener("click", function (event) {
    if (!isZoomable(event.target)) {
      return;
    }
    event.preventDefault();
    open(event.target);
  });

  document.addEventListener("keydown", function (event) {
    if (overlay && !overlay.hidden) {
      if (event.key === "Escape") {
        close_();
      } else if (event.key === "+" || event.key === "=") {
        setLevel(levelIndex + 1);
      } else if (event.key === "-") {
        setLevel(levelIndex - 1);
      } else if (event.key === "0") {
        setLevel(0);
      }
      return;
    }
    // Enter/Space on a focused picture opens it — the keyboard path for the
    // tabindex the marking pass adds below.
    if ((event.key === "Enter" || event.key === " ") && isZoomable(event.target)) {
      event.preventDefault();
      open(event.target);
    }
  });

  /** Marks the zoomable pictures as interactive: cursor, tooltip, tab stop. */
  function markImages() {
    var images = document.querySelectorAll(ZOOMABLE);
    for (var i = 0; i < images.length; i++) {
      var img = images[i];
      if (img.getAttribute("data-kt-zoomable")) {
        continue;
      }
      img.setAttribute("data-kt-zoomable", "1");
      img.setAttribute("tabindex", "0");
      img.setAttribute("role", "button");
      if (!img.getAttribute("title")) {
        img.setAttribute("title", t.open);
      }
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", markImages);
  } else {
    markImages();
  }
})();
