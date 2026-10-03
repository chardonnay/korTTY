package de.kortty.model;

import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.assertThrows;

/** The ANSI palette fields of {@link ConnectionSettings}: indexed access, the flag, and the palette copy. */
class ConnectionSettingsAnsiPaletteTest {

    /** What {@link ConnectionSettings#copyTerminalPaletteFrom} must take over — and nothing else. */
    private static final Set<String> PALETTE_FIELDS = Set.of(
            "ansiBlack", "ansiRed", "ansiGreen", "ansiYellow", "ansiBlue", "ansiMagenta", "ansiCyan", "ansiWhite",
            "ansiBrightBlack", "ansiBrightRed", "ansiBrightGreen", "ansiBrightYellow", "ansiBrightBlue",
            "ansiBrightMagenta", "ansiBrightCyan", "ansiBrightWhite",
            "ansiPaletteCustomized", "selectionColor", "boldAsBright");

    private static String colour(int index, boolean bright) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", index * 16, bright ? 0xAA : 0x55, 0x0F);
    }

    @Test
    void setAnsiColorRoundTripsThroughGetAnsiColorForAllSixteen() {
        ConnectionSettings settings = new ConnectionSettings();
        for (int i = 0; i < ConnectionSettings.ANSI_COLOR_COUNT; i++) {
            settings.setAnsiColor(i, false, colour(i, false));
            settings.setAnsiColor(i, true, colour(i, true));
        }
        for (int i = 0; i < ConnectionSettings.ANSI_COLOR_COUNT; i++) {
            assertThat(settings.getAnsiColor(i, false)).isEqualTo(colour(i, false));
            assertThat(settings.getAnsiColor(i, true)).isEqualTo(colour(i, true));
        }
        // The named accessors see the same fields.
        assertThat(settings.getAnsiRed()).isEqualTo(colour(1, false));
        assertThat(settings.getAnsiBrightWhite()).isEqualTo(colour(7, true));
    }

    @Test
    void setAnsiColorRejectsAnIndexOutsideTheEightColours() {
        ConnectionSettings settings = new ConnectionSettings();
        assertThrows(IllegalArgumentException.class, () -> settings.setAnsiColor(8, false, "#000000"));
        assertThrows(IllegalArgumentException.class, () -> settings.setAnsiColor(-1, true, "#000000"));
        assertThat(settings.getAnsiWhite()).isEqualTo("#E5E5E5");
    }

    @Test
    void theFlagDefaultsToFalseSoUntouchedSettingsKeepTheBuiltInLook() {
        assertThat(new ConnectionSettings().isAnsiPaletteCustomized()).isFalse();
        assertThat(new ConnectionSettings().getSelectionColor()).isEqualTo(ConnectionSettings.DEFAULT_SELECTION_COLOR);
    }

    @Test
    void copyConstructorCopiesTheColoursAndTheFlag() {
        ConnectionSettings source = new ConnectionSettings();
        for (int i = 0; i < ConnectionSettings.ANSI_COLOR_COUNT; i++) {
            source.setAnsiColor(i, false, colour(i, false));
            source.setAnsiColor(i, true, colour(i, true));
        }
        source.setAnsiPaletteCustomized(true);

        ConnectionSettings copy = new ConnectionSettings(source);

        assertThat(copy.isAnsiPaletteCustomized()).isTrue();
        for (int i = 0; i < ConnectionSettings.ANSI_COLOR_COUNT; i++) {
            assertThat(copy.getAnsiColor(i, false)).isEqualTo(colour(i, false));
            assertThat(copy.getAnsiColor(i, true)).isEqualTo(colour(i, true));
        }
    }

    @Test
    void copyTerminalPaletteFromCopiesOnlyThePaletteFields() throws Exception {
        ConnectionSettings source = ConnectionSettingsFieldValues.populatedDistinct(1);
        ConnectionSettings target = ConnectionSettingsFieldValues.populatedDistinct(2);
        ConnectionSettings targetBefore = new ConnectionSettings(target);

        target.copyTerminalPaletteFrom(source);

        for (Field field : ConnectionSettingsFieldValues.persistedFields()) {
            Object expected = PALETTE_FIELDS.contains(field.getName()) ? field.get(source) : field.get(targetBefore);
            assertWithMessage("field '%s' after copyTerminalPaletteFrom", field.getName())
                    .that(Objects.equals(field.get(target), expected))
                    .isTrue();
        }
        for (String name : PALETTE_FIELDS) {
            // Guards the list against renamed fields that would silently drop out of the check above.
            assertThat(ConnectionSettings.class.getDeclaredField(name)).isNotNull();
        }
    }

    @Test
    void copyTerminalPaletteFromIgnoresNull() {
        ConnectionSettings target = new ConnectionSettings();
        target.setAnsiRed("#123456");
        target.copyTerminalPaletteFrom(null);
        assertThat(target.getAnsiRed()).isEqualTo("#123456");
    }
}
