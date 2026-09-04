package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiReasoningEffort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * AI service for OpenAI-compatible chat completion endpoints.
 */
public class OpenAiCompatibleAiService implements AiPromptService, AiSkillUsageTracker, AiRequestTimeoutAware {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiCompatibleAiService.class);
    private static final Gson GSON = new Gson();
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration TEST_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TEST_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String CONNECTION_TEST_SYSTEM_PROMPT = "Reply with exactly OK.";
    private static final String CONNECTION_TEST_USER_PROMPT = "Connection test.";
    private static final java.util.regex.Pattern LOGGED_PREDICTION_PATTERN =
        java.util.regex.Pattern.compile("(?s)Generated prediction:\\s*(\\{.*\\})");
    private static final java.util.regex.Pattern PROMPT_TOKENS_PATTERN =
        java.util.regex.Pattern.compile("\"prompt_tokens\"\\s*:\\s*(\\d+)");
    private static final java.util.regex.Pattern COMPLETION_TOKENS_PATTERN =
        java.util.regex.Pattern.compile("\"completion_tokens\"\\s*:\\s*(\\d+)");
    private static final java.util.regex.Pattern TOTAL_TOKENS_PATTERN =
        java.util.regex.Pattern.compile("\"total_tokens\"\\s*:\\s*(\\d+)");
    private static final int MAX_WEB_TOOL_ROUNDS = 2;
    private static final int MAX_TOOL_CALLS_PER_REQUEST = 3;
    private static final String WEB_SEARCH_TOOL_NAME = "web_search";
    private static final Duration SKILL_CLASSIFICATION_TIMEOUT = Duration.ofSeconds(8);
    /** MiniMax accepts only {@code adaptive} and {@code disabled}; {@code enabled} is a 400. */
    private static final String MINIMAX_THINKING_MODE = "disabled";
    /** Cap for the debug-only echo of a response that carried no usable JSON. */
    private static final int UNUSABLE_RESPONSE_LOG_CHARS = 600;
    /**
     * Appended when retrying a structured-output action on an endpoint that ignored
     * {@code response_format}: it restates, in the prompt, the constraint the schema could not.
     */
    /**
     * Sent with the FIRST attempt to endpoints that accept response_format and ignore it
     * (MiniMax): the retry's instruction, minus the accusation. The escaping rule is already in the
     * contract; this repeats it as the last thing the model reads, where the retry's success
     * suggests it counts. Costs ~70 tokens; a retry it prevents costs a whole request.
     */
    private static final String JSON_ONLY_FIRST_ATTEMPT_INSTRUCTION =
        "Reply with the single JSON object described above and nothing else: no prose, no explanation, "
            + "no markdown code fences. Every string value must be valid JSON — escape every double quote "
            + "inside code as \\\" and every backslash as \\\\, and never break a string across lines. "
            + "The first character of your reply must be { and the last must be }.";

    private static final String JSON_ONLY_RETRY_INSTRUCTION =
        "Your previous answer was not valid JSON. Reply with the single JSON object described above "
            + "and nothing else: no prose, no explanation, no markdown code fences, no leading or "
            + "trailing text. The first character of your reply must be { and the last must be }.";

    private enum CompletionTokenParameter {
        MAX_TOKENS("max_tokens"),
        MAX_COMPLETION_TOKENS("max_completion_tokens");

        private final String jsonName;

        CompletionTokenParameter(String jsonName) {
            this.jsonName = jsonName;
        }
    }

    private final String apiUrl;
    private final String model;
    private final AiModelSelectionMode modelSelectionMode;
    private final String apiKey;
    private final AiReasoningEffort reasoningEffort;
    private final HttpClient httpClient;
    private final TavilyWebSearchTool webSearchTool;
    private final AiSkillPromptSupport skillPromptSupport;
    private Integer defaultMaxCompletionTokens;
    /** {@code null} lets a request run to completion — see {@link AiRequestTimeoutSupport}. */
    private Duration requestTimeout;

    public OpenAiCompatibleAiService(String apiUrl, String model, String apiKey) {
        this(apiUrl, model, apiKey, AiReasoningEffort.DISABLED);
    }

    public OpenAiCompatibleAiService(String apiUrl, String model, String apiKey, AiReasoningEffort reasoningEffort) {
        this(apiUrl, model, apiKey, reasoningEffort, HttpClient.newBuilder().connectTimeout(DEFAULT_CONNECT_TIMEOUT).build());
    }

    OpenAiCompatibleAiService(String apiUrl, String model, String apiKey, HttpClient httpClient) {
        this(apiUrl, model, apiKey, AiReasoningEffort.DISABLED, httpClient);
    }

    OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        AiModelSelectionMode modelSelectionMode,
        String apiKey,
        HttpClient httpClient) {

        this(apiUrl, model, modelSelectionMode, apiKey, AiReasoningEffort.DISABLED, httpClient);
    }

    public OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        TavilyWebSearchTool webSearchTool) {

        this(apiUrl, model, apiKey, reasoningEffort, webSearchTool, AiSkillPromptSupport.disabled());
    }

    public OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        AiModelSelectionMode modelSelectionMode,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        TavilyWebSearchTool webSearchTool,
        AiSkillPromptSupport skillPromptSupport) {

        this(
            apiUrl,
            model,
            modelSelectionMode,
            apiKey,
            reasoningEffort,
            HttpClient.newBuilder().connectTimeout(DEFAULT_CONNECT_TIMEOUT).build(),
            webSearchTool,
            skillPromptSupport);
    }

    public OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        TavilyWebSearchTool webSearchTool,
        AiSkillPromptSupport skillPromptSupport) {

        this(
            apiUrl,
            model,
            apiKey,
            reasoningEffort,
            HttpClient.newBuilder().connectTimeout(DEFAULT_CONNECT_TIMEOUT).build(),
            webSearchTool,
            skillPromptSupport);
    }

    OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        AiModelSelectionMode modelSelectionMode,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        HttpClient httpClient) {

        this(apiUrl, model, modelSelectionMode, apiKey, reasoningEffort, httpClient, null);
    }

    OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        HttpClient httpClient) {

        this(apiUrl, model, apiKey, reasoningEffort, httpClient, null);
    }

    OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        AiModelSelectionMode modelSelectionMode,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        HttpClient httpClient,
        TavilyWebSearchTool webSearchTool) {

        this(apiUrl, model, modelSelectionMode, apiKey, reasoningEffort, httpClient, webSearchTool, AiSkillPromptSupport.disabled());
    }

    OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        HttpClient httpClient,
        TavilyWebSearchTool webSearchTool) {

        this(apiUrl, model, apiKey, reasoningEffort, httpClient, webSearchTool, AiSkillPromptSupport.disabled());
    }

    OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        AiModelSelectionMode modelSelectionMode,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        HttpClient httpClient,
        TavilyWebSearchTool webSearchTool,
        AiSkillPromptSupport skillPromptSupport) {

        this.apiUrl = apiUrl != null ? apiUrl.trim() : "";
        this.model = model != null ? model.trim() : "";
        this.modelSelectionMode = modelSelectionMode != null ? modelSelectionMode : AiModelSelectionMode.MANUAL;
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.reasoningEffort = reasoningEffort != null ? reasoningEffort : AiReasoningEffort.DISABLED;
        this.httpClient = httpClient;
        this.webSearchTool = webSearchTool;
        this.skillPromptSupport = skillPromptSupport != null ? skillPromptSupport : AiSkillPromptSupport.disabled();
    }

    OpenAiCompatibleAiService(
        String apiUrl,
        String model,
        String apiKey,
        AiReasoningEffort reasoningEffort,
        HttpClient httpClient,
        TavilyWebSearchTool webSearchTool,
        AiSkillPromptSupport skillPromptSupport) {

        this.apiUrl = apiUrl != null ? apiUrl.trim() : "";
        this.model = model != null ? model.trim() : "";
        this.modelSelectionMode = AiModelSelectionMode.MANUAL;
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.reasoningEffort = reasoningEffort != null ? reasoningEffort : AiReasoningEffort.DISABLED;
        this.httpClient = httpClient;
        this.webSearchTool = webSearchTool;
        this.skillPromptSupport = skillPromptSupport != null ? skillPromptSupport : AiSkillPromptSupport.disabled();
    }

    /**
     * Sends an explicit {@code max_tokens} with every chat request. Servers that default the field
     * generously (OpenAI, llama.cpp) don't need this, but {@code mlx_lm.server} caps an absent
     * {@code max_tokens} at 512 completion tokens — a reasoning model then spends the whole budget
     * on its chain-of-thought and returns no answer at all.
     */
    public void setDefaultMaxCompletionTokens(Integer maxCompletionTokens) {
        this.defaultMaxCompletionTokens = maxCompletionTokens;
    }

    @Override
    public AiExecutionResult execute(AiRequest request) throws Exception {
        return executeWithClient(request, httpClient, requestTimeout);
    }

    public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) throws Exception {
        return executePromptWithClient(systemPrompt, userPrompt, httpClient, requestTimeout);
    }

    public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws Exception {
        return executePromptWithClient(systemPrompt, userPrompt, httpClient, requestTimeout, true);
    }

    @Override
    public AiExecutionResult executeJsonPromptWithoutResponseFormat(String systemPrompt, String userPrompt) throws Exception {
        return executePromptWithClient(systemPrompt, userPrompt, httpClient, requestTimeout, false);
    }

    @Override
    public boolean supportsVision() {
        return true;
    }

    @Override
    public AiExecutionResult executeVisionJsonPrompt(
        String systemPrompt, String userPrompt, List<AiImageInput> images) throws Exception {
        return executePromptWithClient(systemPrompt, userPrompt, images, httpClient, requestTimeout, true, true);
    }

    @Override
    public AiExecutionResult executeVisionJsonPromptWithoutResponseFormat(
        String systemPrompt, String userPrompt, List<AiImageInput> images) throws Exception {
        return executePromptWithClient(systemPrompt, userPrompt, images, httpClient, requestTimeout, false, true);
    }

    /**
     * @param requestTimeout the user-configured timeout, or {@code null} (the default) to let a
     *     request run to completion
     */
    @Override
    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    /** @return the applied timeout, or {@code null} when requests run without one. */
    Duration requestTimeout() {
        return requestTimeout;
    }

    @Override
    public List<AiSkillPromptSupport.SkillUsage> drainSkillUsages() {
        return skillPromptSupport.drainSkillUsages();
    }

    AiExecutionResult executeWithClient(AiRequest request, HttpClient client, Duration timeout) throws Exception {
        String effectiveModel = resolveModelForRequest(client);
        AiSkillRelevanceClassifier skillClassifier = createSkillClassifier(client, effectiveModel);
        if (webSearchTool != null && AiInternetPromptSupport.isInternetEligible(request)) {
            try {
                return executeToolAwareMessages(
                    buildRequestMessages(request, true, skillClassifier),
                    client,
                    timeout,
                    false,
                    effectiveModel);
            } catch (EmptyResponseException e) {
                AiExecutionResult salvaged = salvageAnswerFromReasoning(request, e);
                if (salvaged == null) {
                    throw e;
                }
                return salvaged;
            }
        }
        try {
            return executeAiRequestWithStructuredOutputFallback(
                request, client, timeout, skillClassifier, effectiveModel);
        } catch (ModelNotLoadedException e) {
            String retryModel = reresolveForRetry(client);
            if (retryModel == null || retryModel.equals(effectiveModel)) {
                throw e;
            }
            return executeAiRequestWithStructuredOutputFallback(
                request, client, timeout, skillClassifier, retryModel);
        }
    }

    private AiExecutionResult executeAiRequestWithStructuredOutputFallback(
        AiRequest request,
        HttpClient client,
        Duration timeout,
        AiSkillRelevanceClassifier skillClassifier,
        String effectiveModel) throws Exception {

        AiExecutionResult result;
        try {
            result = executeAiRequestWithTokenParameterFallback(
                request, client, timeout, skillClassifier, effectiveModel, true, false);
        } catch (EmptyResponseException e) {
            AiExecutionResult salvaged = salvageAnswerFromReasoning(request, e);
            if (salvaged != null) {
                return salvaged;
            }
            if (!repeatsRequestWithoutSchema(request)) {
                throw e;
            }
            // The schema itself is the prime suspect for a reply that carried no content at all,
            // so the plain-JSON retry that already covers an unusable answer covers a missing one
            // too. Without it the action fails outright on an endpoint whose grammar and reasoning
            // parser disagree.
            logger.info(
                "AI endpoint returned no content for action={} with a json_schema response format; "
                    + "retrying once without the schema.",
                request.action());
            return withCarriedOverUsage(
                e.partialResult(),
                executeAiRequestWithTokenParameterFallback(
                    request, client, timeout, skillClassifier, effectiveModel, false, true));
        } catch (AiApiException e) {
            if (!usesStructuredJsonSchema(request) || !isUnsupportedStructuredOutputError(e)) {
                throw e;
            }
            // Some OpenAI-compatible endpoints do not implement json_schema. Drop it only when the
            // endpoint explicitly rejects that capability — an invalid-schema complaint is our own
            // bug and must stay visible. Endpoints that accept and then ignore the parameter are
            // caught after the fact by the unusable-content check below.
            return executeAiRequestWithTokenParameterFallback(
                request, client, timeout, skillClassifier, effectiveModel, false, true);
        }
        if (!needsPlainJsonRetry(request, result)) {
            return result;
        }
        // The endpoint accepted response_format and answered in prose anyway — MiniMax drops the
        // parameter silently, so no status code reveals it and the 400 branch above never fires.
        // Dropping the schema alone would change nothing on such an endpoint, so the retry also
        // spells out the JSON-only rule that the schema was meant to enforce.
        logUnusableStructuredResponse(request, result);
        return withCarriedOverUsage(
            result,
            executeAiRequestWithTokenParameterFallback(
                request, client, timeout, skillClassifier, effectiveModel, false, true));
    }

    /**
     * Carries the discarded first attempt's tokens into the returned result. Unlike the 400 branch,
     * that attempt completed a full generation and was billed, and callers record usage from the
     * returned result alone — dropping it would under-report every retried request.
     */
    private AiExecutionResult withCarriedOverUsage(AiExecutionResult discarded, AiExecutionResult retried) {
        if (retried == null) {
            return null;
        }
        AiTokenUsage discardedUsage = discarded != null ? discarded.usage() : null;
        if (discardedUsage == null) {
            return retried;
        }
        return new AiExecutionResult(
            retried.content(),
            mergeUsage(java.util.Arrays.asList(discardedUsage, retried.usage())),
            retried.reasoning(),
            retried.outputTruncated());
    }

    /**
     * @return whether a structured-output action came back in a shape its own consumer cannot use,
     *     which is worth one retry because that consumer would otherwise fail outright.
     *     A truncated reply is excluded: it stopped at a token limit rather than ignoring the
     *     schema, and its own fail-closed handling must win.
     */
    private static boolean needsPlainJsonRetry(AiRequest request, AiExecutionResult result) {
        return repeatsRequestWithoutSchema(request)
            && result != null
            && !result.outputTruncated()
            && !consumerCanUseResponse(request, result.content());
    }

    /**
     * Whether a completed generation may be repeated without the schema. Every structured action
     * may, except the diagram: it costs minutes on the models this matters for, a rejected diagram
     * is a modelling problem that asking again does not solve, and
     * {@link SnippetTypedDiagramSupport#extractDiagramSource} recovers a mis-escaped answer from
     * the text korTTY already has. An endpoint that rejects the schema outright is a different
     * case — that costs no generation and is retried above.
     */
    private static boolean repeatsRequestWithoutSchema(AiRequest request) {
        return usesStructuredJsonSchema(request)
            && request.action() != AiAction.GENERATE_SNIPPET_MERMAID;
    }

    /**
     * Asks the very parser that will consume this reply whether it can use it, rather than testing
     * for JSON in general. The two differ in both directions and each disagreement is a bug: a
     * shell snippet's {@code find … -exec rm {} +} or a quoted {@code curl -d '{…}'} puts balanced
     * JSON into a prose answer that the analysis parser still rejects, while an apply reply may be
     * a bare fenced script that carries no JSON at all and is nevertheless exactly what
     * {@link SnippetAiResponseSupport#parseSecurityFix} is built to accept.
     */
    private static boolean consumerCanUseResponse(AiRequest request, String content) {
        if (request.action() == AiAction.ANALYZE_SNIPPET_CODE) {
            return SnippetAiResponseSupport.parseScriptAnalysis(content).isUsable();
        }
        if (request.action() == AiAction.GENERATE_SNIPPET_MERMAID) {
            // The shape, not the diagram: a rejected diagram is a modelling problem that asking
            // again does not solve, and the reasoning-channel salvage below must still recognize
            // a well-formed answer.
            return SnippetAiResponseSupport.carriesDiagramJson(content);
        }
        if (AiPromptBuilder.isEditModeApply(request)) {
            return SnippetAiResponseSupport.parseSnippetEdits(content).isUsable();
        }
        if (request.action() == AiAction.MIGRATE_SNIPPET_LANGUAGE) {
            return SnippetAiResponseSupport.parseLanguageMigration(content).isUsable();
        }
        return SnippetAiResponseSupport.parseSecurityFix(content).isUsable();
    }

    /**
     * Recovers an answer that the endpoint delivered in its reasoning channel instead of in
     * {@code content}. LM Studio does exactly that for a thinking model as soon as a
     * {@code json_schema} response format is in play: the grammar constrains the whole generation,
     * the model therefore never emits its end-of-thinking marker, and the complete
     * schema-conforming object arrives as {@code reasoning_content} — with an empty content field
     * and {@code finish_reason=stop}, which is indistinguishable from a model that said nothing.
     *
     * <p>Only the structured-output actions are salvaged, and only when the very parser that will
     * consume the reply accepts the text. For every other action the reasoning is genuine
     * chain-of-thought and must never be promoted to the answer.</p>
     *
     * @return the answer moved into {@code content}, or {@code null} when nothing is salvageable.
     */
    private static AiExecutionResult salvageAnswerFromReasoning(AiRequest request, EmptyResponseException e) {
        if (!usesStructuredJsonSchema(request)) {
            return null;
        }
        AiExecutionResult partial = e.partialResult();
        String reasoning = partial != null ? partial.reasoning() : null;
        if (reasoning == null || reasoning.isBlank() || !consumerCanUseResponse(request, reasoning)) {
            return null;
        }
        logger.info(
            "AI endpoint returned the answer for action={} in its reasoning channel with no content; "
                + "using the reasoning as the reply.",
            request.action());
        return new AiExecutionResult(
            reasoning.trim(),
            partial.usage(),
            null,
            partial.outputTruncated(),
            partial.streamInterrupted());
    }

    /**
     * Records why a structured-output request is being retried. The content itself goes to DEBUG
     * only and capped: it embeds the user's snippet, which must not land in the default log.
     */
    /**
     * Which way an unusable structured answer failed — the retry statistics were useless without
     * it: an object that is there but does not parse (almost always a quote in a code line that
     * was not escaped) is a different problem from prose or a fenced block.
     */
    static String unusableAnswerClass(String content) {
        String trimmed = content != null ? content.strip() : "";
        if (trimmed.isEmpty()) {
            return "empty";
        }
        if (trimmed.startsWith("```")) {
            return "fenced";
        }
        if (trimmed.startsWith("{")) {
            return trimmed.endsWith("}") ? "json-shaped but unparsable (likely an unescaped quote)" : "json-shaped and cut off";
        }
        return "prose";
    }

    private void logUnusableStructuredResponse(AiRequest request, AiExecutionResult result) {
        String content = result.content() != null ? result.content() : "";
        logger.warn(
            "AI endpoint accepted response_format for action={} but answered without usable JSON "
                + "({} chars, {}); retrying once without the schema. Enable debug logging for the response prefix.",
            request.action(),
            content.length(),
            unusableAnswerClass(content));
        if (logger.isDebugEnabled()) {
            logger.debug(
                "Unusable structured response for action={}: {}",
                request.action(),
                content.substring(0, Math.min(content.length(), UNUSABLE_RESPONSE_LOG_CHARS)));
        }
    }

    private AiExecutionResult executeAiRequestWithTokenParameterFallback(
        AiRequest request,
        HttpClient client,
        Duration timeout,
        AiSkillRelevanceClassifier skillClassifier,
        String effectiveModel,
        boolean includeStructuredResponseFormat,
        boolean enforceJsonOnlyInstruction) throws Exception {

        try {
            return executeRequestWithClient(
                buildAiRequestBody(
                    request,
                    skillClassifier,
                    effectiveModel,
                    CompletionTokenParameter.MAX_TOKENS,
                    includeStructuredResponseFormat,
                    enforceJsonOnlyInstruction),
                timeout,
                client,
                AiOutputTokenLimitSupport.actionLimit(request) != null);
        } catch (AiApiException e) {
            if (AiOutputTokenLimitSupport.resolve(request, defaultMaxCompletionTokens) == null
                || !isUnsupportedMaxTokensError(e)) {
                throw e;
            }
            return executeRequestWithClient(
                buildAiRequestBody(
                    request,
                    skillClassifier,
                    effectiveModel,
                    CompletionTokenParameter.MAX_COMPLETION_TOKENS,
                    includeStructuredResponseFormat,
                    enforceJsonOnlyInstruction),
                timeout,
                client,
                AiOutputTokenLimitSupport.actionLimit(request) != null);
        }
    }

    private static boolean isUnsupportedMaxTokensError(AiApiException error) {
        if (error == null || error.statusCode() != 400 || error.getMessage() == null) {
            return false;
        }
        String detail = error.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (!detail.contains("max_tokens")) {
            return false;
        }
        return detail.contains("max_completion_tokens")
            || detail.contains("unsupported parameter")
            || detail.contains("unknown parameter");
    }

    private static boolean isUnsupportedStructuredOutputError(AiApiException error) {
        if (error == null || error.statusCode() != 400 || error.getMessage() == null) {
            return false;
        }
        String detail = error.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (!detail.contains("response_format") && !detail.contains("json_schema")) {
            return false;
        }
        return detail.contains("unsupported parameter")
            || detail.contains("unknown parameter")
            || detail.contains("not supported")
            || detail.contains("does not support");
    }

    AiExecutionResult executePromptWithClient(String systemPrompt, String userPrompt, HttpClient client, Duration timeout) throws Exception {
        return executePromptWithClient(systemPrompt, userPrompt, client, timeout, false);
    }

    AiExecutionResult executePromptWithClient(
        String systemPrompt,
        String userPrompt,
        HttpClient client,
        Duration timeout,
        boolean jsonResponseFormat) throws Exception {
        return executePromptWithClient(
            systemPrompt,
            userPrompt,
            client,
            timeout,
            jsonResponseFormat,
            true);
    }

    private AiExecutionResult executePromptWithClient(
        String systemPrompt,
        String userPrompt,
        HttpClient client,
        Duration timeout,
        boolean jsonResponseFormat,
        boolean includeAgentSkills) throws Exception {
        return executePromptWithClient(
            systemPrompt, userPrompt, null, client, timeout, jsonResponseFormat, includeAgentSkills);
    }

    private AiExecutionResult executePromptWithClient(
        String systemPrompt,
        String userPrompt,
        List<AiImageInput> images,
        HttpClient client,
        Duration timeout,
        boolean jsonResponseFormat,
        boolean includeAgentSkills) throws Exception {

        String effectiveModel = resolveModelForRequest(client);
        AiSkillRelevanceClassifier skillClassifier = createSkillClassifier(client, effectiveModel);
        String effectiveSystemPrompt = includeAgentSkills
            ? skillPromptSupport.appendAgentSkills(systemPrompt, userPrompt, skillClassifier)
            : normalizePrompt(systemPrompt);
        boolean hasImages = images != null && !images.isEmpty();
        if (!hasImages && webSearchTool != null && AiInternetPromptSupport.isPromptInternetEligible(userPrompt)) {
            return executeToolAwareMessages(
                buildPromptMessages(AiInternetPromptSupport.appendRules(effectiveSystemPrompt), userPrompt),
                client,
                timeout,
                jsonResponseFormat,
                effectiveModel);
        }
        String requestBody = buildPromptRequestBody(
            effectiveSystemPrompt,
            userPrompt,
            images,
            0.2,
            jsonResponseFormat,
            effectiveModel);
        try {
            return executeRequestWithClient(requestBody, timeout, client, false);
        } catch (ModelNotLoadedException e) {
            String retryModel = reresolveForRetry(client);
            if (retryModel == null || retryModel.equals(effectiveModel)) {
                throw e;
            }
            return executeRequestWithClient(
                buildPromptRequestBody(effectiveSystemPrompt, userPrompt, images, 0.2, jsonResponseFormat, retryModel),
                timeout,
                client,
                false);
        }
    }

    private static String normalizePrompt(String prompt) {
        return prompt != null ? prompt.trim() : "";
    }

    /**
     * Sends a chat completion as an SSE stream and falls back to a buffered request when the
     * endpoint rejects streaming. Streaming keeps response bytes flowing while the model
     * generates; a buffered request stays byte-silent for the whole generation, which lets
     * API gateways cut the idle connection during multi-minute runs (observed with MiniMax
     * as "EOF reached while reading" after ~4.5 minutes of full code analysis).
     */
    private AiExecutionResult executeRequestWithClient(
        String requestBody,
        Duration timeout,
        HttpClient client,
        boolean returnTruncatedResult) throws Exception {

        if (apiUrl.isBlank()) {
            throw new IllegalStateException("AI API URL must be configured.");
        }
        try {
            AiExecutionResult result = executeStreamingRequest(
                buildJsonPostRequest(enableStreaming(requestBody), timeout),
                timeout,
                client,
                returnTruncatedResult);
            if (!result.streamInterrupted()) {
                return result;
            }
            // Losing the connection mid-answer is transient, unlike a model that stopped at its
            // output-token limit: that would recur on every attempt and only burn the budget
            // again. One more attempt is worth it because the alternative is discarding a
            // multi-minute generation — and, for a staged workflow, every stage before it.
            logger.warn("AI response stream was cut short; retrying the request once.");
            return executeStreamingRequest(
                buildJsonPostRequest(enableStreaming(requestBody), timeout),
                timeout,
                client,
                returnTruncatedResult);
        } catch (AiApiException e) {
            if (isUnsupportedStreamingError(e)) {
                logger.info("AI endpoint rejected streaming ({}); retrying without streaming.", e.getMessage());
                return executeBufferedRequest(buildJsonPostRequest(requestBody, timeout), client, returnTruncatedResult);
            }
            // The thinking object is only sent to endpoints that look like MiniMax, so this is a
            // safety net for a proxy or an older deployment that does not know it — without it,
            // such an endpoint would reject every single request.
            if (isUnsupportedThinkingError(e) && carriesThinkingMode(requestBody)) {
                logger.info("AI endpoint rejected the thinking parameter ({}); retrying without it.", e.getMessage());
                return executeRequestWithClient(
                    withoutThinkingMode(requestBody), timeout, client, returnTruncatedResult);
            }
            throw e;
        }
    }

    private AiExecutionResult executeStreamingRequest(
        HttpRequest httpRequest,
        Duration timeout,
        HttpClient client,
        boolean returnTruncatedResult) throws Exception {

        // The model generates while the body streams, so the power-management scope must cover
        // the body read as well — the send only delivers the response headers here.
        return AiPowerManagementScope.call(() -> {
            HttpResponse<InputStream> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            ResponseBody body = readResponseBodyDetailed(response.body(), timeout);
            String responseBody = body.text();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw apiError(response.statusCode(), responseBody);
            }
            AiExecutionResult result;
            if (isEventStreamResponse(response, responseBody)) {
                JsonObject aggregated = aggregateStreamedResponse(responseBody);
                result = aggregated != null ? parseJsonResponseBody(aggregated) : null;
            } else {
                result = parseResponseBody(responseBody);
            }
            // A stream cut short never delivers a finish_reason, so the aggregated result would
            // look complete. The buffered path marks a salvaged body fail-closed for the same
            // reason; without this, rejectTruncatedReplacement would let a half-written
            // replacement through as if the model had finished it.
            if (body.salvaged()) {
                result = markStreamInterrupted(result);
            }
            return finishExecutionResult(result, returnTruncatedResult);
        });
    }

    private AiExecutionResult executeBufferedRequest(
        HttpRequest httpRequest,
        HttpClient client,
        boolean returnTruncatedResult) throws Exception {

        HttpResponse<InputStream> response = AiPowerManagementScope.call(
            () -> client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream()));
        String responseBody = readResponseBody(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw apiError(response.statusCode(), responseBody);
        }
        return finishExecutionResult(parseResponseBody(responseBody), returnTruncatedResult);
    }

    /**
     * @return {@code result} marked as cut short by a dropped connection. {@code outputTruncated}
     *     is set too, so every fail-closed caller keeps working unchanged, while the extra flag
     *     lets the reason be reported — and retried — as the transient failure it is.
     */
    private static AiExecutionResult markStreamInterrupted(AiExecutionResult result) {
        if (result == null) {
            return null;
        }
        return new AiExecutionResult(result.content(), result.usage(), result.reasoning(), true, true);
    }

    private AiExecutionResult finishExecutionResult(
        AiExecutionResult result,
        boolean returnTruncatedResult) throws IOException {

        String content = result != null ? result.content() : null;
        if (content == null || content.isBlank()) {
            // Bounded snippet actions need the provider's truncation marker even when a reasoning
            // model consumed the entire completion budget before emitting visible content. Their
            // workflow records the usage and maps this fail-closed result to the localized output-
            // limit status (or the deterministic Mermaid fallback) without retrying the model.
            if (returnTruncatedResult && result != null && result.outputTruncated()) {
                return result;
            }
            throw new EmptyResponseException(result);
        }
        return new AiExecutionResult(
            content.trim(),
            result != null ? result.usage() : null,
            result != null ? result.reasoning() : null,
            result != null && result.outputTruncated(),
            // Trimming the content must not launder away how the answer ended: dropping this
            // would silence both the retry and the interrupted-connection message.
            result != null && result.streamInterrupted());
    }

    /** @return {@code requestBody} with SSE streaming and streamed token usage requested. */
    static String enableStreaming(String requestBody) {
        JsonObject root = JsonParser.parseString(requestBody).getAsJsonObject();
        root.addProperty("stream", true);
        // Without this, most endpoints omit the usage payload from the stream entirely.
        JsonObject streamOptions = new JsonObject();
        streamOptions.addProperty("include_usage", true);
        root.add("stream_options", streamOptions);
        return GSON.toJson(root);
    }

    private static boolean isEventStreamResponse(HttpResponse<?> response, String responseBody) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (contentType.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
            return true;
        }
        // Test doubles and misconfigured proxies may drop the header; the SSE framing is
        // unambiguous enough to detect from the payload itself.
        return responseBody != null && responseBody.stripLeading().startsWith("data:");
    }

    private static boolean isUnsupportedStreamingError(AiApiException error) {
        if (error == null || error.statusCode() != 400 || error.getMessage() == null) {
            return false;
        }
        return error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("stream");
    }

    /**
     * Folds an OpenAI-compatible SSE chat stream back into the non-streaming response shape so
     * the regular parsing (content, reasoning, usage, finish_reason) applies unchanged. Returns
     * {@code null} when no chunk could be parsed. Chunks after a salvaged early EOF are simply
     * missing, which yields the partial content — matching the buffered path's salvage behavior.
     */
    JsonObject aggregateStreamedResponse(String sseBody) throws IOException {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        String finishReason = null;
        JsonObject usage = null;
        boolean sawChunk = false;
        boolean sawContent = false;
        for (String rawLine : sseBody.split("\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring("data:".length()).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            JsonObject chunk;
            try {
                chunk = JsonParser.parseString(payload).getAsJsonObject();
            } catch (Exception truncatedChunk) {
                // A salvaged early EOF can leave a half-written final line; keep what parsed.
                continue;
            }
            if (chunk.has("error") && !chunk.get("error").isJsonNull()) {
                throw new IOException("AI API streaming error: " + extractErrorMessage(payload));
            }
            sawChunk = true;
            JsonObject chunkUsage = chunk.has("usage") && chunk.get("usage").isJsonObject()
                ? chunk.getAsJsonObject("usage")
                : null;
            if (chunkUsage != null) {
                usage = chunkUsage;
            }
            JsonArray choices = chunk.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty() || !choices.get(0).isJsonObject()) {
                continue;
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            String chunkFinishReason = stringField(choice, "finish_reason", null);
            if (chunkFinishReason != null && !chunkFinishReason.isBlank()) {
                finishReason = chunkFinishReason;
            }
            JsonObject delta = choice.has("delta") && choice.get("delta").isJsonObject()
                ? choice.getAsJsonObject("delta")
                : choice.getAsJsonObject("message");
            if (delta == null) {
                continue;
            }
            JsonElement deltaContent = delta.get("content");
            if (deltaContent != null && deltaContent.isJsonPrimitive()) {
                content.append(deltaContent.getAsString());
                sawContent = true;
            }
            for (String field : new String[] {"reasoning_content", "reasoning"}) {
                JsonElement deltaReasoning = delta.get(field);
                if (deltaReasoning != null && deltaReasoning.isJsonPrimitive()) {
                    reasoning.append(deltaReasoning.getAsString());
                }
            }
        }
        if (!sawChunk) {
            return null;
        }
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        if (sawContent) {
            message.addProperty("content", content.toString());
        } else {
            // Reasoning-only streams never emit a content delta; JSON null keeps the buffered
            // path's fail-closed handling for a consumed completion budget.
            message.add("content", com.google.gson.JsonNull.INSTANCE);
        }
        if (reasoning.length() > 0) {
            message.addProperty("reasoning_content", reasoning.toString());
        }
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        if (finishReason != null) {
            choice.addProperty("finish_reason", finishReason);
        }
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject root = new JsonObject();
        root.add("choices", choices);
        if (usage != null) {
            root.add("usage", usage);
        }
        return root;
    }

    private AiExecutionResult executeToolAwareMessages(
        JsonArray messages,
        HttpClient client,
        Duration timeout,
        boolean jsonResponseFormat,
        String effectiveModel) throws Exception {

        List<AiTokenUsage> usageEntries = new ArrayList<>();
        List<String> reasoningEntries = new ArrayList<>();
        for (int round = 0; round <= MAX_WEB_TOOL_ROUNDS; round++) {
            String body = buildMessagesRequestBody(messages, 0.2, jsonResponseFormat, true, effectiveModel);
            HttpRequest request = buildJsonPostRequest(body, timeout);
            HttpResponse<InputStream> response = AiPowerManagementScope.call(
                () -> client.send(request, HttpResponse.BodyHandlers.ofInputStream()));
            String responseBody = readResponseBody(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw apiError(response.statusCode(), responseBody);
            }
            JsonObject root = parseResponseRoot(responseBody);
            if (root == null) {
                AiExecutionResult parsed = parseResponseBody(responseBody);
                String content = parsed != null ? parsed.content() : null;
                if (content == null || content.isBlank()) {
                    throw new EmptyResponseException(parsed);
                }
                if (parsed != null && parsed.usage() != null) {
                    usageEntries.add(parsed.usage());
                }
                return new AiExecutionResult(
                    content.trim(),
                    mergeUsage(usageEntries),
                    mergeReasoning(reasoningEntries, parsed != null ? parsed.reasoning() : null),
                    parsed != null && parsed.outputTruncated());
            }
            AiTokenUsage usage = parseUsage(root);
            if (usage != null) {
                usageEntries.add(usage);
            }
            JsonObject message = firstAssistantMessage(root);
            JsonArray toolCalls = message != null ? message.getAsJsonArray("tool_calls") : null;
            if (toolCalls != null && !toolCalls.isEmpty()) {
                // Keep the reasoning of tool-call rounds; the final answer's reasoning alone
                // would drop the model's earlier thinking. The non-tool final round is covered
                // by the parsed result below, so it is not collected here twice.
                String roundReasoning = extractReasoning(message);
                if (roundReasoning != null && !roundReasoning.isBlank()) {
                    reasoningEntries.add(roundReasoning);
                }
                JsonArray limitedToolCalls = limitToolCallsForRequest(toolCalls);
                if (round >= MAX_WEB_TOOL_ROUNDS) {
                    messages.add(copyAssistantToolCallMessage(message, limitedToolCalls));
                    for (JsonElement toolCallElement : limitedToolCalls) {
                        messages.add(buildToolRoundLimitMessage(toolCallElement));
                    }
                    messages.add(buildToolRoundLimitInstructionMessage());
                    return executeFinalMessagesWithoutTools(
                        messages, client, timeout, jsonResponseFormat, usageEntries, reasoningEntries, effectiveModel);
                }
                messages.add(copyAssistantToolCallMessage(message, limitedToolCalls));
                for (JsonElement toolCallElement : limitedToolCalls) {
                    messages.add(buildToolResultMessage(toolCallElement));
                }
                continue;
            }

            AiExecutionResult parsed = parseJsonResponseBody(root);
            String content = parsed != null ? parsed.content() : null;
            if (content == null || content.isBlank()) {
                throw new EmptyResponseException(parsed);
            }
            return new AiExecutionResult(
                content.trim(),
                mergeUsage(usageEntries),
                mergeReasoning(reasoningEntries, parsed != null ? parsed.reasoning() : null),
                parsed != null && parsed.outputTruncated());
        }
        throw new IOException("Web search did not finish within " + MAX_WEB_TOOL_ROUNDS + " tool rounds.");
    }

    private AiExecutionResult executeFinalMessagesWithoutTools(
        JsonArray messages,
        HttpClient client,
        Duration timeout,
        boolean jsonResponseFormat,
        List<AiTokenUsage> usageEntries,
        List<String> reasoningEntries,
        String effectiveModel) throws Exception {

        String body = buildMessagesRequestBody(messages, 0.2, jsonResponseFormat, false, effectiveModel);
        HttpRequest request = buildJsonPostRequest(body, timeout);
        HttpResponse<InputStream> response = AiPowerManagementScope.call(
            () -> client.send(request, HttpResponse.BodyHandlers.ofInputStream()));
        String responseBody = readResponseBody(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw apiError(response.statusCode(), responseBody);
        }
        JsonObject root = parseResponseRoot(responseBody);
        AiExecutionResult parsed;
        if (root != null) {
            AiTokenUsage usage = parseUsage(root);
            if (usage != null) {
                usageEntries.add(usage);
            }
            parsed = parseJsonResponseBody(root);
        } else {
            parsed = parseResponseBody(responseBody);
            if (parsed != null && parsed.usage() != null) {
                usageEntries.add(parsed.usage());
            }
        }
        String content = parsed != null ? parsed.content() : null;
        if (content == null || content.isBlank()) {
            throw new IOException("AI API returned an empty response after the web search limit was reached.");
        }
        return new AiExecutionResult(
            content.trim(),
            mergeUsage(usageEntries),
            mergeReasoning(reasoningEntries, parsed != null ? parsed.reasoning() : null),
            parsed != null && parsed.outputTruncated());
    }

    /**
     * Joins the reasoning captured from tool-call rounds with the final answer's reasoning, so
     * multi-round web-tool runs keep the model's full thinking. Returns {@code null} when no
     * round produced any reasoning.
     */
    private static String mergeReasoning(List<String> reasoningEntries, String finalReasoning) {
        List<String> values = new ArrayList<>();
        if (reasoningEntries != null) {
            for (String entry : reasoningEntries) {
                if (entry != null && !entry.isBlank()) {
                    values.add(entry);
                }
            }
        }
        if (finalReasoning != null && !finalReasoning.isBlank()) {
            values.add(finalReasoning);
        }
        return values.isEmpty() ? null : String.join("\n\n", values);
    }

    private JsonObject firstAssistantMessage(JsonObject root) {
        if (root == null) {
            return null;
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty() || !choices.get(0).isJsonObject()) {
            return null;
        }
        JsonObject firstChoice = choices.get(0).getAsJsonObject();
        return firstChoice.getAsJsonObject("message");
    }

    private JsonObject copyAssistantToolCallMessage(JsonObject message, JsonArray toolCalls) {
        JsonObject assistant = new JsonObject();
        assistant.addProperty("role", "assistant");
        JsonElement content = message != null ? message.get("content") : null;
        if (content == null || content.isJsonNull()) {
            assistant.add("content", com.google.gson.JsonNull.INSTANCE);
        } else {
            assistant.add("content", content.deepCopy());
        }
        assistant.add("tool_calls", toolCalls.deepCopy());
        return assistant;
    }

    private JsonArray limitToolCallsForRequest(JsonArray toolCalls) {
        if (toolCalls == null || toolCalls.size() <= MAX_TOOL_CALLS_PER_REQUEST) {
            return toolCalls;
        }
        logger.warn(
            "AI API returned {} tool calls; processing only the first {}.",
            toolCalls.size(),
            MAX_TOOL_CALLS_PER_REQUEST);
        JsonArray limitedToolCalls = new JsonArray();
        for (int i = 0; i < MAX_TOOL_CALLS_PER_REQUEST; i++) {
            limitedToolCalls.add(toolCalls.get(i).deepCopy());
        }
        return limitedToolCalls;
    }

    private JsonObject buildToolResultMessage(JsonElement toolCallElement) {
        JsonObject toolMessage = new JsonObject();
        toolMessage.addProperty("role", "tool");
        JsonObject toolCall = toolCallElement != null && toolCallElement.isJsonObject()
            ? toolCallElement.getAsJsonObject()
            : new JsonObject();
        String toolCallId = stringField(toolCall, "id", "web-search");
        toolMessage.addProperty("tool_call_id", toolCallId);
        toolMessage.addProperty("content", executeToolCall(toolCall));
        return toolMessage;
    }

    private JsonObject buildToolRoundLimitMessage(JsonElement toolCallElement) {
        JsonObject toolMessage = new JsonObject();
        toolMessage.addProperty("role", "tool");
        JsonObject toolCall = toolCallElement != null && toolCallElement.isJsonObject()
            ? toolCallElement.getAsJsonObject()
            : new JsonObject();
        toolMessage.addProperty("tool_call_id", stringField(toolCall, "id", "web-search"));
        JsonObject error = new JsonObject();
        error.addProperty("status", "error");
        error.addProperty("provider", "kortty");
        error.addProperty("errorType", "tool_round_limit");
        error.addProperty("message", "Web search was stopped after " + MAX_WEB_TOOL_ROUNDS + " tool rounds. Use the available tool results; if they are insufficient, say that the web lookup did not complete.");
        error.addProperty("maxToolRounds", MAX_WEB_TOOL_ROUNDS);
        JsonObject function = toolCall.getAsJsonObject("function");
        String query = extractToolQuery(function);
        if (!query.isBlank()) {
            error.addProperty("query", query);
        }
        toolMessage.addProperty("content", GSON.toJson(error));
        return toolMessage;
    }

    private JsonObject buildToolRoundLimitInstructionMessage() {
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty(
            "content",
            "Web search has reached KorTTY's tool-round limit. Do not call any more tools. Produce the final answer now, follow the original requested response format, use only available tool results and local context, and explicitly state if the web lookup did not complete.");
        return user;
    }

    private String executeToolCall(JsonObject toolCall) {
        JsonObject function = toolCall != null ? toolCall.getAsJsonObject("function") : null;
        String name = stringField(function, "name", "");
        if (!WEB_SEARCH_TOOL_NAME.equals(name)) {
            JsonObject error = new JsonObject();
            error.addProperty("status", "error");
            error.addProperty("provider", "kortty");
            error.addProperty("errorType", "unsupported_tool");
            error.addProperty("message", "Unsupported tool call: " + name);
            return GSON.toJson(error);
        }
        String query = extractToolQuery(function);
        return webSearchTool.searchAsToolResult(query);
    }

    private String extractToolQuery(JsonObject function) {
        if (function == null || !function.has("arguments")) {
            return "";
        }
        JsonElement arguments = function.get("arguments");
        try {
            JsonObject args = arguments.isJsonObject()
                ? arguments.getAsJsonObject()
                : JsonParser.parseString(arguments.getAsString()).getAsJsonObject();
            return stringField(args, "query", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private String stringField(JsonObject object, String name, String fallback) {
        if (object != null && object.has(name) && object.get(name).isJsonPrimitive()) {
            return object.get(name).getAsString();
        }
        return fallback;
    }

    private AiTokenUsage mergeUsage(List<AiTokenUsage> usageEntries) {
        if (usageEntries == null || usageEntries.isEmpty()) {
            return null;
        }
        long promptTokens = 0L;
        long completionTokens = 0L;
        long totalTokens = 0L;
        long cachedPromptTokens = 0L;
        for (AiTokenUsage usage : usageEntries) {
            if (usage == null) {
                continue;
            }
            promptTokens += usage.promptTokens();
            completionTokens += usage.completionTokens();
            totalTokens += usage.totalTokens();
            cachedPromptTokens += usage.cachedPromptTokens();
        }
        return new AiTokenUsage(promptTokens, completionTokens, totalTokens, cachedPromptTokens);
    }

    private String resolveModelForRequest(HttpClient client) throws IOException, InterruptedException {
        return LocalLmModelResolver.resolve(apiUrl, model, modelSelectionMode, apiKey, client);
    }

    /** Thrown when the server reports the requested model is not loaded (e.g. LM Studio JIT off). */
    private static final class ModelNotLoadedException extends IOException {
        ModelNotLoadedException(String message) {
            super(message);
        }
    }

    /** Thrown for a non-2xx API response; carries the HTTP status so callers can classify it. */
    public static final class AiApiException extends IOException {
        private final int statusCode;

        AiApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    /**
     * Thrown when a 2xx reply carried no usable content. Small local models produce this
     * stochastically, so the embedded services treat it as retryable.
     */
    public static final class EmptyResponseException extends IOException {
        private final transient AiExecutionResult partialResult;

        EmptyResponseException() {
            this(null);
        }

        EmptyResponseException(AiExecutionResult partialResult) {
            super("AI API returned an empty response.");
            this.partialResult = partialResult;
        }

        /**
         * @return the parsed reply whose content was empty — it still carries the model's
         *     reasoning and the billed usage — or {@code null} when nothing could be parsed.
         */
        public AiExecutionResult partialResult() {
            return partialResult;
        }
    }

    /** Builds the exception for a non-2xx response, with an actionable hint for the not-loaded case. */
    private IOException apiError(int status, String body) {
        String detail = extractErrorMessage(body);
        if (isModelNotLoadedError(body)) {
            return new ModelNotLoadedException(modelNotLoadedMessage(detail));
        }
        return new AiApiException(status, "AI API error " + status + ": " + detail);
    }

    static boolean isModelNotLoadedError(String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("has not started loading")
            || lower.contains("has been unloaded")
            || lower.contains("no models loaded");
    }

    private String modelNotLoadedMessage(String detail) {
        String hint = modelSelectionMode == AiModelSelectionMode.AUTO
            ? "Load a model in LM Studio (and keep one loaded), then retry."
            : "Load the configured model in LM Studio, enable JIT model loading, or set the profile model to Auto.";
        return "The AI model is not loaded in LM Studio: " + detail + " — " + hint;
    }

    /**
     * For AUTO profiles on a resolvable LM Studio endpoint, re-resolves the currently loaded model so
     * a request can be retried once after a "model not loaded" error (e.g. the model was unloaded
     * between resolution and the call). Returns null when no different usable model can be resolved.
     */
    private String reresolveForRetry(HttpClient client) {
        if (modelSelectionMode != AiModelSelectionMode.AUTO || !LocalLmModelResolver.canResolve(apiUrl)) {
            return null;
        }
        try {
            String resolved = resolveModelForRequest(client);
            return resolved != null && !resolved.isBlank() ? resolved : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public boolean testConnection() {
        try {
            HttpClient testClient = HttpClient.newBuilder().connectTimeout(TEST_CONNECT_TIMEOUT).build();
            AiExecutionResult result = executeConnectionTestWithClient(testClient, TEST_REQUEST_TIMEOUT);
            return result != null && result.content() != null && !result.content().isBlank();
        } catch (Exception e) {
            logger.warn("AI API test connection failed: {}", e.getMessage());
            return false;
        }
    }

    HttpRequest buildHttpRequest(AiRequest request) {
        return buildHttpRequest(request, null);
    }

    HttpRequest buildHttpRequest(AiRequest request, Duration timeout) {
        return buildHttpRequest(request, timeout, null);
    }

    private HttpRequest buildHttpRequest(
        AiRequest request,
        Duration timeout,
        AiSkillRelevanceClassifier skillClassifier) {
        return buildHttpRequest(request, timeout, skillClassifier, model);
    }

    private HttpRequest buildHttpRequest(
        AiRequest request,
        Duration timeout,
        AiSkillRelevanceClassifier skillClassifier,
        String effectiveModel) {
        return buildHttpRequest(
            request,
            timeout,
            skillClassifier,
            effectiveModel,
            CompletionTokenParameter.MAX_TOKENS);
    }

    private HttpRequest buildHttpRequest(
        AiRequest request,
        Duration timeout,
        AiSkillRelevanceClassifier skillClassifier,
        String effectiveModel,
        CompletionTokenParameter completionTokenParameter) {
        return buildHttpRequest(
            request,
            timeout,
            skillClassifier,
            effectiveModel,
            completionTokenParameter,
            true);
    }

    private HttpRequest buildHttpRequest(
        AiRequest request,
        Duration timeout,
        AiSkillRelevanceClassifier skillClassifier,
        String effectiveModel,
        CompletionTokenParameter completionTokenParameter,
        boolean includeStructuredResponseFormat) {
        return buildJsonPostRequest(
            buildAiRequestBody(
                request,
                skillClassifier,
                effectiveModel,
                completionTokenParameter,
                includeStructuredResponseFormat,
                false),
            timeout);
    }

    private String buildAiRequestBody(
        AiRequest request,
        AiSkillRelevanceClassifier skillClassifier,
        String effectiveModel,
        CompletionTokenParameter completionTokenParameter,
        boolean includeStructuredResponseFormat,
        boolean enforceJsonOnlyInstruction) {
        if (apiUrl.isBlank()) {
            throw new IllegalStateException("AI API URL must be configured.");
        }
        boolean includeTools = webSearchTool != null && AiInternetPromptSupport.isInternetEligible(request);
        JsonArray messages = buildRequestMessages(request, includeTools, skillClassifier);
        if (enforceJsonOnlyInstruction && usesStructuredJsonSchema(request)) {
            JsonObject instruction = new JsonObject();
            instruction.addProperty("role", "user");
            instruction.addProperty("content", JSON_ONLY_RETRY_INSTRUCTION);
            messages.add(instruction);
        } else if (usesStructuredJsonSchema(request) && usesMiniMaxThinkingParameter(effectiveModel)) {
            JsonObject reminder = new JsonObject();
            reminder.addProperty("role", "user");
            reminder.addProperty("content", JSON_ONLY_FIRST_ATTEMPT_INSTRUCTION);
            messages.add(reminder);
        }
        String body = buildMessagesRequestBody(
            messages,
            0.2,
            false,
            includeTools,
            effectiveModel,
            AiOutputTokenLimitSupport.resolve(request, defaultMaxCompletionTokens),
            completionTokenParameter);
        return appendStructuredResponseFormat(body, request, includeStructuredResponseFormat);
    }

    HttpRequest buildConnectionTestHttpRequest(Duration timeout) {
        return buildConnectionTestHttpRequest(timeout, model);
    }

    private HttpRequest buildConnectionTestHttpRequest(Duration timeout, String effectiveModel) {
        if (apiUrl.isBlank()) {
            throw new IllegalStateException("AI API URL must be configured.");
        }
        return buildJsonPostRequest(buildConnectionTestRequestBody(effectiveModel), timeout);
    }

    private HttpRequest buildJsonPostRequest(String body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (timeout != null) {
            builder.timeout(timeout);
        }
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    String buildRequestBody(AiRequest request) {
        boolean includeTools = webSearchTool != null && AiInternetPromptSupport.isInternetEligible(request);
        String body = buildMessagesRequestBody(
            buildRequestMessages(request, includeTools),
            0.2,
            false,
            includeTools,
            model,
            AiOutputTokenLimitSupport.resolve(request, defaultMaxCompletionTokens));
        return appendStructuredResponseFormat(body, request, true);
    }

    private static String appendStructuredResponseFormat(
        String body,
        AiRequest request,
        boolean includeStructuredResponseFormat) {

        if (!includeStructuredResponseFormat || !usesStructuredJsonSchema(request)) {
            return body;
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        root.add("response_format", buildStructuredResponseFormat(request));
        return GSON.toJson(root);
    }

    private static boolean usesStructuredJsonSchema(AiRequest request) {
        return request != null
            && (request.action() == AiAction.ANALYZE_SNIPPET_CODE
                || request.action() == AiAction.APPLY_SNIPPET_IMPROVEMENTS
                || request.action() == AiAction.MIGRATE_SNIPPET_LANGUAGE
                || request.action() == AiAction.GENERATE_SNIPPET_MERMAID);
    }

    private static JsonObject buildStructuredResponseFormat(AiRequest request) {
        if (request != null && request.action() == AiAction.ANALYZE_SNIPPET_CODE) {
            return buildSnippetAnalysisResponseFormat();
        }
        if (request != null && request.action() == AiAction.GENERATE_SNIPPET_MERMAID) {
            return buildSnippetDiagramResponseFormat();
        }
        if (AiPromptBuilder.isEditModeApply(request)) {
            return buildSnippetEditsResponseFormat();
        }
        if (request != null && request.action() == AiAction.MIGRATE_SNIPPET_LANGUAGE) {
            return buildLanguageMigrationResponseFormat(request);
        }
        return buildSnippetReplacementResponseFormat(request);
    }

    /** Constrains Full-code-analysis to the exact object consumed by {@link SnippetAiResponseSupport}. */
    private static JsonObject buildSnippetAnalysisResponseFormat() {
        JsonObject stringType = new JsonObject();
        stringType.addProperty("type", "string");

        JsonObject nonBlankStringType = stringType.deepCopy();
        nonBlankStringType.addProperty("minLength", 1);

        JsonObject dependencyProperties = new JsonObject();
        dependencyProperties.add("id", nonBlankStringType.deepCopy());
        dependencyProperties.add("name", nonBlankStringType.deepCopy());
        dependencyProperties.add("kind", enumStringSchema("script", "program", "service"));
        dependencyProperties.add("purpose", stringType.deepCopy());
        dependencyProperties.add("suggestion", stringType.deepCopy());

        JsonObject dependencySchema = strictObjectSchema(
            dependencyProperties,
            "id", "name", "kind", "purpose", "suggestion");
        JsonObject dependencies = arraySchema(dependencySchema);

        JsonObject lineType = new JsonObject();
        lineType.addProperty("type", "integer");
        lineType.addProperty("minimum", 1);

        JsonObject improvementProperties = new JsonObject();
        improvementProperties.add("id", nonBlankStringType.deepCopy());
        improvementProperties.add("category", enumStringSchema("security", "optimization", "design"));
        improvementProperties.add("severity", stringType.deepCopy());
        improvementProperties.add("title", nonBlankStringType.deepCopy());
        improvementProperties.add("detail", stringType.deepCopy());
        improvementProperties.add("recommendation", stringType.deepCopy());
        improvementProperties.add("line", lineType);

        JsonObject improvementSchema = strictObjectSchema(
            improvementProperties,
            "id", "category", "severity", "title", "detail", "recommendation", "line");
        JsonObject improvements = arraySchema(improvementSchema);

        JsonObject properties = new JsonObject();
        properties.add("summary", nonBlankStringType);
        properties.add("dependencies", dependencies);
        properties.add("improvements", improvements);

        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty("name", "snippet_analysis_response");
        jsonSchema.addProperty("strict", true);
        jsonSchema.add("schema", strictObjectSchema(
            properties,
            "summary", "dependencies", "improvements"));

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_schema");
        responseFormat.add("json_schema", jsonSchema);
        return responseFormat;
    }

    /**
     * Constrains a language migration to the object consumed by
     * {@link SnippetAiResponseSupport#parseLanguageMigration}: the full rewritten script plus the
     * notes naming what could not be carried over. {@code notes} is required so the model has to
     * take a position on completeness instead of leaving the field out when something was dropped.
     */
    private static JsonObject buildLanguageMigrationResponseFormat(AiRequest request) {
        JsonObject stringType = new JsonObject();
        stringType.addProperty("type", "string");

        JsonObject replacementLines = new JsonObject();
        replacementLines.addProperty("type", "array");
        replacementLines.add("items", stringType.deepCopy());
        replacementLines.addProperty("minItems", minimumReplacementLineCount(request));

        JsonObject notes = new JsonObject();
        notes.addProperty("type", "array");
        notes.add("items", stringType.deepCopy());

        JsonObject properties = new JsonObject();
        properties.add("replacementLines", replacementLines);
        properties.add("summary", stringType.deepCopy());
        properties.add("notes", notes);

        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty("name", "snippet_language_migration_response");
        jsonSchema.addProperty("strict", true);
        jsonSchema.add("schema", strictObjectSchema(
            properties, "replacementLines", "summary", "notes"));

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_schema");
        responseFormat.add("json_schema", jsonSchema);
        return responseFormat;
    }

    /**
     * Constrains a diagram answer to the object {@link SnippetAiResponseSupport#parseMermaidDiagram}
     * consumes. The value of this schema is the escaping, not the field list: korTTY's diagram
     * grammar requires quoted node labels, and a model that writes them into a hand-built JSON
     * string without escaping the quotes produces an object that parses in neither strict nor
     * lenient mode — the whole answer is then lost, however good the diagram was. An endpoint that
     * honors the schema cannot make that mistake.
     */
    private static JsonObject buildSnippetDiagramResponseFormat() {
        JsonObject stringType = new JsonObject();
        stringType.addProperty("type", "string");

        JsonObject lineType = new JsonObject();
        lineType.addProperty("type", "integer");
        lineType.addProperty("minimum", 1);

        JsonObject referenceProperties = new JsonObject();
        referenceProperties.add("nodeId", stringType.deepCopy());
        referenceProperties.add("label", stringType.deepCopy());
        referenceProperties.add("startLine", lineType.deepCopy());
        referenceProperties.add("endLine", lineType.deepCopy());
        JsonObject codeReferences = arraySchema(strictObjectSchema(
            referenceProperties, "nodeId", "label", "startLine", "endLine"));

        JsonObject mermaid = new JsonObject();
        mermaid.addProperty("type", "string");
        mermaid.addProperty("minLength", 1);

        JsonObject properties = new JsonObject();
        properties.add("title", stringType.deepCopy());
        properties.add("mermaid", mermaid);
        properties.add("codeReferences", codeReferences);

        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty("name", "snippet_diagram_response");
        jsonSchema.addProperty("strict", true);
        jsonSchema.add("schema", strictObjectSchema(properties, "title", "mermaid", "codeReferences"));

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_schema");
        responseFormat.add("json_schema", jsonSchema);
        return responseFormat;
    }

    /** Edit mode: changed regions instead of the whole script; see {@link AiPromptBuilder#isEditModeApply}. */
    private static JsonObject buildSnippetEditsResponseFormat() {
        JsonObject stringType = new JsonObject();
        stringType.addProperty("type", "string");
        JsonObject lineType = new JsonObject();
        lineType.addProperty("type", "integer");
        lineType.addProperty("minimum", 1);

        JsonObject editProperties = new JsonObject();
        editProperties.add("startLine", lineType.deepCopy());
        editProperties.add("endLine", lineType.deepCopy());
        editProperties.add("replacementLines", arraySchema(stringType.deepCopy()));
        JsonObject edits = arraySchema(strictObjectSchema(editProperties, "startLine", "endLine", "replacementLines"));

        JsonObject changeProperties = new JsonObject();
        changeProperties.add("finding", stringType.deepCopy());
        changeProperties.add("anchor", stringType.deepCopy());
        changeProperties.add("reason", stringType.deepCopy());
        JsonObject changes = arraySchema(strictObjectSchema(changeProperties, "finding", "anchor", "reason"));

        JsonObject properties = new JsonObject();
        properties.add("edits", edits);
        properties.add("summary", stringType.deepCopy());
        properties.add("changes", changes);
        properties.add("implementedRequirements", arraySchema(stringType.deepCopy()));

        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty("name", "snippet_edits_response");
        jsonSchema.addProperty("strict", true);
        jsonSchema.add("schema", strictObjectSchema(properties, "edits", "summary", "changes", "implementedRequirements"));

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_schema");
        responseFormat.add("json_schema", jsonSchema);
        return responseFormat;
    }

    private static JsonObject buildSnippetReplacementResponseFormat(AiRequest request) {
        JsonObject stringType = new JsonObject();
        stringType.addProperty("type", "string");

        JsonObject replacementLines = new JsonObject();
        replacementLines.addProperty("type", "array");
        replacementLines.add("items", stringType.deepCopy());
        replacementLines.addProperty("minItems", minimumReplacementLineCount(request));

        JsonObject changeProperties = new JsonObject();
        changeProperties.add("finding", stringType.deepCopy());
        changeProperties.add("anchor", stringType.deepCopy());
        changeProperties.add("reason", stringType.deepCopy());

        JsonObject changeSchema = new JsonObject();
        changeSchema.addProperty("type", "object");
        changeSchema.addProperty("additionalProperties", false);
        changeSchema.add("properties", changeProperties);
        changeSchema.add("required", stringArray("finding", "anchor", "reason"));

        JsonObject changes = new JsonObject();
        changes.addProperty("type", "array");
        changes.add("items", changeSchema);

        JsonObject requirementItems = new JsonObject();
        requirementItems.addProperty("type", "string");
        JsonObject implementedRequirements = new JsonObject();
        implementedRequirements.addProperty("type", "array");
        implementedRequirements.add("items", requirementItems);

        JsonObject properties = new JsonObject();
        properties.add("replacementLines", replacementLines);
        properties.add("summary", stringType.deepCopy());
        properties.add("changes", changes);
        properties.add("implementedRequirements", implementedRequirements);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        schema.add("properties", properties);
        schema.add("required", stringArray("replacementLines", "summary", "changes", "implementedRequirements"));

        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty("name", "snippet_improvement_response");
        jsonSchema.addProperty("strict", true);
        jsonSchema.add("schema", schema);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_schema");
        responseFormat.add("json_schema", jsonSchema);
        return responseFormat;
    }

    private static int minimumReplacementLineCount(AiRequest request) {
        String source = request != null && request.selectedText() != null ? request.selectedText() : "";
        int sourceLineCount = source.split("\\R", -1).length;
        return sourceLineCount >= 12 ? Math.max(3, sourceLineCount / 2) : 1;
    }

    private static JsonArray stringArray(String... values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            for (String value : values) {
                array.add(value);
            }
        }
        return array;
    }

    private static JsonObject strictObjectSchema(JsonObject properties, String... requiredNames) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        schema.add("properties", properties);
        schema.add("required", stringArray(requiredNames));
        return schema;
    }

    private static JsonObject arraySchema(JsonObject itemSchema) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "array");
        schema.add("items", itemSchema);
        return schema;
    }

    private static JsonObject enumStringSchema(String... values) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.add("enum", stringArray(values));
        return schema;
    }

    private JsonArray buildRequestMessages(AiRequest request, boolean includeInternetRules) {
        return buildRequestMessages(request, includeInternetRules, null);
    }

    private JsonArray buildRequestMessages(
        AiRequest request,
        boolean includeInternetRules,
        AiSkillRelevanceClassifier skillClassifier) {
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        String systemPrompt = skillPromptSupport.appendChatSkills(
            AiPromptBuilder.buildSystemPrompt(request),
            request,
            skillClassifier);
        systemPrompt = AiPromptPipeline.appendAfterSkills(systemPrompt, request);
        system.addProperty("content", includeInternetRules ? AiInternetPromptSupport.appendRules(systemPrompt) : systemPrompt);
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", AiPromptBuilder.buildUserPrompt(request));
        messages.add(user);
        return messages;
    }

    private AiSkillRelevanceClassifier createSkillClassifier(HttpClient client, String effectiveModel) {
        return (context, skills) -> classifyRelevantSkills(context, skills, client, effectiveModel);
    }

    private List<String> classifyRelevantSkills(
        AiSkillRelevanceSelector.SelectionContext context,
        List<AiSkillRelevanceSelector.SkillMetadata> skills,
        HttpClient client,
        String effectiveModel) throws Exception {

        if (skills == null || skills.isEmpty()) {
            return List.of();
        }
        String body = buildMessagesRequestBody(
            buildPromptMessages(
                AiSkillRelevanceSelector.classificationSystemPrompt(),
                AiSkillRelevanceSelector.buildClassificationUserPrompt(context, skills)),
            0.0,
            true,
            false,
            effectiveModel);
        HttpRequest request = buildJsonPostRequest(body, SKILL_CLASSIFICATION_TIMEOUT);
        HttpResponse<InputStream> response = AiPowerManagementScope.call(
            () -> client.send(request, HttpResponse.BodyHandlers.ofInputStream()));
        String responseBody = readResponseBody(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("AI skill classification failed with status " + response.statusCode());
        }
        AiExecutionResult result = parseResponseBody(responseBody);
        return AiSkillRelevanceSelector.parseClassifierResponse(result != null ? result.content() : null);
    }

    String buildConnectionTestRequestBody() {
        return buildConnectionTestRequestBody(model);
    }

    private String buildConnectionTestRequestBody(String effectiveModel) {
        String body = buildPromptRequestBody(
            CONNECTION_TEST_SYSTEM_PROMPT,
            CONNECTION_TEST_USER_PROMPT,
            0.0,
            false,
            effectiveModel);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        root.addProperty("max_tokens", 128);
        return GSON.toJson(root);
    }

    String buildPromptRequestBody(String systemPrompt, String userPrompt) {
        return buildPromptRequestBody(systemPrompt, userPrompt, false);
    }

    String buildPromptRequestBody(String systemPrompt, String userPrompt, boolean jsonResponseFormat) {
        return buildPromptRequestBody(systemPrompt, userPrompt, 0.2, jsonResponseFormat);
    }

    private String buildPromptRequestBody(String systemPrompt, String userPrompt, double temperature) {
        return buildPromptRequestBody(systemPrompt, userPrompt, temperature, false);
    }

    private String buildPromptRequestBody(
        String systemPrompt,
        String userPrompt,
        double temperature,
        boolean jsonResponseFormat) {
        return buildPromptRequestBody(systemPrompt, userPrompt, temperature, jsonResponseFormat, model);
    }

    private String buildPromptRequestBody(
        String systemPrompt,
        String userPrompt,
        double temperature,
        boolean jsonResponseFormat,
        String effectiveModel) {

        return buildPromptRequestBody(
            systemPrompt, userPrompt, null, temperature, jsonResponseFormat, effectiveModel);
    }

    private String buildPromptRequestBody(
        String systemPrompt,
        String userPrompt,
        List<AiImageInput> images,
        double temperature,
        boolean jsonResponseFormat,
        String effectiveModel) {

        return buildMessagesRequestBody(
            buildPromptMessages(systemPrompt, userPrompt, images),
            temperature,
            jsonResponseFormat,
            false,
            effectiveModel);
    }

    private JsonArray buildPromptMessages(String systemPrompt, String userPrompt) {
        return buildPromptMessages(systemPrompt, userPrompt, null);
    }

    /**
     * Without images the user {@code content} stays a plain JSON string — some OpenAI-compatible
     * servers reject the array form, so text-only requests must keep the historical wire shape.
     * With images it becomes the multimodal part array ({@code text} + {@code image_url} data URIs).
     */
    private JsonArray buildPromptMessages(String systemPrompt, String userPrompt, List<AiImageInput> images) {
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", systemPrompt != null ? systemPrompt : "");
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        if (images == null || images.isEmpty()) {
            user.addProperty("content", userPrompt != null ? userPrompt : "");
        } else {
            JsonArray parts = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", userPrompt != null ? userPrompt : "");
            parts.add(text);
            for (AiImageInput image : images) {
                JsonObject imagePart = new JsonObject();
                imagePart.addProperty("type", "image_url");
                JsonObject imageUrl = new JsonObject();
                imageUrl.addProperty("url", image.toDataUri());
                imagePart.add("image_url", imageUrl);
                parts.add(imagePart);
            }
            user.add("content", parts);
        }
        messages.add(user);
        return messages;
    }

    private String buildMessagesRequestBody(
        JsonArray messages,
        double temperature,
        boolean jsonResponseFormat,
        boolean includeTools) {
        return buildMessagesRequestBody(messages, temperature, jsonResponseFormat, includeTools, model);
    }

    private String buildMessagesRequestBody(
        JsonArray messages,
        double temperature,
        boolean jsonResponseFormat,
        boolean includeTools,
        String effectiveModel) {
        return buildMessagesRequestBody(
            messages,
            temperature,
            jsonResponseFormat,
            includeTools,
            effectiveModel,
            defaultMaxCompletionTokens);
    }

    private String buildMessagesRequestBody(
        JsonArray messages,
        double temperature,
        boolean jsonResponseFormat,
        boolean includeTools,
        String effectiveModel,
        Integer maxCompletionTokens) {
        return buildMessagesRequestBody(
            messages,
            temperature,
            jsonResponseFormat,
            includeTools,
            effectiveModel,
            maxCompletionTokens,
            CompletionTokenParameter.MAX_TOKENS);
    }

    private String buildMessagesRequestBody(
        JsonArray messages,
        double temperature,
        boolean jsonResponseFormat,
        boolean includeTools,
        String effectiveModel,
        Integer maxCompletionTokens,
        CompletionTokenParameter completionTokenParameter) {
        JsonObject root = new JsonObject();
        if (effectiveModel != null && !effectiveModel.isBlank()) {
            root.addProperty("model", effectiveModel);
        }

        root.add("messages", messages);
        root.addProperty("temperature", temperature);
        if (maxCompletionTokens != null) {
            CompletionTokenParameter parameter = completionTokenParameter != null
                ? completionTokenParameter
                : CompletionTokenParameter.MAX_TOKENS;
            root.addProperty(parameter.jsonName, maxCompletionTokens);
        }
        appendReasoningEffort(root);
        appendThinkingMode(root, effectiveModel);
        if (jsonResponseFormat) {
            JsonObject responseFormat = new JsonObject();
            responseFormat.addProperty("type", "json_object");
            root.add("response_format", responseFormat);
        }
        if (includeTools) {
            root.add("tools", buildWebSearchTools());
            root.addProperty("tool_choice", "auto");
        }
        return GSON.toJson(root);
    }

    private JsonArray buildWebSearchTools() {
        JsonArray tools = new JsonArray();
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        JsonObject function = new JsonObject();
        function.addProperty("name", WEB_SEARCH_TOOL_NAME);
        function.addProperty("description", "Search the public web for current or external information. Return source URLs in the final answer.");
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty("description", "Search query.");
        properties.add("query", query);
        parameters.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("query");
        parameters.add("required", required);
        parameters.addProperty("additionalProperties", false);
        function.add("parameters", parameters);
        tool.add("function", function);
        tools.add(tool);
        return tools;
    }

    private void appendReasoningEffort(JsonObject root) {
        if (reasoningEffort.isApiEnabled()) {
            root.addProperty("reasoning_effort", reasoningEffort.apiValue());
        }
    }

    /**
     * Carries a "no reasoning" profile to endpoints that do not read {@code reasoning_effort}.
     *
     * <p>MiniMax steers reasoning with its own {@code thinking} object and ignores
     * {@code reasoning_effort} entirely, so a profile set to disabled reached it as no parameter at
     * all and the model applied its default: it then spent whole completion budgets on thinking
     * that the response never exposes — 54 881 of 54 881 tokens on a 5.7 KB script, and 8 190 of
     * 8 192 on a diagram — and was cut off before finishing the answer.
     *
     * <p>{@code disabled} rather than {@code adaptive}: adaptive was tried first and still
     * thought up to the ceiling on hard work items — 65 536 of 65 536 tokens on a taint-mode
     * rewrite, after 24 lighter items had passed — because M3 scales its thinking to whatever
     * budget it is handed. Both "off" values are mapped — a disabled profile and an explicit
     * {@code none}. The latter used to slip through because it carries an API value
     * ({@code reasoning_effort: none}), which MiniMax ignores, so the diagram request that korTTY
     * itself pins to {@code none} arrived with no thinking object and M3 thought anyway: a
     * 130-line script came back as 4 853 completion tokens opening with {@code <think>}. An
     * explicit effort level is different — that is the user asking for reasoning, and overriding
     * it with a weaker mode would silently ignore them too.
     */
    private void appendThinkingMode(JsonObject root, String effectiveModel) {
        boolean reasoningOff = !reasoningEffort.isApiEnabled() || reasoningEffort == AiReasoningEffort.NONE;
        if (!reasoningOff || !usesMiniMaxThinkingParameter(effectiveModel)) {
            return;
        }
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", MINIMAX_THINKING_MODE);
        root.add("thinking", thinking);
    }

    /** Matches MiniMax both on its own host and behind an aggregator that prefixes the model name. */
    private boolean usesMiniMaxThinkingParameter(String effectiveModel) {
        String model = effectiveModel != null ? effectiveModel.toLowerCase(java.util.Locale.ROOT) : "";
        String url = apiUrl.toLowerCase(java.util.Locale.ROOT);
        return model.contains("minimax") || url.contains("minimax");
    }

    private static boolean isUnsupportedThinkingError(AiApiException error) {
        return error != null
            && error.statusCode() == 400
            && error.getMessage() != null
            && error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("thinking");
    }

    /** @return whether {@code requestBody} still carries the thinking object, guarding the retry. */
    private static boolean carriesThinkingMode(String requestBody) {
        try {
            return JsonParser.parseString(requestBody).getAsJsonObject().has("thinking");
        } catch (Exception notJson) {
            return false;
        }
    }

    private static String withoutThinkingMode(String requestBody) {
        JsonObject root = JsonParser.parseString(requestBody).getAsJsonObject();
        root.remove("thinking");
        return GSON.toJson(root);
    }

    /**
     * A response body plus whether it had to be salvaged from a stream that ended early. A salvaged
     * body is by definition incomplete, which callers must treat as fail-closed.
     */
    record ResponseBody(String text, boolean salvaged) {
    }

    String readResponseBody(InputStream responseStream) throws IOException {
        return readResponseBody(responseStream, null);
    }

    /**
     * Reads the response body, optionally bounded by {@code timeout}. The HTTP request timeout
     * only covers the wait for the response headers; on a streamed reply the model generates
     * during this body read, so the user-configured limit must be enforced here as well. The
     * check runs between reads — a stream that ticks (as token streams do) is cut close to the
     * deadline, and a timeout is a hard failure rather than a salvaged partial body.
     */
    String readResponseBody(InputStream responseStream, Duration timeout) throws IOException {
        return readResponseBodyDetailed(responseStream, timeout).text();
    }

    ResponseBody readResponseBodyDetailed(InputStream responseStream, Duration timeout) throws IOException {
        if (responseStream == null) {
            return new ResponseBody("", false);
        }
        long deadlineNanos = timeout != null ? System.nanoTime() + timeout.toNanos() : 0L;
        try (InputStream input = responseStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            while (true) {
                if (timeout != null && System.nanoTime() - deadlineNanos >= 0) {
                    throw new HttpTimeoutException(
                        "AI request exceeded the configured timeout of " + timeout.toSeconds()
                            + " s while streaming the response.");
                }
                try {
                    int read = input.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    output.write(buffer, 0, read);
                } catch (IOException ex) {
                    if (output.size() == 0) {
                        throw ex;
                    }
                    logger.warn("AI API response stream ended early: {}. Attempting to use partial response body.", ex.getMessage());
                    return new ResponseBody(output.toString(StandardCharsets.UTF_8), true);
                }
            }
            return new ResponseBody(output.toString(StandardCharsets.UTF_8), false);
        }
    }

    private AiExecutionResult executeConnectionTestWithClient(HttpClient client, Duration timeout) throws Exception {
        String effectiveModel = resolveModelForRequest(client);
        HttpRequest httpRequest = buildConnectionTestHttpRequest(timeout, effectiveModel);
        HttpResponse<InputStream> response = AiPowerManagementScope.call(
            () -> client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream()));
        String responseBody = readResponseBody(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw apiError(response.statusCode(), responseBody);
        }
        AiExecutionResult result = parseResponseBody(responseBody);
        String content = result != null ? result.content() : null;
        if (content == null || content.isBlank()) {
            throw new EmptyResponseException();
        }
        return new AiExecutionResult(
            content.trim(),
            result != null ? result.usage() : null,
            result != null ? result.reasoning() : null,
            result != null && result.outputTruncated());
    }

    AiExecutionResult parseResponseBody(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        JsonObject root = parseResponseRoot(responseBody);
        if (root != null) {
            return parseJsonResponseBody(root);
        }
        String candidateJson = extractCandidateJson(responseBody);
        return parseLenientContentFallback(candidateJson != null ? candidateJson : responseBody);
    }

    private JsonObject parseResponseRoot(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(responseBody).getAsJsonObject();
        } catch (Exception ignored) {
        }
        String candidateJson = extractCandidateJson(responseBody);
        if (candidateJson != null) {
            try {
                return JsonParser.parseString(candidateJson).getAsJsonObject();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private AiExecutionResult parseJsonResponseBody(JsonObject root) {
        if (root == null) {
            return null;
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        JsonObject firstChoice = choices.get(0).getAsJsonObject();
        JsonObject message = firstChoice.getAsJsonObject("message");
        if (message == null) {
            return null;
        }
        JsonElement content = message.get("content");
        String reasoning = extractReasoning(message);
        boolean outputTruncated = "length".equals(stringField(firstChoice, "finish_reason", ""));
        if (content == null || content.isJsonNull()) {
            // Reasoning-only local replies commonly encode the absent final answer as JSON null.
            // Keep the reasoning and the billed usage instead of discarding the whole reply: a
            // provider-reported length stop lets bounded snippet workflows fail closed with their
            // output-limit handling, and an ordinary stop lets the empty-response path see whether
            // the answer itself arrived in the reasoning channel.
            return new AiExecutionResult("", parseUsage(root), reasoning, outputTruncated);
        }
        if (content.isJsonPrimitive()) {
            return new AiExecutionResult(content.getAsString(), parseUsage(root), reasoning, outputTruncated);
        }
        if (content.isJsonArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonElement part : content.getAsJsonArray()) {
                if (!part.isJsonObject()) {
                    continue;
                }
                JsonObject obj = part.getAsJsonObject();
                if (obj.has("text")) {
                    if (builder.length() > 0) {
                        builder.append("\n");
                    }
                    builder.append(obj.get("text").getAsString());
                }
            }
            return new AiExecutionResult(builder.toString(), parseUsage(root), reasoning, outputTruncated);
        }
        return null;
    }

    /**
     * Reads the model's reasoning / chain-of-thought from an assistant message when the provider
     * exposes it. DeepSeek and most OpenAI-compatible local servers (vLLM, SGLang) use
     * {@code reasoning_content}; OpenRouter uses {@code reasoning}. Returns {@code null} when
     * neither is present or the value is not a plain string.
     */
    private String extractReasoning(JsonObject message) {
        if (message == null) {
            return null;
        }
        for (String field : new String[] {"reasoning_content", "reasoning"}) {
            JsonElement element = message.get(field);
            if (element != null && element.isJsonPrimitive()) {
                String value = element.getAsString();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private String extractCandidateJson(String responseBody) {
        java.util.regex.Matcher predictionMatcher = LOGGED_PREDICTION_PATTERN.matcher(responseBody);
        if (predictionMatcher.find()) {
            return predictionMatcher.group(1);
        }
        int firstBrace = responseBody.indexOf('{');
        int lastBrace = responseBody.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return responseBody.substring(firstBrace, lastBrace + 1);
        }
        return null;
    }

    private AiExecutionResult parseLenientContentFallback(String responseBody) {
        String content = extractJsonStringFieldLenient(responseBody, "content");
        if (content == null || content.isBlank()) {
            return null;
        }
        long promptTokens = extractLong(PROMPT_TOKENS_PATTERN, responseBody);
        long completionTokens = extractLong(COMPLETION_TOKENS_PATTERN, responseBody);
        long totalTokens = extractLong(TOTAL_TOKENS_PATTERN, responseBody);
        AiTokenUsage usage = null;
        if (promptTokens > 0 || completionTokens > 0 || totalTokens > 0) {
            usage = new AiTokenUsage(promptTokens, completionTokens, totalTokens > 0 ? totalTokens : promptTokens + completionTokens);
        }
        String reasoning = extractJsonStringFieldLenient(responseBody, "reasoning_content");
        if (reasoning == null || reasoning.isBlank()) {
            reasoning = extractJsonStringFieldLenient(responseBody, "reasoning");
        }
        // This path is reached only when the provider envelope itself could not be parsed. The
        // lenient decoder deliberately salvages text up to EOF, so it cannot prove that the
        // content (or a nested replacement string) completed. Mark it fail-closed.
        return new AiExecutionResult(
            content,
            usage,
            reasoning != null && !reasoning.isBlank() ? reasoning : null,
            true);
    }

    private String extractJsonStringFieldLenient(String source, String fieldName) {
        if (source == null || source.isBlank() || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        String marker = "\"" + fieldName + "\"";
        int fieldIndex = source.indexOf(marker);
        if (fieldIndex < 0) {
            return null;
        }
        int colonIndex = source.indexOf(':', fieldIndex + marker.length());
        if (colonIndex < 0) {
            return null;
        }
        int valueStart = colonIndex + 1;
        while (valueStart < source.length() && Character.isWhitespace(source.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= source.length() || source.charAt(valueStart) != '"') {
            return null;
        }
        return decodeJsonStringLenient(source, valueStart + 1);
    }

    private String decodeJsonStringLenient(String source, int startIndex) {
        StringBuilder builder = new StringBuilder();
        boolean escaping = false;
        for (int i = startIndex; i < source.length(); i++) {
            char c = source.charAt(i);
            if (escaping) {
                switch (c) {
                    case '"', '\\', '/' -> builder.append(c);
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if (i + 4 < source.length()) {
                            String hex = source.substring(i + 1, i + 5);
                            try {
                                builder.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException ex) {
                                builder.append("\\u").append(hex);
                                i += 4;
                            }
                        } else {
                            builder.append("\\u");
                        }
                    }
                    default -> builder.append(c);
                }
                escaping = false;
                continue;
            }
            if (c == '\\') {
                escaping = true;
                continue;
            }
            if (c == '"') {
                return builder.toString();
            }
            builder.append(c);
        }
        if (escaping) {
            builder.append('\\');
        }
        return builder.toString();
    }

    private long extractLong(java.util.regex.Pattern pattern, String value) {
        java.util.regex.Matcher matcher = pattern.matcher(value != null ? value : "");
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private AiTokenUsage parseUsage(JsonObject root) {
        if (root == null) {
            return null;
        }
        JsonObject usage = root.getAsJsonObject("usage");
        if (usage == null) {
            return null;
        }
        long promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").getAsLong() : 0L;
        long completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").getAsLong() : 0L;
        long totalTokens = usage.has("total_tokens") ? usage.get("total_tokens").getAsLong() : promptTokens + completionTokens;
        if (promptTokens <= 0L && completionTokens <= 0L && totalTokens <= 0L) {
            return null;
        }
        // OpenAI and MiniMax report the prefix-cached share of the prompt here; it decides whether
        // a cache-friendly request layout is worth anything on an endpoint, so it is surfaced.
        long cachedTokens = 0L;
        if (usage.has("prompt_tokens_details") && usage.get("prompt_tokens_details").isJsonObject()) {
            JsonElement cached = usage.getAsJsonObject("prompt_tokens_details").get("cached_tokens");
            // Proxies serialise absent detail counters as null; a usage line never fails an answer.
            if (cached != null && cached.isJsonPrimitive() && cached.getAsJsonPrimitive().isNumber()) {
                cachedTokens = cached.getAsLong();
            }
        }
        return new AiTokenUsage(promptTokens, completionTokens, totalTokens, cachedTokens);
    }

    private String extractErrorMessage(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject error = root.getAsJsonObject("error");
            if (error != null && error.has("message")) {
                return error.get("message").getAsString();
            }
        } catch (Exception ignored) {
        }
        return body != null && !body.isBlank() ? body : "Unknown error";
    }
}
