package de.kortty.telemetry;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.core.sftp.transfer.TransferDirection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * {@code sftp_transfer_batch} (D20): only fixed ids, a bucketed file count, a boolean and a small
 * number; never a path, a name or an exact size.
 */
class SftpTransferTelemetryTest {

    @Test
    void theEventNameIsSnakeCase() {
        assertThat(TelemetryEvents.SFTP_TRANSFER_BATCH).isEqualTo("sftp_transfer_batch");
    }

    @Test
    void thePropsAreOnlyIdsABucketABooleanAndASmallNumber() {
        Map<String, Object> props = SftpTransferTelemetry.props(TransferDirection.DOWNLOAD, 37,
            SftpTransferTelemetry.Outcome.PARTIAL, true, 3);

        assertThat(props).containsExactly("direction", "download", "files", 10, "outcome", "partial",
            "resumed", true, "channels", 3).inOrder();
        for (Object value : props.values()) {
            assertWithMessage("props are enum ids, numbers or booleans only")
                .that(value instanceof String || value instanceof Integer || value instanceof Boolean).isTrue();
        }
        assertThat(SftpTransferTelemetry.props(TransferDirection.UPLOAD, 1, null, false, 99))
            .containsEntry("channels", 8);
    }

    @Test
    void fileCountsAreRoundedDownToCoarseBuckets() {
        assertThat(SftpTransferTelemetry.fileBucket(0)).isEqualTo(0);
        assertThat(SftpTransferTelemetry.fileBucket(1)).isEqualTo(1);
        assertThat(SftpTransferTelemetry.fileBucket(4)).isEqualTo(2);
        assertThat(SftpTransferTelemetry.fileBucket(99)).isEqualTo(50);
        assertThat(SftpTransferTelemetry.fileBucket(123_456)).isEqualTo(1000);
        assertThat(SftpTransferTelemetry.fileBucket(-5)).isEqualTo(0);
    }

    @Test
    void theOutcomeSummarizesTheFiles() {
        assertThat(SftpTransferTelemetry.outcome(3, 0, 0, false)).isEqualTo(SftpTransferTelemetry.Outcome.DONE);
        assertThat(SftpTransferTelemetry.outcome(2, 1, 0, false)).isEqualTo(SftpTransferTelemetry.Outcome.PARTIAL);
        assertThat(SftpTransferTelemetry.outcome(0, 2, 0, false)).isEqualTo(SftpTransferTelemetry.Outcome.FAILED);
        assertThat(SftpTransferTelemetry.outcome(0, 0, 2, false)).isEqualTo(SftpTransferTelemetry.Outcome.CANCELLED);
        assertThat(SftpTransferTelemetry.outcome(0, 0, 0, true)).isEqualTo(SftpTransferTelemetry.Outcome.CANCELLED);
        assertThat(SftpTransferTelemetry.outcome(1, 0, 1, false)).isEqualTo(SftpTransferTelemetry.Outcome.PARTIAL);
    }

    @Test
    void theGuideListsTheEvent() throws IOException {
        String guide = Files.readString(Path.of("app-docs/site/docs/en/about/anonymous-data.md"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(guide).contains("`sftp_transfer_batch`");
    }
}
