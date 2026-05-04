package de.kortty.jobscheduler;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class JobSchedulerArchiveCommandBuilderTest {

    private final JobSchedulerArchiveCommandBuilder builder = new JobSchedulerArchiveCommandBuilder();

    @Test
    void buildsTarBz2CommandWithExcludes() {
        JobAction action = new JobAction();
        action.setArchiveFormat(JobArchiveFormat.TAR_BZ2);
        action.setArchivePath("/tmp/app.tar.bz2");
        action.setArchiveCompressionLevel(6);
        action.setArchiveSourcePaths(List.of("/opt/app", "/etc/app.conf"));
        action.setArchiveExcludePatterns(List.of("*.log"));

        assertThat(builder.build(action, null))
            .isEqualTo("BZIP2=-6 tar -cjf '/tmp/app.tar.bz2' --exclude='*.log' '/opt/app' '/etc/app.conf'");
    }

    @Test
    void wrapsSudoArchiveNonInteractiveWhenPasswordIsMissing() {
        JobAction action = new JobAction();
        action.setUseSudo(true);
        action.setArchiveFormat(JobArchiveFormat.TAR);
        action.setArchivePath("/root/app.tar");
        action.setArchiveSourcePaths(List.of("/root/app"));

        assertThat(builder.build(action, null))
            .startsWith("sudo -n sh -lc ");
    }

    @Test
    void buildsPasswordZipCommandWithoutEmbeddingPassword() {
        JobAction action = new JobAction();
        action.setArchiveFormat(JobArchiveFormat.ZIP_PASSWORD);
        action.setArchivePath("/tmp/app.zip");
        action.setArchiveCompressionLevel(6);
        action.setArchiveSourcePaths(List.of("/opt/app"));

        String command = builder.build(action, null);

        assertThat(command).isEqualTo("zip -er -6 '/tmp/app.zip' '/opt/app'");
        assertThat(command).doesNotContain("secret");
    }
}
