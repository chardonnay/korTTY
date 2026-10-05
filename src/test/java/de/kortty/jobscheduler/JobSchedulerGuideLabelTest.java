package de.kortty.jobscheduler;

import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.testng.annotations.Test;

/**
 * Pins that the guide names the JobScheduler AI job's auto-approve option by its real UI label
 * ({@code jobscheduler.dialog.ai.autoApprove}) in English and in the generated German pages, and
 * that the invented labels the guide used before do not come back.
 */
class JobSchedulerGuideLabelTest {

    private static final String KEY = "jobscheduler.dialog.ai.autoApprove";
    private static final List<String> PAGES = List.of(
            "features/jobscheduler.md", "features/ai-swarm.md", "reference/enterprise-policy.md");

    @Test
    void englishGuideUsesTheUiLabel() throws IOException {
        assertPagesUseLabel("en", label("messages.properties"),
                List.of("Auto-approve AI commands", "**Auto-approve**", "**auto-approve on**"));
    }

    @Test
    void germanGuideUsesTheUiLabel() throws IOException {
        assertPagesUseLabel("de", label("messages_de.properties"),
                List.of("Automatisch genehmigende KI-Befehle", "**Automatisch genehmigen**",
                        "Auto-Genehmigung ein", "automatische Genehmigung von KI-Befehlen"));
    }

    private static void assertPagesUseLabel(String lang, String label, List<String> staleLabels)
            throws IOException {
        for (String page : PAGES) {
            Path path = Path.of("app-docs/site/docs", lang, page);
            String text = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertWithMessage("%s names the option by its UI label", path)
                    .that(text).contains("**" + label + "**");
            for (String stale : staleLabels) {
                assertWithMessage("%s still uses the stale label", path).that(text).doesNotContain(stale);
            }
        }
    }

    private static String label(String bundle) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(
                Path.of("src/main/resources/i18n", bundle), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String value = properties.getProperty(KEY);
        assertWithMessage("%s defines %s", bundle, KEY).that(value).isNotEmpty();
        return value;
    }
}
