package de.kortty.core;

import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class WorkflowContextBuilderTest {

    private static TerminalAgentActivityExportService.Activity activity(
        TerminalAgentModels.AgentActivityType type, String title, String detail) {
        return new TerminalAgentActivityExportService.Activity(
            "id-" + title, type, TerminalAgentModels.AgentActivityStatus.COMPLETED,
            title, "summary of " + title, detail, null, 1L);
    }

    private static TerminalAgentActivityExportService.Run run(
        List<TerminalAgentActivityExportService.Activity> activities) {
        return new TerminalAgentActivityExportService.Run(
            "Run", "do work", "pid", "Profile", "model", "off",
            null, null, 0L, false, 0L, activities);
    }

    // ---------------------------------------------------------------- truncateMiddle

    @Test
    void truncateMiddleReturnsShortTextUnchanged() {
        assertThat(WorkflowContextBuilder.truncateMiddle("short", 1200, 800)).isEqualTo("short");
        assertThat(WorkflowContextBuilder.truncateMiddle(null, 10, 10)).isEmpty();
    }

    @Test
    void truncateMiddleKeepsHeadAndTailWithOmissionMarker() {
        String text = "A".repeat(50) + "MIDDLE" + "B".repeat(50);
        String out = WorkflowContextBuilder.truncateMiddle(text, 10, 10);
        assertThat(out).startsWith("AAAAAAAAAA");
        assertThat(out).endsWith("BBBBBBBBBB");
        assertThat(out).contains("characters omitted");
        assertThat(out).doesNotContain("MIDDLE");
    }

    // ---------------------------------------------------------------- selectReproductionActivities

    @Test
    void selectsActionsFirstThenErrorsAndDropsThinking() {
        var activities = List.of(
            activity(TerminalAgentModels.AgentActivityType.THINKING, "plan", "thinking detail"),
            activity(TerminalAgentModels.AgentActivityType.ACTION, "cmd1", "ran cmd1"),
            activity(TerminalAgentModels.AgentActivityType.MESSAGE, "msg", "a message"),
            activity(TerminalAgentModels.AgentActivityType.ERROR, "boom", "it failed"),
            activity(TerminalAgentModels.AgentActivityType.ACTION, "cmd2", "ran cmd2"));

        var selected = WorkflowContextBuilder.selectReproductionActivities(run(activities));

        assertThat(selected.stream().map(TerminalAgentActivityExportService.Activity::title).toList())
            .containsExactly("cmd1", "cmd2", "boom").inOrder();
    }

    @Test
    void selectionHandlesNullRun() {
        assertThat(WorkflowContextBuilder.selectReproductionActivities(null)).isEmpty();
    }

    // ---------------------------------------------------------------- build

    @Test
    void buildIncludesActionDetailAndReportsCounts() {
        var activities = List.of(
            activity(TerminalAgentModels.AgentActivityType.ACTION, "cmd1", "apt-get install nginx"),
            activity(TerminalAgentModels.AgentActivityType.THINKING, "plan", "should be dropped"));

        var ctx = WorkflowContextBuilder.build(run(activities), WorkflowContextBuilder.DEFAULT_MAX_CONTEXT_CHARS);

        assertThat(ctx.totalActions()).isEqualTo(1);   // only the ACTION counts
        assertThat(ctx.includedActions()).isEqualTo(1);
        assertThat(ctx.truncated()).isFalse();
        assertThat(ctx.markdown()).contains("apt-get install nginx");
        assertThat(ctx.markdown()).doesNotContain("should be dropped");
        assertThat(ctx.markdown()).contains("included 1 of 1");
    }

    @Test
    void buildAlwaysKeepsAtLeastOneEntryEvenWhenFirstActionExceedsBudget() {
        var activities = List.of(
            activity(TerminalAgentModels.AgentActivityType.ACTION, "cmd1", "echo hello"));

        // Tiny budget the full render cannot satisfy; a summary must still be emitted.
        var ctx = WorkflowContextBuilder.build(run(activities), 10);

        assertThat(ctx.totalActions()).isEqualTo(1);
        assertThat(ctx.truncated()).isTrue();
        assertThat(ctx.markdown()).isNotEmpty();
        assertThat(ctx.markdown()).contains("cmd1");
    }

    @Test
    void buildKeepsFullEntriesAsContiguousPrefix() {
        String big = "X".repeat(5000);
        var activities = List.of(
            activity(TerminalAgentModels.AgentActivityType.ACTION, "cmd1", "small-one"),
            activity(TerminalAgentModels.AgentActivityType.ACTION, "cmd2", big),
            activity(TerminalAgentModels.AgentActivityType.ACTION, "cmd3", "UNIQUE_CMD3_DETAIL"));

        // Fits cmd1 in full + summaries, but not cmd2 in full.
        var ctx = WorkflowContextBuilder.build(run(activities), 600);

        assertThat(ctx.includedActions()).isEqualTo(1);     // only cmd1 in full (contiguous prefix)
        assertThat(ctx.truncated()).isTrue();
        assertThat(ctx.markdown()).contains("small-one");   // cmd1 full detail present
        assertThat(ctx.markdown()).contains("cmd3");         // cmd3 still listed...
        assertThat(ctx.markdown()).doesNotContain("UNIQUE_CMD3_DETAIL"); // ...but only as a summary
    }

    @Test
    void buildMarksTruncatedWhenOverBudget() {
        String big = "X".repeat(5000);
        var activities = List.of(
            activity(TerminalAgentModels.AgentActivityType.ACTION, "cmd1", big),
            activity(TerminalAgentModels.AgentActivityType.ACTION, "cmd2", big),
            activity(TerminalAgentModels.AgentActivityType.ACTION, "cmd3", big));

        // Budget only fits roughly one full detail block.
        var ctx = WorkflowContextBuilder.build(run(activities), 3000);

        assertThat(ctx.totalActions()).isEqualTo(3);
        assertThat(ctx.includedActions()).isAtLeast(1);
        assertThat(ctx.includedActions()).isLessThan(3);
        assertThat(ctx.truncated()).isTrue();
    }
}
