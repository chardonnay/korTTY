package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * A palette row's title cannot take the whole row. A tab's title can come from the program in the
 * terminal and a teamwork connection's name from someone else's file, each up to the 120 characters
 * a row shows; at that length the title squeezed the detail beside it, which names the
 * {@code user@host} the row really connects to, down to a few letters. The title is cut at half the
 * row instead. The layout itself needs the toolkit (commandPaletteSmoke checks it on screen), so the
 * cap is pinned here as a pure function and against the source.
 */
class CommandPaletteRowTest {

    private static final Path SOURCE = Path.of("src/main/java/de/kortty/ui/CommandPalettePopup.java");

    @Test
    void aTitleTakesAtMostHalfTheRow() {
        assertThat(CommandPalettePopup.TITLE_SHARE).isEqualTo(0.5);
        assertThat(CommandPalettePopup.titleMaxWidth(600)).isEqualTo(300.0);
        // Before the first layout the row is narrower than its inset: no negative maximum.
        assertThat(CommandPalettePopup.titleMaxWidth(-20)).isEqualTo(0.0);
    }

    @Test
    void everyRowCapsItsTitle() throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(source).contains("title.maxWidthProperty().bind(Bindings.createDoubleBinding(\n"
            + "                () -> titleMaxWidth(root.getPrefWidth()), root.prefWidthProperty()));");
        assertThat(source).contains("root.prefWidthProperty().bind(cell.widthProperty().subtract(ROW_INSET));");
    }
}
