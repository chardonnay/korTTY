package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.ShellIntegrationInjection.Support;
import de.kortty.ui.LocalShellIntegrationOption.State;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.testng.annotations.Test;

/**
 * The connection editor's <b>Add shell integration automatically</b>: tickable only for a local shell
 * connection that runs bash, zsh or fish and is not shared through Teamwork, also ahead while shell
 * integration is switched off; every other case says why under the box, in every language.
 */
class LocalShellIntegrationOptionTest {

    private static final List<String> BUNDLES = List.of("messages.properties", "messages_de.properties",
        "messages_it.properties", "messages_es.properties", "messages_pt.properties", "messages_fr.properties",
        "messages_hr.properties", "messages_nl.properties");

    @Test
    void decidesInTheOrderOfTheReasons() {
        assertThat(LocalShellIntegrationOption.state(false, true, Support.SUPPORTED, true)).isEqualTo(State.NOT_LOCAL);
        assertThat(LocalShellIntegrationOption.state(true, true, Support.SUPPORTED, true)).isEqualTo(State.TEAMWORK);
        assertThat(LocalShellIntegrationOption.state(true, false, Support.OTHER_SHELL, true)).isEqualTo(State.OTHER_SHELL);
        assertThat(LocalShellIntegrationOption.state(true, false, null, true)).isEqualTo(State.OTHER_SHELL);
        assertThat(LocalShellIntegrationOption.state(true, false, Support.OTHER_ARGUMENTS, false))
            .isEqualTo(State.OTHER_ARGUMENTS);
        assertThat(LocalShellIntegrationOption.state(true, false, Support.SUPPORTED, false)).isEqualTo(State.SWITCHED_OFF);
        assertThat(LocalShellIntegrationOption.state(true, false, Support.SUPPORTED, true)).isEqualTo(State.AVAILABLE);
    }

    @Test
    void canBeTickedOnlyWhereItCanTakeEffect() {
        for (State state : State.values()) {
            boolean expected = state == State.AVAILABLE || state == State.SWITCHED_OFF;
            assertWithMessage("%s", state).that(state.editable()).isEqualTo(expected);
        }
    }

    @Test
    void everyBundleExplainsEveryState() throws IOException {
        for (String bundle : BUNDLES) {
            Properties properties = load(bundle);
            for (State state : State.values()) {
                assertWithMessage("%s: %s", bundle, state.hintKey())
                    .that(properties.getProperty(state.hintKey(), "")).isNotEmpty();
            }
            for (String key : List.of("connEdit.shellIntegration", "connEdit.shellIntegration.autoInject",
                    "connEdit.shellIntegration.autoInject.tooltip", "terminal.shellIntegration.setup.local")) {
                assertWithMessage("%s: %s", bundle, key).that(properties.getProperty(key, "")).isNotEmpty();
            }
            // The setup window names the box by the label the editor shows.
            assertWithMessage("%s: the setup window names the box", bundle)
                .that(properties.getProperty("terminal.shellIntegration.setup.local"))
                .contains(properties.getProperty("connEdit.shellIntegration.autoInject"));
        }
    }

    @Test
    void theEditorAndTheSetupWindowUseTheKeys() throws IOException {
        String editor = Files.readString(Path.of("src/main/java/de/kortty/ui/ConnectionEditDialog.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(editor).contains("LocalShellIntegrationOption.state(");
        assertThat(editor).contains("connection.setShellIntegrationAutoInject(shellIntegrationAutoInjectCheck.isSelected());");
        assertThat(editor).contains("I18n.get(\"connEdit.shellIntegration.autoInject\")");
        String setup = Files.readString(Path.of("src/main/java/de/kortty/ui/ShellIntegrationSetupDialog.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(setup).contains("hint(I18n.get(\"terminal.shellIntegration.setup.local\"), textWidth)");
    }

    private static Properties load(String bundle) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("src/main/resources/i18n", bundle), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
