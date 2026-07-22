package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;

import java.util.ArrayList;
import java.util.List;

/**
 * Named AI configuration profile for OpenAI-compatible endpoints.
 */
@XmlRootElement(name = "aiProfile")
@XmlAccessorType(XmlAccessType.FIELD)
public class AiProfile {

    public static final int DEFAULT_MAX_SELECTION_CHARS = 1_000_000;

    @XmlElement
    private String id;

    @XmlElement
    private String name;

    @XmlElement
    private String apiUrl;

    @XmlElement
    private AiConnectionMode connectionMode;

    @XmlElement
    private String model;

    /** Registry id of the GGUF model used by an embedded llama.cpp profile. */
    @XmlElement
    private String embeddedModelId;

    @XmlElement
    private AiPromptPreset promptPreset = AiPromptPreset.AUTO;

    @XmlElementWrapper(name = "ragStoreIds")
    @XmlElement(name = "storeId")
    private List<String> ragStoreIds = new ArrayList<>();

    @XmlElement
    private AiModelSelectionMode modelSelectionMode;

    @XmlElement
    private AiReasoningEffort reasoningEffort = AiReasoningEffort.DISABLED;

    @XmlElementWrapper(name = "discoveredReasoningEfforts")
    @XmlElement(name = "effort")
    private List<AiReasoningEffort> discoveredReasoningEfforts = new ArrayList<>();

    @XmlElement
    private String reasoningDiscoveryKey;

    @XmlElement
    private AiInternetAccessMode internetAccessMode = AiInternetAccessMode.DISABLED;

    @XmlElement
    private String encryptedApiKey;

    @XmlElement
    private String cliProviderId;

    @XmlElement
    private String cliExecutablePath;

    @XmlElement
    private String cliArgumentsTemplate;

    @XmlElement
    private Integer maxSelectionChars = DEFAULT_MAX_SELECTION_CHARS;

    @XmlElement
    private AiTokenizerType tokenizerType = AiTokenizerType.ESTIMATE;

    @XmlElement
    private Long tokenLimitAmount;

    @XmlElement
    private AiTokenLimitUnit tokenLimitUnit = AiTokenLimitUnit.THOUSANDS;

    @XmlElement
    private Integer tokenWarningYellowPercent = 75;

    @XmlElement
    private Integer tokenWarningRedPercent = 90;

    @XmlElement
    private Integer tokenResetPeriodDays = 30;

    @XmlElement
    private String tokenResetAnchorDate;

    @XmlElement
    private String tokenUsageCycleStartDate;

    @XmlElement
    private Long usedPromptTokens = 0L;

    @XmlElement
    private Long usedCompletionTokens = 0L;

    @XmlElement
    private Long usedTotalTokens = 0L;

    /**
     * True for profiles injected from the enterprise policy. Never persisted — policy profiles are
     * rebuilt from the policy file on every settings load.
     */
    @XmlTransient
    private boolean policyManaged;

    /**
     * The admin-provided API key in its {@code kortty-enc:v1:} envelope; decrypted only at the
     * moment of use (see {@code PolicyAiProfileSupport.apiKeyOverride}). Never persisted.
     */
    @XmlTransient
    private String policyEncryptedApiKey;

    public AiProfile() {
    }

    public AiProfile(AiProfile source) {
        if (source == null) {
            return;
        }
        this.id = source.id;
        this.name = source.name;
        this.apiUrl = source.apiUrl;
        this.connectionMode = source.getConnectionMode();
        this.model = source.model;
        this.embeddedModelId = source.embeddedModelId;
        this.promptPreset = source.getPromptPreset();
        this.ragStoreIds = new ArrayList<>(source.getRagStoreIds());
        this.modelSelectionMode = source.getModelSelectionMode();
        this.reasoningEffort = source.getReasoningEffort();
        this.discoveredReasoningEfforts = copyReasoningEfforts(source.getDiscoveredReasoningEfforts());
        this.reasoningDiscoveryKey = source.reasoningDiscoveryKey;
        this.internetAccessMode = source.getInternetAccessMode();
        this.encryptedApiKey = source.encryptedApiKey;
        this.cliProviderId = source.cliProviderId;
        this.cliExecutablePath = source.cliExecutablePath;
        this.cliArgumentsTemplate = source.cliArgumentsTemplate;
        this.maxSelectionChars = source.maxSelectionChars;
        this.tokenizerType = source.tokenizerType;
        this.tokenLimitAmount = source.tokenLimitAmount;
        this.tokenLimitUnit = source.tokenLimitUnit;
        this.tokenWarningYellowPercent = source.tokenWarningYellowPercent;
        this.tokenWarningRedPercent = source.tokenWarningRedPercent;
        this.tokenResetPeriodDays = source.tokenResetPeriodDays;
        this.tokenResetAnchorDate = source.tokenResetAnchorDate;
        this.tokenUsageCycleStartDate = source.tokenUsageCycleStartDate;
        this.usedPromptTokens = source.usedPromptTokens;
        this.usedCompletionTokens = source.usedCompletionTokens;
        this.usedTotalTokens = source.usedTotalTokens;
        this.policyManaged = source.policyManaged;
        this.policyEncryptedApiKey = source.policyEncryptedApiKey;
    }

    public boolean isPolicyManaged() {
        return policyManaged;
    }

    public void setPolicyManaged(boolean policyManaged) {
        this.policyManaged = policyManaged;
    }

    public String getPolicyEncryptedApiKey() {
        return policyEncryptedApiKey;
    }

    public void setPolicyEncryptedApiKey(String policyEncryptedApiKey) {
        this.policyEncryptedApiKey = policyEncryptedApiKey;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public AiConnectionMode getConnectionMode() {
        return connectionMode != null ? connectionMode : AiConnectionMode.HTTP_API;
    }

    public void setConnectionMode(AiConnectionMode connectionMode) {
        this.connectionMode = connectionMode != null ? connectionMode : AiConnectionMode.HTTP_API;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getEmbeddedModelId() {
        return embeddedModelId;
    }

    public void setEmbeddedModelId(String embeddedModelId) {
        this.embeddedModelId = embeddedModelId != null && !embeddedModelId.isBlank()
            ? embeddedModelId.trim()
            : null;
    }

    public AiPromptPreset getPromptPreset() {
        return promptPreset != null ? promptPreset : AiPromptPreset.AUTO;
    }

    public void setPromptPreset(AiPromptPreset promptPreset) {
        this.promptPreset = promptPreset != null ? promptPreset : AiPromptPreset.AUTO;
    }

    public List<String> getRagStoreIds() {
        if (ragStoreIds == null) {
            ragStoreIds = new ArrayList<>();
        }
        return ragStoreIds;
    }

    public void setRagStoreIds(List<String> ragStoreIds) {
        this.ragStoreIds = ragStoreIds == null ? new ArrayList<>() : ragStoreIds.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public AiModelSelectionMode getModelSelectionMode() {
        if (modelSelectionMode != null) {
            return modelSelectionMode;
        }
        return model != null && !model.isBlank()
            ? AiModelSelectionMode.MANUAL
            : AiModelSelectionMode.AUTO;
    }

    public void setModelSelectionMode(AiModelSelectionMode modelSelectionMode) {
        this.modelSelectionMode = modelSelectionMode != null ? modelSelectionMode : AiModelSelectionMode.AUTO;
    }

    public AiReasoningEffort getReasoningEffort() {
        return reasoningEffort != null ? reasoningEffort : AiReasoningEffort.DISABLED;
    }

    public void setReasoningEffort(AiReasoningEffort reasoningEffort) {
        this.reasoningEffort = reasoningEffort != null ? reasoningEffort : AiReasoningEffort.DISABLED;
    }

    public List<AiReasoningEffort> getDiscoveredReasoningEfforts() {
        if (discoveredReasoningEfforts == null) {
            discoveredReasoningEfforts = new ArrayList<>();
        }
        return discoveredReasoningEfforts;
    }

    public void setDiscoveredReasoningEfforts(List<AiReasoningEffort> discoveredReasoningEfforts) {
        this.discoveredReasoningEfforts = copyReasoningEfforts(discoveredReasoningEfforts);
    }

    public String getReasoningDiscoveryKey() {
        return reasoningDiscoveryKey;
    }

    public void setReasoningDiscoveryKey(String reasoningDiscoveryKey) {
        this.reasoningDiscoveryKey = reasoningDiscoveryKey;
    }

    public AiInternetAccessMode getInternetAccessMode() {
        return internetAccessMode != null ? internetAccessMode : AiInternetAccessMode.DISABLED;
    }

    public void setInternetAccessMode(AiInternetAccessMode internetAccessMode) {
        this.internetAccessMode = internetAccessMode != null ? internetAccessMode : AiInternetAccessMode.DISABLED;
    }

    public String getEncryptedApiKey() {
        return encryptedApiKey;
    }

    public void setEncryptedApiKey(String encryptedApiKey) {
        this.encryptedApiKey = encryptedApiKey;
    }

    public String getCliProviderId() {
        return cliProviderId;
    }

    public void setCliProviderId(String cliProviderId) {
        this.cliProviderId = cliProviderId;
    }

    public String getCliExecutablePath() {
        return cliExecutablePath;
    }

    public void setCliExecutablePath(String cliExecutablePath) {
        this.cliExecutablePath = cliExecutablePath;
    }

    public String getCliArgumentsTemplate() {
        return cliArgumentsTemplate;
    }

    public void setCliArgumentsTemplate(String cliArgumentsTemplate) {
        this.cliArgumentsTemplate = cliArgumentsTemplate;
    }

    public Integer getMaxSelectionChars() {
        return maxSelectionChars;
    }

    public void setMaxSelectionChars(Integer maxSelectionChars) {
        this.maxSelectionChars = maxSelectionChars;
    }

    public AiTokenizerType getTokenizerType() {
        return tokenizerType;
    }

    public void setTokenizerType(AiTokenizerType tokenizerType) {
        this.tokenizerType = tokenizerType;
    }

    public Long getTokenLimitAmount() {
        return tokenLimitAmount;
    }

    public void setTokenLimitAmount(Long tokenLimitAmount) {
        this.tokenLimitAmount = tokenLimitAmount;
    }

    public AiTokenLimitUnit getTokenLimitUnit() {
        return tokenLimitUnit;
    }

    public void setTokenLimitUnit(AiTokenLimitUnit tokenLimitUnit) {
        this.tokenLimitUnit = tokenLimitUnit;
    }

    public Integer getTokenWarningYellowPercent() {
        return tokenWarningYellowPercent;
    }

    public void setTokenWarningYellowPercent(Integer tokenWarningYellowPercent) {
        this.tokenWarningYellowPercent = tokenWarningYellowPercent;
    }

    public Integer getTokenWarningRedPercent() {
        return tokenWarningRedPercent;
    }

    public void setTokenWarningRedPercent(Integer tokenWarningRedPercent) {
        this.tokenWarningRedPercent = tokenWarningRedPercent;
    }

    public Integer getTokenResetPeriodDays() {
        return tokenResetPeriodDays;
    }

    public void setTokenResetPeriodDays(Integer tokenResetPeriodDays) {
        this.tokenResetPeriodDays = tokenResetPeriodDays;
    }

    public String getTokenResetAnchorDate() {
        return tokenResetAnchorDate;
    }

    public void setTokenResetAnchorDate(String tokenResetAnchorDate) {
        this.tokenResetAnchorDate = tokenResetAnchorDate;
    }

    public String getTokenUsageCycleStartDate() {
        return tokenUsageCycleStartDate;
    }

    public void setTokenUsageCycleStartDate(String tokenUsageCycleStartDate) {
        this.tokenUsageCycleStartDate = tokenUsageCycleStartDate;
    }

    public Long getUsedPromptTokens() {
        return usedPromptTokens;
    }

    public void setUsedPromptTokens(Long usedPromptTokens) {
        this.usedPromptTokens = usedPromptTokens;
    }

    public Long getUsedCompletionTokens() {
        return usedCompletionTokens;
    }

    public void setUsedCompletionTokens(Long usedCompletionTokens) {
        this.usedCompletionTokens = usedCompletionTokens;
    }

    public Long getUsedTotalTokens() {
        return usedTotalTokens;
    }

    public void setUsedTotalTokens(Long usedTotalTokens) {
        this.usedTotalTokens = usedTotalTokens;
    }

    private static List<AiReasoningEffort> copyReasoningEfforts(List<AiReasoningEffort> source) {
        List<AiReasoningEffort> result = new ArrayList<>();
        if (source != null) {
            for (AiReasoningEffort effort : source) {
                if (effort != null && !result.contains(effort)) {
                    result.add(effort);
                }
            }
        }
        if (!result.isEmpty() && !result.contains(AiReasoningEffort.DISABLED)) {
            result.add(0, AiReasoningEffort.DISABLED);
        }
        return result;
    }
}
