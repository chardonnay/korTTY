package de.kortty.core;

import de.kortty.core.ConnectionGroupColors.Inherited;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Folder (group) tab colors: a connection takes the color of its group or of the nearest group
 * above it with one, and renaming or deleting a folder in the Connection Manager moves or drops the
 * colors exactly as it moves or deletes the connections, so a colored production folder never loses
 * its color by a rename and a deleted folder's color never comes back on a new folder of that name.
 */
public class ConnectionGroupColorsTest {

    private static Map<String, String> colors(String... pathsAndColors) {
        Map<String, String> colors = new LinkedHashMap<>();
        for (int i = 0; i < pathsAndColors.length; i += 2) {
            colors.put(pathsAndColors[i], pathsAndColors[i + 1]);
        }
        return colors;
    }

    @Test
    void theKeyIsThePathWithTrimmedSegmentsAndNoGroupHasNone() {
        assertThat(ConnectionGroupColors.key("Production")).isEqualTo("Production");
        assertThat(ConnectionGroupColors.key(" Work / Production ")).isEqualTo("Work/Production");
        assertThat(ConnectionGroupColors.key("Work//Production/")).isEqualTo("Work/Production");
        for (String none : new String[] {null, "", "   ", "/", " / / "}) {
            assertWithMessage("key(%s)", none).that(ConnectionGroupColors.key(none)).isNull();
        }
    }

    @Test
    void aGroupTakesItsOwnColorElseTheNearestColoredGroupAbove() {
        Map<String, String> stored = colors("Work", "#1976D2", "Work/Production", "#d32f2f");

        assertThat(ConnectionGroupColors.inherited("Work/Production", stored::get))
                .isEqualTo(new Inherited("#D32F2F", "Work/Production"));
        assertThat(ConnectionGroupColors.inherited("Work/Production/DB/EU", stored::get))
                .isEqualTo(new Inherited("#D32F2F", "Work/Production"));
        assertThat(ConnectionGroupColors.inherited("Work/Test", stored::get))
                .isEqualTo(new Inherited("#1976D2", "Work"));
        assertThat(ConnectionGroupColors.inherited("Home", stored::get)).isNull();
    }

    @Test
    void thereAreNoDefaultColors() {
        assertThat(ConnectionGroupColors.inherited("Production", path -> null)).isNull();
        assertThat(ConnectionGroupColors.inherited("Production", null)).isNull();
        assertThat(ConnectionGroupColors.inherited(null, path -> "#D32F2F")).isNull();
        assertThat(ConnectionGroupColors.inherited("  ", path -> "#D32F2F")).isNull();
    }

    @Test
    void aValueThatIsNotAHexColorIsSkippedOnTheWayUp() {
        Map<String, String> stored = colors("Ops", "#388E3C", "Ops/Prod", "#8B0000; -fx-background-image: url(x)");

        assertThat(ConnectionGroupColors.inherited("Ops/Prod", stored::get)).isEqualTo(new Inherited("#388E3C", "Ops"));
    }

    @Test
    void onlyWholeSegmentsAndTheExactCaseMatch() {
        Map<String, String> stored = colors("Production", "#D32F2F");

        assertThat(ConnectionGroupColors.inherited("Production2", stored::get)).isNull();
        assertThat(ConnectionGroupColors.inherited("production", stored::get)).isNull();
        assertThat(ConnectionGroupColors.inherited(" Production / DB ", stored::get))
                .isEqualTo(new Inherited("#D32F2F", "Production"));
    }

    @Test
    void theDialogNamesOnlyTheColorFromAboveNotTheFoldersOwn() {
        Map<String, String> stored = colors("Work", "#1976D2", "Work/Production", "#D32F2F");

        assertThat(ConnectionGroupColors.inheritedFromAbove("Work/Production", stored::get))
                .isEqualTo(new Inherited("#1976D2", "Work"));
        assertThat(ConnectionGroupColors.inheritedFromAbove("Work/Production/DB", stored::get))
                .isEqualTo(new Inherited("#D32F2F", "Work/Production"));
        assertWithMessage("a top-level folder has nothing above it")
                .that(ConnectionGroupColors.inheritedFromAbove("Work", stored::get)).isNull();
        assertThat(ConnectionGroupColors.inheritedFromAbove(null, stored::get)).isNull();
    }

    @Test
    void renamingAFolderMovesItsColorAndThoseOfItsSubfolders() {
        Map<String, String> stored = colors(
                "Prod", "#D32F2F",
                "Prod/DB", "#7B1FA2",
                "Prod2", "#388E3C",
                "Test", "#FBC02D");

        Map<String, String> renamed = ConnectionGroupColors.renamed(stored, "Prod", "Live");

        assertThat(renamed).containsExactly(
                "Live", "#D32F2F",
                "Live/DB", "#7B1FA2",
                "Prod2", "#388E3C",
                "Test", "#FBC02D");
        assertWithMessage("the stored colors are not changed in place")
                .that(stored).containsKey("Prod");
    }

    @Test
    void renamingASubfolderLeavesItsParentAlone() {
        Map<String, String> stored = colors("Work", "#1976D2", "Work/Prod", "#D32F2F", "Work/Prod/EU", "#F57C00");

        assertThat(ConnectionGroupColors.renamed(stored, "Work/Prod", "Work/Live")).containsExactly(
                "Work", "#1976D2",
                "Work/Live", "#D32F2F",
                "Work/Live/EU", "#F57C00");
    }

    @Test
    void renamingIntoAColoredFolderKeepsThatFoldersColor() {
        Map<String, String> stored = colors("Prod", "#D32F2F", "Prod/DB", "#7B1FA2", "Misc", "#388E3C");

        Map<String, String> merged = ConnectionGroupColors.renamed(stored, "Prod", "Misc");

        assertWithMessage("the connections already in Misc keep their color; the moved subfolder keeps its own")
                .that(merged).containsExactly("Misc", "#388E3C", "Misc/DB", "#7B1FA2");
    }

    @Test
    void renamingWithASlashMovesTheColorsLikeTheConnections() {
        // The rename dialog appends the new name to the parent, so "A" renamed to "A/B" puts the
        // connections of A/B into A/B/B: the colors follow the same rewrite.
        Map<String, String> stored = colors("A", "#D32F2F", "A/B", "#1976D2");

        assertThat(ConnectionGroupColors.renamed(stored, "A", "A/B"))
                .containsExactly("A/B", "#D32F2F", "A/B/B", "#1976D2");
    }

    @Test
    void aRenameToTheSameNameOrWithoutAGroupChangesNothing() {
        Map<String, String> stored = colors("Prod", "#D32F2F");

        assertThat(ConnectionGroupColors.renamed(stored, "Prod", " Prod ")).containsExactly("Prod", "#D32F2F");
        assertThat(ConnectionGroupColors.renamed(stored, "", "Live")).containsExactly("Prod", "#D32F2F");
        assertThat(ConnectionGroupColors.renamed(stored, "Prod", null)).containsExactly("Prod", "#D32F2F");
        assertThat(ConnectionGroupColors.renamed(null, "Prod", "Live")).isEmpty();
    }

    @Test
    void deletingAFolderDropsItsColorAndThoseOfItsSubfolders() {
        Map<String, String> stored = colors(
                "Prod", "#D32F2F",
                "Prod/DB", "#7B1FA2",
                "Prod2", "#388E3C",
                "Work/Prod", "#F57C00");

        assertThat(ConnectionGroupColors.deleted(stored, "Prod"))
                .containsExactly("Prod2", "#388E3C", "Work/Prod", "#F57C00");
        assertThat(ConnectionGroupColors.deleted(stored, "Prod/DB"))
                .containsExactly("Prod", "#D32F2F", "Prod2", "#388E3C", "Work/Prod", "#F57C00");
        assertThat(ConnectionGroupColors.deleted(stored, " ")).isEqualTo(stored);
    }

    @Test
    void storedColorsAreNormalizedAndUnusableEntriesDropped() {
        Map<String, String> raw = new HashMap<>();
        raw.put(" Work / Prod ", "#d32f2f");
        raw.put("Lab", "#abc");
        raw.put("Broken", "red");
        raw.put("  ", "#388E3C");
        raw.put(null, "#388E3C");
        raw.put("Empty", null);

        assertThat(ConnectionGroupColors.copyOf(raw)).containsExactly("Work/Prod", "#D32F2F", "Lab", "#AABBCC");
        assertThat(ConnectionGroupColors.copyOf(null)).isEmpty();
    }
}
