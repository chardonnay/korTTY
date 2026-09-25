package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted record of one internet tool call (web search, page read, MCP tool) behind an
 * assistant reply in a saved AI chat. Display-only; holds no page content.
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class SavedAiWebToolCall {

    /** {@code SEARCH}, {@code EXTRACT} or {@code OTHER}. */
    @XmlElement
    private String kind;

    @XmlElement
    private String tool;

    @XmlElement
    private String input;

    @XmlElement
    private boolean success;

    @XmlElement
    private String message;

    @XmlElement
    private int contentChars;

    @XmlElement
    private boolean truncated;

    @XmlElementWrapper(name = "sources")
    @XmlElement(name = "source")
    private List<Source> sources = new ArrayList<>();

    public SavedAiWebToolCall() {
    }

    public SavedAiWebToolCall(SavedAiWebToolCall source) {
        if (source == null) {
            return;
        }
        this.kind = source.kind;
        this.tool = source.tool;
        this.input = source.input;
        this.success = source.success;
        this.message = source.message;
        this.contentChars = source.contentChars;
        this.truncated = source.truncated;
        for (Source item : source.getSources()) {
            this.sources.add(new Source(item.getTitle(), item.getUrl()));
        }
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool;
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getContentChars() {
        return contentChars;
    }

    public void setContentChars(int contentChars) {
        this.contentChars = contentChars;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public List<Source> getSources() {
        if (sources == null) {
            sources = new ArrayList<>();
        }
        return sources;
    }

    public void setSources(List<Source> sources) {
        this.sources = sources != null ? new ArrayList<>(sources) : new ArrayList<>();
    }

    /** One result or page URL, with its title when the tool reported one. */
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Source {

        @XmlElement
        private String title;

        @XmlElement
        private String url;

        public Source() {
        }

        public Source(String title, String url) {
            this.title = title;
            this.url = url;
        }

        public String getTitle() {
            return title;
        }

        public String getUrl() {
            return url;
        }
    }
}
