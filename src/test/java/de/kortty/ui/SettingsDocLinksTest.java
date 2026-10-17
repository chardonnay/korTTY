package de.kortty.ui;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The "Open in manual" links next to section headers in Global Settings point at guide pages whose
 * explicit heading ids exist in English and German, and the settings search leaves those links out.
 */
class SettingsDocLinksTest {

    private static final Path DOCS = Path.of("app-docs/site/docs");

    /** Every guide location a {@code sectionHeader}/{@code docLink} in {@link SettingsDialog} points at. */
    @DataProvider
    Object[][] locations() {
        return new Object[][] {
            {"reference/settings/logging.html#application-log"},
            {"features/terminal.html#terminal-logging"},
            {"reference/settings/resources.html"},
            {"reference/settings/security.html#master-password"},
            {"reference/settings/security.html#temporary-ssh-keys"},
            {"features/session-isolation.html"},
            {de.kortty.telemetry.TelemetryEvents.GUIDE_LOCATION},
        };
    }

    @Test(dataProvider = "locations")
    void locationHasAPageAndAnchorInBothLanguages(String location) throws IOException {
        int hash = location.indexOf('#');
        String page = hash >= 0 ? location.substring(0, hash) : location;
        String anchor = hash >= 0 ? location.substring(hash + 1) : null;
        assertWithMessage("%s", location).that(page).endsWith(".html");
        String markdown = page.substring(0, page.length() - ".html".length()) + ".md";
        for (String lang : new String[] {"en", "de"}) {
            Path file = DOCS.resolve(lang).resolve(markdown);
            assertWithMessage("%s → %s", location, file).that(Files.isRegularFile(file)).isTrue();
            if (anchor != null) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                assertWithMessage("explicit heading id #%s in %s", anchor, file)
                    .that(text).containsMatch("\\{ ?#" + anchor + " ?\\}");
            }
        }
    }

    @Test
    void docLinksAreNotSearchResults() {
        assertWithMessage("doc link")
            .that(SettingsSearchBar.isIndexable(List.of("hyperlink", SettingsSearchBar.DOC_LINK_STYLE))).isFalse();
        assertWithMessage("plain hyperlink").that(SettingsSearchBar.isIndexable(List.of("hyperlink"))).isTrue();
        assertWithMessage("label").that(SettingsSearchBar.isIndexable(List.of())).isTrue();
    }
}
