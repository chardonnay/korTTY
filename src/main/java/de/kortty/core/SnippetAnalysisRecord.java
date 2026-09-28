package de.kortty.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * One stored Full-code analysis of a snippet, plus everything that happened to it: the findings,
 * the diagram, the user's selection, every apply run and every export.
 *
 * <p>The persisted types mirror the AI-response records ({@link SnippetAiResponseSupport.ScriptAnalysis},
 * {@link SnippetAiWorkflowSupport.ImprovementApplyCheckpoint}, {@link SnippetAiResponseSupport.SecurityChange})
 * instead of embedding them, so renaming a component in the AI code can never silently change the
 * file format; {@code SnippetAnalysisRecordTest} pins the JSON field names. Every compact
 * constructor turns {@code null} into a default, because Gson calls the canonical constructor with
 * {@code null}/0 for fields an older or newer file does not carry, and maps unknown enum names to
 * {@code null}.
 *
 * <p>Content fields ({@link Source#content()}, {@link ApplyRequestSnapshot#baseContent()},
 * {@link ApplyRun#resultContent()}, {@link StoredCheckpoint#content()}) hold the text in memory. In
 * the file they hold the SHA-256 key of a {@link SnippetAnalysisHistory#blobs() blob}, because
 * source, base, checkpoint and result content repeat across runs; {@link SnippetAnalysisStore}
 * does that translation. A content field over {@link #MAX_CONTENT_CHARS} is not stored at all.
 */
public record SnippetAnalysisRecord(
    String id,
    String snippetId,
    Purpose purpose,
    String previousRecordId,
    long analyzedAt,
    long updatedAt,
    boolean pinned,
    Source source,
    Provenance provenance,
    String summary,
    List<Finding> improvements,
    List<DependencyFinding> dependencies,
    AnalysisDiagram diagram,
    SelectionState selection,
    List<ApplyRun> applyRuns,
    Verification verification,
    List<ExportEntry> exports) {

    /** Each stored content field is capped at 256 KiB (chars); a longer text is dropped, never cut. */
    public static final int MAX_CONTENT_CHARS = 256 * 1024;
    /** Only the newest runs of a record keep their content fields. */
    public static final int MAX_RUNS_WITH_CONTENT = 10;

    public enum Purpose { ANALYSIS, RERUN, VERIFY }

    public enum RunOutcome {
        RUNNING, PENDING_REVIEW, ACCEPTED, REJECTED, FAILED, CANCELLED, INTERRUPTED;

        public boolean isTerminal() {
            return this != RUNNING && this != PENDING_REVIEW;
        }
    }

    /**
     * Derived by {@link SnippetAnalysisHistory#statusOf}, never stored. {@code ACCEPTED_NOT_SAVED}:
     * a run was accepted into the editor, but its result never reached the saved snippet.
     */
    public enum RecordStatus { OPEN, ACCEPTED_NOT_SAVED, PARTIALLY_APPLIED, APPLIED, SUPERSEDED }

    public SnippetAnalysisRecord {
        id = blankToEmpty(id);
        snippetId = blankToEmpty(snippetId);
        purpose = purpose != null ? purpose : Purpose.ANALYSIS;
        previousRecordId = blankToNull(previousRecordId);
        source = source != null ? source : Source.EMPTY;
        provenance = provenance != null ? provenance : Provenance.EMPTY;
        summary = summary != null ? summary : "";
        improvements = copyWithoutNulls(improvements);
        dependencies = copyWithoutNulls(dependencies);
        applyRuns = copyWithoutNulls(applyRuns);
        exports = copyWithoutNulls(exports);
    }

    // ---- Nested types ----

    /**
     * What was analysed. {@code sha256} is {@link SnippetDiagramSupport#contentHash} of the full
     * text (also when {@code content} was too large to keep).
     */
    public record Source(String sha256, String language, String reportLanguageCode, String codeTextLanguageCode,
                         String content, boolean contentTruncated, int lineCount, String snippetName) {
        public static final Source EMPTY = new Source(null, null, null, null, null, false, 0, null);

        public Source {
            sha256 = blankToEmpty(sha256);
            language = blankToEmpty(language);
            reportLanguageCode = blankToEmpty(reportLanguageCode);
            codeTextLanguageCode = blankToEmpty(codeTextLanguageCode);
            snippetName = blankToEmpty(snippetName);
            lineCount = Math.max(0, lineCount);
        }

        /** Builds a source from the full analysed text, applying the content cap. */
        public static Source of(String content, String language, String reportLanguageCode,
                                String codeTextLanguageCode, String snippetName) {
            String text = content != null ? content : "";
            String kept = capContent(text);
            return new Source(SnippetDiagramSupport.contentHash(text), language, reportLanguageCode,
                codeTextLanguageCode, kept, kept == null, SnippetAnalysisRecord.lineCount(text), snippetName);
        }

        public Source withContent(String value) {
            return new Source(sha256, language, reportLanguageCode, codeTextLanguageCode, value,
                contentTruncated, lineCount, snippetName);
        }
    }

    /** Which profile, model and skills actually produced a result (reported by the AI assist). */
    public record Provenance(String profileId, String profileName, String model, List<String> skillIds,
                             List<String> skillNames, String additionalInstructions, Usage usage) {
        public static final Provenance EMPTY = new Provenance(null, null, null, null, null, null, null);

        public Provenance {
            profileId = blankToEmpty(profileId);
            profileName = blankToEmpty(profileName);
            model = blankToEmpty(model);
            skillIds = copyStrings(skillIds);
            skillNames = copyStrings(skillNames);
            additionalInstructions = additionalInstructions != null ? additionalInstructions : "";
            usage = usage != null ? usage : Usage.ZERO;
        }

        public Provenance withUsage(Usage value) {
            return new Provenance(profileId, profileName, model, skillIds, skillNames, additionalInstructions, value);
        }

        public Provenance withAdditionalInstructions(String value) {
            return new Provenance(profileId, profileName, model, skillIds, skillNames, value, usage);
        }
    }

    /** Mirrors {@link AiTokenUsage}. */
    public record Usage(long promptTokens, long completionTokens, long totalTokens, long cachedPromptTokens) {
        public static final Usage ZERO = new Usage(0, 0, 0, 0);

        public Usage {
            promptTokens = Math.max(0L, promptTokens);
            completionTokens = Math.max(0L, completionTokens);
            totalTokens = Math.max(totalTokens, promptTokens + completionTokens);
            cachedPromptTokens = Math.max(0L, Math.min(cachedPromptTokens, promptTokens));
        }

        public static Usage from(AiTokenUsage usage) {
            return usage == null ? ZERO : new Usage(usage.promptTokens(), usage.completionTokens(),
                usage.totalTokens(), usage.cachedPromptTokens());
        }

        public AiTokenUsage toAiTokenUsage() {
            return new AiTokenUsage(promptTokens, completionTokens, totalTokens, cachedPromptTokens);
        }

        public Usage plus(Usage other) {
            if (other == null) {
                return this;
            }
            return new Usage(promptTokens + other.promptTokens, completionTokens + other.completionTokens,
                totalTokens + other.totalTokens, cachedPromptTokens + other.cachedPromptTokens);
        }
    }

    /** Mirrors {@link SnippetAiResponseSupport.ScriptImprovement}. */
    public record Finding(String id, String category, String severity, String title, String detail,
                          String recommendation, Integer line) {
        public Finding {
            id = blankToEmpty(id);
            category = blankToEmpty(category);
            severity = blankToEmpty(severity);
            title = title != null ? title : "";
            detail = detail != null ? detail : "";
            recommendation = recommendation != null ? recommendation : "";
        }

        public static Finding from(SnippetAiResponseSupport.ScriptImprovement improvement) {
            return new Finding(improvement.id(), improvement.category(), improvement.severity(),
                improvement.title(), improvement.detail(), improvement.recommendation(), improvement.line());
        }

        public SnippetAiResponseSupport.ScriptImprovement toImprovement() {
            return new SnippetAiResponseSupport.ScriptImprovement(id, category, severity, title, detail,
                recommendation, line);
        }

        Finding withId(String value) {
            return new Finding(value, category, severity, title, detail, recommendation, line);
        }
    }

    /** Mirrors {@link SnippetAiResponseSupport.ScriptDependency}. */
    public record DependencyFinding(String id, String name, String kind, String purpose, String suggestion) {
        public DependencyFinding {
            id = blankToEmpty(id);
            name = name != null ? name : "";
            kind = kind != null ? kind : "";
            purpose = purpose != null ? purpose : "";
            suggestion = suggestion != null ? suggestion : "";
        }

        public static DependencyFinding from(SnippetAiResponseSupport.ScriptDependency dependency) {
            return new DependencyFinding(dependency.id(), dependency.name(), dependency.kind(),
                dependency.purpose(), dependency.suggestion());
        }

        public SnippetAiResponseSupport.ScriptDependency toDependency() {
            return new SnippetAiResponseSupport.ScriptDependency(id, name, kind, purpose, suggestion);
        }

        DependencyFinding withId(String value) {
            return new DependencyFinding(value, name, kind, purpose, suggestion);
        }
    }

    /**
     * The analysis diagram. {@code typeId} is a {@code SnippetDiagramType} id; {@code sourceSha256}
     * is the hash of the content the diagram was generated from.
     */
    public record AnalysisDiagram(String typeId, String mermaid, List<CodeRef> codeReferences, String notice,
                                  boolean fallback, String sourceSha256, String profileId, long generatedAt) {
        public AnalysisDiagram {
            typeId = blankToEmpty(typeId);
            mermaid = mermaid != null ? mermaid : "";
            codeReferences = copyWithoutNulls(codeReferences);
            notice = notice != null ? notice : "";
            sourceSha256 = blankToEmpty(sourceSha256);
            profileId = blankToEmpty(profileId);
        }

        /** The diagram family, or {@code null} for an id this version does not know. */
        public de.kortty.model.SnippetDiagramType diagramType() {
            return de.kortty.model.SnippetDiagramType.fromId(typeId);
        }

        /** Same rule as {@link SnippetDiagramSupport#isStale}: a blank hash is never stale. */
        public boolean isStale(String currentContent) {
            return !sourceSha256.isBlank()
                && !sourceSha256.equals(SnippetDiagramSupport.contentHash(currentContent));
        }
    }

    public record CodeRef(String nodeId, String label, int startLine, int endLine) {
        public CodeRef {
            nodeId = blankToEmpty(nodeId);
            label = label != null ? label : "";
        }
    }

    /** The user's choices in the analysis panel ({@code null} on the record = selector defaults). */
    public record SelectionState(List<String> improvementIds, List<String> dependencyIds, List<String> hardening,
                                 boolean inputHardeningEnabled, List<String> inputHardeningOptions,
                                 long inputHardeningMaxFileSizeBytes, String headerSnippetId,
                                 String migrationTargetLanguage, String migrationTargetHostFormat,
                                 String codeTextLanguageCode, long updatedAt) {
        public SelectionState {
            improvementIds = copyStrings(improvementIds);
            dependencyIds = copyStrings(dependencyIds);
            hardening = copyStrings(hardening);
            inputHardeningOptions = copyStrings(inputHardeningOptions);
            inputHardeningMaxFileSizeBytes = Math.max(0L, inputHardeningMaxFileSizeBytes);
            headerSnippetId = blankToNull(headerSnippetId);
            migrationTargetLanguage = blankToNull(migrationTargetLanguage);
            migrationTargetHostFormat = blankToNull(migrationTargetHostFormat);
            codeTextLanguageCode = blankToNull(codeTextLanguageCode);
        }
    }

    /**
     * One "Apply" of (part of) the analysis.
     *
     * @param startedAt              when the run started (the report's "applied at")
     * @param decidedAt              when the user accepted or rejected the result, 0 before
     * @param partial                a partial result (the completed stages of an unfinished run) was used
     * @param resultSha256           hash of {@code resultContent} (the replacement after header injection)
     * @param acceptedContentSha256  hash of the editor content right after Accept; compared with the
     *                               saved snippet to tell "applied" from "accepted, not saved"
     * @param completedWorkItemIds   ids of finished work items plus the completed requirement ids
     * @param appliedFindingIds      finding ids this run counts as applied once accepted
     * @param savedToSnippetAt       when an editor save persisted {@code acceptedContentSha256}, 0 before
     */
    public record ApplyRun(String id, long startedAt, long finishedAt, long decidedAt, RunOutcome outcome,
                           boolean partial, ApplyRequestSnapshot request, List<WorkItemState> items,
                           StoredCheckpoint checkpoint, String resultSha256, String resultContent,
                           String summary, List<Change> changes, List<String> implementedRequirements,
                           List<String> completedWorkItemIds, List<String> appliedFindingIds, RunStats stats,
                           Provenance provenance, String failureKey, String acceptedContentSha256,
                           long savedToSnippetAt) {
        public ApplyRun {
            id = blankToEmpty(id);
            outcome = outcome != null ? outcome : RunOutcome.INTERRUPTED;
            request = request != null ? request : ApplyRequestSnapshot.EMPTY;
            items = copyWithoutNulls(items);
            resultSha256 = blankToEmpty(resultSha256);
            summary = summary != null ? summary : "";
            changes = copyWithoutNulls(changes);
            implementedRequirements = copyStrings(implementedRequirements);
            completedWorkItemIds = copyStrings(completedWorkItemIds);
            appliedFindingIds = copyStrings(appliedFindingIds);
            stats = stats != null ? stats : RunStats.EMPTY;
            provenance = provenance != null ? provenance : Provenance.EMPTY;
            failureKey = blankToNull(failureKey);
            acceptedContentSha256 = blankToEmpty(acceptedContentSha256);
        }

        /** A new {@link RunOutcome#RUNNING} run for the given request. */
        public static ApplyRun started(String id, long startedAt, ApplyRequestSnapshot request,
                                       List<WorkItemState> items, Provenance provenance) {
            return new ApplyRun(id, startedAt, 0L, 0L, RunOutcome.RUNNING, false, request, items, null,
                null, null, null, null, null, null, null, null, provenance, null, null, 0L);
        }

        /** Hash of the content the run started from (the export's "before"). */
        public String beforeSha256() {
            return request.baseSha256();
        }

        /** Hash of the proposed replacement (the export's "after"). */
        public String afterSha256() {
            return resultSha256;
        }

        public boolean isAccepted() {
            return outcome == RunOutcome.ACCEPTED;
        }

        /**
         * A run the panel can resume: stopped between stages with its base content stored. The
         * caller must additionally check {@code contentHash(current) == request.baseSha256()}.
         */
        public boolean isResumable() {
            if (outcome != RunOutcome.INTERRUPTED && outcome != RunOutcome.FAILED
                    && outcome != RunOutcome.CANCELLED) {
                return false;
            }
            return checkpoint != null
                && checkpoint.completedStages() >= 1
                && checkpoint.completedStages() < checkpoint.totalStages()
                && request.baseContent() != null;
        }

        public ApplyRun withOutcome(RunOutcome value, long at) {
            boolean decision = value == RunOutcome.ACCEPTED || value == RunOutcome.REJECTED;
            return new ApplyRun(id, startedAt, decision ? finishedAt : Math.max(finishedAt, at),
                decision ? at : decidedAt, value, partial, request, items, checkpoint, resultSha256,
                resultContent, summary, changes, implementedRequirements, completedWorkItemIds,
                appliedFindingIds, stats, provenance, failureKey, acceptedContentSha256, savedToSnippetAt);
        }

        public ApplyRun withCheckpoint(StoredCheckpoint value) {
            return new ApplyRun(id, startedAt, finishedAt, decidedAt, outcome, partial, request, items, value,
                resultSha256, resultContent, summary, changes, implementedRequirements, completedWorkItemIds,
                appliedFindingIds, stats, provenance, failureKey, acceptedContentSha256, savedToSnippetAt);
        }

        public ApplyRun withItems(List<WorkItemState> value) {
            return new ApplyRun(id, startedAt, finishedAt, decidedAt, outcome, partial, request, value, checkpoint,
                resultSha256, resultContent, summary, changes, implementedRequirements, completedWorkItemIds,
                appliedFindingIds, stats, provenance, failureKey, acceptedContentSha256, savedToSnippetAt);
        }

        /** Records a finished rewrite that waits for the user's decision. */
        public ApplyRun withResult(long at, boolean isPartial, String content, String resultSummary,
                                   List<Change> resultChanges, List<String> requirements,
                                   List<String> completedIds, RunStats runStats, Provenance resolved) {
            String text = content != null ? content : "";
            return new ApplyRun(id, startedAt, at, decidedAt, RunOutcome.PENDING_REVIEW, isPartial, request, items,
                checkpoint, SnippetDiagramSupport.contentHash(text), capContent(text), resultSummary,
                resultChanges, requirements, completedIds, appliedFindingIds, runStats,
                resolved != null ? resolved : provenance, failureKey, acceptedContentSha256, savedToSnippetAt);
        }

        public ApplyRun withFailure(RunOutcome value, long at, String key, RunStats runStats) {
            return new ApplyRun(id, startedAt, Math.max(finishedAt, at), decidedAt, value, partial, request, items,
                checkpoint, resultSha256, resultContent, summary, changes, implementedRequirements,
                completedWorkItemIds, appliedFindingIds, runStats != null ? runStats : stats, provenance, key,
                acceptedContentSha256, savedToSnippetAt);
        }

        /** Accept: the editor now holds {@code editorContent}; {@code findingIds} count as applied. */
        public ApplyRun accepted(long at, List<String> findingIds, String editorContent) {
            return new ApplyRun(id, startedAt, finishedAt, at, RunOutcome.ACCEPTED, partial, request, items,
                checkpoint, resultSha256, resultContent, summary, changes, implementedRequirements,
                completedWorkItemIds, findingIds, stats, provenance, failureKey,
                SnippetDiagramSupport.contentHash(editorContent), savedToSnippetAt);
        }

        public ApplyRun withSavedToSnippetAt(long value) {
            return new ApplyRun(id, startedAt, finishedAt, decidedAt, outcome, partial, request, items, checkpoint,
                resultSha256, resultContent, summary, changes, implementedRequirements, completedWorkItemIds,
                appliedFindingIds, stats, provenance, failureKey, acceptedContentSha256, value);
        }

        ApplyRun mapContent(UnaryOperator<String> mapper) {
            ApplyRequestSnapshot mappedRequest = request.withBaseContent(mapper.apply(request.baseContent()));
            StoredCheckpoint mappedCheckpoint = checkpoint != null
                ? checkpoint.withContent(mapper.apply(checkpoint.content()))
                : null;
            return new ApplyRun(id, startedAt, finishedAt, decidedAt, outcome, partial, mappedRequest, items,
                mappedCheckpoint, resultSha256, mapper.apply(resultContent), summary, changes,
                implementedRequirements, completedWorkItemIds, appliedFindingIds, stats, provenance, failureKey,
                acceptedContentSha256, savedToSnippetAt);
        }

        /** Drops content no later step needs: decided runs lose their checkpoint text. */
        ApplyRun compact(boolean keepContent) {
            if (!keepContent) {
                return mapContent(value -> null);
            }
            if ((outcome == RunOutcome.ACCEPTED || outcome == RunOutcome.REJECTED)
                    && checkpoint != null && checkpoint.content() != null) {
                return withCheckpoint(checkpoint.withContent(null));
            }
            return this;
        }
    }

    /**
     * Exactly what an apply run sent, so a resume can rebuild the request byte for byte and the
     * report can name the options. {@code baseContent} is only stored when needed (resume, or when
     * it differs from the analysed source).
     */
    public record ApplyRequestSnapshot(String snippetLanguage, String codeTextLanguageCode,
                                       List<String> improvementIds, List<String> dependencyIds,
                                       String additionalInstructions, List<String> hardeningOptions,
                                       String classicHardeningInstructions, List<String> inputHardeningOptions,
                                       String inputHardeningInstructions, String migrationTargetLanguage,
                                       String migrationTargetHostFormat, String migrationLabel,
                                       String headerSnippetId, String headerName, String headerText,
                                       String aiProfileId, String baseSha256, String baseContent) {
        public static final ApplyRequestSnapshot EMPTY = new ApplyRequestSnapshot(null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null);

        public ApplyRequestSnapshot {
            snippetLanguage = blankToEmpty(snippetLanguage);
            codeTextLanguageCode = blankToNull(codeTextLanguageCode);
            improvementIds = copyStrings(improvementIds);
            dependencyIds = copyStrings(dependencyIds);
            additionalInstructions = additionalInstructions != null ? additionalInstructions : "";
            hardeningOptions = copyStrings(hardeningOptions);
            classicHardeningInstructions = classicHardeningInstructions != null ? classicHardeningInstructions : "";
            inputHardeningOptions = copyStrings(inputHardeningOptions);
            inputHardeningInstructions = inputHardeningInstructions != null ? inputHardeningInstructions : "";
            migrationTargetLanguage = blankToNull(migrationTargetLanguage);
            migrationTargetHostFormat = blankToNull(migrationTargetHostFormat);
            migrationLabel = blankToNull(migrationLabel);
            headerSnippetId = blankToNull(headerSnippetId);
            headerName = blankToNull(headerName);
            headerText = headerText != null ? headerText : "";
            aiProfileId = blankToNull(aiProfileId);
            baseSha256 = blankToEmpty(baseSha256);
        }

        public ApplyRequestSnapshot withBaseContent(String value) {
            return new ApplyRequestSnapshot(snippetLanguage, codeTextLanguageCode, improvementIds, dependencyIds,
                additionalInstructions, hardeningOptions, classicHardeningInstructions, inputHardeningOptions,
                inputHardeningInstructions, migrationTargetLanguage, migrationTargetHostFormat, migrationLabel,
                headerSnippetId, headerName, headerText, aiProfileId, baseSha256, value);
        }
    }

    /**
     * @param phase {@link SnippetAiWorkflowSupport.ImprovementApplyPhase} name
     * @param state UI state id of the item (e.g. pending, running, done, failed)
     */
    public record WorkItemState(int stage, String phase, String id, String label, String category, String severity,
                                String state) {
        public WorkItemState {
            phase = blankToEmpty(phase);
            id = blankToEmpty(id);
            label = label != null ? label : "";
            category = blankToEmpty(category);
            severity = blankToEmpty(severity);
            state = blankToEmpty(state);
        }
    }

    /** Mirrors {@link SnippetAiWorkflowSupport.ImprovementApplyCheckpoint}. */
    public record StoredCheckpoint(int completedStages, int totalStages, String content, List<String> summaries,
                                   List<Change> changes, List<String> completedRequirementIds, Usage usage) {
        public StoredCheckpoint {
            completedStages = Math.max(0, completedStages);
            totalStages = Math.max(0, totalStages);
            summaries = copyStrings(summaries);
            changes = copyWithoutNulls(changes);
            completedRequirementIds = copyStrings(completedRequirementIds);
            usage = usage != null ? usage : Usage.ZERO;
        }

        public static StoredCheckpoint from(SnippetAiWorkflowSupport.ImprovementApplyCheckpoint checkpoint) {
            if (checkpoint == null) {
                return null;
            }
            return new StoredCheckpoint(checkpoint.completedStages(), checkpoint.totalStages(),
                capContent(checkpoint.content()), checkpoint.summaries(),
                checkpoint.changes().stream().map(Change::from).toList(),
                checkpoint.completedRequirementIds(), Usage.from(checkpoint.cumulativeUsage()));
        }

        /** The workflow checkpoint to resume from, or {@code null} when the content was not stored. */
        public SnippetAiWorkflowSupport.ImprovementApplyCheckpoint toCheckpoint() {
            if (content == null) {
                return null;
            }
            return new SnippetAiWorkflowSupport.ImprovementApplyCheckpoint(completedStages, totalStages, content,
                summaries, changes.stream().map(Change::toSecurityChange).toList(), completedRequirementIds,
                usage.toAiTokenUsage());
        }

        public StoredCheckpoint withContent(String value) {
            return new StoredCheckpoint(completedStages, totalStages, value, summaries, changes,
                completedRequirementIds, usage);
        }
    }

    /** Mirrors {@link SnippetAiResponseSupport.SecurityChange}; {@code finding} holds the finding id. */
    public record Change(String finding, String anchor, String reason) {
        public Change {
            finding = finding != null ? finding : "";
            anchor = anchor != null ? anchor : "";
            reason = reason != null ? reason : "";
        }

        public static Change from(SnippetAiResponseSupport.SecurityChange change) {
            return new Change(change.finding(), change.anchor(), change.reason());
        }

        public SnippetAiResponseSupport.SecurityChange toSecurityChange() {
            return new SnippetAiResponseSupport.SecurityChange(finding, anchor, reason);
        }
    }

    public record RunStats(long elapsedSeconds, Usage usage, int retries, int completedItems, int totalItems,
                           String statusKey) {
        public static final RunStats EMPTY = new RunStats(0, null, 0, 0, 0, null);

        public RunStats {
            elapsedSeconds = Math.max(0L, elapsedSeconds);
            usage = usage != null ? usage : Usage.ZERO;
            retries = Math.max(0, retries);
            completedItems = Math.max(0, completedItems);
            totalItems = Math.max(0, totalItems);
            statusKey = blankToNull(statusKey);
        }
    }

    /**
     * Result of {@link SnippetAnalysisComparison#compare}: which findings of {@code previousRecordId}
     * are gone, which persist (current id → previous id) and which are new.
     */
    public record Verification(String previousRecordId, List<String> resolvedPreviousIds,
                               Map<String, String> persistingCurrentToPrevious, List<String> newIds) {
        public Verification {
            previousRecordId = blankToEmpty(previousRecordId);
            resolvedPreviousIds = copyStrings(resolvedPreviousIds);
            persistingCurrentToPrevious = persistingCurrentToPrevious == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(persistingCurrentToPrevious));
            newIds = copyStrings(newIds);
        }
    }

    /** One successful export; {@code fileName} carries no directory. */
    public record ExportEntry(long exportedAt, String format, String phase, String runId, String fileName) {
        public static final String PHASE_BEFORE_APPLY = "BEFORE_APPLY";
        public static final String PHASE_AFTER_APPLY = "AFTER_APPLY";

        public ExportEntry {
            format = blankToEmpty(format);
            phase = blankToEmpty(phase);
            runId = blankToNull(runId);
            String name = fileName != null ? fileName.strip() : "";
            fileName = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        }
    }

    // ---- Conversions ----

    /**
     * Builds a record from a fresh AI result. Finding ids are supplied by the model and are not
     * unique; duplicates are made unique deterministically before anything keys on them (see
     * {@link #uniqueFindingIds}).
     */
    public static SnippetAnalysisRecord fromAnalysis(String id, String snippetId,
                                                     SnippetAiResponseSupport.ScriptAnalysis analysis,
                                                     Source source, Provenance provenance, Purpose purpose,
                                                     String previousRecordId, long analyzedAt) {
        Objects.requireNonNull(analysis, "analysis");
        List<Finding> improvements = analysis.improvements().stream().map(Finding::from).toList();
        List<DependencyFinding> dependencies = analysis.dependencies().stream().map(DependencyFinding::from).toList();
        UniqueIds unique = uniqueFindingIds(improvements, dependencies);
        return new SnippetAnalysisRecord(id, snippetId, purpose, previousRecordId, analyzedAt, analyzedAt, false,
            source, provenance, analysis.summary(), unique.improvements(), unique.dependencies(), null, null,
            List.of(), null, List.of());
    }

    public SnippetAiResponseSupport.ScriptAnalysis toScriptAnalysis() {
        return new SnippetAiResponseSupport.ScriptAnalysis(summary,
            dependencies.stream().map(DependencyFinding::toDependency).toList(),
            improvements.stream().map(Finding::toImprovement).toList());
    }

    public record UniqueIds(List<Finding> improvements, List<DependencyFinding> dependencies) {
    }

    /**
     * Makes finding ids unique across improvements and dependencies (one namespace, improvements
     * first): the first occurrence keeps its id, the second {@code SEC-1} becomes {@code SEC-1.2},
     * the third {@code SEC-1.3}, skipping any id that is already taken. Deterministic for the same
     * input order.
     */
    public static UniqueIds uniqueFindingIds(List<Finding> improvements, List<DependencyFinding> dependencies) {
        List<String> original = new ArrayList<>();
        improvements.forEach(f -> original.add(f.id().isEmpty() ? "I" : f.id()));
        dependencies.forEach(d -> original.add(d.id().isEmpty() ? "D" : d.id()));
        Set<String> reserved = new HashSet<>(original);
        Set<String> used = new HashSet<>();
        List<String> assigned = new ArrayList<>(original.size());
        for (String candidate : original) {
            if (used.add(candidate)) {
                assigned.add(candidate);
                continue;
            }
            int suffix = 2;
            String next = candidate + "." + suffix;
            while (used.contains(next) || reserved.contains(next)) {
                next = candidate + "." + (++suffix);
            }
            used.add(next);
            assigned.add(next);
        }
        List<Finding> uniqueImprovements = new ArrayList<>(improvements.size());
        for (int i = 0; i < improvements.size(); i++) {
            uniqueImprovements.add(improvements.get(i).withId(assigned.get(i)));
        }
        List<DependencyFinding> uniqueDependencies = new ArrayList<>(dependencies.size());
        for (int i = 0; i < dependencies.size(); i++) {
            uniqueDependencies.add(dependencies.get(i).withId(assigned.get(improvements.size() + i)));
        }
        return new UniqueIds(List.copyOf(uniqueImprovements), List.copyOf(uniqueDependencies));
    }

    // ---- Derived views ----

    /** The report's "created at". */
    public long createdAt() {
        return analyzedAt;
    }

    /** The report's "sourceContentSha256". */
    public String sourceContentSha256() {
        return source.sha256();
    }

    /** All finding ids (improvements then dependencies). */
    public Set<String> allFindingIds() {
        Set<String> ids = new LinkedHashSet<>();
        improvements.forEach(f -> ids.add(f.id()));
        dependencies.forEach(d -> ids.add(d.id()));
        return ids;
    }

    public ApplyRun findRun(String runId) {
        for (ApplyRun run : applyRuns) {
            if (run.id().equals(runId)) {
                return run;
            }
        }
        return null;
    }

    public boolean hasRunningRun() {
        return applyRuns.stream().anyMatch(run -> run.outcome() == RunOutcome.RUNNING);
    }

    public boolean hasPendingReview() {
        return applyRuns.stream().anyMatch(run -> run.outcome() == RunOutcome.PENDING_REVIEW);
    }

    public boolean hasResumableRun() {
        return applyRuns.stream().anyMatch(ApplyRun::isResumable);
    }

    /** Retention never trims a pinned record, or one with running, pending or resumable work. */
    public boolean isProtectedFromRetention() {
        return pinned || hasRunningRun() || hasPendingReview() || hasResumableRun();
    }

    // ---- Withers ----

    public SnippetAnalysisRecord withSnippetId(String value) {
        return new SnippetAnalysisRecord(id, value, purpose, previousRecordId, analyzedAt, updatedAt, pinned,
            source, provenance, summary, improvements, dependencies, diagram, selection, applyRuns,
            verification, exports);
    }

    public SnippetAnalysisRecord withPinned(boolean value) {
        return new SnippetAnalysisRecord(id, snippetId, purpose, previousRecordId, analyzedAt, updatedAt, value,
            source, provenance, summary, improvements, dependencies, diagram, selection, applyRuns,
            verification, exports);
    }

    public SnippetAnalysisRecord withUpdatedAt(long value) {
        return new SnippetAnalysisRecord(id, snippetId, purpose, previousRecordId, analyzedAt, value, pinned,
            source, provenance, summary, improvements, dependencies, diagram, selection, applyRuns,
            verification, exports);
    }

    public SnippetAnalysisRecord withSource(Source value) {
        return new SnippetAnalysisRecord(id, snippetId, purpose, previousRecordId, analyzedAt, updatedAt, pinned,
            value, provenance, summary, improvements, dependencies, diagram, selection, applyRuns,
            verification, exports);
    }

    public SnippetAnalysisRecord withProvenance(Provenance value) {
        return new SnippetAnalysisRecord(id, snippetId, purpose, previousRecordId, analyzedAt, updatedAt, pinned,
            source, value, summary, improvements, dependencies, diagram, selection, applyRuns,
            verification, exports);
    }

    public SnippetAnalysisRecord withDiagram(AnalysisDiagram value) {
        return new SnippetAnalysisRecord(id, snippetId, purpose, previousRecordId, analyzedAt, updatedAt, pinned,
            source, provenance, summary, improvements, dependencies, value, selection, applyRuns,
            verification, exports);
    }

    public SnippetAnalysisRecord withSelection(SelectionState value) {
        return new SnippetAnalysisRecord(id, snippetId, purpose, previousRecordId, analyzedAt, updatedAt, pinned,
            source, provenance, summary, improvements, dependencies, diagram, value, applyRuns,
            verification, exports);
    }

    public SnippetAnalysisRecord withVerification(Verification value) {
        return new SnippetAnalysisRecord(id, snippetId, purpose, previousRecordId, analyzedAt, updatedAt, pinned,
            source, provenance, summary, improvements, dependencies, diagram, selection, applyRuns,
            value, exports);
    }

    public SnippetAnalysisRecord withApplyRuns(List<ApplyRun> value) {
        return new SnippetAnalysisRecord(id, snippetId, purpose, previousRecordId, analyzedAt, updatedAt, pinned,
            source, provenance, summary, improvements, dependencies, diagram, selection, value,
            verification, exports);
    }

    /** Replaces the run with the same id, or appends it (runs are oldest first). */
    public SnippetAnalysisRecord withRun(ApplyRun run) {
        List<ApplyRun> runs = new ArrayList<>(applyRuns);
        boolean replaced = false;
        for (int i = 0; i < runs.size(); i++) {
            if (runs.get(i).id().equals(run.id())) {
                runs.set(i, run);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            runs.add(run);
        }
        return withApplyRuns(runs);
    }

    public SnippetAnalysisRecord withExport(ExportEntry entry) {
        List<ExportEntry> entries = new ArrayList<>(exports);
        entries.add(entry);
        return new SnippetAnalysisRecord(id, snippetId, purpose, previousRecordId, analyzedAt, updatedAt, pinned,
            source, provenance, summary, improvements, dependencies, diagram, selection, applyRuns,
            verification, entries);
    }

    /** RUNNING runs cannot survive a restart: they become INTERRUPTED (their checkpoint is kept). */
    SnippetAnalysisRecord interruptRunning() {
        if (!hasRunningRun()) {
            return this;
        }
        return withApplyRuns(applyRuns.stream()
            .map(run -> run.outcome() == RunOutcome.RUNNING ? run.withOutcome(RunOutcome.INTERRUPTED, 0L) : run)
            .toList());
    }

    /**
     * Applies the size caps: every content field at most {@link #MAX_CONTENT_CHARS}, decided runs
     * without checkpoint text, and only the {@link #MAX_RUNS_WITH_CONTENT} newest runs with content.
     * Runs still waiting for review or resumable always keep their content.
     */
    SnippetAnalysisRecord compact() {
        Source cappedSource = source;
        if (source.content() != null && source.content().length() > MAX_CONTENT_CHARS) {
            cappedSource = new Source(source.sha256(), source.language(), source.reportLanguageCode(),
                source.codeTextLanguageCode(), null, true, source.lineCount(), source.snippetName());
        }
        List<ApplyRun> runs = new ArrayList<>(applyRuns.size());
        int firstWithContent = Math.max(0, applyRuns.size() - MAX_RUNS_WITH_CONTENT);
        for (int i = 0; i < applyRuns.size(); i++) {
            ApplyRun run = applyRuns.get(i).mapContent(SnippetAnalysisRecord::capContent);
            boolean keep = i >= firstWithContent || run.outcome() == RunOutcome.PENDING_REVIEW
                || run.outcome() == RunOutcome.RUNNING || run.isResumable();
            runs.add(run.compact(keep));
        }
        return withSource(cappedSource).withApplyRuns(runs);
    }

    SnippetAnalysisRecord mapContent(UnaryOperator<String> mapper) {
        return withSource(source.withContent(mapper.apply(source.content())))
            .withApplyRuns(applyRuns.stream().map(run -> run.mapContent(mapper)).toList());
    }

    // ---- Helpers ----

    /** The text itself, or {@code null} when it exceeds {@link #MAX_CONTENT_CHARS}. */
    public static String capContent(String content) {
        if (content == null || content.length() > MAX_CONTENT_CHARS) {
            return null;
        }
        return content;
    }

    static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return text.endsWith("\n") ? lines - 1 : lines;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> copyStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).toList();
    }

    private static <T> List<T> copyWithoutNulls(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).toList();
    }
}
