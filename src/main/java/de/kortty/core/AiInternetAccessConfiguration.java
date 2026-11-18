package de.kortty.core;

import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.GlobalSettings;

import java.util.function.BiFunction;

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
    String lmStudioToolpackMcpPluginId,
    boolean offerWebToolsForEveryAgentTask) {

    /** Configuration that offers KorTTY's web tools to agent steps only on web signal words. */
    public AiInternetAccessConfiguration(
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
        this(mode, tavilyApiKey, brightDataApiToken, braveSearchApiKey, searxngUrl, tavilyMcpServerLabel,
            brightDataMcpServerLabel, braveSearchMcpPluginId, searxngMcpPluginId, lmStudioToolpackMcpPluginId, false);
    }

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

    /** Builds the selected provider configuration using the caller's credential handling. */
    public static AiInternetAccessConfiguration fromSettings(
        GlobalSettings settings,
        AiInternetAccessMode mode,
        BiFunction<String, String, String> decryptSecret) {
        if (settings == null || mode == null || !mode.isEnabled()) {
            return AiInternetAccessConfiguration.disabled();
        }
        String tavilyApiKey = null;
        String brightDataApiToken = null;
        String braveSearchApiKey = null;
        String searxngUrl = null;
        String tavilyMcpServerLabel = null;
        String brightDataMcpServerLabel = null;
        String braveSearchMcpPluginId = null;
        String searxngMcpPluginId = null;
        String lmStudioToolpackMcpPluginId = null;
        switch (mode) {
            case KORTTY_TAVILY_TOOL -> tavilyApiKey = decryptSecret.apply(settings.getEncryptedAiTavilyApiKey(), "Tavily API key");
            case LM_STUDIO_TAVILY_MCP -> {
                tavilyApiKey = decryptSecret.apply(settings.getEncryptedAiTavilyApiKey(), "Tavily API key");
                tavilyMcpServerLabel = settings.getAiTavilyMcpServerLabel();
            }
            case BRIGHT_DATA_WEB_MCP -> {
                brightDataApiToken = decryptSecret.apply(settings.getEncryptedAiBrightDataApiToken(), "Bright Data API token");
                brightDataMcpServerLabel = settings.getAiBrightDataMcpServerLabel();
            }
            case BRAVE_SEARCH_MCP -> {
                braveSearchApiKey = decryptSecret.apply(settings.getEncryptedAiBraveSearchApiKey(), "Brave Search API key");
                braveSearchMcpPluginId = settings.getAiBraveSearchMcpPluginId();
            }
            case SEARXNG_MCP -> {
                searxngUrl = settings.getAiSearxngUrl();
                searxngMcpPluginId = settings.getAiSearxngMcpPluginId();
            }
            case LM_STUDIO_TOOLPACK -> lmStudioToolpackMcpPluginId = settings.getAiLmStudioToolpackMcpPluginId();
            case DISABLED -> {
            }
        }
        return new AiInternetAccessConfiguration(
            mode,
            tavilyApiKey,
            brightDataApiToken,
            braveSearchApiKey,
            searxngUrl,
            tavilyMcpServerLabel,
            brightDataMcpServerLabel,
            braveSearchMcpPluginId,
            searxngMcpPluginId,
            lmStudioToolpackMcpPluginId,
            settings.isAiAgentAlwaysOfferWebTools());
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
