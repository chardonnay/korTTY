package de.kortty.core;

import de.kortty.model.SnippetDiagramType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Everything one exported code-analysis report shows, as immutable data. The writers (PDF, HTML,
 * Markdown, JSON) render nothing but this model, so the report is the same in every format and
 * independent of any window: it is built from the stored {@link SnippetAnalysisRecord} by
 * {@link SnippetAnalysisReports} and copied at click time, so switching or editing a snippet while
 * an export runs cannot change it.
 *
 * <p>Two kinds exist. {@link Kind#PRE_APPLY} describes the analysis and the current selection;
 * {@link Kind#POST_APPLY} adds one apply run: its outcome, the status of every finding, the diff
 * and, when a follow-up analysis of the applied code exists, its verification.</p>
 *
 * <p>Render-time choices (locale, time zone, export time, branding) are not part of the model; they
 * are {@link SnippetAnalysisExportService.ExportOptions}, which keeps the model deterministic.</p>
 *
 * @param diagram       {@code null} when no diagram is stored
 * @param apply         {@code null} for {@link Kind#PRE_APPLY}
 * @param verification  {@code null} unless a follow-up analysis verified the applied run
 * @param analysedCode  {@code null} when the analysed text was not stored (no excerpts then)
 * @param resultCode    the run's replacement; {@code null} for PRE or when it was not stored
 */
public record SnippetAnalysisReport(
    Kind kind,
    Header header,
    String summary,
    List<Finding> findings,
    List<Dependency> dependencies,
    Diagram diagram,
    ApplyResult apply,
    Verification verification,
    CodeSnapshot analysedCode,
    CodeSnapshot resultCode) {

    public SnippetAnalysisReport {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(header, "header");
        summary = summary != null ? summary : "";
        findings = findings != null ? List.copyOf(findings) : List.of();
        dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
    }

    public enum Kind { PRE_APPLY, POST_APPLY }

    /**
     * What the report is about and when things happened. The three times are kept apart on purpose:
     * {@code analysedAt} (the AI result), {@code appliedAt} (the run; {@code null} before applying)
     * and the export time, which is a render option.
     *
     * @param runNumber 1-based number of the reported run among {@code runCount}; 0 for PRE
     */
    public record Header(
        String scriptName,
        String snippetId,
        String scriptLanguage,
        String aiProfileName,
        String aiModel,
        List<String> includedSkills,
        String analysisLanguageCode,
        String codeTextLanguageCode,
        Instant analysedAt,
        Instant appliedAt,
        String sourceSha256,
        boolean stale,
        int runNumber,
        int runCount) {

        public Header {
            scriptName = scriptName != null ? scriptName : "";
            snippetId = snippetId != null ? snippetId : "";
            scriptLanguage = scriptLanguage != null ? scriptLanguage : "";
            aiProfileName = aiProfileName != null ? aiProfileName : "";
            aiModel = aiModel != null ? aiModel : "";
            includedSkills = includedSkills != null ? List.copyOf(includedSkills) : List.of();
            analysisLanguageCode = analysisLanguageCode != null ? analysisLanguageCode : "";
            codeTextLanguageCode = codeTextLanguageCode != null ? codeTextLanguageCode : "";
            sourceSha256 = sourceSha256 != null ? sourceSha256 : "";
            runNumber = Math.max(0, runNumber);
            runCount = Math.max(0, runCount);
        }
    }

    /**
     * One improvement. {@code displayCategory} is the section it is shown in (security,
     * optimization or design — everything else sorts under design, exactly as in the analysis
     * panel); {@code category} is the AI's raw value, shown as a tag when it differs.
     *
     * @param status       {@code null} in a PRE report
     * @param hunkIndexes  indexes into {@link ApplyResult#diff()}'s hunks this finding's changes touch
     * @param excerpt      the analysed code around {@code line}; {@code null} without line or source
     */
    public record Finding(
        String id,
        String displayCategory,
        String category,
        AnalysisSeverity severity,
        String severityLabel,
        String title,
        String detail,
        String recommendation,
        Integer line,
        boolean selected,
        FindingStatus status,
        List<Change> changes,
        List<Integer> hunkIndexes,
        Excerpt excerpt) {

        public Finding {
            id = id != null ? id : "";
            displayCategory = displayCategory != null ? displayCategory : "design";
            category = category != null ? category : "";
            severity = severity != null ? severity : AnalysisSeverity.INFO;
            severityLabel = severityLabel != null ? severityLabel : "";
            title = title != null ? title : "";
            detail = detail != null ? detail : "";
            recommendation = recommendation != null ? recommendation : "";
            line = line != null && line > 0 ? line : null;
            changes = changes != null ? List.copyOf(changes) : List.of();
            hunkIndexes = hunkIndexes != null ? List.copyOf(hunkIndexes) : List.of();
        }
    }

    public record Dependency(
        String id,
        String name,
        String kind,
        String purpose,
        String suggestion,
        boolean selected,
        FindingStatus status,
        List<Change> changes) {

        public Dependency {
            id = id != null ? id : "";
            name = name != null ? name : "";
            kind = kind != null ? kind : "";
            purpose = purpose != null ? purpose : "";
            suggestion = suggestion != null ? suggestion : "";
            changes = changes != null ? List.copyOf(changes) : List.of();
        }
    }

    /** One "why this part changed" entry of a run; {@code findingId} names the finding it serves. */
    public record Change(String findingId, String anchor, String reason) {

        public Change {
            findingId = findingId != null ? findingId : "";
            anchor = anchor != null ? anchor : "";
            reason = reason != null ? reason : "";
        }
    }

    /** The result of a finding in the reported run. */
    public enum FindingStatus {
        APPLIED("snippets.ai.analysis.report.status.applied", "#15803D"),
        REJECTED("snippets.ai.analysis.report.status.rejected", "#B91C1C"),
        NOT_SELECTED("snippets.ai.analysis.report.status.notSelected", "#6B7280"),
        NOT_REACHED("snippets.ai.analysis.report.status.notReached", "#6B7280"),
        FAILED("snippets.ai.analysis.report.status.failed", "#9A3412"),
        UNCONFIRMED("snippets.ai.analysis.report.status.unconfirmed", "#6B7280"),
        /** The run's result waits for the user's review: proposed, not (yet) applied. */
        PROPOSED("snippets.ai.analysis.report.status.proposed", "#1D4ED8");

        private final String i18nKey;
        private final String colorHex;

        FindingStatus(String i18nKey, String colorHex) {
            this.i18nKey = i18nKey;
            this.colorHex = colorHex;
        }

        public String i18nKey() {
            return i18nKey;
        }

        /** Badge colour on a white page. */
        public String colorHex() {
            return colorHex;
        }

        /** The stable id used in JSON and CSS, e.g. {@code not-selected}. */
        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * The stored diagram. {@code type} is {@code null} for a family this version does not know,
     * which renders as a plain Mermaid diagram.
     */
    public record Diagram(String mermaidSource, SnippetDiagramType type, boolean fallback, String fallbackNotice) {

        public Diagram {
            mermaidSource = mermaidSource != null ? mermaidSource : "";
            fallbackNotice = fallbackNotice != null ? fallbackNotice : "";
        }
    }

    /**
     * How an apply run ended, as the report states it. {@link #PROPOSED} is a finished run whose
     * result still waits for the user's review.
     */
    public enum Outcome {
        ACCEPTED("snippets.ai.analysis.report.apply.outcome.accepted", "#15803D"),
        PARTIAL_ACCEPTED("snippets.ai.analysis.report.apply.outcome.partial", "#B45309"),
        PROPOSED("snippets.ai.analysis.report.apply.outcome.proposed", "#1D4ED8"),
        REJECTED("snippets.ai.analysis.report.apply.outcome.rejected", "#B91C1C"),
        FAILED("snippets.ai.analysis.report.apply.outcome.failed", "#B91C1C"),
        CANCELLED("snippets.ai.analysis.report.apply.outcome.cancelled", "#6B7280"),
        INTERRUPTED("snippets.ai.analysis.report.apply.outcome.interrupted", "#6B7280");

        private final String i18nKey;
        private final String colorHex;

        Outcome(String i18nKey, String colorHex) {
            this.i18nKey = i18nKey;
            this.colorHex = colorHex;
        }

        public String i18nKey() {
            return i18nKey;
        }

        public String colorHex() {
            return colorHex;
        }

        /** True when the run's result is in the script (fully or partially). */
        public boolean applied() {
            return this == ACCEPTED || this == PARTIAL_ACCEPTED;
        }
    }

    /**
     * One apply run. {@code usage} is {@code null} when the provider reported nothing; {@code diff}
     * is {@code null} when the run's content was not stored.
     */
    public record ApplyResult(
        Outcome outcome,
        boolean partial,
        Instant appliedAt,
        Instant decidedAt,
        String profileName,
        long elapsedSeconds,
        AiTokenUsage usage,
        int retries,
        int completedItems,
        int totalItems,
        List<String> hardeningLabels,
        List<String> inputHardeningLabels,
        String headerName,
        String migrationLabel,
        String aiSummary,
        List<Change> changes,
        TextLineDiff.Result diff,
        List<List<String>> hunkFindingIds) {

        public ApplyResult {
            Objects.requireNonNull(outcome, "outcome");
            profileName = profileName != null ? profileName : "";
            elapsedSeconds = Math.max(0L, elapsedSeconds);
            retries = Math.max(0, retries);
            completedItems = Math.max(0, completedItems);
            totalItems = Math.max(0, totalItems);
            hardeningLabels = hardeningLabels != null ? List.copyOf(hardeningLabels) : List.of();
            inputHardeningLabels = inputHardeningLabels != null ? List.copyOf(inputHardeningLabels) : List.of();
            headerName = headerName != null ? headerName : "";
            migrationLabel = migrationLabel != null ? migrationLabel : "";
            aiSummary = aiSummary != null ? aiSummary : "";
            changes = changes != null ? List.copyOf(changes) : List.of();
            hunkFindingIds = hunkFindingIds != null
                ? hunkFindingIds.stream().map(ids -> ids != null ? List.copyOf(ids) : List.<String>of()).toList()
                : List.of();
        }

        /** The finding ids whose changes touch hunk {@code index}. */
        public List<String> findingIdsOfHunk(int index) {
            return index >= 0 && index < hunkFindingIds.size() ? hunkFindingIds.get(index) : List.of();
        }
    }

    /**
     * The stored comparison of the reported analysis with a follow-up analysis of the applied code
     * ({@link SnippetAnalysisComparison}). Matching is heuristic, which the report says.
     */
    public record Verification(Instant verifiedAt, List<DeltaItem> resolved, List<DeltaItem> introduced,
                               List<DeltaItem> persisting) {

        public Verification {
            resolved = resolved != null ? List.copyOf(resolved) : List.of();
            introduced = introduced != null ? List.copyOf(introduced) : List.of();
            persisting = persisting != null ? List.copyOf(persisting) : List.of();
        }
    }

    /** One compared finding; {@code previousId} is the id in the reported analysis (persisting only). */
    public record DeltaItem(String id, String previousId, String title, AnalysisSeverity severity,
                            String displayCategory) {

        public DeltaItem {
            id = id != null ? id : "";
            previousId = previousId != null ? previousId : "";
            title = title != null ? title : "";
            severity = severity != null ? severity : AnalysisSeverity.INFO;
            displayCategory = displayCategory != null ? displayCategory : "design";
        }
    }

    /** A code excerpt: {@code lines} start at {@code firstLine}; {@code targetLine} is highlighted. */
    public record Excerpt(int firstLine, int targetLine, List<String> lines) {

        public Excerpt {
            lines = lines != null ? List.copyOf(lines) : List.of();
        }

        public int lastLine() {
            return firstLine + lines.size() - 1;
        }
    }

    /** Script text; {@code language} is the snippet language (a fence/CSS hint). */
    public record CodeSnapshot(String content, String language) {

        public CodeSnapshot {
            content = content != null ? content : "";
            language = language != null ? language : "";
        }
    }

    // ---- derived views ----

    public boolean isPost() {
        return kind == Kind.POST_APPLY;
    }

    /** Improvements of one display section, most severe first (stable for equal severity). */
    public List<Finding> findingsOf(String displayCategory) {
        return findings.stream()
            .filter(finding -> finding.displayCategory().equals(displayCategory))
            .sorted(java.util.Comparator.comparingInt(finding -> finding.severity().rank()))
            .toList();
    }
}
