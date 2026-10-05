package de.kortty.model;

import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.util.Objects;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Guards the hand-written {@link ConnectionSettings} copy constructor. Every terminal resolves its
 * settings through it (ConnectionSettingsSupport, ThemeManager, the Settings dialog), so a field it
 * forgets is silently reset to its default for every open terminal and every save of the global
 * defaults. The ServerConnection copy guards only reflect over ServerConnection's own fields, so
 * a missed ConnectionSettings field would go unnoticed there.
 */
class ConnectionSettingsCopyCompletenessTest {

    @Test
    void copyConstructorCopiesEveryPersistedField() throws Exception {
        ConnectionSettings defaults = new ConnectionSettings();
        ConnectionSettings source = ConnectionSettingsFieldValues.populatedDistinct(1);

        ConnectionSettings copy = new ConnectionSettings(source);

        assertThat(ConnectionSettingsFieldValues.persistedFields()).isNotEmpty();
        for (Field field : ConnectionSettingsFieldValues.persistedFields()) {
            // A value equal to the default could never prove the field was copied.
            assertWithMessage("Test bug: the populated value of '%s' equals the default", field.getName())
                    .that(Objects.equals(field.get(source), field.get(defaults)))
                    .isFalse();
            assertWithMessage("ConnectionSettings(ConnectionSettings) does not copy '%s'", field.getName())
                    .that(field.get(copy))
                    .isEqualTo(field.get(source));
        }
    }

    @Test
    void theGuardCoversTheAnsiPaletteFlag() {
        assertThat(ConnectionSettingsFieldValues.persistedFields().stream().map(Field::getName).toList())
                .contains("ansiPaletteCustomized");
    }

    @Test
    void theGuardCoversTheOwnSettingsFlag() {
        // useGlobalSettings=false is what makes a connection draw with its own settings; a copy that
        // reset it would silently switch the connection back to the global settings.
        assertThat(ConnectionSettingsFieldValues.persistedFields().stream().map(Field::getName).toList())
                .contains("useGlobalSettings");
        ConnectionSettings own = new ConnectionSettings();
        own.setUseGlobalSettings(false);
        assertThat(new ConnectionSettings(own).isUseGlobalSettings()).isFalse();
    }
}
