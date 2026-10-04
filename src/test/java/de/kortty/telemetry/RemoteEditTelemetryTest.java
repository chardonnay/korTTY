package de.kortty.telemetry;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * {@code sftp_remote_edit} (D20): the edit mode, how it ended and a bucketed upload count; never a
 * path, a name, an editor or a size.
 */
class RemoteEditTelemetryTest {

    @Test
    void theEventNameIsSnakeCase() {
        assertThat(TelemetryEvents.SFTP_REMOTE_EDIT).isEqualTo("sftp_remote_edit");
    }

    @Test
    void thePropsAreOnlyIdsAndABucket() {
        Map<String, Object> props = RemoteEditTelemetry.props(RemoteEditTelemetry.Mode.EXTERNAL,
            RemoteEditTelemetry.Outcome.DISCONNECTED, 7);

        assertThat(props).containsExactly("mode", "external", "outcome", "disconnected", "uploads", 5).inOrder();
        for (Object value : props.values()) {
            assertWithMessage("props are enum ids or numbers only")
                .that(value instanceof String || value instanceof Integer).isTrue();
        }
        assertThat(RemoteEditTelemetry.props(null, null, 0))
            .containsExactly("mode", "external", "outcome", "stopped", "uploads", 0).inOrder();
        assertThat(RemoteEditTelemetry.props(RemoteEditTelemetry.Mode.SUDO, RemoteEditTelemetry.Outcome.CONFLICT, 1))
            .containsEntry("mode", "sudo");
    }

    @Test
    void uploadCountsAreRoundedDownToCoarseBuckets() {
        assertThat(RemoteEditTelemetry.uploadBucket(-1)).isEqualTo(0);
        assertThat(RemoteEditTelemetry.uploadBucket(0)).isEqualTo(0);
        assertThat(RemoteEditTelemetry.uploadBucket(1)).isEqualTo(1);
        assertThat(RemoteEditTelemetry.uploadBucket(3)).isEqualTo(2);
        assertThat(RemoteEditTelemetry.uploadBucket(12)).isEqualTo(10);
        assertThat(RemoteEditTelemetry.uploadBucket(9_999)).isEqualTo(50);
    }

    @Test
    void theGuideListsTheEvent() throws IOException {
        String guide = Files.readString(Path.of("app-docs/site/docs/en/about/anonymous-data.md"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(guide).contains("`sftp_remote_edit`");
    }
}
