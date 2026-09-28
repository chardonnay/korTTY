package de.kortty.core;

import de.kortty.core.SnippetAnalysisRecord.ApplyRun;
import de.kortty.core.SnippetAnalysisRecord.RunOutcome;
import de.kortty.core.SnippetAnalysisReport.ApplyResult;
import de.kortty.core.SnippetAnalysisReport.Change;
import de.kortty.core.SnippetAnalysisReport.CodeSnapshot;
import de.kortty.core.SnippetAnalysisReport.DeltaItem;
import de.kortty.core.SnippetAnalysisReport.Dependency;
import de.kortty.core.SnippetAnalysisReport.Diagram;
import de.kortty.core.SnippetAnalysisReport.Excerpt;
import de.kortty.core.SnippetAnalysisReport.Finding;
import de.kortty.core.SnippetAnalysisReport.FindingStatus;
import de.kortty.core.SnippetAnalysisReport.Header;
import de.kortty.core.SnippetAnalysisReport.Outcome;
import de.kortty.core.SnippetAnalysisReport.Verification;
import de.kortty.ui.I18n;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds {@link SnippetAnalysisReport}s from the stored analysis ({@link SnippetAnalysisRecord}).
 *
 * <p>The reports never compare anything themselves: what an apply run did comes from the stored
 * run (its request, completed work items, change reasons and result), and the verification of an
 * applied run is the one {@link SnippetAnalysisComparison} stored on the follow-up analysis — the
 * same data the analysis panel shows, so the two cannot disagree.</p>
 *
 * <p>Status of a finding in an apply run ({@link #deriveStatus}):</p>
 * <table>
 *   <caption>Status rules</caption>
 *   <tr><th>Condition</th><th>Status</th></tr>
 *   <tr><td>not selected for the run</td><td>NOT_SELECTED</td></tr>
 *   <tr><td>run rejected</td><td>REJECTED</td></tr>
 *   <tr><td>run failed, cancelled or interrupted</td><td>FAILED</td></tr>
 *   <tr><td>result waits for review</td><td>PROPOSED</td></tr>
 *   <tr><td>partial result accepted, its stage completed</td><td>APPLIED</td></tr>
 *   <tr><td>partial result accepted, its stage not reached</td><td>NOT_REACHED</td></tr>
 *   <tr><td>accepted, confirmed by a change reason or a completed work item</td><td>APPLIED</td></tr>
 *   <tr><td>accepted without such a confirmation</td><td>UNCONFIRMED</td></tr>
 * </table>
 */
public final class SnippetAnalysisReports {

    /** Lines of context shown above and below a finding's line. */
    public static final int EXCERPT_RADIUS = 3;
    /** Diff budget for reports: more generous than the UI default, still bounded. */
    static final int REPORT_MAX_EDITS = 4000;

    private static final Set<String> SECTIONS = Set.of("security", "optimization", "design");

    private SnippetAnalysisReports() {
    }

    /**
     * What the caller knows beyond the record.
     *
     * @param scriptName     the snippet's current name (falls back to the name stored with the analysis)
     * @param scriptLanguage the snippet's language (falls back to the stored one)
     * @param currentContent the editor's content, for the "changed since" flag; {@code null} = unknown
     */
    public record ReportContext(String scriptName, String scriptLanguage, String currentContent) {
    }

    /** The runs a POST report can describe: every run that got past RUNNING, oldest first. */
    public static List<ApplyRun> reportableRuns(SnippetAnalysisRecord record) {
        if (record == null) {
            return List.of();
        }
        return record.applyRuns().stream().filter(run -> run.outcome() != RunOutcome.RUNNING).toList();
    }

    /** The report before applying: the analysis and the current selection. */
    public static SnippetAnalysisReport preApply(SnippetAnalysisRecord record, ReportContext context) {
        Objects.requireNonNull(record, "record");
        ReportContext ctx = context != null ? context : new ReportContext(null, null, null);
        Set<String> selected = new LinkedHashSet<>();
        if (record.selection() != null) {
            selected.addAll(record.selection().improvementIds());
            selected.addAll(record.selection().dependencyIds());
        }
        String analysed = record.source().content();
        List<Finding> findings = new ArrayList<>();
        for (SnippetAnalysisRecord.Finding finding : record.improvements()) {
            findings.add(finding(finding, containsIgnoreCase(selected, finding.id()), null, List.of(), List.of(),
                analysed));
        }
        List<Dependency> dependencies = new ArrayList<>();
        for (SnippetAnalysisRecord.DependencyFinding dependency : record.dependencies()) {
            dependencies.add(new Dependency(dependency.id(), dependency.name(), dependency.kind(),
                dependency.purpose(), dependency.suggestion(), containsIgnoreCase(selected, dependency.id()), null,
                List.of()));
        }
        boolean stale = isStale(record.source().sha256(), ctx.currentContent());
        Header header = header(record, ctx, null, stale, 0, reportableRuns(record).size());
        return new SnippetAnalysisReport(SnippetAnalysisReport.Kind.PRE_APPLY, header, record.summary(), findings,
            dependencies, diagram(record), null, null, codeSnapshot(analysed, header.scriptLanguage()), null);
    }

    /**
     * The report after applying run {@code runId} ({@code null}: the newest reportable run).
     *
     * @param history every stored record of the snippet, newest first; used to find the follow-up
     *                analysis that verified this run ({@code null} or empty: no verification)
     * @throws IllegalArgumentException when the record has no such (reportable) run
     */
    public static SnippetAnalysisReport postApply(SnippetAnalysisRecord record, String runId,
                                                  List<SnippetAnalysisRecord> history, ReportContext context) {
        Objects.requireNonNull(record, "record");
        ReportContext ctx = context != null ? context : new ReportContext(null, null, null);
        List<ApplyRun> runs = reportableRuns(record);
        ApplyRun run = null;
        int runNumber = 0;
        for (int index = 0; index < runs.size(); index++) {
            if (runId == null ? index == runs.size() - 1 : runs.get(index).id().equals(runId)) {
                run = runs.get(index);
                runNumber = index + 1;
            }
        }
        if (run == null) {
            throw new IllegalArgumentException("The analysis has no finished apply run " + runId);
        }
        Outcome outcome = outcomeOf(run);
        Set<String> selected = new LinkedHashSet<>();
        selected.addAll(run.request().improvementIds());
        selected.addAll(run.request().dependencyIds());
        Set<String> completed = new LinkedHashSet<>(run.completedWorkItemIds());
        if (run.partial()) {
            completed.addAll(run.appliedFindingIds());
        }

        String before = beforeContent(record, run);
        String result = run.resultContent();
        TextLineDiff.Result diff = before != null && result != null
            ? TextLineDiff.diff(before, result, TextLineDiff.DEFAULT_CONTEXT, REPORT_MAX_EDITS)
            : null;
        List<Change> changes = run.changes().stream()
            .map(change -> new Change(change.finding(), change.anchor(), change.reason()))
            .toList();
        Map<String, List<Integer>> hunksByFinding = new HashMap<>();
        List<List<String>> findingsByHunk = new ArrayList<>();
        if (diff != null) {
            diff.hunks().forEach(hunk -> findingsByHunk.add(new ArrayList<>()));
            for (Change change : changes) {
                for (int hunk : hunksForAnchor(diff, result, change.anchor())) {
                    String key = change.findingId().toLowerCase(Locale.ROOT);
                    List<Integer> indexes = hunksByFinding.computeIfAbsent(key, ignored -> new ArrayList<>());
                    if (!indexes.contains(hunk)) {
                        indexes.add(hunk);
                    }
                    if (!change.findingId().isBlank() && !findingsByHunk.get(hunk).contains(change.findingId())) {
                        findingsByHunk.get(hunk).add(change.findingId());
                    }
                }
            }
        }

        String analysed = record.source().content();
        List<Finding> findings = new ArrayList<>();
        for (SnippetAnalysisRecord.Finding finding : record.improvements()) {
            boolean isSelected = containsIgnoreCase(selected, finding.id());
            List<Change> own = changesOf(changes, finding.id());
            FindingStatus status = deriveStatus(isSelected, outcome, containsIgnoreCase(completed, finding.id()),
                !own.isEmpty() || containsIgnoreCase(completed, finding.id()));
            List<Integer> hunks = hunksByFinding.getOrDefault(finding.id().toLowerCase(Locale.ROOT), List.of());
            findings.add(finding(finding, isSelected, status, own, hunks.stream().sorted().toList(), analysed));
        }
        List<Dependency> dependencies = new ArrayList<>();
        for (SnippetAnalysisRecord.DependencyFinding dependency : record.dependencies()) {
            boolean isSelected = containsIgnoreCase(selected, dependency.id());
            List<Change> own = changesOf(changes, dependency.id());
            FindingStatus status = deriveStatus(isSelected, outcome, containsIgnoreCase(completed, dependency.id()),
                !own.isEmpty() || containsIgnoreCase(completed, dependency.id()));
            dependencies.add(new Dependency(dependency.id(), dependency.name(), dependency.kind(),
                dependency.purpose(), dependency.suggestion(), isSelected, status, own));
        }

        SnippetAnalysisRecord.RunStats stats = run.stats();
        long elapsed = stats.elapsedSeconds();
        if (elapsed == 0 && run.finishedAt() > run.startedAt() && run.startedAt() > 0) {
            elapsed = (run.finishedAt() - run.startedAt()) / 1000L;
        }
        int totalItems = stats.totalItems() > 0 ? stats.totalItems() : run.items().size();
        int completedItems = stats.totalItems() > 0 ? stats.completedItems() : (int) run.items().stream()
            .filter(item -> "done".equalsIgnoreCase(item.state()) || "completed".equalsIgnoreCase(item.state()))
            .count();
        SnippetAnalysisRecord.Usage usage = stats.usage();
        String profileName = !run.provenance().profileName().isBlank()
            ? run.provenance().profileName()
            : record.provenance().profileName();
        ApplyResult apply = new ApplyResult(outcome, run.partial(), instant(run.startedAt()),
            instant(run.decidedAt()), profileName, elapsed,
            usage.totalTokens() > 0 ? usage.toAiTokenUsage() : null, stats.retries(), completedItems, totalItems,
            run.request().hardeningOptions().stream().map(name -> label("ai.workflow.option." + name, name)).toList(),
            run.request().inputHardeningOptions().stream()
                .map(name -> label("ai.inputHardening.option." + name, name)).toList(),
            headerName(run.request()), migrationLabel(run.request()), run.summary(), changes, diff, findingsByHunk);

        boolean stale = isStale(record.source().sha256(), ctx.currentContent())
            && isStale(run.acceptedContentSha256(), ctx.currentContent())
            && isStale(run.resultSha256(), ctx.currentContent());
        Header header = header(record, ctx, run, stale, runNumber, runs.size());
        return new SnippetAnalysisReport(SnippetAnalysisReport.Kind.POST_APPLY, header, record.summary(), findings,
            dependencies, diagram(record), apply, verification(record, run, history),
            codeSnapshot(analysed, header.scriptLanguage()),
            result != null ? new CodeSnapshot(result, header.scriptLanguage()) : null);
    }

    // ---- status ----

    /** Maps a stored run to the report's outcome; {@code partial} only qualifies an accepted run. */
    public static Outcome outcomeOf(ApplyRun run) {
        return switch (run.outcome()) {
            case ACCEPTED -> run.partial() ? Outcome.PARTIAL_ACCEPTED : Outcome.ACCEPTED;
            case PENDING_REVIEW, RUNNING -> Outcome.PROPOSED;
            case REJECTED -> Outcome.REJECTED;
            case FAILED -> Outcome.FAILED;
            case CANCELLED -> Outcome.CANCELLED;
            case INTERRUPTED -> Outcome.INTERRUPTED;
        };
    }

    /**
     * The status table of the class comment.
     *
     * @param stageCompleted the finding's work item finished (partial runs)
     * @param confirmed      a change reason names the finding, or its work item finished
     */
    static FindingStatus deriveStatus(boolean selected, Outcome outcome, boolean stageCompleted, boolean confirmed) {
        if (!selected) {
            return FindingStatus.NOT_SELECTED;
        }
        return switch (outcome) {
            case REJECTED -> FindingStatus.REJECTED;
            case FAILED, CANCELLED, INTERRUPTED -> FindingStatus.FAILED;
            case PROPOSED -> FindingStatus.PROPOSED;
            case PARTIAL_ACCEPTED -> stageCompleted ? FindingStatus.APPLIED : FindingStatus.NOT_REACHED;
            case ACCEPTED -> confirmed ? FindingStatus.APPLIED : FindingStatus.UNCONFIRMED;
        };
    }

    // ---- diff mapping ----

    /**
     * The hunks a change's anchor lands in: the first line of {@code resultContent} whose trimmed
     * text equals the anchor's first non-blank line, else the first line containing it; the hunk
     * whose new-side range covers that line gets the change. Empty when nothing matches.
     */
    static List<Integer> hunksForAnchor(TextLineDiff.Result diff, String resultContent, String anchor) {
        if (diff == null || resultContent == null || anchor == null) {
            return List.of();
        }
        String needle = "";
        for (String line : anchor.split("\\R")) {
            if (!line.isBlank()) {
                needle = line.strip();
                break;
            }
        }
        if (needle.isEmpty()) {
            return List.of();
        }
        String[] lines = resultContent.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int match = -1;
        for (int index = 0; index < lines.length && match < 0; index++) {
            if (lines[index].strip().equals(needle)) {
                match = index + 1;
            }
        }
        for (int index = 0; index < lines.length && match < 0; index++) {
            if (lines[index].contains(needle)) {
                match = index + 1;
            }
        }
        if (match < 0) {
            return List.of();
        }
        List<TextLineDiff.Hunk> hunks = diff.hunks();
        for (int index = 0; index < hunks.size(); index++) {
            TextLineDiff.Hunk hunk = hunks.get(index);
            if (match >= hunk.newStart() && match < hunk.newStart() + hunk.newCount()) {
                return List.of(index);
            }
        }
        return List.of();
    }

    // ---- excerpts & staleness ----

    /**
     * Lines {@code line - radius .. line + radius} of {@code content} (clamped); {@code null} when
     * the content is unknown or the line lies outside it.
     */
    static Excerpt excerpt(String content, Integer line, int radius) {
        if (content == null || line == null || line <= 0) {
            return null;
        }
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int count = content.endsWith("\n") || content.endsWith("\r") ? lines.length - 1 : lines.length;
        if (line > count) {
            return null;
        }
        int first = Math.max(1, line - Math.max(0, radius));
        int last = Math.min(count, line + Math.max(0, radius));
        List<String> excerpt = new ArrayList<>();
        for (int number = first; number <= last; number++) {
            excerpt.add(lines[number - 1]);
        }
        return new Excerpt(first, line, excerpt);
    }

    /** Same rule as {@link SnippetDiagramSupport#isStale}: a blank hash or unknown content is never stale. */
    static boolean isStale(String sha256, String currentContent) {
        return sha256 != null && !sha256.isBlank() && currentContent != null
            && !sha256.equals(SnippetDiagramSupport.contentHash(currentContent));
    }

    // ---- helpers ----

    private static Finding finding(SnippetAnalysisRecord.Finding finding, boolean selected, FindingStatus status,
                                   List<Change> changes, List<Integer> hunks, String analysedContent) {
        return new Finding(finding.id(), displayCategory(finding.category()), finding.category(),
            AnalysisSeverity.fromLabel(finding.severity()), finding.severity(), finding.title(), finding.detail(),
            finding.recommendation(), finding.line(), selected, status, changes, hunks,
            excerpt(analysedContent, finding.line(), EXCERPT_RADIUS));
    }

    /** The section a finding is shown in: anything that is not security or optimization is design. */
    static String displayCategory(String category) {
        String value = category != null ? category.trim().toLowerCase(Locale.ROOT) : "";
        return SECTIONS.contains(value) ? value : "design";
    }

    private static Header header(SnippetAnalysisRecord record, ReportContext ctx, ApplyRun run, boolean stale,
                                 int runNumber, int runCount) {
        SnippetAnalysisRecord.Source source = record.source();
        String name = ctx.scriptName() != null && !ctx.scriptName().isBlank() ? ctx.scriptName().trim()
            : source.snippetName();
        String language = ctx.scriptLanguage() != null && !ctx.scriptLanguage().isBlank()
            ? ctx.scriptLanguage().trim() : source.language();
        String codeText = record.selection() != null && record.selection().codeTextLanguageCode() != null
            ? record.selection().codeTextLanguageCode() : source.codeTextLanguageCode();
        if (run != null && run.request().codeTextLanguageCode() != null) {
            codeText = run.request().codeTextLanguageCode();
        }
        return new Header(name, record.snippetId(), language, record.provenance().profileName(),
            record.provenance().model(), record.provenance().skillNames(), source.reportLanguageCode(), codeText,
            instant(record.analyzedAt()), run != null ? instant(run.startedAt()) : null, source.sha256(), stale,
            runNumber, runCount);
    }

    private static Diagram diagram(SnippetAnalysisRecord record) {
        SnippetAnalysisRecord.AnalysisDiagram diagram = record.diagram();
        if (diagram == null || diagram.mermaid().isBlank()) {
            return null;
        }
        return new Diagram(diagram.mermaid(), diagram.diagramType(), diagram.fallback(), diagram.notice());
    }

    /**
     * The verification of {@code run}: the stored comparison on a later analysis of this record
     * whose analysed content is exactly what the run produced (its accepted editor content or its
     * result). The newest such analysis wins.
     */
    private static Verification verification(SnippetAnalysisRecord record, ApplyRun run,
                                             List<SnippetAnalysisRecord> history) {
        if (history == null || history.isEmpty() || !run.isAccepted()) {
            return null;
        }
        long after = Math.max(run.decidedAt(), run.startedAt());
        for (SnippetAnalysisRecord candidate : history) {
            SnippetAnalysisRecord.Verification stored = candidate.verification();
            if (candidate.id().equals(record.id()) || stored == null || !record.id().equals(stored.previousRecordId())
                    || candidate.analyzedAt() < after) {
                continue;
            }
            String analysedSha = candidate.source().sha256();
            if (analysedSha.isBlank() || !(analysedSha.equals(run.acceptedContentSha256())
                    || analysedSha.equals(run.resultSha256()))) {
                continue;
            }
            List<DeltaItem> resolved = new ArrayList<>();
            for (String id : stored.resolvedPreviousIds()) {
                SnippetAnalysisRecord.Finding previous = findFinding(record, id);
                resolved.add(previous != null ? delta(previous, previous.id(), "")
                    : dependencyDelta(record, id, id, ""));
            }
            List<DeltaItem> persisting = new ArrayList<>();
            stored.persistingCurrentToPrevious().forEach((currentId, previousId) -> {
                SnippetAnalysisRecord.Finding current = findFinding(candidate, currentId);
                persisting.add(current != null ? delta(current, currentId, previousId)
                    : dependencyDelta(candidate, currentId, currentId, previousId));
            });
            List<DeltaItem> introduced = new ArrayList<>();
            for (String id : stored.newIds()) {
                SnippetAnalysisRecord.Finding current = findFinding(candidate, id);
                introduced.add(current != null ? delta(current, id, "") : dependencyDelta(candidate, id, id, ""));
            }
            return new Verification(instant(candidate.analyzedAt()), resolved, introduced, persisting);
        }
        return null;
    }

    private static DeltaItem delta(SnippetAnalysisRecord.Finding finding, String id, String previousId) {
        return new DeltaItem(id, previousId, finding.title(), AnalysisSeverity.fromLabel(finding.severity()),
            displayCategory(finding.category()));
    }

    private static DeltaItem dependencyDelta(SnippetAnalysisRecord record, String lookupId, String id,
                                             String previousId) {
        for (SnippetAnalysisRecord.DependencyFinding dependency : record.dependencies()) {
            if (dependency.id().equals(lookupId)) {
                return new DeltaItem(id, previousId, dependency.name(), AnalysisSeverity.INFO, "dependencies");
            }
        }
        return new DeltaItem(id, previousId, id, AnalysisSeverity.INFO, "design");
    }

    private static SnippetAnalysisRecord.Finding findFinding(SnippetAnalysisRecord record, String id) {
        for (SnippetAnalysisRecord.Finding finding : record.improvements()) {
            if (finding.id().equals(id)) {
                return finding;
            }
        }
        return null;
    }

    /**
     * The content the run started from: its own stored base, or the analysed source when the run
     * started from exactly that (the base is only stored when it differs).
     */
    private static String beforeContent(SnippetAnalysisRecord record, ApplyRun run) {
        if (run.request().baseContent() != null) {
            return run.request().baseContent();
        }
        String base = run.beforeSha256();
        if (base.isBlank() || base.equals(record.source().sha256())) {
            return record.source().content();
        }
        return null;
    }

    private static List<Change> changesOf(List<Change> changes, String findingId) {
        return changes.stream().filter(change -> change.findingId().equalsIgnoreCase(findingId)).toList();
    }

    private static boolean containsIgnoreCase(Set<String> ids, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        if (ids.contains(id)) {
            return true;
        }
        for (String candidate : ids) {
            if (candidate.equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    private static String headerName(SnippetAnalysisRecord.ApplyRequestSnapshot request) {
        if (request.headerName() != null) {
            return request.headerName();
        }
        return request.headerText().isBlank() ? "" : I18n.get("snippets.ai.analysis.header.applied");
    }

    private static String migrationLabel(SnippetAnalysisRecord.ApplyRequestSnapshot request) {
        if (request.migrationLabel() != null) {
            return request.migrationLabel();
        }
        if (request.migrationTargetLanguage() == null) {
            return "";
        }
        return request.migrationTargetHostFormat() != null
            ? request.migrationTargetLanguage() + " / " + request.migrationTargetHostFormat()
            : request.migrationTargetLanguage();
    }

    private static CodeSnapshot codeSnapshot(String content, String language) {
        return content != null ? new CodeSnapshot(content, language) : null;
    }

    private static String label(String key, String fallback) {
        String value = I18n.get(key);
        return value == null || value.equals(key) ? fallback : value;
    }

    private static Instant instant(long epochMillis) {
        return epochMillis > 0 ? Instant.ofEpochMilli(epochMillis) : null;
    }
}
