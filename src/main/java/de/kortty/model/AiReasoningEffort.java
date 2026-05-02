package de.kortty.model;

/**
 * Optional reasoning effort for OpenAI-compatible chat completion requests.
 */
public enum AiReasoningEffort {
    DISABLED(null, "disabled", "Disabled"),
    NONE("none", "none", "None"),
    MINIMAL("minimal", "minimal", "Minimal"),
    LOW("low", "low", "Low"),
    MEDIUM("medium", "medium", "Medium"),
    HIGH("high", "high", "High"),
    XHIGH("xhigh", "xhigh", "Extra high");

    private final String apiValue;
    private final String messageKeySuffix;
    private final String exportLabel;

    AiReasoningEffort(String apiValue, String messageKeySuffix, String exportLabel) {
        this.apiValue = apiValue;
        this.messageKeySuffix = messageKeySuffix;
        this.exportLabel = exportLabel;
    }

    public String apiValue() {
        return apiValue;
    }

    public String messageKeySuffix() {
        return messageKeySuffix;
    }

    public String exportLabel() {
        return exportLabel;
    }

    public boolean isApiEnabled() {
        return apiValue != null && !apiValue.isBlank();
    }
}
