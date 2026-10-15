package de.kortty.core;

import de.kortty.jobscheduler.JobDependencyGraph;
import de.kortty.jobscheduler.JobDependencyMermaidBuilder;
import de.kortty.jobscheduler.JobDependencyMode;
import de.kortty.jobscheduler.ScheduledJob;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;

/** The JobScheduler dependency diagram must always pass the renderer's source checks. */
class JobDependencyMermaidValidationTest {

    private static final JobDependencyMermaidBuilder.Labels LABELS = new JobDependencyMermaidBuilder.Labels(
        "condition", "chain", "never ran", "running", "disabled", status -> status.name());

    @Test
    void ordinaryAndHostileJobNamesPassTheRendererChecks() {
        ScheduledJob plain = job("Backup DB");
        ScheduledJob evil = job("x\"] --> y\nclick y call alert() https://evil.example/a.png %%{init}");
        ScheduledJob child = job("fa:fa-bomb @{ img: 'a' } src=url(a); click z", plain, evil);
        child.setDependencyMode(JobDependencyMode.CHAIN);
        JobDependencyGraph graph = new JobDependencyGraph(List.of(plain, evil, child));

        for (JobDependencyMermaidBuilder.Direction direction : JobDependencyMermaidBuilder.Direction.values()) {
            String source = JobDependencyMermaidBuilder.build(graph, direction,
                JobDependencyMermaidBuilder.Scope.ALL, null, id -> Optional.empty(), id -> false, LABELS);
            assertThat(MermaidRenderService.validateSource(source)).isNull();
        }
    }

    private static ScheduledJob job(String name, ScheduledJob... predecessors) {
        ScheduledJob job = new ScheduledJob();
        job.setName(name);
        job.setDependsOnJobIds(java.util.Arrays.stream(predecessors).map(ScheduledJob::getId).toList());
        return job;
    }
}
