package de.kortty.model;

/**
 * Optional reasoning effort for OpenAI-compatible chat completion requests.
 */
public enum AiReasoningEffort {
    // No reasoning parameter is sent, so the model's own default applies: a model that reasons by
    // default keeps reasoning. NONE is the value that switches reasoning off.
    DISABLED(null, "disabled", "Model default"),
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
