package de.kortty.core;

import de.kortty.model.EnvironmentDefinition;
import de.kortty.model.StoredCredential;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The tab colors of credential environments in {@code environments.xml}: no environment has one
 * until the user picks it (every credential starts out in Production), built-in and custom
 * environments keep theirs across a save and load, files written before colors existed still load,
 * and a color never outlives its environment or a missing file.
 */
class EnvironmentManagerColorTest {

    private Path dir;

    @BeforeMethod
    void createDir() throws IOException {
        dir = Files.createTempDirectory("kortty-env-colors");
    }

    @AfterMethod(alwaysRun = true)
    void deleteDir() throws IOException {
        if (dir == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    void noEnvironmentHasAColorByDefault() {
        EnvironmentManager manager = new EnvironmentManager(dir);
        manager.load();

        for (EnvironmentDefinition environment : manager.getEnvironments()) {
            assertWithMessage("credentials default to %s; a default color would mark most tabs",
                    StoredCredential.Environment.PRODUCTION)
                    .that(manager.getColor(environment.getId())).isNull();
        }
        assertThat(manager.getColors()).isEmpty();
    }

    @Test
    void builtInAndCustomColorsSurviveASaveAndLoad() throws Exception {
        EnvironmentManager manager = new EnvironmentManager(dir);
        manager.load();
        EnvironmentDefinition lab = manager.addCustomEnvironment("Lab");
        assertThat(manager.setColor("PRODUCTION", "#d32f2f")).isTrue();
        assertThat(manager.setColor(lab.getId(), "#3c3")).isTrue();
        manager.save();

        EnvironmentManager reloaded = new EnvironmentManager(dir);
        reloaded.load();

        assertThat(reloaded.getColor("PRODUCTION")).isEqualTo("#D32F2F");
        assertThat(reloaded.getColor(lab.getId())).isEqualTo("#33CC33");
        assertThat(reloaded.getColor("DEVELOPMENT")).isNull();
        assertThat(reloaded.getColors()).containsExactly("PRODUCTION", "#D32F2F", lab.getId(), "#33CC33");
    }

    @Test
    void aFileWrittenBeforeColorsExistedStillLoads() throws Exception {
        Files.writeString(dir.resolve(EnvironmentManager.ENVIRONMENTS_FILE), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <environments>
                    <environment id="custom-1a2b3c4d" displayName="Lab"/>
                </environments>
                """, StandardCharsets.UTF_8);

        EnvironmentManager manager = new EnvironmentManager(dir);
        manager.load();

        assertThat(manager.getDisplayName("custom-1a2b3c4d")).isEqualTo("Lab");
        assertThat(manager.getColors()).isEmpty();
        assertThat(manager.isSaveBlocked()).isFalse();
    }

    @Test
    void savingWithoutColorsKeepsTheOldFileForm() throws Exception {
        EnvironmentManager manager = new EnvironmentManager(dir);
        manager.load();
        manager.addCustomEnvironment("Lab");
        manager.save();

        assertWithMessage("an older korTTY reads the file the same way as before")
                .that(Files.readString(dir.resolve(EnvironmentManager.ENVIRONMENTS_FILE), StandardCharsets.UTF_8))
                .doesNotContain("colors");
    }

    @Test
    void colorsInTheFileThatAreInvalidOrNameNoEnvironmentAreDropped() throws Exception {
        Files.writeString(dir.resolve(EnvironmentManager.ENVIRONMENTS_FILE), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <environments>
                    <environment id="custom-1a2b3c4d" displayName="Lab"/>
                    <colors>
                        <color id="STAGING" color="#f57c00"/>
                        <color id="custom-1a2b3c4d" color="red; -fx-background-color: #8B0000"/>
                        <color id="custom-gone" color="#388E3C"/>
                        <color id="TEST"/>
                    </colors>
                </environments>
                """, StandardCharsets.UTF_8);

        EnvironmentManager manager = new EnvironmentManager(dir);
        manager.load();

        assertThat(manager.getColors()).containsExactly("STAGING", "#F57C00");
    }

    @Test
    void aMissingFileClearsTheColors() throws Exception {
        EnvironmentManager manager = new EnvironmentManager(dir);
        manager.load();
        manager.setColor("PRODUCTION", "#D32F2F");
        manager.save();
        manager.load();
        assertThat(manager.getColor("PRODUCTION")).isEqualTo("#D32F2F");

        Files.delete(dir.resolve(EnvironmentManager.ENVIRONMENTS_FILE));
        manager.load();

        assertThat(manager.getColor("PRODUCTION")).isNull();
        assertThat(manager.getColors()).isEmpty();
    }

    @Test
    void removingACustomEnvironmentDropsItsColor() throws Exception {
        EnvironmentManager manager = new EnvironmentManager(dir);
        manager.load();
        EnvironmentDefinition lab = manager.addCustomEnvironment("Lab");
        manager.setColor(lab.getId(), "#7B1FA2");

        assertThat(manager.removeCustomEnvironment(lab.getId())).isTrue();

        assertThat(manager.getColor(lab.getId())).isNull();
        manager.save();
        assertThat(Files.readString(dir.resolve(EnvironmentManager.ENVIRONMENTS_FILE), StandardCharsets.UTF_8))
                .doesNotContain(lab.getId());
    }

    @Test
    void aColorNeedsAnExistingEnvironmentAndAHexValue() {
        EnvironmentManager manager = new EnvironmentManager(dir);
        manager.load();

        assertThat(manager.setColor("custom-unknown", "#D32F2F")).isFalse();
        assertThat(manager.setColor(null, "#D32F2F")).isFalse();
        assertThat(manager.setColor(" ", "#D32F2F")).isFalse();
        assertThat(manager.getColors()).isEmpty();

        manager.setColor("TEST", "#FBC02D");
        assertWithMessage("a value that is not a hex color removes the color")
                .that(manager.setColor("TEST", "yellow")).isFalse();
        assertThat(manager.getColor("TEST")).isNull();
        assertThat(manager.getColor(null)).isNull();
    }

    @Test
    void replaceColorsAppliesTheDialogsEditsAndReturnsThePreviousColors() {
        EnvironmentManager manager = new EnvironmentManager(dir);
        manager.load();
        manager.setColor("PRODUCTION", "#D32F2F");
        manager.setColor("TEST", "#FBC02D");

        Map<String, String> edits = new HashMap<>();
        edits.put("PRODUCTION", "#b71c1c");
        edits.put("DEVELOPMENT", "#388E3C");
        edits.put("custom-deleted-meanwhile", "#1976D2");
        Map<String, String> previous = manager.replaceColors(edits);

        assertThat(previous).containsExactly("PRODUCTION", "#D32F2F", "TEST", "#FBC02D");
        assertWithMessage("TEST has no color in the edits, so it loses its color")
                .that(manager.getColors()).containsExactly("PRODUCTION", "#B71C1C", "DEVELOPMENT", "#388E3C");

        manager.replaceColors(previous);
        assertWithMessage("a failed save puts the colors back")
                .that(manager.getColors()).containsExactly("PRODUCTION", "#D32F2F", "TEST", "#FBC02D");
    }

    @Test
    void getColorsIsACopy() {
        EnvironmentManager manager = new EnvironmentManager(dir);
        manager.load();
        manager.setColor("STAGING", "#F57C00");

        manager.getColors().clear();

        assertThat(manager.getColor("STAGING")).isEqualTo("#F57C00");
    }
}
