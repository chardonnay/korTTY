package de.kortty.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt construction and answer post-processing for the guide's AI docs search. The model is
 * instructed to answer only from the provided excerpts and to cite them as markdown links whose
 * targets are copied verbatim from the excerpt's {@code Source:} line; {@link #sanitizeAnswer}
 * enforces that contract afterwards so citation navigation never depends on model discipline.
 */
public final class GuideAskPromptSupport {

    /** Custom scheme used for citation links inside the rendered answer HTML. */
    public static final String GUIDE_LINK_SCHEME = "kortty-guide:";

    private static final Pattern MARKDOWN_LINK =
        Pattern.compile("\\[([^\\[\\]]*)\\]\\(([^()\\s]+)\\)");
    // Conservative shape for bundled guide locations, e.g. "features/ai-tools.html#anchor".
    private static final Pattern GUIDE_LOCATION =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*\\.html(?:#[A-Za-z0-9._-]*)?");

    private GuideAskPromptSupport() {
    }

    public static String buildSystemPrompt(String answerLanguageDisplayName, String notFoundSentence) {
        return """
            You are the built-in documentation assistant of korTTY, an SSH terminal client.
            Answer the user's question using ONLY the numbered documentation excerpts provided. \
            Never use outside knowledge and never guess.
            Rules:
            - Answer in %s, regardless of the question's language.
            - Be concrete and step-oriented; use short paragraphs and "-" bullet lists.
            - Cite sources inline as markdown links whose target is copied verbatim from an \
            excerpt's "Source:" line, e.g. [Terminal AI agent](features/ai-tools.html#how-the-ai-agent-works). \
            Never invent or modify a link target.
            - If the excerpts do not contain the answer, reply exactly with: %s
            - Do not use markdown tables, images, raw HTML, or headings deeper than "###".
            - Keep the answer under roughly 300 words.
            """.formatted(answerLanguageDisplayName, notFoundSentence);
    }

    public static String buildUserPrompt(String question, List<GuideDocsRetriever.Excerpt> excerpts) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Question: ").append(question).append("\n\n");
        prompt.append("Documentation excerpts:\n");
        int number = 1;
        for (GuideDocsRetriever.Excerpt excerpt : excerpts) {
            prompt.append('\n')
                .append('[').append(number++).append("] Page: ").append(excerpt.pageTitle());
            if (!excerpt.sectionTitle().isBlank()
                && !excerpt.sectionTitle().equals(excerpt.pageTitle())) {
                prompt.append(" — Section: ").append(excerpt.sectionTitle());
            }
            prompt.append('\n')
                .append("Source: ").append(excerpt.location()).append('\n')
                .append(excerpt.text()).append('\n');
        }
        return prompt.toString();
    }

    /**
     * Enforces the citation whitelist on the model's markdown: link targets that match a sent
     * excerpt location are kept, targets with a wrong/invented anchor on a known page are
     * repaired to the page link, everything else is unwrapped to its plain label text.
     */
    public static String sanitizeAnswer(String markdown, Collection<String> allowedLocations) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        Set<String> allowed = new HashSet<>(allowedLocations);
        Set<String> allowedPages = new HashSet<>();
        for (String location : allowedLocations) {
            int hash = location.indexOf('#');
            allowedPages.add(hash >= 0 ? location.substring(0, hash) : location);
        }

        Matcher matcher = MARKDOWN_LINK.matcher(markdown);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String label = matcher.group(1);
            String target = matcher.group(2);
            String replacement;
            if (allowed.contains(target) || allowedPages.contains(target)) {
                replacement = "[" + label + "](" + target + ")";
            } else {
                int hash = target.indexOf('#');
                String page = hash >= 0 ? target.substring(0, hash) : target;
                replacement = allowedPages.contains(page)
                    ? "[" + label + "](" + page + ")"
                    : label;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Renders the (sanitized) answer markdown to the dark self-contained HTML used by the
     * answer panel, converting guide-location links into {@code kortty-guide:} anchors that the
     * panel intercepts. Delegates the markdown rendering to {@link SnippetMarkupPreviewRenderer}
     * (which escapes everything), then linkifies the escaped {@code [label](location)} residues.
     */
    public static String renderAnswerHtml(String answerMarkdown) {
        String html = SnippetMarkupPreviewRenderer.renderHtml("markdown", answerMarkdown);
        // Re-theme the renderer's generic dark palette to the guide site palette
        // (app-docs/site/.../kortty.css) so the answer blends into the manual window.
        html = html.replace("</style>", """
            body { background: #0d1b2a; color: #e6f3ff; }
            h1, h2, h3, h4, h5, h6 { color: #e6f3ff; border-color: rgba(103, 232, 249, 0.18); }
            pre { background: #061320; border-color: rgba(103, 232, 249, 0.28); }
            code { color: #67e8f9; }
            :not(pre) > code { background: rgba(103, 232, 249, 0.12); }
            blockquote { border-left-color: #38bdf8; background: rgba(56, 189, 248, 0.08); color: #b9c8da; }
            th { background: rgba(56, 189, 248, 0.15); color: #e6f3ff; }
            th, td { border-color: rgba(103, 232, 249, 0.18); }
            hr { border-top-color: rgba(103, 232, 249, 0.18); }
            a { color: #38bdf8; text-decoration: none; }
            a:hover { color: #67e8f9; text-decoration: underline; }
            </style>""");
        Pattern escapedLink = Pattern.compile(
            "\\[([^\\[\\]]+)\\]\\((" + GUIDE_LOCATION.pattern() + ")\\)");
        Matcher matcher = escapedLink.matcher(html);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String anchor = "<a href=\"" + GUIDE_LINK_SCHEME + matcher.group(2) + "\">"
                + matcher.group(1) + "</a>";
            matcher.appendReplacement(result, Matcher.quoteReplacement(anchor));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
