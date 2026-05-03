package de.kortty.core;

import de.kortty.model.AiInternetAccessMode;

/**
 * Decrypted internet-tool configuration used while creating AI services.
 */
public record AiInternetAccessConfiguration(
    AiInternetAccessMode mode,
    String tavilyApiKey,
    String brightDataApiToken,
    String braveSearchApiKey,
    String searxngUrl,
    String tavilyMcpServerLabel,
    String brightDataMcpServerLabel,
    String braveSearchMcpPluginId,
    String searxngMcpPluginId,
    String lmStudioToolpackMcpPluginId) {

    public AiInternetAccessConfiguration {
        mode = mode != null ? mode : AiInternetAccessMode.DISABLED;
        tavilyApiKey = trimToNull(tavilyApiKey);
        brightDataApiToken = trimToNull(brightDataApiToken);
        braveSearchApiKey = trimToNull(braveSearchApiKey);
        searxngUrl = trimToNull(searxngUrl);
        tavilyMcpServerLabel = nonBlank(tavilyMcpServerLabel, "tavily");
        brightDataMcpServerLabel = nonBlank(brightDataMcpServerLabel, "bright-data");
        braveSearchMcpPluginId = trimToNull(braveSearchMcpPluginId);
        searxngMcpPluginId = trimToNull(searxngMcpPluginId);
        lmStudioToolpackMcpPluginId = trimToNull(lmStudioToolpackMcpPluginId);
    }

    public static AiInternetAccessConfiguration disabled() {
        return new AiInternetAccessConfiguration(
            AiInternetAccessMode.DISABLED,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    }

    public void validate() {
        switch (mode) {
            case DISABLED -> {
            }
            case KORTTY_TAVILY_TOOL, LM_STUDIO_TAVILY_MCP -> require(tavilyApiKey, "Tavily API key");
            case BRIGHT_DATA_WEB_MCP -> require(brightDataApiToken, "Bright Data API token");
            case BRAVE_SEARCH_MCP -> require(braveSearchMcpPluginId, "Brave Search MCP plugin id");
            case SEARXNG_MCP -> {
                require(searxngMcpPluginId, "SearXNG MCP plugin id");
                require(searxngUrl, "SearXNG URL");
            }
            case LM_STUDIO_TOOLPACK -> require(lmStudioToolpackMcpPluginId, "LM Studio Toolpack MCP plugin id");
        }
    }

    private void require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + " must be configured for internet mode " + mode + ".");
        }
    }

    private static String trimToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }
}
