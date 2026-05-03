package de.kortty.model;

/**
 * Per-profile internet access strategy for AI requests.
 */
public enum AiInternetAccessMode {
    DISABLED("disabled"),
    KORTTY_TAVILY_TOOL("korttyTavilyTool"),
    LM_STUDIO_TAVILY_MCP("lmStudioTavilyMcp"),
    BRIGHT_DATA_WEB_MCP("brightDataWebMcp"),
    BRAVE_SEARCH_MCP("braveSearchMcp"),
    SEARXNG_MCP("searxngMcp"),
    LM_STUDIO_TOOLPACK("lmStudioToolpack");

    private final String messageKeySuffix;

    AiInternetAccessMode(String messageKeySuffix) {
        this.messageKeySuffix = messageKeySuffix;
    }

    public String messageKeySuffix() {
        return messageKeySuffix;
    }

    public boolean isEnabled() {
        return this != DISABLED;
    }

    public boolean usesKorTTYTool() {
        return this == KORTTY_TAVILY_TOOL;
    }

    public boolean usesLmStudioMcp() {
        return this == LM_STUDIO_TAVILY_MCP
            || this == BRIGHT_DATA_WEB_MCP
            || this == BRAVE_SEARCH_MCP
            || this == SEARXNG_MCP
            || this == LM_STUDIO_TOOLPACK;
    }
}
