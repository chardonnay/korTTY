package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

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
    private String model;

    @XmlElement
    private AiReasoningEffort reasoningEffort = AiReasoningEffort.DISABLED;

    @XmlElement
    private String encryptedApiKey;

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

    public AiProfile() {
    }

    public AiProfile(AiProfile source) {
        if (source == null) {
            return;
        }
        this.id = source.id;
        this.name = source.name;
        this.apiUrl = source.apiUrl;
        this.model = source.model;
        this.reasoningEffort = source.getReasoningEffort();
        this.encryptedApiKey = source.encryptedApiKey;
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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public AiReasoningEffort getReasoningEffort() {
        return reasoningEffort != null ? reasoningEffort : AiReasoningEffort.DISABLED;
    }

    public void setReasoningEffort(AiReasoningEffort reasoningEffort) {
        this.reasoningEffort = reasoningEffort != null ? reasoningEffort : AiReasoningEffort.DISABLED;
    }

    public String getEncryptedApiKey() {
        return encryptedApiKey;
    }

    public void setEncryptedApiKey(String encryptedApiKey) {
        this.encryptedApiKey = encryptedApiKey;
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
}
