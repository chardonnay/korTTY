package de.kortty.jobscheduler;

import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;

class JobDependencyMermaidBuilderTest {

    private static final JobDependencyMermaidBuilder.Labels LABELS = new JobDependencyMermaidBuilder.Labels(
        "condition", "chain", "never ran", "running", "disabled", status -> status.name().toLowerCase());

    @Test
    void buildsAVerticalFlowchartWithStatusClassesAndEdgeKinds() {
        ScheduledJob job1 = JobDependencyGraphTest.job("job1");
        ScheduledJob job2 = JobDependencyGraphTest.job("job2");
        ScheduledJob job3 = JobDependencyGraphTest.job("job3", job1, job2);
        job3.setDependencyMode(JobDependencyMode.CHAIN);
        ScheduledJob job4 = JobDependencyGraphTest.job("job4", job3);
        job4.setEnabled(false);
        JobDependencyGraph graph = new JobDependencyGraph(List.of(job1, job2, job3, job4));
        Map<String, JobRunStatus> status = Map.of(job1.getId(), JobRunStatus.SUCCESS, job2.getId(), JobRunStatus.FAILED);

        String source = JobDependencyMermaidBuilder.build(graph, JobDependencyMermaidBuilder.Direction.TB,
            JobDependencyMermaidBuilder.Scope.ALL, null,
            id -> Optional.ofNullable(status.get(id)), job3.getId()::equals, LABELS);

        assertThat(source).startsWith("flowchart TB\n");
        assertThat(source).contains("j0[\"job1<br/>success\"]");
        assertThat(source).contains("j2[\"job3<br/>running\"]");
        assertThat(source).contains("j3[\"job4<br/>never ran · disabled\"]");
        assertThat(source).contains("j0 -->|\"chain\"| j2");
        assertThat(source).contains("j1 -->|\"chain\"| j2");
        assertThat(source).contains("j2 -.->|\"condition\"| j3");
        assertThat(source).contains("class j0 jobOk");
        assertThat(source).contains("class j1 jobFailed");
        assertThat(source).contains("class j2 jobRunning");
        assertThat(source).contains("class j3 jobNever");
        assertThat(source).contains("class j3 jobDisabled");
    }

    @Test
    void horizontalAndScopesSelectTheRightJobs() {
        ScheduledJob a = JobDependencyGraphTest.job("a");
        ScheduledJob b = JobDependencyGraphTest.job("b", a);
        ScheduledJob c = JobDependencyGraphTest.job("c", b);
        ScheduledJob lonely = JobDependencyGraphTest.job("lonely");
        ScheduledJob other = JobDependencyGraphTest.job("other");
        ScheduledJob otherChild = JobDependencyGraphTest.job("otherChild", other);
        JobDependencyGraph graph = new JobDependencyGraph(List.of(a, b, c, lonely, other, otherChild));

        String all = JobDependencyMermaidBuilder.build(graph, JobDependencyMermaidBuilder.Direction.LR,
            JobDependencyMermaidBuilder.Scope.ALL, null, id -> Optional.empty(), id -> false, LABELS);
        assertThat(all).startsWith("flowchart LR\n");
        assertThat(all).contains("lonely");

        assertThat(JobDependencyMermaidBuilder.selectJobs(graph, JobDependencyMermaidBuilder.Scope.CONNECTED, null))
            .containsExactly(a.getId(), b.getId(), c.getId(), other.getId(), otherChild.getId()).inOrder();
        assertThat(JobDependencyMermaidBuilder.selectJobs(graph, JobDependencyMermaidBuilder.Scope.NEIGHBORHOOD, b.getId()))
            .containsExactly(a.getId(), b.getId(), c.getId()).inOrder();
        String focused = JobDependencyMermaidBuilder.build(graph, JobDependencyMermaidBuilder.Direction.TB,
            JobDependencyMermaidBuilder.Scope.NEIGHBORHOOD, b.getId(), id -> Optional.empty(), id -> false, LABELS);
        assertThat(focused).contains("class j1 jobFocus");
        assertThat(JobDependencyMermaidBuilder.build(new JobDependencyGraph(List.of()), JobDependencyMermaidBuilder.Direction.TB,
            JobDependencyMermaidBuilder.Scope.ALL, null, id -> Optional.empty(), id -> false, LABELS)).isNull();
    }

    @Test
    void hostileJobNamesCannotInjectMermaidOrTripTheRendererChecks() {
        ScheduledJob evil = JobDependencyGraphTest.job("x\"] --> y\nclick y call alert() https://evil.example/a.png %%{init}");
        ScheduledJob child = JobDependencyGraphTest.job("fa:fa-bomb @{ img: 'a' } src=url(a)", evil);
        JobDependencyGraph graph = new JobDependencyGraph(List.of(evil, child));

        String source = JobDependencyMermaidBuilder.build(graph, JobDependencyMermaidBuilder.Direction.TB,
            JobDependencyMermaidBuilder.Scope.ALL, null, id -> Optional.empty(), id -> false, LABELS);

        assertThat(source.lines().filter(line -> line.contains("-->") || line.contains("-.->")).count()).isEqualTo(1);
        assertThat(source).doesNotContain("\"]  -->");
        assertThat(source).doesNotContain("https://");
        assertThat(JobDependencyMermaidBuilder.escape("a\"b")).isEqualTo("a#34;b");
        assertThat(JobDependencyMermaidBuilder.escape("Click me")).isEqualTo("#67;lick me");
    }
}
