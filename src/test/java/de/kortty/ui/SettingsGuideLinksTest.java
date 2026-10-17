package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertWithMessage;

/** Every settings page's manual link points at a guide page, and its anchor exists in English and German. */
class SettingsGuideLinksTest {

    private static final Path DOCS = Path.of("app-docs/site/docs");

    @Test
    void everySectionLinksToAnExistingPageAndAnchor() throws IOException {
        for (SettingsSection section : SettingsSection.values()) {
            String location = SettingsGuideLinks.location(section);
            int hash = location.indexOf('#');
            String page = hash >= 0 ? location.substring(0, hash) : location;
            String anchor = hash >= 0 ? location.substring(hash + 1) : null;
            assertWithMessage("%s location", section).that(page).endsWith(".html");
            String markdown = page.substring(0, page.length() - ".html".length()) + ".md";
            for (String lang : new String[] {"en", "de"}) {
                Path file = DOCS.resolve(lang).resolve(markdown);
                assertWithMessage("%s → %s", section, file).that(Files.isRegularFile(file)).isTrue();
                if (anchor != null) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    assertWithMessage("%s: explicit heading id #%s in %s", section, anchor, file)
                        .that(text).containsMatch("\\{ ?#" + anchor + " ?\\}");
                }
            }
        }
    }

    @Test
    void unknownSectionFallsBackToTheOverview() {
        assertWithMessage("null section").that(SettingsGuideLinks.location(null))
            .isEqualTo("reference/settings/index.html");
    }
}
