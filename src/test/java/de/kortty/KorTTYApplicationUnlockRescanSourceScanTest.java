package de.kortty;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/** The rescan after a vault unlock: the skills are copied on the JavaFX thread and the files are read in the background. */
class KorTTYApplicationUnlockRescanSourceScanTest {

    @Test
    void theRescanAfterAnUnlockCopiesTheSkillsFirstAndScansInTheBackground() throws IOException {
        String source = Files.readString(Path.of("src/main/java/de/kortty/KorTTYApplication.java"));
        int start = source.indexOf("private void rescanForSecretsAfterUnlock()");
        assertThat(start).isAtLeast(0);
        int end = source.indexOf("\n    }\n", start);
        assertThat(end).isGreaterThan(start);
        String body = source.substring(start, end);

        int copy = body.indexOf("globalSettingsManager.getSettings().getAiSkills()");
        int background = body.indexOf("CompletableFuture.runAsync(");
        int scan = body.indexOf("AiSecretStartupScan.run(");
        assertThat(copy).isAtLeast(0);
        assertThat(background).isGreaterThan(copy);
        assertThat(scan).isGreaterThan(background);
    }
}
