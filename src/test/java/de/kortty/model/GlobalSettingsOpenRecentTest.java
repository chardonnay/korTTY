package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * What <i>File → Open Recent</i> keeps in {@code global-settings.xml}: the remembered project paths,
 * newest first, and when Clear List was chosen. Both survive the round trip, and a settings file from
 * before they existed starts with an empty list that was never cleared.
 */
class GlobalSettingsOpenRecentTest {

    @Test
    void aSettingsFileFromBeforeOpenRecentExistedHasAnEmptyListThatWasNeverCleared() throws Exception {
        GlobalSettings settings = unmarshal("<globalSettings><showMenuBar>true</showMenuBar></globalSettings>");

        assertThat(settings.getRecentProjectPaths()).isEmpty();
        assertThat(settings.getOpenRecentClearedAt()).isEqualTo(0L);
        assertThat(new GlobalSettings().getRecentProjectPaths()).isEmpty();
    }

    @Test
    void theRememberedProjectsAndTheClearTimeSurviveTheRoundTripInOrder() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setRecentProjectPaths(List.of("/work/b.kortty", "/work/a.kortty"));
        settings.setOpenRecentClearedAt(1_700_000_000_000L);

        String xml = marshal(settings);
        GlobalSettings restored = unmarshal(xml);

        assertThat(xml).contains("<recentProjectPaths><path>/work/b.kortty</path><path>/work/a.kortty</path>"
            + "</recentProjectPaths>");
        assertThat(xml).contains("<openRecentClearedAt>1700000000000</openRecentClearedAt>");
        assertThat(restored.getRecentProjectPaths()).containsExactly("/work/b.kortty", "/work/a.kortty").inOrder();
        assertThat(restored.getOpenRecentClearedAt()).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void theListIsACopyAndHoldsNoNulls() {
        List<String> given = new ArrayList<>(Arrays.asList("/a.kortty", null));
        GlobalSettings settings = new GlobalSettings();
        settings.setRecentProjectPaths(given);
        given.add("/later.kortty");

        assertWithMessage("a list changed after it was set does not change the settings")
            .that(settings.getRecentProjectPaths()).containsExactly("/a.kortty");
        settings.setRecentProjectPaths(null);
        assertThat(settings.getRecentProjectPaths()).isEmpty();
        settings.setOpenRecentClearedAt(-1L);
        assertThat(settings.getOpenRecentClearedAt()).isEqualTo(0L);
    }

    private static String marshal(GlobalSettings settings) throws Exception {
        StringWriter writer = new StringWriter();
        JAXBContext.newInstance(GlobalSettings.class).createMarshaller().marshal(settings, writer);
        return writer.toString();
    }

    private static GlobalSettings unmarshal(String xml) throws Exception {
        return (GlobalSettings) JAXBContext.newInstance(GlobalSettings.class)
                .createUnmarshaller().unmarshal(new StringReader(xml));
    }
}
