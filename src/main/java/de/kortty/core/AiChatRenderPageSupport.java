package de.kortty.core;

import com.google.gson.Gson;

/**
 * Builds the self-contained HTML pages the AI chat uses to render Mermaid diagrams and LaTeX
 * math in a WebView. The pages reference the bundled libraries ({@code mermaid.min.js},
 * {@code tex-svg.js}) as relative siblings, so they must be written next to the extracted
 * library files (see the chat-render resource bundle in the UI layer).
 *
 * <p>The untrusted diagram/math source is embedded as a JSON string literal (never interpolated
 * into markup), Mermaid runs with {@code securityLevel: 'strict'}, and the page reports its
 * outcome through {@code window.korttyRenderState} ({@code pending} → {@code ok} /
 * {@code error: ...}) so the host can poll for success.
 */
public final class AiChatRenderPageSupport {

    /** JS global the pages set to signal rendering progress to the Java host. */
    public static final String RENDER_STATE_EXPRESSION = "window.korttyRenderState";

    private static final Gson GSON = new Gson();

    private AiChatRenderPageSupport() {
    }

    /** Page rendering one Mermaid diagram from the given source. */
    public static String buildMermaidHtml(String mermaidSource) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>"
            + "html,body{margin:0;padding:8px;background:#ffffff;}"
            + "#out svg{max-width:100%;height:auto;display:block;margin:0 auto;}"
            + "</style></head><body><div id=\"out\"></div>"
            + "<script src=\"mermaid.min.js\"></script>"
            + "<script>"
            + "window.korttyRenderState='pending';"
            + "try{"
            + "var source=" + toJsStringLiteral(mermaidSource) + ";"
            + "mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:'neutral'});"
            + "mermaid.render('kortty-mermaid',source).then(function(r){"
            + "document.getElementById('out').innerHTML=r.svg;"
            + "window.korttyRenderState='ok';"
            + "}).catch(function(err){window.korttyRenderState='error: '+err;});"
            + "}catch(err){window.korttyRenderState='error: '+err;}"
            + "</script></body></html>";
    }

    /** Page typesetting one display-math TeX expression via MathJax SVG output. */
    public static String buildMathHtml(String texSource) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>"
            + "html,body{margin:0;padding:8px;background:#ffffff;}"
            + "#out{display:flex;justify-content:center;}"
            + "</style>"
            + "<script>"
            + "window.korttyRenderState='pending';"
            + "window.MathJax={startup:{typeset:false},svg:{fontCache:'none'}};"
            + "</script>"
            + "<script src=\"tex-svg.js\"></script>"
            + "</head><body><div id=\"out\"></div>"
            + "<script>"
            + "try{"
            + "var source=" + toJsStringLiteral(texSource) + ";"
            + "MathJax.startup.promise.then(function(){"
            + "return MathJax.tex2svgPromise(source,{display:true});"
            + "}).then(function(node){"
            + "document.getElementById('out').appendChild(node);"
            + "window.korttyRenderState='ok';"
            + "}).catch(function(err){window.korttyRenderState='error: '+err;});"
            + "}catch(err){window.korttyRenderState='error: '+err;}"
            + "</script></body></html>";
    }

    /**
     * Embeds untrusted text as a JS string literal. JSON escaping covers quotes/newlines; the
     * additional {@code </} escape prevents a literal {@code </script>} inside the payload from
     * terminating the surrounding script element.
     */
    static String toJsStringLiteral(String value) {
        return GSON.toJson(value != null ? value : "").replace("</", "<\\/");
    }
}
