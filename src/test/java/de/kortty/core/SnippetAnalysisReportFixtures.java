package de.kortty.core;

import de.kortty.core.SnippetAnalysisRecord.ApplyRequestSnapshot;
import de.kortty.core.SnippetAnalysisRecord.ApplyRun;
import de.kortty.core.SnippetAnalysisRecord.Change;
import de.kortty.core.SnippetAnalysisRecord.DependencyFinding;
import de.kortty.core.SnippetAnalysisRecord.Finding;
import de.kortty.core.SnippetAnalysisRecord.Provenance;
import de.kortty.core.SnippetAnalysisRecord.RunOutcome;
import de.kortty.core.SnippetAnalysisRecord.RunStats;
import de.kortty.core.SnippetAnalysisRecord.SelectionState;
import de.kortty.core.SnippetAnalysisRecord.Source;
import de.kortty.core.SnippetAnalysisRecord.Usage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A realistic stored analysis of a small deployment script — findings in every category, a
 * dependency, a diagram, one accepted apply run and a follow-up analysis that verified it — for
 * the report tests and the sample-report generator.
 */
final class SnippetAnalysisReportFixtures {

    static final long ANALYSED_AT = 1_783_609_860_000L;   // 2026-07-09T15:11:00Z
    static final long APPLIED_AT = ANALYSED_AT + 42 * 60_000L;
    static final long VERIFIED_AT = APPLIED_AT + 5 * 60_000L;

    static final String SCRIPT = """
        #!/usr/bin/env bash
        # Deploys the release archive to the web root.

        RELEASE_URL=https://example.test/releases/latest.tar.gz
        TARGET_DIR=$1
        LOG=/tmp/deploy.log

        echo "Deploying to $TARGET_DIR" >> $LOG
        curl -fsSL https://example.test/install.sh | bash

        cd /tmp
        rm -rf $TARGET_DIR/*
        curl -o release.tar.gz $RELEASE_URL
        tar -xzf release.tar.gz -C $TARGET_DIR
        curl -o release.tar.gz $RELEASE_URL
        for f in $(ls $TARGET_DIR); do
          chmod 644 $TARGET_DIR/$f
        done
        jq '.version' $TARGET_DIR/manifest.json
        echo "done"
        """;

    static final String RESULT = """
        #!/usr/bin/env bash
        # Deploys the release archive to the web root.
        set -euo pipefail

        RELEASE_URL=https://example.test/releases/latest.tar.gz
        TARGET_DIR="${1:?usage: deploy.sh <target-dir>}"
        LOG=/tmp/deploy.log

        echo "Deploying to $TARGET_DIR" >> "$LOG"
        installer="$(mktemp)"
        trap 'rm -f "$installer"' EXIT
        curl -fsSL https://example.test/install.sh -o "$installer"
        sha256sum --check install.sh.sha256
        bash "$installer"

        cd /tmp
        rm -rf -- "${TARGET_DIR:?}"/*
        curl -o release.tar.gz "$RELEASE_URL"
        tar -xzf release.tar.gz -C "$TARGET_DIR"
        find "$TARGET_DIR" -type f -exec chmod 644 {} +
        jq '.version' "$TARGET_DIR/manifest.json"
        echo "done"
        """;

    static final String MERMAID = """
        flowchart TD
          start_1(["Start"])
          args_1["Read target directory"]
          install_1["Run remote installer"]
          clean_1["Clean target directory"]
          fetch_1["Download release"]
          ok_1{"Download ok?"}
          unpack_1["Unpack and fix permissions"]
          fail_1["Abort deployment"]
          stop_1(["Stop"])
          start_1 --> args_1
          args_1 --> install_1
          install_1 --> clean_1
          clean_1 --> fetch_1
          fetch_1 --> ok_1
          ok_1 -->|yes| unpack_1
          ok_1 -->|no| fail_1
          unpack_1 --> stop_1
          fail_1 --> stop_1
          class start_1,stop_1 setup
          class args_1,install_1,clean_1,fetch_1,ok_1 work
          class unpack_1 success
          class fail_1 failure
        """;

    private SnippetAnalysisReportFixtures() {
    }

    static List<Finding> improvements() {
        return List.of(
            new Finding("SEC-1", "security", "high", "Unquoted variable expansion in rm -rf",
                "`$TARGET_DIR` is expanded unquoted in `rm -rf $TARGET_DIR/*`. An empty or space-containing value "
                    + "turns this into `rm -rf /*` or deletes the wrong directories.\n\n"
                    + "The value comes straight from `$1` and is never validated.",
                "Quote every expansion and fail early when the argument is missing:\n\n"
                    + "```bash\nTARGET_DIR=\"${1:?usage: deploy.sh <target-dir>}\"\nrm -rf -- \"${TARGET_DIR:?}\"/*\n```",
                12),
            new Finding("SEC-2", "security", "critical", "Remote script piped straight into bash",
                "`curl … | bash` executes whatever the server returns, with no integrity check → a compromised "
                    + "server or a MITM proxy gets full shell access ✓ on every deployment.",
                "Download to a temporary file, verify its checksum (≥ SHA-256) and run it only then.", 9),
            new Finding("OPT-1", "optimization", "medium", "Release archive is downloaded twice",
                "Lines 13 and 15 fetch the same archive. The second download doubles the transfer and can "
                    + "replace a verified archive with a different one.",
                "Remove the second `curl` call.", 15),
            new Finding("DES-1", "dependency", "low", "Loop over `ls` output",
                "`for f in $(ls …)` breaks on file names with spaces and newlines.\n\n"
                    + "| Pattern | Problem |\n|---|---|\n| `$(ls dir)` | word splitting |\n| `dir/$f` | unquoted path |",
                "Use `find … -exec chmod 644 {} +` instead.", 16),
            new Finding("DES-2", "design", "info", "No usage message",
                "The script silently runs with an empty target when called without arguments.",
                "Print a usage line and exit with status 2.", null));
    }

    static List<DependencyFinding> dependencies() {
        return List.of(
            new DependencyFinding("D1", "curl", "program", "downloads the installer and the release archive",
                "Keep it, but always use --fail and write to a file."),
            new DependencyFinding("D2", "jq", "program", "reads the version from manifest.json",
                "Optional: grep the version when jq is not installed."));
    }

    static Provenance provenance() {
        return new Provenance("profile-lm", "LM Studio (qwen3-coder)", "qwen3-coder-30b",
            List.of("skill-bash"), List.of("Bash hardening", "POSIX portability"), "", new Usage(2100, 1850, 3950, 0));
    }

    /** The analysis before anything was applied; SEC-1, SEC-2, OPT-1 and D1 are ticked. */
    static SnippetAnalysisRecord analysed() {
        SnippetAiResponseSupport.ScriptAnalysis analysis = new SnippetAiResponseSupport.ScriptAnalysis(
            "The script deploys a release archive into a web root. It works for the happy path but trusts its "
                + "input and the network completely.\n\n"
                + "Two issues can destroy data or hand over the machine; fix those before the next deployment.",
            dependencies().stream().map(DependencyFinding::toDependency).toList(),
            improvements().stream().map(Finding::toImprovement).toList());
        SnippetAnalysisRecord record = SnippetAnalysisRecord.fromAnalysis("rec-1", "snippet-deploy", analysis,
            Source.of(SCRIPT, "bash", "en", "en", "deploy_release.sh"), provenance(),
            SnippetAnalysisRecord.Purpose.ANALYSIS, null, ANALYSED_AT);
        record = record.withSelection(new SelectionState(List.of("SEC-1", "SEC-2", "OPT-1"), List.of("D1"),
            List.of("STRICT_MODE", "ERROR_TRAP_CLEANUP"), false, List.of(), 0L, null, null, null, "en",
            ANALYSED_AT + 60_000L));
        return record.withDiagram(new SnippetAnalysisRecord.AnalysisDiagram("logical-structure", MERMAID, List.of(),
            "", false, SnippetDiagramSupport.contentHash(SCRIPT), "profile-lm", ANALYSED_AT + 30_000L));
    }

    /** One accepted run: SEC-1, SEC-2 and D1 confirmed, OPT-1 without a confirmation. */
    static ApplyRun acceptedRun() {
        ApplyRequestSnapshot request = new ApplyRequestSnapshot("bash", "en", List.of("SEC-1", "SEC-2", "OPT-1"),
            List.of("D1"), "", List.of("STRICT_MODE", "ERROR_TRAP_CLEANUP"), "", List.of(), "", null, null, null,
            null, null, "", "profile-lm", SnippetDiagramSupport.contentHash(SCRIPT), null);
        ApplyRun run = ApplyRun.started("run-1", APPLIED_AT, request, List.of(), provenance());
        List<Change> changes = List.of(
            new Change("SEC-1", "rm -rf -- \"${TARGET_DIR:?}\"/*",
                "Quoted the target and made the expansion fail when it is empty."),
            new Change("SEC-1", "TARGET_DIR=\"${1:?usage: deploy.sh <target-dir>}\"",
                "The argument is now required and validated at the top."),
            new Change("SEC-2", "sha256sum --check install.sh.sha256",
                "The installer is downloaded to a temp file and checked before it runs."),
            new Change("D1", "curl -fsSL https://example.test/install.sh -o \"$installer\"",
                "curl writes to a file with --fail instead of a pipe."));
        run = run.withResult(APPLIED_AT + 94_000L, false, RESULT,
            "Hardened the deployment: the target directory is validated and quoted, the remote installer is "
                + "verified before it runs, and strict mode plus a cleanup trap were added.",
            changes, List.of("STRICT_MODE", "ERROR_TRAP_CLEANUP"), List.of("SEC-1", "SEC-2", "D1"),
            new RunStats(94, new Usage(1204, 3388, 4592, 512), 1, 5, 5, null), provenance());
        return run.accepted(APPLIED_AT + 120_000L, List.of("SEC-1", "SEC-2", "OPT-1", "D1"), RESULT);
    }

    /** {@link #analysed()} with {@link #acceptedRun()}. */
    static SnippetAnalysisRecord applied() {
        return analysed().withRun(acceptedRun());
    }

    /** The follow-up analysis of the applied code, carrying the stored verification. */
    static SnippetAnalysisRecord verification() {
        SnippetAiResponseSupport.ScriptAnalysis analysis = new SnippetAiResponseSupport.ScriptAnalysis(
            "The deployment is now defensive.", List.of(), List.of(
                new SnippetAiResponseSupport.ScriptImprovement("DES-4", "dependency", "low",
                    "Loop over `ls` output", "Still iterates over ls.", "Use find.", 18),
                new SnippetAiResponseSupport.ScriptImprovement("OPT-9", "optimization", "low",
                    "Log file in /tmp is world-writable", "Another user can pre-create the log.",
                    "Use a private log directory.", 8)));
        SnippetAnalysisRecord record = SnippetAnalysisRecord.fromAnalysis("rec-2", "snippet-deploy", analysis,
            Source.of(RESULT, "bash", "en", "en", "deploy_release.sh"), provenance(),
            SnippetAnalysisRecord.Purpose.VERIFY, "rec-1", VERIFIED_AT);
        Map<String, String> persisting = new LinkedHashMap<>();
        persisting.put("DES-4", "DES-1");
        return record.withVerification(new SnippetAnalysisRecord.Verification("rec-1",
            List.of("SEC-1", "SEC-2", "OPT-1"), persisting, List.of("OPT-9")));
    }

    /** The history with the verification first (newest first). */
    static List<SnippetAnalysisRecord> history() {
        return List.of(verification(), applied());
    }

    static SnippetAnalysisReports.ReportContext context() {
        return new SnippetAnalysisReports.ReportContext("deploy_release.sh", "bash", SCRIPT);
    }

    static SnippetAnalysisReport preReport() {
        return SnippetAnalysisReports.preApply(analysed(), context());
    }

    static SnippetAnalysisReport postReport() {
        return SnippetAnalysisReports.postApply(applied(), null, history(),
            new SnippetAnalysisReports.ReportContext("deploy_release.sh", "bash", RESULT));
    }

    static ApplyRun run(RunOutcome outcome, boolean partial) {
        ApplyRun accepted = acceptedRun();
        return new ApplyRun(accepted.id(), accepted.startedAt(), accepted.finishedAt(), accepted.decidedAt(), outcome,
            partial, accepted.request(), accepted.items(), accepted.checkpoint(), accepted.resultSha256(),
            accepted.resultContent(), accepted.summary(), accepted.changes(), accepted.implementedRequirements(),
            accepted.completedWorkItemIds(), accepted.appliedFindingIds(), accepted.stats(), accepted.provenance(),
            accepted.failureKey(), accepted.acceptedContentSha256(), accepted.savedToSnippetAt());
    }
}
