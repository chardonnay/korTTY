package de.kortty.ui.sftp;

import de.kortty.core.remote.extract.ArchiveExtractException;
import de.kortty.core.remote.extract.RemoteArchiveExtractor;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/** Every phase and refusal of "Extract Here..." has a text in all eight bundles. */
public class RemoteExtractMessagesTest {

    private static final List<String> BUNDLES = List.of("", "_de", "_it", "_es", "_pt", "_fr", "_hr", "_nl");

    @Test
    public void everyKeyExistsInEveryBundle() throws IOException {
        List<String> keys = new ArrayList<>(List.of("sftp.contextMenu.extractHere", "sftp.extract.title",
            "sftp.extract.header", "sftp.extract.content", "sftp.extract.running", "sftp.extract.cancelling",
            "sftp.extract.done", "sftp.extract.cancelled", "sftp.extract.failedTitle"));
        for (RemoteArchiveExtractor.Phase phase : RemoteArchiveExtractor.Phase.values()) {
            keys.add(RemoteExtractMessages.phaseKey(phase));
        }
        for (ArchiveExtractException.Reason reason : ArchiveExtractException.Reason.values()) {
            keys.add(RemoteExtractMessages.errorKey(reason));
        }
        for (String bundle : BUNDLES) {
            Properties properties = new Properties();
            try (InputStream in = getClass().getResourceAsStream("/i18n/messages" + bundle + ".properties")) {
                assertThat(in).isNotNull();
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (String key : keys) {
                assertWithMessage(bundle + " " + key).that(properties.getProperty(key)).isNotEmpty();
            }
        }
    }
}
