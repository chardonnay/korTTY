package de.kortty.core;

import de.kortty.core.SnippetAnalysisRecord.RunOutcome;
import de.kortty.core.SnippetAnalysisReport.Finding;
import de.kortty.core.SnippetAnalysisReport.FindingStatus;
import de.kortty.core.SnippetAnalysisReport.Outcome;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.assertThrows;

/** How the stored analysis becomes a before/after report: statuses, change mapping, excerpts, flags. */
class SnippetAnalysisReportsTest {

    private Locale previous;

    @BeforeClass
    void english() {
        previous = LanguageManager.getInstance().getCurrentLocale();
        LanguageManager.getInstance().setLocale(Locale.ENGLISH);
    }

    @AfterClass(alwaysRun = true)
    void restore() {
        if (previous != null) {
            LanguageManager.getInstance().setLocale(previous);
        }
    }

    @Test
    void statusTable() {
        // not selected wins over everything
        for (Outcome outcome : Outcome.values()) {
            assertThat(SnippetAnalysisReports.deriveStatus(false, outcome, true, true))
                .isEqualTo(FindingStatus.NOT_SELECTED);
        }
        assertThat(SnippetAnalysisReports.deriveStatus(true, Outcome.REJECTED, true, true))
            .isEqualTo(FindingStatus.REJECTED);
        assertThat(SnippetAnalysisReports.deriveStatus(true, Outcome.FAILED, true, true))
            .isEqualTo(FindingStatus.FAILED);
        assertThat(SnippetAnalysisReports.deriveStatus(true, Outcome.CANCELLED, false, false))
            .isEqualTo(FindingStatus.FAILED);
        assertThat(SnippetAnalysisReports.deriveStatus(true, Outcome.INTERRUPTED, false, false))
            .isEqualTo(FindingStatus.FAILED);
        assertThat(SnippetAnalysisReports.deriveStatus(true, Outcome.PROPOSED, true, true))
            .isEqualTo(FindingStatus.PROPOSED);
        assertThat(SnippetAnalysisReports.deriveStatus(true, Outcome.PARTIAL_ACCEPTED, true, false))
            .isEqualTo(FindingStatus.APPLIED);
        assertThat(SnippetAnalysisReports.deriveStatus(true, Outcome.PARTIAL_ACCEPTED, false, true))
            .isEqualTo(FindingStatus.NOT_REACHED);
        assertThat(SnippetAnalysisReports.deriveStatus(true, Outcome.ACCEPTED, false, true))
            .isEqualTo(FindingStatus.APPLIED);
        assertThat(SnippetAnalysisReports.deriveStatus(true, Outcome.ACCEPTED, false, false))
            .isEqualTo(FindingStatus.UNCONFIRMED);
    }

    @Test
    void runOutcomesMapToReportOutcomes() {
        assertThat(SnippetAnalysisReports.outcomeOf(SnippetAnalysisReportFixtures.run(RunOutcome.ACCEPTED, false)))
            .isEqualTo(Outcome.ACCEPTED);
        assertThat(SnippetAnalysisReports.outcomeOf(SnippetAnalysisReportFixtures.run(RunOutcome.ACCEPTED, true)))
            .isEqualTo(Outcome.PARTIAL_ACCEPTED);
        assertThat(SnippetAnalysisReports.outcomeOf(
            SnippetAnalysisReportFixtures.run(RunOutcome.PENDING_REVIEW, false))).isEqualTo(Outcome.PROPOSED);
        assertThat(SnippetAnalysisReports.outcomeOf(SnippetAnalysisReportFixtures.run(RunOutcome.REJECTED, false)))
            .isEqualTo(Outcome.REJECTED);
        assertThat(SnippetAnalysisReports.outcomeOf(SnippetAnalysisReportFixtures.run(RunOutcome.FAILED, true)))
            .isEqualTo(Outcome.FAILED);
        assertThat(SnippetAnalysisReports.outcomeOf(SnippetAnalysisReportFixtures.run(RunOutcome.CANCELLED, false)))
            .isEqualTo(Outcome.CANCELLED);
        assertThat(SnippetAnalysisReports.outcomeOf(
            SnippetAnalysisReportFixtures.run(RunOutcome.INTERRUPTED, false))).isEqualTo(Outcome.INTERRUPTED);
    }

    @Test
    void preApplyReportsSelectionWithoutStatus() {
        SnippetAnalysisReport report = SnippetAnalysisReportFixtures.preReport();
        assertThat(report.kind()).isEqualTo(SnippetAnalysisReport.Kind.PRE_APPLY);
        assertThat(report.apply()).isNull();
        Map<String, Finding> byId = byId(report);
        assertThat(byId.get("SEC-1").selected()).isTrue();
        assertThat(byId.get("DES-2").selected()).isFalse();
        assertThat(byId.get("SEC-1").status()).isNull();
        // Anything that is not security or optimization is shown under design, tagged with its category.
        assertThat(byId.get("DES-1").displayCategory()).isEqualTo("design");
        assertThat(byId.get("DES-1").category()).isEqualTo("dependency");
        assertThat(report.findingsOf("security").stream().map(Finding::id).toList())
            .containsExactly("SEC-2", "SEC-1").inOrder();
        assertThat(report.header().analysedAt().toEpochMilli()).isEqualTo(SnippetAnalysisReportFixtures.ANALYSED_AT);
        assertThat(report.header().appliedAt()).isNull();
        assertThat(report.header().stale()).isFalse();
        assertThat(report.diagram().mermaidSource()).contains("flowchart TD");
        assertThat(report.header().runCount()).isEqualTo(0);
    }

    @Test
    void postApplyDerivesStatusesAndGroupsReasons() {
        SnippetAnalysisReport report = SnippetAnalysisReportFixtures.postReport();
        Map<String, Finding> byId = byId(report);
        assertThat(byId.get("SEC-1").status()).isEqualTo(FindingStatus.APPLIED);
        assertThat(byId.get("SEC-2").status()).isEqualTo(FindingStatus.APPLIED);
        assertThat(byId.get("OPT-1").status()).isEqualTo(FindingStatus.UNCONFIRMED);
        assertThat(byId.get("DES-2").status()).isEqualTo(FindingStatus.NOT_SELECTED);
        assertThat(report.dependencies().getFirst().status()).isEqualTo(FindingStatus.APPLIED);
        assertThat(report.dependencies().get(1).status()).isEqualTo(FindingStatus.NOT_SELECTED);
        // Both SEC-1 reasons end up on SEC-1's card, none on the others.
        assertThat(byId.get("SEC-1").changes()).hasSize(2);
        assertThat(byId.get("SEC-2").changes()).hasSize(1);
        assertThat(byId.get("OPT-1").changes()).isEmpty();

        SnippetAnalysisReport.ApplyResult apply = report.apply();
        assertThat(apply.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(apply.elapsedSeconds()).isEqualTo(94L);
        assertThat(apply.usage().totalTokens()).isEqualTo(4592L);
        assertThat(apply.retries()).isEqualTo(1);
        assertThat(apply.hardeningLabels()).hasSize(2);
        assertThat(apply.diff()).isNotNull();
        assertThat(apply.diff().added()).isGreaterThan(0);
        assertThat(report.header().runNumber()).isEqualTo(1);
        assertThat(report.header().runCount()).isEqualTo(1);
        assertThat(report.header().appliedAt().toEpochMilli()).isEqualTo(SnippetAnalysisReportFixtures.APPLIED_AT);
        // The editor holds the result: that is not "changed since the analysis".
        assertThat(report.header().stale()).isFalse();
        assertThat(report.resultCode().content()).isEqualTo(SnippetAnalysisReportFixtures.RESULT);
    }

    @Test
    void hunksAreMappedByAnchor() {
        SnippetAnalysisReport report = SnippetAnalysisReportFixtures.postReport();
        TextLineDiff.Result diff = report.apply().diff();
        Finding sec1 = byId(report).get("SEC-1");
        assertThat(sec1.hunkIndexes()).isNotEmpty();
        for (int hunk : sec1.hunkIndexes()) {
            assertThat(report.apply().findingIdsOfHunk(hunk)).contains("SEC-1");
            assertThat(hunk).isLessThan(diff.hunks().size());
        }
        // The anchor line itself lies in the new-side range of the mapped hunk.
        String anchor = "sha256sum --check install.sh.sha256";
        List<Integer> hunks = SnippetAnalysisReports.hunksForAnchor(diff, SnippetAnalysisReportFixtures.RESULT, anchor);
        assertThat(hunks).hasSize(1);
        TextLineDiff.Hunk hunk = diff.hunks().get(hunks.getFirst());
        assertThat(hunk.lines().stream().map(TextLineDiff.Line::text).toList()).contains(anchor);
        // No match, blank anchors and a missing result map to nothing.
        assertThat(SnippetAnalysisReports.hunksForAnchor(diff, SnippetAnalysisReportFixtures.RESULT, "no such line"))
            .isEmpty();
        assertThat(SnippetAnalysisReports.hunksForAnchor(diff, SnippetAnalysisReportFixtures.RESULT, "  \n "))
            .isEmpty();
        assertThat(SnippetAnalysisReports.hunksForAnchor(diff, null, anchor)).isEmpty();
        // A multi-line anchor uses its first non-blank line; containment is the fallback.
        assertThat(SnippetAnalysisReports.hunksForAnchor(diff, SnippetAnalysisReportFixtures.RESULT,
            "\n  sha256sum --check\nmore")).isEqualTo(hunks);
    }

    @Test
    void excerptBounds() {
        String content = "l1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\nl10\n";
        SnippetAnalysisReport.Excerpt middle = SnippetAnalysisReports.excerpt(content, 5, 3);
        assertThat(middle.firstLine()).isEqualTo(2);
        assertThat(middle.lines()).containsExactly("l2", "l3", "l4", "l5", "l6", "l7", "l8").inOrder();
        assertThat(middle.targetLine()).isEqualTo(5);
        SnippetAnalysisReport.Excerpt first = SnippetAnalysisReports.excerpt(content, 1, 3);
        assertThat(first.firstLine()).isEqualTo(1);
        assertThat(first.lines()).containsExactly("l1", "l2", "l3", "l4").inOrder();
        SnippetAnalysisReport.Excerpt last = SnippetAnalysisReports.excerpt(content, 10, 3);
        assertThat(last.lines()).containsExactly("l7", "l8", "l9", "l10").inOrder();
        assertThat(last.lastLine()).isEqualTo(10);
        // The trailing newline is not a line 11; lines outside the text have no excerpt.
        assertThat(SnippetAnalysisReports.excerpt(content, 11, 3)).isNull();
        assertThat(SnippetAnalysisReports.excerpt(content, 0, 3)).isNull();
        assertThat(SnippetAnalysisReports.excerpt(null, 3, 3)).isNull();
        assertThat(SnippetAnalysisReports.excerpt("a\r\nb\r\nc", 2, 1).lines()).containsExactly("a", "b", "c").inOrder();
    }

    @Test
    void staleFlag() {
        SnippetAnalysisRecord record = SnippetAnalysisReportFixtures.analysed();
        assertThat(SnippetAnalysisReports.preApply(record,
            new SnippetAnalysisReports.ReportContext("x", "bash", SnippetAnalysisReportFixtures.SCRIPT))
            .header().stale()).isFalse();
        assertThat(SnippetAnalysisReports.preApply(record,
            new SnippetAnalysisReports.ReportContext("x", "bash", "echo changed\n")).header().stale()).isTrue();
        // Unknown content is never stale.
        assertThat(SnippetAnalysisReports.preApply(record, new SnippetAnalysisReports.ReportContext("x", "bash", null))
            .header().stale()).isFalse();
        // After applying, content that is neither the source nor the result is stale.
        assertThat(SnippetAnalysisReports.postApply(SnippetAnalysisReportFixtures.applied(), null, List.of(),
            new SnippetAnalysisReports.ReportContext("x", "bash", "echo other\n")).header().stale()).isTrue();
    }

    @Test
    void missingSourceGivesNoExcerptsAndNoDiff() {
        SnippetAnalysisRecord record = SnippetAnalysisReportFixtures.applied();
        SnippetAnalysisRecord withoutSource = record.withSource(record.source().withContent(null));
        SnippetAnalysisReport pre = SnippetAnalysisReports.preApply(withoutSource, SnippetAnalysisReportFixtures.context());
        assertThat(pre.findings().stream().allMatch(finding -> finding.excerpt() == null)).isTrue();
        assertThat(pre.analysedCode()).isNull();
        SnippetAnalysisReport post = SnippetAnalysisReports.postApply(withoutSource, "run-1", List.of(),
            SnippetAnalysisReportFixtures.context());
        assertThat(post.apply().diff()).isNull();
        assertThat(post.resultCode()).isNotNull();

        SnippetAnalysisReport full = SnippetAnalysisReportFixtures.preReport();
        Finding sec1 = byId(full).get("SEC-1");
        assertThat(sec1.excerpt().targetLine()).isEqualTo(12);
        assertThat(sec1.excerpt().lines()).contains("rm -rf $TARGET_DIR/*");
        assertThat(byId(full).get("DES-2").excerpt()).isNull();
    }

    @Test
    void verificationComesFromTheFollowUpAnalysis() {
        SnippetAnalysisReport report = SnippetAnalysisReportFixtures.postReport();
        SnippetAnalysisReport.Verification verification = report.verification();
        assertThat(verification).isNotNull();
        assertThat(verification.resolved().stream().map(SnippetAnalysisReport.DeltaItem::id).toList())
            .containsExactly("SEC-1", "SEC-2", "OPT-1").inOrder();
        assertThat(verification.introduced().getFirst().id()).isEqualTo("OPT-9");
        assertThat(verification.persisting().getFirst().id()).isEqualTo("DES-4");
        assertThat(verification.persisting().getFirst().previousId()).isEqualTo("DES-1");
        // A later analysis of other content (not what the run produced) is not a verification.
        SnippetAnalysisRecord unrelated = SnippetAnalysisReportFixtures.verification()
            .withSource(SnippetAnalysisRecord.Source.of("echo other\n", "bash", "en", "en", "x"));
        assertThat(SnippetAnalysisReports.postApply(SnippetAnalysisReportFixtures.applied(), null,
            List.of(unrelated), SnippetAnalysisReportFixtures.context()).verification()).isNull();
        // No history, no verification.
        assertThat(SnippetAnalysisReports.postApply(SnippetAnalysisReportFixtures.applied(), null, null,
            SnippetAnalysisReportFixtures.context()).verification()).isNull();
    }

    @Test
    void unknownRunIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SnippetAnalysisReports.postApply(
            SnippetAnalysisReportFixtures.analysed(), null, List.of(), SnippetAnalysisReportFixtures.context()));
        assertThrows(IllegalArgumentException.class, () -> SnippetAnalysisReports.postApply(
            SnippetAnalysisReportFixtures.applied(), "nope", List.of(), SnippetAnalysisReportFixtures.context()));
        // RUNNING runs are not reportable.
        SnippetAnalysisRecord running = SnippetAnalysisReportFixtures.analysed()
            .withRun(SnippetAnalysisReportFixtures.run(RunOutcome.RUNNING, false));
        assertThat(SnippetAnalysisReports.reportableRuns(running)).isEmpty();
    }

    @Test
    void partialRunCountsCompletedStagesOnly() {
        SnippetAnalysisRecord record = SnippetAnalysisReportFixtures.analysed()
            .withRun(SnippetAnalysisReportFixtures.run(RunOutcome.ACCEPTED, true));
        SnippetAnalysisReport report = SnippetAnalysisReports.postApply(record, null, List.of(),
            SnippetAnalysisReportFixtures.context());
        assertWithMessage("outcome").that(report.apply().outcome()).isEqualTo(Outcome.PARTIAL_ACCEPTED);
        Map<String, Finding> byId = byId(report);
        // SEC-1/SEC-2 are completed work items; OPT-1 is in appliedFindingIds of the fixture, so it counts too.
        assertThat(byId.get("SEC-1").status()).isEqualTo(FindingStatus.APPLIED);
        assertThat(byId.get("DES-2").status()).isEqualTo(FindingStatus.NOT_SELECTED);

        SnippetAnalysisRecord rejected = SnippetAnalysisReportFixtures.analysed()
            .withRun(SnippetAnalysisReportFixtures.run(RunOutcome.REJECTED, false));
        SnippetAnalysisReport rejectedReport = SnippetAnalysisReports.postApply(rejected, null, List.of(),
            SnippetAnalysisReportFixtures.context());
        assertThat(byId(rejectedReport).get("SEC-1").status()).isEqualTo(FindingStatus.REJECTED);
        assertThat(rejectedReport.verification()).isNull();
    }

    private static Map<String, Finding> byId(SnippetAnalysisReport report) {
        return report.findings().stream().collect(Collectors.toMap(Finding::id, Function.identity()));
    }
}
