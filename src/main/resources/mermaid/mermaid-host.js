(function () {
  "use strict";

  const SVG_NS = "http://www.w3.org/2000/svg";
  const MAX_RASTER_PIXELS = 16 * 1024 * 1024;
  const MAX_MERMAID_TEXT_SIZE = 34 * 1024;
  const target = document.getElementById("render-target");

  // JavaFX 21 WebKit exposes CSSStyleSheet as a non-constructable host object. Mermaid 11.16
  // uses a detached sheet only as a small CSS string builder, so provide that subset when the
  // constructable-stylesheet API is absent. Nothing is adopted into the document from this shim.
  try {
    new CSSStyleSheet();
  } catch (error) {
    class DetachedStyleSheet {
      constructor() {
        this.cssRules = [];
      }

      insertRule(rule, index) {
        const offset = Number.isInteger(index) ? index : this.cssRules.length;
        this.cssRules.splice(offset, 0, { cssText: String(rule) });
        return offset;
      }

      replaceSync(css) {
        this.cssRules = [{ cssText: String(css) }];
      }
    }
    globalThis.CSSStyleSheet = DetachedStyleSheet;
  }

  function api() {
    const namespace = globalThis.__esbuild_esm_mermaid_nm;
    const instance = globalThis.mermaid
      || (namespace && namespace.mermaid && namespace.mermaid.default);
    if (!instance) throw new Error("Bundled Mermaid API did not initialize.");
    return instance;
  }

  function config(dark, background) {
    const bg = background || (dark ? "#111827" : "#FFFFFF");
    return {
      startOnLoad: false,
      securityLevel: "strict",
      deterministicIds: true,
      deterministicIDSeed: "kortty-mermaid",
      htmlLabels: false,
      suppressErrorRendering: true,
      // The Java boundary enforces 32 KiB on caller input. The small fixed allowance is for the
      // four trusted semantic class definitions appended to generated korTTY flowcharts.
      maxTextSize: MAX_MERMAID_TEXT_SIZE,
      maxEdges: 300,
      secure: [
        "secure", "securityLevel", "startOnLoad", "maxTextSize", "maxEdges",
        "htmlLabels", "deterministicIds", "deterministicIDSeed", "dompurifyConfig"
      ],
      theme: dark ? "dark" : "base",
      themeVariables: {
        background: bg,
        mainBkg: bg,
        fontFamily: "Arial, Helvetica, sans-serif"
      },
      flowchart: { useMaxWidth: false, htmlLabels: false }
    };
  }

  function usefulError(error) {
    if (!error) return "Mermaid rendering failed.";
    const message = error.str || error.message || String(error);
    return String(message).replace(/\s+/g, " ").trim().slice(0, 1000);
  }

  function sanitizeCssText(css) {
    return String(css || "")
      .replace(/@import[^;{}]*(?:;|$)/gi, "")
      .replace(/url\s*\(([^)]*)\)/gi, function (match, rawValue) {
        const trimmed = String(rawValue || "").trim();
        const unquoted = trimmed.replace(/^(['"])(.*)\1$/, "$2").trim();
        return unquoted.startsWith("#") ? "url(" + trimmed + ")" : "none";
      });
  }

  function removeUnsafeSvgContent(svg) {
    svg.querySelectorAll("script,foreignObject,image,iframe,object,embed,a").forEach(node => node.remove());
    svg.querySelectorAll("style").forEach(node => {
      node.textContent = sanitizeCssText(node.textContent);
    });
    svg.querySelectorAll("[style]").forEach(node => {
      const sanitized = sanitizeCssText(node.getAttribute("style"));
      if (sanitized.trim()) node.setAttribute("style", sanitized);
      else node.removeAttribute("style");
    });
    svg.querySelectorAll("*").forEach(node => {
      Array.from(node.attributes || []).forEach(attribute => {
        const name = attribute.name.toLowerCase();
        const value = attribute.value || "";
        if (name.startsWith("on") || name === "src" || name === "srcset" || name === "poster"
            || ((name === "href" || name === "xlink:href") && !value.trim().startsWith("#"))) {
          node.removeAttribute(attribute.name);
        }
      });
    });
  }

  function dimensions(svg) {
    const viewBox = svg.viewBox && svg.viewBox.baseVal;
    let x = viewBox && Number.isFinite(viewBox.x) ? viewBox.x : 0;
    let y = viewBox && Number.isFinite(viewBox.y) ? viewBox.y : 0;
    let width = viewBox && viewBox.width > 0 ? viewBox.width : 0;
    let height = viewBox && viewBox.height > 0 ? viewBox.height : 0;
    if (width <= 0 || height <= 0) {
      const box = svg.getBBox();
      x = box.x;
      y = box.y;
      width = Math.max(1, box.width);
      height = Math.max(1, box.height);
      svg.setAttribute("viewBox", [x, y, width, height].join(" "));
    }
    svg.setAttribute("width", String(width));
    svg.setAttribute("height", String(height));
    svg.setAttribute("xmlns", SVG_NS);
    return { x, y, width, height };
  }

  function insertBackground(svg, box, color) {
    const rect = document.createElementNS(SVG_NS, "rect");
    rect.setAttribute("data-kortty-background", "true");
    rect.setAttribute("x", String(box.x));
    rect.setAttribute("y", String(box.y));
    rect.setAttribute("width", String(box.width));
    rect.setAttribute("height", String(box.height));
    rect.setAttribute("fill", color || "#FFFFFF");
    svg.insertBefore(rect, svg.firstChild);
  }

  function sourceNodeId(node) {
    const dataId = node.getAttribute("data-id");
    if (dataId) return dataId;
    const id = node.getAttribute("id") || "";
    const match = /(?:^|-)flowchart-(.+)-\d+$/.exec(id);
    return match ? match[1] : id;
  }

  function nodeBounds(svg, viewBox) {
    return Array.from(svg.querySelectorAll("g.node")).map(node => {
      const box = node.getBBox();
      let matrix = node.getCTM();
      const rootMatrix = svg.getCTM();
      if (matrix && rootMatrix && typeof rootMatrix.inverse === "function"
          && typeof rootMatrix.multiply === "function") {
        matrix = rootMatrix.inverse().multiply(matrix);
      }
      const corners = [
        [box.x, box.y],
        [box.x + box.width, box.y],
        [box.x, box.y + box.height],
        [box.x + box.width, box.y + box.height]
      ].map(point => matrix ? {
        x: matrix.a * point[0] + matrix.c * point[1] + matrix.e,
        y: matrix.b * point[0] + matrix.d * point[1] + matrix.f
      } : { x: point[0], y: point[1] });
      const minX = Math.min.apply(null, corners.map(point => point.x));
      const maxX = Math.max.apply(null, corners.map(point => point.x));
      const minY = Math.min.apply(null, corners.map(point => point.y));
      const maxY = Math.max.apply(null, corners.map(point => point.y));
      return {
        nodeId: sourceNodeId(node),
        label: String(node.textContent || "").replace(/\s+/g, " ").trim(),
        x: minX - viewBox.x,
        y: minY - viewBox.y,
        width: maxX - minX,
        height: maxY - minY
      };
    }).filter(item => item.nodeId && item.width > 0 && item.height > 0);
  }

  function rasterize(svgText, width, height) {
    return new Promise((resolve, reject) => {
      const pixels = Math.max(1, width * height);
      const scale = pixels > MAX_RASTER_PIXELS ? Math.sqrt(MAX_RASTER_PIXELS / pixels) : 1;
      const rasterWidth = Math.max(1, Math.round(width * scale));
      const rasterHeight = Math.max(1, Math.round(height * scale));
      const image = new Image();
      image.onload = function () {
        try {
          const canvas = document.createElement("canvas");
          canvas.width = rasterWidth;
          canvas.height = rasterHeight;
          const context = canvas.getContext("2d");
          context.drawImage(image, 0, 0, rasterWidth, rasterHeight);
          resolve(canvas.toDataURL("image/png").replace(/^data:image\/png;base64,/, ""));
        } catch (error) {
          reject(error);
        }
      };
      image.onerror = reject;
      image.src = "data:image/svg+xml;base64," + btoa(unescape(encodeURIComponent(svgText)));
    });
  }

  async function parse(requestId, source) {
    try {
      const renderer = api();
      renderer.initialize(config(false, "#FFFFFF"));
      const parsed = await renderer.parse(source, { suppressErrors: false });
      javaBridge.parseSucceeded(requestId, parsed && parsed.diagramType ? parsed.diagramType : "");
    } catch (error) {
      javaBridge.requestFailed(requestId, usefulError(error));
    }
  }

  async function render(requestId, source, dark, background, includePng) {
    try {
      const renderer = api();
      renderer.initialize(config(Boolean(dark), background));
      await renderer.parse(source, { suppressErrors: false });
      const rendered = await renderer.render("kortty-mermaid-" + requestId, source);
      target.innerHTML = rendered.svg;
      const svg = target.querySelector("svg");
      if (!svg) throw new Error("Mermaid did not produce an SVG document.");
      removeUnsafeSvgContent(svg);
      const box = dimensions(svg);
      insertBackground(svg, box, background || (dark ? "#111827" : "#FFFFFF"));
      const bounds = nodeBounds(svg, box);
      const svgText = new XMLSerializer().serializeToString(svg);
      const png = includePng ? await rasterize(svgText, box.width, box.height) : "";
      javaBridge.renderSucceeded(
        requestId,
        svgText,
        png,
        box.width,
        box.height,
        JSON.stringify(bounds));
      target.replaceChildren();
    } catch (error) {
      target.replaceChildren();
      javaBridge.requestFailed(requestId, usefulError(error));
    }
  }

  window.korttyMermaid = { parse, render };
}());
